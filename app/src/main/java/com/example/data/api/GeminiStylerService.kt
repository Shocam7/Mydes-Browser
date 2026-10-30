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
import com.squareup.moshi.Json
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
 * v19 - "Zero-Lag 60fps Static Artistry Engine"
 *
 * Designed for Mid-to-High Tier Mobile Devices:
 * 1. Zero GPU Lag & Zero Full-Screen Overdraw:
 *    - Eliminates full-viewport `body::before`/`body::after` particle/blend overlays, continuous
 *      `@keyframes` loops, `background-attachment: fixed`, and per-card `backdrop-filter: blur()`.
 *    - Guarantees native 60fps scrolling performance on mid-tier mobile GPUs.
 * 2. Token-Efficient, High-Craftsmanship Pipeline:
 *    - Pass 1 (Visual Architect) focuses 100% on static visual artistry: rich color harmony,
 *      tactile surface gradients, crisp geometric corner/edge trims, polychromatic :nth-child
 *      rhythms, and expressive typography.
 *    - Pass 2 (Pure CSS Compiler) compiles clean, lightweight, conflict-free CSS with instant
 *      `:active` / `:focus-within` tactile transitions instead of heavy infinite animations.
 *    - Pass 3 (Telemetry & Visual Reviewer) stitches any un-themed host containers (`clashingSurfaces`)
 *      and low-contrast text (`lowContrastText`) without adding heavy overlays.
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
    @Json(name = "thinking_level") val thinkingLevel: String? = null,
    @Json(name = "thinking_config") val thinkingConfig: ThinkingConfig? = null
)

@JsonClass(generateAdapter = true)
data class ThinkingConfig(
    @Json(name = "thinking_budget") val thinkingBudget: Int? = null,
    @Json(name = "thinking_level") val thinkingLevel: String? = null
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

    private val HEX_FINDER = Regex("#([0-9a-fA-F]{6}|[0-9a-fA-F]{3})")
    fun extractFirstHex(s: String?, fallback: String): String {
        if (s.isNullOrBlank()) return fallback
        val m = HEX_FINDER.find(s) ?: return fallback
        return norm(m.value, fallback)
    }
    fun extractSecondHex(s: String?, fallback: String): String {
        if (s.isNullOrBlank()) return fallback
        val matches = HEX_FINDER.findAll(s).toList()
        return if (matches.size >= 2) norm(matches[1].value, fallback) else extractFirstHex(s, fallback)
    }
}

// ───────────────────── 60fps Mobile GPU Sanitizer & Auto-Healer ─────────────────────

internal fun stripInventedLabels(css: String): String =
    css.replace(
        Regex("""[^{}]*::?(?:before|after)\s*\{[^{}]*content\s*:\s*(["'])(?!data:)[^"'{}]*[A-Za-z]{4,}[^"'{}]*\1[^{}]*\}""", RegexOption.IGNORE_CASE),
        ""
    )

/**
 * 60fps Mobile GPU & Layout Firewall:
 * 1. Strips full-screen `body::before` and `body::after` overlays (eliminates full-screen blend-mode overdraw).
 * 2. Strips `background-attachment: fixed` (which disables hardware-accelerated scrolling on mobile WebViews).
 * 3. Strips `backdrop-filter` on repeating `[data-mydes="card"]` elements (prevents per-card Gaussian blur lag).
 * 4. Repairs mojibake in `content: "..."` and illegal shorthand tokens inside `background-image:`.
 * 5. Protects native Flexbox/Grid layouts and prevents bare `a`/`button` tags from breaking containers.
 */
internal fun sanitizeGeneratedCss(css: String): String {
    var out = stripInventedLabels(css)
    out = out.replace(Regex("""@import\s+url\([^)]+\)\s*;?""", RegexOption.IGNORE_CASE), "")

    // 1. Strip full-screen body::before and body::after rules & @keyframes to guarantee 60fps scrolling
    out = out.replace(
        Regex("""(?:html\s+)?body::?(?:before|after)\s*\{[^{}]*\}""", RegexOption.IGNORE_CASE),
        ""
    )
    out = out.replace(
        Regex("""@keyframes\s+[A-Za-z0-9_-]+\s*\{[^{}]*(?:\{[^{}]*\}[^{}]*)*\}""", RegexOption.IGNORE_CASE),
        ""
    )
    out = out.replace(
        Regex("""animation(?:-name|-duration|-timing-function|-iteration-count|-direction)?\s*:[^;!}]+(?:!important)?\s*;?""", RegexOption.IGNORE_CASE),
        ""
    )

    // 2. Strip `background-attachment: fixed` (causes severe repaint lag during touch scrolling on Android WebView)
    out = out.replace(
        Regex("""background-attachment\s*:\s*fixed\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE),
        ""
    )

    // 3. Strip `backdrop-filter` from repeating `[data-mydes="card"]` rules (major GPU bottleneck on mid-tier phones)
    out = out.replace(Regex("""(\[data-mydes=['"]card['"]\][^{]*\{)([^}]+)\}""", RegexOption.IGNORE_CASE)) { match ->
        val sel = match.groupValues[1]
        val body = match.groupValues[2]
            .replace(Regex("""(?:-webkit-)?backdrop-filter\s*:[^;!}]+(?:!important)?\s*;?""", RegexOption.IGNORE_CASE), "")
        "$sel$body}"
    }

    // 4. Fix corrupted UTF-8 / mojibake inside ::before/::after `content: "..."`
    out = out.replace(Regex("""content\s*:\s*(["'])([^"']*)\1""")) { match ->
        val quote = match.groupValues[1]
        val inner = match.groupValues[2]
        if (inner.isEmpty() || inner.startsWith("\\")) {
            match.value
        } else {
            val hasMojibake = inner.any { ch ->
                val code = ch.code
                (code in 0xE000..0xF8FF) || (code in 0xFF61..0xFF9F) || (code in 0x4E00..0x9FFF && inner.length <= 4) || code == 0xFFFD
            }
            if (hasMojibake) "content: ${quote}\\2726 ${quote}" else match.value
        }
    }

    // 5. Strip layout-breaking flex-direction overrides & negative margins
    out = out.replace(
        Regex("""flex-direction\s*:\s*column\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE),
        ""
    )
    out = out.replace(
        Regex("""margin(?:-bottom|-top)?\s*:\s*-\d+(?:\.\d+)?(?:px|rem|em|%)\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE),
        "margin-bottom: 12px;"
    )

    // 6. Strip unreferenced external SVG url(#id) masks
    out = out.replace(
        Regex("""(?:-webkit-)?mask(?:-image)?\s*:\s*url\([^)]+\)\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE),
        ""
    )

    // 7. Syntax Auto-Healer: Clean illegal shorthand placement inside background-image declarations
    out = out.replace(Regex("""background-image\s*:\s*([^;!}]+)""", RegexOption.IGNORE_CASE)) { match ->
        val propVal = match.groupValues[1]
        val cleaned = propVal.replace(
            Regex("""\)\s+(?:[\d.]+(?:px|%|em|rem)?\s+[\d.]+(?:px|%|em|rem)?(?:\/[\d.]+(?:px|%|em|rem)?)?|no-repeat|repeat-x|repeat-y|repeat)\b""", RegexOption.IGNORE_CASE),
            ")"
        )
        "background-image: $cleaned"
    }

    // 8. Prevent [data-mydes="btn"] from forcing fixed square/circle width & height
    out = out.replace(Regex("""(\[data-mydes=['"]btn['"]\][^{]*\{)([^}]+)\}""", RegexOption.IGNORE_CASE)) { match ->
        val sel = match.groupValues[1]
        val body = match.groupValues[2]
            .replace(Regex("""\b(?:width|height)\s*:\s*\d+(?:px|rem|em)\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE), "")
        "$sel$body}"
    }

    // 9. Isolate broad 'a' and 'button' tags from box-model mutations
    out = out.replace(Regex("""([^{}@]+)\{([^{}]+)\}""")) { match ->
        val rawSelectors = match.groupValues[1]
        val body = match.groupValues[2]
        val touchesBoxModel = Regex("""\b(?:padding|border|background|display|width|height)\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)
        if (!touchesBoxModel) {
            match.value
        } else {
            val safeSelectors = rawSelectors.split(',').map { sel ->
                val s = sel.trim()
                when {
                    Regex("""^(?:html\s+|body\s+)*a(?::[a-zA-Z-]+)?$""").matches(s) -> {
                        val pseudo = s.substringAfter("a", "")
                        "a[data-mydes=\"btn\"]$pseudo"
                    }
                    Regex("""^(?:html\s+|body\s+)*(?:button|\[data-mydes=['"]btn['"]\])(?::[a-zA-Z-]+)?$""").matches(s) -> {
                        "$s:not(:has(svg, img)):not([aria-label])"
                    }
                    else -> s
                }
            }.joinToString(", ")
            "$safeSelectors{$body}"
        }
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
    val fingerprint: String = "",
    val designSpec: String = ""
) {
    fun toJson(): String = JSONObject()
        .put("concept", concept)
        .put("fingerprint", fingerprint)
        .put("designSpec", designSpec)
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
        const val RUNTIME_VERSION = 19

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

            val bg = Hex.norm(j.optString("bg"), if (dark) "#0a0c0e" else "#f8f9fa")
            val surface = Hex.norm(j.optString("surface"), Hex.mix(bg, if (dark) white else black, 0.08))
            val surface2 = Hex.norm(j.optString("surface2"), Hex.mix(surface, if (dark) white else black, 0.08))

            var fg = Hex.norm(j.optString("fg"), if (dark) "#e2e8f0" else "#1a202c")
            if (Hex.contrast(fg, bg) < 4.5) {
                fg = if (Hex.lum(bg) < 0.4) "#f8fafc" else "#0f172a"
            }
            val rawInk = Hex.norm(j.optString("ink"), fg)
            val ink = if (Hex.contrast(rawInk, surface) >= 3.0) rawInk else fg
            val accent = Hex.norm(j.optString("accent"), if (dark) "#00ff88" else "#0284c7")

            val rawCss = when {
                j.has("customCss") -> j.optString("customCss", "")
                j.has("custom_css") -> j.optString("custom_css", "")
                j.has("css") -> j.optString("css", "")
                j.has("fixCss") -> j.optString("fixCss", "")
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
                id = sha1Hex("v$RUNTIME_VERSION|${prompt.trim().lowercase(Locale.US)}|$rawCss").take(12),
                name = name,
                dark = dark,
                bg = bg,
                surface = surface,
                surface2 = surface2,
                fg = fg,
                ink = ink,
                accent = accent,
                remapSurfaces = true,
                customCss = sanitizeGeneratedCss(rawCss),
                backdropHtml = "",
                hudHtml = "",
                customJs = "",
                concept = concept,
                needsHud = false,
                hudReason = "",
                fingerprint = j.optString("fingerprint", "").take(120),
                designSpec = j.optString("designSpec", "")
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
        byteArrayOf(8, 8, 8, 8),
        byteArrayOf(1, 1, 1, 1),
        byteArrayOf(8, 8, 4, 4),
        byteArrayOf(9, 9, 9, 9)
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

// ───────────────────── Zero-Lag 60fps Universal Runtime v19 ─────────────────────

object MydesRuntime {

    private const val CORE = """
if(window.__mydes && window.__mydes.id()===BP.id) return;
if(window.__mydes && window.__mydes.clear){try{window.__mydes.clear();}catch(e){}}
if(BP.skip && BP.skip.indexOf(location.hostname)>=0) return;

var cfg = BP;
var cleanupFns=[], mo=null, retagRaf=0;

window.MydesBridge = {
  decorateUnits: function() {},
  injectOrnament: function() {},
  spawnParticles: function() {},
  enable3DTilt: function() {},
  onCleanup: function(fn) { if(typeof fn === 'function') cleanupFns.push(fn); }
};

/* ---------- 1. Lightweight 60fps Stylesheet Engine ---------- */
var eStyle = null;
function applySheets() {
  var rootVars = ':root{' +
    '--m-bg:'+cfg.bg+';--bg:'+cfg.bg+';' +
    '--m-surface:'+cfg.surface+';--surface:'+cfg.surface+';--m-primary:'+cfg.surface+';' +
    '--m-surface2:'+cfg.surface2+';--surface2:'+cfg.surface2+';' +
    '--m-fg:'+cfg.fg+';--fg:'+cfg.fg+';--m-text:'+cfg.fg+';' +
    '--m-ink:'+cfg.ink+';--ink:'+cfg.ink+';' +
    '--m-accent:'+cfg.accent+';--accent:'+cfg.accent+';' +
    '--m-border:color-mix(in srgb, '+cfg.accent+' 50%, '+cfg.surface2+');--border:var(--m-border);' +
    '}\n';

  var universalBaseCss =
    'html, body { background-color: var(--m-bg) !important; color: var(--m-fg) !important; }\n' +
    'main, [role="main"], [data-mydes="main"], [data-mydes="grid"], lazy-list, ytm-rich-grid-renderer, ytm-section-list-renderer, ytm-item-section-renderer, ytm-single-column-browse-results-renderer, #page-manager, #content { background-color: transparent !important; }\n' +
    '[data-mydes="header"], header, .mobile-topbar-header { background-color: var(--m-surface); border-bottom: 2px solid var(--m-accent); }\n' +
    '[data-mydes="search"], yt-searchbox, input[type="search"], input[name="q"], input[name="k"] { background-color: var(--m-surface2); color: var(--m-fg); border: 1px solid var(--m-accent); border-radius: 18px; }\n' +
    '[data-mydes="chip"] { background-color: var(--m-surface2); color: var(--m-ink); border: 1px solid var(--m-border); border-radius: 12px; }\n' +
    '[data-mydes="card"] { background-color: var(--m-surface); border: 1.5px solid var(--m-border); border-radius: 14px; margin-bottom: 12px !important; padding: 10px !important; box-sizing: border-box !important; color: var(--m-fg); position: relative; }\n' +
    '[data-mydes="heading"], [data-mydes="title"], [data-mydes="card"] h1, [data-mydes="card"] h2, [data-mydes="card"] h3, [data-mydes="card"] h4, [class*="media-item-headline"], [class*="title"] { color: var(--m-ink); }\n' +
    '[data-mydes="heading"] *, [data-mydes="title"] *, [class*="media-item-headline"] * { font-family: inherit !important; color: inherit !important; letter-spacing: inherit !important; text-shadow: inherit !important; text-transform: inherit !important; }\n' +
    '[data-mydes="bottom-bar"], ytm-pivot-bar-renderer, footer { background-color: var(--m-surface); border-top: 2px solid var(--m-accent); color: var(--m-fg); }\n';

  var safetyResetCss =
    '\n/* --- Pure-CSS Safety Resets --- */\n' +
    'html body a:has(img, video, picture, h1, h2, h3, h4, div), html body [class*="thumbnail"] a, html body [class*="media"] a { border: none !important; padding: 0 !important; background: transparent !important; box-shadow: none !important; }\n' +
    'html body button:has(svg, img), html body [role="button"]:has(svg, img), html body [aria-label*="menu" i], html body [aria-label*="More" i], html body [aria-label*="Action" i] { background: transparent !important; border: none !important; box-shadow: none !important; }\n';

  var fullCss = rootVars + universalBaseCss + '\n' + (cfg.customCss || '') + safetyResetCss;
  var root = document.head || document.documentElement;
  if(!root) return;
  if(!eStyle || !eStyle.isConnected) {
    eStyle = document.createElement('style');
    eStyle.id = '__mydes_style';
    root.appendChild(eStyle);
  }
  if(eStyle.textContent !== fullCss) eStyle.textContent = fullCss;

  // Automatic Google Fonts loader
  var fontRegex = /(?:font-family|--[a-zA-Z0-9_-]*font[a-zA-Z0-9_-]*)\s*:\s*['"]?([^;'"!,}]+)['"]?/gi, match;
  while((match = fontRegex.exec(cfg.customCss || '')) !== null) {
    var fam = match[1].trim();
    if(fam && !/^(serif|sans-serif|monospace|cursive|fantasy|system-ui|inherit|initial|unset|var\(|Georgia|Arial|Verdana|Tahoma|Courier|Times)$/i.test(fam)) {
      var id = '__mydes_font_' + fam.replace(/\W+/g, '_');
      if(!document.getElementById(id) && document.head) {
        var link = document.createElement('link');
        link.id = id;
        link.rel = 'stylesheet';
        link.href = 'https://fonts.googleapis.com/css2?family=' + encodeURIComponent(fam).replace(/%20/g, '+') + ':wght@400;500;600;700&display=swap';
        document.head.appendChild(link);
      }
    }
  }
}

/* ---------- 2. Universal Semantic & Geometric Role Tagger ---------- */
function each(sel,fn){var l=document.querySelectorAll(sel);for(var i=0;i<l.length;i++)fn(l[i]);}
function put(e,r){if(e.getAttribute('data-mydes')!==r)e.setAttribute('data-mydes',r);}
function putNew(e,r){if(!e.hasAttribute('data-mydes'))e.setAttribute('data-mydes',r);}
var PRICE=/^\s*(?:(?:[\u0024\u20AC\u00A3\u00A5\u20B9]|USD|EUR|GBP|INR|Rs\.?)\s?\d[\d,.]*|\d[\d,.]*\s?(?:[\u0024\u20AC\u00A3\u00A5\u20B9]|USD|EUR|GBP|INR))\s*$/;
function inContent(e){return !!e.closest('main,article,section,[role="main"]');}

function cardLike(e){
  if(e.closest('nav,footer,header,[data-mydes="nav"],[data-mydes="footer"],[data-mydes="header"],[data-mydes="bottom-bar"]'))return false;
  var tag = e.tagName.toLowerCase();
  if(/continuation|spinner|skeleton|loader|sentinel/.test(tag + ' ' + (e.className||''))) return false;
  var link = e.tagName==='A' || !!e.querySelector('a[href]');
  var hasMedia = !!e.querySelector('img,picture,video,yt-thumbnail-view-model,ytm-thumbnail-overlay-time-status-renderer');
  var t = (e.textContent||'').trim().length;
  return (link || hasMedia) && (hasMedia || t >= 20);
}

function decorate(c){
  if(c.querySelector('h1,h2,h3,h4,h5,h6,[data-mydes="heading"]'))return;
  var l=c.querySelectorAll('[class*="headline"],[class*="title"],a,span,div,p,strong,b');
  for(var i=0;i<l.length;i++){
    var x=l[i];
    if(x.children.length<=1){
      var t=(x.textContent||'').trim();
      if(t.length>=8&&t.length<=160&&!PRICE.test(t)){x.setAttribute('data-mydes','heading');return;}
    }
  }
}

function retag(){
  if(!document.body)return;
  var vw = window.innerWidth || 400;

  // 1. Header, Bottom Bar, Search, Chips
  each('header,[role="banner"],#navbar,#nav-main,#navbar-main,.mobile-topbar-header',function(e){if(!inContent(e))put(e,'header');});
  each('footer,[role="contentinfo"],#navFooter,ytm-pivot-bar-renderer',function(e){
    put(e,'bottom-bar');
  });
  each('yt-searchbox,form[role="search"],form[name="site-search"],input[type="search"],input[name="q"],input[name="k"]',function(e){
    if(e.parentElement && e.parentElement.closest('[data-mydes="search"]')) return;
    put(e,'search');
  });
  each('ytm-chip-cloud-chip-renderer,[role="tab"],[class*="chip-cloud-chip"]',function(e){
    if(e.closest('ytm-pivot-bar-renderer,footer,[data-mydes="bottom-bar"],header,[data-mydes="header"]')) {
      if(e.getAttribute('data-mydes')==='chip') e.removeAttribute('data-mydes');
      return;
    }
    if(e.parentElement && e.parentElement.closest('[data-mydes="chip"]')) {
      if(e.getAttribute('data-mydes')==='chip') e.removeAttribute('data-mydes');
      return;
    }
    put(e,'chip');
  });

  // 2. Universal & Platform Card Archetypes
  var cardSel = [
    '[data-component-type="s-search-result"]',
    '[data-asin]:not([data-asin=""])',
    '.s-result-item',
    '.a-cardui',
    'article',
    'ytm-video-with-context-renderer',
    'ytm-rich-item-renderer',
    'ytm-compact-video-renderer',
    'ytm-shorts-lockup-view-model',
    'ytm-shorts-lockup-view-model-v2',
    'ytm-playlist-video-renderer'
  ].join(',');

  each(cardSel, function(e){
    if(e.parentElement && e.parentElement.closest('[data-mydes="card"]')) {
      e.removeAttribute('data-mydes');
      return;
    }
    if(!e.closest('nav,header,footer,[data-mydes="header"],[data-mydes="bottom-bar"]') && cardLike(e)){
      put(e,'card');
      decorate(e);
    }
  });

  // 3. Geometric Repeating Feed Detector
  var containers = document.querySelectorAll('main, [role="main"], section, ul, ol, lazy-list, [id*="feed" i], [class*="feed" i], [class*="list" i], [class*="results" i], [class*="grid" i]');
  for(var c=0; c<containers.length && c<80; c++) {
    var parent = containers[c];
    if(parent.closest('header, nav, footer, [data-mydes="header"], [data-mydes="bottom-bar"]')) continue;
    var kids = parent.children;
    if(!kids || kids.length < 3) continue;
    var sigCounts = {};
    for(var k=0; k<kids.length && k<50; k++) {
      var ch = kids[k];
      var sig = ch.tagName + '|' + (ch.classList[0] || '');
      sigCounts[sig] = (sigCounts[sig] || 0) + 1;
    }
    for(var k2=0; k2<kids.length && k2<50; k2++) {
      var item = kids[k2];
      var itemSig = item.tagName + '|' + (item.classList[0] || '');
      if(sigCounts[itemSig] >= 3 && !item.hasAttribute('data-mydes') && !item.closest('[data-mydes="card"]')) {
        var rect = item.getBoundingClientRect();
        if(rect.width >= vw * 0.42 && rect.height >= 48 && cardLike(item)) {
          put(item, 'card');
          decorate(item);
        }
      }
    }
  }

  each('h1',function(e){putNew(e,'title');});
  each('h2,h3,h4,[class*="media-item-headline"]',function(e){
    // Only tag headings that are NOT inside channel sub-metadata
    if(e.closest('[class*="byline"],[class*="channel-name"],[class*="metadata-row"]')) return;
    putNew(e,'heading');
  });
  each('button,input[type="submit"],input[type="button"],[role="button"],a[class*="btn"],a[class*="button"],.a-button',function(e){
    if(e.closest('ytm-pivot-bar-renderer,footer,[data-mydes="bottom-bar"],[data-mydes="chip"]')) return;
    if(e.querySelector('img, video, picture, svg, h1, h2, h3, h4')) return;
    putNew(e,'btn');
  });
}

/* ---------- 3. Page Summary ---------- */
function summary(){
  try{retag();}catch(e){}
  var counts={};
  each('[data-mydes]',function(e){
    var r=e.getAttribute('data-mydes');
    counts[r]=(counts[r]||0)+1;
  });
  return JSON.stringify({
    url:location.href,
    host:location.hostname,
    title:(document.title||'').slice(0,80),
    viewport:{w:innerWidth,h:innerHeight},
    roleCounts:counts
  });
}

/* ---------- 4. Continuous SPA & Scroll Observer ---------- */
function observe(){
  try{
    if(mo) mo.disconnect();
    mo = new MutationObserver(function(){
      if(retagRaf) return;
      retagRaf = requestAnimationFrame(function(){
        retagRaf = 0;
        if(!document.getElementById('__mydes_style')) applySheets();
        retag();
      });
    });
    mo.observe(document.documentElement, {childList: true, subtree: true});
    window.addEventListener('popstate', retag, {passive: true});
    window.addEventListener('yt-navigate-finish', retag, {passive: true});
  } catch(e){}
}

function start(){
  applySheets();
  var go=function(){
    if(!document.body)return;
    retag();
    observe();
  };
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',go);else go();
}

window.__mydes={
  id:function(){return cfg.id;},
  setBlueprint:function(nextBp){
    cfg=nextBp;
    start();
  },
  outline:summary,
  exit:function(done){
    if(mo)mo.disconnect();
    var exitStyle = document.createElement('style');
    exitStyle.id = '__mydes_exit_anim';
    exitStyle.textContent = '* { transition: opacity 0.4s cubic-bezier(0.4, 0, 0.2, 1), transform 0.4s cubic-bezier(0.4, 0, 0.2, 1) !important; } [data-mydes], header, footer { opacity: 0.25 !important; transform: scale(0.98) translateY(4px) !important; }';
    (document.head || document.documentElement).appendChild(exitStyle);
    setTimeout(function() {
      var s = document.getElementById('__mydes_style'); if(s) s.remove();
      var sOld = document.getElementById('mydes-ai-styler-style'); if(sOld) sOld.remove();
      exitStyle.remove();
      document.querySelectorAll('[data-mydes]').forEach(function(el) {
        el.removeAttribute('data-mydes');
        el.style.opacity = '';
        el.style.transform = '';
      });
      document.querySelectorAll('link[id^="__mydes_font_"]').forEach(function(l) { l.remove(); });
      if(typeof done === 'function') done();
    }, 400);
  },
  clear:function(){
    if(mo)mo.disconnect();
    var s=document.getElementById('__mydes_style');if(s)s.remove();
    var sOld=document.getElementById('mydes-ai-styler-style');if(sOld)sOld.remove();
    var exitS=document.getElementById('__mydes_exit_anim');if(exitS)exitS.remove();
    document.querySelectorAll('[data-mydes]').forEach(function(el){
      el.removeAttribute('data-mydes');
      el.style.opacity = '';
      el.style.transform = '';
    });
    document.querySelectorAll('link[id^="__mydes_font_"]').forEach(function(l){l.remove();});
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
          var vw = innerWidth, vh = innerHeight;
          var bodyStyles = getComputedStyle(document.body || document.documentElement);
          return JSON.stringify({
            url: location.href,
            host: location.hostname,
            viewport: { w: vw, h: vh },
            baseline: {
              backgroundColor: bodyStyles.backgroundColor,
              textColor: bodyStyles.color,
              fontFamily: (bodyStyles.fontFamily || '').split(',')[0].replace(/['"]/g, '').trim()
            },
            semanticTargets: [
              "html, body",
              "[data-mydes=\"header\"], header",
              "[data-mydes=\"search\"], yt-searchbox",
              "[data-mydes=\"chip\"]",
              "[data-mydes=\"card\"]",
              "[data-mydes=\"card\"] img",
              "[data-mydes=\"heading\"], [data-mydes=\"title\"]",
              "[data-mydes=\"btn\"]",
              "[data-mydes=\"bottom-bar\"]"
            ]
          });
        })();
    """

    /**
     * Lightweight Computed-DOM Telemetry Probe (~600 tokens):
     * Inspects `clashingSurfaces`, `lowContrastText`, and `squishedColumns` on the painted page.
     */
    const val VISUAL_AND_DOM_AUDIT_JS = """
        (function(){
          try {
            var vw = document.documentElement.clientWidth || innerWidth;
            var vh = document.documentElement.clientHeight || innerHeight;

            function parseRgba(c) {
              var m = /rgba?\(([^)]+)\)/.exec(c || '');
              if(!m) return null;
              var p = m[1].split(/[ ,\/]+/).filter(Boolean).map(parseFloat);
              return { r: p[0], g: p[1], b: p[2], a: p.length > 3 ? p[3] : 1 };
            }
            function lum(c) {
              function f(v){ v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
              return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
            }
            function contrastRatio(a, b) {
              var x = lum(a), y = lum(b);
              return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
            }
            function toHex(c) {
              function h(v){ v = Math.max(0, Math.min(255, Math.round(v))); return (v < 16 ? '0' : '') + v.toString(16); }
              return '#' + h(c.r) + h(c.g) + h(c.b);
            }
            function cleanSel(el) {
              if(!el || el.nodeType !== 1) return '';
              var s = el.tagName.toLowerCase();
              var md = el.getAttribute('data-mydes');
              if(md) return s + '[data-mydes="' + md + '"]';
              if(el.id && !/\d{4,}/.test(el.id)) return s + '#' + CSS.escape(el.id);
              var cls = Array.from(el.classList || []).filter(function(c){ return c && !/\d{4,}/.test(c); }).slice(0, 2);
              return cls.length ? s + '.' + cls.map(function(c){ return CSS.escape(c); }).join('.') : s;
            }

            var rootBg = parseRgba(getComputedStyle(document.documentElement).backgroundColor) || {r:10,g:10,b:15,a:1};
            var isDarkTheme = lum(rootBg) < 0.35;

            function effectiveBg(el) {
              for(var n = el; n && n.nodeType === 1; n = n.parentElement) {
                var cs = getComputedStyle(n);
                var c = parseRgba(cs.backgroundColor);
                if(c && c.a >= 0.5) return c;
              }
              return rootBg;
            }

            var roleCounts = {};
            var roles = ['header','search','chip','card','heading','title','btn','bottom-bar'];
            roles.forEach(function(r){ roleCounts[r] = document.querySelectorAll('[data-mydes="' + r + '"]').length; });

            var clashingSurfaces = [];
            var clashingSeen = {};
            var allEls = document.body ? document.body.querySelectorAll('*') : [];
            for(var i = 0; i < allEls.length && clashingSurfaces.length < 10; i++) {
              var el = allEls[i];
              if(el.tagName === 'IMG' || el.tagName === 'VIDEO' || el.tagName === 'SVG') continue;
              var r = el.getBoundingClientRect();
              if(r.width < 100 || r.height < 28 || r.bottom < 0 || r.top > vh * 1.8) continue;
              var cs = getComputedStyle(el);
              if(cs.display === 'none' || cs.visibility === 'hidden' || parseFloat(cs.opacity) < 0.2) continue;
              var bg = parseRgba(cs.backgroundColor);
              if(bg && bg.a >= 0.65) {
                var bgLum = lum(bg);
                if((isDarkTheme && bgLum > 0.65) || (!isDarkTheme && bgLum < 0.08)) {
                  var s = cleanSel(el);
                  if(s && !clashingSeen[s]) {
                    clashingSeen[s] = true;
                    clashingSurfaces.push({
                      sel: s,
                      bg: toHex(bg),
                      w: Math.round(r.width),
                      h: Math.round(r.height)
                    });
                  }
                }
              }
            }

            var lowContrastText = [];
            var squishedColumns = [];
            var textSeen = {};
            var tw = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null);
            var node, guard = 0;
            while((node = tw.nextNode()) && guard++ < 1500 && lowContrastText.length < 8) {
              if(!/\S/.test(node.nodeValue)) continue;
              var p = node.parentElement;
              if(!p || p.closest('script,style,noscript')) continue;
              var pr = p.getBoundingClientRect();
              if(pr.width < 6 || pr.height < 6 || pr.bottom < 0 || pr.top > vh * 1.8) continue;
              if(pr.width < 75 && pr.height > pr.width * 2 && node.nodeValue.trim().length > 12 && squishedColumns.length < 5) {
                squishedColumns.push({ sel: cleanSel(p), w: Math.round(pr.width), h: Math.round(pr.height) });
              }
              var pcs = getComputedStyle(p);
              var fg = parseRgba(pcs.color);
              if(!fg || fg.a < 0.2) continue;
              var ebg = effectiveBg(p);
              var ratio = contrastRatio(fg, ebg);
              if(ratio < 3.2) {
                var ts = cleanSel(p);
                if(ts && !textSeen[ts]) {
                  textSeen[ts] = true;
                  lowContrastText.push({
                    sel: ts,
                    fg: toHex(fg),
                    bg: toHex(ebg),
                    ratio: Math.round(ratio * 100) / 100,
                    sample: node.nodeValue.trim().slice(0, 24)
                  });
                }
              }
            }

            return JSON.stringify({
              overflowX: document.documentElement.scrollWidth - vw,
              roleCounts: roleCounts,
              clashingSurfaces: clashingSurfaces,
              lowContrastText: lowContrastText,
              squishedColumns: squishedColumns
            });
          } catch(e) {
            return JSON.stringify({ error: String(e) });
          }
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
    private val prefs = context.applicationContext.getSharedPreferences("mydes_blueprints_v19", Context.MODE_PRIVATE)
    private fun key(prompt: String, domain: String) =
        "bp19|" + sha1Hex("${domain.trim().lowercase(Locale.US)}|${prompt.trim().lowercase(Locale.US)}")
    private fun lastPromptKey(prompt: String) =
        "bp19_last|" + sha1Hex(prompt.trim().lowercase(Locale.US))

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

// ───────────────────── The Gemini UI Synthesis Service (v19) ─────────────────────

class GeminiStylerService {

    companion object {
        const val MODEL_GEMINI_35_FLASH_LITE = "gemini-3.5-flash-lite"
        const val MODEL_GEMINI_38_FLASH = "gemini-3.8-flash"
        const val MODEL_NVIDIA_NEMOTRON_3_ULTRA = "nvidia/nemotron-3-ultra-550b-a55b"

        const val CONCEPT_MODEL = MODEL_GEMINI_35_FLASH_LITE
        const val SYNTHESIS_MODEL = MODEL_GEMINI_35_FLASH_LITE
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

        fun getReasoningLevel(context: Context, model: String): String {
            val prefs = context.getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
            val default = ReasoningModulatorConfig.getDefaultModeForModel(model)
            return prefs.getString("reasoning_level_$model", default) ?: default
        }

        fun setReasoningLevel(context: Context, model: String, level: String) {
            val prefs = context.getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("reasoning_level_$model", level.lowercase().trim()).apply()
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
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null,
        onConceptReady: ((bg: String, fg: String, concept: String) -> Unit)? = null
    ): Result<String> = synthesizeBlueprint(
        context = context,
        prompt = userPrompt,
        websiteName = websiteName,
        websiteUrl = websiteUrl,
        pageOutlineJson = outlineJson?.takeIf { it.isNotBlank() && it != "null" },
        screenshotBase64 = initialScreenshotBase64,
        forceFresh = forceFresh,
        isReviewPass = false,
        onThinkingUpdate = onThinkingUpdate,
        onConceptReady = onConceptReady
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
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null,
        screenshotPath: String? = null,
        onConceptReady: ((bg: String, fg: String, concept: String) -> Unit)? = null
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
        onThinkingUpdate = onThinkingUpdate,
        screenshotPath = screenshotPath,
        onConceptReady = onConceptReady
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
        auditJson: String? = null,
        isReviewPass: Boolean = false,
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null,
        screenshotPath: String? = null,
        onConceptReady: ((bg: String, fg: String, concept: String) -> Unit)? = null
    ): Result<UIBlueprint> = synthesizeBlueprint(
        context = context,
        prompt = prompt,
        websiteName = websiteUrl,
        websiteUrl = websiteUrl,
        pageOutlineJson = pageOutlineJson,
        screenshotBase64 = screenshotBase64,
        forceFresh = forceFresh,
        auditJson = auditJson,
        isReviewPass = isReviewPass || (screenshotBase64 != null && !forceFresh),
        onThinkingUpdate = onThinkingUpdate,
        screenshotPath = screenshotPath,
        onConceptReady = onConceptReady
    )

    /**
     * Iteratively modifies the currently active design.
     * The currently active CSS design is injected into the system prompt so the model
     * understands the exact existing styling and applies the requested modifications.
     */
    suspend fun modifyUserScript(
        context: Context,
        userModificationPrompt: String,
        activeCss: String,
        activeConcept: String,
        websiteName: String,
        websiteUrl: String,
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null,
        onConceptReady: ((bg: String, fg: String, concept: String) -> Unit)? = null
    ): Result<UIBlueprint> = withContext(Dispatchers.IO) {
        val cleanPrompt = userModificationPrompt.trim()
        if (cleanPrompt.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Empty prompt"))

        val geminiApiKey = getApiKey(context)
        val nvidiaApiKey = getNvidiaApiKey(context)

        val synthesisModel = getSynthesisModel(context)
        val isNemotronSynthesis = isNvidiaModel(synthesisModel)
        val activeModel = synthesisModel

        if (isNemotronSynthesis && nvidiaApiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("NVIDIA NIM API key is not set. Please enter your NVIDIA API key in Settings."))
        }
        if (!isNemotronSynthesis && geminiApiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GEMINI_API_KEY is not set. Please set your Gemini API key in Settings."))
        }

        // Pass 1: Quick Concept Direction for the modification
        onThinkingUpdate?.invoke("Pass 1: Visual Architecture ($CONCEPT_MODEL)", "Directing modification concept...", false)
        val conceptInput = buildString {
            appendLine("USER MODIFICATION REQUEST: \"$cleanPrompt\"")
            appendLine("ACTIVE DESIGN CONCEPT: \"$activeConcept\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            appendLine("Specify the modification concept and color tweaks in JSON.")
        }

        val conceptSystem = """
            You are the Master Visual Architect modifying an existing website theme.
            Given the user's requested modification and the current design concept, specify:
            1. "concept": What modification is being built in one concise phrase (e.g. "Neon pink button accents with glowing borders")
            2. "bg": background color hex
            3. "fg": readable foreground text hex
            Output valid JSON only:
            {
              "concept": "concise phrase describing the modification being built",
              "bg": "#rrggbb",
              "fg": "#rrggbb"
            }
        """.trimIndent()

        var conceptText = cleanPrompt
        var bg = "#10141D"
        var fg = "#E6EDF3"

        if (geminiApiKey.isNotBlank()) {
            try {
                val rawConcept = callForText(
                    CONCEPT_MODEL,
                    geminiApiKey,
                    conceptSystem,
                    listOf(Part(text = conceptInput)),
                    0.8f,
                    0.9f,
                    512,
                    onThoughtChunk = { chunk ->
                        onThinkingUpdate?.invoke("Pass 1: Visual Architecture", chunk, false)
                    }
                )
                val json = JSONObject(extractJsonObject(rawConcept) ?: "{}")
                conceptText = (json.optString("concept", "").ifBlank { json.optString("idea", "") }).ifBlank { cleanPrompt }.take(200)
                val rawBg = json.optString("bg", "#10141D")
                val rawFg = json.optString("fg", "#E6EDF3")
                bg = Hex.norm(rawBg, "#10141D")
                fg = Hex.norm(rawFg, "#E6EDF3")
                if (Hex.contrast(fg, bg) < 4.0) {
                    fg = if (Hex.lum(bg) < 0.4) "#f8fafc" else "#0f172a"
                }
            } catch (_: Exception) {
                conceptText = cleanPrompt
            }
        }

        onConceptReady?.invoke(bg, fg, conceptText)

        // Pass 2: Consequent CSS Modification Pass
        // Injects currently active CSS in system prompt per requirement
        val modelLabel = if (isNemotronSynthesis) "Nemotron 3 Ultra 550B (NVIDIA NIM)" else activeModel
        onThinkingUpdate?.invoke("Pass 2: 60fps CSS Modification ($modelLabel)", "Compiling modified CSS...", false)

        val modificationSystemPrompt = """
            You are an expert Pure-CSS Compiler modifying an active website theme.
            The user wants to iteratively tweak, mold, and update the active design.
            
            === CURRENTLY ACTIVE CSS DESIGN ===
            $activeCss
            
            === TASK ===
            Incorporate the user's requested modifications on top of the active CSS design.
            Keep what already works in the active design, and update or add the CSS rules requested by the user.
            
            CRITICAL PERFORMANCE RULES:
            - Output ONLY one valid JSON object.
            - 100% static and GPU-friendly: NO moving particle overlays, NO full-viewport looping animations, NO background-attachment: fixed.
            - Return valid JSON:
            {
              "name": "${conceptText.take(40)}",
              "dark": true,
              "bg": "$bg",
              "surface": "${Hex.mix(bg, "#ffffff", 0.08)}",
              "surface2": "${Hex.mix(bg, "#ffffff", 0.15)}",
              "fg": "$fg",
              "ink": "$fg",
              "accent": "#00ff88",
              "concept": "$conceptText",
              "customCss": "/* Complete modified CSS incorporating the changes cleanly on top of the active CSS */"
            }
        """.trimIndent()

        val userContext = buildString {
            appendLine("MODIFICATION REQUEST: \"$cleanPrompt\"")
            appendLine("MODIFICATION CONCEPT: \"$conceptText\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            appendLine("Compile the complete updated CSS with the requested modifications applied on the active design.")
        }

        val (raw, rawThoughts) = if (isNemotronSynthesis) {
            callNvidiaNimStream(
                model = activeModel,
                apiKey = nvidiaApiKey,
                systemInstruction = modificationSystemPrompt,
                userContent = userContext,
                temperature = 0.5f,
                topP = 0.95f,
                maxTokens = NIM_MAX_TOKENS,
                reasoningLevel = getReasoningLevel(context, activeModel),
                onThoughtChunk = { chunk ->
                    onThinkingUpdate?.invoke("Pass 2: CSS Modification", chunk, false)
                },
                onOutputChunk = { _ ->
                    onThinkingUpdate?.invoke("Pass 2: CSS Modification ($activeModel)", "", false)
                }
            )
        } else {
            val req = GeminiRequest(
                contents = listOf(Content(parts = listOf(Part(text = userContext)))),
                systemInstruction = Content(listOf(Part(text = modificationSystemPrompt))),
                generationConfig = GenerationConfig(
                    temperature = 0.7f,
                    topP = 0.95f,
                    maxOutputTokens = 8192,
                    responseMimeType = "application/json",
                    thinkingConfig = ThinkingConfig(thinkingBudget = 2048)
                )
            )
            callGeminiStream(
                activeModel,
                req,
                geminiApiKey,
                onThoughtChunk = { chunk ->
                    onThinkingUpdate?.invoke("Pass 2: CSS Modification", chunk, false)
                }
            )
        }

        onThinkingUpdate?.invoke("Theme Modified & Applied", "", true)

        val parsed = UIBlueprint.parse(raw, cleanPrompt)
            ?: return@withContext Result.failure(IllegalStateException("Failed to parse modified blueprint JSON: ${raw.take(200)}"))

        val finalizedBlueprint = parsed.copy(
            concept = conceptText,
            bg = bg,
            fg = fg
        )

        val domain = extractSafeDomain(websiteUrl, websiteName)
        storeFor(context).put(cleanPrompt, finalizedBlueprint, domain, remember = true)

        ApiRequestLogger.logRequest(
            context = context,
            type = if (isNemotronSynthesis) "modification (nemotron)" else "modification (gemini)",
            websiteName = websiteName,
            websiteUrl = websiteUrl,
            userPrompt = cleanPrompt,
            requestPayload = "=== SYSTEM INSTRUCTION ===\n$modificationSystemPrompt\n\n=== USER CONTENT ===\n$userContext",
            responseResult = raw,
            isSuccess = true
        )

        Result.success(finalizedBlueprint)
    }

    // ───── Pass 1: Static Visual Architect (Fast, Token-Efficient, Zero-Lag Design) ─────

    private class ConceptBrief(
        val raw: String,
        val idea: String,
        val fingerprint: String,
        val bg: String,
        val fg: String
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
        onThinkingUpdate?.invoke("Pass 1: Visual Architecture ($CONCEPT_MODEL)", "Designing static artistic UI surfaces, trims, and typography...", false)
        val input = buildString {
            appendLine("USER REQUEST: \"$prompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            val recent = storeFor(context).recentConcepts()
            if (recent.isNotEmpty()) {
                appendLine("RECENT THEMES SHIPPED (create a completely distinct visual design):")
                recent.forEach { appendLine("- $it") }
            }
            appendLine("Design the static element-by-element visual specification JSON now.")
        }
        val logInput = buildString {
            appendLine("=== TARGET MODEL ===")
            appendLine(CONCEPT_MODEL)
            appendLine()
            appendLine("=== PASS TYPE ===")
            appendLine("Pass 1: Static Artistic Visual Specification")
            appendLine()
            appendLine("=== SYSTEM INSTRUCTION ===")
            appendLine(UI_CONCEPT_SYSTEM_PROMPT)
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
                1536,
                onThoughtChunk = { chunk ->
                    onThinkingUpdate?.invoke("Pass 1: Visual Architecture", chunk, false)
                }
            )
            val json = JSONObject(extractJsonObject(raw) ?: throw IllegalStateException("no JSON in brief: ${raw.take(200)}"))
            val idea = json.optString("idea", "").trim()
            if (idea.isBlank()) throw IllegalStateException("brief has no idea: ${raw.take(200)}")

            val bgRaw = when {
                json.has("bg") && json.optString("bg").isNotBlank() -> json.optString("bg")
                json.has("background") && json.optString("background").isNotBlank() -> json.optString("background")
                else -> Hex.extractFirstHex(json.optString("palette", ""), "#0a0c0e")
            }
            val fgRaw = when {
                json.has("fg") && json.optString("fg").isNotBlank() -> json.optString("fg")
                json.has("foreground") && json.optString("foreground").isNotBlank() -> json.optString("foreground")
                else -> Hex.extractSecondHex(json.optString("palette", ""), "#e2e8f0")
            }
            val bg = Hex.norm(bgRaw, "#0a0c0e")
            var fg = Hex.norm(fgRaw, "#e2e8f0")
            if (Hex.contrast(fg, bg) < 4.0) {
                fg = if (Hex.lum(bg) < 0.4) "#f8fafc" else "#0f172a"
            }

            val directions = listOf(
                "idea" to "1. OVERALL VISUAL CONCEPT",
                "palette" to "2. EXACT COLOR PALETTE & HARMONY ACCENTS",
                "typographyAndSymbol" to "3. TYPOGRAPHY & 4-DIGIT BMP UNICODE HEX SYMBOL",
                "backdropCanvas" to "4. BASE PAGE CANVAS (html, body)",
                "headerBar" to "5. TOP HEADER BAR & ICON DESIGN ([data-mydes=\"header\"])",
                "searchBox" to "6. SEARCH BOX & FOCUS STATE ([data-mydes=\"search\"])",
                "filterChips" to "7. FILTER CHIPS, COLOR CYCLING & SELECTED STATE ([data-mydes=\"chip\"])",
                "cardsAndCorners" to "8. CARDS, SURFACE TEXTURE, CORNER ORNAMENTS & COLOR CYCLING ([data-mydes=\"card\"])",
                "thumbnailsAndButtons" to "9. THUMBNAIL IMAGE GRADING & ACTION BUTTONS ([data-mydes=\"card\"] img, [data-mydes=\"btn\"])",
                "bottomBar" to "10. BOTTOM NAVIGATION BAR ([data-mydes=\"bottom-bar\"])"
            ).mapNotNull { (k, label) -> json.optString(k, "").trim().takeIf { it.isNotBlank() }?.let { "$label:\n$it" } }
            .joinToString("\n\n")

            val brief = ConceptBrief(
                raw = directions,
                idea = idea.take(300),
                fingerprint = json.optString("fingerprint", "").take(120),
                bg = bg,
                fg = fg
            )
            ApiRequestLogger.logRequest(context, "conceptBrief ($CONCEPT_MODEL)", websiteName, websiteUrl, prompt, logInput, json.toString(2), true)
            Result.success(brief)
        } catch (e: Exception) {
            val errMsg = describe(e)
            ApiRequestLogger.logRequest(context, "conceptBrief ($CONCEPT_MODEL)", websiteName, websiteUrl, prompt, logInput, errMsg, false)
            Result.failure(Exception("Concept pass failed ($CONCEPT_MODEL): $errMsg", e))
        }
    }

    // ───── Pass 2 (60fps Pure CSS Compiler) & Pass 3 (Telemetry Reviewer) ─────

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
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null,
        screenshotPath: String? = null,
        onConceptReady: ((bg: String, fg: String, concept: String) -> Unit)? = null
    ): Result<UIBlueprint> = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Empty prompt"))

        val domain = extractSafeDomain(websiteUrl, websiteName)

        if (!isReviewPass && screenshotBase64 == null && auditJson == null && !forceFresh) {
            storeFor(context).get(cleanPrompt, domain)?.let {
                onConceptReady?.invoke(it.bg, it.fg, it.concept.ifBlank { it.name })
                return@withContext Result.success(it)
            }
        }

        val geminiApiKey = getApiKey(context)
        val nvidiaApiKey = getNvidiaApiKey(context)

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

        val synthesisModel = getSynthesisModel(context)
        val isNemotronSynthesis = !isReview && isNvidiaModel(synthesisModel)
        val activeModel = if (isReview) REVIEW_MODEL else synthesisModel

        if (isNemotronSynthesis && nvidiaApiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("NVIDIA NIM API key is not set. Please enter your NVIDIA API key (nvapi-...) in Settings."))
        }

        val brief: ConceptBrief? = if (isReview) {
            null
        } else {
            designBrief(context, cleanPrompt, websiteName, websiteUrl, pageOutlineJson, geminiApiKey, onThinkingUpdate)
                .getOrElse { conceptError -> return@withContext Result.failure(conceptError) }
        }

        if (brief != null) {
            onConceptReady?.invoke(brief.bg, brief.fg, brief.idea)
        }

        if (!isReview) {
            val modelLabel = if (isNemotronSynthesis) "Nemotron 3 Ultra 550B (NVIDIA NIM)" else activeModel
            onThinkingUpdate?.invoke("Pass 2: 60fps Pure CSS Compilation ($modelLabel)", "Compiling lightweight, artistic Pure CSS...", false)
        } else {
            onThinkingUpdate?.invoke("Pass 3: Visual & Telemetry QA ($activeModel)", "Checking live surface contrast and compatibility...", false)
        }

        val userContextText = if (isReview) buildString {
            appendLine("REVIEW & SELF-HEALING PASS")
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            appendLine("THEME PALETTE: dark=${previous!!.dark}, bg=${previous.bg}, surface=${previous.surface}, surface2=${previous.surface2}, fg=${previous.fg}, ink=${previous.ink}, accent=${previous.accent}")
            if (previous.designSpec.isNotBlank()) {
                appendLine()
                appendLine("ORIGINAL PASS 1 VISUAL SPECIFICATION:")
                appendLine(previous.designSpec.take(3_000))
            }
            appendLine()
            appendLine("LIVE COMPUTED DOM TELEMETRY (Ground-truth measurements from the painted WebView):")
            appendLine(auditJson?.takeIf { it.isNotBlank() && it != "null" }?.take(3_000) ?: "not available")
            appendLine()
            appendLine("CURRENT CSS (from Pass 2):")
            appendLine(previous.customCss)
            appendLine()
            appendLine(if (!screenshotBase64.isNullOrBlank()) "A live screenshot of the rendered page is attached. Stitch any `clashingSurfaces`, `lowContrastText`, or missing static ornaments from Pass 1's spec. Return the review JSON now." else "Return the review JSON now.")
        } else buildString {
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            appendLine()
            appendLine("VISUAL ARCHITECTURE SPECIFICATION FROM PASS 1 (Translate every static visual detail into lightweight, 60fps Pure CSS):")
            appendLine(brief!!.raw)
            appendLine()
            appendLine("Compile this specification into valid, visible, conflict-free Pure CSS now.")
        }

        val parts = mutableListOf<Part>(Part(text = userContextText))
        if (isReview && !screenshotBase64.isNullOrBlank()) {
            parts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = screenshotBase64)))
        }

        fun buildReq(jsonMode: Boolean) = GeminiRequest(
            contents = listOf(Content(parts = parts)),
            systemInstruction = Content(listOf(Part(text = if (isReview) UI_REVIEW_SYSTEM_PROMPT else UI_SYNTHESIS_SYSTEM_PROMPT))),
            generationConfig = GenerationConfig(
                temperature = if (isReview) 0.25f else 0.6f,
                topP = 0.95f,
                maxOutputTokens = if (isReview) 3072 else 6144,
                responseMimeType = if (jsonMode) "application/json" else null,
                thinkingConfig = ThinkingConfig(thinkingBudget = if (isReview) 512 else 768)
            )
        )

        val logType = if (isReview) "reviewBlueprint ($activeModel)" else "buildBlueprint ($activeModel)"
        val requestJson = buildString {
            appendLine("=== TARGET MODEL ===")
            appendLine(if (isNemotronSynthesis) "$activeModel (NVIDIA NIM)" else activeModel)
            appendLine()
            appendLine("=== SYSTEM INSTRUCTION ===")
            appendLine(if (isReview) UI_REVIEW_SYSTEM_PROMPT else UI_SYNTHESIS_SYSTEM_PROMPT)
            appendLine()
            appendLine("=== USER CONTENT & PROMPT ===")
            append(userContextText)
            if (isReview && !screenshotBase64.isNullOrBlank()) {
                appendLine()
                appendLine("=== ATTACHED LIVE SCREENSHOT (SENT TO REVIEW AGENT) ===")
                appendLine("Payload Size: ${screenshotBase64.length / 1024} KB base64 (image/jpeg)")
                if (!screenshotPath.isNullOrBlank()) {
                    appendLine("Screenshot File: $screenshotPath")
                }
            }
        }

        try {
            val (raw, rawThoughts) = if (isNemotronSynthesis) {
                callNvidiaNimStream(
                    model = activeModel,
                    apiKey = nvidiaApiKey,
                    systemInstruction = UI_SYNTHESIS_SYSTEM_PROMPT,
                    userContent = userContextText,
                    temperature = 0.6f,
                    topP = 0.95f,
                    maxTokens = NIM_MAX_TOKENS,
                    onThoughtChunk = { chunk ->
                        onThinkingUpdate?.invoke("Pass 2: CSS Engineering", chunk, false)
                    },
                    onOutputChunk = { _ ->
                        onThinkingUpdate?.invoke("Pass 2: Writing Pure CSS ($activeModel)", "", false)
                    }
                )
            } else {
                callGeminiStream(
                    activeModel,
                    buildReq(true),
                    geminiApiKey,
                    onThoughtChunk = { chunk ->
                        onThinkingUpdate?.invoke(
                            if (isReview) "Pass 3: Visual & Telemetry QA Audit" else "Pass 2: CSS Engineering",
                            chunk,
                            false
                        )
                    },
                    onOutputChunk = { _ ->
                        onThinkingUpdate?.invoke(
                            if (isReview) "Pass 3: Stitching Self-Healing CSS" else "Pass 2: Writing Pure CSS",
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
                parsed?.copy(
                    concept = brief!!.idea,
                    fingerprint = brief.fingerprint,
                    designSpec = brief.raw
                )
            }

            if (blueprint == null) {
                val err = "Model returned invalid UIBlueprint JSON."
                ApiRequestLogger.logRequest(
                    context = context,
                    type = logType,
                    websiteName = websiteName,
                    websiteUrl = websiteUrl,
                    userPrompt = cleanPrompt,
                    requestPayload = requestJson,
                    responseResult = err + "\n\n" + raw,
                    isSuccess = false,
                    screenshotBase64 = if (isReview) screenshotBase64 else null,
                    screenshotPath = if (isReview) screenshotPath else null
                )
                Result.failure(IllegalStateException(err))
            } else {
                storeFor(context).put(cleanPrompt, blueprint, domain, remember = !isReview)
                val logResponse = if (rawThoughts.isNotBlank()) {
                    "=== REASONING & THINKING PROCESS ===\n$rawThoughts\n\n=== RESPONSE RESULT ===\n" + (if (isReview) raw else blueprint.toJson())
                } else {
                    if (isReview) raw else blueprint.toJson()
                }
                ApiRequestLogger.logRequest(
                    context = context,
                    type = logType,
                    websiteName = websiteName,
                    websiteUrl = websiteUrl,
                    userPrompt = cleanPrompt,
                    requestPayload = requestJson,
                    responseResult = logResponse,
                    isSuccess = true,
                    screenshotBase64 = if (isReview) screenshotBase64 else null,
                    screenshotPath = if (isReview) screenshotPath else null
                )
                onThinkingUpdate?.invoke("Theme Synthesis Complete", "", true)
                Result.success(blueprint)
            }
        } catch (e: Exception) {
            val msg = describe(e)
            ApiRequestLogger.logRequest(
                context = context,
                type = logType,
                websiteName = websiteName,
                websiteUrl = websiteUrl,
                userPrompt = cleanPrompt,
                requestPayload = requestJson,
                responseResult = msg,
                isSuccess = false,
                screenshotBase64 = if (isReview) screenshotBase64 else null,
                screenshotPath = if (isReview) screenshotPath else null
            )
            Result.failure(Exception(msg, e))
        }
    }

    private fun applyReviewPatch(previous: UIBlueprint, raw: String, prompt: String): UIBlueprint {
        val json = try { JSONObject(extractJsonObject(raw) ?: return previous) } catch (_: Exception) { return previous }
        val replacedCss = sanitizeGeneratedCss(json.optString("replacedCss", "")).trim()
        val fixCss = sanitizeGeneratedCss(json.optString("fixCss", "")).trim().take(4_000)

        if (replacedCss.isBlank() && fixCss.isBlank()) return previous

        val baseCss = if (replacedCss.length > 200) replacedCss else previous.customCss
        val mergedCss = if (fixCss.isNotBlank()) "$baseCss\n/* review-stitch */\n$fixCss" else baseCss

        val merged = JSONObject(previous.toJson()).put("customCss", mergedCss)
        return UIBlueprint.parse(merged.toString(), prompt)
            ?.copy(
                concept = previous.concept,
                fingerprint = previous.fingerprint,
                designSpec = previous.designSpec
            )
            ?: previous
    }

    // ───── v19 Prompts: Simple, Artistic, Zero-Lag Static Craftsmanship ─────

    private val UI_REVIEW_SYSTEM_PROMPT = """
You are the Pure-CSS QA & Self-Healing Reviewer inside an Android mobile browser.
You receive:
1. The ORIGINAL PASS 1 VISUAL SPECIFICATION.
2. The LIVE COMPUTED DOM TELEMETRY (`clashingSurfaces`, `lowContrastText`, `squishedColumns`, `roleCounts`) measured from the painted WebView.
3. The CURRENT CSS generated by Pass 2.
4. A LIVE SCREENSHOT of how the page renders on the phone screen.

Fix any visual or compatibility defects using lightweight, 60fps static Pure CSS (`fixCss`):
1. UN-THEMED / CLASHING HOST SURFACES (`clashingSurfaces` in Telemetry or Screenshot):
   - Every item in `clashingSurfaces` is a visible container, header bar, search box, or card on the live website that ignored the theme (e.g. stayed bright white `#ffffff` on a dark theme).
   - For EVERY selector in `clashingSurfaces`, write an explicit rule in `fixCss` (`html body <sel> { background: var(--m-surface) !important; color: var(--m-fg) !important; border-color: var(--m-accent) !important; }`).
2. UNREADABLE TEXT (`lowContrastText` in Telemetry or Screenshot):
   - For every selector in `lowContrastText` (contrast < 3.2:1), force readable high-contrast `color: var(--m-fg) !important;` and `text-shadow`.
3. MISSING STATIC ORNAMENTS FROM PASS 1 SPEC:
   - If Pass 2 forgot a requested card corner ornament, header border trim, or `:nth-child` color variation, restore it in `fixCss`.
4. ZERO LAG & LAYOUT PROTECTION:
   - Do NOT add `@keyframes` animations, `body::before`/`body::after` overlays, or `backdrop-filter: blur()`. Never alter `display`, `flex-direction`, `width`, or `height`.

OUTPUT: ONLY one valid JSON object, no markdown fences:
{
  "problems": ["concise list of clashingSurfaces, lowContrastText, or missing ornaments found"],
  "replacedCss": "",
  "fixCss": "high-specificity static CSS overrides (using !important) that stitch all clashingSurfaces and restore missing details"
}
""".trimIndent()

    private val UI_CONCEPT_SYSTEM_PROMPT = """
You are the Master Visual Architect for a mobile browser that transforms any website into a deeply artistic, decorative, and cohesive thematic world using lightweight, 60fps static Pure CSS.
You write ZERO CSS code. You decide every visual element and describe **exactly what each element looks like visually** (its colors, geometry, surface patterns, borders, corner ornaments, and lighting) so the CSS Compiler can build it cleanly.

CRITICAL RULES FOR YOUR VISUAL DECISIONS:
- Keep it 100% static and GPU-friendly for mid-tier phones: do NOT invent moving particles, full-screen weather overlays (`body::before`/`body::after`), or continuous looping animations!
- Achieve deep immersion through **crisp static artistry**: multi-layered surface gradients, fine geometric patterns, decorative repeating edge trims on headers/footers, corner motifs on cards, polychromatic `:nth-child` color rhythms, and expressive typography.
- For `typographyAndSymbol`, choose a classic 4-digit BMP Unicode symbol and write its 4-character hex code (e.g. `\2726`, `\2699`, `\2694`, `\2693`, `\26A1`)—never use raw multi-byte emojis!

OUTPUT: ONLY one valid JSON object with concrete visual instructions:
{
  "idea": "One sentence summarizing the visual world and artistic direction.",
  "bg": "#rrggbb (exact base canvas background hex color)",
  "fg": "#rrggbb (exact primary high-contrast readable text hex color)",
  "palette": "Exact hex codes and roles: bg (main canvas), surface (primary card/bar), surface2 (elevated/secondary), fg (primary text), ink (headline highlight text), accent (primary glow/trim), plus 2 or 3 distinct secondary/tertiary harmony hex colors for alternating cards and chips.",
  "typographyAndSymbol": "Exact Google Font names for headings and body text, casing, letter-spacing, text-shadow colors, and one 4-digit CSS Unicode hex escape to prefix main video/article headlines.",
  "backdropCanvas": "Detailed visual description of the static background canvas (`html, body`): base gradients and subtle repeating grid or texture patterns.",
  "headerBar": "Detailed visual description of the top header (`[data-mydes=\"header\"]`): its surface gradient, repeating border-bottom decorative trim pattern, box-shadow, and icon colors.",
  "searchBox": "Detailed visual description of the search input (`[data-mydes=\"search\"]`): its surface material, border, inner/outer glow, text/placeholder colors, and `:focus-within` highlight.",
  "filterChips": "Detailed visual description of the filter chips (`[data-mydes=\"chip\"]`): their shape, surface gradient, how every 2nd, 3rd, and 4th chip cycles through the harmony palette colors, and how the active/selected chip lights up.",
  "cardsAndCorners": "Detailed visual description of the content cards (`[data-mydes=\"card\"]`): their surface gradient, subtle interior texture pattern, top/left edge light-trim, 4-corner geometric ornaments or `::before` badge, and how every 2nd and 3rd card cycles through distinct border and shadow colors.",
  "thumbnailsAndButtons": "Detailed visual description of how card thumbnails (`[data-mydes=\"card\"] img`) are framed and color-graded, and how action buttons (`[data-mydes=\"btn\"]`) are styled.",
  "bottomBar": "Detailed visual description of the bottom navigation bar (`[data-mydes=\"bottom-bar\"]`): its surface gradient, top-edge decorative trim pattern, and icon styling."
}
""".trimIndent()

    private val UI_SYNTHESIS_SYSTEM_PROMPT = """
You are the Pure-CSS Compiler inside an Android mobile browser.
The Master Visual Architect (Pass 1) has already made 100% of the creative and visual decisions for every element on the page.
Your job is to **faithfully execute every visual instruction from Pass 1's specification**, writing clean, lightweight, 60fps static Pure CSS (`customCss`) that runs smoothly on mid-tier phones.

UNIVERSAL SELECTORS TO TARGET IN `customCss`:
1. `html, body` (Static base canvas—never use `background-attachment: fixed`)
2. `[data-mydes="header"], header` (plus `::before`, `::after`) and `[data-mydes="header"] svg, header svg`
3. `[data-mydes="search"], yt-searchbox` (plus `input`, `input::placeholder`, `svg`, and `:focus-within`)
4. `[data-mydes="chip"]` (plus `::before`, `:nth-child(4n+2)`, `:nth-child(4n+3)`, `:nth-child(4n)`, and `[aria-selected="true"], [aria-pressed="true"]`)
5. `[data-mydes="card"]` (plus `::before`, `::after`, `:nth-child(3n+2)`, `:nth-child(3n)`, and `:hover, :active`)
6. `[data-mydes="card"] img`
7. `[data-mydes="heading"], [data-mydes="title"]` (plus `[data-mydes="heading"]::before` and `[data-mydes="heading"] *`)
8. `[data-mydes="btn"]`
9. `[data-mydes="bottom-bar"]` (plus `::before`, `::after`) and `[data-mydes="bottom-bar"] svg`

CRITICAL 60FPS PERFORMANCE & VISIBILITY LAWS:
1. ZERO GPU LAG (NO OVERLAYS, NO CONTINUOUS KEYFRAMES, NO CARD BLUR):
   - Do NOT create `body::before`, `body::after`, or `@keyframes` animations! Use crisp static gradients, borders, and fast `transition: border-color 0.2s, box-shadow 0.2s` on `:hover`, `:active`, and `:focus-within`.
   - NEVER use `background-attachment: fixed` on `html, body` or `backdrop-filter: blur()` on `[data-mydes="card"]` (they cause severe scroll lag on mid-tier mobile GPUs).
2. MULTI-LAYER `background-*` ARRAY ALIGNMENT:
   - Never put `background` shorthand position/size/repeat tokens (like `0 0/100% 2px no-repeat`) inside `background-image:`!
   - Always declare `background-image`, `background-size`, `background-position`, and `background-repeat` as separate properties with `!important`, and verify that all 4 properties have the exact same number of comma-separated layers!
   - On `[data-mydes="header"]`, `[data-mydes="bottom-bar"]`, and `[data-mydes="card"]`, the LAST layer in `background-image` must always be a full-surface `100% 100%` (`no-repeat`) base gradient, while earlier layers draw the repeating edge trims (`repeat-x`) or corner ornaments (`no-repeat`).
3. UNCLIPPED CARD ORNAMENTS & COMPLETE `:nth-child` OVERRIDES:
   - Offset corner gradient ornaments inward (`at 10px 10px`) or use `[data-mydes="card"]::before` (`top: 6px; left: 8px;`) so rounded corners (`border-radius`) never clip them.
   - Whenever you style `[data-mydes="card"]:nth-child(3n+2)` or `:nth-child(3n)` (and chip `:nth-child` rules), ALWAYS set BOTH `border-color: <color> !important;` AND `box-shadow: ... !important;`.
   - ALWAYS append `!important` to every property inside `[data-mydes="..."]` rules.
4. PURE BMP UNICODE HEX ESCAPES & NATIVE LAYOUT PROTECTION:
   - Write symbols as CSS backslash hex escapes (e.g. `content: "\\26A1 ";`)—never raw emojis.
   - Never alter `display`, `flex-direction`, `width`, `height`, or `aspect-ratio` on `[data-mydes="card"]`, `[data-mydes="btn"]`, or their children. Never add `border` or `padding` to bare `a` or `button` tags.

OUTPUT: ONLY one valid JSON object, no markdown fences, no commentary:
{
  "name": "short title",
  "dark": true or false,
  "bg": "#rrggbb", "surface": "#rrggbb", "surface2": "#rrggbb", "fg": "#rrggbb", "ink": "#rrggbb", "accent": "#rrggbb",
  "remapSurfaces": true,
  "customCss": "complete, lightweight, 60fps static Pure CSS faithfully implementing every element from Pass 1",
  "concept": "one sentence summary"
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
                        val trimmed = line.trim()
                        if (trimmed.startsWith("data:")) {
                            val jsonStr = trimmed.removePrefix("data:").trim()
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
                    if (gc.thinkingConfig != null || gc.thinkingLevel != null) {
                        currentRequest = currentRequest.copy(
                            generationConfig = gc.copy(thinkingConfig = null, thinkingLevel = null)
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

    private val NIM_MAX_TOKENS = 8192
    private fun nimReasoningKwargs(reasoningLevel: String = "medium"): JSONObject {
        return when (reasoningLevel.lowercase()) {
            "off" -> JSONObject().put("enable_thinking", false)
            "full" -> JSONObject().put("enable_thinking", true)
            "medium" -> JSONObject().put("enable_thinking", true).put("medium_effort", true)
            else -> JSONObject().put("enable_thinking", true).put("medium_effort", true)
        }
    }
    @Volatile private var lastNimFinishReason: String? = null

    private suspend fun callNvidiaNimStream(
        model: String,
        apiKey: String,
        systemInstruction: String,
        userContent: String,
        temperature: Float = 0.6f,
        topP: Float = 0.95f,
        maxTokens: Int = NIM_MAX_TOKENS,
        reasoningLevel: String = "medium",
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
            .put("chat_template_kwargs", nimReasoningKwargs(reasoningLevel))
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

        val fallbackBody = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemInstruction))
                .put(JSONObject().put("role", "user").put("content", userContent))
            )
            .put("temperature", temperature)
            .put("top_p", topP)
            .put("max_tokens", maxTokens)
            .put("chat_template_kwargs", nimReasoningKwargs(reasoningLevel))
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

    suspend fun setTheme(prompt: String, includeScreenshot: Boolean = true): Result<UIBlueprint> {
        val currentUrl = withContext(Dispatchers.Main) { webView.url ?: "*" }
        val outlineJson = webView.evaluateJsSuspend(MydesRuntime.OUTLINE_PROBE_JS)

        // 1. Run Pass 1 + Pass 2
        val initialBlueprint = service.themeSpec(
            context = context,
            prompt = prompt,
            websiteUrl = currentUrl,
            pageOutlineJson = outlineJson,
            screenshotBase64 = null
        ).getOrElse { return Result.failure(it) }

        val initialJs = MydesRuntime.script(initialBlueprint)
        withContext(Dispatchers.Main) {
            script = initialJs
            if (docStartOk) {
                try { handler?.remove() } catch (_: Exception) {}
                handler = WebViewCompat.addDocumentStartJavaScript(webView, initialJs, setOf("*"))
            }
            webView.evaluateJavascript(initialJs, null)
        }

        // 2. Run Pass 3 (Live Render Audit + Screenshot Stitch)
        if (includeScreenshot) {
            delay(450)
            val auditJson = webView.evaluateJsSuspend(MydesRuntime.VISUAL_AND_DOM_AUDIT_JS)
            val liveScreenshot = withContext(Dispatchers.Main) { webView.captureJpegBase64() }

            val reviewedBlueprint = service.themeSpec(
                context = context,
                prompt = prompt,
                websiteUrl = currentUrl,
                pageOutlineJson = outlineJson,
                screenshotBase64 = liveScreenshot,
                auditJson = auditJson,
                isReviewPass = true
            ).getOrNull()

            if (reviewedBlueprint != null && reviewedBlueprint.customCss != initialBlueprint.customCss) {
                val finalJs = MydesRuntime.script(reviewedBlueprint)
                withContext(Dispatchers.Main) {
                    script = finalJs
                    if (docStartOk) {
                        try { handler?.remove() } catch (_: Exception) {}
                        handler = WebViewCompat.addDocumentStartJavaScript(webView, finalJs, setOf("*"))
                    }
                    webView.evaluateJavascript(finalJs, null)
                }
                return Result.success(reviewedBlueprint)
            }
        }

        return Result.success(initialBlueprint)
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