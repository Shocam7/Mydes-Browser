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
    val hudReason: String = ""
) {
    fun toJson(): String = JSONObject()
        .put("concept", concept)
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
                customCss = j.optString("customCss", ""),
                backdropHtml = j.optString("backdropHtml", ""),
                // The HUD is opt-in. If the model did not explicitly justify it, it is dropped here,
                // so a stray footer/dock can never appear just because the model felt like adding one.
                hudHtml = if (needsHud) j.optString("hudHtml", "") else "",
                customJs = j.optString("customJs", ""),
                concept = j.optString("concept", "").take(300),
                needsHud = needsHud,
                hudReason = j.optString("hudReason", "").take(200)
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
    '#__mydes_backdrop{position:fixed;inset:0;width:100vw;height:100vh;pointer-events:none!important;z-index:0;overflow:hidden;transform:translateZ(0);will-change:transform;}\n' +
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
  if(cfg.remapSurfaces && tag!=='BODY' && tag!=='HTML' && !hasBgImage(cs)){
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
          if(window.__mydes && window.__mydes.outline) return JSON.parse(window.__mydes.outline());
          var counts={};
          ['header','nav','main','aside','footer','form','button','input','img','article','h1','h2'].forEach(function(t){
            counts[t]=document.querySelectorAll(t).length;
          });
          return {url:location.href,host:location.hostname,title:document.title,viewport:{w:innerWidth,h:innerHeight},counts:counts};
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
        val line = (bp.concept.ifBlank { bp.name }).replace('\n', ' ').take(160)
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
        forceFresh: Boolean = false
    ): Result<String> = synthesizeBlueprint(
        context = context,
        prompt = userPrompt,
        websiteName = websiteName,
        websiteUrl = websiteUrl,
        pageOutlineJson = null,
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
        outlineJson: String? = null
    ): Result<String> = synthesizeBlueprint(
        context = context,
        prompt = userPrompt,
        websiteName = websiteName,
        websiteUrl = websiteUrl,
        pageOutlineJson = outlineJson?.takeIf { it.isNotBlank() && it != "null" }
            ?: "{\"viewport\":{\"w\":$screenWidth,\"h\":$screenHeight}}",
        screenshotBase64 = screenshotBase64
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


    // ───── Variation engine ─────
    // These are NOT templates. They are random, orthogonal "provocations" that push the model off its
    // favourite defaults. Each axis has many unrelated options, so the combination space is huge and
    // none of them describes a finished layout. The request itself always wins over the lens.

    private val LENS_LAYOUT = listOf(
        "content flows in a single oversized editorial column with huge type and tiny captions",
        "content is staggered on a diagonal, each block offset from the previous one",
        "content sits on a continuous scrolling surface with no visible containers at all, only spacing and rules",
        "blocks are different sizes like a mosaic or bento arrangement, not equal-width repeats",
        "content hangs from a strong vertical spine or timeline running down the screen",
        "content is set in overlapping layers with generous negative margins and depth",
        "everything is aligned to a hard baseline grid with visible rules, like a printed form or ledger",
        "blocks wrap around organic, curved edges instead of rectangles",
        "wide horizontal bands alternate between full-bleed and inset",
        "content is arranged as stacked sheets/pages that fan slightly"
    )
    private val LENS_SHAPE = listOf(
        "razor-sharp 0px corners everywhere", "very large soft radii and pill shapes",
        "mixed shapes: circles for media, squares for text", "cut corners / chamfers using clip-path",
        "hand-drawn wobbly edges using SVG filters or irregular border-radius", "thick outlines with offset hard shadows",
        "no borders at all, separation by tone only", "hairline 1px borders and lots of white space"
    )
    private val LENS_TYPE = listOf(
        "a high-contrast serif for headings with a plain sans body", "monospace everywhere",
        "very heavy condensed uppercase headings", "light, wide-tracked headings",
        "a humanist/handwritten feel using cursive or italic system stacks", "huge numerals and tiny labels",
        "all-lowercase, quiet typography", "a bold grotesque with tight tracking"
    )
    private val LENS_MATERIAL = listOf(
        "matte paper with subtle grain (CSS gradients)", "flat solid colour blocks", "brushed metal / hardware",
        "frosted or tinted glass but only on one element type", "wood, cloth or leather suggested through gradients",
        "ink on newsprint, strictly two colours", "soft clay / neumorphic relief", "screen-printed poster colours with misregistration offsets",
        "chalk on a dark board", "plain and almost no decoration; the idea lives in the layout and type"
    )
    private val LENS_MOTION = listOf(
        "almost no motion; stillness is the point", "slow ambient drift in the backdrop only",
        "content reveals as you scroll", "gentle hover/press response only", "one signature animation, nothing else",
        "parallax depth driven by --m-scroll"
    )
    private val LENS_HEADER = listOf(
        "header becomes a slim, quiet strip", "header is oversized and part of the composition",
        "header is stripped to the bare minimum and the search field is the hero", "header blends invisibly into the backdrop",
        "header is styled like an object from the concept's world"
    )

    private fun creativeLens(): String {
        val r = java.util.Random()
        fun <T> pick(l: List<T>) = l[r.nextInt(l.size)]
        return "layout: ${pick(LENS_LAYOUT)}; shape language: ${pick(LENS_SHAPE)}; type: ${pick(LENS_TYPE)}; " +
            "material: ${pick(LENS_MATERIAL)}; motion: ${pick(LENS_MOTION)}; header: ${pick(LENS_HEADER)}"
    }

    // ───── Core Generative Synthesis ─────

    private suspend fun synthesizeBlueprint(
        context: Context,
        prompt: String,
        websiteName: String,
        websiteUrl: String,
        pageOutlineJson: String?,
        screenshotBase64: String?,
        forceFresh: Boolean = false
    ): Result<UIBlueprint> = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Empty prompt"))

        val domain = try {
            java.net.URI(websiteUrl).host ?: websiteName
        } catch (_: Exception) {
            websiteName
        }.ifBlank { "*" }

        // Return cached blueprint if we already generated one for this (prompt, domain) pair and no new screenshot is passed
        if (screenshotBase64 == null && !forceFresh) {
            storeFor(context).get(cleanPrompt, domain)?.let { return@withContext Result.success(it) }
        }

        val apiKey = getApiKey(context)
        if (apiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GEMINI_API_KEY is not set. Please set your API key in Settings."))
        }

        // REVIEW MODE: a screenshot plus an existing blueprint for this (prompt, domain) means "fix what you shipped",
        // not "design again". No lens, no nonce, no recent-design blacklist, low temperature.
        val previous = if (!screenshotBase64.isNullOrBlank() && !forceFresh) storeFor(context).get(cleanPrompt, domain) else null
        val isReview = previous != null

        val userContextText = if (isReview) buildString {
            appendLine("REVIEW PASS")
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            if (!pageOutlineJson.isNullOrBlank()) appendLine("LIVE DOM SEMANTIC OUTLINE: $pageOutlineJson")
            appendLine("CURRENT BLUEPRINT (JSON):")
            appendLine(previous!!.toJson())
            appendLine("The attached screenshot is how the current blueprint renders right now.")
            appendLine("Return the corrected complete blueprint JSON now.")
        } else buildString {
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            if (!pageOutlineJson.isNullOrBlank()) {
                appendLine("LIVE DOM SEMANTIC OUTLINE: $pageOutlineJson")
            }
            appendLine()
            appendLine("VARIATION NONCE: ${java.util.UUID.randomUUID()} (this request must produce a design unlike any previous one)")
            appendLine("CREATIVE LENS (a provocation, secondary to the request; blend it, bend it, or drop it if it fights the request): ${creativeLens()}")
            val recent = storeFor(context).recentConcepts()
            if (recent.isNotEmpty()) {
                appendLine("RECENT DESIGNS ALREADY SHIPPED (do NOT resemble any of these in layout, shape language, palette logic or structure):")
                recent.forEach { appendLine("- $it") }
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
                temperature = if (isReview) 0.4f else 1.0f,
                topP = if (isReview) 0.9f else 0.95f,
                maxOutputTokens = 8192,
                responseMimeType = if (jsonMode) "application/json" else null
            )
        )

        val requestJson = try { requestAdapter.toJson(buildReq(true)).take(4_000) } catch (_: Exception) { "(unserializable)" }

        try {
            val response = try {
                callGemini(buildReq(true), apiKey)
            } catch (e: HttpException) {
                if (e.code() == 400) callGemini(buildReq(false), apiKey) else throw e
            }
            val raw = response.candidates?.firstOrNull()?.content?.parts?.mapNotNull { it.text }?.joinToString("").orEmpty()
            val parsed = UIBlueprint.parse(raw, cleanPrompt)
            val blueprint = if (parsed != null && isReview && parsed.concept.isBlank()) parsed.copy(concept = previous!!.concept) else parsed
            if (blueprint == null) {
                val err = "Model returned invalid UIBlueprint JSON. Raw start: ${raw.take(300)}"
                ApiRequestLogger.logRequest(context, "synthesizeBlueprint", websiteName, websiteUrl, cleanPrompt, requestJson, err, false)
                Result.failure(IllegalStateException(err))
            } else {
                storeFor(context).put(cleanPrompt, blueprint, domain, remember = !isReview)
                ApiRequestLogger.logRequest(context, if (isReview) "reviewBlueprint" else "synthesizeBlueprint", websiteName, websiteUrl, cleanPrompt, requestJson, blueprint.toJson(), true)
                Result.success(blueprint)
            }
        } catch (e: Exception) {
            val msg = describe(e)
            ApiRequestLogger.logRequest(context, "synthesizeBlueprint", websiteName, websiteUrl, cleanPrompt, requestJson, msg, false)
            Result.failure(Exception(msg, e))
        }
    }

    private val UI_REVIEW_SYSTEM_PROMPT = """
You are the art director reviewing a live redesign of a website on an Android phone. A designer already produced the CURRENT BLUEPRINT (JSON) and the attached screenshot shows exactly how it renders now. Your job is to FIX it, not to redesign it.

KEEP: the concept, palette logic, layout idea, typography voice, backdrop idea and overall personality. The user has already seen this design and should recognise it after your changes. Do not introduce a new theme, new decorative motifs or extra ornament.

LOOK FOR AND FIX (only what is actually wrong in the screenshot):
- Text that is hard to read: dark text on a dark surface, light on light, low contrast, links that vanish. When you set a background on an element, set `color` for it and its links, headings and spans explicitly.
- Content clipped, overlapping, hidden under the fixed header, the status bar area or any fixed layer; horizontal overflow; elements pushed off screen.
- Fixed or sticky elements taking too much of the screen.
- Decoration that overwhelms the content: heavy frames, repeated boxes around every block, oversized glows, invented labels or text added with ::before/::after. Simplify these. Do not add more.
- Tap targets smaller than 40px, or controls that are hard to find.
- A backdrop that is invisible, misplaced, or fights the text.
- Anything in the layout that looks broken compared with the user's request.

If something already works, leave its CSS untouched. Make the smallest set of changes that resolves the real problems, but still return the COMPLETE blueprint (all fields), because it replaces the previous one.

HUD: keep needsHud/hudHtml exactly as in the current blueprint, unless the HUD is covering content or is clearly irrelevant to the site, in which case remove it (needsHud false, hudHtml ""). Never add a HUD, footer, dock or bottom bar.

JS: keep customJs unless it is broken. Anything started with requestAnimationFrame, setInterval or event listeners must be stopped by registering a function with MydesBridge.onCleanup(fn).

Technical rules are unchanged: selectors use `html body [data-mydes="X"]` (header nav search main grid card title heading price btn field media aside footer) and standard tags; never hide or disable main, card or search; no external resources; phone-friendly performance.

OUTPUT: ONLY one valid JSON object with the same keys as the current blueprint: concept (keep it), name, dark, bg, surface, surface2, fg, ink, accent, remapSurfaces, customCss, backdropHtml, needsHud, hudReason, hudHtml, customJs. No markdown fences, no commentary.

""".trimIndent()

    private val UI_SYNTHESIS_SYSTEM_PROMPT = """
You are a fearless, original UI designer inside an Android web browser. The user describes how they want a website to look, feel or behave, and you redesign the LIVE page by writing CSS, an optional backdrop layer, and optional JS. Every redesign you produce must look like it came from a different designer with a different idea. Repetition is the failure mode to avoid.

RUNTIME FACTS (the only technical constraints):
1. The browser tags real DOM elements so your CSS can target any website. Selector form: `html body [data-mydes="X"]`
   header (site header) | nav | search (search form) | main | grid (feed/list container) | card (item/result/article) | title | heading | price | btn | field (inputs/selects) | media (images) | aside | footer
   Tags can be missing on some pages (a Wikipedia main page has few cards). Also style plain tags (h1-h3, p, a, ul, table, img, section, figure) when that serves the design.
2. CSS variables on :root: --m-bg --m-surface --m-surface2 --m-fg --m-ink --m-accent, and live motion variables --m-rx, --m-ry (deg), --m-scroll (px). Using them is optional.
3. Layers:
   - backdropHtml goes inside #__mydes_backdrop (position:fixed; inset:0; pointer-events:none; z-index:0). It is the world behind the content: SVG, CSS shapes, canvas.
   - hudHtml goes inside #__mydes_hud (position:fixed; inset:0; pointer-events:none; children get pointer-events:auto). OPT-IN ONLY, see HUD POLICY.
   - customJs runs as (MydesBridge, T). Available: MydesBridge.enable3DTilt(n), focusSearch(), proxySearch(q), proxyClick(selector), zoom(+/-0.15), speakPage(), onCleanup(fn). Anything you start with requestAnimationFrame, setInterval or addEventListener MUST be stopped by a function registered with MydesBridge.onCleanup(fn), because the theme can be replaced live.
4. No external resources: no remote fonts, images or scripts. Use system font stacks, CSS, inline SVG and data URIs.

HARD RULES (these protect the page; they say nothing about style):
- Never hide, remove or make unusable [data-mydes="main"], [data-mydes="card"] or [data-mydes="search"]. The real page stays interactive.
- Legibility is mandatory. Whenever you set a background on an element, also set `color` on it and make links (a, a:visited), headings, spans and list items inside it readable on that background (contrast at least 4.5:1). Never leave the site's default dark text on your dark surface, or light text on your light one.
- Tap targets stay at least 40px tall. Do not let fixed or sticky elements cover more than about 15% of the screen height.
- Performance on a phone: at most one canvas, at most ~120 animated particles, animate transform/opacity only, and respect prefers-reduced-motion.
- Do not rely on exact class names of the site unless you are sure they exist; prefer the data-mydes tags and standard elements.

CREATIVE PROCESS (follow in this order):
1. CONCEPT. Before any code, write the `concept` field: one concrete sentence describing what this page IS in the world the user asked for, built from the user's subject and this site's actual content. "Dark theme with glowing cards" is not a concept. Something with a specific physical or cultural idea is.
2. RESTRUCTURE, DON'T JUST RECOLOUR. Decide how content is arranged and read: layout geometry, shape language, scale and density, type voice, material, how lists/links/media/headings behave, and how the header and search look. Change at least four of those. Let the concept decide `dark`; light designs are as valid as dark ones.
3. ONE STRONG IDEA. Prefer one memorable, well-executed idea over many stacked effects. If a backdrop does not add to the concept, return an empty string. Draw backdrops specifically (an actual scene, pattern or structure from the concept), not generic gradient blobs.
4. ORIGINALITY CHECK. Ask yourself if someone could guess your CSS from the last redesign you would write for any similar request. If yes, throw the idea away and pick a different one. You may receive a CREATIVE LENS (a provocation to bend or blend) and a list of RECENT DESIGNS. Treat the recent list as a blacklist for layout, shape language, palette logic and structure.

DEFAULTS TO AVOID (these are the habits that make output feel templated; use one only when the request literally demands it):
- Framing titles or hero text as banners, signs, tickets, plaques, labelled placards or boxes. Do not inject invented labels, captions or taglines with ::before/::after; text on the page should be the site's own.
- The same rounded dark-glass card with a neon border, blur and glow, repeated on everything.
- Giving every card an identical tilt or identical treatment. Vary scale, rhythm and emphasis between elements.
- Navy + cyan/amber neon palettes, and Space Grotesk/Orbitron style type, as a reflex.
- Radial-gradient circles as stand-ins for objects.
- Any extra UI that the site does not already have (docks, bottom bars, floating buttons, footers).

HUD POLICY:
- Default: needsHud false, hudHtml "", hudReason "". The vast majority of requests need no HUD. A themed look is never a reason for one.
- Set needsHud true only if the user explicitly asks for controls, a toolbar or dock, or an assistive need (for example easy reading, voice, bigger text for someone with low tech skill) truly cannot be met by restyling alone.
- If you do add a HUD, tailor it to the concept and the user's need: only controls that do something useful on THIS site through MydesBridge, plainly labelled, as few as possible. Explain why in hudReason. Never reuse a generic "search / audio / top" trio.

ACCESSIBILITY REQUESTS (low vision, elderly user, simplicity, calm, focus, etc.): the same creative freedom applies, but clarity wins over spectacle. Cut clutter, enlarge text and targets, increase contrast, and still find an identity for the design instead of falling back on a stock high-contrast template.

OUTPUT FORMAT:
Return ONLY one valid JSON object (no markdown fences, no commentary). Write `concept` FIRST so the idea comes before the code:
{
  "concept": "one concrete sentence: the idea this page embodies",
  "name": "short title",
  "dark": true or false,
  "bg": "#rrggbb",
  "surface": "#rrggbb",
  "surface2": "#rrggbb",
  "fg": "#rrggbb",
  "ink": "#rrggbb",
  "accent": "#rrggbb",
  "remapSurfaces": true or false (false if your CSS styles surfaces itself, e.g. translucent or custom backgrounds),
  "customCss": "complete CSS targeting [data-mydes=...] and standard tags, using !important where needed",
  "backdropHtml": "HTML/SVG/canvas string for the backdrop, or empty string",
  "needsHud": false,
  "hudReason": "",
  "hudHtml": "",
  "customJs": "JS using MydesBridge and T, or empty string"
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