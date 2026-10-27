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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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

/**
 * 1. Strips @import rules from CSS (we load fonts cleanly via <link> in JS without :wght@ 404 errors).
 * 2. Strips any @media (max-width: ...) block that collapses grid-template-columns to 1fr on mobile.
 */
internal fun sanitizeGeneratedCss(css: String): String {
    var out = stripInventedLabels(css)
    out = out.replace(Regex("""@import\s+url\([^)]+\)\s*;?""", RegexOption.IGNORE_CASE), "")
    // CRITICAL FIX: repeat(auto-fill, minmax(0, 1fr)) creates dozens of 0px columns and crushes cards into skinny vertical strips!
    out = out.replace(
        Regex("""repeat\(\s*auto-(?:fill|fit)\s*,\s*minmax\(\s*0(?:px)?\s*,\s*1fr\s*\)\s*\)""", RegexOption.IGNORE_CASE),
        "repeat(auto-fill, minmax(160px, 1fr))"
    )
    out = out.replace(
        Regex("""@media\s*\(\s*max-width\s*:\s*\d+px\s*\)\s*\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}""", RegexOption.IGNORE_CASE)
    ) { match ->
        if (match.value.contains("1fr")) "" else match.value
    }
    return out
}

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

        /**
         * Rebuilds a JSON object that was cut off mid-stream (token limit / dropped connection):
         * closes an open string, drops a dangling comma, fills a dangling `"key":` with null and
         * appends the missing `}` / `]`. Works from the first `{` to the END of the text, because
         * the last `}` in a truncated response usually sits inside a CSS/JS string.
         */
        fun repairTruncatedJson(raw: String): String {
            var s = raw
            if (s.contains("</think>")) s = s.substringAfterLast("</think>")
            val a = s.indexOf('{')
            if (a < 0) return s
            s = s.substring(a)
            val sb = StringBuilder()
            val stack = ArrayList<Char>()
            var inStr = false
            var esc = false
            for (ch in s) {
                sb.append(ch)
                if (inStr) {
                    if (esc) esc = false
                    else if (ch == '\\') esc = true
                    else if (ch == '"') inStr = false
                } else {
                    when (ch) {
                        '"' -> inStr = true
                        '{' -> stack.add('}')
                        '[' -> stack.add(']')
                        '}', ']' -> if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    }
                }
            }
            if (inStr) {
                if (esc) sb.setLength(sb.length - 1)
                sb.append('"')
            }
            var done = false
            while (!done) {
                var end = sb.length
                while (end > 0 && sb[end - 1].isWhitespace()) end--
                sb.setLength(end)
                if (end == 0) break
                when (sb[end - 1]) {
                    ',' -> sb.setLength(end - 1)
                    ':' -> { sb.append("null"); done = true }
                    else -> done = true
                }
            }
            for (i in stack.indices.reversed()) sb.append(stack[i])
            return sb.toString()
        }

        private fun jsonSlice(raw: String): String {
            var s = raw.trim()
            // Reasoning models may leak their chain of thought into `content`; its braces would corrupt the slice.
            if (s.contains("</think>")) s = s.substringAfterLast("</think>").trim()
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
            val j = try { JSONObject(jsonSlice(raw)) } catch (_: Exception) {
                try { JSONObject(repairTruncatedJson(raw)) } catch (_: Exception) { return null }
            }
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
                customCss = sanitizeGeneratedCss(rawCss),
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
      // Always include [data-mydes="card"] so newly infinite-scrolled cards get decorated too
      var combinedSel = rule.sel ? (rule.sel + ', [data-mydes="card"]') : '[data-mydes="card"]';
      var nodes = document.querySelectorAll(combinedSel);
      for(var i=0; i<nodes.length && i<300; i++) {
        var el = nodes[i];
        // Never decorate an inner element if its parent card is already decorated
        if(el.parentElement && el.parentElement.closest('[data-m-deco="' + cfg.id + '"]')) continue;
        if(el.closest('#__mydes_hud,#__mydes_backdrop') || el.getAttribute('data-m-deco') === cfg.id) continue;
        el.setAttribute('data-m-deco', cfg.id);
        var cs = getComputedStyle(el);
        if(cs.position === 'static') el.style.setProperty('position', 'relative', 'important');
        var res = typeof rule.fn === 'function' ? rule.fn(el, i) : String(rule.fn || '');
        if(typeof res === 'string' && res.trim()) {
          var deco = document.createElement('div');
          deco.className = '__mydes_deco';
          deco.setAttribute('aria-hidden', 'true');
          deco.style.cssText = 'pointer-events:none !important; position:absolute; inset:4px; overflow:visible; z-index:10;';
          deco.innerHTML = res;
          el.appendChild(deco);
        }
      }
    }
    for(var o=0; o<ornamentRules.length; o++) {
      var orn = ornamentRules[o];
      var target = document.querySelector(orn.sel) || document.querySelector('[data-mydes="header"], header, [role="banner"]');
      if(target && !document.querySelector('.__mydes_orn_' + o)) {
        var wrap = document.createElement('div');
        wrap.className = '__mydes_deco __mydes_orn_' + o;
        var isHdr = target.tagName === 'HEADER' || target.getAttribute('data-mydes') === 'header' || /header|topbar/i.test(orn.sel);
        if(isHdr) {
          var hRect = target.getBoundingClientRect();
          wrap.style.cssText = 'pointer-events:none!important;position:fixed!important;left:0!important;right:0!important;top:' + Math.max(44, Math.round(hRect.height)) + 'px!important;z-index:2147483642!important;display:flex!important;justify-content:center!important;align-items:center!important;width:100vw!important;';
          document.body.appendChild(wrap);
        } else {
          wrap.style.cssText = 'pointer-events:none!important;position:relative!important;z-index:10!important;width:100%!important;display:block!important;';
          if(orn.pos === 'prepend') target.prepend(wrap); else target.appendChild(wrap);
        }
        wrap.innerHTML = orn.html;
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
  var surfaceVal = cfg.backdropHtml ? 'color-mix(in srgb, '+cfg.surface+' 78%, transparent)' : cfg.surface;
  var surface2Val = cfg.backdropHtml ? 'color-mix(in srgb, '+cfg.surface2+' 82%, transparent)' : cfg.surface2;
  var rootVars = ':root{' +
    '--m-bg:'+cfg.bg+'!important;--bg:'+cfg.bg+'!important;' +
    '--m-surface:'+surfaceVal+'!important;--surface:'+surfaceVal+'!important;--m-primary:'+surfaceVal+'!important;' +
    '--m-surface2:'+surface2Val+'!important;--surface2:'+surface2Val+'!important;' +
    '--m-fg:'+cfg.fg+'!important;--fg:'+cfg.fg+'!important;--m-text:'+cfg.fg+'!important;' +
    '--m-ink:'+cfg.ink+'!important;--ink:'+cfg.ink+'!important;' +
    '--m-accent:'+cfg.accent+'!important;--accent:'+cfg.accent+'!important;' +
    '--m-border:color-mix(in srgb, '+cfg.accent+' 45%, '+cfg.surface+')!important;--border:var(--m-border)!important;' +
    '--m-rx:0deg;--m-ry:0deg;--m-scroll:0px;}\n';

  var structuralCss =
    'html{background-color:var(--m-bg)!important;color:var(--m-fg)!important;overflow-x:hidden!important;}\n' +
    'html body{background-color:transparent!important;color:var(--m-fg)!important;position:relative;z-index:1;overflow-x:hidden!important;}\n' +
    '#__mydes_backdrop{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;pointer-events:none!important;z-index:2147483630!important;mix-blend-mode:screen!important;opacity:0.42!important;overflow:hidden!important;transform:translateZ(0);}\n' +
    '#__mydes_backdrop > *, #__mydes_backdrop svg, #__mydes_backdrop canvas{position:fixed!important;inset:0!important;width:100vw!important;height:100vh!important;z-index:2147483630!important;pointer-events:none!important;}\n' +
    '#__mydes_hud{position:fixed;z-index:2147483640;pointer-events:none;inset:0;transform:translateZ(0);}\n' +
    '#__mydes_hud *{pointer-events:auto;}\n' +
    'html body header button, html body [data-mydes="header"] button, html body button:has(svg, img) { background-color: transparent !important; box-shadow: none !important; }\n' +
    /* Force EVERY child of a grid (or lazy-list) to span full width unless it is explicitly a compact grid tile */
    'lazy-list { display: flex !important; flex-direction: column !important; gap: 14px !important; width: 100% !important; max-width: 100% !important; padding: 12px 8px !important; box-sizing: border-box !important; }\n' +
    '[data-mydes-shelf="true"], [data-mydes-sentinel="true"], ytm-reel-shelf-renderer, ytm-rich-section-renderer, ytm-continuation-item-renderer, ytm-item-section-renderer { grid-column: 1 / -1 !important; width: 100% !important; max-width: 100% !important; }\n' +
    'html body [data-mydes="grid"], html body [data-mydes="card"], html body [data-mydes="card"] * { max-width: 100% !important; box-sizing: border-box !important; }\n' +
    /* Universal Card Framing & Full-Width Media Un-Constrainer (expands ALL intermediate wrappers holding the thumbnail) */
    'html body [data-mydes="card"] { display: flex !important; flex-direction: column !important; align-items: stretch !important; width: 100% !important; margin: 8px 0 !important; padding: 10px !important; height: auto !important; overflow: visible !important; background-color: var(--m-surface) !important; border: 1px solid var(--m-border) !important; }\n' +
    'html body [data-mydes="card"] ytm-media-item, html body [data-mydes="card"] [class*="media-item"] { display: flex !important; flex-direction: column !important; align-items: stretch !important; width: 100% !important; height: auto !important; }\n' +
    'html body [data-mydes="card"] *:has(> img, > video, > picture, > yt-collections-stack, > ytm-thumbnail-overlay-time-status-renderer), html body [data-mydes="card"] yt-collections-stack, html body [data-mydes="card"] a[class*="thumbnail"], html body [data-mydes="card"] div[class*="thumbnail"] { width: 100% !important; max-width: 100% !important; min-width: 100% !important; height: auto !important; display: block !important; position: relative !important; }\n' +
    'html body [data-mydes="card"] img:not([width^="1"]):not([width^="2"]):not([width^="3"]):not([width^="4"]):not([class*="avatar"]):not([class*="channel"]), html body [data-mydes="card"] video { width: 100% !important; max-width: 100% !important; height: auto !important; aspect-ratio: 16 / 9 !important; object-fit: cover !important; display: block !important; }\n' +
    'html body [data-mydes="card"] [data-mydes="heading"], html body [data-mydes="card"] h1, html body [data-mydes="card"] h2, html body [data-mydes="card"] h3 { padding: 6px 4px !important; box-sizing: border-box !important; }\n';
    
  // Put rootVars & structuralCss AFTER customCss so LLM :root redeclarations don't kill backdrop translucency or card stacking
  var fullCss = (cfg.customCss || '') + '\n' + rootVars + structuralCss;
  var root = document.head || document.documentElement;
  if(!root) return;
  if(!eStyle || !eStyle.isConnected) {
    eStyle = document.createElement('style');
    eStyle.id = '__mydes_style';
    root.appendChild(eStyle);
  }
  if(eStyle.textContent !== fullCss) eStyle.textContent = fullCss;

  // Universal Google Fonts loader (requests default weight only so single-weight fonts never 404)
  var fontRegex = /font-family\s*:\s*['"]?([^;'"!,}]+)['"]?/gi, match;
  while((match = fontRegex.exec(cfg.customCss || '')) !== null) {
    var fam = match[1].trim();
    if(fam && !/^(serif|sans-serif|monospace|cursive|fantasy|system-ui|inherit|initial|unset|Georgia|Arial|Verdana|Tahoma|Courier|Times)$/i.test(fam)) {
      var id = '__mydes_font_' + fam.replace(/\W+/g, '_');
      if(!document.getElementById(id) && document.head) {
        var link = document.createElement('link');
        link.id = id;
        link.rel = 'stylesheet';
        link.href = 'https://fonts.googleapis.com/css2?family=' + encodeURIComponent(fam).replace(/%20/g, '+') + '&display=swap';
        document.head.appendChild(link);
      }
    }
  }
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
      var cleanBd = cfg.backdropHtml.replace(/<@keyframes\s+([^>]+)>/gi, '<style>@keyframes $1</style>');
      bd.innerHTML = cleanBd; 
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
      var jsCode = cfg.customJs.trim();
      var openP = (jsCode.match(/\(/g)||[]).length - (jsCode.match(/\)/g)||[]).length;
      var openB = (jsCode.match(/\{/g)||[]).length - (jsCode.match(/\}/g)||[]).length;
      while(openB-- > 0) jsCode += '}';
      while(openP-- > 0) jsCode += ')';
      // If the LLM wrote a bare function expression without invoking it, return & call it!
      if(/^\s*\(\s*(?:function|\([^)]*\)\s*=>)/.test(jsCode) && !/\)\s*\([^)]*\)\s*;?\s*$/.test(jsCode)) {
        jsCode = 'return (' + jsCode + ')(MydesBridge, T);';
      }
      var fn = new Function('MydesBridge', 'T', jsCode);
      var ret = fn(window.MydesBridge, cfg);
      if(typeof ret === 'function') ret(window.MydesBridge, cfg);
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
  if(cfg.backdropHtml){
    var kids=document.body.children;
    for(var ki=0;ki<kids.length;ki++){
      var k=kids[ki];
      if(k.id==='__mydes_backdrop'||k.id==='__mydes_hud'||SKIPTAG.test(k.tagName.toUpperCase())||k.hasAttribute('data-mydes-lift'))continue;
      if(getComputedStyle(k).position==='static')k.setAttribute('data-mydes-lift','1');
    }
  }
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
  // 1. Tag outer card containers FIRST so inner elements are never tagged instead of their parent on scroll
  each('[data-component-type="s-search-result"],[data-asin]:not([data-asin=""]),article,ytm-video-with-context-renderer,ytm-rich-item-renderer',function(e){
    if(e.parentElement && e.parentElement.closest('[data-mydes="card"]')) return;
    if(!e.closest('#__mydes_hud,#__mydes_backdrop,nav,header,footer') && cardLike(e)){
      put(e,'card');
      if(e.parentElement && !e.parentElement.hasAttribute('data-mydes')) put(e.parentElement,'grid');
      decorate(e);
    }
  });
  // 2. Fallback for standalone inner media items that do NOT live inside an outer card container
  each('ytm-media-item',function(e){
    if(e.parentElement && e.parentElement.closest('[data-mydes="card"]')) {
      e.removeAttribute('data-mydes'); // Clean up if previously mis-tagged before parent finished rendering
      return;
    }
    if(!e.closest('#__mydes_hud,#__mydes_backdrop,nav,header,footer') && cardLike(e)){
      putNew(e,'card');
      if(e.parentElement && !e.parentElement.hasAttribute('data-mydes')) putNew(e.parentElement,'grid');
      decorate(e);
    }
  });
  // 3. Only mark true multi-card shelves/carousels (never mark a single card that just has an avatar + thumbnail)
  each('[data-mydes="grid"] > *, lazy-list > *, main > *', function(e){
    if(e.getAttribute('data-mydes') === 'card') {
      e.removeAttribute('data-mydes-shelf');
      return;
    }
    if(e.querySelectorAll('[data-mydes="card"], ytm-shorts-lockup-view-model, a:has(img)').length >= 2) {
      e.setAttribute('data-mydes-shelf', 'true');
    }
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
  var bdRole=el.getAttribute('data-mydes')||'';
  if(cfg.backdropHtml && tag!=='BODY' && tag!=='HTML' && bdRole!=='card' && bdRole!=='header' && bdRole!=='btn' && cs.position!=='fixed' && !hasBgImage(cs)){
    var wb=rgba(cs.backgroundColor);
    if(wb&&wb.a>=0.5){
      var wr=el.getBoundingClientRect();
      if(wr.width>=innerWidth*0.85&&wr.height>=innerHeight*0.18){setProp(el,'background-color','transparent');EB.delete(el);}
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
          var vw = innerWidth, vh = innerHeight;

          function getUniqueSel(el) {
            if(!el || el === document.body || el === document.documentElement) return '';
            var tag = el.tagName.toLowerCase();
            if(el.id && !/\d{4,}/.test(el.id)) return tag + '#' + CSS.escape(el.id);
            var classes = Array.from(el.classList || [])
              .filter(function(c){ return c && !c.startsWith('__mydes') && !/\d{4,}/.test(c); })
              .slice(0, 2);
            return classes.length ? tag + '.' + classes.map(CSS.escape).join('.') : tag;
          }

          // Middle-ground visibility check: must have real rendered pixel dimensions, anywhere in the entire document height!
          function isRendered(el) {
            if(!el || el.nodeType !== 1) return false;
            var cs = getComputedStyle(el);
            if(cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) === 0) return false;
            var r = el.getBoundingClientRect();
            return r.width > 16 && r.height > 16;
          }

          // 1. Top Navigation / Header
          var topBars = Array.from(document.querySelectorAll('header, nav, [role="banner"], [class*="header"], [class*="topbar"], [class*="navbar"]'))
            .filter(function(el){
              if(!isRendered(el)) return false;
              var r = el.getBoundingClientRect();
              var cs = getComputedStyle(el);
              return (r.top <= 60 && r.width >= vw * 0.8 && r.height <= 120) || cs.position === 'fixed' || cs.position === 'sticky';
            });
          var searchEl = topBars[0] ? topBars[0].querySelector('input, form, yt-searchbox, [class*="search"], button[aria-label*="Search" i], [role="search"]') : null;
          var header = topBars[0] ? {
            sel: getUniqueSel(topBars[0]),
            searchSel: searchEl ? getUniqueSel(searchEl) : null,
            height: Math.round(topBars[0].getBoundingClientRect().height)
          } : null;

          // 2. Bottom Bar / Dock
          var bottomBars = Array.from(document.querySelectorAll('footer, ytm-pivot-bar-renderer, [role="navigation"], [class*="bottom"], [class*="pivot"], [class*="tabbar"]'))
            .filter(function(el){
              if(!isRendered(el)) return false;
              var r = el.getBoundingClientRect();
              var cs = getComputedStyle(el);
              return (r.bottom >= vh - 20 && r.width >= vw * 0.8 && r.height <= 90) || (cs.position === 'fixed' && r.top > vh * 0.7);
            });
          var bottomBar = bottomBars[0] ? {
            sel: getUniqueSel(bottomBars[0]),
            height: Math.round(bottomBars[0].getBoundingClientRect().height)
          } : null;

          // 3. Filter / Chip Strip
          var filterBars = Array.from(document.querySelectorAll('ytm-chip-cloud-renderer, [class*="chip"], [class*="filter"], [role="tablist"]'))
            .filter(function(el){
              if(!isRendered(el)) return false;
              var r = el.getBoundingClientRect();
              return r.height < 65 && r.width >= vw * 0.7 && el != (bottomBars[0] || null);
            });
          var filterBar = filterBars[0] ? { sel: getUniqueSel(filterBars[0]) } : null;

          // 4. All Card Archetypes Across the Entire Document (Categorized by Size & Orientation, NOT "primary vs secondary")
          var cand = document.querySelectorAll('ytm-video-with-context-renderer, ytm-rich-item-renderer, ytm-compact-video-renderer, ytm-playlist-panel-video-renderer, ytm-shorts-lockup-view-model, article, [data-component-type="s-search-result"], [class*="card"], [class*="item"], [class*="renderer"], li');
          var groups = {};
          for(var i=0; i<cand.length && i<3000; i++) {
            var c = cand[i];
            if(c.closest('header, nav, footer, ytm-pivot-bar-renderer, #__mydes_backdrop, #__mydes_hud, .__mydes_deco')) continue;
            if(!isRendered(c)) continue;
            var r = c.getBoundingClientRect();
            if(r.width < vw * 0.22 || r.height < 45) continue;
            if(!c.querySelector('img, video, picture')) continue;
            var t = (c.textContent || '').trim();
            if(t.length < 6 || t.length > 600) continue;

            var s = getUniqueSel(c);
            if(!s || s === 'div' || s === 'section') continue;
            var pSel = c.parentElement ? getUniqueSel(c.parentElement) : '';
            groups[s] = groups[s] || { sel: s, parentSel: pSel, sample: c, count: 0, w: Math.round(r.width), h: Math.round(r.height) };
            groups[s].count++;
          }

          // Filter out inner wrappers if an outer card wrapper in the same subtree is already in `groups`
          var rawList = Object.keys(groups).map(function(k){ return groups[k]; }).filter(function(g){ return g.count >= 2; });
          var cardArchetypes = [];
          for(var a=0; a<rawList.length; a++) {
            var g = rawList[a];
            var isNestedInsideAnother = false;
            for(var b=0; b<rawList.length; b++) {
              if(a === b) continue;
              if(g.sample.parentElement && g.sample.parentElement.closest(rawList[b].sel)) {
                // Only skip if the outer one isn't a multi-card shelf holding 2+ of these
                if(rawList[b].sample.querySelectorAll(g.sel).length <= 1) {
                  isNestedInsideAnother = true;
                  break;
                }
              }
            }
            if(isNestedInsideAnother) continue;

            var sampleEl = g.sample;
            var imgEl = sampleEl.querySelector('img, video');
            var headingEl = sampleEl.querySelector('h1, h2, h3, h4, [class*="title"], [class*="headline"]');
            var isRow = false;
            if(imgEl && headingEl) {
              var ir = imgEl.getBoundingClientRect(), hr = headingEl.getBoundingClientRect();
              if(ir.width > 0 && hr.width > 0 && Math.abs(ir.top - hr.top) < 40 && ir.left < hr.left) isRow = true;
            }
            var sizeCategory = g.w >= vw * 0.7 ? (isRow ? 'full-width-horizontal-row-card' : 'full-width-vertical-card') : 'compact-grid-tile';
            cardArchetypes.push({
              sizeCategory: sizeCategory,
              cardSelector: g.sel,
              containerSelector: g.parentSel,
              dimensions: { w: g.w, h: g.h },
              count: g.count,
              anatomy: {
                mediaWrapper: imgEl ? getUniqueSel(imgEl.closest('a, [class*="thumbnail"]') || imgEl) : null,
                mediaImg: imgEl ? getUniqueSel(imgEl) : null,
                title: headingEl ? getUniqueSel(headingEl) : null
              }
            });
          }

          return JSON.stringify({
            url: location.href,
            host: location.hostname,
            viewport: { w: vw, h: vh },
            components: {
              header: header,
              filterBar: filterBar,
              cardArchetypes: cardArchetypes.slice(0, 6),
              bottomBar: bottomBar
            }
          });
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
        const val MODEL_GEMINI_35_FLASH_LITE = "gemini-3.5-flash-lite"
        const val MODEL_GEMINI_38_FLASH = "gemini-3.8-flash"
        const val MODEL_NVIDIA_NEMOTRON_3_ULTRA = "nvidia/nemotron-3-ultra-550b-a55b"

        /** Fast model for Pass 1 (Art Director creative concept brief) */
        const val CONCEPT_MODEL = MODEL_GEMINI_35_FLASH_LITE

        /** Default synthesis model for Pass 2 (Main CSS3/SVG/JS synthesis) */
        const val SYNTHESIS_MODEL = MODEL_GEMINI_35_FLASH_LITE

        /** Fast, quota-friendly model for Pass 3 (Visual QA & CSS review fix patch) */
        const val REVIEW_MODEL = MODEL_GEMINI_35_FLASH_LITE

        fun isNvidiaModel(model: String): Boolean =
            model.startsWith("nvidia/", ignoreCase = true) || model.contains("nemotron", ignoreCase = true)

        fun getSynthesisModel(context: Context): String {
            val prefs = context.getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
            return prefs.getString("synthesis_model", MODEL_GEMINI_35_FLASH_LITE) ?: MODEL_GEMINI_35_FLASH_LITE
        }

        fun setSynthesisModel(context: Context, model: String) {
            val prefs = context.getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("synthesis_model", model).apply()
        }

        fun getNvidiaApiKey(context: Context): String {
            val prefs = context.getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
            val customKey = prefs.getString("custom_nvidia_api_key", "") ?: ""
            if (customKey.isNotBlank()) return customKey
            val buildConfigKey = try {
                val field = BuildConfig::class.java.getField("NVIDIA_API_KEY")
                field.get(null) as? String ?: ""
            } catch (_: Throwable) { "" }
            if (buildConfigKey.isNotBlank() && buildConfigKey != "MY_NVIDIA_API_KEY") return buildConfigKey
            return customKey
        }

        fun setNvidiaApiKey(context: Context, key: String) {
            val prefs = context.getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("custom_nvidia_api_key", key.trim()).apply()
        }
    }

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()

    private val okHttpClient = OkHttpClient.Builder()
        .dns(ResilientGoogleDns)
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(600, TimeUnit.SECONDS)
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
        // Concept Pass (Pass 1) ALWAYS runs on gemini-3.5-flash-lite only
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

        val geminiApiKey = getApiKey(context)
        val nvidiaApiKey = getNvidiaApiKey(context)

        // Gemini API key is always required because Pass 1 (Concept) and Pass 3 (Review) strictly run on gemini-3.5-flash-lite
        if (geminiApiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GEMINI_API_KEY is not set. Please set your Gemini API key in Settings."))
        }

        val previous = if (isReviewPass || !screenshotBase64.isNullOrBlank() || !auditJson.isNullOrBlank()) {
            storeFor(context).get(cleanPrompt, domain)
        } else null

        if (isReviewPass && previous == null) {
            return@withContext Result.failure(IllegalStateException("Review pass skipped: no base blueprint cached for $domain"))
        }

        val isReview = isReviewPass || (previous != null && !forceFresh && (!screenshotBase64.isNullOrBlank() || !auditJson.isNullOrBlank()))

        // Pass 3 (Review) strictly runs on REVIEW_MODEL ("gemini-3.5-flash-lite").
        // Pass 2 (Synthesis) is the ONLY pass that uses the user's selected synthesis model (Nemotron or Gemini).
        val synthesisModel = getSynthesisModel(context)
        val isNemotronSynthesis = !isReview && isNvidiaModel(synthesisModel)
        val activeModel = if (isReview) REVIEW_MODEL else synthesisModel

        if (isNemotronSynthesis && nvidiaApiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("NVIDIA NIM API key is not set. Please enter your NVIDIA API key (nvapi-...) in Settings."))
        }

        // FAIL-FAST: Pass 1 (Concept Pass) strictly runs on CONCEPT_MODEL ("gemini-3.5-flash-lite")
        val brief: ConceptBrief? = if (isReview) {
            null
        } else {
            designBrief(context, cleanPrompt, websiteName, websiteUrl, pageOutlineJson, geminiApiKey, onThinkingUpdate)
                .getOrElse { conceptError -> return@withContext Result.failure(conceptError) }
        }

        if (!isReview) {
            val modelLabel = if (isNemotronSynthesis) "Nemotron 3 Ultra 550B (NVIDIA NIM)" else activeModel
            onThinkingUpdate?.invoke("Pass 2: Generative Synthesis ($modelLabel)", "Building CSS, SVG backdrop, and DOM ornaments...", false)
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
            appendLine(if (isNemotronSynthesis) "$activeModel (NVIDIA NIM)" else activeModel)
            appendLine()
            appendLine("=== PASS TYPE ===")
            appendLine(if (isReview) "Pass 3: Visual QA & Review Pass" else if (isNemotronSynthesis) "Pass 2: NVIDIA NIM Generative Synthesis Pass" else "Pass 2: Main Generative Synthesis Pass")
            appendLine()
            appendLine("=== SYSTEM INSTRUCTION ===")
            appendLine(if (isReview) UI_REVIEW_SYSTEM_PROMPT else UI_SYNTHESIS_SYSTEM_PROMPT)
            appendLine()
            appendLine("=== GENERATION CONFIG ===")
            appendLine("""{
  "temperature": ${if (isReview) 0.3f else 0.8f},
  "topP": 0.95,
  "maxOutputTokens": ${if (isReview) 1536 else if (isNemotronSynthesis) NIM_MAX_TOKENS else 8192},
  "provider": "${if (isNemotronSynthesis) "NVIDIA NIM" else "Google AI Studio"}"
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
            val (raw, rawThoughts) = if (isNemotronSynthesis) {
                callNvidiaNimStream(
                    model = activeModel,
                    apiKey = nvidiaApiKey,
                    systemInstruction = UI_SYNTHESIS_SYSTEM_PROMPT,
                    userContent = userContextText,
                    temperature = 0.7f,
                    topP = 0.95f,
                    maxTokens = NIM_MAX_TOKENS,
                    onThoughtChunk = { chunk ->
                        onThinkingUpdate?.invoke("Pass 2: Nemotron 3 Ultra Reasoning", chunk, false)
                    },
                    onOutputChunk = { _ ->
                        onThinkingUpdate?.invoke("Pass 2: Generating UI Code ($activeModel)", "", false)
                    }
                )
            } else {
                callGeminiStream(
                    activeModel,
                    buildReq(true),
                    geminiApiKey,
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
            }
            val blueprint: UIBlueprint? = if (isReview) {
                applyReviewPatch(previous!!, raw, cleanPrompt)
            } else {
                val parsed = UIBlueprint.parse(raw, cleanPrompt)
                parsed?.copy(concept = brief!!.idea, fingerprint = brief.fingerprint)
            }

            if (blueprint == null) {
                val err = "Model returned invalid UIBlueprint JSON (finish_reason=${lastNimFinishReason ?: "n/a"}, " +
                    "contentChars=${raw.length}, reasoningChars=${rawThoughts.length}). Raw start: ${raw.take(200)} ... Raw end: ${raw.takeLast(200)}"
                val errLog = err + "\n\n=== FULL RAW OUTPUT ===\n" + raw +
                    (if (rawThoughts.isNotBlank()) "\n\n=== REASONING ===\n" + rawThoughts else "")
                ApiRequestLogger.logRequest(context, logType, websiteName, websiteUrl, cleanPrompt, requestJson, errLog, false)
                Result.failure(IllegalStateException(err))
            } else {
                storeFor(context).put(cleanPrompt, blueprint, domain, remember = !isReview)
                val truncNote = if (isNemotronSynthesis && !isReview && lastNimFinishReason == "length") "!!! OUTPUT WAS TRUNCATED (finish_reason=length); blueprint was repaired and may be missing the end of customCss !!!\n\n" else ""
                val logResponse = if (rawThoughts.isNotBlank()) {
                    truncNote + "=== REASONING & THINKING PROCESS ===\n$rawThoughts\n\n=== RESPONSE RESULT ===\n" + (if (isReview) raw else blueprint.toJson())
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

BUILDER CAPABILITIES: It can restyle real page elements with CSS, add a full-screen SVG/Canvas backdrop layer, and run JS (`MydesBridge.decorateUnits` and `MydesBridge.injectOrnament`) to create and attach brand-new thematic SVG/HTML elements onto pre-existing elements. The site's own text, links, search and items can be enhanced but must stay readable and usable on a phone.

OUTPUT: ONLY one JSON object, under 200 words in total. Every value must be concrete (hex colours, named shapes, real behaviours). Vague words like "modern", "futuristic", "sleek" or "immersive" are not decisions.
{
  "considered": ["3 alternative worlds, max 8 words each (for you; the builder never sees them)"],
  "idea": "one sentence: what this page IS",
  "signature": "the one memorable visual or interaction, max 25 words",
  "palette": "4 or 5 colours as hex, each with its job",
  "type": "typographic styling and formatting using fonts from Google Fonts, max 15 words",
  "surface": "edges, material, depth, texture, clip-path shapes, borders, shadows, max 25 words",
    "layout": "how content is arranged and how that transforms the usual list or page flow to fit this world, max 30 words",
  "newElements": "specific new SVG/HTML elements to create and attach onto items and headers via JS to bring this world to life, max 30 words",
  "rules": "how each of these behaves in this world: header, search, headings, repeated items or cards, links, buttons, images; max 45 words",
  "fingerprint": "max 6 words: typographic styling and formatting, palette family, shape language, layout idea"
}
""".trimIndent()

        private val UI_SYNTHESIS_SYSTEM_PROMPT = """
You are the front-end builder inside an Android web browser. An art director wrote DESIGN DIRECTIONS for how a live website should look. Implement them as CSS (`customCss`), an animated SVG/HTML backdrop (`backdropHtml`), and DOM-augmenting JS (`customJs`). The directions are the design: follow them literally and make each part visibly present (palette, type, surface, layout, newElements, rules, signature). Do not substitute your own taste or fall back to generic templates.

EXECUTION ORDER & MANDATORY DIRECTIONS
- In LLM structured generation, field order is execution order.
- You MUST populate the 1-line `implemented` checklist at the very top of the JSON first (`signature`, `layout`, `surface`, `newElements`, `rules`), committing to the exact selectors and mechanisms from the outline before generating code. Each `implemented` value is ONE short sentence (max 20 words), with no code and no lists.
- You MUST generate `backdropHtml` and `customJs` BEFORE `customCss` so that the SIGNATURE backdrop and NEW DOM ELEMENTS (`MydesBridge.decorateUnits` and `MydesBridge.injectOrnament`) are fully realized while your attention is 100% fresh.
- In `customCss`, you MUST implement every single item from `RULES` (header, search, headings, repeated items/cards, links, buttons, images), `SURFACE`, `LAYOUT`, and `TYPE`. Never skip any direction.

COMPONENT ARCHETYPE CONTRACT:
The outline provides a `components` map containing the exact live selectors for this website:
1. `header`: Apply the SIGNATURE header rules, backdrop/glass styling, and brand identity directly to `components.header.sel` (and use MydesBridge.injectOrnament on it). Style `components.header.sel` as the themed bar, and style `components.header.searchSel` (if present) as the search input/trigger.
2. `filterBar`: If present, style `components.filterBar.sel` and its child chips/pills to match the theme's surface, accent border, and typography.
3. `cardArchetypes`: The outline lists ALL card types on the page grouped by their dimensions (`full-width-vertical-card`, `full-width-horizontal-row-card`, `compact-grid-tile`):
   - You MUST style EVERY `cardSelector` in `components.cardArchetypes` (along with `[data-mydes="card"]`) using the art director's SURFACE, RULES, and NEW DOM ELEMENTS (`MydesBridge.decorateUnits`).
   - Respect their dimensions:
     * For `full-width-vertical-card` and `full-width-horizontal-row-card`: keep them as full-width dossier panels (`width: 100% !important; display: flex !important; flex-direction: column !important;`) with their `anatomy.mediaWrapper` and `anatomy.mediaImg` stretched to `width: 100% !important; height: auto !important;`.
     * For `compact-grid-tile`: keep them in a 2-column grid (`grid-template-columns: repeat(2, minmax(0, 1fr)) !important; gap: 10px !important;`) on their `containerSelector`.
     * Style every archetype's `anatomy.title` with the TYPE directions (`-webkit-line-clamp: unset !important; overflow: visible !important;`).4. `shelves`: If any shelf selectors are listed in `components.shelves`, span them full width (`grid-column: 1 / -1 !important; width: 100% !important;`).
5. `bottomBar`: If present, style `components.bottomBar.sel` to match the theme background, surface borders, and icons.

RUNTIME & TOOLS
- Real elements carry data-mydes="header|nav|search|main|grid|card|title|heading|price|btn|field|media|aside|footer". Select with `html body [data-mydes="X"]`. Roles are hints, not guarantees: the page's main repeating items may NOT be tagged `card`. The outline lists roleCounts, roleSamples and repeatedItems (real `sel` and `parentSel` selectors). Whenever the directions talk about items, cards or results, style `html body [data-mydes="card"]` AND those real `sel` selectors too. Also style standard tags (h1-h3, p, a, ul, li, table, figure, img).
- :root variables: --m-bg --m-surface --m-surface2 --m-fg --m-ink --m-accent, and motion variables --m-rx --m-ry (deg) --m-scroll (px).
- `backdropHtml`: A vivid scene drawn with inline SVG, CSS or one canvas that realizes the SIGNATURE background/stage. It sits behind all page content. Keep `html { background-color: var(--m-bg) !important; }` and `body, main, [data-mydes="main"], [data-mydes="grid"] { background-color: transparent !important; }` so the backdrop shows through clearly. When animating SVG `<g>` or `<circle>` elements inside backdropHtml, use pure `rotate(0deg)` -> `rotate(360deg)` with `transform-origin: center; transform-box: fill-box;` (never put `translate(-50%, -50%)` inside @keyframes on inner SVG shapes).
- `customJs`: Runs as (MydesBridge, T). Write top-level statements directly: MydesBridge.decorateUnits(...); MydesBridge.injectOrnament(...); MydesBridge.enable3DTilt(12);. NEVER wrap customJs in (function(){...})! You MUST use `customJs` to create the `NEW DOM ELEMENTS` specified in the directions:
  * `MydesBridge.decorateUnits(selector, htmlString)`: In `MydesBridge.decorateUnits(sel, htmlString)`, pass an HTML/SVG string as the second argument (do NOT write complex DOM-creation callbacks). Only target the outermost card selector in repeatedItems, never inner details wrappers. Always pass components.primaryFeed.cardSelector + ', [data-mydes=\"card\"]' as the first argument to MydesBridge.decorateUnits(...) so every card on the page gets the SVG ornaments.
  * `MydesBridge.injectOrnament(targetSelector, svgOrHtmlString, "append"|"prepend")`: Creates and mounts new thematic SVG/HTML centerpiece or structural elements into the header or main stage.
  * `MydesBridge.enable3DTilt(n)`: Updates `--m-rx` and `--m-ry` on scroll/touch. Whenever you call `enable3DTilt`, you MUST reference `var(--m-rx)` and `var(--m-ry)` inside a `transform` rule in `customCss`.
  * Also available: `focusSearch()`, `proxySearch(q)`, `proxyClick(selector)`, `onCleanup(fn)`.

SPATIAL & LAYOUT SAFETY (APPLIES TO ANY CHOSEN LAYOUT)
- Implement whatever `LAYOUT` the directions specify on the parent container (`parentSel` and `[data-mydes="grid"]`) and child items (`sel` and `[data-mydes="card"]`), while obeying these structural physics laws so the layout never breaks on any screen size:
  1. NO HORIZONTAL OVERFLOW: Any multi-column grid MUST use explicit column counts like grid-template-columns: repeat(2, minmax(0, 1fr)) or minimum pixel widths like repeat(auto-fill, minmax(160px, 1fr)) — NEVER write repeat(auto-fill, minmax(0, 1fr))! and `width: 100% !important; max-width: 100% !important; box-sizing: border-box !important;`, and all child items must include `min-width: 0 !important; max-width: 100% !important; box-sizing: border-box !important;`.
  2. REFLOW INTERNAL ROWS WHEN NARROWED: Whenever your layout places items side-by-side or narrows an item below 60% of the viewport width, any horizontal row inside that item (such as a side-by-side thumbnail + text wrapper) MUST be stacked vertically (`display: flex !important; flex-direction: column !important; width: 100% !important;`) with its media/thumbnail container and `img`/`video` set to `width: 100% !important; height: auto !important;`.
  3. PROTECT NESTED CAROUSELS/SHELVES: Never squeeze a container that already holds multiple side-by-side cards (`[data-mydes-shelf="true"]`) into a narrow single column of a parent grid—always let nested shelves and continuation sentinels (`[data-mydes-sentinel="true"]`) span the full container width (`grid-column: 1 / -1 !important; width: 100% !important;`).
  4. BAN @MEDIA (MAX-WIDTH: 600PX) RESETS: The target viewport is ALREADY a mobile phone (~400px wide). NEVER write @media (max-width: 600px) or @media (max-width: 768px) rules that collapse your layout back to 1fr—design your primary layout directly for the ~400px mobile viewport.

HARD RULES
- BAN @MEDIA (MAX-WIDTH: 600PX) RESETS: The target viewport is ALREADY a mobile phone (~400px wide). NEVER write @media (max-width: 600px) or @media (max-width: 768px) rules that collapse your layout back to 1fr—design your primary layout directly for the ~400px mobile viewport.
- STYLE ONLY OUTERMOST CARD SELECTORS & RETURN HTML STRINGS IN DECORATEUNITS: In `MydesBridge.decorateUnits(sel, htmlString)`, pass an HTML/SVG string as the second argument (do NOT write complex DOM-creation callbacks). Only target the outermost card selector in repeatedItems, never inner details wrappers.
- Never hide or break main, card or search. The page stays interactive and infinitely scrollable (never set `overflow: hidden` or a fixed `height` on `html`, `body`, or main feed containers).
- MEDIA OVERLAYS: Never cover an image or video thumbnail with opaque elements! If a card places its heading/title inside an overlay on top of media, either set `position: static !important; width: 100% !important;` on the text wrapper so the title sits neatly BELOW the media, or keep the overlay compact (`font-size: 12px !important; padding: 3px 6px !important; max-height: 3.4em !important;`) at the bottom edge.
- FULL TITLES (NO PREMATURE TRUNCATION): Whenever you style headings or titles (`h1-h4`, `[data-mydes="heading"]`, `[data-mydes="title"]`, `[class*="headline"]`), ALWAYS include `-webkit-line-clamp: unset !important; line-clamp: unset !important; max-height: none !important; height: auto !important; overflow: visible !important; display: block !important; white-space: normal !important; word-break: normal !important; overflow-wrap: break-word !important;` so titles are never sliced off after two words or wrapped letter-by-letter.
- TEXT COLOUR: Never set `color` on generic tags (p, span, a, div, h1-h3) unless the same rule also sets the background behind them. A surface you style (card, header, item) sets its own background and `color` together and its descendants inherit; everything else inherits --m-fg from the page. All text must stay readable (>= 4.5:1 contrast).
- CLIP-PATH SAFETY: Never use clip-path: polygon(...) with corner cuts deeper than 8px (use calc(100% - 8px) instead of percentages like 12% / 88%), or prefer borders, box-shadow, and MydesBridge.decorateUnits SVG frames so you never slice off card titles or corner ornaments.
- No invented words in CSS `::before`/`::after` `content`. Use `MydesBridge.decorateUnits` and `MydesBridge.injectOrnament` in `customJs` for custom SVG/HTML elements instead.

OUTPUT: ONLY one valid JSON object, no markdown fences, no commentary:
{
  "implemented": { "signature": "...", "layout": "...", "surface": "...", "newElements": "...", "rules": "..." },
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

    /** Reasoning tokens count against max_tokens on NIM reasoning models, so this must cover thinking + the full JSON. */
    private val NIM_MAX_TOKENS = 16384
    /**
     * Medium reasoning effort, per the NIM docs: extra_body={"chat_template_kwargs": {"enable_thinking": True, "medium_effort": True}}.
     * The Python SDK merges extra_body into the top level of the HTTP body, so over raw HTTP it sits beside "messages".
     */
    private fun nimReasoningKwargs(): JSONObject = JSONObject()
        .put("enable_thinking", true)
        .put("medium_effort", true)
    @Volatile private var lastNimFinishReason: String? = null

    private suspend fun callNvidiaNimStream(
        model: String,
        apiKey: String,
        systemInstruction: String,
        userContent: String,
        temperature: Float = 0.7f,
        topP: Float = 0.95f,
        maxTokens: Int = NIM_MAX_TOKENS,
        onThoughtChunk: ((String) -> Unit)? = null,
        onOutputChunk: ((String) -> Unit)? = null
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        lastNimFinishReason = null
        val fullText = StringBuilder()
        val rawThoughts = StringBuilder()

        val jsonBody = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemInstruction))
                .put(JSONObject().put("role", "user").put("content", userContent))
            )
            .put("temperature", temperature)
            .put("top_p", topP)
            .put("max_tokens", maxTokens)
            .put("chat_template_kwargs", nimReasoningKwargs())
            .put("stream", true)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(jsonBody.toString().toRequestBody(mediaType))
            .build()

        var streamWorked = false
        var lastErr: Exception? = null

        try {
            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                val errBody = response.body?.string() ?: ""
                throw java.io.IOException("NVIDIA NIM HTTP ${response.code}: $errBody")
            }

            response.body?.byteStream()?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                var line = reader.readLine()
                while (line != null) {
                    val trimmed = line.trim()
                    if (trimmed.startsWith("data:")) {
                        val payload = trimmed.removePrefix("data:").trim()
                        if (payload == "[DONE]") {
                            streamWorked = true
                            break
                        }
                        if (payload.isNotEmpty()) {
                            try {
                                val chunk = JSONObject(payload)
                                val choices = chunk.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val choice = choices.getJSONObject(0)
                                    if (!choice.isNull("finish_reason")) lastNimFinishReason = choice.optString("finish_reason")
                                    val delta = choice.optJSONObject("delta")
                                    if (delta != null) {
                                        val reasoning = delta.optString("reasoning_content", "")
                                            .ifEmpty { delta.optString("reasoning", "") }
                                        if (reasoning.isNotEmpty()) {
                                            rawThoughts.append(reasoning)
                                            onThoughtChunk?.invoke(reasoning)
                                        }
                                        val content = delta.optString("content", "")
                                        if (content.isNotEmpty()) {
                                            fullText.append(content)
                                            onOutputChunk?.invoke(content)
                                        }
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    line = reader.readLine()
                }
            }
            if (fullText.isNotBlank()) {
                streamWorked = true
            }
        } catch (e: Exception) {
            lastErr = e
        }

        if (streamWorked && fullText.isNotBlank()) {
            return@withContext Pair(fullText.toString(), rawThoughts.toString())
        }

        // Non-streaming fallback if stream failed or returned empty
        val fallbackBody = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemInstruction))
                .put(JSONObject().put("role", "user").put("content", userContent))
            )
            .put("temperature", temperature)
            .put("top_p", topP)
            .put("max_tokens", maxTokens)
            .put("chat_template_kwargs", nimReasoningKwargs())
            .put("stream", false)

        val nonStreamReq = Request.Builder()
            .url("https://integrate.api.nvidia.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer ${apiKey.trim()}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .post(fallbackBody.toString().toRequestBody(mediaType))
            .build()

        val nonStreamResp = okHttpClient.newCall(nonStreamReq).execute()
        if (!nonStreamResp.isSuccessful) {
            val errBody = nonStreamResp.body?.string() ?: ""
            throw java.io.IOException(lastErr?.message ?: "NVIDIA NIM HTTP ${nonStreamResp.code}: $errBody")
        }

        val nonStreamJson = JSONObject(nonStreamResp.body?.string() ?: throw java.io.IOException("Empty response from NVIDIA NIM"))
        val choice = nonStreamJson.optJSONArray("choices")?.optJSONObject(0)
        val msg = choice?.optJSONObject("message")
        if (choice != null && !choice.isNull("finish_reason")) lastNimFinishReason = choice.optString("finish_reason")
        val content = msg?.optString("content", "") ?: ""
        val reasoning = msg?.optString("reasoning_content", "")
            ?: msg?.optString("reasoning", "") ?: ""

        if (content.isNotBlank()) {
            return@withContext Pair(content, reasoning)
        }

        throw lastErr ?: java.io.IOException("No content returned from model $model")
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