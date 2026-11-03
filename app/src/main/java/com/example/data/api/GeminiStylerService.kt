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
 * v21 - "Adaptive Dual-Tier Pipeline & 4 Structural Sanitizer Fixes"
 *
 * What's New in v21:
 * 1. Dual-Tier Model-Aware Pipeline (Uncapping Frontier Models while keeping Flash-Lite 11s Fast):
 *    - Pass 1 ALWAYS runs on `CONCEPT_MODEL` (`gemini-3.5-flash-lite`).
 *    - Tier A (`gemini-3.5-flash-lite` selected for Pass 2): Follows the exact v20 fast, token-dense
 *      pipeline (`UI_CONCEPT_SYSTEM_PROMPT_LITE` + `UI_SYNTHESIS_SYSTEM_PROMPT_LITE`).
 *    - Tier B (`nemotron-3-ultra` or `gemini-3.8-flash` selected for Pass 2): Removes the Flash-Lite
 *      ceiling! Pass 1 uses `UI_CONCEPT_SYSTEM_PROMPT_FRONTIER` (describing rich, artistic visual &
 *      material goals with ZERO literal CSS snippets so it never dictates flat `solid #142118` or
 *      broken `100% 100%` rules), and Pass 2 uses `UI_SYNTHESIS_SYSTEM_PROMPT_FRONTIER` so the big
 *      model moulds those artistic goals into multi-layered CSS shaders, bespoke SVG data-URI corner
 *      ornaments, and choreographed animations in its own way.
 * 2. The 4 New Sanitizer Fixes (`sanitizeGeneratedCss`):
 *    - Fix A (Data-URI Semicolon Protection): Splits CSS declarations only at top-level semicolons
 *      outside `(...)` and quotes, so `data:image/svg+xml;utf8` is NEVER corrupted into
 *      `data:image/svg+xml !important;utf8`!
 *    - Fix B (Auto-Inject Missing `content: ""` on `::before`/`::after`): If a `::before` or `::after`
 *      rule (like `[data-mydes="media-box"]::after`) forgets `content:`, automatically prepends
 *      `content: "" !important;` so scanlines and corner overlays never fail to render.
 *    - Fix C (Expanded Animation Unlocker): Strips `!important` from `border`, `border-color`, and
 *      `box-shadow` (in addition to `background-position`, `opacity`, `transform`, `filter`) inside
 *      animated blocks so `@keyframes borderGlow` and `@keyframes doom-glow` pulse freely.
 *    - Fix D (Unfreeze `background-size: 100% 100%` on `body::before`): Automatically rewrites
 *      `background-size: 100% 100%` on animated `body::before` rules to `64px 64px` so percentage
 *      `background-position` animations never mathematically zero out.
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

// ───────────────────── Pure-CSS Sanitizer & Auto-Healer (v21) ─────────────────────

internal fun stripInventedLabels(css: String): String =
    css.replace(
        Regex("""[^{}]*::?(?:before|after)\s*\{[^{}]*content\s*:\s*(["'])(?!data:)[^"'{}]*[A-Za-z]{4,}[^"'{}]*\1[^{}]*\}""", RegexOption.IGNORE_CASE),
        ""
    )

/**
 * Splits a CSS rule body by top-level semicolons `;` ONLY when outside parentheses `(...)` and quotes `""`/`''`.
 * Prevents `data:image/svg+xml;utf8,...` from ever being split at `;utf8`!
 */
internal fun splitTopLevelDeclarations(body: String): List<String> {
    val result = ArrayList<String>()
    val sb = StringBuilder()
    var parenDepth = 0
    var inSingleQuote = false
    var inDoubleQuote = false
    var escape = false

    for (ch in body) {
        if (escape) {
            sb.append(ch)
            escape = false
            continue
        }
        when (ch) {
            '\\' -> {
                sb.append(ch)
                escape = true
            }
            '\'' -> {
                if (!inDoubleQuote) inSingleQuote = !inSingleQuote
                sb.append(ch)
            }
            '"' -> {
                if (!inSingleQuote) inDoubleQuote = !inDoubleQuote
                sb.append(ch)
            }
            '(' -> {
                if (!inSingleQuote && !inDoubleQuote) parenDepth++
                sb.append(ch)
            }
            ')' -> {
                if (!inSingleQuote && !inDoubleQuote && parenDepth > 0) parenDepth--
                sb.append(ch)
            }
            ';' -> {
                if (!inSingleQuote && !inDoubleQuote && parenDepth == 0) {
                    result.add(sb.toString())
                    sb.setLength(0)
                } else {
                    sb.append(ch)
                }
            }
            else -> sb.append(ch)
        }
    }
    if (sb.isNotEmpty()) result.add(sb.toString())
    return result
}

internal fun sanitizeGeneratedCss(css: String): String {
    var out = stripInventedLabels(css)
    out = out.replace(Regex("""@import\s+url\([^)]+\)\s*;?""", RegexOption.IGNORE_CASE), "")

    // Repair any pre-existing `; !importantutf8` or `!important;utf8` corruption
    out = out.replace(Regex("""data:image/svg\+xml\s*!important\s*;""", RegexOption.IGNORE_CASE), "data:image/svg+xml;")

    // 1. Auto-encode raw '#' to '%23' inside data:image/svg+xml URLs
    out = out.replace(Regex("""url\(\s*(['"]?)(data:image/svg\+xml[^)'"]+)\1\s*\)""", RegexOption.IGNORE_CASE)) { match ->
        val quote = match.groupValues[1].ifEmpty { "\"" }
        val dataUrl = match.groupValues[2].replace("#", "%23")
        "url($quote$dataUrl$quote)"
    }

    // 2. Prevent repeating-radial-gradient on body::before/after from creating blinding dense rings
    out = out.replace(
        Regex("""(body::(?:before|after)\s*\{[^}]*?)repeating-radial-gradient""", RegexOption.IGNORE_CASE),
        "$1radial-gradient"
    )

    // 3. Prevent foreground overlays from being buried
    out = out.replace(
        Regex("""(body::(?:before|after)\s*\{[^}]*?)z-index\s*:\s*-?[01]\b""", RegexOption.IGNORE_CASE),
        "$1z-index: 2147483630"
    )

    // 4. Fix D: Unfreeze `background-size: 100% 100%` (or `0 0/100% 100%`) on animated `body::before`
    // (In CSS, percentage background-position on a 100% 100% background-size moves 0px!)
    out = out.replace(Regex("""(body::before\s*\{)([^}]+)\}""", RegexOption.IGNORE_CASE)) { match ->
        val sel = match.groupValues[1]
        var body = match.groupValues[2]
        if (Regex("""\banimation(?:-name)?\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            body = body.replace(
                Regex("""background-size\s*:\s*100%\s+100%""", RegexOption.IGNORE_CASE),
                "background-size: 64px 64px"
            )
            body = body.replace(
                Regex("""0\s+0\s*\/\s*100%\s+100%""", RegexOption.IGNORE_CASE),
                "0 0 / 64px 64px"
            )
        }
        "$sel$body}"
    }

    // 5. Auto-fix top-anchored conic-gradient beams pointing North (0deg) so they shine South (150deg..210deg)
    out = out.replace(
        Regex("""conic-gradient\(\s*from\s+0deg\s+at\s+50%\s+0%\s*,\s*transparent\s+0deg\s*,\s*([^,]+?)\s+(\d+)deg\s*,\s*transparent\s+(\d+)deg\s*\)""", RegexOption.IGNORE_CASE)
    ) { match ->
        val beamColor = match.groupValues[1]
        "conic-gradient(from 150deg at 50% 0%, transparent 0deg, $beamColor 30deg, transparent 60deg)"
    }

    // 6. Fix corrupted UTF-8 / mojibake inside content: "..."
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

    // 7. Fix B: Auto-inject `content: "" !important;` into any `::before` or `::after` rule that forgot `content:`
    out = out.replace(Regex("""([^{}@]*::?(?:before|after)[^{}@]*)\{([^{}]+)\}""", RegexOption.IGNORE_CASE)) { match ->
        val sel = match.groupValues[1]
        val body = match.groupValues[2]
        if (Regex("""\bcontent\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            match.value
        } else {
            "$sel{content: \"\" !important; $body}"
        }
    }

    // 8. Strip layout-breaking flex-direction overrides
    out = out.replace(
        Regex("""flex-direction\s*:\s*column\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE),
        ""
    )

    // 9. Strip negative margins
    out = out.replace(
        Regex("""margin(?:-bottom|-top)?\s*:\s*-\d+(?:\.\d+)?(?:px|rem|em|%)\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE),
        "margin-bottom: 12px;"
    )

    // 10. Strip unreferenced external SVG url(#id) masks
    out = out.replace(
        Regex("""(?:-webkit-)?mask(?:-image)?\s*:\s*url\(\s*['"]?#[^)]+\)\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE),
        ""
    )

    // 11. Syntax Auto-Healer: Clean illegal shorthand placement inside background-image declarations
    out = out.replace(Regex("""background-image\s*:\s*([^;!}]+)""", RegexOption.IGNORE_CASE)) { match ->
        val propVal = match.groupValues[1]
        val cleaned = propVal.replace(
            Regex("""\)\s+(?:[\d.]+(?:px|%|em|rem)?\s+[\d.]+(?:px|%|em|rem)?(?:\/[\d.]+(?:px|%|em|rem)?)?|no-repeat|repeat-x|repeat-y|repeat)\b""", RegexOption.IGNORE_CASE),
            ")"
        )
        "background-image: $cleaned"
    }

    // 12. Safeguard: Clamp unconstrained 100% 100% background-size on SVG data-URIs to prevent blurry screen-covering blobs
    out = out.replace(Regex("""(\[data-mydes=['"][^'"]+['"]\][^{]*\{[^}]*background-image:[^}]*data:image\/svg\+xml[^}]*background-size:\s*)100%\s+100%""", RegexOption.IGNORE_CASE)) { match ->
        "${match.groupValues[1]}48px 48px"
    }

    // 13. Prevent [data-mydes="btn"] from forcing fixed square/circle width & height
    out = out.replace(Regex("""(\[data-mydes=['"]btn['"]\][^{]*\{)([^}]+)\}""", RegexOption.IGNORE_CASE)) { match ->
        val sel = match.groupValues[1]
        val body = match.groupValues[2]
            .replace(Regex("""\b(?:width|height)\s*:\s*\d+(?:px|rem|em)\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE), "")
        "$sel$body}"
    }

    // 14. Isolate broad 'a' and 'button' tags from box-model mutations
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

    // 15. Fix A: Ensure static properties on [data-mydes="..."] elements have !important using
    // `splitTopLevelDeclarations` so semicolons inside `url("data:image/svg+xml;utf8,...")` are NEVER split!
    out = out.replace(Regex("""(\[data-mydes=['"][^'"]+['"]\][^{]*\{)([^}]+)\}""", RegexOption.IGNORE_CASE)) { match ->
        val sel = match.groupValues[1]
        val body = match.groupValues[2]
        val hasAnimation = Regex("""\banimation(?:-name)?\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(body)
        val declarations = splitTopLevelDeclarations(body)
        val forcedBody = declarations.joinToString(";") { statement ->
            val trimmed = statement.trim()
            val propName = trimmed.substringBefore(':').trim().lowercase(Locale.US)
            val isAnimatableProp = propName in setOf("background-position", "opacity", "transform", "filter") ||
                (hasAnimation && propName in setOf("border", "border-color", "box-shadow"))
            if (trimmed.isEmpty() ||
                trimmed.startsWith("@") ||
                trimmed.contains("!important", ignoreCase = true) ||
                isAnimatableProp
            ) {
                statement
            } else {
                val colonIdx = trimmed.indexOf(':')
                if (colonIdx != -1) "$trimmed !important" else statement
            }
        }
        "$sel$forcedBody}"
    }

    // 16. Fix C: Expanded Animation Unlocker
    // Strip `!important` from `background-position` globally, and from `opacity`, `transform`, `filter`,
    // `border`, `border-color`, and `box-shadow` inside any rule block that declares `animation` or targets `body::before`/`body::after`.
    out = out.replace(
        Regex("""(background-position\s*:\s*[^;!}]+?)\s*!important""", RegexOption.IGNORE_CASE),
        "$1"
    )
    out = out.replace(Regex("""([^{}@]+)\{([^{}]+)\}""")) { match ->
        val sel = match.groupValues[1]
        val body = match.groupValues[2]
        val isAnimatedBlock = Regex("""\banimation(?:-name)?\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(body) ||
            Regex("""body::(?:before|after)""", RegexOption.IGNORE_CASE).containsMatchIn(sel)
        if (!isAnimatedBlock) {
            match.value
        } else {
            val unlockedBody = body.replace(
                Regex("""(\b(?:background-position|opacity|transform|filter|border|border-color|box-shadow)\s*:\s*[^;!}]+?)\s*!important""", RegexOption.IGNORE_CASE),
                "$1"
            )
            "$sel{$unlockedBody}"
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
        const val RUNTIME_VERSION = 21

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

// ───────────────────── Artisanal Pure-CSS Universal Runtime v21 ─────────────────────

object MydesRuntime {

    private val CORE = """
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

/* ---------- 1. Artisanal Pure-CSS Stylesheet Engine ---------- */
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
    '[data-mydes="media-box"] { position: relative; display: block; overflow: hidden; border-radius: 8px; }\n' +
    '[data-mydes="heading"], [data-mydes="title"], [data-mydes="card"] h1, [data-mydes="card"] h2, [data-mydes="card"] h3, [data-mydes="card"] h4, [class*="media-item-headline"], [class*="title"] { color: var(--m-ink); }\n' +
    '[data-mydes="heading"] *, [data-mydes="title"] *, [class*="media-item-headline"] * { font-family: inherit !important; color: inherit !important; letter-spacing: inherit !important; text-shadow: inherit !important; text-transform: inherit !important; }\n' +
    '[data-mydes="bottom-bar"], ytm-pivot-bar-renderer, footer { background-color: var(--m-surface); border-top: 2px solid var(--m-accent); color: var(--m-fg); }\n';

  var safetyResetCss =
    '\n/* --- Pure-CSS Safety Resets --- */\n' +
    'html body a:not([data-mydes="card"]):not([data-mydes="btn"]):not([data-mydes="chip"]):has(img, video, picture, h1, h2, h3, h4, div), html body [class*="thumbnail"] a:not([data-mydes="card"]), html body [class*="media"] a:not([data-mydes="card"]) { border: none !important; padding: 0 !important; background: transparent !important; box-shadow: none !important; }\n' +
    'html body button:not([data-mydes="nav-item"]):has(svg, img), html body [role="button"]:not([data-mydes="nav-item"]):not([data-mydes="chip"]):has(svg, img), html body [aria-label*="menu" i], html body [aria-label*="More" i], html body [aria-label*="Action" i] { background: transparent !important; border: none !important; box-shadow: none !important; }\n';

  var fullCss = rootVars + universalBaseCss + '\n' + (cfg.customCss || '') + safetyResetCss;
  var root = document.head || document.documentElement;
  if(!root) return;
  if(!eStyle || !eStyle.isConnected) {
    eStyle = document.createElement('style');
    eStyle.id = '__mydes_style';
    root.appendChild(eStyle);
  }
  if(eStyle.textContent !== fullCss) eStyle.textContent = fullCss;

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

function decorateCardAnatomy(c){
  var mainImg = c.querySelector('img:not([class*="avatar" i]):not([class*="channel" i]):not([class*="icon" i]):not([width^="1"]):not([width^="2"]):not([width^="3"]), video');
  if(mainImg) {
    var mediaWrap = mainImg.closest('a[class*="thumbnail" i], div[class*="thumbnail" i], yt-thumbnail-view-model, picture') || mainImg.parentElement;
    if(mediaWrap && mediaWrap !== c && !mediaWrap.hasAttribute('data-mydes')) {
      put(mediaWrap, 'media-box');
    }
  }

  var existingHeadings = c.querySelectorAll('[data-mydes="heading"]');
  if(existingHeadings.length > 1) {
    for(var h=1; h<existingHeadings.length; h++) existingHeadings[h].removeAttribute('data-mydes');
    return;
  }
  if(existingHeadings.length === 1) return;

  var primaryHeading = c.querySelector('h1, h2, h3, h4, [class*="media-item-headline"], [class*="title" i]:not([class*="sub" i])');
  if(primaryHeading) {
    put(primaryHeading, 'heading');
    return;
  }

  var l = c.querySelectorAll('a,span,div,p,strong,b');
  for(var i=0; i<l.length; i++){
    var x = l[i];
    if(x.children.length <= 1){
      var t = (x.textContent||'').trim();
      if(t.length >= 8 && t.length <= 160 && !PRICE.test(t)){
        put(x, 'heading');
        return;
      }
    }
  }
}

function syncActiveState(e) {
  var isAct = e.getAttribute('aria-selected') === 'true' ||
              e.getAttribute('aria-pressed') === 'true' ||
              e.getAttribute('aria-current') === 'page' ||
              e.getAttribute('aria-current') === 'true' ||
              /\b(?:selected|active|chip-bar-item-selected|pivot-bar-item-tab-selected)\b/i.test(e.className || '') ||
              !!e.querySelector('[aria-selected="true"], .pivot-bar-item-tab-selected');
  if(isAct) e.setAttribute('data-mydes-state', 'active');
  else e.removeAttribute('data-mydes-state');
}

function retag(){
  if(!document.body)return;
  var vw = window.innerWidth || 400;

  // 1. Header, Bottom Bar & Nav Items, Search, Chips
  each('header,[role="banner"],#navbar,#nav-main,#navbar-main,.mobile-topbar-header',function(e){if(!inContent(e))put(e,'header');});
  each('footer,[role="contentinfo"],#navFooter,ytm-pivot-bar-renderer',function(e){
    put(e,'bottom-bar');
    each('ytm-pivot-bar-item-renderer, [role="tab"], a, button', function(item){
      if(item.closest('[data-mydes="bottom-bar"]') === e && item.parentElement === e) {
        put(item, 'nav-item');
        syncActiveState(item);
      }
    });
  });
  each('ytm-pivot-bar-item-renderer', function(item){
    put(item, 'nav-item');
    syncActiveState(item);
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
    syncActiveState(e);
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
      decorateCardAnatomy(e);
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
          decorateCardAnatomy(item);
        }
      }
    }
  }

  each('h1',function(e){if(!e.closest('[data-mydes="card"]'))putNew(e,'title');});
  each('h2,h3,h4,[class*="media-item-headline"]',function(e){if(!e.closest('[data-mydes="card"]'))putNew(e,'heading');});
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
  clear:function(){
    if(mo)mo.disconnect();
    var s=document.getElementById('__mydes_style');if(s)s.remove();
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

    val OUTLINE_PROBE_JS = """
        (function(){
          var vw = innerWidth, vh = innerHeight;
          var bodyStyles = getComputedStyle(document.body || document.documentElement);
          var roleCounts = {};
          if(window.__mydes && window.__mydes.outline) {
            try { roleCounts = JSON.parse(window.__mydes.outline()).roleCounts || {}; } catch(e){}
          }
          return JSON.stringify({
            url: location.href,
            host: location.hostname,
            viewport: { w: vw, h: vh },
            roleCounts: roleCounts,
            baseline: {
              backgroundColor: bodyStyles.backgroundColor,
              textColor: bodyStyles.color,
              fontFamily: (bodyStyles.fontFamily || '').split(',')[0].replace(/['"]/g, '').trim()
            }
          });
        })();
    """

    val SNAPSHOT_FRAME_1_JS = """
        (function(){
          try {
            function snap(p) {
              var cs = getComputedStyle(document.body, p);
              if(!cs) return null;
              return { bp: cs.backgroundPosition, op: cs.opacity, tr: cs.transform, fl: cs.filter };
            }
            window.__mydes_f1 = { before: snap('::before'), after: snap('::after'), t: Date.now() };
            return true;
          } catch(e) { return false; }
        })();
    """

    val VISUAL_AND_DOM_AUDIT_JS = """
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
            var roles = ['header','search','chip','card','media-box','heading','title','btn','bottom-bar','nav-item'];
            roles.forEach(function(r){ roleCounts[r] = document.querySelectorAll('[data-mydes="' + r + '"]').length; });

            var f1 = window.__mydes_f1 || {};
            var animationWarnings = [];
            function inspectPseudo(pseudo, f1Snap) {
              var cs = getComputedStyle(document.body, pseudo);
              if(!cs || cs.content === 'none' || cs.display === 'none') {
                return null;
              }
              var zi = parseInt(cs.zIndex, 10);
              var op = parseFloat(cs.opacity);
              var bgi = cs.backgroundImage || '';
              var bgr = cs.backgroundRepeat || '';
              var anim = cs.animationName || 'none';

              var isMoving = false;
              if(f1Snap) {
                isMoving = (f1Snap.bp !== cs.backgroundPosition) ||
                           (f1Snap.op !== cs.opacity) ||
                           (f1Snap.tr !== cs.transform) ||
                           (f1Snap.fl !== cs.filter);
              }
              if(anim !== 'none' && f1Snap && !isMoving) {
                animationWarnings.push('body' + pseudo + ' animation (' + anim + ') computed values did not change.');
              }
              if(isNaN(zi) || zi <= 0) {
                animationWarnings.push('body' + pseudo + ' z-index is ' + cs.zIndex + '. Requires z-index: 2147483630.');
              }
              return {
                zIndex: cs.zIndex,
                opacity: cs.opacity,
                animationName: anim,
                isActivelyMovingBetweenFrames: isMoving,
                backgroundRepeat: bgr,
                backgroundSize: cs.backgroundSize
              };
            }
            var pseudoOverlays = {
              before: inspectPseudo('::before', f1.before),
              after: inspectPseudo('::after', f1.after)
            };

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
              animationWarnings: animationWarnings,
              pseudoOverlays: pseudoOverlays,
              clashingSurfaces: clashingSurfaces,
              lowContrastText: lowContrastText,
              squishedColumns: squishedColumns
            });
          } catch(e) {
            return JSON.stringify({ error: String(e) });
          }
        })();
    """

    val CONTRAST_AUDIT_JS = VISUAL_AND_DOM_AUDIT_JS
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
    private val prefs = context.applicationContext.getSharedPreferences("mydes_blueprints_v21", Context.MODE_PRIVATE)
    private fun key(prompt: String, domain: String) =
        "bp21|" + sha1Hex("${domain.trim().lowercase(Locale.US)}|${prompt.trim().lowercase(Locale.US)}")
    private fun lastPromptKey(prompt: String) =
        "bp21_last|" + sha1Hex(prompt.trim().lowercase(Locale.US))

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

    fun recentConcepts(limit: Int = 3): List<String> =
        (prefs.getString("recent_concepts", "") ?: "")
            .split('\n').filter { it.isNotBlank() }.takeLast(limit)

    private fun pushConcept(bp: UIBlueprint) {
        val traits = bp.fingerprint.ifBlank { bp.concept }.ifBlank { bp.name }
        val line = (bp.name.take(40) + ": " + traits).replace('\n', ' ').take(180)
        if (line.isBlank()) return
        val all = (recentConcepts(10) + line).takeLast(10)
        prefs.edit().putString("recent_concepts", all.joinToString("\n")).apply()
    }
}

// ───────────────────── The Gemini UI Synthesis Service (v21) ─────────────────────

class GeminiStylerService {

    companion object {
        const val MODEL_GEMINI_35_FLASH_LITE = "gemini-3.5-flash-lite"
        const val MODEL_GEMINI_38_FLASH = "gemini-3.8-flash"
        const val MODEL_NVIDIA_NEMOTRON_3_ULTRA = "nvidia/nemotron-3-ultra-550b-a55b"

        const val CONCEPT_MODEL = MODEL_GEMINI_35_FLASH_LITE
        const val SYNTHESIS_MODEL = MODEL_GEMINI_35_FLASH_LITE
        const val REVIEW_MODEL = MODEL_GEMINI_35_FLASH_LITE


        // ── Shared by both Pass 1 prompts: plain language, zero CSS vocabulary ──
        private val SHARED_UI_PARTS = """
THE PAGE YOU ARE DESIGNING
Whatever the website, its interface is reduced to these parts. Refer to them by these names:
- page canvas: the full-page background behind everything
- ambient layer A and ambient layer B: two full-screen transparent layers above the page that never block taps
- header: the top bar
- bottom bar: the bottom bar or footer, and its navigation items (one item may be marked active)
- search: the search field and its container
- chip: small filter or tab pills (one may be marked active)
- card: each repeated content item, including the frame around its image or video
- headline text and title text
- button: text buttons
Not every site shows every part, so design all of them.
 
WHAT IS POSSIBLE
Styling and imagery only: colour, gradient, texture, light, shadow, borders, shape, vector artwork drawn behind or over existing elements, typefaces, text effects, and animation of colour, light, opacity, shadow and imagery.
Not possible: scripts, canvas, adding new elements or words, rearranging layout, changing text content.
Cards and readable text never drift or resize continuously; they may react briefly to touch. Life comes from light, colour, texture and the ambient layers. Text must always stay readable.
""".trimIndent()

        // ── Shared by both compiler prompts and the reviewer: technical contract + correctness rules ──
        private val SHARED_CAPABILITY_CONTRACT = """
RUNTIME CONTRACT
Your output is CSS injected into the live page. No script, canvas or added elements: only existing elements, their ::before and ::after, and imagery drawn with CSS or inline vector data-URIs.
The runtime tags page elements with a data-mydes role. Use only these hooks and never depend on a site's own tag names, ids or classes:
- html, body                          page canvas
- body::before, body::after           ambient layers A and B
- [data-mydes="header"]               top bar (and its svg icons)
- [data-mydes="bottom-bar"]           bottom bar or footer
- [data-mydes="nav-item"]             items inside bars; [data-mydes-state="active"] marks the current one
- [data-mydes="search"]               the search container OR the field itself; style the element and any input inside it so no host colour leaks
- [data-mydes="chip"]                 filter or tab pills; [data-mydes-state="active"] marks the selected one
- [data-mydes="card"]                 each repeated content item
- [data-mydes="media-box"]            frame around a card's image or video; takes ::before/::after overlays
- [data-mydes="card"] img             the image itself
- [data-mydes="heading"], [data-mydes="title"]   headline text
- [data-mydes="btn"]                  text buttons
Palette variables available: --m-bg, --m-surface, --m-surface2, --m-fg, --m-ink, --m-accent, --m-border.
Ambient layers must declare: content:''; position:fixed; inset:0; pointer-events:none; z-index:2147483630 (all with !important). Overlays on a media-box must declare: content:''; position:absolute; inset:0; pointer-events:none.
 
CORRECTNESS RULES (they prevent broken output; they never dictate design)
1. Layered backgrounds: background-image, background-size, background-position and background-repeat must list the same number of layers. Never place size, position or repeat tokens inside background-image. The bottom-most layer of a surface is a full-surface fill; decorative layers above it use finite sizes and positions.
2. Animation: !important beats animation, so no declaration may carry !important on any property that an animation or transition drives on that element. Every @keyframes you define must be attached by an animation declaration to a live selector and must visibly change something. Animate only layers that repeat or that stay on screen. A looping shift of a tiled layer must equal a whole multiple of that layer's tile size, otherwise the loop snaps.
3. Ambient layers must be visible but must never reduce text legibility or read as full-screen static interference; do not use repeating-radial-gradient on them. Angles in conic gradients are measured from the top of the screen, so a gradient anchored on the top edge only shows if it sweeps through the lower half.
4. Geometry safety: never change display, flex-direction, width, height, aspect-ratio, margin or position of cards, buttons or their children (your own pseudo-elements and overlays excepted). Never add padding or border to bare <a> or <button> selectors. Ornaments near rounded corners must sit inside the curve or they get clipped.
5. Use !important on declarations that must beat host styles, except where rule 2 forbids it.
6. If you vary elements by position (for example with :nth-child), restate both border and shadow in every variant so no base value clashes.
7. Inline vector data-URIs: encode # as %23, use fixed pixel tile sizes (never percentage), and never put !important inside the url().
8. Generated content may contain glyphs or words. Write every non-ASCII glyph as a CSS backslash hex escape of a Basic Multilingual Plane character. A pseudo-element that carries content must not push or reflow its host.
9. Typefaces: any Google Font by name, always followed by a generic fallback. Fonts are loaded automatically.
10. Keep text readable against every surface you create, whatever the decoration.
11. The device is a phone: be economical with effects that are expensive when repeated across many items (backdrop blur, large blurred shadows, large filters).
""".trimIndent()

        fun isNvidiaModel(model: String): Boolean =
            model.startsWith("nvidia/", ignoreCase = true) || model.contains("nemotron", ignoreCase = true)

        /**
         * Returns true ONLY when a frontier model (`nemotron-3-ultra` or `gemini-3.8-flash`) is selected.
         * Returns false when `gemini-3.5-flash-lite` is selected.
         */
        fun isFrontierSynthesisModel(model: String): Boolean =
            isNvidiaModel(model) || model.equals(MODEL_GEMINI_38_FLASH, ignoreCase = true)

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
            val saved = prefs.getString("reasoning_level_$model", null)
            return if (!saved.isNullOrBlank()) saved else ReasoningModulatorConfig.getDefaultModeForModel(model)
        }

        fun setReasoningLevel(context: Context, model: String, level: String) {
            val prefs = context.getSharedPreferences("mydes_prefs", Context.MODE_PRIVATE)
            prefs.edit().putString("reasoning_level_$model", level.lowercase(Locale.US).trim()).apply()
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
        screenshotPath: String? = null
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
        screenshotPath = screenshotPath
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
        onConceptReady: ((bg: String, fg: String, concept: String) -> Unit)? = null,
        screenshotPath: String? = null
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
        onConceptReady = onConceptReady,
        screenshotPath = screenshotPath
    )

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
        val geminiApiKey = getApiKey(context)
        val nvidiaApiKey = getNvidiaApiKey(context)
        if (geminiApiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("GEMINI_API_KEY is not set."))
        }

        val synthesisModel = getSynthesisModel(context)
        val isNemotron = isNvidiaModel(synthesisModel)
        val isFrontier = isFrontierSynthesisModel(synthesisModel)
        if (isNemotron && nvidiaApiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("NVIDIA NIM API key is not set."))
        }

        val modPrompt = buildString {
            appendLine("MODIFICATION REQUEST: \"$userModificationPrompt\"")
            appendLine("CURRENT CONCEPT: $activeConcept")
            appendLine("CURRENT ACTIVE CSS:")
            appendLine(activeCss.take(3_000))
        }

        val brief = designBrief(
            context = context,
            prompt = "$activeConcept — Modification: $userModificationPrompt",
            websiteName = websiteName,
            websiteUrl = websiteUrl,
            pageOutlineJson = null,
            apiKey = geminiApiKey,
            forFrontierModel = isFrontier,
            onThinkingUpdate = onThinkingUpdate
        ).getOrNull()

        if (brief != null) {
            onConceptReady?.invoke(brief.bg, brief.fg, brief.idea)
        }

        val targetSysPrompt = if (isFrontier) UI_SYNTHESIS_SYSTEM_PROMPT_FRONTIER else UI_SYNTHESIS_SYSTEM_PROMPT_LITE
        val userContent = buildString {
            appendLine(modPrompt)
            if (brief != null) {
                appendLine()
                appendLine("UPDATED VISUAL SPECIFICATION:")
                appendLine(brief.raw)
            }
            appendLine()
            appendLine("Return the updated UIBlueprint JSON now.")
        }

        try {
            val (raw, _) = if (isNemotron) {
                callNvidiaNimStream(
                    model = synthesisModel,
                    apiKey = nvidiaApiKey,
                    systemInstruction = targetSysPrompt,
                    userContent = userContent,
                    temperature = 0.45f,
                    topP = 0.95f,
                    maxTokens = NIM_MAX_TOKENS,
                    onThoughtChunk = { chunk -> onThinkingUpdate?.invoke("Modifying CSS...", chunk, false) },
                    onOutputChunk = { _ -> onThinkingUpdate?.invoke("Writing Modified CSS...", "", false) }
                )
            } else {
                val req = GeminiRequest(
                    contents = listOf(Content(parts = listOf(Part(text = userContent)))),
                    systemInstruction = Content(listOf(Part(text = targetSysPrompt))),
                    generationConfig = GenerationConfig(
                        temperature = if (isFrontier) 0.45f else 0.3f,
                        topP = 0.95f,
                        maxOutputTokens = 8192,
                        responseMimeType = "application/json",
                        thinkingConfig = ThinkingConfig(thinkingBudget = if (isFrontier) 1024 else 512)
                    )
                )
                callGeminiStream(
                    synthesisModel,
                    req,
                    geminiApiKey,
                    onThoughtChunk = { chunk -> onThinkingUpdate?.invoke("Modifying CSS...", chunk, false) },
                    onOutputChunk = { _ -> onThinkingUpdate?.invoke("Writing Modified CSS...", "", false) }
                )
            }

            val parsed = UIBlueprint.parse(raw, userModificationPrompt)?.copy(
                concept = brief?.idea ?: activeConcept,
                designSpec = brief?.raw ?: ""
            ) ?: return@withContext Result.failure(IllegalStateException("Failed to parse modified blueprint JSON."))

            val domain = extractSafeDomain(websiteUrl, websiteName)
            storeFor(context).put(userModificationPrompt, parsed, domain, remember = true)
            onThinkingUpdate?.invoke("Modification Complete", "", true)
            Result.success(parsed)
        } catch (e: Exception) {
            Result.failure(Exception(describe(e), e))
        }
    }

    // ───── Pass 1: Dual-Tier Visual Architect (Always runs on gemini-3.5-flash-lite) ─────

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
                responseMimeType = "application/json",
                thinkingConfig = ThinkingConfig(thinkingBudget = 256)
            )
        )
        return callGeminiStream(model, req, apiKey, onThoughtChunk).first
    }

    /**
     * Pass 1 ALWAYS runs on `CONCEPT_MODEL` (`gemini-3.5-flash-lite`), but adapts its prompt
     * based on `forFrontierModel`:
     * - When `forFrontierModel == false` (`gemini-3.5-flash-lite` selected): Uses the fast, dense
     *   token-directive prompt (`UI_CONCEPT_SYSTEM_PROMPT_LITE`).
     * - When `forFrontierModel == true` (`nemotron-3-ultra` or `gemini-3.8-flash` selected): Uses
     *   `UI_CONCEPT_SYSTEM_PROMPT_FRONTIER` which describes rich, creative visual & artistic goals
     *   with ZERO literal CSS property values so the frontier model's creativity is never capped!
     */
    private suspend fun designBrief(
        context: Context,
        prompt: String,
        websiteName: String,
        websiteUrl: String,
        pageOutlineJson: String?,
        apiKey: String,
        forFrontierModel: Boolean,
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null
    ): Result<ConceptBrief> {
        val activeConceptPrompt = if (forFrontierModel) UI_CONCEPT_SYSTEM_PROMPT_FRONTIER else UI_CONCEPT_SYSTEM_PROMPT_LITE
        val modeLabel = if (forFrontierModel) "Creative Vision (Frontier Tier B)" else "Art Direction (Compact Tier A)"
        onThinkingUpdate?.invoke("Pass 1: $modeLabel ($CONCEPT_MODEL)", "Designing world, materials, and element decisions...", false)

        val input = buildString {
            appendLine("USER REQUEST: \"$prompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            if (!pageOutlineJson.isNullOrBlank()) {
                appendLine("LIVE PAGE ROLE COUNTS: ${pageOutlineJson.take(800)}")
            }
            val recent = storeFor(context).recentConcepts(limit = 3)
            if (recent.isNotEmpty()) {
                appendLine("RECENT THEMES SHIPPED (differ in material, geometry, rhythm, and motion):")
                recent.forEach { appendLine("- $it") }
            }
            appendLine("Write the specification JSON now.")
        }

        val logInput = buildString {
            appendLine("=== TARGET MODEL ===")
            appendLine(CONCEPT_MODEL)
            appendLine()
            appendLine("=== PASS TYPE ===")
            appendLine("Pass 1: $modeLabel")
            appendLine()
            appendLine("=== SYSTEM INSTRUCTION ===")
            appendLine(activeConceptPrompt)
            appendLine()
            appendLine("=== USER CONTENT & PROMPT ===")
            append(input)
        }

        return try {
            val raw = callForText(
                CONCEPT_MODEL,
                apiKey,
                activeConceptPrompt,
                listOf(Part(text = input)),
                if (forFrontierModel) 1.05f else 0.95f,
                0.95f,
                if (forFrontierModel) 2400 else 1800,
                onThoughtChunk = { chunk ->
                    onThinkingUpdate?.invoke("Pass 1: Visual Architecture", chunk, false)
                }
            )
            val json = JSONObject(extractJsonObject(raw) ?: throw IllegalStateException("no JSON in brief: ${raw.take(200)}"))
            val idea = json.optString("idea", "").trim()
            if (idea.isBlank()) throw IllegalStateException("brief has no idea: ${raw.take(200)}")

            val paletteText = json.optString("palette", "").trim()
            val typeText = json.optString("type", "").trim()
            val fingerprint = json.optString("fingerprint", "").trim().take(120)

            val hexMatches = Regex("#[0-9a-fA-F]{6}").findAll(paletteText).map { it.value }.toList()
            val extractedBg = hexMatches.getOrNull(0) ?: "#0a0c10"
            val extractedFg = hexMatches.getOrNull(3) ?: "#e2e8f0"

            // Format the v22 `elements` array ({target, decisions: [...]}) into a clean, checkable ledger for Pass 2 & Pass 3
            val directions = buildString {
                appendLine("IDEA: $idea")
                if (paletteText.isNotBlank()) appendLine("PALETTE: $paletteText")
                if (typeText.isNotBlank()) appendLine("TYPE: $typeText")
                if (fingerprint.isNotBlank()) appendLine("FINGERPRINT: $fingerprint")

                val elementsArr = json.optJSONArray("elements")
                if (elementsArr != null && elementsArr.length() > 0) {
                    appendLine()
                    appendLine("ELEMENT DECISIONS:")
                    var decisionCounter = 1
                    for (i in 0 until elementsArr.length()) {
                        val elObj = elementsArr.optJSONObject(i) ?: continue
                        val target = elObj.optString("target", "").trim()
                        val decArr = elObj.optJSONArray("decisions")
                        if (target.isNotBlank() && decArr != null && decArr.length() > 0) {
                            appendLine("[$target]")
                            for (d in 0 until decArr.length()) {
                                val dec = decArr.optString(d, "").trim()
                                if (dec.isNotBlank()) {
                                    appendLine("  D${decisionCounter++}: $dec")
                                }
                            }
                        }
                    }
                }
            }.trim()

            val brief = ConceptBrief(
                raw = directions,
                idea = idea.take(300),
                fingerprint = fingerprint,
                bg = extractedBg,
                fg = extractedFg
            )
            ApiRequestLogger.logRequest(context, "conceptBrief ($CONCEPT_MODEL)", websiteName, websiteUrl, prompt, logInput, json.toString(2), true)
            Result.success(brief)
        } catch (e: Exception) {
            val errMsg = describe(e)
            ApiRequestLogger.logRequest(context, "conceptBrief ($CONCEPT_MODEL)", websiteName, websiteUrl, prompt, logInput, errMsg, false)
            Result.failure(Exception("Concept pass failed ($CONCEPT_MODEL): $errMsg", e))
        }
    }

    // ───── Pass 2 (Dual-Tier CSS Synthesis) & Pass 3 (Conditional Self-Healing Reviewer) ─────

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
        onConceptReady: ((bg: String, fg: String, concept: String) -> Unit)? = null,
        screenshotPath: String? = null
    ): Result<UIBlueprint> = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()
        if (cleanPrompt.isEmpty()) return@withContext Result.failure(IllegalArgumentException("Empty prompt"))

        val domain = extractSafeDomain(websiteUrl, websiteName)

        if (!isReviewPass && screenshotBase64 == null && auditJson == null && !forceFresh) {
            storeFor(context).get(cleanPrompt, domain)?.let { return@withContext Result.success(it) }
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
        val isFrontier = !isReview && isFrontierSynthesisModel(synthesisModel)
        val isNemotronSynthesis = !isReview && isNvidiaModel(synthesisModel)
        val activeModel = if (isReview) REVIEW_MODEL else synthesisModel

        if (isNemotronSynthesis && nvidiaApiKey.isBlank()) {
            return@withContext Result.failure(IllegalStateException("NVIDIA NIM API key is not set. Please enter your NVIDIA API key (nvapi-...) in Settings."))
        }

        val brief: ConceptBrief? = if (isReview) {
            null
        } else {
            val b = designBrief(
                context = context,
                prompt = cleanPrompt,
                websiteName = websiteName,
                websiteUrl = websiteUrl,
                pageOutlineJson = pageOutlineJson,
                apiKey = geminiApiKey,
                forFrontierModel = isFrontier,
                onThinkingUpdate = onThinkingUpdate
            ).getOrElse { conceptError -> return@withContext Result.failure(conceptError) }
            onConceptReady?.invoke(b.bg, b.fg, b.idea)
            b
        }

        if (!isReview) {
            val modelLabel = if (isNemotronSynthesis) "Nemotron 3 Ultra 550B (NVIDIA NIM)" else activeModel
            onThinkingUpdate?.invoke(
                "Pass 2: Pure CSS Synthesis ($modelLabel)",
                if (isFrontier) "Crafting multi-layered shaders, SVG ornaments & animations..." else "Writing Pure CSS...",
                false
            )
        } else {
            onThinkingUpdate?.invoke("Pass 3: QA Review ($activeModel)", "Inspecting render telemetry & screenshot...", false)
        }

        val activeSynthesisPrompt = if (isFrontier) UI_SYNTHESIS_SYSTEM_PROMPT_FRONTIER else UI_SYNTHESIS_SYSTEM_PROMPT_LITE

        val userContextText = if (isReview) buildString {
            appendLine("REVIEW PASS")
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            appendLine("PALETTE: dark=${previous!!.dark}, bg=${previous.bg}, surface=${previous.surface}, surface2=${previous.surface2}, fg=${previous.fg}, ink=${previous.ink}, accent=${previous.accent}")
            if (previous.designSpec.isNotBlank()) {
                appendLine()
                appendLine("ORIGINAL SPECIFICATION:")
                appendLine(previous.designSpec.take(3_200))
            }
            appendLine()
            appendLine("LIVE TELEMETRY:")
            appendLine(auditJson?.takeIf { it.isNotBlank() && it != "null" }?.take(2_000) ?: "clean")
            appendLine()
            appendLine("CURRENT CSS:")
            appendLine(previous.customCss)
            appendLine()
            appendLine("Return the review JSON now.")
        } else buildString {
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $websiteName ($websiteUrl)")
            if (!pageOutlineJson.isNullOrBlank()) {
                appendLine("PAGE OUTLINE: ${pageOutlineJson.take(800)}")
            }
            appendLine()
            appendLine("SPECIFICATION OF VISUAL DECISIONS:")
            appendLine(brief!!.raw)
            appendLine()
            appendLine("Produce the JSON response now.")
        }

        val parts = mutableListOf<Part>(Part(text = userContextText))
        if (isReview && !screenshotBase64.isNullOrBlank()) {
            parts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = screenshotBase64)))
        }

        fun buildReq(jsonMode: Boolean) = GeminiRequest(
            contents = listOf(Content(parts = parts)),
            systemInstruction = Content(listOf(Part(text = if (isReview) UI_REVIEW_SYSTEM_PROMPT else activeSynthesisPrompt))),
            generationConfig = GenerationConfig(
                temperature = if (isReview) 0.2f else if (isFrontier) 0.55f else 0.3f,
                topP = 0.95f,
                maxOutputTokens = if (isReview) 2048 else if (isFrontier) 8192 else 6144,
                responseMimeType = if (jsonMode) "application/json" else null,
                thinkingConfig = ThinkingConfig(thinkingBudget = if (isReview) 512 else if (isFrontier) 1024 else 512)
            )
        )

        val logType = if (isReview) "reviewBlueprint ($activeModel)" else "buildBlueprint ($activeModel)"
        val requestJson = buildString {
            appendLine("=== TARGET MODEL ===")
            appendLine(if (isNemotronSynthesis) "$activeModel (NVIDIA NIM)" else activeModel)
            appendLine()
            appendLine("=== PIPELINE TIER ===")
            appendLine(if (isReview) "Pass 3: Delta Review" else if (isFrontier) "Tier B: Uncapped Frontier Synthesis" else "Tier A: Fast Lite Synthesis")
            appendLine()
            appendLine("=== SYSTEM INSTRUCTION ===")
            appendLine(if (isReview) UI_REVIEW_SYSTEM_PROMPT else activeSynthesisPrompt)
            appendLine()
            appendLine("=== USER CONTENT & PROMPT ===")
            append(userContextText)
            if (isReview && !screenshotBase64.isNullOrBlank()) {
                appendLine()
                appendLine("=== ATTACHED SCREENSHOT ===")
                appendLine("[JPEG Screenshot: ${screenshotBase64.length / 1024} KB base64]")
            }
        }

        try {
            val (raw, rawThoughts) = if (isNemotronSynthesis) {
                callNvidiaNimStream(
                    model = activeModel,
                    apiKey = nvidiaApiKey,
                    systemInstruction = activeSynthesisPrompt,
                    userContent = userContextText,
                    temperature = 0.55f,
                    topP = 0.95f,
                    maxTokens = NIM_MAX_TOKENS,
                    onThoughtChunk = { chunk ->
                        onThinkingUpdate?.invoke("Pass 2: Frontier CSS Architecture", chunk, false)
                    },
                    onOutputChunk = { _ ->
                        onThinkingUpdate?.invoke("Pass 2: Writing Artisanal Pure CSS ($activeModel)", "", false)
                    }
                )
            } else {
                callGeminiStream(
                    activeModel,
                    buildReq(true),
                    geminiApiKey,
                    onThoughtChunk = { chunk ->
                        onThinkingUpdate?.invoke(
                            if (isReview) "Pass 3: QA Audit" else "Pass 2: CSS Engineering",
                            chunk,
                            false
                        )
                    },
                    onOutputChunk = { _ ->
                        onThinkingUpdate?.invoke(
                            if (isReview) "Pass 3: Stitching Fixes" else "Pass 2: Writing Pure CSS",
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
                val err = "Model returned invalid UIBlueprint JSON (finish_reason=${lastNimFinishReason ?: "n/a"})."
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
                if (isReview) {
                    onThinkingUpdate?.invoke("Theme Synthesis Complete", "", true)
                }
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

    // ───── v22 System Prompts: Dual-Tier (Tier A = Fast Lite, Tier B = Uncapped Frontier) ─────

    private val UI_REVIEW_SYSTEM_PROMPT = """
You are the Pure-CSS QA Reviewer for a browser that restyles websites. You receive the original specification (when present), live telemetry measured on the painted page, the current CSS, and sometimes a screenshot. Repair what is broken or missing. Do not redesign.
 
$SHARED_CAPABILITY_CONTRACT
 
REVIEW TASKS
1. Telemetry: for every entry in animationWarnings, pseudoOverlays, clashingSurfaces, lowContrastText and squishedColumns, write CSS that fixes the root cause, using the theme's own palette variables and specificity high enough to beat the host.
2. Specification fidelity: treat every decision in the specification as a requirement. If the current CSS lacks one, or the telemetry or screenshot shows it is not visibly painting (no content, zero size, buried under another layer, mismatched layer counts, keyframes never attached, overridden by the host), write CSS that realises it in the specification's own terms.
3. Preserve character: change the minimum needed. Never flatten, remove or replace the existing design to cure a defect, and never add design ideas the specification does not contain.
4. fixCss is appended after the current CSS and cannot remove an !important that already exists there. If an animation or other effect is blocked by an existing !important declaration, return the full corrected stylesheet in replacedCss instead.
 
OUTPUT: ONLY valid JSON:
{"problems": ["short list of defects found"], "fixCss": "targeted CSS overrides, or empty", "replacedCss": "full corrected stylesheet, or empty"}
""".trimIndent()
 
    // ───────────────────────── TIER A (fast, compact) ─────────────────────────
 
    private val UI_CONCEPT_SYSTEM_PROMPT_LITE = """
You are the Art Director for a browser that restyles any website into a distinct designed world. You write no CSS. You decide what the world looks like; a separate compiler turns your decisions into styling.
 
$SHARED_UI_PARTS
 
HOW TO DECIDE
- Read the request and the target site, then commit to one governing idea: a way of seeing the world, covering what it is made of, where its light comes from, how its geometry behaves and how it moves. Derive every part from that idea so the result feels authored, not assembled.
- If recent themes are listed, differ from them in material, geometry, rhythm and motion, most importantly in colour.
- Nothing is mandatory except covering the listed parts. You choose which parts carry rich ornament and which stay quiet, and you invent whatever extra layers, motifs, textures or effects the idea needs, as many or as few as it deserves. Different requests must produce different structures, not the same structure recoloured.
- Every decision must be concrete enough to picture exactly: shapes, counts, sizes, directions, colours as hex, pace, and what is still versus moving. No mood words without visual meaning. No CSS property names or syntax.
- Everything must be achievable within the limits above.
- If you use a glyph as an ornament, give it as the 4-digit hex code of a Basic Multilingual Plane character, never an emoji.
 
OUTPUT: ONLY valid JSON, compact, about 450 words at most:
{
  "idea": "one sentence",
  "palette": "hex colours with roles: canvas, surface, raised surface, text, headline ink, primary accent, plus any further colours the idea needs",
  "type": "heading and body typefaces (any Google Font), casing and spacing",
  "elements": [
    {"target": "a listed part, or an extra layer or motif you invent and where it attaches", "decisions": ["one concrete visual decision per string"]}
  ],
  "fingerprint": "at most 10 words naming what makes this world distinct"
}
Include one entry for every listed part (a part that simply inherits the world quietly still gets one decision), plus every invented element the idea needs. Each decision string must stand alone so it can be built and checked individually.
""".trimIndent()
 
    private val UI_SYNTHESIS_SYSTEM_PROMPT_LITE = """
You are the Pure-CSS Compiler. A specification of visual decisions follows in the user message. Produce CSS that makes every one of them visible on the page.
 
$SHARED_CAPABILITY_CONTRACT
 
METHOD
- Work through the specification element by element and decision by decision. Every decision must end up as CSS that actually works. Do not skip small ones, and do not merge several decisions into one weaker effect.
- You compile, you do not redesign: add no decorative ideas of your own, no stock details the specification did not ask for. Where it is silent on a detail, continue its own logic.
- If a decision cannot be expressed within the contract, build the closest faithful equivalent and list that decision under "unrealized".
- Parts the specification leaves quiet still receive a clean, coherent treatment from the palette so no part of the page keeps host colours.
- Before finishing, check each decision: does a rule exist for it, does it have the content, size and layers needed to paint, and is every referenced keyframe defined and attached?
 
OUTPUT: ONLY valid JSON, no markdown fences:
{
  "name": "short title",
  "dark": true or false,
  "bg": "#rrggbb", "surface": "#rrggbb", "surface2": "#rrggbb", "fg": "#rrggbb", "ink": "#rrggbb", "accent": "#rrggbb",
  "remapSurfaces": true,
  "customCss": "complete CSS implementing every decision",
  "unrealized": ["decisions that could not be built, or empty"],
  "concept": "one sentence"
}
""".trimIndent()
 
    // ───────────────────────── TIER B (frontier, uncapped) ─────────────────────────
 
    private val UI_CONCEPT_SYSTEM_PROMPT_FRONTIER = """
You are the Creative Visionary for a frontier CSS synthesis model. You write no CSS and use no CSS vocabulary: no property names, function names, units or syntax. Describe what a viewer sees and feels (materials, light, texture, geometry, rhythm, motion) richly enough that a skilled craftsperson can build it with full freedom.
 
$SHARED_UI_PARTS
 
HOW TO DECIDE
- Read the request and the target site, then commit to one governing idea: what this world is made of, where its light comes from, how its geometry behaves, how it moves. Derive every part from that idea so the result feels authored, not assembled.
- If recent themes are listed, differ from them in material, geometry, rhythm and motion, not only in colour.
- Nothing is mandatory except covering the listed parts. You choose which parts carry rich ornament and which stay quiet, and you invent whatever extra layers, motifs, textures or effects the idea needs, as many or as few as it deserves. Different requests must produce different structures, not the same structure recoloured.
- Make every decision specific enough to picture exactly: shapes, counts, scale in plain words, direction, pace in plain words, what is still and what moves, and where light falls. Colours are given as hex. Avoid mood words that have no visual meaning.
- Everything must be achievable within the limits above.
- If you use a glyph as an ornament, give it as the 4-digit hex code of a Basic Multilingual Plane character, never an emoji.
 
OUTPUT: ONLY valid JSON, about 650 words at most:
{
  "idea": "one vivid sentence",
  "palette": "hex colours with roles: canvas, surface, raised surface, text, headline ink, primary accent, plus any further colours the idea needs",
  "type": "heading and body typefaces (any Google Font), typographic mood",
  "elements": [
    {"target": "a listed part, or an extra layer or motif you invent and where it attaches", "decisions": ["one specific visual decision per string"]}
  ],
  "fingerprint": "at most 10 words naming what makes this world distinct"
}
Include one entry for every listed part, plus every invented element the idea needs. Each decision string must stand alone so it can be built and checked individually.
""".trimIndent()
 
    private val UI_SYNTHESIS_SYSTEM_PROMPT_FRONTIER = """
You are a Frontier CSS Artisan. The Art Director's vision arrives as separate decisions in sensory terms with no CSS. The vision tells the looks; you build them, and you are uncapped in how well you build them.
 
$SHARED_CAPABILITY_CONTRACT
 
CRAFT
- Use the whole CSS toolbox where a decision calls for it (layered gradients, masks, filters, blend modes, shadows, clip shapes, inline vector imagery, pseudo-elements, keyframes), never by habit. Choose the construction that best fits each decision. Two different visions must lead to visibly different constructions, not one recipe reskinned.
- Build real material, not flat approximations: depth, lighting, texture and edge detail where the vision implies them.
- Honour every decision. Add detail only where it deepens a decision. Do not introduce motifs the vision did not contain. Where the vision is silent, extend its own logic.
- Give every part considered treatment so the whole page reads as one coherent world and no part keeps host colours.
- Work decision by decision and check each one before finishing: a rule exists, it has the content, size and layers needed to paint, and every keyframe is defined and attached.
- If a decision cannot be expressed within the contract, build the closest faithful equivalent and list it under "unrealized".
 
OUTPUT: ONLY valid JSON, no markdown fences:
{
  "name": "short title",
  "dark": true or false,
  "bg": "#rrggbb", "surface": "#rrggbb", "surface2": "#rrggbb", "fg": "#rrggbb", "ink": "#rrggbb", "accent": "#rrggbb",
  "remapSurfaces": true,
  "customCss": "complete, richly crafted CSS implementing every decision",
  "unrealized": ["decisions that could not be built, or empty"],
  "concept": "one sentence"
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

                val result = fullText.toString()
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

    private val NIM_MAX_TOKENS = 8192
    private fun nimReasoningKwargs(): JSONObject = JSONObject()
        .put("enable_thinking", true)
        .put("medium_effort", true)
    @Volatile private var lastNimFinishReason: String? = null

    private suspend fun callNvidiaNimStream(
        model: String,
        apiKey: String,
        systemInstruction: String,
        userContent: String,
        temperature: Float = 0.55f,
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

// ───────────────────── Live WebView Controller (Conditional Pass 3) ─────────────────────

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
            screenshotBase64 = null,
            forceFresh = true,
            isReviewPass = false
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

        // 2. Conditional Pass 3: Test live DOM. Skip Pass 3 completely if 0 defects found!
        if (includeScreenshot) {
            delay(220)
            webView.evaluateJsSuspend(MydesRuntime.SNAPSHOT_FRAME_1_JS)
            delay(280)
            val auditJson = webView.evaluateJsSuspend(MydesRuntime.VISUAL_AND_DOM_AUDIT_JS)

            var needsRepair = false
            if (!auditJson.isNullOrBlank() && auditJson != "null") {
                try {
                    val auditObj = JSONObject(auditJson)
                    val clashing = auditObj.optJSONArray("clashingSurfaces")?.length() ?: 0
                    val animWarns = auditObj.optJSONArray("animationWarnings")?.length() ?: 0
                    val lowContrast = auditObj.optJSONArray("lowContrastText")?.length() ?: 0
                    if (clashing > 0 || animWarns > 0 || lowContrast > 0) {
                        needsRepair = true
                    }
                } catch (_: Exception) {
                    needsRepair = true
                }
            }

            if (needsRepair) {
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
