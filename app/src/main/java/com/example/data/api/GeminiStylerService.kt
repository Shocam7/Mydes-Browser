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
 * v11 - "Generative UI Synthesis Engine: V4 Visual Core + Asymmetric Panels + Live DOM Elements"
 *
 * How it works:
 * 1. Restores the exact high-contrast, high-creativity runtime core of v4 (`remapSurfaces = true`,
 *    clean stylesheet ordering without destructive post-CSS inline overrides).
 * 2. Preserves all production infrastructure: SSE Streaming (`streamGenerateContent`), `onThinkingUpdate`
 *    progress callbacks, fail-fast Concept Pass, and `ResilientGoogleDns` (4-tier raw socket + cache).
 * 3. Solves the 3 V4 Limitations:
 *    - Asymmetric Panel Grids (1-2-1 Span Pattern): Teach the builder to alternate full-width hero panels
 *      (`:nth-child(3n+1) { grid-column: 1 / -1 }`) with angled side-by-side split panels while keeping
 *      multi-item shelves (`[data-mydes-shelf="true"]`) spanned full-width so nothing ever gets crushed.
 *    - New DOM Elements (`newElements` + `MydesBridge.decorateUnits` / `injectOrnament`): Explicitly directs
 *      `customJs` to create and mount bespoke SVG/HTML elements onto cards and headers.
 *    - Thumbnail & Title Integrity: Prevents opaque text boxes from covering vertical thumbnails and
 *      unclamps headings (`-webkit-line-clamp: unset`) so titles are never cut off after two words.
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
 * Removes ::before/::after rules that inject generic fake text labels (e.g. content:"> SYS.RECORD").
 * Icons, symbols, bullets, and SVG/data-URIs are kept.
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
    val hudHtml: String = "",
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
        const val RUNTIME_VERSION = 11

        private fun jsonSlice(raw: String): String {
            var s = raw.trim()
            Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE).find(s)?.let {
                s = it.groupValues[1].trim()
            }
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
            val needsHud = j.optBoolean("needsHud", false) &&
                j.optString("hudReason").isNotBlank() &&
                j.optString("hudHtml").isNotBlank()

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
                // Default to true (just like v4) so unstyled surfaces & text contrast stay harmonious
                remapSurfaces = j.optBoolean("remapSurfaces", true),
                customCss = stripInventedLabels(rawCss),
                backdropHtml = rawBackdrop,
                hudHtml = if (needsHud) j.optString("hudHtml", "") else "",
                customJs = rawJs,
                concept = concept,
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

// ───────────────────── Generative Runtime + MydesBridge ─────────────────────

object MydesRuntime {

    private const val CORE = """
if(window.__mydes && window.__mydes.id()===BP.id) return;
if(window.__mydes && window.__mydes.clear){try{window.__mydes.clear();}catch(e){}}
if(BP.skip && BP.skip.indexOf(location.hostname)>=0) return;

var cfg = BP;
var changes=[], EB=new WeakMap(), done=new WeakSet(), jobs=[], draining=false;
var tagDirty=true, lastTag=0, tagDelay=120, BG, FG, INK;
var cleanupFns=[], unitDecorators=[], ornamentRules=[], mo=null;
var nodeUidCounter=0;

function hex(h){return {r:parseInt(h.substr(1,2),16),g:parseInt(h.substr(3,2),16),b:parseInt(h.substr(5,2),16),a:1};}
function rgbStr(c){return 'rgb('+Math.round(c.r)+','+Math.round(c.g)+','+Math.round(c.b)+')';}
function configure(){BG=hex(cfg.bg);FG=hex(cfg.fg);INK=hex(cfg.ink);}
configure();

/* ---------- 1. MydesBridge: Safe API for LLM-generated UIs & Live DOM Elements ---------- */
window.MydesBridge = {
  /*
   * Attaches bespoke SVG/HTML ornaments (e.g., comic action bursts, rotating gears, targeting reticles,
   * speech bubble tails) onto matching cards/units and automatically applies them to infinite-scrolled items.
   */
  decorateUnits: function(selector, htmlOrBuilderFn) {
    var sel = selector || '[data-mydes="card"]';
    unitDecorators.push({ sel: sel, fn: htmlOrBuilderFn });
    window.MydesBridge._applyDecorators();
  },

  /*
   * Injects a new thematic SVG/HTML centerpiece or banner into a target container (like header or main)
   * and keeps it alive across SPA DOM re-renders.
   */
  injectOrnament: function(targetSelector, htmlString, position) {
    var sel = targetSelector || '[data-mydes="header"], header';
    ornamentRules.push({ sel: sel, html: htmlString, pos: position || 'append' });
    window.MydesBridge._applyDecorators();
  },

  _applyDecorators: function() {
    for(var r=0; r<unitDecorators.length; r++) {
      var rule = unitDecorators[r];
      var nodes = document.querySelectorAll(rule.sel);
      for(var i=0; i<nodes.length && i<120; i++) {
        var el = nodes[i];
        if(el.closest('#__mydes_hud,#__mydes_backdrop') || el.getAttribute('data-m-deco') === cfg.id) continue;
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
    for(var o=0; o<ornamentRules.length; o++) {
      var orn = ornamentRules[o];
      var target = document.querySelector(orn.sel);
      if(target && !target.querySelector('.__mydes_orn_' + o)) {
        var wrap = document.createElement('div');
        wrap.className = '__mydes_deco __mydes_orn_' + o;
        wrap.style.cssText = 'pointer-events:none;position:relative;z-index:2;';
        wrap.innerHTML = orn.html;
        if(orn.pos === 'prepend') target.prepend(wrap);
        else target.appendChild(wrap);
      }
    }
  },

  extractNodes: function(customSelector) {
    var sel = customSelector || '[data-mydes="card"], article, section > div, li, tr';
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
      var metaEl = n.querySelector('[data-mydes="price"],time,small');

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

/* ---------- 2. Constructable / Injected Stylesheets (V4 Clean Ordering) ---------- */
var eStyle = null;
function applySheets() {
  var baseVars = ':root{' +
    '--m-bg:'+cfg.bg+';--bg:'+cfg.bg+';' +
    '--m-surface:'+cfg.surface+';--surface:'+cfg.surface+';' +
    '--m-surface2:'+cfg.surface2+';--surface2:'+cfg.surface2+';' +
    '--m-fg:'+cfg.fg+';--fg:'+cfg.fg+';' +
    '--m-ink:'+cfg.ink+';--ink:'+cfg.ink+';' +
    '--m-accent:'+cfg.accent+';--accent:'+cfg.accent+';' +
    '--m-rx:0deg;--m-ry:0deg;--m-scroll:0px;}\n' +
    'html{background-color:var(--m-bg)!important;color:var(--m-fg)!important;overflow-x:hidden!important;}\n' +
    'html body{background-color:transparent!important;color:var(--m-fg)!important;position:relative;z-index:1;overflow-x:hidden!important;}\n' +
    '#__mydes_backdrop{position:fixed;inset:0;width:100vw;height:100vh;pointer-events:none!important;z-index:-1;overflow:hidden;transform:translateZ(0);will-change:transform;}\n' +
    '#__mydes_hud{position:fixed;z-index:2147483640;pointer-events:none;inset:0;transform:translateZ(0);}\n' +
    '#__mydes_hud *{pointer-events:auto;}\n' +
    '/* Multi-item shelves/carousels & continuation sentinels always span full width inside any grid */\n' +
    '[data-mydes-shelf="true"], [data-mydes-sentinel="true"], ytm-reel-shelf-renderer, ytm-rich-section-renderer, ytm-continuation-item-renderer { grid-column: 1 / -1 !important; width: 100% !important; }\n';

  var fullCss = baseVars + '\n' + (cfg.customCss || '') + '\nhtml{background-color:var(--m-bg)!important;}\n';
  var root = document.head || document.documentElement;
  if(!root) return;
  if(!eStyle || !eStyle.isConnected) {
    eStyle = document.createElement('style');
    eStyle.id = '__mydes_style';
    root.appendChild(eStyle);
  }
  if(eStyle.textContent !== fullCss) eStyle.textContent = fullCss;
}

/* ---------- 3. Custom Stage Layers (3D Backdrop + Optional HUD + Sandbox JS) ---------- */
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
  window.MydesBridge._applyDecorators();
}

/* ---------- 4. Semantic DOM Role Tagger ---------- */
function each(sel,fn){var l=document.querySelectorAll(sel);for(var i=0;i<l.length;i++)fn(l[i]);}
function put(e,r){if(e.getAttribute('data-mydes')!==r)e.setAttribute('data-mydes',r);}
function putNew(e,r){if(!e.hasAttribute('data-mydes'))e.setAttribute('data-mydes',r);}
var PRICE=/^\s*(?:(?:[\u0024\u20AC\u00A3\u00A5\u20B9]|USD|EUR|GBP|INR|Rs\.?)\s?\d[\d,.]*|\d[\d,.]*\s?(?:[\u0024\u20AC\u00A3\u00A5\u20B9]|USD|EUR|GBP|INR))\s*$/;
function inContent(e){return !!e.closest('main,article,section,[role="main"]');}
function sig(e){return e.tagName+'.'+(e.classList[0]||'')+'.'+(e.classList[1]||'');}
function cardLike(e){
  if(e.closest('nav,footer,#__mydes_hud,#__mydes_backdrop,.__mydes_deco,[data-mydes="nav"],[data-mydes="footer"]'))return false;
  var tag = e.tagName.toLowerCase();
  if(/continuation|spinner|skeleton|loader|sentinel/.test(tag + ' ' + (e.className||''))) {
    e.setAttribute('data-mydes-sentinel', 'true');
    return false;
  }
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
  var ps=document.body.querySelectorAll('*:not(#__mydes_hud *):not(#__mydes_backdrop *):not(.__mydes_deco):not(.__mydes_deco *)'),n=0;
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
    if(e.closest('#__mydes_hud,#__mydes_backdrop,.__mydes_deco'))continue;
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
  each('[data-component-type="s-search-result"],[data-asin]:not([data-asin=""]),article,ytm-media-item,ytm-video-with-context-renderer',function(e){
    if(!e.closest('#__mydes_hud,#__mydes_backdrop,nav,header,footer') && cardLike(e)){
      putNew(e,'card');
      if(e.parentElement && !e.parentElement.hasAttribute('data-mydes')) putNew(e.parentElement,'grid');
      decorate(e);
    }
  });
  // Mark multi-item shelves/carousels so they span full width if their parent becomes a CSS grid
  each('[data-mydes="grid"] > *, lazy-list > *, main > *', function(e){
    if(e.querySelectorAll('img, video').length >= 3) e.setAttribute('data-mydes-shelf', 'true');
  });
  each('h1',function(e){if(!e.closest('#__mydes_hud,.__mydes_deco'))putNew(e,'title');});
  each('h2,h3,h4',function(e){if(!e.closest('#__mydes_hud,.__mydes_deco'))putNew(e,'heading');});
  each('button,input[type="submit"],input[type="button"],[role="button"],a[class*="btn"],a[class*="button"],.a-button',function(e){
    if(!e.closest('#__mydes_hud,.__mydes_deco'))putNew(e,'btn');
  });
  each('input:not([type="hidden"]):not([type="submit"]):not([type="button"]):not([type="checkbox"]):not([type="radio"]),textarea,select',function(e){
    if(!e.closest('#__mydes_hud,.__mydes_deco'))putNew(e,'field');
  });
  each('img',function(e){if(!e.closest('#__mydes_hud,#__mydes_backdrop,.__mydes_deco'))putNew(e,'media');});
  markPrices();
}

/* ---------- 5. Infinite Scroll Keeper ---------- */
function setupInfiniteScrollKeeper() {
  var lastKick = 0;
  function checkBottom() {
    var now = Date.now();
    if(now - lastKick < 500) return;
    var scrollPos = window.scrollY + window.innerHeight;
    var docHeight = Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0);
    if(docHeight - scrollPos < 450) {
      lastKick = now;
      window.dispatchEvent(new Event('scroll', {bubbles: true}));
      window.dispatchEvent(new Event('resize', {bubbles: true}));
    }
  }
  window.addEventListener('scroll', checkBottom, {passive: true});
  cleanupFns.push(function() {
    window.removeEventListener('scroll', checkBottom);
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
  return JSON.stringify({
    url:location.href,
    host:location.hostname,
    title:(document.title||'').slice(0,80),
    viewport:{w:innerWidth,h:innerHeight},
    roleCounts:counts,
    roleSamples:samples
  });
}

/* ---------- 7. Surface Remapper & Contrast Guard (V4 Proven Engine) ---------- */
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
  changes=[];EB=new WeakMap();done=new WeakSet();unitDecorators=[];ornamentRules=[];
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
  if(el.closest && el.closest('#__mydes_hud,#__mydes_backdrop,.__mydes_deco'))return;
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
          if(a[j].nodeType===1 && a[j].id!=='__mydes_backdrop' && a[j].id!=='__mydes_hud' && !a[j].classList.contains('__mydes_deco')){
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
    setupInfiniteScrollKeeper();
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

    /**
     * Lightweight tagger + outline extractor script that discovers both standard elements and custom web
     * components (e.g. ytm-media-item, article) along with their parent feed container selector.
     */
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

          var counts={};
          ['header','nav','main','aside','footer','form','button','input','img','article','h1','h2','h3'].forEach(function(t){
            counts[t]=document.querySelectorAll(t).length;
          });
          var groups={};
          var cand=document.querySelectorAll('ytm-media-item,ytm-video-with-context-renderer,article,a,li,section,div');
          for(var i=0;i<cand.length&&i<4000;i++){
            var e=cand[i];
            if(e.closest('#__mydes_hud,#__mydes_backdrop,.__mydes_deco'))continue;
            if(e.children.length<1||e.children.length>16||!e.querySelector('img,video'))continue;
            var t=(e.textContent||'').replace(/\s+/g,' ').trim();
            if(t.length<8||t.length>300)continue;
            var k=getSel(e);
            if(!k)continue;
            var p=e.parentElement;
            var parentSel=p?getSel(p):'';
            var g=groups[k]||(groups[k]={sel:k,parentSel:parentSel,n:0,sample:t.slice(0,40)});
            g.n++;
          }
          var rep=Object.keys(groups).map(function(k){return groups[k];}).filter(function(g){return g.n>=3;}).sort(function(x,y){return y.n-x.n;}).slice(0,5);
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
     * Audits contrast, horizontal overflow, squished text columns, and unfilled media containers on the live page.
     */
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
            function sel(e){var s=e.tagName.toLowerCase();var d=e.getAttribute('data-mydes');if(d)s+='[data-mydes="'+d+'"]';var cl=e.classList;for(var i=0;i<cl.length&&i<2;i++){if(cl[i]!=='__mydes_deco')s+='.'+(window.CSS&&CSS.escape?CSS.escape(cl[i]):cl[i]);}return s;}
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
            var found={},squished=[],checked=0,unknown=0,guard=0,node;
            var seen=new WeakSet();
            var tw=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null);
            var limitY=vh*2.5;
            while((node=tw.nextNode())&&guard++<3000&&checked<500){
              if(!/\S/.test(node.nodeValue))continue;
              var el=node.parentElement;
              if(!el||seen.has(el))continue;
              seen.add(el);
              if(el.closest('#__mydes_hud,#__mydes_backdrop,.__mydes_deco,script,style,noscript'))continue;
              var r=el.getBoundingClientRect();
              if(r.width<4||r.height<4||r.bottom<0||r.top>limitY)continue;
              if(r.width < 85 && r.height > r.width * 1.8 && (node.nodeValue||'').trim().length > 12 && squished.length < 6) {
                squished.push({sel: sel(el), w: Math.round(r.width), h: Math.round(r.height)});
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
            return {checked:checked,unknownBg:unknown,failing:failing,overflowX:document.documentElement.scrollWidth-vw,squishedColumns:squished,worst:worst.slice(0,10)};
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
    private val prefs = context.applicationContext.getSharedPreferences("mydes_blueprints_v11", Context.MODE_PRIVATE)
    private fun key(prompt: String, domain: String) =
        "bp11|" + sha1Hex("${domain.trim().lowercase(Locale.US)}|${prompt.trim().lowercase(Locale.US)}")
    private fun lastPromptKey(prompt: String) =
        "bp11_last|" + sha1Hex(prompt.trim().lowercase(Locale.US))

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

        /** Synthesis model for Pass 2 (Main CSS3/SVG/JS synthesis) */
        const val SYNTHESIS_MODEL = "gemini-3.5-flash-lite"

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

    // ───── Pass 1: Concept Pass (Art Director) ─────

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
            if (!pageOutlineJson.isNullOrBlank()) appendLine("LIVE DOM SEMANTIC OUTLINE: ${pageOutlineJson.take(4_000)}")
            val recent = storeFor(context).recentConcepts()
            if (recent.isNotEmpty()) {
                appendLine("RECENT DESIGNS ALREADY SHIPPED (name: traits):")
                recent.forEach { appendLine("- $it") }
            }
            appendLine("Write the design brief JSON now.")
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
  "temperature": 1.15,
  "topP": 0.98,
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
                1.15f,
                0.98f,
                1024,
                onThoughtChunk = { chunk ->
                    onThinkingUpdate?.invoke("Pass 1: Concept Brainstorming", chunk, false)
                }
            )
            val json = JSONObject(extractJsonObject(raw) ?: throw IllegalStateException("no JSON in brief: ${raw.take(200)}"))
            val idea = json.optString("idea", "").trim()
            if (idea.isBlank()) throw IllegalStateException("brief has no idea: ${raw.take(200)}")

            val directions = listOf(
                "idea" to "IDEA",
                "signature" to "SIGNATURE",
                "palette" to "PALETTE",
                "type" to "TYPE",
                "surface" to "SURFACE",
                "layout" to "LAYOUT",
                "newElements" to "NEW DOM ELEMENTS TO CREATE (SVG/HTML injected via customJs)",
                "rules" to "RULES"
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

    // ───── Pass 2 (Synthesis) & Pass 3 (Review) ─────

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
            onThinkingUpdate?.invoke("Pass 2: Generative Synthesis ($activeModel)", "Building CSS, SVG backdrop, and DOM ornaments...", false)
        } else {
            onThinkingUpdate?.invoke("Pass 3: Visual Review & QA ($activeModel)", "Auditing live render and preparing fixes...", false)
        }

        val userContextText = if (isReview) buildString {
            appendLine("REVIEW PASS")
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            appendLine("INTENDED CONCEPT: ${previous!!.concept}")
            appendLine("PALETTE: bg ${previous.bg}, surface ${previous.surface}, surface2 ${previous.surface2}, text ${previous.fg}, ink ${previous.ink}, accent ${previous.accent}")
            if (!pageOutlineJson.isNullOrBlank()) appendLine("LIVE DOM OUTLINE: ${pageOutlineJson.take(2_500)}")
            appendLine("MEASURED AUDIT (live page, ground truth): ${auditJson?.takeIf { it.isNotBlank() && it != "null" }?.take(3_000) ?: "not available"}")
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
                appendLine("LIVE DOM SEMANTIC OUTLINE: ${pageOutlineJson.take(6_000)}")
            }
            appendLine()
            appendLine("DESIGN DIRECTIONS from the art director (the spec; implement them literally):")
            appendLine(brief!!.raw)
            appendLine("Generate the complete UIBlueprint JSON now.")
        }

        val parts = mutableListOf<Part>(Part(text = userContextText))
        // Only attach screenshot on the Review Pass so the Synthesis Pass focuses purely on the Art Director spec + DOM outline (like v4)
        if (isReview && !screenshotBase64.isNullOrBlank()) {
            parts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = screenshotBase64)))
        }

        fun buildReq(jsonMode: Boolean) = GeminiRequest(
            contents = listOf(Content(parts = parts)),
            systemInstruction = Content(listOf(Part(text = if (isReview) UI_REVIEW_SYSTEM_PROMPT else UI_SYNTHESIS_SYSTEM_PROMPT))),
            generationConfig = GenerationConfig(
                temperature = if (isReview) 0.3f else 0.8f,
                topP = 0.95f,
                maxOutputTokens = if (isReview) 1536 else 8192,
                responseMimeType = if (jsonMode) "application/json" else null,
                thinkingConfig = if (!isReview) ThinkingConfig(thinkingBudget = 1024) else null
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
  "temperature": ${if (isReview) 0.3f else 0.8f},
  "topP": 0.95,
  "maxOutputTokens": ${if (isReview) 1536 else 8192},
  "responseMimeType": "application/json"${if (!isReview) ",\n  \"thinkingConfig\": { \"thinkingBudget\": 1024 }" else ""}
}""")
            appendLine()
            appendLine("=== USER CONTENT & PROMPT ===")
            append(userContextText)
            if (isReview && !screenshotBase64.isNullOrBlank()) {
                appendLine()
                appendLine("=== SCREENSHOT ATTACHMENT ===")
                appendLine("[Attached JPEG screenshot: ${screenshotBase64.length / 1024} KB base64]")
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
        val fixCss = stripInventedLabels(json.optString("fixCss", "")).trim().take(3_000)
        val fixJs = json.optString("fixJs", "").trim().take(2_000)

        if (fixCss.isBlank() && fixJs.isBlank()) return previous

        val mergedCss = if (fixCss.isNotBlank()) previous.customCss + "\n/* review */\n" + fixCss else previous.customCss
        val mergedJs = if (fixJs.isNotBlank()) previous.customJs + "\n/* review */\ntry {\n" + fixJs + "\n} catch(e){}" else previous.customJs

        val merged = JSONObject(previous.toJson())
            .put("customCss", mergedCss)
            .put("customJs", mergedJs)

        return UIBlueprint.parse(merged.toString(), prompt)
            ?.copy(concept = previous.concept, fingerprint = previous.fingerprint)
            ?: previous
    }

    // ───── System Prompts (V4 Core + Asymmetric Panel Grids + New DOM Elements) ─────

    private val UI_REVIEW_SYSTEM_PROMPT = """
You are the QA reviewer of a live website redesign on an Android phone. The design is final and approved: you do NOT redesign anything. You receive the user's request, the palette, the CURRENT CSS/JS, a MEASURED AUDIT taken from the live page (ground truth), and usually a screenshot of how it renders now.

You may ONLY append override CSS in `fixCss` (and optional DOM repair JS in `fixJs`). It is added after the current CSS, so you cannot delete anything. Never undo or tone down the design: keep every transform, shadow, texture, ornament, font, palette colour and the backdrop.

Fix only real defects, in this order:
1. Squished vertical text columns (`squishedColumns` non-empty or screenshot shows words wrapping every 2-3 letters inside narrow columns): force the affected container or shelf to span all columns (`grid-column: 1 / -1 !important; width: 100% !important; max-width: 100% !important;`) and set `word-break: normal !important; overflow-wrap: break-word !important;`.
2. Text covering thumbnails: if a styled heading/title box covers more than 30% of a video/image thumbnail (such as on vertical Shorts), force its overlay wrapper to `position: static !important; width: 100% !important;` so the title sits neatly below the thumbnail, or shrink its padding and font-size.
3. Truncated titles: if titles are cut off after 1-2 words, set `-webkit-line-clamp: unset !important; max-height: none !important; height: auto !important; overflow: visible !important; display: block !important; white-space: normal !important;`.
4. Unreadable text: the audit lists elements whose text has under 3:1 contrast against what is behind it. Fix each by setting `color` (and `background-color` if needed) using the design's own palette so that it reaches 4.5:1.
5. Elements clipped, overlapping unintentionally, or horizontal overflow (audit overflowX above 2): fix with width, max-width, overflow, margin or position overrides.

Use selectors of the form `html body <selector>` with !important. Keep fixCss under 1500 characters. If nothing is wrong, return an empty fixCss.

OUTPUT: ONLY one JSON object, no markdown fences:
{"problems": ["short list of what you found, max 5 items"], "fixCss": "css or empty string", "fixJs": "optional js or empty string"}
""".trimIndent()

    private val UI_CONCEPT_SYSTEM_PROMPT = """
You are the art director of a browser that redesigns any website around the user's imagination. You write no code. You invent one specific, original design idea; a separate builder turns your directions into CSS, a backdrop, and newly created SVG/HTML DOM ornaments on top of the live page.

THINK, silently: What does the user really want to see or feel? What is this site's real content made of (the outline lists real repeated items)? What are three genuinely different worlds it could live in? Pick the least obvious one that still fully honours the request and keeps the site usable, and give it one memorable signature visual. Reject your first idea, anything a generic "make it look <theme>" would produce by default, and anything resembling RECENT DESIGNS (do not reuse their type voice, palette family, shape language or layout idea unless the request leaves no alternative). For accessibility or comfort requests clarity wins over spectacle, but still give the design an identity.

BUILDER CAPABILITIES: it restyles real page elements with CSS, adds a full-screen SVG/Canvas backdrop layer, and runs JS (`MydesBridge.decorateUnits` and `MydesBridge.injectOrnament`) to create and attach brand-new thematic SVG/HTML elements onto cards and headers. The site's own text, links, search and items must stay readable and usable on a phone. No unrequested bottom docks or generic floating button bars.

OUTPUT: ONLY one JSON object, under 200 words in total. Every value must be concrete (hex colours, named shapes, real behaviours). Vague words like "modern", "futuristic", "sleek" or "immersive" are not decisions.
{
  "considered": ["3 alternative worlds, max 8 words each (for you; the builder never sees them)"],
  "idea": "one sentence: what this page IS",
  "signature": "the one memorable visual or interaction, max 25 words",
  "palette": "4 or 5 colours as hex, each with its job",
  "type": "type voice using web or system font stacks, max 15 words",
  "surface": "edges, material, depth, texture, clip-path shapes, borders, shadows, max 25 words",
  "layout": "how content is arranged and how that breaks the usual list (e.g. asymmetric 1-2-1 panel grid where every 3rd card spans full width and pairs sit side-by-side with angled overlapping frames), max 30 words",
  "newElements": "specific new SVG/HTML elements to create and attach onto cards and headers via JS (e.g. jagged comic speech tails, spinning brass gears, glowing sigils, targeting brackets), max 30 words",
  "rules": "how each of these behaves in this world: header, search, headings, repeated items or cards, links, buttons, images; max 45 words",
  "fingerprint": "max 6 words: type voice, palette family, shape language, layout idea"
}
""".trimIndent()

    private val UI_SYNTHESIS_SYSTEM_PROMPT = """
You are the front-end builder inside an Android web browser. An art director wrote DESIGN DIRECTIONS for how a live website should look. Implement them as CSS (`customCss`), an animated SVG/HTML backdrop (`backdropHtml`), and DOM-augmenting JS (`customJs`). The directions are the design: follow them literally and make each part visibly present (palette, type, surface, layout, newElements, rules, signature). Do not substitute your own taste.

EXECUTION ORDER & MANDATORY DIRECTIONS
- In LLM structured generation, field order is execution order.
- You MUST populate the 1-line `implemented` checklist at the very top of the JSON first (`signature`, `surface`, `newElements`, `rules`), committing to exact selectors and mechanisms before generating code.
- You MUST generate `backdropHtml` and `customJs` BEFORE `customCss` so that the SIGNATURE backdrop and NEW DOM ELEMENTS (`MydesBridge.decorateUnits` and `MydesBridge.injectOrnament`) are fully realized while your attention is 100% fresh!
- In `customCss`, you MUST implement every single rule from `RULES` (for header, search, headings, repeated items/cards, links, buttons) and `SURFACE` along with `LAYOUT`. Never skip any direction.

RUNTIME & TOOLS
- Real elements carry data-mydes="header|nav|search|main|grid|card|title|heading|price|btn|field|media|aside|footer". Select with `html body [data-mydes="X"]`. Roles are hints, not guarantees: the page's main repeating items may NOT be tagged `card`. The outline lists roleCounts, roleSamples and repeatedItems (real `sel` and `parentSel` selectors). Whenever the directions talk about items, cards or results, style `[data-mydes="card"]` AND those real `sel` selectors too. Also style standard tags (h1-h3, p, a, ul, li, table, figure, img).
- :root variables: --m-bg --m-surface --m-surface2 --m-fg --m-ink --m-accent, and motion variables --m-rx --m-ry (deg) --m-scroll (px).
- `backdropHtml`: A vivid scene drawn with inline SVG, CSS or one canvas. It sits behind all page content. Keep `html { background-color: var(--m-bg) !important; }` and `body { background-color: transparent !important; }` so the backdrop shows through.
- `customJs`: Runs as (MydesBridge, T). You MUST use `customJs` to create the `NEW DOM ELEMENTS` specified in the directions:
  * `MydesBridge.decorateUnits(selector, svgOrHtmlStringOrFn)`: Creates and attaches real inline `<svg>` or HTML ornaments (e.g. spinning toothed gears, comic action bursts, speech bubble tails, sci-fi reticles) onto every card/item!
  * `MydesBridge.injectOrnament(targetSelector, svgOrHtmlString, "append"|"prepend")`: Creates and mounts new thematic SVG/HTML centerpiece elements into the header or main stage.
  * Also available: `MydesBridge.enable3DTilt(n)`, `focusSearch()`, `proxySearch(q)`, `proxyClick(selector)`, `onCleanup(fn)`.

HOW TO BUILD ASYMMETRIC MULTI-PANEL GRIDS SAFELY
- Whenever `LAYOUT` calls for an asymmetric grid, comic panels, or multi-panel layout:
  1. Style the parent feed container (`parentSel` and `[data-mydes="grid"]`) with `display: grid !important; grid-template-columns: repeat(2, minmax(0, 1fr)) !important; gap: 12px !important; padding: 10px !important;`.
  2. Use an asymmetric 1-2-1 panel rhythm so cards are never squished: make every 3rd card span full width (`html body <sel>:nth-child(3n+1) { grid-column: 1 / -1 !important; }`), while the other cards sit side-by-side in pairs (`grid-column: span 1`) with alternating rotations (`transform: rotate(-1.2deg)` / `rotate(1.5deg)`) or overlapping negative margins!
  3. Inside any card placed in a grid, stack its thumbnail and text vertically (`display: flex !important; flex-direction: column !important; width: 100% !important;`) and set its thumbnail container and `img` to `width: 100% !important; height: auto !important;`.
  4. Never force nested multi-item shelves (`ytm-reel-shelf-renderer`, `[data-mydes-shelf="true"]`) into a single half-width column—keep them at `grid-column: 1 / -1 !important;`.

HARD RULES
- Never hide or break main, card or search. The page stays interactive and infinitely scrollable.
- THUMBNAIL OVERLAYS: Never cover a video thumbnail with a giant opaque text box! For vertical Shorts or overlay titles (`a.shortsLockupViewModelHostEndpoint h3`, `[class*="shortsLockup"] h3`), either set `position: static !important; width: 100% !important;` on the text container so the title sits neatly BELOW the thumbnail, or keep the overlay compact (`font-size: 12px !important; padding: 3px 6px !important;`) at the bottom edge.
- FULL TITLES (NO 2-WORD TRUNCATION): Whenever you style headings (`h1-h4`, `[data-mydes="heading"]`, `[data-mydes="title"]`, `.media-item-headline`), ALWAYS include `-webkit-line-clamp: unset !important; max-height: none !important; height: auto !important; overflow: visible !important; display: block !important; white-space: normal !important;` so titles never get cut off after two words!
- TEXT COLOUR: Never set `color` on generic tags (p, span, a, div, h1-h3) unless the same rule also sets the background behind them. A surface you style (card, header, item) sets its own background and `color` together and its descendants inherit; everything else inherits --m-fg from the page. All text must stay readable (4.5:1).
- No invented text in CSS `::before`/`::after` `content`. Use `MydesBridge.decorateUnits` in `customJs` for visual SVG ornaments instead.

OUTPUT: ONLY one valid JSON object, no markdown fences, no commentary:
{
  "implemented": { "signature": "...", "surface": "...", "newElements": "...", "rules": "..." },
  "name": "short title",
  "dark": true or false,
  "bg": "#rrggbb", "surface": "#rrggbb", "surface2": "#rrggbb", "fg": "#rrggbb", "ink": "#rrggbb", "accent": "#rrggbb",
  "remapSurfaces": true,
  "backdropHtml": "Vivid SVG/HTML/canvas scene realizing the SIGNATURE visual, or empty string",
  "customJs": "JS creating NEW DOM ELEMENTS via MydesBridge.decorateUnits & injectOrnament, plus enable3DTilt",
  "customCss": "complete CSS implementing LAYOUT, SURFACE, RULES, and TYPE, using !important where needed",
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
