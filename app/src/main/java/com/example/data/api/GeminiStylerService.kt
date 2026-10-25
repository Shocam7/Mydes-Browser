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
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.roundToInt

/*
 * v10 - "Unified Generative UI & DOM Choreography Engine"
 *
 * Core Philosophy:
 * 1. Single Unified Mode (No "restyle" vs "app_shell" split, No HUD boilerplate):
 *    - Eliminates artificial mode divisions and removes HUD fields so the model never invents generic floating docks.
 *    - Every redesign has full creative authority to combine deep CSS3 spatial layouts, high-visibility animated
 *      SVG/Canvas backdrops, and live DOM augmentation via `customJs` (injecting bespoke SVG mechanisms, 3D layers,
 *      custom card frames, or interactive viewports directly into the page).
 * 2. True Choreographic Execution:
 *    - Art Director (`gemini-3.5-flash-lite`) focuses 100% on concrete visual anatomy, geometry, bespoke SVG
 *      structures, and spatial motion rather than writing cheap CSS snippets.
 *    - Synthesis Engine (`gemini-3.8-flash`) implements the full choreography: real inline SVG ornaments injected
 *      via `MydesBridge.decorateUnits()`, live 3D scroll/touch perspective bound to `var(--m-rx)` / `var(--m-ry)`,
 *      and structural DOM layout transformations.
 *    - Review Pass (`gemini-3.5-flash-lite`) audits legibility, overflow, and visual fidelity.
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
    val thinkingBudget: Int? = null,
    val thinkingLevel: String? = null
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
 * Strips ::before/::after rules that inject generic fake text labels (e.g. content:"> SYS.RECORD").
 * Icons, symbols, and SVG/data-URIs are preserved.
 */
internal fun stripInventedLabels(css: String): String =
    css.replace(
        Regex("""[^{}]*::?(?:before|after)\s*\{[^{}]*content\s*:\s*(["'])(?!data:)[^"'{}]*[A-Za-z]{4,}[^"'{}]*\1[^{}]*\}""", RegexOption.IGNORE_CASE),
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
    val customJs: String,
    val concept: String = "",
    val fingerprint: String = ""
) {
    fun toJson(): String = JSONObject()
        .put("concept", concept)
        .put("fingerprint", fingerprint)
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
        .put("customJs", customJs)
        .toString()

    companion object {
        const val RUNTIME_VERSION = 10

        private fun jsonSlice(raw: String): String {
            var s = raw.trim()
            Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE).find(s)?.let {
                s = it.groupValues[1].trim()
            }
            // Check if it's wrapped in Mydes runtime script: (function(BP){...})({"name": ...})
            val scriptCall = Regex("""\}\)\s*\(\s*(\{[\s\S]*\})\s*\)\s*;?\s*$""").find(s)
            if (scriptCall != null) {
                s = scriptCall.groupValues[1].trim()
            }
            val a = s.indexOf('{')
            val b = s.lastIndexOf('}')
            return if (a >= 0 && b > a) s.substring(a, b + 1) else s
        }

        fun parse(raw: String, prompt: String = "Imported Redesign"): UIBlueprint? {
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

            // Support key aliases from frontier models or review passes
            val rawCss = when {
                j.has("customCss") -> j.optString("customCss", "")
                j.has("custom_css") -> j.optString("custom_css", "")
                j.has("css") -> j.optString("css", "")
                j.has("fixCss") -> j.optString("fixCss", "")
                else -> ""
            }

            val rawJs = when {
                j.has("customJs") -> j.optString("customJs", "")
                j.has("custom_js") -> j.optString("custom_js", "")
                j.has("js") -> j.optString("js", "")
                j.has("fixJs") -> j.optString("fixJs", "")
                else -> ""
            }

            val rawBackdrop = when {
                j.has("backdropHtml") -> j.optString("backdropHtml", "")
                j.has("backdrop_html") -> j.optString("backdrop_html", "")
                j.has("backdrop") -> j.optString("backdrop", "")
                j.has("html") -> j.optString("html", "")
                else -> ""
            }

            val name = when {
                j.has("name") && j.optString("name").isNotBlank() -> j.optString("name")
                j.has("title") && j.optString("title").isNotBlank() -> j.optString("title")
                j.has("themeName") && j.optString("themeName").isNotBlank() -> j.optString("themeName")
                else -> prompt
            }.take(50)

            val concept = (j.optString("concept", "").ifBlank { j.optString("idea", "") }).take(300)

            return UIBlueprint(
                id = sha1Hex("v$RUNTIME_VERSION|${prompt.trim().lowercase(Locale.US)}|$rawCss|$rawBackdrop|$rawJs").take(12),
                name = name,
                dark = dark,
                bg = bg,
                surface = surface,
                surface2 = surface2,
                fg = fg,
                ink = ink,
                accent = accent,
                remapSurfaces = j.optBoolean("remapSurfaces", false),
                customCss = stripInventedLabels(rawCss),
                backdropHtml = rawBackdrop,
                customJs = rawJs,
                concept = concept,
                fingerprint = j.optString("fingerprint", "").take(120)
            )
        }
    }
}

typealias ThemeSpec = UIBlueprint

object SitePacks {
    val skipHosts = mutableSetOf("accounts.google.com")
}

// ───────────────────── Bulletproof Raw-Socket & Cached DNS Resolver ─────────────────────

object ResilientGoogleDns : Dns {
    private data class CacheEntry(val addresses: List<InetAddress>, val timestamp: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private const val TTL_MS = 15 * 60 * 1000L

    private val PUBLIC_DNS_SERVERS = listOf(
        byteArrayOf(8, 8, 8, 8),         // Google Primary
        byteArrayOf(1, 1, 1, 1),         // Cloudflare Primary
        byteArrayOf(8, 8, 4, 4),         // Google Secondary
        byteArrayOf(9, 9, 9, 9)          // Quad9
    )

    private val GOOGLE_API_FALLBACK_IPS = listOf(
        byteArrayOf(142.toByte(), 250.toByte(), 192.toByte(), 74.toByte()),
        byteArrayOf(142.toByte(), 250.toByte(), 183.toByte(), 106.toByte()),
        byteArrayOf(172.toByte(), 217.toByte(), 160.toByte(), 106.toByte()),
        byteArrayOf(142.toByte(), 250.toByte(), 77.toByte(), 106.toByte())
    )

    override fun lookup(hostname: String): List<InetAddress> {
        val cleanHost = hostname.trim().lowercase(Locale.US)
        val now = System.currentTimeMillis()

        cache[cleanHost]?.let { entry ->
            if (now - entry.timestamp < TTL_MS && entry.addresses.isNotEmpty()) {
                return entry.addresses
            }
        }

        try {
            val sys = Dns.SYSTEM.lookup(cleanHost)
            if (sys.isNotEmpty()) {
                cache[cleanHost] = CacheEntry(sys, now)
                return sys
            }
        } catch (_: UnknownHostException) {}

        for (dnsIp in PUBLIC_DNS_SERVERS) {
            val resolved = queryWireDns(cleanHost, dnsIp)
            if (resolved.isNotEmpty()) {
                cache[cleanHost] = CacheEntry(resolved, now)
                return resolved
            }
        }

        cache[cleanHost]?.let { if (it.addresses.isNotEmpty()) return it.addresses }

        if (cleanHost.endsWith("googleapis.com")) {
            val fallbackList = GOOGLE_API_FALLBACK_IPS.map { ipBytes ->
                InetAddress.getByAddress(cleanHost, ipBytes)
            }
            cache[cleanHost] = CacheEntry(fallbackList, now)
            return fallbackList
        }

        throw UnknownHostException("Unable to resolve host \"$hostname\" across System and Public DNS")
    }

    private fun buildDnsQueryPacket(hostname: String): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeShort(0x1337)
        dos.writeShort(0x0100)
        dos.writeShort(1)
        dos.writeShort(0)
        dos.writeShort(0)
        dos.writeShort(0)
        for (label in hostname.split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            dos.writeByte(bytes.size)
            dos.write(bytes)
        }
        dos.writeByte(0)
        dos.writeShort(1)
        dos.writeShort(1)
        return baos.toByteArray()
    }

    private fun parseDnsResponse(hostname: String, resp: ByteArray, length: Int): List<InetAddress> {
        if (length < 12) return emptyList()
        val anCount = ((resp[6].toInt() and 0xFF) shl 8) or (resp[7].toInt() and 0xFF)
        if (anCount <= 0) return emptyList()

        var pos = 12
        while (pos < length && resp[pos].toInt() != 0) {
            val len = resp[pos].toInt() and 0xFF
            if ((len and 0xC0) == 0xC0) { pos += 2; break }
            pos += len + 1
        }
        if (pos < length && resp[pos].toInt() == 0) pos++
        pos += 4

        val results = mutableListOf<InetAddress>()
        for (i in 0 until anCount) {
            if (pos >= length) break
            while (pos < length && resp[pos].toInt() != 0) {
                val len = resp[pos].toInt() and 0xFF
                if ((len and 0xC0) == 0xC0) { pos += 2; break }
                pos += len + 1
            }
            if (pos < length && resp[pos].toInt() == 0) pos++
            if (pos + 10 > length) break

            val rtype = ((resp[pos].toInt() and 0xFF) shl 8) or (resp[pos + 1].toInt() and 0xFF)
            val rdLength = ((resp[pos + 8].toInt() and 0xFF) shl 8) or (resp[pos + 9].toInt() and 0xFF)
            pos += 10

            if (rtype == 1 && rdLength == 4 && pos + 4 <= length) {
                val ipBytes = resp.copyOfRange(pos, pos + 4)
                results.add(InetAddress.getByAddress(hostname, ipBytes))
            }
            pos += rdLength
        }
        return results
    }

    private fun queryWireDns(hostname: String, dnsServerIp: ByteArray): List<InetAddress> {
        val query = buildDnsQueryPacket(hostname)
        val serverAddr = InetAddress.getByAddress(dnsServerIp)

        try {
            DatagramSocket().use { socket ->
                socket.soTimeout = 2500
                val outPacket = DatagramPacket(query, query.size, serverAddr, 53)
                socket.send(outPacket)
                val buf = ByteArray(512)
                val inPacket = DatagramPacket(buf, buf.size)
                socket.receive(inPacket)
                val parsed = parseDnsResponse(hostname, buf, inPacket.length)
                if (parsed.isNotEmpty()) return parsed
            }
        } catch (_: Exception) {}

        try {
            Socket().use { tcp ->
                tcp.connect(InetSocketAddress(serverAddr, 53), 2500)
                tcp.soTimeout = 2500
                val dos = DataOutputStream(tcp.getOutputStream())
                dos.writeShort(query.size)
                dos.write(query)
                dos.flush()
                val dis = DataInputStream(tcp.getInputStream())
                val respLen = dis.readUnsignedShort()
                val buf = ByteArray(respLen)
                dis.readFully(buf)
                val parsed = parseDnsResponse(hostname, buf, respLen)
                if (parsed.isNotEmpty()) return parsed
            }
        } catch (_: Exception) {}

        return emptyList()
    }
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
var cleanupFns=[], unitDecorators=[], mo=null;
var nodeUidCounter=0;

function hex(h){return {r:parseInt(h.substr(1,2),16),g:parseInt(h.substr(3,2),16),b:parseInt(h.substr(5,2),16),a:1};}
function rgbStr(c){return 'rgb('+Math.round(c.r)+','+Math.round(c.g)+','+Math.round(c.b)+')';}
function configure(){BG=hex(cfg.bg);FG=hex(cfg.fg);INK=hex(cfg.ink);}
configure();

/* ---------- 1. MydesBridge: DOM Augmentation, SVG Ornamentation, 3D & State-Safe Proxy API ---------- */
window.MydesBridge = {
  /*
   * Injects custom thematic HTML/SVG structures (e.g., spinning clockwork gears, comic speech tails,
   * cybernetic HUD brackets, wax seals, 3D frames) into matching elements on the live page,
   * and automatically applies them to new elements as the user infinite-scrolls!
   * Everything injected is marked with class `__mydes_deco` and automatically removed on theme reset.
   */
  decorateUnits: function(selector, htmlOrBuilderFn) {
    var sel = selector || '[data-mydes="card"], [data-mydes="item"]';
    var rule = { sel: sel, fn: htmlOrBuilderFn };
    unitDecorators.push(rule);
    window.MydesBridge._applyDecorators();
  },

  /* Injects a thematic SVG/HTML centerpiece or ornament into a specific container (like header or main) */
  injectOrnament: function(targetSelector, htmlString, position) {
    var target = document.querySelector(targetSelector || '[data-mydes="header"], header');
    if(!target) return null;
    var wrap = document.createElement('div');
    wrap.className = '__mydes_deco';
    wrap.style.cssText = 'pointer-events:none;position:relative;z-index:2;';
    wrap.innerHTML = htmlString;
    if(position === 'prepend') target.prepend(wrap);
    else target.appendChild(wrap);
    return wrap;
  },

  _applyDecorators: function() {
    for(var r=0; r<unitDecorators.length; r++) {
      var rule = unitDecorators[r];
      var nodes = document.querySelectorAll(rule.sel);
      for(var i=0; i<nodes.length && i<120; i++) {
        var el = nodes[i];
        if(el.closest('#__mydes_backdrop') || el.getAttribute('data-m-deco') === cfg.id) continue;
        el.setAttribute('data-m-deco', cfg.id);
        var cs = getComputedStyle(el);
        if(cs.position === 'static') el.style.setProperty('position', 'relative', 'important');
        var html = typeof rule.fn === 'function' ? rule.fn(el, i) : String(rule.fn || '');
        if(html) {
          var deco = document.createElement('div');
          deco.className = '__mydes_deco';
          deco.setAttribute('aria-hidden', 'true');
          deco.style.cssText = 'pointer-events:none !important; position:absolute; inset:0; overflow:visible; z-index:2;';
          deco.innerHTML = html;
          el.appendChild(deco);
        }
      }
    }
  },

  /* Extracts structured items from the live DOM for custom interactive layouts */
  extractNodes: function(customSelector) {
    var sel = customSelector || '[data-mydes="card"], [data-mydes="item"], article, section > div, li, tr';
    var nodes = document.querySelectorAll(sel);
    var list = [];
    for(var i=0; i<nodes.length && list.length<60; i++) {
      var n = nodes[i];
      if(n.closest('#__mydes_backdrop,nav,header,footer')) continue;
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
      if(e.closest('#__mydes_backdrop')) continue;
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

  /* Drives live 3D scroll & touch tilt variables (--m-rx, --m-ry, --m-scroll) */
  enable3DTilt: function(intensity) {
    var mult = typeof intensity === 'number' ? intensity : 1.0;
    var raf = 0, lastY = window.scrollY;
    function onScroll() {
      if(raf) return;
      raf = requestAnimationFrame(function() {
        var sy = window.scrollY;
        var dy = Math.max(-14, Math.min(14, (sy - lastY) * 0.35 * mult));
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
        var ry = ((cx / Math.max(1, innerWidth)) - 0.5) * 16 * mult;
        var rx = (0.5 - (cy / Math.max(1, innerHeight))) * 12 * mult;
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
  var baseVars = ':root{' +
    '--m-bg:'+cfg.bg+';--bg:'+cfg.bg+';' +
    '--m-surface:'+cfg.surface+';--surface:'+cfg.surface+';' +
    '--m-surface2:'+cfg.surface2+';--surface2:'+cfg.surface2+';' +
    '--m-fg:'+cfg.fg+';--fg:'+cfg.fg+';' +
    '--m-ink:'+cfg.ink+';--ink:'+cfg.ink+';' +
    '--m-accent:'+cfg.accent+';--accent:'+cfg.accent+';' +
    '--m-rx:0deg;--m-ry:0deg;--m-scroll:0px;}\n';

  var universalSafetyCss =
    '*, *::before, *::after { box-sizing: border-box; }\n' +
    'html {\n' +
    '  background-color: var(--m-bg) !important; color: var(--m-fg) !important;\n' +
    '  width: 100% !important; max-width: 100% !important; overflow-x: hidden !important;\n' +
    '}\n' +
    'html body {\n' +
    '  background: transparent !important; background-color: transparent !important;\n' +
    '  color: var(--m-fg) !important; width: 100% !important; max-width: 100% !important;\n' +
    '  overflow-x: hidden !important; overflow-y: visible !important;\n' +
    '  margin: 0 !important; position: relative !important; z-index: 1 !important;\n' +
    '}\n' +
    '#__mydes_backdrop {\n' +
    '  position: fixed !important; inset: 0 !important; width: 100vw !important; height: 100vh !important;\n' +
    '  pointer-events: none !important; z-index: -1 !important; overflow: hidden !important;\n' +
    '}\n' +
    '/* Multi-item shelves / carousels inside a CSS Grid must span all columns so they never get crushed into a 47px column! */\n' +
    'html body [data-mydes="grid"] > [data-mydes-shelf="true"],\n' +
    'html body [data-mydes="grid"] > :not([data-mydes="card"]):not([data-mydes="item"]),\n' +
    'html body [data-mydes-sentinel="true"] {\n' +
    '  grid-column: 1 / -1 !important; width: 100% !important; max-width: 100% !important;\n' +
    '}\n' +
    '/* Prevent 2-letter vertical noodle text wrapping */\n' +
    'html body [data-mydes="heading"], html body [data-mydes="title"], html body h1, html body h2, html body h3, html body h4 {\n' +
    '  white-space: normal !important; word-break: normal !important; overflow-wrap: break-word !important;\n' +
    '}\n';

  // Put universalSafetyCss AFTER cfg.customCss so the model can never accidentally make `html` transparent!
  var fullCss = baseVars + '\n' + (cfg.customCss || '') + '\n' + universalSafetyCss;
  var root = document.head || document.documentElement;
  if(!root) return;
  if(!eStyle || !eStyle.isConnected) {
    eStyle = document.createElement('style');
    eStyle.id = '__mydes_style';
    root.appendChild(eStyle);
  }
  if(eStyle.textContent !== fullCss) eStyle.textContent = fullCss;
}

/* ---------- 3. Custom Stage Layers (Backdrop + Sandbox JS + Live Decorations) ---------- */
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

  if(cfg.customJs && document.body.getAttribute('data-m-js') !== cfg.id) {
    document.body.setAttribute('data-m-js', cfg.id);
    try {
      var fn = new Function('MydesBridge', 'T', cfg.customJs);
      fn(window.MydesBridge, cfg);
    } catch(e) { console.warn('[Mydes CustomJS Error]', e); }
  }
  window.MydesBridge._applyDecorators();
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
  if(e.closest('nav,footer,header,#__mydes_backdrop,[data-mydes="nav"],[data-mydes="footer"],[data-mydes="header"],.__mydes_deco')) return false;
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

  var sub = unit.querySelectorAll('*:not(.__mydes_deco):not(.__mydes_deco *)');
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
  var ps = document.body.querySelectorAll('*:not(#__mydes_backdrop *):not(.__mydes_deco):not(.__mydes_deco *)'), n = 0;
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
    if(p.closest('nav,footer,header,#__mydes_backdrop,[data-mydes="nav"],[data-mydes="footer"],[data-mydes="header"]')) continue;
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
    if(e.closest('#__mydes_backdrop,.__mydes_deco')) continue;
    if(e.children.length > 2 || e.hasAttribute('data-mydes')) continue;
    var t = (e.textContent||'').trim();
    if(t.length > 24 || t.length < 2) continue;
    if(PRICE.test(t)){ e.setAttribute('data-mydes','price'); n++; }
  }
}

function clampOutOfBoundsElements() {
  var vw = document.documentElement.clientWidth || window.innerWidth;
  if(vw <= 0 || !document.body) return;

  // 1. Any grid child that contains multiple side-by-side items (or whose inner items are crushed < 110px wide)
  // must span the full width of the parent grid (grid-column: 1 / -1)
  var gridChildren = document.querySelectorAll('[data-mydes="grid"] > *, lazy-list > *');
  for(var c=0; c<gridChildren.length && c<120; c++) {
    var child = gridChildren[c];
    var subCards = child.querySelectorAll('[data-mydes="card"], [data-mydes="item"], a[href] img');
    if(subCards.length >= 2) {
      setProp(child, 'grid-column', '1 / -1');
      setProp(child, 'width', '100%');
      setProp(child, 'max-width', '100%');
    }
  }

  // 2. If any text heading is crushed narrower than 90px (causing 2-letter vertical wrapping),
  // force its ancestor grid container back to 1 column or span the item full-width
  var headings = document.querySelectorAll('h1, h2, h3, [data-mydes="heading"], [data-mydes="title"]');
  for(var h=0; h<headings.length && h<80; h++) {
    var hr = headings[h].getBoundingClientRect();
    if(hr.width > 0 && hr.width < 95 && hr.height > hr.width * 1.8) {
      var cardAnc = headings[h].closest('[data-mydes="card"], [data-mydes="item"]') || headings[h].parentElement;
      if(cardAnc) {
        setProp(cardAnc, 'grid-column', '1 / -1');
        setProp(cardAnc, 'width', '100%');
      }
    }
  }

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

  each('h1', function(e){ if(!e.closest('.__mydes_deco')){ putNew(e,'title'); e.setAttribute('data-mydes-clamped','true'); } });
  each('h2,h3,h4', function(e){ if(!e.closest('.__mydes_deco')){ putNew(e,'heading'); e.setAttribute('data-mydes-clamped','true'); } });
  each('button,input[type="submit"],input[type="button"],[role="button"]', function(e){
    if(!e.closest('.__mydes_deco')) putNew(e,'btn');
  });
  each('input:not([type="hidden"]):not([type="submit"]):not([type="button"]):not([type="checkbox"]):not([type="radio"]),textarea,select', function(e){
    if(!e.closest('.__mydes_deco')) putNew(e,'field');
  });
  each('img,video,picture', function(e){ if(!e.closest('#__mydes_backdrop,.__mydes_deco')) putNew(e,'media'); });
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
  changes=[];EB=new WeakMap();done=new WeakSet();unitDecorators=[];
  var decos = document.querySelectorAll('.__mydes_deco');
  for(var d=0; d<decos.length; d++){ try{decos[d].remove();}catch(e){} }
  var marked = document.querySelectorAll('[data-m-deco]');
  for(var m=0; m<marked.length; m++){ try{marked[m].removeAttribute('data-m-deco');}catch(e){} }
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
  if(el.closest && el.closest('#__mydes_backdrop,.__mydes_deco'))return;
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
          if(a[j].nodeType===1 && a[j].id!=='__mydes_backdrop' && !a[j].classList.contains('__mydes_deco')){
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
            var cl = (e.className && typeof e.className === 'string') ? e.className.trim().split(/\s+/).filter(function(c){ return c && c !== '__mydes_deco'; }) : [];
            if(cl.length) s += '.' + cl.slice(0,2).map(function(c){ return window.CSS && CSS.escape ? CSS.escape(c) : c; }).join('.');
            return s;
          }

          var vw = document.documentElement.clientWidth || innerWidth;
          var vh = document.documentElement.clientHeight || innerHeight;

          // 1. Detect full-screen opaque wrappers that would block backdrop visibility
          var opaqueWrappers = [];
          var bodyEls = document.body ? document.body.querySelectorAll('*:not(script):not(style):not(noscript):not(svg):not(path)') : [];
          for (var w = 0; w < bodyEls.length && opaqueWrappers.length < 8; w++) {
            var el = bodyEls[w];
            var r = el.getBoundingClientRect();
            if (r.width >= vw * 0.85 && r.height >= vh * 0.75) {
              var bg = getComputedStyle(el).backgroundColor;
              var m = /rgba?\(([^)]+)\)/.exec(bg || '');
              if (m) {
                var p = m[1].split(/[ ,\/]+/).filter(Boolean).map(parseFloat);
                if (p.length >= 3 && (p.length === 3 || p[3] >= 0.7)) {
                  var selStr = getSel(el);
                  if (selStr && opaqueWrappers.indexOf(selStr) === -1) opaqueWrappers.push(selStr);
                }
              }
            }
          }

          // 2. Discover repeating structures and inspect internal child box-models
          var groups = {};
          for (var i = 0; i < bodyEls.length && i < 3500; i++) {
            var e = bodyEls[i];
            if (e.closest('#__mydes_backdrop,nav,header,footer,.__mydes_deco')) continue;
            if (e.children.length < 1 || e.children.length > 25) continue;

            var r = e.getBoundingClientRect();
            if (r.width < 60 || r.height < 24) continue;

            var t = (e.textContent || '').replace(/\s+/g,' ').trim();
            if (t.length < 10 || t.length > 1000) continue;

            var hasMedia = !!e.querySelector('img,video,picture,canvas');
            if (!hasMedia && e.parentElement) {
              var pr = e.parentElement.getBoundingClientRect();
              if (r.width < pr.width * 0.72 && e.parentElement.querySelector('img,video,picture')) continue;
            }

            var p = e.parentElement;
            if (!p || p === document.body) continue;
            var k = getSel(e);
            if (!k) continue;

            var parentSel = getSel(p);
            var cs = getComputedStyle(e);
            var pcs = getComputedStyle(p);
            var key = parentSel + ' > ' + k;

            if (!groups[key]) {
              var childrenInfo = [];
              for (var c = 0; c < e.children.length && c < 5; c++) {
                var ch = e.children[c];
                var cr = ch.getBoundingClientRect();
                var ccs = getComputedStyle(ch);
                childrenInfo.push({
                  sel: getSel(ch),
                  w: Math.round(cr.width),
                  h: Math.round(cr.height),
                  display: ccs.display,
                  isMedia: !!ch.matches('img,video,picture') || !!ch.querySelector('img,video,picture')
                });
              }

              groups[key] = {
                sel: k,
                parentSel: parentSel,
                n: 0,
                hasMedia: hasMedia,
                itemDisplay: cs.display + (cs.flexDirection && cs.display.indexOf('flex')>=0 ? ' ('+cs.flexDirection+')' : ''),
                parentDisplay: pcs.display,
                widthPct: Math.round((r.width / Math.max(1, vw)) * 100),
                sample: t.slice(0, 45),
                childBoxModels: childrenInfo
              };
            }
            groups[key].n++;
          }

          var rep = Object.keys(groups).map(function(k){ return groups[k]; })
            .filter(function(g){ return g.n >= 3; })
            .sort(function(x,y){ return (y.hasMedia - x.hasMedia) || (y.n - x.n); })
            .slice(0, 5);

          var heads = [].slice.call(document.querySelectorAll('h1,h2,h3,[role="heading"]')).slice(0, 6)
            .map(function(h){ return (h.textContent || '').replace(/\s+/g,' ').trim().slice(0, 45); });

          var o = {
            url: location.href,
            host: location.hostname,
            title: (document.title||'').slice(0, 80),
            viewport: { w: vw, h: vh },
            opaqueWrappers: opaqueWrappers,
            repeatedStructures: rep,
            headings: heads
          };
          return o;
        })();
    """

    const val VISUAL_AND_DOM_AUDIT_JS = """
        (function(){
          try{
            var vw = document.documentElement.clientWidth || innerWidth;
            var vh = document.documentElement.clientHeight || innerHeight;
            function P(c){var m=/rgba?\(([^)]+)\)/.exec(c||'');if(!m)return null;var p=m[1].split(/[ ,\/]+/).filter(Boolean).map(parseFloat);return {r:p[0],g:p[1],b:p[2],a:p.length>3?p[3]:1};}
            function L(c){function f(v){v/=255;return v<=0.03928?v/12.92:Math.pow((v+0.055)/1.055,2.4);}return 0.2126*f(c.r)+0.7152*f(c.g)+0.0722*f(c.b);}
            function R(a,b){var x=L(a),y=L(b);if(x<y){var t=x;x=y;y=t;}return (x+0.05)/(y+0.05);}
            function mix(t,b){var a=t.a;return {r:t.r*a+b.r*(1-a),g:t.g*a+b.g*(1-a),b:t.b*a+b.b*(1-a),a:1};}
            function hx(c){function h(v){v=Math.max(0,Math.min(255,Math.round(v)));return (v<16?'0':'')+v.toString(16);}return '#'+h(c.r)+h(c.g)+h(c.b);}
            function sel(e){
              if(!e || e===document.body || e===document.documentElement) return '';
              var s = e.tagName.toLowerCase();
              var d = e.getAttribute('data-mydes');
              if(d) s += '[data-mydes="'+d+'"]';
              var cl = (e.className && typeof e.className === 'string') ? e.className.trim().split(/\s+/).filter(Boolean) : [];
              if(cl.length) s += '.' + cl.slice(0,2).map(function(c){ return window.CSS&&CSS.escape?CSS.escape(c):c; }).join('.');
              return s;
            }

            // 1. Audit whether backdrop is occluded by opaque elements in front of it
            var occludingWrappers = [];
            var stack = document.elementsFromPoint(vw / 2, vh / 2);
            for(var s=0; s<stack.length; s++){
              var elStack = stack[s];
              if(elStack.id === '__mydes_backdrop' || elStack.tagName === 'HTML' || elStack.tagName === 'BODY') continue;
              var bg = P(getComputedStyle(elStack).backgroundColor);
              if(bg && bg.a >= 0.75) {
                var rStack = elStack.getBoundingClientRect();
                if(rStack.width >= vw * 0.8 && rStack.height >= vh * 0.6) {
                  occludingWrappers.push(sel(elStack));
                }
              }
            }

            // 2. Audit injected ornaments: check if crushed, zero-size, or missing
            var brokenOrnaments = [];
            var decos = document.querySelectorAll('.__mydes_deco, [data-m-deco]');
            for(var d=0; d<decos.length && d<10; d++){
              var decoEl = decos[d];
              var dr = decoEl.getBoundingClientRect();
              var dcs = getComputedStyle(decoEl);
              if(dr.width < 4 || dr.height < 4 || dcs.display === 'none' || dcs.visibility === 'hidden') {
                brokenOrnaments.push({ parent: sel(decoEl.parentElement), issue: 'zero_size_or_hidden', w: Math.round(dr.width), h: Math.round(dr.height) });
              }
            }

            // 3. Audit unfilled media containers inside cards (e.g. 160px image inside 350px column card)
            var unfilledMedia = [];
            var cards = document.querySelectorAll('[data-mydes="card"], [data-mydes="item"]');
            for(var c=0; c<cards.length && c<8; c++){
              var card = cards[c];
              var cardW = card.getBoundingClientRect().width;
              var media = card.querySelector('img, video, ytm-thumbnail-cover, .media-item-thumbnail-container');
              if(media && cardW > 140) {
                var mediaW = media.getBoundingClientRect().width;
                if(mediaW < cardW * 0.75) {
                  unfilledMedia.push({ card: sel(card), media: sel(media), cardWidth: Math.round(cardW), mediaWidth: Math.round(mediaW) });
                }
              }
            }

            // 4. Contrast and Right-Edge Overflow Checks
            var found={}, clipped=[], checked=0, unknown=0, guard=0, node;
            var seen=new WeakSet();
            var tw=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null);
            var limitY=vh*2.5;
            while((node=tw.nextNode())&&guard++<3000&&checked<500){
              if(!/\S/.test(node.nodeValue))continue;
              var el=node.parentElement;
              if(!el||seen.has(el))continue;
              seen.add(el);
              if(el.closest('#__mydes_backdrop,.__mydes_deco,script,style,noscript'))continue;
              var r=el.getBoundingClientRect();
              if(r.width<4||r.height<4||r.bottom<0||r.top>limitY)continue;
              if((r.right > vw + 2 || r.left < -2) && clipped.length < 6) {
                clipped.push({sel: sel(el), overflowPx: Math.round(Math.max(r.right - vw, -r.left))});
              }
              var cs2=getComputedStyle(el);
              if(cs2.visibility==='hidden'||cs2.display==='none'||parseFloat(cs2.opacity)===0)continue;
              var fg=P(cs2.color);
              if(!fg||fg.a===0)continue;
              var bg2=P(cs2.backgroundColor);
              if(!bg2 || bg2.a < 0.5) continue;
              checked++;
              var ratio=R(fg,bg2);
              if(ratio<3){
                var key=sel(el)+'|'+hx(fg)+'|'+hx(bg2);
                var f=found[key]||(found[key]={sel:sel(el),text:(node.nodeValue||'').trim().slice(0,28),fg:hx(fg),bg:hx(bg2),ratio:Math.round(ratio*100)/100,count:0});
                f.count++;
              }
            }

            return {
              viewport: { w: vw, h: vh },
              backdropOccludedBy: occludingWrappers,
              brokenOrnaments: brokenOrnaments,
              unfilledMediaContainers: unfilledMedia,
              clippedElements: clipped,
              overflowX: document.documentElement.scrollWidth - vw,
              contrastIssues: Object.keys(found).map(function(k){return found[k];}).slice(0, 8)
            };
          }catch(e){return {error:String(e)};}
        })();
    """

    const val CONTRAST_AUDIT_JS = VISUAL_AND_DOM_AUDIT_JS
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
    private val prefs = context.applicationContext.getSharedPreferences("mydes_blueprints_v10", Context.MODE_PRIVATE)
    private fun key(prompt: String, domain: String) =
        "bp10|" + sha1Hex("${domain.trim().lowercase(Locale.US)}|${prompt.trim().lowercase(Locale.US)}")
    private fun lastPromptKey(prompt: String) =
        "bp10_last|" + sha1Hex(prompt.trim().lowercase(Locale.US))

    fun get(prompt: String, domain: String = "*"): UIBlueprint? {
        val exact = prefs.getString(key(prompt, domain), null)?.let { UIBlueprint.parse(it, prompt) }
        if (exact != null) return exact
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

    private val okHttpClient = OkHttpClient.Builder()
        .dns(ResilientGoogleDns)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .pingInterval(10, TimeUnit.SECONDS)
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
        outlineJson: String? = null,
        initialScreenshotBase64: String? = null,
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null
    ): Result<String> = synthesizeBlueprint(
        context = context,
        prompt = userPrompt,
        websiteName = websiteName,
        websiteUrl = websiteUrl,
        pageOutlineJson = outlineJson?.takeIf { it.isNotBlank() && it != "null" },
        screenshotBase64 = initialScreenshotBase64,
        forceFresh = forceFresh,
        isReviewPass = false,
        onThinkingUpdate = onThinkingUpdate
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
        auditJson: String? = null,
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null
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
        isReviewPass = true,
        onThinkingUpdate = onThinkingUpdate
    ).map { MydesRuntime.script(it) }

    suspend fun adaptUserScriptForWebpage(
        context: Context,
        basePrompt: String,
        baseScript: String,
        pageTitle: String,
        pageUrl: String,
        domain: String,
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null
    ): Result<String> = synthesizeBlueprint(
        context = context,
        prompt = basePrompt,
        websiteName = pageTitle.ifBlank { domain },
        websiteUrl = pageUrl,
        pageOutlineJson = null,
        screenshotBase64 = null,
        isReviewPass = false,
        onThinkingUpdate = onThinkingUpdate
    ).map { MydesRuntime.script(it) }

    suspend fun themeSpec(
        context: Context,
        prompt: String,
        websiteUrl: String = "*",
        pageOutlineJson: String? = null,
        screenshotBase64: String? = null,
        forceFresh: Boolean = false,
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null
    ): Result<UIBlueprint> = synthesizeBlueprint(
        context = context,
        prompt = prompt,
        websiteName = websiteUrl,
        websiteUrl = websiteUrl,
        pageOutlineJson = pageOutlineJson,
        screenshotBase64 = screenshotBase64,
        forceFresh = forceFresh,
        isReviewPass = screenshotBase64 != null && !forceFresh,
        onThinkingUpdate = onThinkingUpdate
    )

    // ───── Pass 1: Concept Pass (Art Director using gemini-3.5-flash-lite) ─────

    private val LOG_SYSTEM_PROMPT = false

    private class ConceptBrief(
        val raw: String,
        val idea: String,
        val fingerprint: String
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
        maxTokens: Int,
        onThoughtChunk: ((String) -> Unit)? = null
    ): String {
        val req = GeminiRequest(
            contents = listOf(Content(parts = parts)),
            systemInstruction = Content(listOf(Part(text = system))),
            generationConfig = GenerationConfig(
                temperature = temperature,
                topP = topP,
                maxOutputTokens = maxTokens,
                responseMimeType = "application/json"
            )
        )
        return callGeminiStream(model, req, apiKey, onThoughtChunk).first
    }

    private suspend fun designBrief(
        context: Context,
        prompt: String,
        websiteName: String,
        websiteUrl: String,
        pageOutlineJson: String?,
        apiKey: String,
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null
    ): Result<ConceptBrief> {
        onThinkingUpdate?.invoke("Pass 1: Concept Direction ($CONCEPT_MODEL)", "Brainstorming creative aesthetic and visual anatomy...", false)
        val input = buildString {
            appendLine("USER REQUEST: \"$prompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            if (!pageOutlineJson.isNullOrBlank()) appendLine("LIVE DOM TOPOLOGY OUTLINE: $pageOutlineJson")
            val recent = storeFor(context).recentConcepts()
            if (recent.isNotEmpty()) {
                appendLine("RECENT DESIGNS ALREADY SHIPPED (do NOT repeat their aesthetic):")
                recent.forEach { appendLine("- $it") }
            }
            appendLine("Write the Art Director blueprint JSON now.")
        }
        val logInput = buildString {
            appendLine("=== TARGET MODEL ===")
            appendLine(CONCEPT_MODEL)
            appendLine()
            appendLine("=== PASS TYPE ===")
            appendLine("Pass 1: Concept Brief (Art Director)")
            appendLine()
            appendLine("=== SYSTEM INSTRUCTION ===")
            appendLine(UI_CONCEPT_SYSTEM_PROMPT)
            appendLine()
            appendLine("=== GENERATION CONFIG ===")
            appendLine("""{
  "temperature": 1.05,
  "topP": 0.95,
  "maxOutputTokens": 1024,
  "responseMimeType": "application/json"
}""")
            appendLine()
            appendLine("=== USER CONTENT & PROMPT ===")
            append(input)
        }

        return try {
            val raw = callForText(
                CONCEPT_MODEL,
                apiKey,
                UI_CONCEPT_SYSTEM_PROMPT,
                listOf(Part(text = input)),
                1.05f,
                0.95f,
                1024,
                onThoughtChunk = { chunk ->
                    onThinkingUpdate?.invoke("Pass 1: Concept Brainstorming", chunk, false)
                }
            )
            val json = JSONObject(extractJsonObject(raw) ?: throw IllegalStateException("Concept pass returned invalid JSON: ${raw.take(200)}"))
            val idea = json.optString("idea", "").trim()
            if (idea.isBlank()) throw IllegalStateException("Concept brief missing 'idea': ${raw.take(200)}")

            val directions = listOf(
                "idea" to "CONCEPT",
                "signature" to "SIGNATURE VISUAL & KINETIC MECHANISM",
                "palette" to "PALETTE",
                "type" to "TYPOGRAPHY",
                "surface" to "SURFACE & MATERIALITY",
                "style" to "VISUAL ATMOSPHERE",
                "choreography" to "DOM & INTERACTIVE ENHANCEMENTS"
            ).mapNotNull { (k, label) -> json.optString(k, "").trim().takeIf { it.isNotBlank() }?.let { "$label: $it" } }
            .joinToString("\n")

            val brief = ConceptBrief(
                raw = directions,
                idea = idea.take(300),
                fingerprint = json.optString("fingerprint", "").take(120)
            )
            ApiRequestLogger.logRequest(context, "conceptBrief ($CONCEPT_MODEL)", websiteName, websiteUrl, prompt, logInput, json.toString(2), true)
            Result.success(brief)
        } catch (e: Exception) {
            val errMsg = describe(e)
            ApiRequestLogger.logRequest(context, "conceptBrief ($CONCEPT_MODEL)", websiteName, websiteUrl, prompt, logInput, errMsg, false)
            Result.failure(Exception("Concept pass failed ($CONCEPT_MODEL): $errMsg", e))
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
        isReviewPass: Boolean = false,
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null
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

        if (isReviewPass && previous == null) {
            return@withContext Result.failure(IllegalStateException("Review pass skipped: no base blueprint cached for $domain"))
        }

        val isReview = isReviewPass || (previous != null && !forceFresh && (!screenshotBase64.isNullOrBlank() || !auditJson.isNullOrBlank()))
        val activeModel = if (isReview) REVIEW_MODEL else SYNTHESIS_MODEL

        // FAIL-FAST: If Concept Pass fails, immediately abort the pipeline and return the Concept Pass error
        val brief: ConceptBrief? = if (isReview) {
            null
        } else {
            designBrief(context, cleanPrompt, websiteName, websiteUrl, pageOutlineJson, apiKey, onThinkingUpdate)
                .getOrElse { conceptError -> return@withContext Result.failure(conceptError) }
        }

        if (!isReview) {
            onThinkingUpdate?.invoke("Pass 2: Generative Synthesis ($activeModel)", "Analyzing layout and preparing CSS/backdrop...", false)
        } else {
            onThinkingUpdate?.invoke("Pass 3: Visual Review & QA ($activeModel)", "Auditing render and preparing CSS patches...", false)
        }

        val userContextText = if (isReview) buildString {
            appendLine("REVIEW PASS")
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            appendLine("INTENDED CONCEPT: ${previous!!.concept}")
            appendLine("PALETTE: bg ${previous.bg}, surface ${previous.surface}, surface2 ${previous.surface2}, text ${previous.fg}, ink ${previous.ink}, accent ${previous.accent}")
            if (!pageOutlineJson.isNullOrBlank()) appendLine("LIVE DOM TOPOLOGY OUTLINE: $pageOutlineJson")
            appendLine("MEASURED AUDIT (check overflowX and clippedElements!): ${auditJson?.takeIf { it.isNotBlank() && it != "null" } ?: "not available"}")
            appendLine("BACKDROP HTML:")
            appendLine(previous.backdropHtml)
            appendLine("CURRENT CSS:")
            appendLine(previous.customCss)
            appendLine("CURRENT CUSTOM JS:")
            appendLine(previous.customJs)
            appendLine(if (!screenshotBase64.isNullOrBlank()) "A screenshot of the current render is attached." else "No screenshot is available; rely on the audit.")
            appendLine("Return the review JSON now.")
        } else buildString {
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            if (!pageOutlineJson.isNullOrBlank()) {
                appendLine("LIVE DOM TOPOLOGY OUTLINE (repeatedStructures lists parent container selectors `parentSel` and composite item selectors `sel`):")
                appendLine(pageOutlineJson)
            }
            if (!screenshotBase64.isNullOrBlank()) {
                appendLine("LIVE SCREENSHOT ATTACHED: A real visual capture of the un-styled webpage layout is attached. Inspect it alongside the DOM outline to see exact element positions, row alignments, fixed headers, side-by-side rows, and opaque wrappers.")
            }
            appendLine()
            appendLine("CREATIVE CONCEPT FROM THE ART DIRECTOR (you must build every single mechanism here—do NOT take shortcuts):")
            appendLine(brief!!.raw)
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
                temperature = if (isReview) 0.2f else 0.8f,
                topP = 0.95f,
                maxOutputTokens = if (isReview) 2048 else 8192,
                responseMimeType = if (jsonMode) "application/json" else null,
                thinkingConfig = if (!isReview) ThinkingConfig(thinkingBudget = 2048) else null
            )
        )

        val logType = if (isReview) "reviewBlueprint ($activeModel)" else "buildBlueprint ($activeModel)"
        val requestJson = buildString {
            appendLine("=== TARGET MODEL ===")
            appendLine(activeModel)
            appendLine()
            appendLine("=== PASS TYPE ===")
            appendLine(if (isReview) "Pass 3: Visual QA & Review Pass" else "Pass 2: Main Generative Synthesis Pass")
            appendLine()
            appendLine("=== SYSTEM INSTRUCTION ===")
            appendLine(if (isReview) UI_REVIEW_SYSTEM_PROMPT else UI_SYNTHESIS_SYSTEM_PROMPT)
            appendLine()
            appendLine("=== GENERATION CONFIG ===")
            appendLine("""{
  "temperature": ${if (isReview) 0.2f else 0.8f},
  "topP": 0.95,
  "maxOutputTokens": ${if (isReview) 2048 else 8192},
  "responseMimeType": "application/json"${if (!isReview) ",\n  \"thinkingConfig\": { \"thinkingBudget\": 2048 }" else ""}
}""")
            appendLine()
            appendLine("=== USER CONTENT & PROMPT ===")
            append(userContextText)
            if (!screenshotBase64.isNullOrBlank()) {
                appendLine()
                appendLine("=== SCREENSHOT ATTACHMENT ===")
                appendLine("[Attached JPEG screenshot: ${screenshotBase64.length / 1024} KB base64]")
                appendLine("Preview header: data:image/jpeg;base64,${screenshotBase64.take(160)}...")
            }
        }

        try {
            val (raw, rawThoughts) = callGeminiStream(
                activeModel,
                buildReq(true),
                apiKey,
                onThoughtChunk = { chunk ->
                    onThinkingUpdate?.invoke(
                        if (isReview) "Pass 3: Visual QA Audit" else "Pass 2: Reasoning & Spatial Choreography",
                        chunk,
                        false
                    )
                },
                onOutputChunk = { _ ->
                    onThinkingUpdate?.invoke(
                        if (isReview) "Pass 3: Generating Fixes" else "Pass 2: Generating UI Code",
                        "",
                        false
                    )
                }
            )
            val blueprint: UIBlueprint? = if (isReview) {
                applyReviewPatch(previous!!, raw, cleanPrompt)
            } else {
                val parsed = UIBlueprint.parse(raw, cleanPrompt)
                parsed?.copy(concept = brief!!.idea, fingerprint = brief.fingerprint)
            }

            if (blueprint == null) {
                val err = "Model returned invalid UIBlueprint JSON. Raw start: ${raw.take(300)}"
                ApiRequestLogger.logRequest(context, logType, websiteName, websiteUrl, cleanPrompt, requestJson, err, false)
                Result.failure(IllegalStateException(err))
            } else {
                storeFor(context).put(cleanPrompt, blueprint, domain, remember = !isReview)
                val logResponse = if (rawThoughts.isNotBlank()) {
                    "=== REASONING & THINKING PROCESS ===\n$rawThoughts\n\n=== RESPONSE RESULT ===\n" + (if (isReview) raw else blueprint.toJson())
                } else {
                    if (isReview) raw else blueprint.toJson()
                }
                ApiRequestLogger.logRequest(context, logType, websiteName, websiteUrl, cleanPrompt, requestJson, logResponse, true)
                onThinkingUpdate?.invoke("Theme Synthesis Complete", "", true)
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
        val fixCss = stripInventedLabels(json.optString("fixCss", "")).trim()
        val fixJs = json.optString("fixJs", "").trim()

        if (fixCss.isBlank() && fixJs.isBlank()) return previous

        val mergedCss = if (fixCss.isNotBlank()) previous.customCss + "\n/* review patch */\n" + fixCss else previous.customCss
        val mergedJs = if (fixJs.isNotBlank()) previous.customJs + "\n/* review patch */\ntry {\n" + fixJs + "\n} catch(e){ console.warn('[Review fixJs error]', e); }" else previous.customJs

        val mergedJson = JSONObject(previous.toJson())
            .put("customCss", mergedCss)
            .put("customJs", mergedJs)

        return UIBlueprint.parse(mergedJson.toString(), prompt)
            ?.copy(concept = previous.concept, fingerprint = previous.fingerprint)
            ?: previous
    }

    // ───── System Prompts ─────

    private val UI_REVIEW_SYSTEM_PROMPT = """
You are the Visual QA Engineer for a generative web browser.
You receive:
1. A screenshot of how the redesigned page currently looks.
2. The intended concept, current CSS, backdrop HTML, and custom JS.
3. Ground-truth visual measurements from the live DOM.

Audit the render for technical defects:
- Check that text has sufficient contrast against its background.
- Check that important text or elements are not unintentionally clipped or cut off by container bounds.
- If backdropHtml was intended to be visible, ensure intermediate full-page wrappers allow it to show through.

OUTPUT: ONLY one valid JSON object, no markdown fences:
{
  "problems": ["list of technical issues found"],
  "fixCss": "CSS overrides to append, or empty string",
  "fixJs": "optional JS code to repair, append, or empty string"
}
""".trimIndent()

    private val UI_CONCEPT_SYSTEM_PROMPT = """
You are the Art Director for a generative web browser. You create creative artistic concepts to transform websites based on the user's prompt.
You have complete artistic freedom to imagine any visual aesthetic, mood, or experience that reflects and visually enhances the user's request.

Provide your creative concept across these dimensions:
1. `idea`: A concise summary of the visual concept.
2. `signature`: The key visual, kinetic, or artistic centerpiece of this concept.
3. `palette`: Color roles (bg, surface, surface2, fg, accent).
4. `type`: Typography choices and styling.
5. `surface`: Materiality, textures, borders, or styling of elements.
6. `style`: Overall visual atmosphere.
7. `choreography`: Interactive, motion, or DOM enhancements.

OUTPUT: ONLY one valid JSON object, no markdown fences:
{
  "idea": "summary of the visual concept",
  "signature": "central visual or artistic mechanism",
  "palette": "color palette description with roles",
  "type": "typography direction and styling",
  "surface": "materiality and element styling",
  "style": "overall visual atmosphere",
  "choreography": "interactive, motion, or DOM enhancements",
  "fingerprint": "short keyword summary"
}
""".trimIndent()

    private val UI_SYNTHESIS_SYSTEM_PROMPT = """
You are the Front-End Synthesis Engine for a generative web browser. You translate the Art Director's concept and the webpage's DOM structure into a complete UIBlueprint.
You have complete artistic freedom to style and transform the page to realize the design.

Tools available:
1. `customCss`: CSS injected into the page. Supports web fonts via `@import`, animations via `@keyframes`, and CSS variables `--m-bg`, `--m-surface`, `--m-surface2`, `--m-fg`, `--m-ink`, `--m-accent`, `--m-rx`, `--m-ry`.
2. `backdropHtml`: Optional HTML/SVG/Canvas fixed behind the page content.
3. `customJs`: Optional JavaScript executed in the page context. You can use the `MydesBridge` API:
   - `MydesBridge.decorateUnits(selector, htmlOrBuilderFn)`: Injects HTML/SVG elements into matched elements and retains them during dynamic scrolling.
   - `MydesBridge.injectOrnament(targetSelector, htmlString, position)`: Mounts HTML/SVG into a target container.
   - `MydesBridge.enable3DTilt(intensity)`: Binds scroll and touch motion to CSS variables `--m-rx` and `--m-ry`.
   - `MydesBridge.extractNodes(selector)` / `MydesBridge.triggerNode(uid)`: Extracts content nodes or routes interaction events.

Technical requirements:
- Keep `html { background-color: var(--m-bg); }` and `body { background: transparent; }` so the backdrop layer remains compositable.
- Ensure the page remains scrollable.
- Never cover media thumbnails with text, and never truncate titles.

OUTPUT: ONLY one valid JSON object, no markdown fences:
{
  "name": "short title",
  "dark": true or false,
  "bg": "#rrggbb", "surface": "#rrggbb", "surface2": "#rrggbb", "fg": "#rrggbb", "ink": "#rrggbb", "accent": "#rrggbb",
  "remapSurfaces": false,
  "customCss": "complete CSS styles",
  "backdropHtml": "optional backdrop HTML or SVG",
  "customJs": "optional JavaScript code",
  "concept": "concise summary",
  "fingerprint": "short keyword summary"
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
        apiKey: String,
        onThoughtChunk: ((String) -> Unit)? = null,
        onOutputChunk: ((String) -> Unit)? = null
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        var currentRequest = request
        var lastException: Exception? = null

        for (wait in longArrayOf(0, 1_500, 4_000)) {
            if (wait > 0) delay(wait)
            try {
                val responseBody = api.streamGenerateContent(model, apiKey, currentRequest)
                val fullText = StringBuilder()
                val rawThoughts = StringBuilder()

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
                                        val candidate = candidates.getJSONObject(0)
                                        val content = candidate.optJSONObject("content")
                                        val parts = content?.optJSONArray("parts")
                                        if (parts != null) {
                                            for (i in 0 until parts.length()) {
                                                val partObj = parts.getJSONObject(i)
                                                val isThought = partObj.optBoolean("thought", false) ||
                                                        (partObj.has("thought") && partObj.opt("thought") != JSONObject.NULL && partObj.opt("thought") != false)
                                                val text = partObj.optString("text", "")
                                                val thoughtDirect = partObj.optString("thought", "")

                                                if (isThought || (thoughtDirect.isNotEmpty() && thoughtDirect != "false" && thoughtDirect != "true")) {
                                                    val thoughtPiece = if (isThought) text else thoughtDirect
                                                    if (thoughtPiece.isNotEmpty()) {
                                                        rawThoughts.append(thoughtPiece)
                                                        onThoughtChunk?.invoke(thoughtPiece)
                                                    }
                                                } else {
                                                    if (text.isNotEmpty()) {
                                                        fullText.append(text)
                                                        onOutputChunk?.invoke(text)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                } catch (_: Exception) {}
                            }
                        }
                        line = reader.readLine()
                    }
                }

                var result = fullText.toString()
                if (result.isBlank() && rawThoughts.isNotBlank()) {
                    result = rawThoughts.toString()
                }
                if (result.isNotBlank()) return@withContext Pair(result, rawThoughts.toString())
                throw IllegalStateException("Empty streamed response from $model")

            } catch (e: Exception) {
                lastException = e
                if (e is HttpException && e.code() == 400 && currentRequest.generationConfig != null) {
                    val gc = currentRequest.generationConfig!!
                    if (gc.thinkingConfig != null) {
                        currentRequest = currentRequest.copy(
                            generationConfig = gc.copy(thinkingConfig = null)
                        )
                        continue
                    } else if (gc.responseMimeType != null) {
                        currentRequest = currentRequest.copy(
                            generationConfig = gc.copy(responseMimeType = null)
                        )
                        continue
                    }
                }

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