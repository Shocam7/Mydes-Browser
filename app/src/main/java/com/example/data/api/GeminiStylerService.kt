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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.json.JSONTokener
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/*
 * HOW THIS WORKS (the page is never replaced, so the site keeps working)
 *
 *  1. MydesRuntime  - JS injected into the LIVE page. A deterministic tagger labels the site's real
 *                     elements with data-mydes="card|grid|nav|btn|..." (no LLM, runs on every page and on
 *                     every DOM change). A stylesheet is applied on top. Nothing is removed or rebuilt, so
 *                     the site's own JS, logins, carts and forms keep working.
 *  2. GeminiStylerService - writes the design ONCE per site as CSS that targets those roles (a "kit"),
 *                     and, only when a page type looks broken, a small per-template "delta".
 *  3. KitStore      - caches kits/deltas on disk, so later pages and later sessions cost zero LLM time.
 *  4. SiteThemeController - wires it into your WebViewClient.
 *
 * Needs: implementation("androidx.webkit:webkit:1.9.0") or newer.
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
data class GenerationConfig(val temperature: Float = 0.4f, val maxOutputTokens: Int? = null)

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

data class GeneratedCss(val css: String, val concept: String?)

// ───────────────────── 1. Runtime injected into the live page ─────────────────────

object MydesRuntime {

    /** Body of an IIFE. Expects `KIT` and `DELTA` (CSS strings) to be defined by [script]. */
    private const val CORE = """
if(window.__mydes){window.__mydes.setKit(KIT);window.__mydes.setDelta(DELTA);return;}
var adopted=('adoptedStyleSheets' in document)&&typeof CSSStyleSheet!=='undefined'&&('replaceSync' in CSSStyleSheet.prototype);
var sK=null,sD=null,eK=null,eD=null;
function sync(){
  try{
    if(adopted){
      if(!sK){sK=new CSSStyleSheet();sD=new CSSStyleSheet();}
      if(sK.__c!==KIT){sK.replaceSync(KIT);sK.__c=KIT;}
      if(sD.__c!==DELTA){sD.replaceSync(DELTA);sD.__c=DELTA;}
      var a=document.adoptedStyleSheets;
      if(a[a.length-2]!==sK||a[a.length-1]!==sD){
        document.adoptedStyleSheets=Array.prototype.filter.call(a,function(s){return s!==sK&&s!==sD;}).concat([sK,sD]);
      }
    }else{
      var root=document.head||document.documentElement;if(!root)return;
      if(!eK||!eK.isConnected){eK=document.createElement('style');root.appendChild(eK);}
      if(!eD||!eD.isConnected){eD=document.createElement('style');root.appendChild(eD);}
      if(eK.textContent!==KIT)eK.textContent=KIT;
      if(eD.textContent!==DELTA)eD.textContent=DELTA;
    }
  }catch(e){}
}
function each(sel,fn){var l=document.querySelectorAll(sel);for(var i=0;i<l.length;i++)fn(l[i]);}
function put(e,r){if(e.getAttribute('data-mydes')!==r)e.setAttribute('data-mydes',r);}
function putNew(e,r){if(!e.hasAttribute('data-mydes'))e.setAttribute('data-mydes',r);}
var PRICE=/^\s*(?:(?:[\u0024\u20AC\u00A3\u00A5\u20B9]|USD|EUR|GBP|INR|Rs\.?)\s?\d[\d,.]*|\d[\d,.]*\s?(?:[\u0024\u20AC\u00A3\u00A5\u20B9]|USD|EUR|GBP|INR))\s*$/;
function inContent(e){return !!e.closest('main,article,section,[role="main"]');}
function sig(e){return e.tagName+'.'+(e.classList[0]||'')+'.'+(e.classList[1]||'');}
function cardLike(e){
  if(e.closest('nav,footer,[data-mydes="nav"],[data-mydes="footer"]'))return false;
  var link=e.tagName==='A'||!!e.querySelector('a[href]');
  if(!link)return false;
  var t=(e.textContent||'').trim().length;
  if(t<6||t>700)return false;
  return !!e.querySelector('img,picture,video')||t>=40;
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
  var ps=document.body.querySelectorAll('*'),n=0;
  for(var i=ps.length-1;i>=0&&n<2500;i--){
    var p=ps[i],k=p.children.length;
    if(k<3||k>80)continue;
    n++;
    if(p.getAttribute('data-mydes')==='grid'){
      for(var q=0;q<k;q++){var c0=p.children[q];if(!c0.hasAttribute('data-mydes')&&cardLike(c0)){c0.setAttribute('data-mydes','card');decorate(c0);}}
      continue;
    }
    if(p.closest('nav,footer,header,[data-mydes="nav"],[data-mydes="footer"],[data-mydes="header"]'))continue;
    if(p.querySelector('[data-mydes="card"]'))continue;
    var groups={},best=null,j;
    for(j=0;j<k;j++){var s=sig(p.children[j]);(groups[s]=groups[s]||[]).push(p.children[j]);}
    for(var g in groups){if(!best||groups[g].length>best.length)best=groups[g];}
    if(best.length<3||best.length<k*0.6)continue;
    var cards=best.filter(cardLike);
    if(cards.length<3||cards.length<best.length*0.7)continue;
    p.setAttribute('data-mydes','grid');
    cards.forEach(function(c){c.setAttribute('data-mydes','card');decorate(c);});
  }
}
function markPrices(){
  var l=document.querySelectorAll('span,div,p,strong,b,em,ins,del,s,bdi'),n=0;
  for(var i=0;i<l.length&&n<400;i++){
    var e=l[i];
    if(e.children.length||e.hasAttribute('data-mydes'))continue;
    var t=e.textContent||'';
    if(t.length>24)continue;
    if(PRICE.test(t)){e.setAttribute('data-mydes','price');n++;}
  }
}
function markProse(){
  var ps=document.querySelectorAll('p'),map=new Map();
  for(var i=0;i<ps.length;i++){
    var p=ps[i];
    if((p.textContent||'').length>=80&&p.parentElement&&!p.closest('[data-mydes="card"]'))map.set(p.parentElement,(map.get(p.parentElement)||0)+1);
  }
  map.forEach(function(c,el){if(c>=3)putNew(el,'prose');});
}
function markHero(){
  if(document.querySelector('[data-mydes="hero"]'))return;
  var imgs=document.querySelectorAll('img');
  for(var i=0;i<imgs.length&&i<8;i++){
    var im=imgs[i];
    if(im.closest('[data-mydes="card"],[data-mydes="nav"],[data-mydes="header"],[data-mydes="footer"]'))continue;
    var r=im.getBoundingClientRect();
    if(r.width>=innerWidth*0.6&&r.height>=120&&r.top<innerHeight*1.5){
      var a=im.parentElement,d=0;
      while(a&&d<4&&a!==document.body){
        if(a.querySelector('h1,h2,[data-mydes="title"]')){putNew(a,'hero');return;}
        a=a.parentElement;d++;
      }
      return;
    }
  }
}
function retag(){
  if(!document.body)return;
  var t0=performance.now();
  each('header,[role="banner"]',function(e){if(!inContent(e))put(e,'header');});
  each('nav,[role="navigation"]',function(e){put(e,'nav');});
  each('footer,[role="contentinfo"]',function(e){if(!inContent(e))put(e,'footer');});
  each('main,[role="main"]',function(e){put(e,'main');});
  each('aside,[role="complementary"]',function(e){putNew(e,'aside');});
  each('form[role="search"],input[type="search"],input[name="q"],input[name="s"],input[name="k"],input[name="query"],input[name="search"]',function(e){var f=e.tagName==='FORM'?e:e.closest('form');if(f)putNew(f,'search');});
  if(performance.now()-t0<25)findGrids();
  each('h1',function(e){putNew(e,'title');});
  each('h2,h3,h4,h5,h6',function(e){putNew(e,'heading');});
  each('button,input[type="submit"],input[type="button"],[role="button"],a[class*="btn"],a[class*="button"]',function(e){putNew(e,'btn');});
  each('input:not([type="hidden"]):not([type="submit"]):not([type="button"]):not([type="checkbox"]):not([type="radio"]),textarea,select',function(e){putNew(e,'field');});
  each('img',function(e){putNew(e,'media');});
  markPrices();
  markProse();
  if(performance.now()-t0<40)markHero();
}
function summary(){
  var cs=getComputedStyle(document.body),counts={},lines=[];
  each('[data-mydes]',function(e){var r=e.getAttribute('data-mydes');counts[r]=(counts[r]||0)+1;});
  function txt(e,n){return (e.textContent||'').replace(/\s+/g,' ').trim().slice(0,n);}
  (function walk(e,d){
    if(lines.length>=70||d>8)return;
    var role=e.getAttribute('data-mydes'),nd=d;
    if(role==='card'){var ps=e.previousElementSibling;if(ps&&ps.getAttribute('data-mydes')==='card')return;}
    if(role&&role!=='media'&&role!=='field'){
      var s=role+' <'+e.tagName.toLowerCase()+(e.classList[0]?'.'+e.classList[0]:'')+'>';
      if(role==='grid'){var c=0;for(var i=0;i<e.children.length;i++)if(e.children[i].getAttribute('data-mydes')==='card')c++;s+=' cards='+c;}
      if(/^(title|heading|btn|price|hero)$/.test(role))s+=' "'+txt(e,40)+'"';
      lines.push(new Array(d+1).join('  ')+s);
      nd=d+1;
    }
    for(var j=0;j<e.children.length;j++)walk(e.children[j],nd);
  })(document.body,0);
  return JSON.stringify({url:location.href,title:(document.title||'').slice(0,80),viewport:{w:innerWidth,h:innerHeight},bg:cs.backgroundColor,fg:cs.color,font:(cs.fontFamily||'').slice(0,80),counts:counts,outline:lines});
}
function rgba(s){
  var m=/rgba?\(([^)]+)\)/.exec(s||'');
  if(!m)return null;
  var p=m[1].split(/[ ,\/]+/).filter(Boolean).map(parseFloat);
  if(p.length<3||p.some(isNaN))return null;
  return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1};
}
function lum(c){function f(v){v/=255;return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4);}return 0.2126*f(c.r)+0.7152*f(c.g)+0.0722*f(c.b);}
function contrast(a,b){var x=lum(a),y=lum(b);return (Math.max(x,y)+0.05)/(Math.min(x,y)+0.05);}
function effBg(e){
  for(var n=e;n&&n.nodeType===1;n=n.parentElement){
    var c=rgba(getComputedStyle(n).backgroundColor);
    if(c&&c.a>0.5)return c;
  }
  return {r:255,g:255,b:255,a:1};
}
function health(){
  var ov=document.documentElement.scrollWidth>innerWidth+2,low=0,tested=0;
  var els=document.querySelectorAll('[data-mydes="heading"],[data-mydes="title"],[data-mydes="btn"],[data-mydes="price"],[data-mydes="nav"] a,[data-mydes="card"] a,[data-mydes="prose"] p');
  for(var i=0;i<els.length&&tested<80;i++){
    var e=els[i];
    if(!(e.textContent||'').trim())continue;
    var r=e.getBoundingClientRect();
    if(r.width<2||r.height<2)continue;
    var fg=rgba(getComputedStyle(e).color),bg=effBg(e);
    if(!fg||!bg)continue;
    tested++;
    if(contrast(fg,bg)<3)low++;
  }
  var cover=0;
  each('[data-mydes]',function(e){
    if(getComputedStyle(e).position==='fixed'){var r=e.getBoundingClientRect();if(r.width*r.height>0.4*innerWidth*innerHeight)cover++;}
  });
  return JSON.stringify({overflowX:ov,lowContrast:low,tested:tested,fixedCover:cover});
}
var timer=0,delay=120;
function schedule(){
  if(timer)return;
  timer=setTimeout(function(){
    timer=0;
    var t0=performance.now();
    try{retag();}catch(e){}
    sync();
    delay=Math.min(1500,Math.max(120,(performance.now()-t0)*8));
  },delay);
}
window.__mydes={
  setKit:function(c){KIT=c;sync();},
  setDelta:function(c){DELTA=c;sync();},
  retag:function(){retag();},
  summary:summary,
  health:health
};
sync();
try{new MutationObserver(schedule).observe(document,{childList:true,subtree:true});}catch(e){}
document.addEventListener('DOMContentLoaded',schedule);
window.addEventListener('load',schedule);
schedule();
"""

    /**
     * Idempotent: the first call installs the tagger + stylesheet, later calls just swap the CSS.
     * Styles are applied with constructable stylesheets, which (unlike <style> tags) are not
     * blocked by a site's Content-Security-Policy.
     */
    fun script(kitCss: String, deltaCss: String): String =
        "(function(){var KIT=" + JSONObject.quote(kitCss) +
            ",DELTA=" + JSONObject.quote(deltaCss) + ";" + CORE + "\n})();"
}

// ───────────────────── 3. Disk cache ─────────────────────

class KitStore(context: Context) {
    private val dir = File(context.filesDir, "mydes_kits").apply { mkdirs() }

    fun themeId(prompt: String): String = sha1("theme|" + prompt.trim().lowercase()).take(10)

    fun getKit(origin: String, themeId: String): String? = read("kit|$origin|$themeId")
    fun putKit(origin: String, themeId: String, css: String) =
        write("kit|$origin|$themeId", "kit", origin, themeId, css)

    fun getDelta(origin: String, themeId: String, template: String): String? =
        read("delta|$origin|$themeId|$template")

    fun putDelta(origin: String, themeId: String, template: String, css: String) =
        write("delta|$origin|$themeId|$template", "delta", origin, themeId, css)

    /** origin -> kit CSS for every site already designed in this theme. */
    fun allKits(themeId: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        dir.listFiles()?.forEach { f ->
            try {
                val j = JSONObject(f.readText())
                if (j.optString("kind") == "kit" && j.optString("theme") == themeId) {
                    out[j.getString("origin")] = j.getString("css")
                }
            } catch (_: Exception) {
            }
        }
        return out
    }

    private fun read(key: String): String? = try {
        File(dir, sha1(key) + ".json").takeIf { it.exists() }?.let { JSONObject(it.readText()).getString("css") }
    } catch (_: Exception) {
        null
    }

    private fun write(key: String, kind: String, origin: String, theme: String, css: String) {
        try {
            File(dir, sha1(key) + ".json").writeText(
                JSONObject().put("kind", kind).put("origin", origin).put("theme", theme).put("css", css).toString()
            )
        } catch (_: Exception) {
        }
    }

    private fun sha1(s: String) =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

// ───────────────────── URL helpers ─────────────────────

object SiteKeys {
    fun originOf(url: String): String? {
        val u = Uri.parse(url)
        val scheme = u.scheme ?: return null
        val authority = u.encodedAuthority ?: return null
        return if (scheme == "http" || scheme == "https") "$scheme://$authority" else null
    }

    /**
     * Groups URLs by page type, e.g. /dp/B08X1234/ref=sr_1 -> "/dp/:id", /blog/my-first-post -> "/blog/:slug".
     * Deltas (per-page-type repairs) are cached under this key.
     */
    fun templateKey(url: String): String {
        val u = Uri.parse(url)
        val segs = (u.pathSegments ?: emptyList()).take(3).map { s ->
            when {
                s.any { it.isDigit() } && s.length > 3 -> ":id"
                s.count { it == '-' } >= 2 || s.length > 24 -> ":slug"
                else -> s
            }
        }
        return (u.host ?: "") + "/" + segs.joinToString("/")
    }
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

// ───────────────────── 2. The Gemini client ─────────────────────

class GeminiStylerService {

    companion object {
        /** Faster Gemini model ID to prevent timeouts. */
        const val MODEL = "gemini-3.5-flash-lite"
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val requestAdapter = moshi.adapter(GeminiRequest::class.java)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val api = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()
        .create(GeminiApi::class.java)

    /** Designs a whole site once. [summaryJson] comes from `window.__mydes.summary()`. */
    suspend fun generateUserScript(
        context: Context,
        userPrompt: String,
        websiteName: String,
        websiteUrl: String
    ): Result<String> {
        val host = Uri.parse(websiteUrl).host ?: websiteName
        val res = generateKit(context, userPrompt, host, "{\"url\":\"$websiteUrl\",\"title\":\"$websiteName\",\"counts\":{}}")
        return res.map { generated ->
            MydesRuntime.script(generated.css, "")
        }
    }

    suspend fun generateUserScriptWithFeedback(
        context: Context,
        userPrompt: String,
        websiteName: String,
        websiteUrl: String,
        screenshotBase64: String?,
        screenWidth: Int,
        screenHeight: Int
    ): Result<String> {
        val host = Uri.parse(websiteUrl).host ?: websiteName
        val kitRes = generateKit(context, userPrompt, host, "{\"url\":\"$websiteUrl\",\"title\":\"$websiteName\",\"counts\":{}}")
        val kitCss = kitRes.getOrNull()?.css ?: ""
        val deltaRes = generateDelta(context, userPrompt, host, kitCss, "{\"url\":\"$websiteUrl\"}", "{\"overflowX\":false,\"lowContrast\":0}", screenshotBase64)
        return deltaRes.map { generated ->
            MydesRuntime.script(kitCss, generated.css)
        }.recover {
            MydesRuntime.script(kitCss, "")
        }
    }

    suspend fun adaptUserScriptForWebpage(
        context: Context,
        basePrompt: String,
        baseScript: String,
        pageTitle: String,
        pageUrl: String,
        domain: String
    ): Result<String> {
        val kitRes = generateKit(context, basePrompt, domain, "{\"url\":\"$pageUrl\",\"title\":\"$pageTitle\",\"counts\":{}}")
        return kitRes.map { generated ->
            MydesRuntime.script(generated.css, "")
        }
    }

    suspend fun generateKit(
        context: Context,
        themePrompt: String,
        host: String,
        summaryJson: String
    ): Result<GeneratedCss> = callModel(
        context, "generateKit", host, themePrompt,
        system = kitSystemPrompt(themePrompt, host),
        userText = "PAGE SUMMARY (tagged outline of one page; your CSS will run on every page of the site):\n" +
            summaryJson.take(8_000),
        screenshot = null, temperature = 0.9f, maxTokens = 4_096
    )

    /** Small per-page-type repair stylesheet, layered after the kit. */
    suspend fun generateDelta(
        context: Context,
        themePrompt: String,
        host: String,
        kitCss: String,
        summaryJson: String,
        healthJson: String,
        screenshotBase64: String?
    ): Result<GeneratedCss> = callModel(
        context, "generateDelta", host, themePrompt,
        system = deltaSystemPrompt(themePrompt, host),
        userText = "HEALTH REPORT:\n$healthJson\n\nPAGE SUMMARY:\n${summaryJson.take(6_000)}\n\n" +
            "KIT CSS ALREADY APPLIED:\n${kitCss.take(12_000)}",
        screenshot = screenshotBase64, temperature = 0.4f, maxTokens = 2_048
    )

    private suspend fun callModel(
        context: Context,
        op: String,
        host: String,
        themePrompt: String,
        system: String,
        userText: String,
        screenshot: String?,
        temperature: Float,
        maxTokens: Int
    ): Result<GeneratedCss> = withContext(Dispatchers.IO) {
        val apiKey = getApiKey(context)
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext Result.failure(IllegalStateException("GEMINI_API_KEY is not set. Please set your API key in Settings."))
        }
        val parts = mutableListOf(Part(text = userText))
        if (!screenshot.isNullOrBlank()) parts += Part(inlineData = InlineData("image/jpeg", screenshot))
        val request = GeminiRequest(
            contents = listOf(Content(parts)),
            systemInstruction = Content(listOf(Part(text = system))),
            generationConfig = GenerationConfig(temperature, maxTokens)
        )
        val requestJson = try { requestAdapter.toJson(request).take(6_000) } catch (_: Exception) { "(unserializable)" }
        try {
            val response = callGemini(request, apiKey)
            val raw = response.candidates?.firstOrNull()?.content?.parts
                ?.mapNotNull { it.text }?.joinToString("").orEmpty()
            val css = cleanCss(raw)
            if (css == null) {
                val err = "Model returned no usable CSS. Raw start: ${raw.take(300)}"
                ApiRequestLogger.logRequest(context, op, host, host, themePrompt, requestJson, err, false)
                Result.failure(IllegalStateException(err))
            } else {
                ApiRequestLogger.logRequest(context, op, host, host, themePrompt, requestJson, css.css, true)
                Result.success(css)
            }
        } catch (e: Exception) {
            val msg = describe(e)
            ApiRequestLogger.logRequest(context, op, host, host, themePrompt, requestJson, msg, false)
            Result.failure(Exception(msg, e))
        }
    }

    // ───── prompts ─────

    private val hardRules = """
HARD RULES (they keep the site fully working)
1. Never hide or disable things the user needs: no display:none, visibility:hidden, opacity:0, pointer-events:none or zero sizes on btn, field, search, nav, header, main, card, title, price.
2. Do not put transform, filter, perspective, contain or overflow:hidden on html, body, main or header (they break position:fixed/sticky and scrolling).
3. Beat the site's own CSS: start selectors with `html body` and add !important to layout and colour properties (display, grid-*, flex-*, position, width/height, margin/padding, color, background, font-*).
4. Keep text readable: contrast at least 4.5:1. Whenever you change a container's background, also set the colour of its descendants (headings, links, price, buttons) because the site sets its own colours.
5. No horizontal scrolling of the page itself (inner carousels are fine). Touch targets at least 44px.
6. Standard CSS only. No @import, no web fonts, no external url(). Use system font stacks (system-ui, ui-serif, Georgia, ui-monospace, "Roboto", "Roboto Condensed", cursive). Tiny inline data:image/svg+xml is fine.
7. If you add fixed or sticky bars, add matching padding to body so content is not covered.
8. Keep it under about 250 lines. Return ONLY raw CSS, no markdown fences, no explanation. The first line must be: /* CONCEPT: one sentence describing the composition and how it breaks from the original */
""".trimIndent()

    private fun kitSystemPrompt(themePrompt: String, host: String) = """
You are a world-class creative director and CSS artist. You REDESIGN a website by writing ONE stylesheet that is injected on top of the LIVE site. The real DOM, JavaScript, forms, logins and carts keep working, so you never remove or replace elements; you re-compose them.

THE BRIEF
Site: $host
Creative direction from the user: "$themePrompt"

HOW THE PAGE IS LABELLED
The app tags the real elements with data-mydes="ROLE". Roles: header, nav, footer, main, aside, search, hero, grid, card, title (the h1), heading (h2-h6, or a card's title), btn, field, media (img), price, prose (long text block). A "grid" contains "card" children. The summary shows the tagged outline of ONE page, but your CSS is applied to EVERY page (home, listings, product, article, cart, login). Therefore:
- Style by role only, e.g. html body [data-mydes="card"]. Never depend on the site's own class names or ids.
- Every rule must degrade gracefully if a role is absent or appears in another context (a card in a sidebar, a heading inside a form).
- Define the palette and type scale once as CSS variables on :root (--m-bg, --m-fg, --m-accent, ...) and reuse them. That is what keeps every page consistent.

WHAT "REDESIGN" MEANS
This must feel like a different product, not a re-skin. Changing colours, borders and fonts alone is a FAILURE. Change the composition with structural CSS:
- grid: horizontal scroll-snap carousel, bento or masonry layout (CSS grid / columns), overlapping card deck, oversized single-column editorial feed.
- card: new internal layout (media as full-bleed background with text overlaid via grid stacking, numbered with counters and ::before, tilted, glass, cut-corner with clip-path).
- header / nav: floating pill, bottom tab bar (position:fixed), compact icon-style row. search: expanding capsule.
- hero: full-bleed poster with huge type. prose: magazine measure and drop caps (::first-letter).
- Decoration through ::before / ::after, gradients, layered shadows, backdrop-filter, blend modes, and @keyframes (transform and opacity only; honour prefers-reduced-motion).
Commit to ONE bold concept drawn from the creative direction and express it in layout, typography, imagery treatment and motion.

$hardRules
""".trimIndent()

    private fun deltaSystemPrompt(themePrompt: String, host: String) = """
You are a CSS art director fixing one page type of a redesigned website ($host, theme: "$themePrompt").
A site-wide stylesheet (the KIT) is already applied. On this kind of page it has problems, described by the health report (overflowX = page scrolls sideways, lowContrast = number of unreadable text samples, fixedCover = fixed elements covering most of the screen) and, if attached, a screenshot of the current render.
Write a SMALL delta stylesheet that is applied after the kit for pages of this type only. Fix the problems and any other visible breakage (overlap, clipped or unreadable text, huge empty areas), keeping the kit's concept, palette variables and style. Do not repeat the kit.
Elements are labelled with data-mydes roles (header, nav, footer, main, aside, search, hero, grid, card, title, heading, btn, field, media, price, prose). Style by role.

$hardRules
""".trimIndent()

    private fun getApiKey(context: Context): String {
        val prefs = context.getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
        val customKey = prefs.getString("custom_api_key", "") ?: ""
        if (customKey.isNotBlank()) {
            return customKey
        }
        val buildConfigKey = BuildConfig.GEMINI_API_KEY
        if (buildConfigKey.isNotBlank() && buildConfigKey != "MY_GEMINI_API_KEY") {
            return buildConfigKey
        }
        return customKey
    }

    // ───── networking ─────

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

    /** CSS survives truncation (the parser closes open blocks), so no repair step is needed. */
    private fun cleanCss(raw: String): GeneratedCss? {
        var s = raw.trim()
        Regex("```(?:css)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE).find(s)?.let { s = it.groupValues[1].trim() }
        s = s.replace(Regex("</?style[^>]*>", RegexOption.IGNORE_CASE), "")
            .replace(Regex("@import[^;]*;", RegexOption.IGNORE_CASE), "")
            .trim()
        if (s.length < 20 || s.contains("<script", ignoreCase = true)) return null
        val concept = Regex("/\\*\\s*CONCEPT:([\\s\\S]*?)\\*/", RegexOption.IGNORE_CASE)
            .find(s)?.groupValues?.get(1)?.trim()
        return GeneratedCss(s.take(60_000), concept)
    }
}

// ───────────────────── 4. Wiring into the browser ─────────────────────

/**
 * Use a main-dispatcher scope (e.g. lifecycleScope).
 *
 *   val theme = SiteThemeController(ctx, webView, GeminiStylerService(), KitStore(ctx), lifecycleScope)
 *   theme.setTheme("solar system")
 *   webView.webViewClient = object : WebViewClient() {
 *       override fun onPageCommitVisible(v: WebView, url: String) = theme.onPageCommitVisible(url)
 *       override fun onPageFinished(v: WebView, url: String) = theme.onPageFinished(url)
 *       override fun doUpdateVisitedHistory(v: WebView, url: String, isReload: Boolean) = theme.onUrlChanged(url)
 *   }
 */
class SiteThemeController(
    private val context: Context,
    private val webView: WebView,
    private val service: GeminiStylerService,
    private val store: KitStore,
    private val scope: CoroutineScope
) {
    var enabled = true
    var onConcept: ((host: String, concept: String?) -> Unit)? = null
    var onError: ((message: String) -> Unit)? = null

    private var themePrompt = ""
    private var themeId = ""
    private val docStartOk = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    private val docStart = mutableMapOf<String, ScriptHandler>()
    private val busy = mutableSetOf<String>()
    private val checked = mutableSetOf<String>()

    /** Switching theme swaps every cached kit; sites not yet designed in this theme are designed on first visit. */
    fun setTheme(prompt: String) {
        themePrompt = prompt.trim()
        themeId = store.themeId(themePrompt)
        docStart.values.forEach { try { it.remove() } catch (_: Exception) {} }
        docStart.clear()
        checked.clear()
        if (themePrompt.isNotEmpty()) store.allKits(themeId).forEach { (origin, css) -> register(origin, css) }
    }

    /** Fires when the new page first paints: put cached CSS (kit + page-type delta) on it immediately. */
    fun onPageCommitVisible(url: String) {
        if (!enabled || themePrompt.isEmpty()) return
        scope.launch {
            val origin = SiteKeys.originOf(url) ?: return@launch
            val kit = store.getKit(origin, themeId) ?: return@launch
            val delta = store.getDelta(origin, themeId, SiteKeys.templateKey(url)) ?: ""
            eval(MydesRuntime.script(kit, delta))
        }
    }

    fun onPageFinished(url: String) {
        if (enabled && themePrompt.isNotEmpty()) scope.launch { ensureTheme(url) }
    }

    /** Also fires for single-page-app route changes, which never trigger onPageFinished. */
    fun onUrlChanged(url: String) = onPageFinished(url)

    // ───── internals ─────

    private fun register(origin: String, kit: String) {
        if (!docStartOk) return
        docStart.remove(origin)?.let { try { it.remove() } catch (_: Exception) {} }
        try {
            docStart[origin] = WebViewCompat.addDocumentStartJavaScript(webView, MydesRuntime.script(kit, ""), setOf(origin))
        } catch (_: Exception) {
        }
    }

    private suspend fun ensureTheme(url: String) {
        val origin = SiteKeys.originOf(url) ?: return
        val host = Uri.parse(url).host ?: return
        val template = SiteKeys.templateKey(url)
        val lock = "$origin|$template"
        if (!busy.add(lock)) return
        try {
            var kit = store.getKit(origin, themeId)
            if (kit == null) {
                // First visit to this site in this theme: install the tagger, let the page settle, design once.
                eval(MydesRuntime.script("", ""))
                delay(900)
                var summary = evalString("window.__mydes && window.__mydes.summary()") ?: return
                if (outlineSize(summary) < 4) { // SPA not rendered yet
                    delay(1_500)
                    summary = evalString("window.__mydes && window.__mydes.summary()") ?: return
                }
                val gen = service.generateKit(context, themePrompt, host, summary)
                    .getOrElse { onError?.invoke(it.message ?: "Kit generation failed"); return }
                store.putKit(origin, themeId, gen.css)
                kit = gen.css
                register(origin, gen.css) // next loads of this site get the design before first paint
                onConcept?.invoke(host, gen.concept)
            }
            val delta = store.getDelta(origin, themeId, template)
            eval(MydesRuntime.script(kit, delta ?: ""))
            if (delta == null && checked.add(lock)) repairIfNeeded(origin, host, template, kit)
        } finally {
            busy.remove(lock)
        }
    }

    /** Health-checks the kit on this page type; if it looks broken, asks for a small delta and caches it. */
    private suspend fun repairIfNeeded(origin: String, host: String, template: String, kit: String) {
        delay(800)
        val health = evalString("window.__mydes && window.__mydes.health()") ?: return
        val h = try { JSONObject(health) } catch (_: Exception) { return }
        val bad = h.optBoolean("overflowX") || h.optInt("lowContrast") >= 4 || h.optInt("fixedCover") > 0
        if (!bad) return
        val summary = evalString("window.__mydes.summary()") ?: return
        val shot = withContext(Dispatchers.Main) { webView.captureJpegBase64() }
        val gen = service.generateDelta(context, themePrompt, host, kit, summary, health, shot)
            .getOrElse { onError?.invoke(it.message ?: "Delta generation failed"); return }
        store.putDelta(origin, themeId, template, gen.css)
        val stillHere = withContext(Dispatchers.Main) { webView.url }?.let { SiteKeys.templateKey(it) } == template
        if (stillHere) eval(MydesRuntime.script(kit, gen.css))
    }

    private fun outlineSize(summaryJson: String): Int = try {
        JSONObject(summaryJson).getJSONArray("outline").length()
    } catch (_: Exception) {
        0
    }

    private suspend fun eval(js: String) = withContext(Dispatchers.Main) { webView.evaluateJavascript(js, null) }

    private suspend fun evalString(js: String): String? = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine<String?> { cont ->
            webView.evaluateJavascript(js) { raw ->
                val v = try {
                    if (raw == null || raw == "null") null else JSONTokener(raw).nextValue() as? String
                } catch (_: Exception) {
                    null
                }
                if (cont.isActive) cont.resume(v)
            }
        }
    }
}