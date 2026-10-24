package com.example.data.api

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
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
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/*
 * v4 - "Generative UI Synthesis Engine: Unbounded LLM Creativity + Safe DOM Execution"
 *
 * How it works:
 * 1. MydesRuntime tags the live webpage with semantic attributes (data-mydes="card|grid|btn|search|nav|price|media|...")
 *    and exposes MydesBridge (an API for safely triggering real clicks, searches, scrolling,TTS, and 3D tilt
 *    without detaching the website's own React/Vue/vanilla event listeners).
 * 2. Gemini receives the user's imaginative prompt (+ live DOM outline & optional screenshot) and generates a
 *    complete UIBlueprint:
 *      - Full custom CSS (3D perspectives, keyframe animations, spatial layouts, or ultra-clean accessible reflows)
 *      - Custom Backdrop HTML/SVG/Canvas (e.g., 3D space shuttle cockpit window, animated shaders, HUD frames)
 *      - Custom Interactive Overlay HTML (e.g., giant Grandma assistive controls, sci-fi telemetry bars)
 *      - Custom Sandbox JS (animations, 3D touch/scroll parallax, canvas rendering, and MydesBridge hooks)
 * 3. Contrast Guard & Anti-Breakage Watchdog ensure text stays readable and native clicks remain 100% functional.
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
    val topP: Float? = null
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
 * Unlike v3's static token list, UIBlueprint holds actual LLM-authored CSS, 3D/SVG/Canvas stage markup,
 * interactive assistive/creative HUD overlays, and custom JS choreography, backed by safe palette tokens
 * so the runtime's contrast guard keeps all text legible.
 */
/**
 * Removes ::before/::after rules that inject readable words (e.g. content:"> SYS.RECORD"). Models like to bolt
 * invented captions/labels onto every card; the page's own text should be the only text. Icons, bullets and
 * attr() content are kept because they contain no 3+ letter word.
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
    val needsHud: Boolean = false,
    val hudReason: String = "",
    val fingerprint: String = ""
) {
    fun toJson(): String = JSONObject()
        .put("concept", concept)
        .put("fingerprint", fingerprint)
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
        const val RUNTIME_VERSION = 4

        private fun jsonSlice(raw: String): String {
            var s = raw.trim()
            Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE).find(s)?.let {
                s = it.groupValues[1]
            }
            val a = s.indexOf('{')
            val b = s.lastIndexOf('}')
            return if (a >= 0 && b > a) s.substring(a, b + 1) else s
        }

        fun parse(raw: String, prompt: String): UIBlueprint? {
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
            val needsHud = j.optBoolean("needsHud", false) &&
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
                // The HUD is opt-in. If the model did not explicitly justify it, it is dropped here,
                // so a stray footer/dock can never appear just because the model felt like adding one.
                hudHtml = if (needsHud) j.optString("hudHtml", "") else "",
                customJs = j.optString("customJs", ""),
                concept = j.optString("concept", "").take(300),
                needsHud = needsHud,
                hudReason = j.optString("hudReason", "").take(200),
                fingerprint = j.optString("fingerprint", "").take(120)
            )
        }
    }
}

/** Compatibility alias so existing references to ThemeSpec in your project continue to compile seamlessly. */
typealias ThemeSpec = UIBlueprint

object SitePacks {
    val skipHosts = mutableSetOf("accounts.google.com")
}

// ───────────────────── Generative Runtime + MydesBridge ─────────────────────

object MydesRuntime {

    private const val CORE = """
if(window.__mydes && window.__mydes.id()===BP.id) return;
if(window.__mydes && window.__mydes.clear){try{window.__mydes.clear();}catch(e){}}
if(BP.skip && BP.skip.indexOf(location.hostname)>=0) return;

var cfg = BP;
var changes=[], EB=new WeakMap(), done=new WeakSet(), jobs=[], draining=false;
var tagDirty=true, lastTag=0, tagDelay=120, BG, FG, INK;
var cleanupFns=[], mo=null;

function hex(h){return {r:parseInt(h.substr(1,2),16),g:parseInt(h.substr(3,2),16),b:parseInt(h.substr(5,2),16),a:1};}
function rgbStr(c){return 'rgb('+Math.round(c.r)+','+Math.round(c.g)+','+Math.round(c.b)+')';}
function configure(){BG=hex(cfg.bg);FG=hex(cfg.fg);INK=hex(cfg.ink);}
configure();

/* ---------- 1. MydesBridge: Safe API for LLM-generated UIs to control the real website ---------- */
window.MydesBridge = {
  /* Clicks the first visible matching element on the real page without breaking React/Vue state */
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
  /* Types into the website's real search bar and submits the search form */
  proxySearch: function(query) {
    var inp = document.querySelector('input[type="search"],input[name="k"],input[name="q"],input[name="s"],input[name="query"],[data-mydes="search"] input');
    if(!inp) inp = document.querySelector('input[type="text"]');
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
  /* Focuses and scrolls to the real search input */
  focusSearch: function() {
    var inp = document.querySelector('input[type="search"],input[name="k"],input[name="q"],[data-mydes="search"] input,input[type="text"]');
    if(inp) {
      inp.scrollIntoView({behavior:'smooth', block:'center'});
      inp.focus();
      inp.style.setProperty('outline', '4px solid ' + cfg.accent, 'important');
      return true;
    }
    return false;
  },
  /* Adjusts global page zoom smoothly (ideal for accessibility controls) */
  zoom: function(delta) {
    var cur = parseFloat(document.documentElement.getAttribute('data-m-zoom') || '1');
    var next = Math.min(1.8, Math.max(0.8, cur + delta));
    document.documentElement.setAttribute('data-m-zoom', String(next));
    document.documentElement.style.setProperty('font-size', Math.round(next * 100) + '%', 'important');
  },
  /* Reads visible main content or headings aloud using Android WebView SpeechSynthesis */
  speakPage: function(customText) {
    try {
      if(!window.speechSynthesis) return;
      window.speechSynthesis.cancel();
      var txt = customText;
      if(!txt) {
        var nodes = document.querySelectorAll('[data-mydes="title"],[data-mydes="heading"],[data-mydes="price"]');
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
  /* Registers a 3D tilt/parallax loop driven by touch & scroll that updates CSS variables --m-rx, --m-ry, --m-scroll */
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

/* ---------- 2. Constructable / Injected Stylesheets ---------- */
var eStyle = null;
function applySheets() {
  var baseVars = ':root{--m-bg:'+cfg.bg+';--m-surface:'+cfg.surface+';--m-surface2:'+cfg.surface2+
    ';--m-fg:'+cfg.fg+';--m-ink:'+cfg.ink+';--m-accent:'+cfg.accent+';--m-rx:0deg;--m-ry:0deg;--m-scroll:0px;}\n' +
    'html{background-color:var(--m-bg)!important;color:var(--m-fg)!important;}\n' +
    'html body{background-color:transparent!important;color:var(--m-fg)!important;position:relative;z-index:1;}\n' +
    '#__mydes_backdrop{position:fixed;inset:0;width:100vw;height:100vh;pointer-events:none!important;z-index:-1;overflow:hidden;transform:translateZ(0);will-change:transform;}\n' +
    '#__mydes_hud{position:fixed;z-index:2147483640;pointer-events:none;inset:0;transform:translateZ(0);}\n' +
    '#__mydes_hud *{pointer-events:auto;}\n';
  var fullCss = baseVars + '\n' + (cfg.customCss || '');
  var root = document.head || document.documentElement;
  if(!root) return;
  if(!eStyle || !eStyle.isConnected) {
    eStyle = document.createElement('style');
    eStyle.id = '__mydes_style';
    root.appendChild(eStyle);
  }
  if(eStyle.textContent !== fullCss) eStyle.textContent = fullCss;
}

/* ---------- 3. Custom Stage Layers (3D Backdrop + Interactive HUD + Sandbox JS) ---------- */
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

/* ---------- 4. Semantic DOM Role Tagger ---------- */
function each(sel,fn){var l=document.querySelectorAll(sel);for(var i=0;i<l.length;i++)fn(l[i]);}
function put(e,r){if(e.getAttribute('data-mydes')!==r)e.setAttribute('data-mydes',r);}
function putNew(e,r){if(!e.hasAttribute('data-mydes'))e.setAttribute('data-mydes',r);}
var PRICE=/^\s*(?:(?:[\u0024\u20AC\u00A3\u00A5\u20B9]|USD|EUR|GBP|INR|Rs\.?)\s?\d[\d,.]*|\d[\d,.]*\s?(?:[\u0024\u20AC\u00A3\u00A5\u20B9]|USD|EUR|GBP|INR))\s*$/;
function inContent(e){return !!e.closest('main,article,section,[role="main"]');}
function sig(e){return e.tagName+'.'+(e.classList[0]||'')+'.'+(e.classList[1]||'');}
function cardLike(e){
  if(e.closest('nav,footer,#__mydes_hud,#__mydes_backdrop,[data-mydes="nav"],[data-mydes="footer"]'))return false;
  var link=e.tagName==='A'||!!e.querySelector('a[href]');
  if(!link)return false;
  var t=(e.textContent||'').trim().length;
  if(t<6||t>800)return false;
  return !!e.querySelector('img,picture,video')||t>=35;
}
function decorate(c){
  if(c.querySelector('h1,h2,h3,h4,h5,h6,[data-mydes="heading"]'))return;
  var l=c.querySelectorAll('a,span,div,p,strong,b');
  for(var i=0;i<l.length;i++){
    var x=l[i];
    if(x.children.length===0){
      var t=(x.textContent||'').trim();
      if(t.length>=8&&t.length<=140&&!PRICE.test(t)){x.setAttribute('data-mydes','heading');return;}
    }
  }
}
function findGrids(){
  var ps=document.body.querySelectorAll('*:not(#__mydes_hud *):not(#__mydes_backdrop *)'),n=0;
  for(var i=ps.length-1;i>=0&&n<2500;i--){
    var p=ps[i],k=p.children.length;
    if(k<2||k>80)continue;
    n++;
    if(p.getAttribute('data-mydes')==='grid'){
      for(var q=0;q<k;q++){var c0=p.children[q];if(!c0.hasAttribute('data-mydes')&&cardLike(c0)){c0.setAttribute('data-mydes','card');decorate(c0);}}
      continue;
    }
    if(p.closest('nav,footer,header,#__mydes_hud,#__mydes_backdrop,[data-mydes="nav"],[data-mydes="footer"],[data-mydes="header"]'))continue;
    if(p.querySelector('[data-mydes="card"]'))continue;
    var groups={},best=null,j;
    for(j=0;j<k;j++){var s=sig(p.children[j]);(groups[s]=groups[s]||[]).push(p.children[j]);}
    for(var g in groups){if(!best||groups[g].length>best.length)best=groups[g];}
    if(!best||best.length<2||best.length<k*0.55)continue;
    var cards=best.filter(cardLike);
    if(cards.length<2||cards.length<best.length*0.65)continue;
    p.setAttribute('data-mydes','grid');
    p.setAttribute('data-m-size',cards.length<=8?'s':'l');
    cards.forEach(function(c){c.setAttribute('data-mydes','card');decorate(c);});
  }
}
function markPrices(){
  var l=document.querySelectorAll('span,div,p,strong,b,em,ins,bdi,.a-price,.a-offscreen'),n=0;
  for(var i=0;i<l.length&&n<400;i++){
    var e=l[i];
    if(e.closest('#__mydes_hud,#__mydes_backdrop'))continue;
    if(e.children.length>2||e.hasAttribute('data-mydes'))continue;
    var t=(e.textContent||'').trim();
    if(t.length>24)continue;
    if(PRICE.test(t)){e.setAttribute('data-mydes','price');n++;}
  }
}

function retag(){
  if(!document.body)return;
  var t0=performance.now();
  each('header,[role="banner"],#navbar,#nav-main',function(e){if(!inContent(e))put(e,'header');});
  each('nav,[role="navigation"],#nav-xshop',function(e){put(e,'nav');});
  each('footer,[role="contentinfo"],#navFooter',function(e){if(!inContent(e))put(e,'footer');});
  each('main,[role="main"],#pageContent,#search',function(e){put(e,'main');});
  each('aside,[role="complementary"]',function(e){putNew(e,'aside');});
  each('form[role="search"],form[name="site-search"],input[type="search"],input[name="q"],input[name="k"]',function(e){
    var f=e.tagName==='FORM'?e:e.closest('form');
    if(f)putNew(f,'search');
  });
  if(performance.now()-t0<35)findGrids();
  /* Also tag standalone product/article cards on sites like Amazon (e.g. [data-component-type="s-search-result"]) */
  each('[data-component-type="s-search-result"],[data-asin]:not([data-asin=""]),article',function(e){
    if(!e.closest('#__mydes_hud,#__mydes_backdrop,nav,header,footer') && cardLike(e)){
      putNew(e,'card');
      if(e.parentElement && !e.parentElement.hasAttribute('data-mydes')) putNew(e.parentElement,'grid');
      decorate(e);
    }
  });
  each('h1',function(e){if(!e.closest('#__mydes_hud'))putNew(e,'title');});
  each('h2,h3,h4',function(e){if(!e.closest('#__mydes_hud'))putNew(e,'heading');});
  each('button,input[type="submit"],input[type="button"],[role="button"],a[class*="btn"],a[class*="button"],.a-button',function(e){
    if(!e.closest('#__mydes_hud'))putNew(e,'btn');
  });
  each('input:not([type="hidden"]):not([type="submit"]):not([type="button"]):not([type="checkbox"]):not([type="radio"]),textarea,select',function(e){
    if(!e.closest('#__mydes_hud'))putNew(e,'field');
  });
  each('img',function(e){if(!e.closest('#__mydes_hud,#__mydes_backdrop'))putNew(e,'media');});
  markPrices();
}

/* ---------- 5. Page Outline Extractor (Sent to Gemini so it knows the live page structure) ---------- */
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
  return JSON.stringify({
    url:location.href,
    host:location.hostname,
    title:(document.title||'').slice(0,80),
    viewport:{w:innerWidth,h:innerHeight},
    roleCounts:counts,
    roleSamples:samples
  });
}

/* ---------- 6. Surface Remapper & Contrast Guard ---------- */
var SKIPTAG=/^(SCRIPT|STYLE|NOSCRIPT|IMG|SVG|PATH|VIDEO|CANVAS|IFRAME|PICTURE|INPUT|TEXTAREA|SELECT|OPTION|BR|HR|META|LINK|TITLE|HEAD|SOURCE|TEMPLATE|BUTTON)$/;
var SURFACE_ROLE=/^(card|header|nav|footer|hero|aside)$/;
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
    tagDelay=Math.min(1200,Math.max(120,(lastTag-t0)*6));
  }
  while(jobs.length&&performance.now()-t0<12){
    var j=jobs[0];
    if(!j.list)j.list=[j.root].concat(Array.prototype.slice.call(j.root.querySelectorAll('*'),0,3500));
    while(j.i<j.list.length&&performance.now()-t0<12){try{handle(j.list[j.i]);}catch(e){}j.i++;}
    if(j.i>=j.list.length)jobs.shift();
  }
  if(jobs.length||tagDirty)setTimeout(drain,20);else draining=false;
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

    /** Lightweight tagger + outline extractor script so Gemini can inspect the live DOM before generating. */
    const val OUTLINE_PROBE_JS = """
        (function(){
          var counts={};
          ['header','nav','main','aside','footer','form','button','input','img','article','h1','h2','h3'].forEach(function(t){
            counts[t]=document.querySelectorAll(t).length;
          });
          var groups={};
          var cand=document.querySelectorAll('a,li,article,section,div');
          for(var i=0;i<cand.length&&i<4000;i++){
            var e=cand[i];
            if(e.closest('#__mydes_hud,#__mydes_backdrop'))continue;
            if(e.children.length<1||e.children.length>14||!e.querySelector('img'))continue;
            var t=(e.textContent||'').replace(/\s+/g,' ').trim();
            if(t.length<8||t.length>240)continue;
            var k=e.tagName.toLowerCase()+(e.classList[0]?'.'+e.classList[0]:'');
            var g=groups[k]||(groups[k]={sel:k,n:0,sample:t.slice(0,40)});
            g.n++;
          }
          var rep=Object.keys(groups).map(function(k){return groups[k];}).filter(function(g){return g.n>=3;}).sort(function(x,y){return y.n-x.n;}).slice(0,4);
          var heads=[].slice.call(document.querySelectorAll('h1,h2,h3')).slice(0,6).map(function(h){return (h.textContent||'').trim().slice(0,40);});
          var o;
          if(window.__mydes && window.__mydes.outline){o=JSON.parse(window.__mydes.outline());}
          else{o={url:location.href,host:location.hostname,title:(document.title||'').slice(0,80),viewport:{w:innerWidth,h:innerHeight},counts:counts};}
          o.repeatedItems=rep;
          o.headings=heads;
          return o;
        })();
    """

    /**
     * Measures legibility on the live page: text whose colour has under 3:1 contrast against what is really behind
     * it, plus horizontal overflow. Elements sitting on gradients/images or on a backdrop are counted as unknownBg.
     * This is ground truth for the review pass, so it cannot be blind the way a failed screenshot can.
     */
    const val CONTRAST_AUDIT_JS = """
        (function(){
          try{
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
            var found={},checked=0,unknown=0,guard=0,node;
            var seen=new WeakSet();
            var tw=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null);
            var limitY=innerHeight*2.5;
            while((node=tw.nextNode())&&guard++<3000&&checked<500){
              if(!/\S/.test(node.nodeValue))continue;
              var el=node.parentElement;
              if(!el||seen.has(el))continue;
              seen.add(el);
              if(el.closest('#__mydes_hud,#__mydes_backdrop,script,style,noscript'))continue;
              var r=el.getBoundingClientRect();
              if(r.width<4||r.height<4||r.bottom<0||r.top>limitY)continue;
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
            return {checked:checked,unknownBg:unknown,failing:failing,overflowX:document.documentElement.scrollWidth-innerWidth,worst:worst.slice(0,10)};
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
    private val prefs = context.applicationContext.getSharedPreferences("mydes_blueprints_v4", Context.MODE_PRIVATE)
    private fun key(prompt: String, domain: String) =
        "bp4|" + sha1Hex("${domain.trim().lowercase(Locale.US)}|${prompt.trim().lowercase(Locale.US)}")

    fun get(prompt: String, domain: String = "*"): UIBlueprint? =
        prefs.getString(key(prompt, domain), null)?.let { UIBlueprint.parse(it, prompt) }

    fun put(prompt: String, blueprint: UIBlueprint, domain: String = "*", remember: Boolean = true) {
        prefs.edit().putString(key(prompt, domain), blueprint.toJson()).apply()
        if (remember) pushConcept(blueprint)
    }

    /** Last few design concepts across ALL sites/prompts. Fed back to the model so it moves away from them. */
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
        /**
         * Use gemini-2.5-flash (or gemini-2.5-pro) so the model has strong CSS3/3D/SVG/DOM synthesis capabilities.
         */
        const val MODEL = "gemini-3.5-flash-lite"
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val requestAdapter = moshi.adapter(GeminiRequest::class.java)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val api = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GeminiApi::class.java)

    private var themeStore: ThemeStore? = null
    private fun storeFor(context: Context): ThemeStore = themeStore ?: ThemeStore(context).also { themeStore = it }

    // ───── Public Entry Points (100% compatible with your existing call sites) ─────

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
        forceFresh = forceFresh
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
        auditJson = auditJson
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
        screenshotBase64 = null
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
        forceFresh = forceFresh
    )


    // ───── Concept pass (art director) ─────
    // Pipeline: 1) CONCEPT PASS invents a design brief with no code, 2) BUILD PASS implements that brief,
    // 3) REVIEW PASS (after a screenshot) fixes defects. There are no random seeds, option lists or example
    // layouts anywhere: variety comes from the model's own idea plus a memory of traits it already shipped.

    /** Set true while debugging to append the full system prompt to each log entry. */
    private val LOG_SYSTEM_PROMPT = false

    private class ConceptBrief(val raw: String, val idea: String, val fingerprint: String)

    private fun extractJsonObject(raw: String): String? {
        val s = raw.indexOf('{')
        val e = raw.lastIndexOf('}')
        return if (s >= 0 && e > s) raw.substring(s, e + 1) else null
    }

    private suspend fun callForText(
        apiKey: String,
        system: String,
        parts: List<Part>,
        temperature: Float,
        topP: Float,
        maxTokens: Int
    ): String {
        fun req(jsonMode: Boolean) = GeminiRequest(
            contents = listOf(Content(parts = parts)),
            systemInstruction = Content(listOf(Part(text = system))),
            generationConfig = GenerationConfig(
                temperature = temperature,
                topP = topP,
                maxOutputTokens = maxTokens,
                responseMimeType = if (jsonMode) "application/json" else null
            )
        )
        val response = try {
            callGemini(req(true), apiKey)
        } catch (e: HttpException) {
            if (e.code() == 400) callGemini(req(false), apiKey) else throw e
        }
        return response.candidates?.firstOrNull()?.content?.parts?.mapNotNull { it.text }?.joinToString("").orEmpty()
    }

    /** Pass 1: invent the idea. Returns null on any failure so the build pass can fall back to inventing its own. */
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
            if (!pageOutlineJson.isNullOrBlank()) appendLine("LIVE DOM SEMANTIC OUTLINE: ${pageOutlineJson.take(4_000)}")
            val recent = storeFor(context).recentConcepts()
            if (recent.isNotEmpty()) {
                appendLine("RECENT DESIGNS ALREADY SHIPPED (name: traits):")
                recent.forEach { appendLine("- $it") }
            }
            appendLine("Write the design brief JSON now.")
        }
        val logInput = buildString {
            append(input)
            if (LOG_SYSTEM_PROMPT) { appendLine(); appendLine("--- SYSTEM PROMPT ---"); append(UI_CONCEPT_SYSTEM_PROMPT) }
        }.take(if (LOG_SYSTEM_PROMPT) 30_000 else 12_000)
        return try {
            val raw = callForText(apiKey, UI_CONCEPT_SYSTEM_PROMPT, listOf(Part(text = input)), 1.15f, 0.98f, 1024)
            val json = JSONObject(extractJsonObject(raw) ?: throw IllegalStateException("no JSON in brief: ${raw.take(200)}"))
            val idea = json.optString("idea", "").trim()
            if (idea.isBlank()) throw IllegalStateException("brief has no idea: ${raw.take(200)}")
            // Only the directions the builder needs are forwarded (not `considered`, not the fingerprint)
            val directions = listOf("idea" to "IDEA", "signature" to "SIGNATURE", "palette" to "PALETTE", "type" to "TYPE",
                "surface" to "SURFACE", "layout" to "LAYOUT", "rules" to "RULES")
                .mapNotNull { (k, label) -> json.optString(k, "").trim().takeIf { it.isNotBlank() }?.let { "$label: $it" } }
                .joinToString("\n")
            val brief = ConceptBrief(directions, idea.take(300), json.optString("fingerprint", "").take(120))
            ApiRequestLogger.logRequest(context, "conceptBrief", websiteName, websiteUrl, prompt, logInput, json.toString(2), true)
            brief
        } catch (e: Exception) {
            ApiRequestLogger.logRequest(context, "conceptBrief", websiteName, websiteUrl, prompt, logInput, describe(e), false)
            null
        }
    }

    // ───── Core Generative Synthesis ─────

    private suspend fun synthesizeBlueprint(
        context: Context,
        prompt: String,
        websiteName: String,
        websiteUrl: String,
        pageOutlineJson: String?,
        screenshotBase64: String?,
        forceFresh: Boolean = false,
        auditJson: String? = null
    ): Result<UIBlueprint> = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Empty prompt"))

        val domain = try {
            java.net.URI(websiteUrl).host ?: websiteName
        } catch (_: Exception) {
            websiteName
        }.ifBlank { "*" }

        // Return cached blueprint if we already generated one for this (prompt, domain) pair and no new screenshot is passed
        if (screenshotBase64 == null && auditJson == null && !forceFresh) {
            storeFor(context).get(cleanPrompt, domain)?.let { return@withContext Result.success(it) }
        }

        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GEMINI_API_KEY is not set. Please set your API key in Settings."))
        }

        // REVIEW MODE: a screenshot and/or a measured audit plus an existing blueprint for this (prompt, domain) means
        // "fix what you shipped". The review can only APPEND override CSS, so it can never remove the design.
        val reviewInputs = !screenshotBase64.isNullOrBlank() || !auditJson.isNullOrBlank()
        val previous = if (reviewInputs && !forceFresh) storeFor(context).get(cleanPrompt, domain) else null
        val isReview = previous != null

        // PASS 1 (first generations only): invent the idea, with no code.
        val brief: ConceptBrief? = if (isReview) null
            else designBrief(context, cleanPrompt, websiteName, websiteUrl, pageOutlineJson, apiKey)

        val userContextText = if (isReview) buildString {
            appendLine("REVIEW PASS")
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            appendLine("PALETTE: bg ${previous!!.bg}, surface ${previous.surface}, surface2 ${previous.surface2}, text ${previous.fg}, ink ${previous.ink}, accent ${previous.accent}")
            if (!pageOutlineJson.isNullOrBlank()) appendLine("LIVE DOM OUTLINE: ${pageOutlineJson.take(2_500)}")
            appendLine("MEASURED AUDIT (live page, ground truth): ${auditJson?.takeIf { it.isNotBlank() && it != "null" }?.take(3_000) ?: "not available"}")
            appendLine("CURRENT CSS:")
            appendLine(previous.customCss)
            appendLine(if (!screenshotBase64.isNullOrBlank()) "A screenshot of the current render is attached." else "No screenshot is available; rely on the audit.")
            appendLine("Return the review JSON now.")
        } else buildString {
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            if (!pageOutlineJson.isNullOrBlank()) {
                appendLine("LIVE DOM SEMANTIC OUTLINE: ${pageOutlineJson.take(6_000)}")
            }
            appendLine()
            if (brief != null) {
                appendLine("DESIGN DIRECTIONS from the art director (the spec; implement them literally):")
                appendLine(brief.raw)
            } else {
                appendLine("No directions were supplied. First decide a specific, original concept yourself (a world, material or process, not a colour scheme), then build it.")
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
                temperature = if (isReview) 0.3f else 0.8f,
                topP = 0.95f,
                maxOutputTokens = if (isReview) 1536 else 8192,
                responseMimeType = if (jsonMode) "application/json" else null
            )
        )

        // What the log sheet shows: the mode and the actual per-request input (lens, recent-design list, current
        // blueprint for reviews), not the static system prompt, which used to eat the whole 4000-char budget.
        val requestJson = buildString {
            appendLine(if (isReview) "MODE: review pass" else if (brief != null) "MODE: build pass (from design directions)" else "MODE: build pass (no directions, concept pass failed)")
            append(userContextText)
            if (!screenshotBase64.isNullOrBlank()) appendLine("[screenshot attached, ${screenshotBase64.length / 1024} KB base64]")
            if (LOG_SYSTEM_PROMPT) {
                appendLine()
                appendLine("--- SYSTEM PROMPT ---")
                append(if (isReview) UI_REVIEW_SYSTEM_PROMPT else UI_SYNTHESIS_SYSTEM_PROMPT)
            }
        }.take(if (LOG_SYSTEM_PROMPT) 30_000 else 12_000)

        try {
            val response = try {
                callGemini(buildReq(true), apiKey)
            } catch (e: HttpException) {
                if (e.code() == 400) callGemini(buildReq(false), apiKey) else throw e
            }
            val raw = response.candidates?.firstOrNull()?.content?.parts?.mapNotNull { it.text }?.joinToString("").orEmpty()
            val blueprint: UIBlueprint? = if (isReview) {
                applyReviewPatch(previous!!, raw, cleanPrompt)
            } else {
                val parsed = UIBlueprint.parse(raw, cleanPrompt)
                if (parsed != null && brief != null) parsed.copy(concept = brief.idea, fingerprint = brief.fingerprint) else parsed
            }
            if (blueprint == null) {
                val err = "Model returned invalid UIBlueprint JSON. Raw start: ${raw.take(300)}"
                ApiRequestLogger.logRequest(context, "synthesizeBlueprint", websiteName, websiteUrl, cleanPrompt, requestJson, err, false)
                Result.failure(IllegalStateException(err))
            } else {
                storeFor(context).put(cleanPrompt, blueprint, domain, remember = !isReview)
                ApiRequestLogger.logRequest(context, if (isReview) "reviewBlueprint" else "buildBlueprint", websiteName, websiteUrl, cleanPrompt, requestJson, if (isReview) raw.take(4_000) else blueprint.toJson(), true)
                Result.success(blueprint)
            }
        } catch (e: Exception) {
            val msg = describe(e)
            ApiRequestLogger.logRequest(context, "synthesizeBlueprint", websiteName, websiteUrl, cleanPrompt, requestJson, msg, false)
            Result.failure(Exception(msg, e))
        }
    }

    /**
     * The review returns {"problems": [...], "fixCss": "..."}. The fix is appended AFTER the existing CSS, so a review
     * can repair legibility and layout but can never delete or simplify the design. An empty or invalid review leaves
     * the blueprint untouched (same id, so the runtime does not even re-apply it).
     */
    private fun applyReviewPatch(previous: UIBlueprint, raw: String, prompt: String): UIBlueprint {
        val json = try { JSONObject(extractJsonObject(raw) ?: return previous) } catch (_: Exception) { return previous }
        val fix = stripInventedLabels(json.optString("fixCss", "")).trim().take(3_000)
        if (fix.isBlank()) return previous
        val merged = JSONObject(previous.toJson()).put("customCss", previous.customCss + "\n/* review */\n" + fix)
        return UIBlueprint.parse(merged.toString(), prompt)
            ?.copy(concept = previous.concept, fingerprint = previous.fingerprint)
            ?: previous
    }

    private val UI_REVIEW_SYSTEM_PROMPT = """
You are the QA reviewer of a live website redesign on an Android phone. The design is final and approved: you do NOT redesign anything. You receive the user's request, the palette, the CURRENT CSS, a MEASURED AUDIT taken from the live page (ground truth), and usually a screenshot of how it renders now.

You may ONLY append override CSS in `fixCss`. It is added after the current CSS, so you cannot delete anything. Never undo or tone down the design: keep every transform, shadow, texture, pseudo-element, font, palette colour and the backdrop. Add no decoration, no new text, no new UI and no colours outside the palette (a plain black or white is allowed for contrast).

Fix only real defects, in this order:
1. Unreadable text. The audit lists elements whose text has under 3:1 contrast against what is behind it (sel, text, fg, bg, ratio). Fix each by setting `color` (and `background-color` only if the design clearly intends a surface there) using the design's own palette so that it reaches 4.5:1. When many failures share one cause (for example a generic text-colour rule written for a surface that does not exist on those items), fix the cause with one scoped rule instead of many small ones. Check the screenshot too: if text looks hard to read even though the audit lists nothing, fix that as well.
2. Elements clipped, overlapping, pushed off screen, or horizontal overflow (audit overflowX above 2): fix with width, max-width, overflow, margin or position overrides.
3. Fixed or sticky bars covering content: add padding or shrink them.
4. Tap targets under 40px.

Use selectors of the form `html body <selector>` with !important, based on the audit's `sel` values or on selectors already in the CSS. Keep fixCss under 1200 characters. If nothing is wrong, return an empty fixCss.

OUTPUT: ONLY one JSON object, no markdown fences:
{"problems": ["short list of what you found, max 5 items"], "fixCss": "css or empty string"}
""".trimIndent()

    private val UI_CONCEPT_SYSTEM_PROMPT = """
You are the art director of a browser that redesigns any website around the user's imagination. You write no code. You invent one specific, original design idea; a separate builder turns your directions into CSS, a backdrop and a little JS on top of the live page.

THINK, silently: What does the user really want to see or feel? What is this site's real content made of (the outline lists real repeated items)? What are three genuinely different worlds it could live in? Pick the least obvious one that still fully honours the request and keeps the site usable, and give it one memorable signature visual. Reject your first idea, anything a generic "make it look <theme>" would produce by default, and anything resembling RECENT DESIGNS (do not reuse their type voice, palette family, shape language or layout idea unless the request leaves no alternative). For accessibility or comfort requests clarity wins over spectacle, but still give the design an identity.

BUILDER LIMITS: it restyles real page elements with CSS, adds one backdrop layer, and runs small JS. No new content, images or external files; system fonts only. The site's own text, links, search and items must stay readable and usable on a phone. No invented UI (docks, bottom bars, floating buttons, labels, captions) unless the user asked for controls.

OUTPUT: ONLY one JSON object, under 170 words in total. Every value must be concrete (hex colours, named shapes, real behaviours). Vague words like "modern", "futuristic", "sleek" or "immersive" are not decisions.
{
  "considered": ["3 alternative worlds, max 8 words each (for you; the builder never sees them)"],
  "idea": "one sentence: what this page IS",
  "signature": "the one memorable visual or interaction, max 25 words",
  "palette": "4 or 5 colours as hex, each with its job",
  "type": "type voice using system font stacks, max 12 words",
  "surface": "edges, material, depth, texture, max 20 words",
  "layout": "how content is arranged and how that breaks the usual grid, max 25 words",
  "rules": "how each of these behaves in this world: header, search, headings, repeated items or cards, links, buttons, images; max 45 words",
  "fingerprint": "max 6 words: type voice, palette family, shape language, layout idea"
}
""".trimIndent()

    private val UI_SYNTHESIS_SYSTEM_PROMPT = """
You are the front-end builder inside an Android web browser. An art director wrote DESIGN DIRECTIONS for how a live website should look. Implement them as CSS, an optional backdrop and optional JS. The directions are the design: follow them literally and make each part visibly present (palette, type, surface, layout, rules, signature). Do not substitute your own taste.

RUNTIME
- Real elements carry data-mydes="header|nav|search|main|grid|card|title|heading|price|btn|field|media|aside|footer". Select with `html body [data-mydes="X"]`. Roles are hints, not guarantees: the page's main repeating items may NOT be tagged `card`. The outline lists roleCounts, roleSamples and repeatedItems (real tag.class selectors). Whenever the directions talk about items, cards or results, style those real selectors too. Also style standard tags (h1-h3, p, a, ul, li, table, figure, img).
- :root variables: --m-bg --m-surface --m-surface2 --m-fg --m-ink --m-accent, and motion variables --m-rx --m-ry (deg) --m-scroll (px).
- backdropHtml is a scene drawn with inline SVG, CSS or one canvas. It sits behind all page content. The runtime clears full-screen opaque page wrappers so it shows through.
- customJs runs as (MydesBridge, T). Available: enable3DTilt(n), focusSearch(), proxySearch(q), proxyClick(selector), zoom(+/-0.15), speakPage(), onCleanup(fn). Stop anything you start (requestAnimationFrame, setInterval, listeners) with onCleanup(fn).
- No external resources: system fonts, CSS, inline SVG and data URIs only.

HARD RULES
- Never hide or break main, card or search. The page stays interactive.
- TEXT COLOUR: never set `color` on generic tags (p, span, a, div, h1-h3) unless the same rule also sets the background behind them. A surface you style (card, header, item) sets its own background and `color` together and its descendants inherit; everything else inherits --m-fg from the page. All text, including links and text inside items that are not tagged `card`, must stay readable (4.5:1) on whatever is really behind it.
- No invented text: no ::before or ::after content containing words. Icons and bullets are fine.
- Style specific roles. Do not blanket-reset div, section or article.
- Buttons: the btn role includes icon-only buttons (logo, menus). Do not force one filled box on all of them; keep glyphs readable.
- Tap targets at least 40px. Fixed or sticky elements stay under 15% of the screen height.
- Phone performance: at most one canvas, about 120 particles, animate transform and opacity only, honour prefers-reduced-motion.

HUD: default needsHud false, hudHtml "", hudReason "". A themed look is never a reason for a HUD. Only if the user explicitly asked for controls, or an accessibility need cannot be met by restyling, add a few plainly labelled controls that do something useful on THIS site through MydesBridge, and explain why in hudReason.

If no directions are given, first decide a specific original concept yourself (a world, material or process, not a colour scheme), build it, and fill `concept` and `fingerprint`.

OUTPUT: ONLY one valid JSON object, no markdown fences, no commentary:
{
  "name": "short title",
  "dark": true or false,
  "bg": "#rrggbb", "surface": "#rrggbb", "surface2": "#rrggbb", "fg": "#rrggbb", "ink": "#rrggbb", "accent": "#rrggbb",
  "remapSurfaces": true or false (false if your CSS sets surface colours itself),
  "customCss": "complete CSS, using !important where needed",
  "backdropHtml": "SVG/HTML/canvas string, or empty string",
  "needsHud": false,
  "hudReason": "",
  "hudHtml": "",
  "customJs": "JS using MydesBridge and T, or empty string",
  "concept": "(only when no directions were given) one sentence",
  "fingerprint": "(only when no directions were given) max 6 words"
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

    private suspend fun callGemini(request: GeminiRequest, apiKey: String): GeminiResponse {
        var last: Exception? = null
        for (wait in longArrayOf(0, 3_000, 8_000)) {
            if (wait > 0) delay(wait)
            try {
                return api.generateContent(MODEL, apiKey, request)
            } catch (e: HttpException) {
                last = e
                val retryable = e.code() == 429 || e.code() in 500..599
                if (!retryable) throw e
            }
        }
        throw last ?: IllegalStateException("Gemini request failed")
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

    /**
     * Extracts the live DOM outline from the current WebView, sends it with the user's prompt to Gemini,
     * and applies the synthesized 3D / Accessible / Creative UIBlueprint immediately without needing a hard reload.
     */
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
            // Apply live immediately so the user doesn't lose their scroll/session state
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