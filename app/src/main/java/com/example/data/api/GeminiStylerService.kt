package com.example.data.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.util.Base64
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.example.BuildConfig
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Streaming
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/*
 * v8 - "Commercial Multi-Device Generative UI Synthesis Engine"
 *
 * Key Upgrades in v8:
 * 1. Explicit Tri-Stage Model Routing (Fixes Review Pass Model Bug):
 *    - Pass 1 (Concept): CONCEPT_MODEL ("gemini-3.5-flash-lite")
 *    - Pass 2 (Synthesis): SYNTHESIS_MODEL ("gemini-3.8-flash")
 *    - Pass 3 (Review): REVIEW_MODEL ("gemini-3.5-flash-lite") — explicitly enforced via `isReviewPass = true`
 *      and `android.net.Uri` domain normalization so SPA URL changes never cause a fallback to 3.8-flash.
 * 2. Device-Agnostic Responsive Containment & Live Geometry Guard:
 *    - Works across all Android form factors (compact phones, large phones, foldables, tablets, landscape/portrait).
 *    - Post-render `clampOutOfBoundsElements()` inspects live `getBoundingClientRect()` against `clientWidth`
 *      and automatically pulls in any element or grid track that overflows the viewport.
 * 3. Zero-Dependency DNS-over-HTTPS Fallback:
 *    - Uses only standard `okhttp3` + `org.json.JSONObject` (matching your build.gradle.kts) to resolve
 *      `generativelanguage.googleapis.com` via 8.8.8.8 / 1.1.1.1 whenever carrier DNS throws UnknownHostException.
 */

// ───────────────────────── Gemini wire models ─────────────────────────

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null,
    val generationConfig: GenerationConfig? = null
)

@JsonClass(generateAdapter = true)
data class Content(val parts: List<Part>)

@JsonClass(generateAdapter = true)
data class Part(val text: String? = null, val inlineData: InlineData? = null)

@JsonClass(generateAdapter = true)
data class InlineData(val mimeType: String, val data: String)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    val temperature: Float = 0.7f,
    val maxOutputTokens: Int? = null,
    val responseMimeType: String? = null,
    val topP: Float? = null,
    val thinkingConfig: ThinkingConfig? = null
)

@JsonClass(generateAdapter = true)
data class ThinkingConfig(
    val thinkingBudget: Int? = 0
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(val candidates: List<Candidate>? = null)

@JsonClass(generateAdapter = true)
data class Candidate(val content: Content? = null, val finishReason: String? = null)

interface GeminiApi {
    @POST("v1beta/models/{model}:generateContent")
    suspend fun generateContent(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse

    @Streaming
    @POST("v1beta/models/{model}:streamGenerateContent?alt=sse")
    suspend fun streamGenerateContent(
        @Path("model") model: String,
        @Header("x-goog-api-key") apiKey: String,
        @Body request: GeminiRequest
    ): ResponseBody
}

// ───────────────────── Color & Contrast Utilities ─────────────────────

private fun sha1Hex(s: String) =
    MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

object Hex {
    private val SIX = Regex("^#[0-9a-fA-F]{6}$")
    private val THREE = Regex("^#[0-9a-fA-F]{3}$")

    fun norm(s: String?, fallback: String): String {
        var v = s?.trim() ?: return fallback
        if (THREE.matches(v)) v = "#" + v.substring(1).map { "$it$it" }.joinToString("")
        return if (SIX.matches(v)) v.lowercase(Locale.US) else fallback
    }

    fun rgb(h: String): IntArray =
        intArrayOf(h.substring(1, 3).toInt(16), h.substring(3, 5).toInt(16), h.substring(5, 7).toInt(16))

    fun toHex(r: Int, g: Int, b: Int): String =
        String.format(Locale.US, "#%02x%02x%02x", r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255))

    fun lum(h: String): Double {
        val c = rgb(h).map { v ->
            val x = v / 255.0
            if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4)
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]
    }

    fun contrast(a: String, b: String): Double {
        val x = lum(a)
        val y = lum(b)
        return (maxOf(x, y) + 0.05) / (minOf(x, y) + 0.05)
    }

    fun mix(a: String, b: String, t: Double): String {
        val p = rgb(a)
        val q = rgb(b)
        return toHex(
            (p[0] + (q[0] - p[0]) * t).roundToInt(),
            (p[1] + (q[1] - p[1]) * t).roundToInt(),
            (p[2] + (q[2] - p[2]) * t).roundToInt()
        )
    }
}

// ───────────────────── Generative UI Blueprint ─────────────────────

/**
 * Strips ::before/::after rules that inject readable words (e.g. content:"> SYS.RECORD").
 * Icons, bullets and attr() content are preserved.
 */
internal fun stripInventedLabels(css: String): String =
    css.replace(
        Regex("""[^{}]*::?(?:before|after)\s*\{[^{}]*content\s*:\s*(["'])[^"'{}]*[A-Za-z]{3,}[^"'{}]*\1[^{}]*\}""", RegexOption.IGNORE_CASE),
        ""
    )

data class UIBlueprint(
    val id: String,
    val name: String,
    val dark: Boolean,
    val bg: String,
    val surface: String,
    val surface2: String,
    val fg: String,
    val ink: String,
    val accent: String,
    val remapSurfaces: Boolean,
    val customCss: String,
    val backdropHtml: String,
    val hudHtml: String,
    val customJs: String,
    val concept: String = "",
    val architecture: String = "restyle", // "restyle" (default) or "app_shell"
    val needsHud: Boolean = false,
    val hudReason: String = "",
    val fingerprint: String = ""
) {
    fun toJson(): String = JSONObject()
        .put("concept", concept)
        .put("fingerprint", fingerprint)
        .put("architecture", architecture)
        .put("needsHud", needsHud)
        .put("hudReason", hudReason)
        .put("id", id)
        .put("name", name)
        .put("dark", dark)
        .put("bg", bg)
        .put("surface", surface)
        .put("surface2", surface2)
        .put("fg", fg)
        .put("ink", ink)
        .put("accent", accent)
        .put("remapSurfaces", remapSurfaces)
        .put("customCss", customCss)
        .put("backdropHtml", backdropHtml)
        .put("hudHtml", hudHtml)
        .put("customJs", customJs)
        .toString()

    companion object {
        const val RUNTIME_VERSION = 8

        private fun jsonSlice(raw: String): String {
            var s = raw.trim()
            Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE).find(s)?.let {
                s = it.groupValues[1]
            }
            val a = s.indexOf('{')
            val b = s.lastIndexOf('}')
            return if (a >= 0 && b > a) s.substring(a, b + 1) else s
        }

        fun parse(raw: String, prompt: String, allowedArchitecture: String = "restyle"): UIBlueprint? {
            val j = try { JSONObject(jsonSlice(raw)) } catch (_: Exception) { return null }
            val dark = j.optBoolean("dark", true)
            val white = "#ffffff"
            val black = "#000000"

            val bg = Hex.norm(j.optString("bg"), if (dark) "#080b12" else "#fbf9f5")
            val surface = Hex.norm(j.optString("surface"), Hex.mix(bg, if (dark) white else black, 0.08))
            val surface2 = Hex.norm(j.optString("surface2"), Hex.mix(surface, if (dark) white else black, 0.08))

            var fg = Hex.norm(j.optString("fg"), if (dark) "#f0f6ff" else "#121316")
            if (Hex.contrast(fg, bg) < 4.5) {
                fg = if (Hex.lum(bg) < 0.4) "#f5f8ff" else "#111111"
            }
            val ink = Hex.norm(j.optString("ink"), "#111215")
            val accent = Hex.norm(j.optString("accent"), if (dark) "#00e5ff" else "#0055ff")

            val isAppShellApproved = allowedArchitecture.equals("app_shell", ignoreCase = true)
            val needsHud = isAppShellApproved &&
                j.optBoolean("needsHud", false) &&
                j.optString("hudReason").isNotBlank() &&
                j.optString("hudHtml").isNotBlank()

            return UIBlueprint(
                id = sha1Hex("v$RUNTIME_VERSION|${prompt.trim().lowercase(Locale.US)}|${j.optString("customCss")}|${j.optString("backdropHtml")}|${j.optString("hudHtml")}|${j.optString("customJs")}").take(12),
                name = j.optString("name", prompt).take(50),
                dark = dark,
                bg = bg,
                surface = surface,
                surface2 = surface2,
                fg = fg,
                ink = ink,
                accent = accent,
                remapSurfaces = j.optBoolean("remapSurfaces", true),
                customCss = stripInventedLabels(j.optString("customCss", "")),
                backdropHtml = j.optString("backdropHtml", ""),
                hudHtml = if (needsHud) j.optString("hudHtml", "") else "",
                customJs = j.optString("customJs", ""),
                concept = j.optString("concept", "").take(300),
                architecture = if (isAppShellApproved) "app_shell" else "restyle",
                needsHud = needsHud,
                hudReason = j.optString("hudReason", "").take(200),
                fingerprint = j.optString("fingerprint", "").take(120)
            )
        }
    }
}

typealias ThemeSpec = UIBlueprint

object SitePacks {
    val skipHosts = mutableSetOf("accounts.google.com")
}

// ───────────────────── Universal Generative Runtime + MydesBridge ─────────────────────

object MydesRuntime {

    private const val CORE = """
if(window.__mydes && window.__mydes.id()===BP.id) return;
if(window.__mydes && window.__mydes.clear){try{window.__mydes.clear();}catch(e){}}
if(BP.skip && BP.skip.indexOf(location.hostname)>=0) return;

var cfg = BP;
var changes=[], EB=new WeakMap(), done=new WeakSet(), jobs=[], draining=false;
var tagDirty=true, lastTag=0, tagDelay=120, BG, FG, INK;
var cleanupFns=[], mo=null;
var nodeUidCounter=0;

function hex(h){return {r:parseInt(h.substr(1,2),16),g:parseInt(h.substr(3,2),16),b:parseInt(h.substr(5,2),16),a:1};}
function rgbStr(c){return 'rgb('+Math.round(c.r)+','+Math.round(c.g)+','+Math.round(c.b)+')';}
function configure(){BG=hex(cfg.bg);FG=hex(cfg.fg);INK=hex(cfg.ink);}
configure();

/* ---------- 1. MydesBridge: Universal DOM Control & State-Safe Proxy API ---------- */
window.MydesBridge = {
  extractNodes: function(customSelector) {
    var sel = customSelector || '[data-mydes="card"], [data-mydes="item"], article, section > div, li, tr';
    var nodes = document.querySelectorAll(sel);
    var list = [];
    for(var i=0; i<nodes.length && list.length<60; i++) {
      var n = nodes[i];
      if(n.closest('#__mydes_hud,#__mydes_backdrop,nav,header,footer')) continue;
      var r = n.getBoundingClientRect();
      if(r.width < 60 || r.height < 24) continue;
      var txt = (n.textContent || '').replace(/\s+/g, ' ').trim();
      if(txt.length < 6) continue;

      var uid = n.getAttribute('data-mydes-uid');
      if(!uid) {
        uid = 'm-node-' + (++nodeUidCounter);
        n.setAttribute('data-mydes-uid', uid);
      }
      var headingEl = n.querySelector('h1,h2,h3,h4,h5,h6,[data-mydes="heading"],[data-mydes="title"],strong,b,a[href]');
      var imgEl = n.querySelector('img,picture img');
      var vidEl = n.querySelector('video');
      var linkEl = n.tagName === 'A' ? n : n.querySelector('a[href]');
      var metaEl = n.querySelector('[data-mydes="meta"],[data-mydes="price"],time,small,sub,sup');

      var titleText = (headingEl ? headingEl.textContent : txt.slice(0, 80)).replace(/\s+/g, ' ').trim();
      var bodyText = txt.replace(titleText, '').trim().slice(0, 180);

      list.push({
        uid: uid,
        title: titleText,
        body: bodyText,
        meta: metaEl ? metaEl.textContent.replace(/\s+/g, ' ').trim() : '',
        img: imgEl ? (imgEl.currentSrc || imgEl.src || imgEl.getAttribute('data-src') || '') : '',
        hasVideo: !!vidEl,
        href: linkEl ? linkEl.href : ''
      });
    }
    return list;
  },

  extractFeed: function(sel) { return window.MydesBridge.extractNodes(sel); },

  triggerNode: function(uid, subSelector) {
    if(!uid) return false;
    var el = document.querySelector('[data-mydes-uid="' + CSS.escape(uid) + '"]');
    if(!el) return false;
    var target = (subSelector ? el.querySelector(subSelector) : null) || el.querySelector('a[href],button,[role="button"],input') || el;
    target.scrollIntoView({behavior:'smooth', block:'center'});
    target.focus();
    var opts = {bubbles: true, cancelable: true, view: window};
    target.dispatchEvent(new PointerEvent('pointerdown', opts));
    target.dispatchEvent(new MouseEvent('mousedown', opts));
    target.dispatchEvent(new PointerEvent('pointerup', opts));
    target.dispatchEvent(new MouseEvent('mouseup', opts));
    target.click();
    return true;
  },

  triggerItem: function(uid, subSelector) { return window.MydesBridge.triggerNode(uid, subSelector); },

  loadMore: function(onLoaded) {
    var prevCount = document.body ? document.body.querySelectorAll('*').length : 0;
    window.scrollTo({top: document.documentElement.scrollHeight, behavior: 'instant'});
    window.dispatchEvent(new Event('scroll', {bubbles: true}));
    var attempts = 0;
    var check = setInterval(function() {
      attempts++;
      var curCount = document.body ? document.body.querySelectorAll('*').length : 0;
      if(curCount > prevCount + 5 || attempts > 15) {
        clearInterval(check);
        if(typeof onLoaded === 'function') onLoaded(window.MydesBridge.extractNodes());
      }
    }, 200);
  },

  proxyClick: function(sel) {
    var l = document.querySelectorAll(sel);
    for(var i=0; i<l.length; i++) {
      var e = l[i];
      if(e.closest('#__mydes_hud,#__mydes_backdrop')) continue;
      var r = e.getBoundingClientRect();
      if(r.width > 0 && r.height > 0) {
        e.scrollIntoView({behavior:'smooth', block:'center'});
        e.focus();
        e.click();
        return true;
      }
    }
    return false;
  },

  proxySearch: function(query) {
    var inp = document.querySelector('input[type="search"],[role="search"] input,form input[type="text"],[data-mydes="search"] input,input[type="text"]');
    if(!inp) return false;
    inp.focus();
    var setter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
    if(setter) setter.call(inp, query); else inp.value = query;
    inp.dispatchEvent(new Event('input', {bubbles:true}));
    inp.dispatchEvent(new Event('change', {bubbles:true}));
    var form = inp.closest('form');
    if(form) {
      var btn = form.querySelector('button[type="submit"],input[type="submit"],[data-mydes="btn"]');
      if(btn) btn.click(); else form.submit();
    } else {
      inp.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', keyCode:13, bubbles:true}));
    }
    return true;
  },

  focusSearch: function() {
    var inp = document.querySelector('input[type="search"],[role="search"] input,[data-mydes="search"] input,input[type="text"]');
    if(inp) {
      inp.scrollIntoView({behavior:'smooth', block:'center'});
      inp.focus();
      inp.style.setProperty('outline', '4px solid ' + cfg.accent, 'important');
      return true;
    }
    return false;
  },

  zoom: function(delta) {
    var cur = parseFloat(document.documentElement.getAttribute('data-m-zoom') || '1');
    var next = Math.min(1.8, Math.max(0.8, cur + delta));
    document.documentElement.setAttribute('data-m-zoom', String(next));
    document.documentElement.style.setProperty('font-size', Math.round(next * 100) + '%', 'important');
  },

  speakPage: function(customText) {
    try {
      if(!window.speechSynthesis) return;
      window.speechSynthesis.cancel();
      var txt = customText;
      if(!txt) {
        var nodes = document.querySelectorAll('[data-mydes="title"],[data-mydes="heading"],p,h1,h2,h3');
        var arr = [];
        for(var i=0; i<nodes.length && arr.length<12; i++) {
          var t = (nodes[i].textContent||'').trim();
          if(t.length > 3) arr.push(t);
        }
        txt = arr.join('. ') || document.title;
      }
      var u = new SpeechSynthesisUtterance(txt.slice(0, 600));
      u.rate = 0.92;
      window.speechSynthesis.speak(u);
    } catch(e){}
  },

  enable3DTilt: function(intensity) {
    var mult = typeof intensity === 'number' ? intensity : 1.0;
    var raf = 0, lastY = window.scrollY;
    function onScroll() {
      if(raf) return;
      raf = requestAnimationFrame(function() {
        var sy = window.scrollY;
        var dy = Math.max(-18, Math.min(18, (sy - lastY) * 0.4 * mult));
        lastY = sy;
        document.documentElement.style.setProperty('--m-rx', dy.toFixed(2) + 'deg');
        document.documentElement.style.setProperty('--m-scroll', sy + 'px');
        raf = 0;
      });
    }
    var touchRaf = 0;
    function onTouch(e) {
      if(!e.touches || !e.touches[0]) return;
      var cx = e.touches[0].clientX;
      var cy = e.touches[0].clientY;
      if(touchRaf) return;
      touchRaf = requestAnimationFrame(function() {
        var ry = ((cx / Math.max(1, innerWidth)) - 0.5) * 22 * mult;
        var rx = (0.5 - (cy / Math.max(1, innerHeight))) * 16 * mult;
        document.documentElement.style.setProperty('--m-rx', rx.toFixed(2) + 'deg');
        document.documentElement.style.setProperty('--m-ry', ry.toFixed(2) + 'deg');
        touchRaf = 0;
      });
    }
    window.addEventListener('scroll', onScroll, {passive:true});
    window.addEventListener('touchmove', onTouch, {passive:true});
    cleanupFns.push(function() {
      window.removeEventListener('scroll', onScroll);
      window.removeEventListener('touchmove', onTouch);
      if(raf) cancelAnimationFrame(raf);
      if(touchRaf) cancelAnimationFrame(touchRaf);
    });
  },
  onCleanup: function(fn) { if(typeof fn === 'function') cleanupFns.push(fn); }
};

/* ---------- 2. Device-Agnostic Responsive Variables & Containment Stylesheets ---------- */
var eStyle = null;
function updateViewportVars() {
  var vw = document.documentElement.clientWidth || window.innerWidth || 360;
  var vh = document.documentElement.clientHeight || window.innerHeight || 640;
  document.documentElement.style.setProperty('--m-vw', vw + 'px');
  document.documentElement.style.setProperty('--m-vh', vh + 'px');
}

function applySheets() {
  updateViewportVars();
  /*
   * Multi-Device Responsive Containment:
   * - Uses `100%` (rather than `100vw` which ignores scrollbars/margins) to fit any screen size from 280px foldables
   *   up to 1280px+ tablets.
   * - Forces `min-width: 0` and `max-width: 100%` on all grid/flex descendants so child items never push past the
   *   available screen width on any device.
   * - Preserves infinite-scroll sentinels at the bottom of grids (`grid-column: 1 / -1`).
   */
  var universalSafetyCss =
    '/* 1. Multi-Device Viewport Containment */\n' +
    '*, *::before, *::after { box-sizing: border-box; }\n' +
    'html, body {\n' +
    '  width: 100% !important; max-width: 100% !important; overflow-x: hidden !important;\n' +
    '  overflow-y: visible !important; height: auto !important; min-height: 100% !important;\n' +
    '  margin-left: 0 !important; margin-right: 0 !important;\n' +
    '}\n' +
    'html body [data-mydes="main"], html body [data-mydes="header"], html body [data-mydes="nav"],\n' +
    'html body [data-mydes="footer"], html body [data-mydes="grid"] {\n' +
    '  width: 100% !important; max-width: 100% !important;\n' +
    '  margin-left: 0 !important; margin-right: 0 !important;\n' +
    '  box-sizing: border-box !important;\n' +
    '}\n' +
    'html body [data-mydes="grid"] > *,\n' +
    'html body [data-mydes="card"], html body [data-mydes="card"] *,\n' +
    'html body [data-mydes="item"], html body [data-mydes="item"] * {\n' +
    '  min-width: 0 !important; max-width: 100% !important;\n' +
    '  box-sizing: border-box !important;\n' +
    '}\n' +
    'html body [data-mydes="card"], html body [data-mydes="item"] {\n' +
    '  width: 100% !important; margin-left: 0 !important; margin-right: 0 !important;\n' +
    '  overflow: hidden !important;\n' +
    '}\n' +
    'html body img, html body video, html body canvas, html body svg, html body iframe {\n' +
    '  max-width: 100% !important; height: auto !important; box-sizing: border-box !important;\n' +
    '}\n' +
    '/* 2. Infinite Scroll Sentinel Preservation */\n' +
    'html body [data-mydes="grid"] > :not([data-mydes="card"]):not([data-mydes="item"]),\n' +
    'html body [data-mydes-sentinel="true"] {\n' +
    '  grid-column: 1 / -1 !important; width: 100% !important;\n' +
    '  min-height: 28px !important; display: block !important;\n' +
    '  visibility: visible !important; opacity: 1 !important;\n' +
    '}\n' +
    '/* 3. Universal Text Unclamping */\n' +
    'html body [data-mydes="heading"], html body [data-mydes="title"], html body [data-mydes-clamped="true"],\n' +
    'html body h1, html body h2, html body h3, html body h4 {\n' +
    '  -webkit-line-clamp: unset !important; line-clamp: unset !important;\n' +
    '  max-height: none !important; height: auto !important;\n' +
    '  overflow: visible !important; text-overflow: unset !important;\n' +
    '  white-space: normal !important; overflow-wrap: anywhere !important; word-break: break-word !important;\n' +
    '}\n' +
    '/* 4. Universal Media Overlay Separation */\n' +
    'html body [data-mydes-overlay-parent="true"] {\n' +
    '  display: flex !important; flex-direction: column !important; height: auto !important;\n' +
    '}\n' +
    'html body [data-mydes-overlay="true"] {\n' +
    '  position: static !important; inset: auto !important;\n' +
    '  width: 100% !important; height: auto !important;\n' +
    '  max-height: none !important; box-sizing: border-box !important;\n' +
    '  transform: none !important;\n' +
    '}\n';

  var baseVars = ':root{--m-bg:'+cfg.bg+';--m-surface:'+cfg.surface+';--m-surface2:'+cfg.surface2+
    ';--m-fg:'+cfg.fg+';--m-ink:'+cfg.ink+';--m-accent:'+cfg.accent+';--m-rx:0deg;--m-ry:0deg;--m-scroll:0px;}\n' +
    'html{background-color:var(--m-bg)!important;color:var(--m-fg)!important;}\n' +
    'html body{background-color:transparent!important;color:var(--m-fg)!important;position:relative;z-index:1;}\n' +
    '#__mydes_backdrop{position:fixed;inset:0;width:100%;height:100%;pointer-events:none!important;z-index:-1;overflow:hidden;transform:translateZ(0);will-change:transform;}\n' +
    '#__mydes_hud{position:fixed;z-index:2147483640;pointer-events:none;inset:0;transform:translateZ(0);}\n' +
    '#__mydes_hud *{pointer-events:auto;}\n';

  var fullCss = baseVars + '\n' + universalSafetyCss + '\n' + (cfg.customCss || '');
  var root = document.head || document.documentElement;
  if(!root) return;
  if(!eStyle || !eStyle.isConnected) {
    eStyle = document.createElement('style');
    eStyle.id = '__mydes_style';
    root.appendChild(eStyle);
  }
  if(eStyle.textContent !== fullCss) eStyle.textContent = fullCss;
}

/* ---------- 3. Custom Stage Layers (Backdrop + HUD + Sandbox JS) ---------- */
function mountLayers() {
  if(!document.body) return;
  var bd = document.getElementById('__mydes_backdrop');
  if(cfg.backdropHtml) {
    if(!bd) {
      bd = document.createElement('div');
      bd.id = '__mydes_backdrop';
      bd.setAttribute('aria-hidden', 'true');
      document.body.prepend(bd);
    }
    if(bd.getAttribute('data-m-id') !== cfg.id) {
      bd.innerHTML = cfg.backdropHtml;
      bd.setAttribute('data-m-id', cfg.id);
    }
  } else if(bd) { bd.remove(); }

  var hud = document.getElementById('__mydes_hud');
  if(cfg.hudHtml) {
    if(!hud) {
      hud = document.createElement('div');
      hud.id = '__mydes_hud';
      document.body.appendChild(hud);
    }
    if(hud.getAttribute('data-m-id') !== cfg.id) {
      hud.innerHTML = cfg.hudHtml;
      hud.setAttribute('data-m-id', cfg.id);
    }
  } else if(hud) { hud.remove(); }

  if(cfg.customJs && document.body.getAttribute('data-m-js') !== cfg.id) {
    document.body.setAttribute('data-m-js', cfg.id);
    try {
      var fn = new Function('MydesBridge', 'T', cfg.customJs);
      fn(window.MydesBridge, cfg);
    } catch(e) { console.warn('[Mydes CustomJS Error]', e); }
  }
}

/* ---------- 4. Geometry-Driven Universal DOM Tagger & Live Bounds Clamper ---------- */
function each(sel,fn){var l=document.querySelectorAll(sel);for(var i=0;i<l.length;i++)fn(l[i]);}
function put(e,r){if(e.getAttribute('data-mydes')!==r)e.setAttribute('data-mydes',r);}
function putNew(e,r){if(!e.hasAttribute('data-mydes'))e.setAttribute('data-mydes',r);}
var PRICE=/^\s*(?:(?:[\u0024\u20AC\u00A3\u00A5\u20B9]|USD|EUR|GBP|INR|Rs\.?)\s?\d[\d,.]*|\d[\d,.]*\s?(?:[\u0024\u20AC\u00A3\u00A5\u20B9]|USD|EUR|GBP|INR))\s*$/;
function inContent(e){return !!e.closest('main,article,section,[role="main"]');}
function sig(e){return e.tagName.toLowerCase()+'.'+(e.classList[0]||'')+'.'+(e.classList[1]||'');}

function isUnitLike(e){
  if(!e || e.nodeType!==1) return false;
  var tag = e.tagName.toLowerCase();
  if(/continuation|spinner|skeleton|loader|placeholder|sentinel/.test(tag + ' ' + (e.className||''))) {
    e.setAttribute('data-mydes-sentinel', 'true');
    return false;
  }
  if(e.closest('nav,footer,header,#__mydes_hud,#__mydes_backdrop,[data-mydes="nav"],[data-mydes="footer"],[data-mydes="header"]')) return false;
  var t = (e.textContent||'').trim().length;
  if(t < 6 || t > 1800) return false;
  var r = e.getBoundingClientRect();
  if(r.width < 60 || r.height < 24) return false;
  if(!e.querySelector('img,video,picture,canvas,svg') && e.parentElement) {
    var pr = e.parentElement.getBoundingClientRect();
    if(r.width < pr.width * 0.72 && e.parentElement.querySelector('img,video,picture')) return false;
  }
  return !!e.querySelector('a[href],img,picture,video,button') || t >= 35;
}

function normalizeUnitInternals(unit){
  var hasHeading = !!unit.querySelector('h1,h2,h3,h4,h5,h6,[data-mydes="heading"]');
  var mediaEl = unit.querySelector('img,video,picture');
  var mediaRect = mediaEl ? mediaEl.getBoundingClientRect() : null;

  var sub = unit.querySelectorAll('*');
  for(var i=0; i<sub.length && i<80; i++){
    var x = sub[i];
    var cs = getComputedStyle(x);

    if((cs.webkitLineClamp && cs.webkitLineClamp !== 'none') || cs.textOverflow === 'ellipsis') {
      x.setAttribute('data-mydes-clamped', 'true');
      if(x.parentElement && x.parentElement !== unit) x.parentElement.setAttribute('data-mydes-clamped', 'true');
    }

    if(mediaRect && mediaRect.width > 40 && mediaRect.height > 40 && (cs.position === 'absolute' || cs.position === 'fixed')) {
      var xr = x.getBoundingClientRect();
      var txtLen = (x.textContent || '').trim().length;
      var overlapsMedia = xr.left < mediaRect.right && xr.right > mediaRect.left && xr.top < mediaRect.bottom && xr.bottom > mediaRect.top;
      if(overlapsMedia && txtLen >= 8 && !x.contains(mediaEl)) {
        x.setAttribute('data-mydes-overlay', 'true');
        if(x.parentElement) x.parentElement.setAttribute('data-mydes-overlay-parent', 'true');
      }
    }

    if(!hasHeading && x.children.length === 0) {
      var t = (x.textContent||'').trim();
      if(t.length >= 8 && t.length <= 160 && !PRICE.test(t)) {
        x.setAttribute('data-mydes', 'heading');
        hasHeading = true;
      }
    }
  }
}

function findCollections(){
  var ps = document.body.querySelectorAll('*:not(#__mydes_hud *):not(#__mydes_backdrop *)'), n = 0;
  for(var i = ps.length - 1; i >= 0 && n < 3000; i--){
    var p = ps[i], k = p.children.length;
    if(k < 2 || k > 150) continue;
    n++;
    if(p.getAttribute('data-mydes') === 'collection' || p.getAttribute('data-mydes') === 'grid'){
      for(var q=0; q<k; q++){
        var c0 = p.children[q];
        if(!c0.hasAttribute('data-mydes') && isUnitLike(c0)){
          c0.setAttribute('data-mydes', 'card');
          normalizeUnitInternals(c0);
        }
      }
      continue;
    }
    if(p.closest('nav,footer,header,#__mydes_hud,#__mydes_backdrop,[data-mydes="nav"],[data-mydes="footer"],[data-mydes="header"]')) continue;
    if(p.querySelector('[data-mydes="item"],[data-mydes="card"]')) continue;

    var groups = {}, best = null;
    for(var j=0; j<k; j++){
      var s = sig(p.children[j]);
      (groups[s] = groups[s] || []).push(p.children[j]);
    }
    for(var g in groups){
      if(!best || groups[g].length > best.length) best = groups[g];
    }
    if(!best || best.length < 2 || best.length < k * 0.45) continue;
    var units = best.filter(isUnitLike);
    if(units.length < 2 || units.length < best.length * 0.55) continue;

    p.setAttribute('data-mydes', 'grid');
    p.setAttribute('data-m-size', units.length <= 8 ? 's' : 'l');
    units.forEach(function(u){
      u.setAttribute('data-mydes', 'card');
      normalizeUnitInternals(u);
    });
  }
}

function markPricesAndMeta(){
  var l = document.querySelectorAll('span,div,p,strong,b,em,ins,bdi,time'), n = 0;
  for(var i=0; i<l.length && n<400; i++){
    var e = l[i];
    if(e.closest('#__mydes_hud,#__mydes_backdrop')) continue;
    if(e.children.length > 2 || e.hasAttribute('data-mydes')) continue;
    var t = (e.textContent||'').trim();
    if(t.length > 24 || t.length < 2) continue;
    if(PRICE.test(t)){ e.setAttribute('data-mydes','price'); n++; }
  }
}

/*
 * Device-Agnostic Post-Render Geometry Clamper:
 * Measures actual rendered bounding boxes against the current device's `clientWidth`.
 * If any grid container, card, header, or child extends past the left (< 0) or right (> clientWidth)
 * edge of the screen on ANY device size, it automatically clamps its width, grid-template-columns,
 * or horizontal margins so nothing is ever cut off.
 */
function clampOutOfBoundsElements() {
  var vw = document.documentElement.clientWidth || window.innerWidth;
  if(vw <= 0 || !document.body) return;

  var grids = document.querySelectorAll('[data-mydes="grid"]');
  for(var g=0; g<grids.length; g++) {
    var grid = grids[g];
    var gr = grid.getBoundingClientRect();
    var gcs = getComputedStyle(grid);
    if(gr.right > vw + 2 || gr.left < -2 || grid.scrollWidth > vw + 2) {
      setProp(grid, 'width', '100%');
      setProp(grid, 'max-width', '100%');
      setProp(grid, 'margin-left', '0px');
      setProp(grid, 'margin-right', '0px');
      setProp(grid, 'padding-left', '6px');
      setProp(grid, 'padding-right', '6px');
      setProp(grid, 'box-sizing', 'border-box');
      if(gcs.display.indexOf('grid') >= 0) {
        var cols = (gcs.gridTemplateColumns || '').split(/\s+/).filter(Boolean).length;
        if(cols >= 2) {
          var safeCols = vw < 340 ? 1 : cols;
          setProp(grid, 'grid-template-columns', 'repeat(' + safeCols + ', minmax(0, 1fr))');
          setProp(grid, 'gap', '8px');
        }
      }
    }
  }

  var items = document.querySelectorAll('[data-mydes="card"], [data-mydes="item"], [data-mydes="header"], [data-mydes="search"]');
  for(var i=0; i<items.length && i<200; i++) {
    var el = items[i];
    var r = el.getBoundingClientRect();
    if(r.width <= 0) continue;
    if(r.right > vw + 2 || r.left < -2) {
      setProp(el, 'max-width', '100%');
      setProp(el, 'min-width', '0px');
      setProp(el, 'margin-left', '0px');
      setProp(el, 'margin-right', '0px');
      setProp(el, 'box-sizing', 'border-box');
      // If a parent grid wasn't tagged [data-mydes="grid"] but its children overflow horizontally, clamp the parent too
      var p = el.parentElement;
      if(p && p !== document.body) {
        var pcs = getComputedStyle(p);
        if(pcs.display.indexOf('grid') >= 0) {
          var pCols = (pcs.gridTemplateColumns || '').split(/\s+/).filter(Boolean).length;
          if(pCols >= 2) {
            setProp(p, 'grid-template-columns', 'repeat(' + (vw < 340 ? 1 : 2) + ', minmax(0, 1fr))');
            setProp(p, 'width', '100%');
            setProp(p, 'max-width', '100%');
            setProp(p, 'box-sizing', 'border-box');
          }
        }
      }
    }
  }
}

function retag(){
  if(!document.body) return;
  var t0 = performance.now();
  each('header,[role="banner"]', function(e){ if(!inContent(e)) put(e,'header'); });
  each('nav,[role="navigation"]', function(e){ put(e,'nav'); });
  each('footer,[role="contentinfo"]', function(e){ if(!inContent(e)) put(e,'footer'); });
  each('main,[role="main"],article', function(e){ putNew(e,'main'); });
  each('aside,[role="complementary"]', function(e){ putNew(e,'aside'); });
  each('form[role="search"],input[type="search"]', function(e){
    var f = e.tagName==='FORM' ? e : (e.closest('form,[role="search"]') || e.parentElement);
    if(f) putNew(f,'search');
  });

  if(performance.now() - t0 < 45) findCollections();

  each('h1', function(e){ if(!e.closest('#__mydes_hud')){ putNew(e,'title'); e.setAttribute('data-mydes-clamped','true'); } });
  each('h2,h3,h4', function(e){ if(!e.closest('#__mydes_hud')){ putNew(e,'heading'); e.setAttribute('data-mydes-clamped','true'); } });
  each('button,input[type="submit"],input[type="button"],[role="button"]', function(e){
    if(!e.closest('#__mydes_hud')) putNew(e,'btn');
  });
  each('input:not([type="hidden"]):not([type="submit"]):not([type="button"]):not([type="checkbox"]):not([type="radio"]),textarea,select', function(e){
    if(!e.closest('#__mydes_hud')) putNew(e,'field');
  });
  each('img,video,picture', function(e){ if(!e.closest('#__mydes_hud,#__mydes_backdrop')) putNew(e,'media'); });
  markPricesAndMeta();
  clampOutOfBoundsElements();
}

/* ---------- 5. Infinite Scroll & Resize Keeper ---------- */
function setupLifecycleKeepers() {
  var lastKick = 0;
  function checkBottom() {
    var now = Date.now();
    if(now - lastKick < 500) return;
    var scrollPos = window.scrollY + window.innerHeight;
    var docHeight = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
    if(docHeight - scrollPos < 450) {
      lastKick = now;
      var sentinels = document.querySelectorAll('[data-mydes-sentinel="true"], [class*="continuation"], [class*="spinner"], [class*="load-more"]');
      for(var i=0; i<sentinels.length; i++) {
        var s = sentinels[i];
        s.style.setProperty('display', 'block', 'important');
        s.style.setProperty('min-height', '28px', 'important');
      }
      window.dispatchEvent(new Event('scroll', {bubbles: true}));
      window.dispatchEvent(new Event('resize', {bubbles: true}));
    }
  }
  function onResize() {
    updateViewportVars();
    clampOutOfBoundsElements();
  }
  window.addEventListener('scroll', checkBottom, {passive: true});
  window.addEventListener('resize', onResize, {passive: true});
  cleanupFns.push(function() {
    window.removeEventListener('scroll', checkBottom);
    window.removeEventListener('resize', onResize);
  });
}

/* ---------- 6. Page Outline Extractor ---------- */
function summary(){
  try{retag();}catch(e){}
  var counts={},samples={};
  each('[data-mydes]',function(e){
    var r=e.getAttribute('data-mydes');
    counts[r]=(counts[r]||0)+1;
    if(!samples[r] && r!=='media'){
      var txt=(e.textContent||'').replace(/\s+/g,' ').trim().slice(0,50);
      var cls=e.tagName.toLowerCase()+(e.id?'#'+e.id:'')+(e.classList[0]?'.'+e.classList[0]:'');
      samples[r]=cls+(txt?' ("'+txt+'")':'');
    }
  });
  var vw = document.documentElement.clientWidth || innerWidth;
  var vh = document.documentElement.clientHeight || innerHeight;
  return JSON.stringify({
    url:location.href,
    host:location.hostname,
    title:(document.title||'').slice(0,80),
    viewport:{w:vw,h:vh},
    roleCounts:counts,
    roleSamples:samples
  });
}

/* ---------- 7. Surface Remapper & Contrast Guard ---------- */
var SKIPTAG=/^(SCRIPT|STYLE|NOSCRIPT|IMG|SVG|PATH|VIDEO|CANVAS|IFRAME|PICTURE|INPUT|TEXTAREA|SELECT|OPTION|BR|HR|META|LINK|TITLE|HEAD|SOURCE|TEMPLATE|BUTTON)$/;
var SURFACE_ROLE=/^(card|item|header|nav|footer|hero|aside)$/;
function rgba(s){
  var m=/rgba?\(([^)]+)\)/.exec(s||'');
  if(!m)return null;
  var p=m[1].split(/[ ,\/]+/).filter(Boolean).map(parseFloat);
  if(p.length<3||p.some(isNaN))return null;
  return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1};
}
function lum(c){function f(v){v/=255;return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4);}return 0.2126*f(c.r)+0.7152*f(c.g)+0.0722*f(c.b);}
function contrast(a,b){var x=lum(a),y=lum(b);return (Math.max(x,y)+0.05)/(Math.min(x,y)+0.05);}
function blend(f,b){var a=f.a;return {r:f.r*a+b.r*(1-a),g:f.g*a+b.g*(1-a),b:f.b*a+b.b*(1-a),a:1};}

function setProp(el,p,v){
  if(changes.length>35000)return;
  changes.push({el:el,p:p,v:el.style.getPropertyValue(p),pr:el.style.getPropertyPriority(p)});
  el.style.setProperty(p,v,'important');
}
function undoAll(){
  for(var i=changes.length-1;i>=0;i--){
    var c=changes[i];
    try{if(c.v)c.el.style.setProperty(c.p,c.v,c.pr);else c.el.style.removeProperty(c.p);}catch(e){}
  }
  changes=[];EB=new WeakMap();done=new WeakSet();
  for(var j=0;j<cleanupFns.length;j++){try{cleanupFns[j]();}catch(e){}}
  cleanupFns=[];
}
function hasBgImage(cs){return !!cs.backgroundImage&&cs.backgroundImage!=='none';}
function eff(el){
  if(!el||el.nodeType!==1)return BG;
  if(EB.has(el))return EB.get(el);
  var cs=getComputedStyle(el),v;
  if(hasBgImage(cs))v=BG;
  else{
    var c=rgba(cs.backgroundColor);
    if(c&&c.a>=0.85)v=c;
    else v=el.parentElement?eff(el.parentElement):BG;
  }
  EB.set(el,v);
  return v;
}
function ownText(el){for(var n=el.firstChild;n;n=n.nextSibling){if(n.nodeType===3&&/\S/.test(n.nodeValue))return true;}return false;}
function pickInk(bg){
  var cands=[FG,INK,{r:255,g:255,b:255},{r:0,g:0,b:0}],best=cands[0],bc=0;
  for(var i=0;i<cands.length;i++){var c=contrast(cands[i],bg);if(c>=4.5)return cands[i];if(c>bc){bc=c;best=cands[i];}}
  return best;
}
function handle(el){
  if(done.has(el))return;
  done.add(el);
  if(el.closest && el.closest('#__mydes_hud,#__mydes_backdrop'))return;
  var tag=el.tagName.toUpperCase();
  if(SKIPTAG.test(tag))return;
  var cs=getComputedStyle(el);

  if(tag!=='BODY' && tag!=='HTML' && (cs.overflowY==='hidden' || cs.overflowY==='auto' || cs.overflowY==='scroll')) {
    var rr=el.getBoundingClientRect();
    if(rr.width>=innerWidth*0.9 && rr.height>=innerHeight*0.8 && cs.position!=='fixed') {
      setProp(el,'overflow-y','visible');
      setProp(el,'height','auto');
      setProp(el,'max-height','none');
    }
  }

  if(cfg.backdropHtml && tag!=='BODY' && tag!=='HTML' && !el.hasAttribute('data-mydes') && cs.position!=='fixed' && !hasBgImage(cs)){
    var wb=rgba(cs.backgroundColor);
    if(wb&&wb.a>=0.5){
      var wr=el.getBoundingClientRect();
      if(wr.width>=innerWidth*0.95&&wr.height>=innerHeight*0.8){setProp(el,'background-color','transparent');EB.delete(el);}
    }
  }
  if(cfg.remapSurfaces && tag!=='BODY' && tag!=='HTML' && !hasBgImage(cs)){
    if(el.querySelector('img,video,picture,svg,[role="img"]')) return;
    var b=rgba(cs.backgroundColor);
    if(b&&b.a>=0.85&&((cfg.dark&&lum(b)>0.5)||(!cfg.dark&&lum(b)<0.2))){
      var role=el.getAttribute('data-mydes')||'';
      var r=el.getBoundingClientRect();
      if((r.width>=innerWidth*0.35&&r.height>=20)||SURFACE_ROLE.test(role)){
        setProp(el,'background-color',role==='card'?cfg.surface2:cfg.surface);
        EB.delete(el);
      }
    }
  }
  if(ownText(el)){
    var bg=eff(el);
    if(bg){
      var fg=rgba(getComputedStyle(el).color);
      if(fg&&fg.a>0.05){
        if(fg.a<1)fg=blend(fg,bg);
        if(contrast(fg,bg)<4.5)setProp(el,'color',rgbStr(pickInk(bg)));
      }
    }
  }
}

function enqueue(root){jobs.push({root:root,list:null,i:0});kick();}
function kick(){if(!draining){draining=true;setTimeout(drain,16);}}
function drain(){
  var t0=performance.now();
  if(tagDirty&&t0-lastTag>tagDelay){
    tagDirty=false;
    try{retag();mountLayers();}catch(e){}
    lastTag=performance.now();
    tagDelay=Math.min(800,Math.max(100,(lastTag-t0)*4));
  }
  while(jobs.length&&performance.now()-t0<12){
    var j=jobs[0];
    if(!j.list)j.list=[j.root].concat(Array.prototype.slice.call(j.root.querySelectorAll('*'),0,3500));
    while(j.i<j.list.length&&performance.now()-t0<12){try{handle(j.list[j.i]);}catch(e){}j.i++;}
    if(j.i>=j.list.length)jobs.shift();
  }
  if(jobs.length||tagDirty)setTimeout(drain,20);else{draining=false;try{clampOutOfBoundsElements();}catch(e){}}
}

function observe(){
  try{
    if(mo)mo.disconnect();
    mo=new MutationObserver(function(muts){
      tagDirty=true;
      var n=0;
      for(var i=0;i<muts.length&&n<120;i++){
        var a=muts[i].addedNodes;
        for(var j=0;j<a.length;j++){
          if(a[j].nodeType===1 && a[j].id!=='__mydes_backdrop' && a[j].id!=='__mydes_hud'){
            enqueue(a[j]);n++;
          }
        }
      }
      kick();
    });
    mo.observe(document.documentElement,{childList:true,subtree:true});
  }catch(e){}
}

function start(){
  applySheets();
  var go=function(){
    if(!document.body)return;
    retag();
    mountLayers();
    setupLifecycleKeepers();
    enqueue(document.body);
    observe();
  };
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',go);else go();
}

window.__mydes={
  id:function(){return cfg.id;},
  setBlueprint:function(nextBp){
    undoAll();
    cfg=nextBp;
    configure();
    if(document.body)document.body.removeAttribute('data-m-js');
    start();
  },
  outline:summary,
  clear:function(){
    undoAll();
    if(mo)mo.disconnect();
    var s=document.getElementById('__mydes_style');if(s)s.remove();
    var b=document.getElementById('__mydes_backdrop');if(b)b.remove();
    var h=document.getElementById('__mydes_hud');if(h)h.remove();
  }
};
start();
"""

    fun script(
        blueprint: UIBlueprint,
        skipHosts: Set<String> = SitePacks.skipHosts
    ): String {
        val bpJson = JSONObject(blueprint.toJson())
            .put("skip", JSONArray(skipHosts.toList()))
        return "(function(BP){$CORE\n})($bpJson);"
    }

    const val OUTLINE_PROBE_JS = """
        (function(){
          function getSel(e){
            if(!e || e===document.body || e===document.documentElement) return '';
            var s = e.tagName.toLowerCase();
            if(e.id && !/\d{4,}/.test(e.id)) return s + '#' + CSS.escape(e.id);
            var cl = (e.className && typeof e.className === 'string') ? e.className.trim().split(/\s+/).filter(Boolean) : [];
            if(cl.length) s += '.' + cl.slice(0,2).map(function(c){ return window.CSS && CSS.escape ? CSS.escape(c) : c; }).join('.');
            return s;
          }

          var vw = document.documentElement.clientWidth || innerWidth;
          var vh = document.documentElement.clientHeight || innerHeight;

          var counts={};
          ['header','nav','main','aside','footer','form','button','input','img','video','article','section','table','ul','ol','h1','h2','h3'].forEach(function(t){
            counts[t]=document.querySelectorAll(t).length;
          });

          var all = document.body ? document.body.querySelectorAll('*:not(script):not(style):not(noscript):not(svg):not(path)') : [];
          var groups = {};

          for(var i=0; i<all.length && i<3500; i++){
            var e = all[i];
            if(e.closest('#__mydes_hud,#__mydes_backdrop,nav,header,footer')) continue;
            if(e.children.length < 1 || e.children.length > 25) continue;

            var r = e.getBoundingClientRect();
            if(r.width < 60 || r.height < 24) continue;

            var t = (e.textContent || '').replace(/\s+/g,' ').trim();
            if(t.length < 10 || t.length > 1000) continue;

            var hasMedia = !!e.querySelector('img,video,picture,canvas');
            if(!hasMedia && e.parentElement) {
              var pr = e.parentElement.getBoundingClientRect();
              if(r.width < pr.width * 0.72 && e.parentElement.querySelector('img,video,picture')) continue;
            }

            var p = e.parentElement;
            if(!p || p === document.body) continue;
            var k = getSel(e);
            if(!k) continue;

            var parentSel = getSel(p);
            var cs = getComputedStyle(e);
            var pcs = getComputedStyle(p);
            var key = parentSel + ' > ' + k;

            var g = groups[key] || (groups[key] = {
              sel: k,
              parentSel: parentSel,
              n: 0,
              hasMedia: hasMedia,
              itemDisplay: cs.display + (cs.flexDirection && cs.display.indexOf('flex')>=0 ? ' ('+cs.flexDirection+')' : ''),
              parentDisplay: pcs.display,
              widthPct: Math.round((r.width / Math.max(1, vw)) * 100),
              sample: t.slice(0, 45)
            });
            g.n++;
          }

          var rep = Object.keys(groups).map(function(k){ return groups[k]; })
            .filter(function(g){ return g.n >= 3; })
            .sort(function(x,y){ return (y.hasMedia - x.hasMedia) || (y.n - x.n); })
            .slice(0, 5);

          var heads = [].slice.call(document.querySelectorAll('h1,h2,h3,[role="heading"]')).slice(0, 6)
            .map(function(h){ return (h.textContent || '').replace(/\s+/g,' ').trim().slice(0, 45); });

          var o;
          if(window.__mydes && window.__mydes.outline){
            try { o = JSON.parse(window.__mydes.outline()); } catch(e){}
          }
          if(!o) {
            o = { url: location.href, host: location.hostname, title: (document.title||'').slice(0, 80), viewport: { w: vw, h: vh }, counts: counts };
          }
          o.repeatedStructures = rep;
          o.headings = heads;
          return o;
        })();
    """

    const val CONTRAST_AUDIT_JS = """
        (function(){
          try{
            var vw = document.documentElement.clientWidth || innerWidth;
            var vh = document.documentElement.clientHeight || innerHeight;
            function P(c){var m=/rgba?\(([^)]+)\)/.exec(c||'');if(!m)return null;var p=m[1].split(/[ ,\/]+/).filter(Boolean).map(parseFloat);return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1};}
            function L(c){function f(v){v/=255;return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4);}return 0.2126*f(c.r)+0.7152*f(c.g)+0.0722*f(c.b);}
            function R(a,b){var x=L(a),y=L(b);if(x<y){var t=x;x=y;y=t;}return (x+0.05)/(y+0.05);}
            function mix(t,b){var a=t.a;return {r:t.r*a+b.r*(1-a),g:t.g*a+b.g*(1-a),b:t.b*a+b.b*(1-a),a:1};}
            function hx(c){function h(v){v=Math.max(0,Math.min(255,Math.round(v)));return (v<16?'0':'')+v.toString(16);}return '#'+h(c.r)+h(c.g)+h(c.b);}
            function sel(e){var s=e.tagName.toLowerCase();var d=e.getAttribute('data-mydes');if(d)s+='[data-mydes="'+d+'"]';var cl=e.classList;for(var i=0;i<cl.length&&i<2;i++){s+='.'+(window.CSS&&CSS.escape?CSS.escape(cl[i]):cl[i]);}return s;}
            var hasBackdrop=!!document.getElementById('__mydes_backdrop');
            var rootBg=P(getComputedStyle(document.documentElement).backgroundColor)||{r:255,g:255,b:255,a:1};
            if(rootBg.a<1)rootBg=mix(rootBg,{r:255,g:255,b:255,a:1});
            function bgOf(el){
              var layers=[],opaque=false;
              for(var n=el;n&&n.nodeType===1;n=n.parentElement){
                var cs=getComputedStyle(n);
                if(cs.backgroundImage&&cs.backgroundImage!=='none')return null;
                var c=P(cs.backgroundColor);
                if(c&&c.a>0){layers.push(c);if(c.a>=0.99){opaque=true;break;}}
              }
              if(!opaque&&hasBackdrop)return null;
              var base=rootBg;
              for(var i=layers.length-1;i>=0;i--)base=mix(layers[i],base);
              return base;
            }
            var found={},clipped=[],checked=0,unknown=0,guard=0,node;
            var seen=new WeakSet();
            var tw=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null);
            var limitY=vh*2.5;
            while((node=tw.nextNode())&&guard++<3000&&checked<500){
              if(!/\S/.test(node.nodeValue))continue;
              var el=node.parentElement;
              if(!el||seen.has(el))continue;
              seen.add(el);
              if(el.closest('#__mydes_hud,#__mydes_backdrop,script,style,noscript'))continue;
              var r=el.getBoundingClientRect();
              if(r.width<4||r.height<4||r.bottom<0||r.top>limitY)continue;
              if((r.right > vw + 2 || r.left < -2) && clipped.length < 6) {
                clipped.push({sel: sel(el), overflowPx: Math.round(Math.max(r.right - vw, -r.left))});
              }
              var cs2=getComputedStyle(el);
              if(cs2.visibility==='hidden'||cs2.display==='none'||parseFloat(cs2.opacity)===0)continue;
              var fg=P(cs2.color);
              if(!fg||fg.a===0)continue;
              var bg=bgOf(el);
              if(!bg){unknown++;continue;}
              var fgc=fg.a<1?mix(fg,bg):fg;
              checked++;
              var ratio=R(fgc,bg);
              if(ratio<3){
                var key=sel(el)+'|'+hx(fgc)+'|'+hx(bg);
                var f=found[key]||(found[key]={sel:sel(el),text:(node.nodeValue||'').trim().slice(0,28),fg:hx(fgc),bg:hx(bg),ratio:Math.round(ratio*100)/100,count:0});
                f.count++;
              }
            }
            var worst=Object.keys(found).map(function(k){return found[k];}).sort(function(a,b){return a.ratio-b.ratio;});
            var failing=0;worst.forEach(function(w){failing+=w.count;});
            return {viewport:{w:vw,h:vh},checked:checked,unknownBg:unknown,failing:failing,overflowX:document.documentElement.scrollWidth-vw,clippedElements:clipped,worst:worst.slice(0,10)};
          }catch(e){return {error:String(e)};}
        })();
    """
}

fun WebView.captureJpegBase64(maxWidth: Int = 640): String? {
    if (width <= 0 || height <= 0) return null
    return try {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp))
        val scale = minOf(1f, maxWidth.toFloat() / width)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (width * scale).toInt(), (height * scale).toInt(), true) else bmp
        val bos = ByteArrayOutputStream()
        out.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    } catch (_: Throwable) {
        null
    }
}

suspend fun WebView.evaluateJsSuspend(script: String): String? = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { result ->
            if (cont.isActive) cont.resume(result)
        }
    }
}

// ───────────────────── Blueprint Cache ─────────────────────

class ThemeStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("mydes_blueprints_v8", Context.MODE_PRIVATE)
    private fun key(prompt: String, domain: String) =
        "bp8|" + sha1Hex("${domain.trim().lowercase(Locale.US)}|${prompt.trim().lowercase(Locale.US)}")
    private fun lastPromptKey(prompt: String) =
        "bp8_last|" + sha1Hex(prompt.trim().lowercase(Locale.US))

    fun get(prompt: String, domain: String = "*"): UIBlueprint? {
        val exact = prefs.getString(key(prompt, domain), null)?.let { UIBlueprint.parse(it, prompt) }
        if (exact != null) return exact
        // Fallback if the SPA URL/host shifted slightly between Pass 2 and Pass 3 (Review)
        return prefs.getString(lastPromptKey(prompt), null)?.let { UIBlueprint.parse(it, prompt) }
    }

    fun put(prompt: String, blueprint: UIBlueprint, domain: String = "*", remember: Boolean = true) {
        val json = blueprint.toJson()
        prefs.edit()
            .putString(key(prompt, domain), json)
            .putString(lastPromptKey(prompt), json)
            .apply()
        if (remember) pushConcept(blueprint)
    }

    fun recentConcepts(limit: Int = 8): List<String> =
        (prefs.getString("recent_concepts", "") ?: "")
            .split('\n').filter { it.isNotBlank() }.takeLast(limit)

    private fun pushConcept(bp: UIBlueprint) {
        val traits = bp.fingerprint.ifBlank { bp.concept }.ifBlank { bp.name }
        val line = (bp.name.take(40) + ": " + traits).replace('\n', ' ').take(180)
        if (line.isBlank()) return
        val all = (recentConcepts(20) + line).takeLast(20)
        prefs.edit().putString("recent_concepts", all.joinToString("\n")).apply()
    }
}

// ───────────────────── The Gemini UI Synthesis Service ─────────────────────

class GeminiStylerService {

    companion object {
        /** Fast model for Pass 1 (Art Director creative concept brief) */
        const val CONCEPT_MODEL = "gemini-3.5-flash-lite"

        /** High-capability coding model for Pass 2 (Main CSS3/SVG/JS synthesis) */
        const val SYNTHESIS_MODEL = "gemini-3.8-flash"

        /** Fast, quota-friendly model for Pass 3 (Visual QA & CSS review fix patch) */
        const val REVIEW_MODEL = "gemini-3.5-flash-lite"
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    /**
     * Zero-Dependency DNS-over-HTTPS Fallback (compatible with standard okhttp3 in your build.gradle.kts):
     * Uses system DNS first. If the ISP resolver fails with UnknownHostException, queries Google Public DNS
     * (8.8.8.8) or Cloudflare (1.1.1.1) over HTTPS by direct IP so no hostname lookup is needed for the DNS server.
     */
    private val resilientDns = object : Dns {
        private val dohHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        override fun lookup(hostname: String): List<InetAddress> {
            try {
                val sys = Dns.SYSTEM.lookup(hostname)
                if (sys.isNotEmpty()) return sys
            } catch (_: UnknownHostException) {
                // Fall through to DoH JSON resolution over raw IP
            }

            val endpoints = listOf(
                "https://8.8.8.8/resolve?name=$hostname&type=A",
                "https://1.1.1.1/dns-query?name=$hostname&type=A"
            )
            for (url in endpoints) {
                try {
                    val req = Request.Builder()
                        .url(url)
                        .header("Accept", "application/dns-json")
                        .build()
                    dohHttpClient.newCall(req).execute().use { resp ->
                        val body = resp.body?.string() ?: return@use
                        val json = JSONObject(body)
                        val answers = json.optJSONArray("Answer") ?: return@use
                        val resolved = mutableListOf<InetAddress>()
                        for (i in 0 until answers.length()) {
                            val ans = answers.getJSONObject(i)
                            if (ans.optInt("type") == 1) { // A record (IPv4)
                                val ip = ans.optString("data", "").trim()
                                if (ip.isNotEmpty()) {
                                    resolved.add(InetAddress.getByName(ip))
                                }
                            }
                        }
                        if (resolved.isNotEmpty()) return resolved
                    }
                } catch (_: Exception) {
                    // Try next DoH endpoint
                }
            }
            return Dns.SYSTEM.lookup(hostname)
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .dns(resilientDns)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val api = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GeminiApi::class.java)

    private var themeStore: ThemeStore? = null
    private fun storeFor(context: Context): ThemeStore = themeStore ?: ThemeStore(context).also { themeStore = it }

    private fun extractSafeDomain(websiteUrl: String, fallbackName: String): String {
        if (websiteUrl.isNotBlank()) {
            try {
                val host = Uri.parse(websiteUrl).host?.lowercase(Locale.US) ?: ""
                if (host.isNotBlank()) return host.removePrefix("www.")
            } catch (_: Exception) {}
        }
        return fallbackName.trim().lowercase(Locale.US).ifBlank { "*" }
    }

    // ───── Public Entry Points ─────

    suspend fun generateUserScript(
        context: Context,
        userPrompt: String,
        websiteName: String,
        websiteUrl: String,
        forceFresh: Boolean = false,
        outlineJson: String? = null
    ): Result<String> = synthesizeBlueprint(
        context = context,
        prompt = userPrompt,
        websiteName = websiteName,
        websiteUrl = websiteUrl,
        pageOutlineJson = outlineJson?.takeIf { it.isNotBlank() && it != "null" },
        screenshotBase64 = null,
        forceFresh = forceFresh,
        isReviewPass = false
    ).map { MydesRuntime.script(it) }

    suspend fun generateUserScriptWithFeedback(
        context: Context,
        userPrompt: String,
        websiteName: String,
        websiteUrl: String,
        screenshotBase64: String?,
        screenWidth: Int,
        screenHeight: Int,
        outlineJson: String? = null,
        auditJson: String? = null
    ): Result<String> = synthesizeBlueprint(
        context = context,
        prompt = userPrompt,
        websiteName = websiteName,
        websiteUrl = websiteUrl,
        pageOutlineJson = outlineJson?.takeIf { it.isNotBlank() && it != "null" }
            ?: "{\"viewport\":{\"w\":$screenWidth,\"h\":$screenHeight}}",
        screenshotBase64 = screenshotBase64,
        forceFresh = false,
        auditJson = auditJson,
        isReviewPass = true // Explicitly locks this call to REVIEW_MODEL (gemini-3.5-flash-lite)
    ).map { MydesRuntime.script(it) }

    suspend fun adaptUserScriptForWebpage(
        context: Context,
        basePrompt: String,
        baseScript: String,
        pageTitle: String,
        pageUrl: String,
        domain: String
    ): Result<String> = synthesizeBlueprint(
        context = context,
        prompt = basePrompt,
        websiteName = pageTitle.ifBlank { domain },
        websiteUrl = pageUrl,
        pageOutlineJson = null,
        screenshotBase64 = null,
        isReviewPass = false
    ).map { MydesRuntime.script(it) }

    suspend fun themeSpec(
        context: Context,
        prompt: String,
        websiteUrl: String = "*",
        pageOutlineJson: String? = null,
        screenshotBase64: String? = null,
        forceFresh: Boolean = false
    ): Result<UIBlueprint> = synthesizeBlueprint(
        context = context,
        prompt = prompt,
        websiteName = websiteUrl,
        websiteUrl = websiteUrl,
        pageOutlineJson = pageOutlineJson,
        screenshotBase64 = screenshotBase64,
        forceFresh = forceFresh,
        isReviewPass = screenshotBase64 != null && !forceFresh
    )

    // ───── Pass 1: Concept Pass (Art Director using gemini-3.5-flash-lite) ─────

    private val LOG_SYSTEM_PROMPT = false

    private class ConceptBrief(
        val raw: String,
        val idea: String,
        val fingerprint: String,
        val architecture: String // "restyle" | "app_shell"
    )

    private fun extractJsonObject(raw: String): String? {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        return if (s >= 0 && e > s) raw.substring(s, e + 1) else null
    }

    private suspend fun callForText(
        model: String,
        apiKey: String,
        system: String,
        parts: List<Part>,
        temperature: Float,
        topP: Float,
        maxTokens: Int
    ): String {
        val req = GeminiRequest(
            contents = listOf(Content(parts = parts)),
            systemInstruction = Content(listOf(Part(text = system))),
            generationConfig = GenerationConfig(
                temperature = temperature,
                topP = topP,
                maxOutputTokens = maxTokens,
                responseMimeType = "application/json",
                thinkingConfig = ThinkingConfig(thinkingBudget = 0)
            )
        )
        return callGeminiStream(model, req, apiKey)
    }

    private suspend fun designBrief(
        context: Context,
        prompt: String,
        websiteName: String,
        websiteUrl: String,
        pageOutlineJson: String?,
        apiKey: String
    ): ConceptBrief? {
        val input = buildString {
            appendLine("USER REQUEST: \"$prompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            if (!pageOutlineJson.isNullOrBlank()) appendLine("LIVE DOM TOPOLOGY OUTLINE: ${pageOutlineJson.take(4_500)}")
            val recent = storeFor(context).recentConcepts()
            if (recent.isNotEmpty()) {
                appendLine("RECENT DESIGNS ALREADY SHIPPED (avoid repeating their style):")
                recent.forEach { appendLine("- $it") }
            }
            appendLine("Write the technical art direction JSON now.")
        }
        val logInput = buildString {
            appendLine("MODEL: $CONCEPT_MODEL")
            append(input)
            if (LOG_SYSTEM_PROMPT) { appendLine(); appendLine("--- SYSTEM PROMPT ---"); append(UI_CONCEPT_SYSTEM_PROMPT) }
        }.take(if (LOG_SYSTEM_PROMPT) 30_000 else 12_000)

        return try {
            val raw = callForText(CONCEPT_MODEL, apiKey, UI_CONCEPT_SYSTEM_PROMPT, listOf(Part(text = input)), 1.05f, 0.95f, 1024)
            val json = JSONObject(extractJsonObject(raw) ?: throw IllegalStateException("No JSON in brief: ${raw.take(200)}"))
            val idea = json.optString("idea", "").trim()
            if (idea.isBlank()) throw IllegalStateException("Brief has no idea: ${raw.take(200)}")

            val arch = json.optString("architecture", "restyle").lowercase(Locale.US)
            val directions = listOf(
                "architecture" to "ARCHITECTURE",
                "idea" to "CONCEPT WORLD",
                "signature" to "SIGNATURE VISUAL & ANIMATION MECHANIC",
                "palette" to "PALETTE",
                "type" to "TYPOGRAPHY",
                "surface" to "SURFACE & BORDERS",
                "layout" to "RESPONSIVE STRUCTURAL LAYOUT MECHANICS",
                "rules" to "ELEMENT CHOREOGRAPHY"
            ).mapNotNull { (k, label) -> json.optString(k, "").trim().takeIf { it.isNotBlank() }?.let { "$label: $it" } }
            .joinToString("\n")

            val brief = ConceptBrief(
                raw = directions,
                idea = idea.take(300),
                fingerprint = json.optString("fingerprint", "").take(120),
                architecture = arch
            )
            ApiRequestLogger.logRequest(context, "conceptBrief ($CONCEPT_MODEL)", websiteName, websiteUrl, prompt, logInput, json.toString(2), true)
            brief
        } catch (e: Exception) {
            ApiRequestLogger.logRequest(context, "conceptBrief ($CONCEPT_MODEL)", websiteName, websiteUrl, prompt, logInput, describe(e), false)
            null
        }
    }

    // ───── Pass 2 (Synthesis via gemini-3.8-flash) & Pass 3 (Review via gemini-3.5-flash-lite) ─────

    private suspend fun synthesizeBlueprint(
        context: Context,
        prompt: String,
        websiteName: String,
        websiteUrl: String,
        pageOutlineJson: String?,
        screenshotBase64: String?,
        forceFresh: Boolean = false,
        auditJson: String? = null,
        isReviewPass: Boolean = false
    ): Result<UIBlueprint> = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Empty prompt"))

        val domain = extractSafeDomain(websiteUrl, websiteName)

        if (!isReviewPass && screenshotBase64 == null && auditJson == null && !forceFresh) {
            storeFor(context).get(cleanPrompt, domain)?.let { return@withContext Result.success(it) }
        }

        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GEMINI_API_KEY is not set. Please set your API key in Settings."))
        }

        val previous = if (isReviewPass || !screenshotBase64.isNullOrBlank() || !auditJson.isNullOrBlank()) {
            storeFor(context).get(cleanPrompt, domain)
        } else null

        // If generateUserScriptWithFeedback called this (isReviewPass = true), NEVER fall back to SYNTHESIS_MODEL.
        if (isReviewPass && previous == null) {
            return@withContext Result.failure(IllegalStateException("Review pass skipped: no base blueprint cached for $domain"))
        }

        val isReview = isReviewPass || (previous != null && !forceFresh && (!screenshotBase64.isNullOrBlank() || !auditJson.isNullOrBlank()))
        val activeModel = if (isReview) REVIEW_MODEL else SYNTHESIS_MODEL

        val brief: ConceptBrief? = if (isReview) null
            else designBrief(context, cleanPrompt, websiteName, websiteUrl, pageOutlineJson, apiKey)

        val userContextText = if (isReview) buildString {
            appendLine("REVIEW PASS")
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            appendLine("PALETTE: bg ${previous!!.bg}, surface ${previous.surface}, surface2 ${previous.surface2}, text ${previous.fg}, ink ${previous.ink}, accent ${previous.accent}")
            if (!pageOutlineJson.isNullOrBlank()) appendLine("LIVE DOM TOPOLOGY OUTLINE: ${pageOutlineJson.take(2_500)}")
            appendLine("MEASURED AUDIT (check overflowX and clippedElements!): ${auditJson?.takeIf { it.isNotBlank() && it != "null" }?.take(3_000) ?: "not available"}")
            appendLine("CURRENT CSS:")
            appendLine(previous.customCss)
            appendLine(if (!screenshotBase64.isNullOrBlank()) "A screenshot of the current render is attached." else "No screenshot is available; rely on the audit.")
            appendLine("Return the review JSON now.")
        } else buildString {
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            if (!pageOutlineJson.isNullOrBlank()) {
                appendLine("LIVE DOM TOPOLOGY OUTLINE (repeatedStructures lists parent container selectors `parentSel` and composite item selectors `sel`):")
                appendLine(pageOutlineJson.take(6_000))
            }
            appendLine()
            if (brief != null) {
                appendLine("DESIGN DIRECTIONS from the art director (implement every visual, layout, and animation mechanic literally):")
                appendLine(brief.raw)
            } else {
                appendLine("No directions were supplied. Invent a specific, cohesive concept, use 'restyle' architecture, and implement it completely.")
            }
            appendLine("Generate the complete UIBlueprint JSON now.")
        }

        val parts = mutableListOf<Part>(Part(text = userContextText))
        if (!screenshotBase64.isNullOrBlank()) {
            parts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = screenshotBase64)))
        }

        fun buildReq(jsonMode: Boolean) = GeminiRequest(
            contents = listOf(Content(parts = parts)),
            systemInstruction = Content(listOf(Part(text = if (isReview) UI_REVIEW_SYSTEM_PROMPT else UI_SYNTHESIS_SYSTEM_PROMPT))),
            generationConfig = GenerationConfig(
                temperature = if (isReview) 0.2f else 0.75f,
                topP = 0.95f,
                maxOutputTokens = if (isReview) 1536 else 8192,
                responseMimeType = if (jsonMode) "application/json" else null,
                thinkingConfig = ThinkingConfig(thinkingBudget = 0)
            )
        )

        val logType = if (isReview) "reviewBlueprint ($activeModel)" else "buildBlueprint ($activeModel)"
        val requestJson = buildString {
            appendLine("MODEL: $activeModel")
            appendLine(if (isReview) "MODE: review pass" else if (brief != null) "MODE: build pass (${brief.architecture})" else "MODE: build pass (default restyle)")
            append(userContextText)
            if (!screenshotBase64.isNullOrBlank()) appendLine("[screenshot attached, ${screenshotBase64.length / 1024} KB base64]")
            if (LOG_SYSTEM_PROMPT) {
                appendLine()
                appendLine("--- SYSTEM PROMPT ---")
                append(if (isReview) UI_REVIEW_SYSTEM_PROMPT else UI_SYNTHESIS_SYSTEM_PROMPT)
            }
        }.take(if (LOG_SYSTEM_PROMPT) 30_000 else 12_000)

        try {
            val raw = callGeminiStream(activeModel, buildReq(true), apiKey)
            val blueprint: UIBlueprint? = if (isReview) {
                applyReviewPatch(previous!!, raw, cleanPrompt)
            } else {
                val parsed = UIBlueprint.parse(raw, cleanPrompt, brief?.architecture ?: "restyle")
                if (parsed != null && brief != null) parsed.copy(concept = brief.idea, fingerprint = brief.fingerprint) else parsed
            }

            if (blueprint == null) {
                val err = "Model returned invalid UIBlueprint JSON. Raw start: ${raw.take(300)}"
                ApiRequestLogger.logRequest(context, logType, websiteName, websiteUrl, cleanPrompt, requestJson, err, false)
                Result.failure(IllegalStateException(err))
            } else {
                storeFor(context).put(cleanPrompt, blueprint, domain, remember = !isReview)
                ApiRequestLogger.logRequest(context, logType, websiteName, websiteUrl, cleanPrompt, requestJson, if (isReview) raw.take(4_000) else blueprint.toJson(), true)
                Result.success(blueprint)
            }
        } catch (e: Exception) {
            val msg = describe(e)
            ApiRequestLogger.logRequest(context, logType, websiteName, websiteUrl, cleanPrompt, requestJson, msg, false)
            Result.failure(Exception(msg, e))
        }
    }

    private fun applyReviewPatch(previous: UIBlueprint, raw: String, prompt: String): UIBlueprint {
        val json = try { JSONObject(extractJsonObject(raw) ?: return previous) } catch (_: Exception) { return previous }
        val fix = stripInventedLabels(json.optString("fixCss", "")).trim().take(3_000)
        if (fix.isBlank()) return previous
        val merged = JSONObject(previous.toJson()).put("customCss", previous.customCss + "\n/* review */\n" + fix)
        return UIBlueprint.parse(merged.toString(), prompt, previous.architecture)
            ?.copy(concept = previous.concept, fingerprint = previous.fingerprint)
            ?: previous
    }

    // ───── System Prompts ─────

    private val UI_REVIEW_SYSTEM_PROMPT = """
You are the visual QA engineer for a commercial Android web browser running across all device sizes (compact phones, foldables, tablets). The design concept is final. You receive the user's prompt, palette, CURRENT CSS, a MEASURED AUDIT from the live DOM, and a screenshot.

You may ONLY append override CSS in `fixCss`. Never strip out the theme's transforms, animations, borders, or palette.
Inspect both the screenshot and the audit to fix real rendering defects:
1. Out-of-bounds elements or horizontal clipping (`overflowX > 2` or `clippedElements` non-empty, or elements cut off at the screen edges):
   - Use percentage and fluid sizing (`width: 100% !important; max-width: 100% !important; min-width: 0 !important; box-sizing: border-box !important; margin-left: 0 !important; margin-right: 0 !important;`).
   - For multi-column grids, use `grid-template-columns: repeat(auto-fit, minmax(min(100%, 150px), 1fr)) !important;` so columns adapt fluidly to any screen width without overflowing.
2. Unreadable or low-contrast text: set explicit `color` (and surface `background-color` if needed) with >= 4.5:1 contrast.
3. Truncated or clipped text: force `-webkit-line-clamp: unset !important; max-height: none !important; height: auto !important; overflow: visible !important; white-space: normal !important;`.
4. Text occluding images/media: override the text container to `position: static !important; width: 100% !important;` and its parent wrapper to `display: flex !important; flex-direction: column !important;`.

Keep `fixCss` under 1500 characters. If nothing is broken, return `""`.
OUTPUT: ONLY one JSON object:
{"problems": ["short list of defects found"], "fixCss": "css overrides or empty string"}
""".trimIndent()

    private val UI_CONCEPT_SYSTEM_PROMPT = """
You are the executive Art Director of a commercial generative web browser capable of redesigning ANY website into any world or interface paradigm across all Android device sizes (small phones, foldables, tablets).

1. CHOOSE THE ARCHITECTURE:
- "restyle" (DEFAULT - 95% of requests): Transforms the live DOM in-place using responsive CSS layout reflows (CSS Grid, Flexbox, 3D perspective, clip-path, keyframe animations), custom SVG/Canvas backdrops, and touch choreography. Zero invented floating docks or fake buttons.
- "app_shell" (OPT-IN ONLY): Select ONLY when the user or concept explicitly demands replacing the page with a brand-new interactive application layout (e.g., "dual-lane split comparator", "swipeable card deck", "full-screen comic book reader", "interactive terminal OS", or explicit assistive toolbars).

2. TRANSLATE VISION INTO DEVICE-AGNOSTIC TECHNICAL MECHANICS:
Do not assume a fixed screen width. Every layout rule must be fluid and responsive:
- In `signature`: Specify the exact `@keyframes` animation, SVG filter/pattern, 3D scroll tilt, or interactive effect.
- In `layout`: Inspect `repeatedStructures` in the outline. Specify how parent containers (`parentSel`) and repeating units (`sel`) must be arranged using fluid CSS mechanics (e.g., `grid-template-columns: repeat(auto-fit, minmax(min(100%, 160px), 1fr))` or `repeat(2, minmax(0, 1fr))` with `width: 100%; box-sizing: border-box;` so elements never overflow on any screen size).
- In `rules`: Detail how headers, navigation, search inputs, content units, typography, and media behave on touch screens.

OUTPUT: ONLY one valid JSON object:
{
  "architecture": "restyle" or "app_shell",
  "idea": "one sentence defining the world or interface",
  "signature": "concrete visual signature + exact @keyframes or SVG/JS mechanic",
  "palette": "5 hex colors with exact roles (bg, surface, surface2, fg, accent)",
  "type": "font stack + weight, casing, letter-spacing, and fluid clamp() scale hierarchy",
  "surface": "exact border styles, corner radii, clip-paths, shadows, and backdrop textures",
  "layout": "fluid, device-agnostic CSS layout rules for parent containers and child units",
  "rules": "concrete styling rules for header, search/inputs, content units, headings, media, and touch states",
  "fingerprint": "max 6 words summarizing type, palette, shape, layout"
}
""".trimIndent()

    private val UI_SYNTHESIS_SYSTEM_PROMPT = """
You are the Principal Front-End Synthesis Engine inside a commercial Android browser running on all device form factors. You receive the Art Director's DESIGN DIRECTIONS and the target webpage's LIVE DOM TOPOLOGY OUTLINE. You author responsive, production-grade CSS3, SVG/Canvas backdrops, and JS.

1. ARCHITECTURAL EXECUTION
- When ARCHITECTURE is "restyle":
  - `needsHud` MUST be `false`, `hudHtml` MUST be `""`. Never invent fake toolbars, docks, or decorative buttons.
  - Transform the real DOM using `customCss`, `backdropHtml`, and optional `customJs` (e.g. `MydesBridge.enable3DTilt(1.0)`).
- When ARCHITECTURE is "app_shell":
  - `needsHud` is `true`. Build the custom interface inside `hudHtml` (`#__mydes_hud`).
  - In `customJs`, call `MydesBridge.extractNodes()` to harvest live page content (`{uid, title, body, meta, img, hasVideo, href}`), render your custom interactive view, bind clicks to `MydesBridge.triggerNode(uid)` so the real site's router/state handles navigation, and call `MydesBridge.loadMore(cb)` for pagination.

2. MULTI-DEVICE RESPONSIVE CONTAINMENT (ZERO OUT-OF-BOUNDS ELEMENTS)
- Never assume fixed pixel widths. Your CSS must fit everything from compact 320px screens to wide foldables and tablets:
  - Always use `width: 100% !important; max-width: 100% !important; box-sizing: border-box !important;` (avoid `100vw` which causes horizontal overflow when combined with margins or borders).
  - For multi-column grids on `[data-mydes="grid"]` or `parentSel`, ALWAYS use `minmax(0, 1fr)` or `repeat(auto-fit, minmax(min(100%, 150px), 1fr))` so grid tracks shrink gracefully on narrow screens and expand on wider screens.
  - On all grid/flex children (`sel` and `[data-mydes="card"]`), ALWAYS set `min-width: 0 !important; width: 100% !important; max-width: 100% !important; box-sizing: border-box !important; margin-left: 0 !important; margin-right: 0 !important;`.
  - Use `clamp()` for typography (e.g. `font-size: clamp(0.85rem, 2.5vw, 1.15rem) !important;`) so text scales proportionally to the device width.
- PRESERVE INFINITE SCROLL:
  - Never set `overflow: hidden` or fixed `height` on `html`, `body`, `main`, or feed containers (`parentSel`).
  - Keep non-card continuation/sentinel elements at the bottom of feeds visible (`grid-column: 1 / -1 !important;`).

3. DOM TOPOLOGY & CONTENT INTEGRITY
- TARGET WHOLE UNITS, NOT SUB-COLUMNS: Always apply card borders, backgrounds, and padding to the full composite unit selector (`sel` and `[data-mydes="card"]`), NEVER to an inner text-only wrapper.
- STACKING ROW ITEMS: When turning horizontal row items (`itemDisplay: flex (row)`) into vertical cards or grid cells, force the unit and its internal link/row wrappers to stack vertically:
  `display: flex !important; flex-direction: column !important; align-items: stretch !important; width: 100% !important;` and set immediate children to `width: 100% !important; max-width: 100% !important;`.
- NEVER OCCLUDE MEDIA: Text, titles, and badges must never cover images or video previews (`position: static !important`).
- NEVER TRUNCATE HEADINGS: Allow styled headings and titles to wrap naturally (`-webkit-line-clamp: unset !important; max-height: none !important; height: auto !important; overflow: visible !important; white-space: normal !important;`).
- REAL ANIMATIONS & TOUCH STATES: Write actual `@keyframes` rules for requested motion/pulses, and use `:active` for mobile touch feedback.

OUTPUT: ONLY one valid JSON object, no markdown fences:
{
  "name": "short title",
  "dark": true or false,
  "bg": "#rrggbb", "surface": "#rrggbb", "surface2": "#rrggbb", "fg": "#rrggbb", "ink": "#rrggbb", "accent": "#rrggbb",
  "remapSurfaces": false,
  "customCss": "complete responsive CSS implementing all layout, surface, containment, and @keyframes rules",
  "backdropHtml": "inline SVG/Canvas/HTML stage or empty string",
  "needsHud": false,
  "hudReason": "",
  "hudHtml": "",
  "customJs": "JS using MydesBridge and T, or empty string",
  "concept": "one sentence summary",
  "fingerprint": "max 6 words"
}
""".trimIndent()

    // ───── Networking Helpers ─────

    private fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
        val customKey = prefs.getString("custom_api_key", "") ?: ""
        if (customKey.isNotBlank()) return customKey
        val buildConfigKey = BuildConfig.GEMINI_API_KEY
        if (buildConfigKey.isNotBlank() && buildConfigKey != "MY_GEMINI_API_KEY") return buildConfigKey
        return customKey
    }

    private suspend fun callGeminiStream(
        model: String,
        request: GeminiRequest,
        apiKey: String
    ): String = withContext(Dispatchers.IO) {
        var lastException: Exception? = null

        for (wait in longArrayOf(0, 2_000, 5_000)) {
            if (wait > 0) delay(wait)
            try {
                val responseBody = api.streamGenerateContent(model, apiKey, request)
                val fullText = StringBuilder()

                responseBody.byteStream().bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null) {
                        if (line.startsWith("data: ")) {
                            val jsonStr = line.removePrefix("data: ").trim()
                            if (jsonStr.isNotEmpty() && jsonStr != "[DONE]") {
                                try {
                                    val chunk = JSONObject(jsonStr)
                                    val candidates = chunk.optJSONArray("candidates")
                                    if (candidates != null && candidates.length() > 0) {
                                        val parts = candidates.getJSONObject(0)
                                            .optJSONObject("content")
                                            ?.optJSONArray("parts")
                                        if (parts != null) {
                                            for (i in 0 until parts.length()) {
                                                val partText = parts.getJSONObject(i).optString("text", "")
                                                fullText.append(partText)
                                            }
                                        }
                                    }
                                } catch (_: Exception) {
                                    // Skip individual chunk JSON parse errors
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }

                val result = fullText.toString()
                if (result.isNotBlank()) return@withContext result
                throw IllegalStateException("Empty streamed response from $model")

            } catch (e: Exception) {
                lastException = e
                val retryable = when (e) {
                    is HttpException -> e.code() == 429 || e.code() in 500..599
                    is java.io.IOException -> true
                    else -> false
                }
                if (!retryable) throw e
            }
        }
        throw lastException ?: IllegalStateException("Streaming failed on model $model")
    }

    private fun describe(e: Exception): String {
        if (e is HttpException) {
            val body = try { e.response()?.errorBody()?.string()?.take(300) } catch (_: Exception) { null }
            return "Gemini HTTP ${e.code()}${if (body.isNullOrBlank()) "" else ": $body"}"
        }
        return e.message ?: e.javaClass.simpleName
    }
}

// ───────────────────── Live WebView Controller ─────────────────────

class SiteThemeController(
    private val context: Context,
    private val webView: WebView,
    private val service: GeminiStylerService
) {
    private val docStartOk = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private var handler: ScriptHandler? = null
    private var script: String? = null

    suspend fun setTheme(prompt: String, includeScreenshot: Boolean = false): Result<UIBlueprint> {
        val currentUrl = withContext(Dispatchers.Main) { webView.url ?: "*" }
        val outlineJson = webView.evaluateJsSuspend(MydesRuntime.OUTLINE_PROBE_JS)
        val screenshot = if (includeScreenshot) {
            withContext(Dispatchers.Main) { webView.captureJpegBase64() }
        } else null

        val blueprint = service.themeSpec(
            context = context,
            prompt = prompt,
            websiteUrl = currentUrl,
            pageOutlineJson = outlineJson,
            screenshotBase64 = screenshot
        ).getOrElse { return Result.failure(it) }

        val js = MydesRuntime.script(blueprint)
        withContext(Dispatchers.Main) {
            script = js
            if (docStartOk) {
                try { handler?.remove() } catch (_: Exception) {}
                handler = WebViewCompat.addDocumentStartJavaScript(webView, js, setOf("*"))
            }
            webView.evaluateJavascript(js, null)
        }
        return Result.success(blueprint)
    }

    fun clearTheme() {
        script = null
        try { handler?.remove() } catch (_: Exception) {}
        handler = null
        webView.evaluateJavascript("window.__mydes && window.__mydes.clear();", null)
    }

    fun onPageFinished() {
        val js = script ?: return
        if (!docStartOk) webView.evaluateJavascript(js, null)
    }

    fun debugOutline(callback: (String?) -> Unit) {
        webView.evaluateJavascript("window.__mydes && window.__mydes.outline()") { raw -> callback(raw) }
    }
}
