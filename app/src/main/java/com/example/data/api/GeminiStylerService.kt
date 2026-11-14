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
 * v23 - "Interactive Set-of-Mark (SoM) YOLO Inspector, Multi-Level Sibling Propagation & Co-Located Micro-RAG"
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

// ───────────────────── In-Memory Co-Located Micro-RAG (v23) ─────────────────────

object CssRuleRag {

    val SHARED_CORE_CONTRACT = """
RUNTIME CONTRACT
Output valid CSS injected into the live page. No script, canvas, or added DOM elements: style only existing `[data-mydes="..."]` hooks, `html`, `body`, and their `::before`/`::after` pseudo-elements.
Palette variables available: `--m-bg`, `--m-surface`, `--m-surface2`, `--m-fg`, `--m-ink`, `--m-accent`, `--m-border`.
Global Rules:
1. Use `!important` on static properties so they beat host styles, EXCEPT on properties animated by `@keyframes` or `transition` (`!important` blocks animations).
2. Keep text readable against every surface you create. Any Google Font may be referenced by name with a generic fallback.
3. Each `[target]` below includes co-located `RULES FOR THIS ELEMENT` and `@TECHNIQUE` anchors. Obey them strictly while compiling that element's `D1..Dn` decisions.
4. NEVER output `content: attr(data-mydes)` or diagnostic dashed inspector outlines in your CSS.
""".trimIndent()

    private val TARGET_RULES = mapOf(
        "CANVAS" to "Selector: `html, body`. Because `body` covers `html`, apply `html { background-color: var(--m-bg) !important; }` and place all page canvas `background-image` layers on `body` (never `html` alone).",
        "AMBIENT" to "Selectors: `body::before` (ambient layer A), `body::after` (ambient layer B). MUST declare: `content: '' !important; position: fixed !important; inset: 0 !important; pointer-events: none !important; z-index: 2147483630 !important; mix-blend-mode: screen !important;`. Keep layer color alphas <= 0.25 so text is never darkened. Never use `repeating-radial-gradient`. Top-anchored `conic-gradient(from ... at 50% 0%)` must sweep through 150deg..210deg (South) to be visible. Never animate `opacity` to `0` on a layer carrying continuous particles (keep opacity 0.35..0.75).",
        "HEADER" to "Selector: `[data-mydes=\"header\"]` (and `[data-mydes=\"header\"] svg` for icons). For inner nav items use `[data-mydes=\"nav-item\"]` and `[data-mydes=\"nav-item\"][data-mydes-state=\"active\"]`. Never set fixed `width`/`height` on `nav-item` or cover its text with opaque `::after` shapes.",
        "BOTTOM_BAR" to "Selectors: `[data-mydes=\"bottom-bar\"]`, `[data-mydes=\"nav-item\"]`, and `[data-mydes=\"nav-item\"][data-mydes-state=\"active\"]`. Never set fixed `width` or `height` on `[data-mydes=\"nav-item\"]` or cover its text with opaque `::after` shapes; style its border, background, or underline so text/icons remain 100% legible.",
        "SEARCH" to "Selectors: `[data-mydes=\"search\"]` AND `[data-mydes=\"search\"] input, [data-mydes=\"search\"] textarea`. Style both the container and inner input (`background: transparent !important; color: var(--m-fg) !important;`) so no host background color leaks.",
        "CHIP" to "Selectors: `[data-mydes=\"chip\"]` and `[data-mydes=\"chip\"][data-mydes-state=\"active\"]` for the selected pill. Never set fixed `width`/`height` or alter `display`/`position`.",
        "CARD_MEDIA" to "Selectors: `[data-mydes=\"card\"]`, `[data-mydes=\"media-box\"]`, `[data-mydes=\"card\"] img`. Overlays on thumbnails MUST use `[data-mydes=\"media-box\"]::after` (NEVER `::before`, which sits behind the image) with `content: '' !important; position: absolute !important; inset: 0 !important; pointer-events: none !important; z-index: 2 !important;`. Never change `display`, `flex-direction`, `width`, `height`, `aspect-ratio`, `margin`, or `position` of cards. Avoid `backdrop-filter: blur()` on cards. If varying cards with `:nth-child()`, restate both `border` and `box-shadow` in every variant.",
        "TEXT" to "Selectors: `[data-mydes=\"heading\"]` (card headlines) and `[data-mydes=\"title\"]` (page titles). Reference any Google Font followed by a generic fallback; keep contrast crisp against surfaces.",
        "BTN" to "Selector: `[data-mydes=\"btn\"]`. Never set fixed `width` or `height`, never change `display` or `position`, and never add padding/border to bare `<a>` or `<button>` tag selectors."
    )

    private val TECH_FULL_RULES = mapOf(
        "ANIMATION" to "@ANIMATION Rule: NEVER put `!important` on any property driven by `@keyframes` or `transition` (`background-position`, `opacity`, `transform`, `filter`, `border-color`, `box-shadow`). Every `@keyframes` must be attached to a selector. Looping shifts of tiled backgrounds must equal an exact integer multiple of that layer's `background-size` tile. Never attach two `@keyframes` animating the same property to one element.",
        "SVG" to "@SVG Rule: Draw recognizable shapes using `url(\"data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' viewBox='...'>...</svg>\")`. Encode every `#` as `%23`, use fixed pixel `background-size` (never `100% 100%`), never put `!important` inside `url()`, and give `::before`/`::after` ornaments explicit `width` and `height` inside rounded corners so they are not clipped.",
        "LAYERED_BG" to "@LAYERED_BG Rule: `background-image`, `background-size`, `background-position`, and `background-repeat` must list the exact same number of comma-separated layers. Never place size, position, or repeat tokens inside `background-image`.",
        "GLYPH" to "@GLYPH Rule: Write any non-ASCII glyph in `content:` as a 4-digit BMP CSS hex escape (e.g. `\\2726`), never raw emojis or 5-digit escapes. Pseudo-elements with `content` must use `position: absolute` or `pointer-events: none` so they never reflow host layout."
    )

    private val TECH_MICRO_ANCHORS = mapOf(
        "ANIMATION" to "@ANIMATION (no `!important` on animated props; shift = integer multiple of tile size)",
        "SVG" to "@SVG (encode `#` as `%23`; fixed px `background-size`; explicit `width`/`height` on pseudo-ornaments)",
        "LAYERED_BG" to "@LAYERED_BG (match comma-separated layer counts across image/size/position/repeat)",
        "GLYPH" to "@GLYPH (4-digit BMP hex escape only; `position: absolute` + `pointer-events: none`)"
    )

    data class ElementSpecItem(
        val target: String,
        val decisions: List<String>,
        val targetRuleKeys: Set<String>,
        val techRuleKeys: Set<String>
    )

    fun parseElementSpec(target: String, decisions: List<String>, rawTags: List<String>): ElementSpecItem {
        val targetKeys = LinkedHashSet<String>()
        val techKeys = LinkedHashSet<String>()

        for (raw in rawTags) {
            val k = raw.trim().uppercase(Locale.US).removePrefix("RULE_").removePrefix("TECH_")
            if (k in TARGET_RULES) targetKeys.add(k)
            if (k in TECH_FULL_RULES) techKeys.add(k)
        }

        if (targetKeys.isEmpty()) {
            val t = target.lowercase(Locale.US)
            when {
                t.contains("canvas") || t.contains("background") || t.contains("body") -> targetKeys.add("CANVAS")
                t.contains("ambient") || t.contains("layer a") || t.contains("layer b") || t.contains("overlay") -> targetKeys.add("AMBIENT")
                t.contains("header") || t.contains("top bar") -> targetKeys.add("HEADER")
                t.contains("bottom") || t.contains("footer") || t.contains("nav") -> targetKeys.add("BOTTOM_BAR")
                t.contains("search") || t.contains("input") -> targetKeys.add("SEARCH")
                t.contains("chip") || t.contains("pill") || t.contains("tab") -> targetKeys.add("CHIP")
                t.contains("card") || t.contains("media") || t.contains("thumb") -> targetKeys.add("CARD_MEDIA")
                t.contains("head") || t.contains("title") || t.contains("text") -> targetKeys.add("TEXT")
                t.contains("button") || t.contains("btn") -> targetKeys.add("BTN")
            }
        }

        if (techKeys.isEmpty()) {
            val combined = decisions.joinToString(" ").lowercase(Locale.US)
            if (combined.contains("animat") || combined.contains("pulse") || combined.contains("drift") || combined.contains("shimmer") || combined.contains("cycle")) {
                techKeys.add("ANIMATION")
            }
            if (combined.contains("svg") || combined.contains("vector") || combined.contains("filigree") || combined.contains("emblem") || combined.contains("motif")) {
                techKeys.add("SVG")
            }
            if (combined.contains("grid") || combined.contains("mesh") || combined.contains("stacked") || combined.contains("multiple") || combined.contains("pattern")) {
                techKeys.add("LAYERED_BG")
            }
            if (combined.contains("glyph") || combined.contains("unicode") || combined.contains("symbol") || combined.contains("stamp")) {
                techKeys.add("GLYPH")
            }
        }

        return ElementSpecItem(target, decisions, targetKeys, techKeys)
    }

    fun buildCoLocatedLedger(items: List<ElementSpecItem>): String = buildString {
        if (items.isEmpty()) return@buildString
        appendLine("ELEMENT DECISIONS & CO-LOCATED RULES:")

        val clustered = items.sortedWith(
            compareByDescending<ElementSpecItem> { it.techRuleKeys.size }
                .thenBy { it.techRuleKeys.sorted().joinToString("+") }
        )

        val emittedTargetRules = HashSet<String>()
        val emittedTechRules = HashSet<String>()
        var decisionCounter = 1

        for (item in clustered) {
            appendLine()
            appendLine("[${item.target}]")
            appendLine("  RULES FOR THIS ELEMENT:")

            if (item.targetRuleKeys.isEmpty()) {
                appendLine("  * Attach only to valid `[data-mydes=\"...\"]` hooks or `body::before`/`body::after` pseudo-elements.")
            } else {
                for (tk in item.targetRuleKeys) {
                    if (emittedTargetRules.add(tk)) {
                        appendLine("  * ${TARGET_RULES[tk]}")
                    } else {
                        appendLine("  * Same selector & layout constraints as @$tk above.")
                    }
                }
            }

            val reusedMicroAnchors = ArrayList<String>()
            for (tech in item.techRuleKeys) {
                if (emittedTechRules.add(tech)) {
                    appendLine("  * ${TECH_FULL_RULES[tech]}")
                } else {
                    TECH_MICRO_ANCHORS[tech]?.let { reusedMicroAnchors.add(it) }
                }
            }
            if (reusedMicroAnchors.isNotEmpty()) {
                appendLine("  * Enforce ${reusedMicroAnchors.joinToString("; ")}.")
            }

            appendLine("  DECISIONS TO IMPLEMENT:")
            for (dec in item.decisions) {
                appendLine("  D${decisionCounter++}: $dec")
            }
        }
    }.trimEnd()

    fun retrieveScopedContractForSpec(auditJson: String?): String {
        val rules = LinkedHashSet<String>()
        rules.add(SHARED_CORE_CONTRACT)

        if (auditJson.isNullOrBlank() || auditJson == "null" || auditJson == "clean") {
            return rules.joinToString("\n")
        }

        val json = try { JSONObject(auditJson) } catch (_: Exception) { null }
        val lower = auditJson.lowercase(Locale.US)

        val hasAnimationDefect = (json?.optJSONArray("animationWarnings")?.let { it.length() > 0 } == true) ||
            (lower.contains("animationwarnings") && !lower.contains("\"animationwarnings\":[]") && !lower.contains("\"animationwarnings\": []"))
        if (hasAnimationDefect) {
            TECH_FULL_RULES["ANIMATION"]?.let { rules.add("- $it") }
        }

        val hasPseudoDefect = run {
            if (json != null) {
                val animWarn = json.optJSONArray("animationWarnings")
                if (animWarn != null) {
                    for (i in 0 until animWarn.length()) {
                        val w = animWarn.optString(i, "").lowercase(Locale.US)
                        if (w.contains("before") || w.contains("after") || w.contains("pseudo") || w.contains("z-index")) return@run true
                    }
                }
                val po = json.optJSONObject("pseudoOverlays")
                if (po != null) {
                    val b = po.optJSONObject("before")
                    val a = po.optJSONObject("after")
                    fun isDefective(p: JSONObject?): Boolean {
                        if (p == null) return false
                        val zi = p.optInt("zIndex", 2147483630)
                        val op = p.optDouble("opacity", 1.0)
                        val anim = p.optString("animationName", "none")
                        val isMoving = p.optBoolean("isActivelyMovingBetweenFrames", true)
                        return zi < 1000 || op < 0.1 || (anim != "none" && !isMoving)
                    }
                    if (isDefective(b) || isDefective(a)) return@run true
                }
            }
            lower.contains("body::before") || lower.contains("body::after") ||
                (lower.contains("pseudooverlays") && (lower.contains("z-index") || lower.contains("did not change") || lower.contains("requires z-index")))
        }
        if (hasPseudoDefect) {
            TARGET_RULES["AMBIENT"]?.let { rules.add("- $it") }
        }

        val hasTextDefect = (json?.optJSONArray("lowContrastText")?.let { it.length() > 0 } == true) ||
            (json?.optJSONArray("clashingSurfaces")?.let { it.length() > 0 } == true) ||
            (lower.contains("lowcontrasttext") && !lower.contains("\"lowcontrasttext\":[]") && !lower.contains("\"lowcontrasttext\": []")) ||
            (lower.contains("clashingsurfaces") && !lower.contains("\"clashingsurfaces\":[]") && !lower.contains("\"clashingsurfaces\": []"))
        if (hasTextDefect) {
            TARGET_RULES["TEXT"]?.let { rules.add("- $it") }
        }

        return rules.joinToString("\n")
    }
}

// ───────────────────── Pure-CSS Sanitizer & Auto-Healer (v23) ─────────────────────

internal fun stripInventedLabels(css: String): String =
    css.replace(
        Regex("""[^{}]*::?(?:before|after)\s*\{[^{}]*content\s*:\s*(["'])(?!data:)[^"'{}]*[A-Za-z]{4,}[^"'{}]*\1[^{}]*\}""", RegexOption.IGNORE_CASE),
        ""
    )

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

    // Strip any diagnostic attr(data-mydes) labels or inspector outlines if the LLM tried to mimic the YOLO HUD
    out = out.replace(
        Regex("""[^{}]*::?(?:before|after)\s*\{[^{}]*content\s*:\s*attr\(\s*data-mydes\s*\)[^{}]*\}""", RegexOption.IGNORE_CASE),
        ""
    )
    out = out.replace(
        Regex("""content\s*:\s*attr\(\s*data-mydes\s*\)\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE),
        ""
    )

    out = out.replace(Regex("""@import\s+url\([^)]+\)\s*;?""", RegexOption.IGNORE_CASE), "")

    // 0. Auto-Healer 2: Fix unclosed [data-mydes="...":pseudo selectors
    out = out.replace(Regex("""\[data-mydes=(['"])([^'"\]]+)\1(?=\s*[:\{,])"""), "[data-mydes=$1$2$1]")

    // 0b. Promote standalone `html { ... background ... }` rules to `html, body { ... }`
    out = out.replace(Regex("""(^|\}|\*\/)\s*html\s*\{([^}]*\bbackground(?:-image)?\s*:[^}]+)\}""", RegexOption.IGNORE_CASE)) { match ->
        val prefix = match.groupValues[1]
        val body = match.groupValues[2]
        "$prefix\nhtml, body{$body}"
    }

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

    // 4b. Auto-Healer 1: Smart Blend-Mode & Alpha Clamping on body::before and body::after
    out = out.replace(Regex("""(body::(?:before|after)[^{]*\{)([^}]+)\}""", RegexOption.IGNORE_CASE)) { match ->
        val sel = match.groupValues[1]
        var body = match.groupValues[2]
        val isDarkVignetteOnly = Regex("""rgba\(\s*0\s*,\s*0\s*,\s*0""").containsMatchIn(body) &&
            !Regex("""rgba\(\s*(?:[5-9]\d|\d{3})""").containsMatchIn(body)

        if (isDarkVignetteOnly) {
            body = body.replace(Regex("""mix-blend-mode\s*:\s*screen\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE), "mix-blend-mode: normal !important;")
        } else if (!Regex("""\bmix-blend-mode\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            body = "mix-blend-mode: screen !important; $body"
        }
        body = body.replace(
            Regex("""\bopacity\s*:\s*0(?:\.0+)?\s*(?:!important)?\s*(?=[;}])""", RegexOption.IGNORE_CASE),
            "opacity: 0.45"
        )
        body = body.replace(
            Regex("""rgba\(\s*(\d{1,2})\s*,\s*(\d{1,2})\s*,\s*(\d{1,2})\s*,\s*(0\.[5-9]\d*|1(?:\.0+)?)\s*\)""", RegexOption.IGNORE_CASE)
        ) { rgbaMatch ->
            val r = rgbaMatch.groupValues[1].toIntOrNull() ?: 0
            val g = rgbaMatch.groupValues[2].toIntOrNull() ?: 0
            val b = rgbaMatch.groupValues[3].toIntOrNull() ?: 0
            if (r < 45 && g < 45 && b < 45) "rgba($r, $g, $b, 0.32)" else rgbaMatch.value
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

    // 6. Fix corrupted UTF-8 / mojibake or 5-digit supplementary emoji escapes (\1F451) inside content: "..."
    out = out.replace(Regex("""content\s*:\s*(["'])([^"']*)\1""")) { match ->
        val quote = match.groupValues[1]
        val inner = match.groupValues[2]
        val fixedHex = inner.replace(Regex("""\\1[fF][0-9a-fA-F]{3}"""), """\\2726""")
        if (fixedHex.isEmpty() || fixedHex.startsWith("\\")) {
            "content: $quote$fixedHex$quote"
        } else {
            val hasMojibake = fixedHex.any { ch ->
                val code = ch.code
                (code in 0xE000..0xF8FF) || (code in 0xFF61..0xFF9F) || (code in 0x4E00..0x9FFF && fixedHex.length <= 4) || code == 0xFFFD
            }
            if (hasMojibake) "content: ${quote}\\2726 ${quote}" else "content: $quote$fixedHex$quote"
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

    // 7b. Auto-Healer 3: Unbury `[data-mydes="media-box"]::before` watermarks/overlays
    out = out.replace(
        Regex("""(\[data-mydes=(['"])media-box\2\])\s*::?before\b""", RegexOption.IGNORE_CASE),
        "$1::after"
    )
    out = out.replace(Regex("""(\[data-mydes=(['"])media-box\2\]\s*::?after[^{]*\{)([^}]+)\}""", RegexOption.IGNORE_CASE)) { match ->
        val sel = match.groupValues[1]
        var body = match.groupValues[3]
        if (!Regex("""\bz-index\s*:""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            body = "z-index: 2 !important; pointer-events: none !important; $body"
        }
        "$sel$body}"
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

    // 13. Prevent [data-mydes="btn"] and [data-mydes="nav-item"] from forcing fixed width & height
    out = out.replace(Regex("""(\[data-mydes=['"](?:btn|nav-item)['"]\][^{]*\{)([^}]+)\}""", RegexOption.IGNORE_CASE)) { match ->
        val sel = match.groupValues[1]
        val body = match.groupValues[2]
            .replace(Regex("""\b(?:width|height)\s*:\s*\d+(?:\.\d+)?(?:px|rem|em)\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE), "")
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

    // 14b. Auto-Healer 4: Strip destructive `color: inherit` resets on `[data-mydes="chip"|"btn"|"nav-item"]`
    out = out.replace(Regex("""([^{}@]+)\{([^{}]+)\}""")) { match ->
        val sel = match.groupValues[1]
        val body = match.groupValues[2]
        if (Regex("""\[data-mydes=['"](?:chip|btn|nav-item)['"]\]""", RegexOption.IGNORE_CASE).containsMatchIn(sel) &&
            !sel.contains("*") &&
            Regex("""\bcolor\s*:\s*inherit\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)
        ) {
            val cleanedBody = body.replace(Regex("""\bcolor\s*:\s*inherit\s*(?:!important)?\s*;?""", RegexOption.IGNORE_CASE), "")
            if (cleanedBody.trim().isEmpty()) "" else "$sel{$cleanedBody}"
        } else {
            match.value
        }
    }

    // 15. Fix A: Ensure static properties on [data-mydes="..."] elements have !important using `splitTopLevelDeclarations`
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
        const val RUNTIME_VERSION = 23

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

        fun findMissingDecisions(designSpec: String, customCss: String): List<String> {
            if (designSpec.isBlank()) return emptyList()
            val implementedIds = Regex("""\b(D\d+)\b""")
                .findAll(customCss)
                .map { it.groupValues[1] }
                .toSet()

            val missing = ArrayList<String>()
            val lineRegex = Regex("""^\s*(D\d+)\s*:\s*(.+)$""", RegexOption.MULTILINE)
            for (match in lineRegex.findAll(designSpec)) {
                val id = match.groupValues[1]
                val desc = match.groupValues[2].trim()
                if (id !in implementedIds) {
                    missing.add("$id: $desc")
                }
            }
            return missing
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

// ───────────────────── Artisanal Pure-CSS Universal Runtime v23 ─────────────────────

object MydesRuntime {

    private val CORE = """
if (window.top !== window) return;
var oldHud = document.getElementById('__mydes_yolo_hud'); if (oldHud && !BP.probeOnly) oldHud.remove();
var oldMask = document.getElementById('__mydes_blind_mask'); if (oldMask) oldMask.remove();
if (window.__mydes && window.__mydes.id() === BP.id) return;
if (window.__mydes && window.__mydes.clear) { try { window.__mydes.clear(true); } catch (e) {} }
if (BP.skip && BP.skip.indexOf(location.hostname) >= 0) return;

var W = window, D = document, cfg = BP;
var A = 'data-mydes', S = 'data-mydes-surface', F = 'data-mydes-fix', ST = 'data-mydes-state';
var dead = false, scheduled = false;
var stats = { slices: 0, ms: 0, scanned: 0, tagged: 0, surfaces: 0, healed: 0 };

window.MydesBridge = {
  decorateUnits: function() {}, injectOrnament: function() {}, spawnParticles: function() {}, enable3DTilt: function() {},
  onCleanup: function() {}
};

/* ================= colour helpers ================= */
function h2c(h) { return { r: parseInt(h.substr(1, 2), 16), g: parseInt(h.substr(3, 2), 16), b: parseInt(h.substr(5, 2), 16), a: 1 }; }
function c2h(c) {
  function x(v) { v = Math.max(0, Math.min(255, Math.round(v))); return (v < 16 ? '0' : '') + v.toString(16); }
  return '#' + x(c.r) + x(c.g) + x(c.b);
}
function lum(c) {
  function f(v) { v /= 255; return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4); }
  return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
}
function contrast(a, b) { var x = lum(a), y = lum(b); return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05); }
function mix(a, b, t) { return { r: a.r + (b.r - a.r) * t, g: a.g + (b.g - a.g) * t, b: a.b + (b.b - a.b) * t, a: 1 }; }
function sat(c) {
  var r = c.r / 255, g = c.g / 255, b = c.b / 255, mx = Math.max(r, g, b), mn = Math.min(r, g, b), l = (mx + mn) / 2, d = mx - mn;
  if (d === 0) return { s: 0, l: l };
  return { s: d / (1 - Math.abs(2 * l - 1)), l: l };
}
function parse(c) {
  if (!c) return null;
  var m = /^rgba?\(([^)]+)\)$/.exec(c), p;
  if (m) {
    p = m[1].split(/[\s,\/]+/).filter(Boolean).map(parseFloat);
    if (p.length < 3) return null;
    var a = p.length > 3 ? p[3] : 1;
    if (a > 1) a = a / 100;
    return { r: p[0], g: p[1], b: p[2], a: a };
  }
  m = /^color\(srgb\s+([^)]+)\)$/.exec(c);
  if (m) {
    p = m[1].split(/[\s\/]+/).filter(Boolean).map(parseFloat);
    if (p.length < 3) return null;
    return { r: p[0] * 255, g: p[1] * 255, b: p[2] * 255, a: p.length > 3 ? p[3] : 1 };
  }
  return null;
}

/* ================= palette + stylesheet ================= */
var P = {};
function pal() {
  P.bg = h2c(cfg.bg); P.surface = h2c(cfg.surface); P.surface2 = h2c(cfg.surface2);
  P.fg = h2c(cfg.fg); P.ink = h2c(cfg.ink); P.accent = h2c(cfg.accent);
}
function buildCss() {
  var border = c2h(mix(P.accent, P.surface2, 0.5));
  var vars = ':root{color-scheme:' + (cfg.dark ? 'dark' : 'light') + ';' +
    '--m-bg:' + cfg.bg + ';--bg:' + cfg.bg + ';' +
    '--m-surface:' + cfg.surface + ';--surface:' + cfg.surface + ';--m-primary:' + cfg.surface + ';' +
    '--m-surface2:' + cfg.surface2 + ';--surface2:' + cfg.surface2 + ';' +
    '--m-fg:' + cfg.fg + ';--fg:' + cfg.fg + ';--m-text:' + cfg.fg + ';' +
    '--m-ink:' + cfg.ink + ';--ink:' + cfg.ink + ';' +
    '--m-accent:' + cfg.accent + ';--accent:' + cfg.accent + ';' +
    '--m-border:' + border + ';--border:' + border + ';}\n';

  var base =
    'html,body{background-color:var(--m-bg)!important;color:var(--m-fg)!important}\n' +
    '[data-mydes-surface="canvas"],[data-mydes-surface="clear"]{background-color:transparent!important}\n' +
    '[data-mydes-surface="1"],[data-mydes-surface="pop"]{background-color:var(--m-surface)!important;color:var(--m-fg)!important}\n' +
    '[data-mydes-surface="2"],[data-mydes-surface="field"]{background-color:var(--m-surface2)!important;color:var(--m-fg)!important}\n' +
    '[data-mydes-surface="pop"],[data-mydes-surface="field"]{border-color:var(--m-border)!important}\n' +
    '[data-mydes-surface="field"]::placeholder{color:var(--m-fg)!important;opacity:.55!important}\n' +
    '[data-mydes-fix="fg"]{color:var(--m-fg)!important}\n' +
    '[data-mydes-fix="link"]{color:var(--m-accent)!important}\n' +
    '[data-mydes-fix="w"]{color:#fff!important}\n' +
    '[data-mydes-fix="k"]{color:#000!important}\n' +
    '[data-mydes="header"]{background-color:var(--m-surface);border-bottom:2px solid var(--m-accent)}\n' +
    '[data-mydes="search"]{background-color:var(--m-surface2);color:var(--m-fg);border:1px solid var(--m-accent);border-radius:18px}\n' +
    '[data-mydes="search"] input,[data-mydes="search"] textarea{background-color:transparent;color:var(--m-fg)}\n' +
    '[data-mydes="chip"]{background-color:var(--m-surface2);color:var(--m-ink);border:1px solid var(--m-border);border-radius:12px}\n' +
    '[data-mydes="card"]{background-color:var(--m-surface);border:1.5px solid var(--m-border);border-radius:14px;margin-bottom:12px!important;padding:10px!important;box-sizing:border-box!important;color:var(--m-fg);position:relative}\n' +
    '[data-mydes="media-box"]{position:relative;display:block;overflow:hidden;border-radius:8px}\n' +
    '[data-mydes="heading"],[data-mydes="title"]{color:var(--m-ink)}\n' +
    '[data-mydes="heading"] *,[data-mydes="title"] *{font-family:inherit!important;color:inherit!important;letter-spacing:inherit!important;text-shadow:inherit!important;text-transform:inherit!important}\n' +
    '[data-mydes="bottom-bar"]{background-color:var(--m-surface);border-top:2px solid var(--m-accent);color:var(--m-fg)}\n';

  var safetyResetCss =
    '\n/* --- Pure-CSS Safety Resets --- */\n' +
    'html body a:not([data-mydes="card"]):not([data-mydes="btn"]):not([data-mydes="chip"]):has(img, video, picture, h1, h2, h3, h4, div), html body [class*="thumbnail"] a:not([data-mydes="card"]), html body [class*="media"] a:not([data-mydes="card"]) { border: none !important; padding: 0 !important; background: transparent !important; box-shadow: none !important; }\n' +
    'html body button:not([data-mydes="nav-item"]):has(svg, img), html body [role="button"]:not([data-mydes="nav-item"]):not([data-mydes="chip"]):has(svg, img), html body [aria-label*="menu" i], html body [aria-label*="More" i], html body [aria-label*="Action" i] { background: transparent !important; border: none !important; box-shadow: none !important; }\n';

  return vars + base + '\n' + (cfg.customCss || '') + safetyResetCss + '\n';
}

var sheet = null, styleEl = null;
function mountSheet() {
  if (cfg.probeOnly) return;
  var css = buildCss();
  try {
    if (typeof CSSStyleSheet === 'function' && 'adoptedStyleSheets' in D) {
      sheet = new CSSStyleSheet();
      sheet.replaceSync(css);
      D.adoptedStyleSheets = D.adoptedStyleSheets.concat([sheet]);
      return;
    }
  } catch (e) { sheet = null; }
  styleEl = D.createElement('style');
  styleEl.id = '__mydes_style';
  styleEl.textContent = css;
  var r = D.head || D.documentElement;
  if (r) r.appendChild(styleEl);
}
function ensureSheet() {
  if (cfg.probeOnly) return;
  if (!sheet && !styleEl) { mountSheet(); return; }
  if (sheet) {
    if (D.adoptedStyleSheets.indexOf(sheet) < 0) { try { D.adoptedStyleSheets = D.adoptedStyleSheets.concat([sheet]); } catch (e) {} }
  } else if (styleEl && !styleEl.isConnected) {
    var r = D.head || D.documentElement;
    if (r) r.appendChild(styleEl);
  }
}
function fonts() {
  if (cfg.probeOnly) return;
  var head = D.head;
  if (!head) return;
  var re = /(?:font-family|--[a-zA-Z0-9_-]*font[a-zA-Z0-9_-]*)\s*:\s*['"]?([^;'"!,}]+)['"]?/gi, m;
  var css = cfg.customCss || '';
  while ((m = re.exec(css)) !== null) {
    var fam = m[1].trim();
    if (fam && !/^(serif|sans-serif|monospace|cursive|fantasy|system-ui|inherit|initial|unset|var\(|Georgia|Arial|Verdana|Tahoma|Courier|Times)/i.test(fam)) {
      var id = '__mydes_font_' + fam.replace(/\W+/g, '_');
      if (!D.getElementById(id)) {
        var l = D.createElement('link');
        l.id = id; l.rel = 'stylesheet';
        l.href = 'https://fonts.googleapis.com/css2?family=' + encodeURIComponent(fam).replace(/%20/g, '+') + ':wght@400;500;600;700&display=swap';
        head.appendChild(l);
      }
    }
  }
}

/* ================= state ================= */
var done = new WeakSet();
var seen = new WeakSet();
var tmap = new WeakMap();
var known = new WeakMap();
var fresh = new Set(), regroup = new Set(), bars = new Set(), stateDirty = new Set();
var plan = new Map(), fixPlan = new Map(), sq = [], iq = [], tasks = [];
var bgCache = new Map();
var mo = null, io = null;
var origAttach = null;

var SKIPT = { script: 1, style: 1, noscript: 1, template: 1, head: 1, link: 1, meta: 1, title: 1, base: 1, svg: 1 };
var NOINSPECT = { html: 1, head: 1, body: 1, svg: 1, img: 1, picture: 1, source: 1, video: 1, audio: 1, canvas: 1, iframe: 1, br: 1, hr: 1, wbr: 1, option: 1 };
var NOGROUP = { table: 1, tbody: 1, thead: 1, tfoot: 1, tr: 1, select: 1, datalist: 1, optgroup: 1, colgroup: 1, dl: 1, pre: 1, code: 1 };
var NOKID = { script: 1, style: 1, template: 1, link: 1, meta: 1, br: 1, hr: 1, option: 1, tr: 1, td: 1, th: 1, dt: 1, dd: 1, source: 1, track: 1, svg: 1, noscript: 1, img: 1 };
var CANDBAR = { div: 1, nav: 1, ul: 1, section: 1, aside: 1 };
var RANK = { 'header': 9, 'bottom-bar': 9, 'search': 8, 'chip': 7, 'nav-item': 7, 'card': 6, 'media-box': 5, 'title': 4, 'heading': 4, 'btn': 3 };
var BYROLE = { banner: 'header', contentinfo: 'bottom-bar', search: 'search', searchbox: 'search', tab: 'chip', heading: 'heading', button: 'btn' };
var POPROLE = { dialog: 1, alertdialog: 1, menu: 1, listbox: 1, tooltip: 1 };
var BARSEL = 'header,[role="banner"],footer,[role="contentinfo"],[data-mydes="header"],[data-mydes="bottom-bar"]';
var CLICK = 'a[href],button,[role="button"],[role="link"],[role="tab"]';
var PRICE = /^\s*(?:(?:[\u0024\u20AC\u00A3\u00A5\u20B9]|USD|EUR|GBP|INR|Rs\.?)\s?\d[\d,.]*|\d[\d,.]*\s?(?:[\u0024\u20AC\u00A3\u00A5\u20B9]|USD|EUR|GBP|INR))\s*$/;

/* ================= small utilities ================= */
function up(n) {
  if (!n) return null;
  if (n.parentElement) return n.parentElement;
  var r = n.getRootNode && n.getRootNode();
  return (r && r.host) ? r.host : null;
}
function depthOf(el) { var d = 0, p = el; while ((p = p.parentElement) && d < 12) d++; return d; }
function med(a) { a = a.slice().sort(function(x, y) { return x - y; }); return a[a.length >> 1]; }
function txtLen(e) { return (e.textContent || '').trim().length; }
function inContent(e) { return !!e.closest('main,article,section,[role="main"]'); }
function docOrder(a, b) {
  if (a === b) return 0;
  var p = a.compareDocumentPosition(b);
  return (p & 4) ? -1 : ((p & 2) ? 1 : 0);
}
function hasOwnText(e) {
  var c = e.childNodes, n = Math.min(c.length, 14);
  for (var i = 0; i < n; i++) if (c[i].nodeType === 3 && /\S/.test(c[i].nodeValue)) return true;
  return false;
}

/* ================= role planning (writes are batched) ================= */
function want(el, role) {
  if (el && el.getAttribute && el.getAttribute('data-mydes-locked') === '1') return;
  var cur = plan.has(el) ? plan.get(el) : el.getAttribute(A);
  if (role === '') { plan.set(el, ''); return; }
  if (cur && (RANK[cur] || 0) >= (RANK[role] || 0)) return;
  plan.set(el, role);
}
function flush() {
  plan.forEach(function(role, el) {
    if (!el.isConnected) return;
    if (el.getAttribute('data-mydes-locked') === '1') return;
    var cur = el.getAttribute(A);
    if (role === '') { if (cur) el.removeAttribute(A); return; }
    if (role === 'search' && el.parentElement && el.parentElement.closest('[data-mydes="search"]')) {
      if (cur === 'search') el.removeAttribute(A);
      return;
    }
    if (cur !== role) { el.setAttribute(A, role); stats.tagged++; }
    if (el.hasAttribute(S)) { el.removeAttribute(S); tmap.delete(el); }
    if (role === 'chip' || role === 'nav-item') syncState(el);
  });
  plan.clear();
  var sEls = D.querySelectorAll('[data-mydes="search"] [data-mydes="search"]');
  for (var s = 0; s < sEls.length; s++) {
    if (sEls[s].getAttribute('data-mydes-locked') !== '1') sEls[s].removeAttribute(A);
  }
  if (!cfg.probeOnly) {
    for (var i = 0; i < sq.length; i++) {
      var e = sq[i], t = tmap.get(e);
      if (e.isConnected && t && !e.hasAttribute(A)) { if (e.getAttribute(S) !== t) e.setAttribute(S, t); }
    }
    sq.length = 0;
    fixPlan.forEach(function(v, el) {
      if (!el.isConnected) return;
      if (v === '') { if (el.hasAttribute(F)) el.removeAttribute(F); }
      else if (el.getAttribute(F) !== v) el.setAttribute(F, v);
    });
    fixPlan.clear();
  }
}
var STATE_TOK = /(^|[-_])(active|selected|current)$/;
var STATE_NEG = /(^|[-_])(inactive|unselected|not[-_]active|not[-_]selected)$/;
function syncState(e) {
  var on = e.getAttribute('aria-selected') === 'true' || e.getAttribute('aria-pressed') === 'true' ||
    e.getAttribute('aria-current') === 'page' || e.getAttribute('aria-current') === 'true' || e.getAttribute('aria-checked') === 'true';
  if (!on) {
    var cl = e.classList;
    for (var i = 0; i < cl.length && i < 12; i++) {
      var t = cl[i].toLowerCase();
      if (STATE_TOK.test(t) && !STATE_NEG.test(t)) { on = true; break; }
    }
  }
  if (!on) on = !!e.querySelector('[aria-selected="true"],[aria-current="page"],[aria-current="true"],[aria-pressed="true"]');
  if (on) { if (e.getAttribute(ST) !== 'active') e.setAttribute(ST, 'active'); }
  else if (e.hasAttribute(ST)) e.removeAttribute(ST);
}

/* ================= phase 1: semantic roles (no layout reads) ================= */
function btnOk(e) {
  if (e.querySelector('img,svg,picture,video,canvas,h1,h2,h3,h4,h5,h6')) return false;
  /* Never tag inline prose links inside paragraphs/lists as buttons (fixes AI Overview inline links) */
  if (e.localName === 'a' && e.closest('p,li,blockquote,dd,dt')) return false;
  var cs = W.getComputedStyle(e);
  if (e.localName === 'a' && cs.display === 'inline') return false;
  return txtLen(e) > 0 || !!e.value;
}
function searchTarget(el) {
  var c = el.closest('form,[role="search"]');
  if (c && c.getElementsByTagName('*').length <= 40) return c;
  return el;
}
var SEARCHNAME = /^(q|k|s|query|search|keyword|keywords|term|text)$/i;
function semantic(el, ln) {
  if (ln !== 'input') {
    var cls = (typeof el.className === 'string' ? el.className : (el.getAttribute('class') || '')).toLowerCase();
    var al = (el.getAttribute('aria-label') || '').toLowerCase();
    if (ln.indexOf('search') >= 0 || cls.indexOf('search') >= 0 || al.indexOf('search') >= 0) {
      var topBar = el.closest('header,[role="banner"],[data-mydes="header"]') ||
        (!inContent(el) && (el.closest('[class*="header" i],[class*="topbar" i],[class*="top-bar" i],[class*="navbar" i]') || (el.getBoundingClientRect().top <= 80 && depthOf(el) >= 2 ? el.parentElement : null)));
      if (topBar && topBar !== el && !el.closest('footer,[role="contentinfo"],[data-mydes="bottom-bar"]')) {
        if (el.getBoundingClientRect().width >= 120) {
          want(searchTarget(el), 'search');
          return null;
        }
      }
    }
  }
  var role = el.getAttribute('role');
  if (role && BYROLE[role]) {
    var m = BYROLE[role];
    if (m === 'btn') return btnOk(el) ? 'btn' : null;
    if (m === 'header' || m === 'bottom-bar') return inContent(el) ? null : m;
    if (m === 'search') { if (ln === 'input') { want(searchTarget(el), 'search'); return null; } return 'search'; }
    if (m === 'chip') return el.closest(BARSEL) ? 'nav-item' : 'chip';
    if (m === 'heading') {
      /* Never tag a container as heading if it wraps an image/video or a whole card! */
      if (el.querySelector('img,video,picture,canvas') && el.getBoundingClientRect().height > 90) return null;
      return 'heading';
    }
    return m;
  }
  switch (ln) {
    case 'header': return inContent(el) ? null : 'header';
    case 'footer': return inContent(el) ? null : 'bottom-bar';
    case 'h1': return 'title';
    case 'h2': case 'h3': case 'h4': case 'h5': case 'h6':
      if (el.querySelector('img,video,picture') && el.getBoundingClientRect().height > 90) return null;
      return 'heading';
    case 'button': return btnOk(el) ? 'btn' : null;
    case 'input':
      var ty = (el.getAttribute('type') || 'text').toLowerCase();
      if (ty === 'submit' || ty === 'button' || ty === 'reset') return 'btn';
      if (ty === 'search' || SEARCHNAME.test(el.getAttribute('name') || '') ||
          /search/i.test((el.getAttribute('placeholder') || '') + ' ' + (el.getAttribute('aria-label') || ''))) {
        want(searchTarget(el), 'search');
      }
      return null;
    case 'form': return null;
  }
  return null;
}
function visit(el) {
  done.add(el);
  stats.scanned++;
  var ln = el.localName;
  if (el.shadowRoot) watch(el.shadowRoot);
  var r = semantic(el, ln);
  if (r) want(el, r);
  if (el.childElementCount >= 2) regroup.add(el);
  if (!NOINSPECT[ln]) { if (io) io.observe(el); else iq.push(el); }
  if ((CANDBAR[ln] || ln.indexOf('-') > 0) && depthOf(el) <= 6) bars.add(el);
}
function accept(node) { return (SKIPT[node.localName] || done.has(node)) ? 2 : 1; }
function* scanRoots(roots) {
  var n = 0;
  for (var i = 0; i < roots.length; i++) {
    var root = roots[i];
    if (root.nodeType === 9) root = root.documentElement;
    if (!root || !root.isConnected) continue;
    if (root.nodeType === 1) { if (done.has(root) || SKIPT[root.localName]) continue; visit(root); }
    var tw = D.createTreeWalker(root, 1, { acceptNode: accept }), cur;
    while ((cur = tw.nextNode())) {
      visit(cur);
      if ((++n & 63) === 0) { flush(); yield; }
    }
  }
  flush();
}

/* ================= phase 2: structure (bars, repeated groups, card anatomy) ================= */
function sig(e) {
  var f = e.firstElementChild;
  while (f && W.getComputedStyle(f).display === 'contents' && f.firstElementChild) {
    f = f.firstElementChild;
  }
  return e.localName + '>' + (f ? f.localName : '') + (e.querySelector('img,picture,video') ? 'm' : '');
}
function visRect(el) {
  if (!el || el.nodeType !== 1) return { left: 0, top: 0, width: 0, height: 0, bottom: 0, right: 0 };
  var r = el.getBoundingClientRect();
  if (r.width > 0 && r.height > 0) return r;
  /* Unwrap display:contents or 0x0 wrapper by finding first visible child rect */
  var kids = el.children;
  for (var i = 0; i < kids.length; i++) {
    var kr = visRect(kids[i]);
    if (kr.width > 0 && kr.height > 0) return kr;
  }
  return r;
}
function wrapMediaBox(imgEl, boundaryEl) {
  var r = imgEl.getBoundingClientRect(), ba = r.width * r.height;
  if (r.width < 56 || r.height < 56) return null;
  var w = null, p = imgEl.parentElement;
  while (p && p !== boundaryEl && p !== D.body) {
    var pr = visRect(p);
    if (pr.width * pr.height <= ba * 1.3 + 400) {
      /* Stop before a wrapper that also includes long caption text */
      if (txtLen(p) <= 28) { w = p; p = p.parentElement; } else break;
    } else break;
  }
  if (!w && imgEl.parentElement && imgEl.parentElement !== boundaryEl && txtLen(imgEl.parentElement) <= 28) {
    w = imgEl.parentElement;
  }
  return (w && w !== boundaryEl) ? w : null;
}

function* structure() {
  var vw = W.innerWidth || 400, vh = W.innerHeight || 800, k = 0, i, j;

  /* bars: full-width fixed/sticky strips at the top or bottom, whatever their tag */
  var bl = Array.from(bars); bars.clear();
  for (i = 0; i < bl.length; i++) {
    var b = bl[i];
    if (!b.isConnected || b.hasAttribute(A) || b.closest('[data-mydes="header"],[data-mydes="bottom-bar"]')) continue;
    var cs = W.getComputedStyle(b), pos = cs.position, fixed = (pos === 'fixed' || pos === 'sticky');
    if (!fixed && !(W.scrollY < 4 && depthOf(b) <= 3)) continue;
    var br = visRect(b);
    if (br.width < vw * 0.9 || br.height < 24 || br.height > vh * 0.2) continue;
    var atTop = br.top <= 8 && br.bottom > 0, atBottom = br.bottom >= vh - 8 && br.top < vh;
    if (!atTop && !atBottom) continue;
    if (!fixed && atBottom) continue;
    var it = b.querySelectorAll('a[href],button,[role="tab"],[role="link"],input').length;
    if (it < 2 || it > 40) continue;
    if (atBottom && (it < 3 || txtLen(b) > 200)) continue;
    if (b.closest('[role="dialog"],dialog')) continue;
    want(b, atTop ? 'header' : 'bottom-bar');
    flush();
    if ((++k & 15) === 0) yield;
  }

  /* repeated sibling groups -> card / chip / nav-item */
  var ps = Array.from(regroup); regroup.clear();
  var newCards = [];
  for (i = 0; i < ps.length; i++) {
    var p = ps[i];
    if (!p.isConnected || NOGROUP[p.localName] || p.closest('[data-mydes="card"],[data-mydes="chip"]')) continue;
    var kids = p.children, n = Math.min(kids.length, 120), buckets = {}, c;
    if (n < 2) continue;
    for (j = 0; j < n; j++) {
      c = kids[j];
      if (NOKID[c.localName]) continue;
      var s = sig(c);
      (buckets[s] || (buckets[s] = [])).push(c);
    }
    var learned = known.get(p) || {};
    for (var key in buckets) {
      var g = buckets[key], lr = learned[key];
      var need = (g[0].localName === 'article' || g[0].hasAttribute('itemscope')) ? 2 : 3;
      if (lr) {
        for (j = 0; j < g.length; j++) { if (!g[j].hasAttribute(A)) { want(g[j], lr); if (lr === 'card') newCards.push(g[j]); } }
        continue;
      }
      if (g.length < 2) continue;
      var rs = [], vis = [];
      for (j = 0; j < g.length; j++) {
        var r = visRect(g[j]);
        if (r.width > 0 && r.height > 0) { rs.push(r); vis.push(g[j]); }
      }
      if (vis.length < 2) continue;
      var ws = rs.map(function(x) { return x.width; }), hs = rs.map(function(x) { return x.height; });
      var mw = med(ws), mh = med(hs);
      if (mw < 24 || mh < 16) continue;
      var wOk = 0, hOk = 0, tops = [], tl = [], media = 0, click = 0;
      for (j = 0; j < vis.length; j++) {
        if (Math.abs(ws[j] - mw) <= mw * 0.25) wOk++;
        if (Math.abs(hs[j] - mh) <= mh * 0.4) hOk++;
        tops.push(Math.round(rs[j].top / 6));
        tl.push(txtLen(vis[j]));
        if (vis[j].querySelector('img,picture,video,canvas')) media++;
        if (vis[j].matches(CLICK) || vis[j].querySelector(CLICK)) click++;
      }
      var m2 = vis.length, wf = wOk / m2, hf = hOk / m2, cf = click / m2, mf = media / m2, mt = med(tl);
      if (g.length === 2 && Math.abs(rs[0].top - rs[1].top) < 16 && Math.abs(ws[0] - ws[1]) <= 8 && mf === 1 && (mh > 120 || hs[0] > 120)) need = 2;
      if (vis.length < need) continue;
      var rowsN = 0, seenTop = {};
      for (j = 0; j < tops.length; j++) { if (!seenTop[tops[j]]) { seenTop[tops[j]] = 1; rowsN++; } }
      var horizontal = rowsN <= Math.ceil(m2 / 3);
      var role = null;
      var barAnc = p.closest(BARSEL);
      if (barAnc) {
        var barPos = W.getComputedStyle(barAnc).position;
        var isFixedBar = (barPos === 'fixed' || barPos === 'sticky');
        if (cf >= 0.6 && wf >= 0.5 && horizontal && mh <= 68 && (isFixedBar ? mt <= 24 : mt <= 16)) role = 'nav-item';
      } else if (mh <= 64 && mt >= 1 && mt <= 32 && cf >= 0.6 && mf < 0.5 && horizontal && wf >= 0.5) {
        role = 'chip';
      } else if ((mw >= 130 || need === 2) && mh >= 56 && (wf >= 0.7 || hf >= 0.6) && hf >= 0.5 &&
                 (mf >= 0.5 || (mt >= 20 && cf >= 0.5) || g[0].localName === 'article' || need === 2)) {
        role = 'card';
      }
      if (!role) continue;
      learned[key] = role; known.set(p, learned);
      for (j = 0; j < vis.length; j++) { want(vis[j], role); if (role === 'card') newCards.push(vis[j]); }
      if ((++k & 7) === 0) { flush(); yield; }
    }
  }
  flush();

  /* Fix Issue 4: Rescue isolated #1 search result cards that sit outside the sibling group of results #2..#10 */
  var headings = D.querySelectorAll('h2,h3,[role="heading"]');
  for (i = 0; i < headings.length; i++) {
    var hEl = headings[i];
    if (hEl.closest('[data-mydes="card"],[data-mydes="header"],[data-mydes="bottom-bar"]')) continue;
    var linkAnc = hEl.closest('a[href]') || hEl.querySelector('a[href]');
    if (!linkAnc) continue;
    var candCard = hEl.parentElement;
    while (candCard && candCard !== D.body && candCard !== D.documentElement) {
      var cr = visRect(candCard);
      if (cr.width >= vw * 0.82 && cr.height >= 72 && cr.height <= 360 && txtLen(candCard) >= txtLen(hEl) + 25) {
        if (!candCard.querySelector('[data-mydes="card"]')) {
          want(candCard, 'card');
          newCards.push(candCard);
        }
        break;
      }
      if (cr.height > 380) break;
      candCard = candCard.parentElement;
    }
  }
  flush();

  /* card anatomy: tag ALL qualifying media-boxes + primary heading per card */
  for (i = 0; i < newCards.length; i++) {
    if (newCards[i].isConnected) anatomy(newCards[i]);
    if ((i & 7) === 7) { flush(); yield; }
  }
  flush();
}

var META_NOISE = /^\s*(\d+[\d,.]*[kKmM]?\+?\s*(likes?|views?|comments?|followers?|shares?)|\d+\s*(mins?|minutes?|hrs?|hours?|days?|wks?|weeks?|mos?|months?|yrs?|years?)\s*ago|sponsored|promoted)\b/i;

function anatomy(card) {
  /* If a child inside this card was mistakenly tagged as 'card', unwrap it so cards don't double-nest */
  var innerCards = card.querySelectorAll('[data-mydes="card"]');
  var i;
  for (i = 0; i < innerCards.length; i++) {
    if (innerCards[i] !== card && innerCards[i].getAttribute('data-mydes-locked') !== '1') {
      want(innerCards[i], '');
    }
  }

  var cr = visRect(card), carea = Math.max(1, cr.width * cr.height);
  /* Fix Issue 3: Tag ALL qualifying thumbnail/media containers inside the card (e.g. 2x2 image grids), not just 1! */
  var ms = card.querySelectorAll('img,video,canvas');
  var highestMediaBottom = cr.top;
  for (i = 0; i < ms.length && i < 16; i++) {
    var r = ms[i].getBoundingClientRect(), a = r.width * r.height;
    if (r.width >= 56 && r.height >= 56 && a >= carea * 0.04) {
      var mb = wrapMediaBox(ms[i], card);
      if (mb) {
        want(mb, 'media-box');
        if (r.bottom > highestMediaBottom) highestMediaBottom = r.bottom;
      }
    }
  }

  /* Fix Issue 2: Cleanly select the true card title/headline, never an image wrapper or a bottom comment */
  var hs = card.querySelectorAll('[data-mydes="heading"],[data-mydes="title"]');
  for (i = 0; i < hs.length; i++) {
    if (hs[i].getAttribute('data-mydes-locked') !== '1') want(hs[i], '');
  }

  var h = null;
  var semHeadings = card.querySelectorAll('h1,h2,h3,h4,h5,h6,[role="heading"],[itemprop="name"]');
  for (i = 0; i < semHeadings.length; i++) {
    var sh = semHeadings[i];
    if (sh.querySelector('img,video,picture,canvas') && visRect(sh).height > 80) continue;
    var st = (sh.textContent || '').trim();
    if (st.length >= 4 && !META_NOISE.test(st)) { h = sh; break; }
  }

  if (!h) {
    var cand = card.querySelectorAll('a,span,div,p,strong,b'), bestScore = -999, n = 0;
    for (i = 0; i < cand.length && n < 36; i++) {
      var x = cand[i];
      if (x.querySelector('img,video,picture,canvas,div,p')) continue;
      if (x.closest('[data-mydes="media-box"]')) continue;
      var t = (x.textContent || '').trim();
      if (t.length < 8 || t.length > 180 || PRICE.test(t) || META_NOISE.test(t)) continue;
      var xr = x.getBoundingClientRect();
      if (xr.width < 40 || xr.height < 12) continue;
      n++;
      var cs = W.getComputedStyle(x);
      var fontSize = parseFloat(cs.fontSize) || 14;
      var weightBonus = parseInt(cs.fontWeight, 10) >= 600 ? 1.18 : 1.0;
      /* Prefer text near the top of the card or immediately below the thumbnail, penalizing bottom comments */
      var relY = Math.max(0, xr.top - cr.top) / Math.max(1, cr.height);
      var posScore = (relY < 0.68) ? (1.0 - relY * 0.35) : 0.45;
      var score = fontSize * weightBonus * posScore;
      if (score > bestScore + 0.3) { bestScore = score; h = x; }
    }
  }
  if (h && h.getAttribute('data-mydes-locked') !== '1') {
    plan.set(h, 'heading');
  }
}

/* ================= phase 3: surfaces + contrast healing (viewport-gated) ================= */
function parentTier(el) {
  var a = up(el);
  while (a && a.nodeType === 1 && a !== D.body && a !== D.documentElement) {
    if (a.hasAttribute(A)) return 'clear';
    var t = tmap.get(a) || a.getAttribute(S);
    if (t === '1') return '2';
    if (t === '2' || t === 'pop' || t === 'field') return '1';
    a = up(a);
  }
  return '1';
}
function isField(el, ln) {
  if (ln === 'textarea' || ln === 'select') return true;
  if (ln !== 'input') return false;
  var t = (el.getAttribute('type') || 'text').toLowerCase();
  return !(t === 'checkbox' || t === 'radio' || t === 'range' || t === 'color' || t === 'file' || t === 'image' || t === 'hidden' || t === 'submit' || t === 'button' || t === 'reset');
}
function surface(el, ln, cs) {
  var bgc = parse(cs.backgroundColor);
  if (!bgc || bgc.a < 0.5 || cs.backgroundImage !== 'none') return;
  var field = isField(el, ln), sc = sat(bgc);
  if (!field && sc.s > 0.35 && sc.l > 0.15 && sc.l < 0.85) return;
  var r = el.getBoundingClientRect();
  if (r.width < 4 || r.height < 4) return;
  var vw = W.innerWidth || 400, vh = W.innerHeight || 800, tier;
  if (ln === 'dialog' || POPROLE[el.getAttribute('role')]) tier = 'pop';
  else if (field) tier = 'field';
  else if (r.width >= vw * 0.9 && r.width * r.height >= vw * vh * 0.55) tier = 'canvas';
  else tier = parentTier(el);
  tmap.set(el, tier); sq.push(el); stats.surfaces++;
}
function effBg(n, d) {
  if (!n || (d || 0) > 60) return P.bg;
  if (bgCache.has(n)) return bgCache.get(n);
  var t = tmap.get(n) || (n.nodeType === 1 ? n.getAttribute(S) : null), res = null;
  if (t === '1' || t === 'pop') res = P.surface;
  else if (t === '2' || t === 'field') res = P.surface2;
  else if (t !== 'canvas' && t !== 'clear') {
    var c = parse(W.getComputedStyle(n).backgroundColor);
    if (c && c.a >= 0.5) res = c;
  }
  if (!res) res = effBg(up(n), (d || 0) + 1);
  bgCache.set(n, res);
  return res;
}
var WHITE = { r: 255, g: 255, b: 255 }, BLACK = { r: 0, g: 0, b: 0 };
function heal(el, ln, cs) {
  if (!hasOwnText(el) || cs.webkitBackgroundClip === 'text' || cs.backgroundClip === 'text') return;
  /* Never turn links inside a card heading into blue accent links (Fixes Issue 4) */
  if (el.closest('[data-mydes="heading"],[data-mydes="title"]')) {
    if (el.hasAttribute(F)) fixPlan.set(el, '');
    return;
  }
  var fg = parse(cs.color);
  if (!fg || fg.a < 0.2) return;
  var bg = effBg(el), ratio = contrast(fg, bg);
  if (ratio >= 3.6) {
    if (el.hasAttribute(F)) fixPlan.set(el, '');
    return;
  }
  var cf = contrast(P.fg, bg), cw = contrast(WHITE, bg), ck = contrast(BLACK, bg), v;
  if (ln === 'a' && contrast(P.accent, bg) >= 4.5) v = 'link';
  else if (cf >= 4 || (cf >= cw && cf >= ck)) v = 'fg';
  else v = cw >= ck ? 'w' : 'k';
  fixPlan.set(el, v); stats.healed++;
}
function inspect(el) {
  seen.add(el);
  if (cfg.probeOnly) return;
  var cs;
  try { cs = W.getComputedStyle(el); } catch (e) { return; }
  if (cs.display === 'none' || cs.visibility === 'hidden') return;
  var ln = el.localName;
  if (!el.hasAttribute(A) && cs.display !== 'inline') surface(el, ln, cs);
  heal(el, ln, cs);
}
function inspectChain(el) {
  var chain = [], p = el;
  while (p && p.nodeType === 1 && !seen.has(p) && chain.length < 30) { chain.push(p); p = up(p); }
  for (var i = chain.length - 1; i >= 0; i--) inspect(chain[i]);
}
function* inspectAll() {
  var list = iq.splice(0, iq.length).filter(function(e) { return e.isConnected && !seen.has(e); });
  list.sort(docOrder);
  for (var i = 0; i < list.length; i++) {
    inspectChain(list[i]);
    if ((i & 31) === 31) { flush(); yield; }
  }
  flush();
}

/* ================= scheduler ================= */
function refill() {
  if (!(fresh.size || regroup.size || bars.size || iq.length)) return;
  if (fresh.size) { var rs = Array.from(fresh); fresh.clear(); tasks.push(scanRoots(rs)); }
  tasks.push(structure());
  if (!cfg.probeOnly) tasks.push(inspectAll());
}
function idle() { return !tasks.length && !fresh.size && !regroup.size && !bars.size && (cfg.probeOnly || !iq.length) && !plan.size; }
function pumpSlice(budgetMs) {
  if (dead) return;
  ensureSheet();
  var t0 = performance.now();
  if (!tasks.length) refill();
  while (tasks.length && performance.now() - t0 < budgetMs) {
    if (tasks[0].next().done) tasks.shift();
  }
  if (stateDirty.size) {
    stateDirty.forEach(function(e) { if (e.isConnected && (e.getAttribute(A) === 'chip' || e.getAttribute(A) === 'nav-item')) syncState(e); });
    stateDirty.clear();
  }
  flush();
  bgCache.clear();
  stats.ms += performance.now() - t0; stats.slices++;
}
function pump() {
  scheduled = false;
  pumpSlice(8);
  if (!idle()) schedule();
}
function schedule() {
  if (scheduled || dead) return;
  scheduled = true;
  if (W.requestIdleCallback) W.requestIdleCallback(pump, { timeout: 120 }); else setTimeout(pump, 16);
}

/* ================= observers (document + every shadow root, open or closed) ================= */
var watched = new WeakSet();
function watch(root) {
  if (watched.has(root)) return;
  watched.add(root);
  try { mo.observe(root, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'aria-selected', 'aria-current', 'aria-pressed', 'aria-checked'] }); } catch (e) {}
  if (root !== D && sheet) { try { root.adoptedStyleSheets = root.adoptedStyleSheets.concat([sheet]); } catch (e) {} }
  fresh.add(root);
  schedule();
}
function start() {
  pal();
  mountSheet();
  mo = new MutationObserver(function(recs) {
    for (var i = 0; i < recs.length; i++) {
      var r = recs[i];
      if (r.type === 'childList') {
        for (var j = 0; j < r.addedNodes.length; j++) { var n = r.addedNodes[j]; if (n.nodeType === 1) fresh.add(n); }
        if (r.addedNodes.length && r.target.nodeType === 1) regroup.add(r.target);
      } else if (r.target.hasAttribute && r.target.hasAttribute(A)) {
        stateDirty.add(r.target);
      }
    }
    schedule();
  });
  if (typeof IntersectionObserver === 'function') {
    io = new IntersectionObserver(function(es) {
      for (var i = 0; i < es.length; i++) if (es[i].isIntersecting) { io.unobserve(es[i].target); iq.push(es[i].target); }
      if (iq.length) schedule();
    }, { rootMargin: '120% 0px 120% 0px' });
  }
  try {
    origAttach = Element.prototype.attachShadow;
    Element.prototype.attachShadow = function(init) {
      var sr = origAttach.call(this, init);
      try { watch(sr); } catch (e) {}
      return sr;
    };
  } catch (e) { origAttach = null; }
  watch(D);
  fonts();
  var ready = function() {
    fonts();
    if (D.documentElement) {
      fresh.add(D.documentElement);
      pumpSlice(28);
      if (!idle()) schedule();
    }
  };
  if (D.readyState === 'loading') D.addEventListener('DOMContentLoaded', ready, { once: true }); else ready();
  W.addEventListener('pageshow', function() { ensureSheet(); schedule(); }, { passive: true });
}

/* ================= Interactive YOLO Inspector & Multi-Level Hit-Test Engine ================= */
var selectedEl = null;
var CONTAINER_ROLES = { 'header': 1, 'bottom-bar': 1, 'search': 1, 'chip': 1, 'card': 1 };

function isMeaningfulBox(el, vw, vh) {
  if (!el || el.nodeType !== 1 || SKIPT[el.localName] || el === D.body || el === D.documentElement) return false;
  var r = visRect(el);
  if (r.width < 20 || r.height < 14) return false;
  if (r.width >= vw * 0.98 && r.height >= vh * 0.92) return false;
  var cs = W.getComputedStyle(el);
  if (cs.display === 'none' || cs.visibility === 'hidden') return false;
  return true;
}

function unwrapZeroSize(el) {
  if (!el || el.nodeType !== 1) return el;
  var r = el.getBoundingClientRect();
  if (r.width > 0 && r.height > 0) return el;
  var c = el.firstElementChild;
  while (c && c.getBoundingClientRect().width === 0 && c.firstElementChild) c = c.firstElementChild;
  if (c && c.getBoundingClientRect().width > 0) return c;
  var p = el.parentElement;
  while (p && p !== D.body && p.getBoundingClientRect().width === 0) p = p.parentElement;
  return (p && p !== D.body) ? p : el;
}

function snapToBestAncestor(leaf) {
  if (!leaf || leaf.nodeType !== 1) return null;
  var vw = W.innerWidth || 400, vh = W.innerHeight || 800;
  var tagged = leaf.closest('[' + A + ']');
  if (tagged && tagged !== D.body && tagged !== D.documentElement) return unwrapZeroSize(tagged);

  var cur = leaf;
  while (cur && cur !== D.body && cur !== D.documentElement) {
    var ln = cur.localName;
    if (ln === 'path' || ln === 'svg' || ln === 'use' || ln === 'g' || ln === 'i' || ln === 'small' || ln === 'b' || ln === 'strong' || ln === 'em') {
      cur = cur.parentElement;
      continue;
    }
    var r = visRect(cur);
    var cs = W.getComputedStyle(cur);
    var bg = parse(cs.backgroundColor);
    var hasBoxVisuals = (bg && bg.a > 0.15) || cs.borderTopWidth !== '0px' || cs.boxShadow !== 'none' || cs.borderRadius !== '0px';
    var isSemanticBox = (ln === 'article' || ln === 'header' || ln === 'footer' || ln === 'nav' || ln === 'button' || ln === 'a' || ln === 'input' || ln === 'form' || cur.hasAttribute('role'));
    var isRepeatedSibling = cur.parentElement && cur.parentElement.children.length >= 2 && !NOGROUP[cur.parentElement.localName];

    if (r.width >= 28 && r.height >= 18 && !(r.width >= vw * 0.98 && r.height >= vh * 0.9)) {
      if (isSemanticBox || hasBoxVisuals || (isRepeatedSibling && cs.display !== 'inline')) {
        return unwrapZeroSize(cur);
      }
    }
    cur = cur.parentElement;
  }
  cur = leaf;
  while (cur && cur !== D.body && !isMeaningfulBox(cur, vw, vh)) cur = cur.parentElement;
  return unwrapZeroSize((cur && cur !== D.body) ? cur : leaf);
}

/**
 * Fix Issue 1: Walks up 1 to 4 ancestor levels (e.g. g-scrolling-carousel, ul, grid tracks)
 * to find all cousin/sibling elements at the exact same relative depth & similar geometry.
 */
function findRepeatingPeers(el) {
  if (!el || el.nodeType !== 1) return [];
  var r0 = visRect(el);
  if (r0.width < 12 || r0.height < 12) return [];
  var bestPeers = [];

  var anc = el.parentElement;
  for (var depth = 1; depth <= 4 && anc && anc !== D.body && anc !== D.documentElement; depth++) {
    if (!NOGROUP[anc.localName] && anc.children.length >= 2) {
      var candidates = [];
      function collectAtDepth(node, d) {
        if (!node || node.nodeType !== 1 || NOKID[node.localName]) return;
        if (d === 0) {
          if (node !== el && node.localName === el.localName) {
            var nr = visRect(node);
            if (nr.width > 0 && nr.height > 0 &&
                Math.abs(nr.width - r0.width) <= Math.max(r0.width * 0.4, 32) &&
                Math.abs(nr.height - r0.height) <= Math.max(r0.height * 0.45, 40)) {
              candidates.push(node);
            }
          }
          return;
        }
        var ch = node.children;
        for (var k = 0; k < ch.length; k++) collectAtDepth(ch[k], d - 1);
      }
      collectAtDepth(anc, depth);
      if (candidates.length > bestPeers.length) {
        bestPeers = candidates;
      }
      if (bestPeers.length >= 2) break;
    }
    anc = anc.parentElement;
  }
  return bestPeers;
}

function describeElement(el) {
  if (!el || el.nodeType !== 1) return null;
  var r = visRect(el);
  var role = el.getAttribute(A) || '';
  var peers = findRepeatingPeers(el);
  var cls = (typeof el.className === 'string' ? el.className.trim().split(/\s+/).slice(0, 2).join('.') : '');
  return {
    tag: el.localName + (cls ? '.' + cls : ''),
    assignedRole: role,
    isContainer: !!CONTAINER_ROLES[role],
    matchingSiblings: peers.length,
    canStepUp: !!(el.parentElement && el.parentElement !== D.body && el.parentElement !== D.documentElement),
    canStepDown: !!(el.firstElementChild),
    rect: { x: Math.round(r.left), y: Math.round(r.top), w: Math.round(r.width), h: Math.round(r.height) }
  };
}

function setSelectedEl(el) {
  if (selectedEl && selectedEl.removeAttribute) selectedEl.removeAttribute('data-mydes-selected');
  selectedEl = el;
  if (selectedEl && selectedEl.setAttribute) selectedEl.setAttribute('data-mydes-selected', '1');
}

function applyRoleToSingle(el, role) {
  if (!el || el.nodeType !== 1) return;
  if (!role || role === 'none' || role === '') {
    el.removeAttribute(A);
    el.removeAttribute(ST);
    el.setAttribute('data-mydes-locked', '1');
    return;
  }
  el.setAttribute(A, role);
  el.setAttribute('data-mydes-locked', '1');
  plan.delete(el);
  if (el.hasAttribute(S)) { el.removeAttribute(S); tmap.delete(el); }
  if (role === 'chip' || role === 'nav-item') syncState(el);
  if (role === 'card') {
    anatomy(el);
    flush();
  }
}

window.__mydes = {
  id: function() { return cfg.id; },
  setBlueprint: function(next) {
    /* Issue 5 Fix: Always strip any lingering YOLO or Blind Mask style tags when applying a real theme! */
    var yoloEl = D.getElementById('__mydes_yolo_hud');
    if (yoloEl) yoloEl.remove();
    var blindEl = D.getElementById('__mydes_blind_mask');
    if (blindEl) blindEl.remove();
    setSelectedEl(null);

    cfg = next;
    cfg.probeOnly = false;
    pal();
    seen = new WeakSet();
    tmap = new WeakMap();
    bgCache.clear();
    var surfEls = D.querySelectorAll('[data-mydes-surface],[data-mydes-fix]');
    for (var i = 0; i < surfEls.length; i++) {
      surfEls[i].removeAttribute(S);
      surfEls[i].removeAttribute(F);
      iq.push(surfEls[i]);
    }
    var css = buildCss();
    if (!sheet && !styleEl) {
      mountSheet();
    } else if (sheet) {
      try { sheet.replaceSync(css); } catch (e) {}
    } else if (styleEl) {
      styleEl.textContent = css;
    }
    fonts();
    schedule();
  },
  idle: idle,
  forceFullScan: function() {
    var guard = 0;
    while (!idle() && guard++ < 40) pumpSlice(35);
    flush();
    return this.outline();
  },
  hitTestAt: function(clientX, clientY) {
    var els = D.elementsFromPoint ? D.elementsFromPoint(clientX, clientY) : [D.elementFromPoint(clientX, clientY)];
    var rawLeaf = null;
    for (var i = 0; i < els.length; i++) {
      var cand = els[i];
      if (!cand || cand === D.body || cand === D.documentElement) continue;
      if (cand.id && cand.id.indexOf('__mydes') === 0) continue;
      rawLeaf = cand;
      break;
    }
    if (!rawLeaf) return JSON.stringify({ found: false });
    var snapped = snapToBestAncestor(rawLeaf);
    setSelectedEl(snapped);
    return JSON.stringify({
      found: true,
      element: describeElement(snapped)
    });
  },
  stepSelection: function(direction) {
    if (!selectedEl || !selectedEl.isConnected) return JSON.stringify({ found: false });
    var next = selectedEl;
    if (direction === 'up' && selectedEl.parentElement && selectedEl.parentElement !== D.body && selectedEl.parentElement !== D.documentElement) {
      next = unwrapZeroSize(selectedEl.parentElement);
    } else if (direction === 'down' && selectedEl.firstElementChild) {
      next = unwrapZeroSize(selectedEl.firstElementChild);
    }
    setSelectedEl(next);
    return JSON.stringify({
      found: true,
      element: describeElement(next)
    });
  },
  assignSelectedRole: function(role, propagateSiblings) {
    if (!selectedEl || !selectedEl.isConnected) return JSON.stringify({ updated: 0 });
    var peers = propagateSiblings ? findRepeatingPeers(selectedEl) : [];
    applyRoleToSingle(selectedEl, role);
    var count = 1;
    for (var i = 0; i < peers.length; i++) {
      applyRoleToSingle(peers[i], role);
      count++;
    }
    return JSON.stringify({
      updated: count,
      element: describeElement(selectedEl),
      outline: JSON.parse(this.outline())
    });
  },
  clearSelection: function() {
    setSelectedEl(null);
    return true;
  },
  outline: function() {
    if (!idle()) pumpSlice(24);
    var counts = {}, els = D.querySelectorAll('[data-mydes]');
    for (var i = 0; i < els.length; i++) { var r = els[i].getAttribute(A); counts[r] = (counts[r] || 0) + 1; }
    return JSON.stringify({
      host: location.hostname, title: (D.title || '').slice(0, 80),
      viewport: { w: innerWidth, h: innerHeight }, roleCounts: counts,
      surfaces: D.querySelectorAll('[data-mydes-surface]').length, healed: D.querySelectorAll('[data-mydes-fix]').length,
      idle: idle(), stats: stats
    });
  },
  clear: function(keepTags) {
    dead = true;
    setSelectedEl(null);
    var yoloEl = D.getElementById('__mydes_yolo_hud');
    if (yoloEl && !keepTags) yoloEl.remove();
    if (mo) mo.disconnect();
    if (io) io.disconnect();
    if (origAttach) { try { Element.prototype.attachShadow = origAttach; } catch (e) {} }
    if (sheet) {
      try { sheet.replaceSync(''); } catch (e) {}
      try { D.adoptedStyleSheets = D.adoptedStyleSheets.filter(function(s) { return s !== sheet; }); } catch (e) {}
    }
    if (styleEl) styleEl.remove();
    var sfEls = D.querySelectorAll('[data-mydes-surface],[data-mydes-fix]');
    for (var j = 0; j < sfEls.length; j++) { sfEls[j].removeAttribute(S); sfEls[j].removeAttribute(F); }
    if (!keepTags) {
      var els = D.querySelectorAll('[data-mydes],[data-mydes-state],[data-mydes-locked]');
      for (var i = 0; i < els.length; i++) {
        els[i].removeAttribute(A);
        els[i].removeAttribute(ST);
        els[i].removeAttribute('data-mydes-locked');
      }
    }
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

    fun probeScript(skipHosts: Set<String> = SitePacks.skipHosts): String {
        val probeBp = JSONObject()
            .put("id", "__mydes_probe__")
            .put("probeOnly", true)
            .put("dark", true)
            .put("bg", "#0a0c0e")
            .put("surface", "#14181d")
            .put("surface2", "#1e242b")
            .put("fg", "#e2e8f0")
            .put("ink", "#ffffff")
            .put("accent", "#00ff88")
            .put("customCss", "")
            .put("skip", JSONArray(skipHosts.toList()))
        return "(function(BP){$CORE\n})($probeBp);"
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

    /**
     * Injects the Crystal-Clear YOLO Object-Detection Overlay:
     * 1. Outer Containers (`header`, `bottom-bar`, `search`, `chip`, `card`) -> Solid 2.5px color-coded bounding boxes + Top-Left solid pill label.
     * 2. Inner Anatomy (`media-box`, `heading`, `title`, `btn`, `nav-item`) -> 1.75px dotted inner lines + Bottom-Right mini-badge so labels NEVER collide with parent container labels.
     * 3. Selected Element (`[data-mydes-selected="1"]`) -> High-visibility target frame during user inspection.
     */
    val APPLY_YOLO_INSPECTOR_HUD_JS = """
        (function(){
          try {
            if (window.__mydes && window.__mydes.forceFullScan) {
              window.__mydes.forceFullScan();
            }
            var existing = document.getElementById('__mydes_yolo_hud');
            if (existing) existing.remove();
            var s = document.createElement('style');
            s.id = '__mydes_yolo_hud';
            s.textContent =
              '[data-mydes] { position: relative !important; }\n' +

              /* --- TIER 1: OUTER CONTAINERS (Solid Bounding Boxes + Top-Left YOLO Label) --- */
              '[data-mydes="header"] { outline: 2.5px solid #00E5FF !important; outline-offset: -2px !important; }\n' +
              '[data-mydes="bottom-bar"] { outline: 2.5px solid #00B8D4 !important; outline-offset: -2px !important; }\n' +
              '[data-mydes="search"] { outline: 2.5px solid #FFD600 !important; outline-offset: -1px !important; }\n' +
              '[data-mydes="chip"] { outline: 2px solid #FF4081 !important; outline-offset: -1px !important; }\n' +
              '[data-mydes="card"] { outline: 2.5px solid #00E676 !important; outline-offset: -2px !important; background-image: linear-gradient(rgba(0,230,118,0.04), rgba(0,230,118,0.04)) !important; }\n' +

              '[data-mydes="header"]::before, [data-mydes="bottom-bar"]::before, [data-mydes="search"]::before, [data-mydes="chip"]::before, [data-mydes="card"]::before {\n' +
              '  content: attr(data-mydes) !important;\n' +
              '  position: absolute !important; top: 0 !important; left: 0 !important;\n' +
              '  font: 700 9px/1.1 ui-monospace, SFMono-Regular, Menlo, monospace !important;\n' +
              '  text-transform: uppercase !important; letter-spacing: 0.4px !important;\n' +
              '  padding: 2px 5px !important; border-radius: 0 0 4px 0 !important;\n' +
              '  color: #000 !important; z-index: 2147483645 !important;\n' +
              '  pointer-events: none !important; box-shadow: 0 1px 3px rgba(0,0,0,0.45) !important;\n' +
              '}\n' +
              '[data-mydes="header"]::before { background: #00E5FF !important; }\n' +
              '[data-mydes="bottom-bar"]::before { background: #00B8D4 !important; }\n' +
              '[data-mydes="search"]::before { background: #FFD600 !important; }\n' +
              '[data-mydes="chip"]::before { background: #FF4081 !important; color: #fff !important; }\n' +
              '[data-mydes="card"]::before { background: #00E676 !important; }\n' +

              /* --- TIER 2: INNER ANATOMY (Dotted Lines + Bottom-Right Mini Badge) --- */
              '[data-mydes="media-box"] { outline: 1.75px dotted #B388FF !important; outline-offset: -2px !important; }\n' +
              '[data-mydes="heading"], [data-mydes="title"] { outline: 1.5px dotted #FF9100 !important; outline-offset: 1px !important; }\n' +
              '[data-mydes="btn"] { outline: 1.75px dotted #FF5252 !important; outline-offset: 0px !important; }\n' +
              '[data-mydes="nav-item"] { outline: 1.5px dotted #64FFDA !important; outline-offset: -1px !important; }\n' +

              '[data-mydes="media-box"]::after, [data-mydes="heading"]::after, [data-mydes="title"]::after, [data-mydes="btn"]::after, [data-mydes="nav-item"]::after {\n' +
              '  content: attr(data-mydes) !important;\n' +
              '  position: absolute !important; bottom: 0 !important; right: 0 !important;\n' +
              '  font: 600 8px/1.1 ui-monospace, SFMono-Regular, Menlo, monospace !important;\n' +
              '  padding: 1px 4px !important; border-radius: 4px 0 0 0 !important;\n' +
              '  color: #000 !important; z-index: 2147483646 !important;\n' +
              '  pointer-events: none !important; opacity: 0.94 !important;\n' +
              '}\n' +
              '[data-mydes="media-box"]::after { background: #B388FF !important; }\n' +
              '[data-mydes="heading"]::after, [data-mydes="title"]::after { background: #FF9100 !important; }\n' +
              '[data-mydes="btn"]::after { background: #FF5252 !important; color: #fff !important; }\n' +
              '[data-mydes="nav-item"]::after { background: #64FFDA !important; }\n' +

              /* --- ACTIVE USER SELECTION HIGHLIGHT --- */
              '[data-mydes-selected="1"] {\n' +
              '  outline: 3px solid #FFFFFF !important;\n' +
              '  box-shadow: 0 0 0 3px #FF0055, 0 0 18px rgba(255,0,85,0.65) !important;\n' +
              '  z-index: 2147483640 !important;\n' +
              '}';
            (document.head || document.documentElement).appendChild(s);
            return window.__mydes ? window.__mydes.outline() : '{}';
          } catch(e) { return JSON.stringify({ error: String(e) }); }
        })();
    """

    val REMOVE_YOLO_INSPECTOR_HUD_JS = """
        (function(){
          try {
            if (window.__mydes && window.__mydes.clearSelection) window.__mydes.clearSelection();
            var el = document.getElementById('__mydes_yolo_hud');
            if (el) el.remove();
            var blindEl = document.getElementById('__mydes_blind_mask');
            if (blindEl) blindEl.remove();
            var sel = document.querySelectorAll('[data-mydes-selected]');
            for (var i = 0; i < sel.length; i++) sel[i].removeAttribute('data-mydes-selected');
            return true;
          } catch(e) { return false; }
        })();
    """

    val APPLY_BLIND_MASK_JS = APPLY_YOLO_INSPECTOR_HUD_JS
    val REMOVE_BLIND_MASK_JS = REMOVE_YOLO_INSPECTOR_HUD_JS

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

// ───────────────────── Interactive YOLO Inspector & Hit-Testing Extensions ─────────────────────

@JsonClass(generateAdapter = true)
data class InspectedElementInfo(
    val tag: String,
    val assignedRole: String = "",
    val isContainer: Boolean = false,
    val matchingSiblings: Int = 0,
    val canStepUp: Boolean = false,
    val canStepDown: Boolean = false,
    val rectX: Int = 0,
    val rectY: Int = 0,
    val rectW: Int = 0,
    val rectH: Int = 0
)

/**
 * Runs MydesRuntime in probeOnly mode (no palette CSS applied yet) and mounts the
 * interactive YOLO bounding-box & dotted-anatomy HUD for user inspection before Pass 1 begins.
 */
suspend fun WebView.startYoloInspectionSession(): String? {
    evaluateJsSuspend(MydesRuntime.probeScript())
    delay(65)
    return evaluateJsSuspend(MydesRuntime.APPLY_YOLO_INSPECTOR_HUD_JS)
}

/**
 * Performs a viewport hit-test at CSS pixel coordinates (cssX, cssY) with automatic
 * ancestor-snapping and 0x0 unwrapping for unassigned leaf nodes.
 */
suspend fun WebView.hitTestElementForInspection(cssX: Float, cssY: Float): InspectedElementInfo? {
    val raw = evaluateJsSuspend(
        "(function(){ return window.__mydes ? window.__mydes.hitTestAt($cssX, $cssY) : '{}'; })();"
    ) ?: return null
    return parseInspectedElementJson(raw)
}

/**
 * Steps the currently selected element 'up' (to parent container) or 'down' (to child).
 */
suspend fun WebView.stepInspectedElement(direction: String): InspectedElementInfo? {
    val safeDir = if (direction == "down") "down" else "up"
    val raw = evaluateJsSuspend(
        "(function(){ return window.__mydes ? window.__mydes.stepSelection('$safeDir') : '{}'; })();"
    ) ?: return null
    return parseInspectedElementJson(raw)
}

/**
 * Assigns or changes the `data-mydes` tag on the currently selected element, propagating
 * across multi-level carousel/grid peers (`findRepeatingPeers`) when `propagateSiblings` is true.
 */
suspend fun WebView.assignRoleToInspectedElement(
    role: String,
    propagateSiblings: Boolean = true
): Pair<Int, InspectedElementInfo?> {
    val cleanRole = role.trim().lowercase(Locale.US)
    val raw = evaluateJsSuspend(
        "(function(){ return window.__mydes ? window.__mydes.assignSelectedRole('$cleanRole', $propagateSiblings) : '{}'; })();"
    ) ?: return Pair(0, null)
    return try {
        val unwrapped = unwrapJsString(raw) ?: return Pair(0, null)
        val j = JSONObject(unwrapped)
        val count = j.optInt("updated", 0)
        val elObj = j.optJSONObject("element")
        val info = if (elObj != null) parseElementObject(elObj) else null
        Pair(count, info)
    } catch (_: Exception) {
        Pair(0, null)
    }
}

private fun unwrapJsString(raw: String): String? {
    if (raw.isBlank() || raw == "null") return null
    return if (raw.startsWith("\"") && raw.endsWith("\"")) {
        try {
            org.json.JSONTokener(raw).nextValue()?.toString() ?: raw
        } catch (_: Exception) {
            raw.substring(1, raw.length - 1).replace("\\\"", "\"").replace("\\\\", "\\")
        }
    } else raw
}

private fun parseInspectedElementJson(raw: String): InspectedElementInfo? {
    return try {
        val unwrapped = unwrapJsString(raw) ?: return null
        val j = JSONObject(unwrapped)
        if (!j.optBoolean("found", false)) return null
        val elObj = j.optJSONObject("element") ?: return null
        parseElementObject(elObj)
    } catch (_: Exception) {
        null
    }
}

private fun parseElementObject(elObj: JSONObject): InspectedElementInfo {
    val rect = elObj.optJSONObject("rect")
    return InspectedElementInfo(
        tag = elObj.optString("tag", "element"),
        assignedRole = elObj.optString("assignedRole", ""),
        isContainer = elObj.optBoolean("isContainer", false),
        matchingSiblings = elObj.optInt("matchingSiblings", 0),
        canStepUp = elObj.optBoolean("canStepUp", false),
        canStepDown = elObj.optBoolean("canStepDown", false),
        rectX = rect?.optInt("x", 0) ?: 0,
        rectY = rect?.optInt("y", 0) ?: 0,
        rectW = rect?.optInt("w", elObj.optInt("rectW", 0)) ?: 0,
        rectH = rect?.optInt("h", elObj.optInt("rectH", 0)) ?: 0
    )
}

/**
 * Captures the user-approved, crystal-clear Set-of-Mark (SoM) YOLO screenshot with all
 * bounding boxes and dotted anatomy markers visible, and then IMMEDIATELY removes the
 * YOLO HUD overlay from the live WebView so the inspector markings disappear!
 */
suspend fun WebView.captureApprovedYoloBase64(maxDimension: Int = 640): String? {
    return try {
        evaluateJsSuspend("(function(){ if(window.__mydes && window.__mydes.clearSelection) window.__mydes.clearSelection(); })();")
        delay(24)
        val base64 = withContext(Dispatchers.Main) {
            if (width <= 0 || height <= 0) return@withContext null
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            draw(Canvas(bmp))
            val longestEdge = maxOf(width, height)
            val scale = minOf(1f, maxDimension.toFloat() / longestEdge)
            val out = if (scale < 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (width * scale).toInt().coerceAtLeast(1),
                    (height * scale).toInt().coerceAtLeast(1),
                    true
                )
            } else bmp
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 78, bos)
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
        }
        // Issue 5 Fix: Immediately remove the YOLO HUD right after capturing the screenshot!
        evaluateJsSuspend(MydesRuntime.REMOVE_YOLO_INSPECTOR_HUD_JS)
        base64
    } catch (_: Throwable) {
        try { evaluateJsSuspend(MydesRuntime.REMOVE_YOLO_INSPECTOR_HUD_JS) } catch (_: Throwable) {}
        null
    }
}

suspend fun WebView.captureBlindWireframeBase64(maxDimension: Int = 384): String? =
    captureApprovedYoloBase64(maxDimension)

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

// ───────────────────── The Gemini UI Synthesis Service (v23) ─────────────────────

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
If an annotated Set-of-Mark (SoM) screenshot of the live page is attached, it displays the exact user-approved `[data-mydes]` element mapping:
- Solid color-coded bounding boxes with top-left badges mark outer containers (`header` in cyan, `bottom-bar` in teal, `search` in yellow, `chip` in pink, `card` in green).
- Dotted inner lines with bottom-right badges mark nested anatomy (`media-box` in purple, `heading`/`title` in orange, `btn` in red, `nav-item` in mint).
IMPORTANT: Those colored inspection outlines and text badges (`card`, `heading`, `media-box`, etc.) are diagnostic markers ONLY so you can see the layout and density—NEVER include those diagnostic badges or dashed green outlines in your design decisions!
 
WHAT IS POSSIBLE
Styling and imagery only: colour, gradient, texture, light, shadow, borders, shape, vector artwork drawn behind or over existing elements, typefaces, text effects, text-based ornaments and animation of colour, light, opacity, shadow and imagery.
Not possible: scripts, rearranging layout, changing text content.
Existing text must always stay readable.

RULE TAGS FOR EACH ELEMENT
For every item in "elements", include a "rules" array containing the matching target tag(s) and any visual technique tag(s) required by that element's decisions:
- Target tags (pick 1+): CANVAS (page canvas), AMBIENT (ambient layer A/B or screen-wide overlays), HEADER (top bar), BOTTOM_BAR (bottom bar/footer/nav items), SEARCH (search box), CHIP (filter pills/tabs), CARD_MEDIA (cards, thumbnails, frames), TEXT (headlines/titles), BTN (buttons).
- Technique tags (include ONLY when used by this element's decisions):
  * LAYERED_BG: multiple stacked gradients, patterns, grids, meshes, stripes, dots, or textures
  * SVG: drawn vector shapes, motifs, emblems, corners, filigree, or custom artwork
  * ANIMATION: any movement, drift, pulse, shimmer, glow cycle, transition or animated effect
  * GLYPH: any symbolic character, badge marker, or unicode/ASCII stamp
""".trimIndent()

        fun isNvidiaModel(model: String): Boolean =
            model.startsWith("nvidia/", ignoreCase = true) || model.contains("nemotron", ignoreCase = true)

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

    private fun cleanSiteLabel(websiteName: String, websiteUrl: String): String {
        val raw = websiteName.trim()
        if (raw.isBlank() || raw == "*") {
            val dom = extractSafeDomain(websiteUrl, "")
            return if (dom.isBlank() || dom == "*") "Webpage" else dom.take(50)
        }
        if (raw.startsWith("http://", ignoreCase = true) ||
            raw.startsWith("https://", ignoreCase = true) ||
            raw.contains("://") ||
            raw.contains("?") ||
            raw.contains("&") ||
            raw.length > 65
        ) {
            val dom = extractSafeDomain(raw, extractSafeDomain(websiteUrl, "Webpage"))
            return dom.take(50)
        }
        return raw.take(60)
    }

    private fun sanitizeOutlineJson(outlineJson: String?): String? {
        if (outlineJson.isNullOrBlank() || outlineJson == "null") return null
        return try {
            val unwrapped = unwrapJsString(outlineJson) ?: outlineJson
            val obj = JSONObject(unwrapped)
            obj.remove("url")
            obj.toString().take(700)
        } catch (_: Exception) {
            outlineJson.replace(Regex(""""url"\s*:\s*"[^"]*"\s*,?"""), "").take(700)
        }
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
        websiteName = cleanSiteLabel(websiteUrl, websiteUrl),
        websiteUrl = websiteUrl,
        pageOutlineJson = pageOutlineJson,
        screenshotBase64 = screenshotBase64,
        forceFresh = forceFresh,
        auditJson = auditJson,
        isReviewPass = isReviewPass,
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
        screenshotBase64: String? = null,
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
            approvedScreenshotBase64 = screenshotBase64,
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
                appendLine("UPDATED VISUAL SPECIFICATION (WITH CO-LOCATED ELEMENT RULES):")
                appendLine(brief.raw)
            }
            appendLine()
            appendLine("Return the updated UIBlueprint JSON now.")
        }

        val lowestMode = ReasoningModulatorConfig.getLowestModeForModel(synthesisModel)
        try {
            val (raw, _) = if (isNemotron) {
                val kwargs = ReasoningModulatorConfig.buildNemotronChatTemplateKwargs(lowestMode)
                callNvidiaNimStream(
                    model = synthesisModel,
                    apiKey = nvidiaApiKey,
                    systemInstruction = targetSysPrompt,
                    userContent = userContent,
                    temperature = 0.45f,
                    topP = 0.95f,
                    maxTokens = NIM_MAX_TOKENS,
                    chatTemplateKwargs = kwargs,
                    onThoughtChunk = { chunk -> onThinkingUpdate?.invoke("Modifying CSS...", chunk, false) },
                    onOutputChunk = { _ -> onThinkingUpdate?.invoke("Writing Modified CSS...", "", false) }
                )
            } else {
                val resolvedLevel = ReasoningModulatorConfig.resolveGeminiThinkingLevel(synthesisModel, lowestMode)
                val req = GeminiRequest(
                    contents = listOf(Content(parts = listOf(Part(text = userContent)))),
                    systemInstruction = Content(listOf(Part(text = targetSysPrompt))),
                    generationConfig = GenerationConfig(
                        temperature = if (isFrontier) 0.45f else 0.3f,
                        topP = 0.95f,
                        maxOutputTokens = 8192,
                        responseMimeType = "application/json",
                        thinkingConfig = ThinkingConfig(thinkingLevel = resolvedLevel)
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

    // ───── Pass 1: Dual-Tier Visual Architect + Co-Located Rule-RAG Builder ─────

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
                thinkingConfig = ThinkingConfig(thinkingLevel = "minimal")
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
        approvedScreenshotBase64: String? = null,
        apiKey: String,
        forFrontierModel: Boolean,
        onThinkingUpdate: ((phase: String, chunk: String, isComplete: Boolean) -> Unit)? = null
    ): Result<ConceptBrief> {
        val activeConceptPrompt = if (forFrontierModel) UI_CONCEPT_SYSTEM_PROMPT_FRONTIER else UI_CONCEPT_SYSTEM_PROMPT_LITE
        val modeLabel = if (forFrontierModel) "Creative Vision (Frontier Tier B)" else "Art Direction (Compact Tier A)"
        onThinkingUpdate?.invoke("Pass 1: $modeLabel ($CONCEPT_MODEL)", "Designing world, materials, and element decisions...", false)

        val siteLabel = cleanSiteLabel(websiteName, websiteUrl)
        val cleanOutline = sanitizeOutlineJson(pageOutlineJson)

        val input = buildString {
            appendLine("USER REQUEST: \"$prompt\"")
            appendLine("TARGET WEBSITE: $siteLabel")
            if (!cleanOutline.isNullOrBlank()) {
                appendLine("LIVE PAGE ROLE COUNTS: $cleanOutline")
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
            if (!approvedScreenshotBase64.isNullOrBlank()) {
                appendLine()
                appendLine()
                appendLine("=== ATTACHED USER-APPROVED YOLO SCREENSHOT ===")
                appendLine("[Approved YOLO Set-of-Mark JPEG: ${approvedScreenshotBase64.length / 1024} KB base64]")
            }
        }

        val briefParts = mutableListOf<Part>(Part(text = input))
        if (!approvedScreenshotBase64.isNullOrBlank()) {
            briefParts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = approvedScreenshotBase64)))
        }

        return try {
            val raw = callForText(
                CONCEPT_MODEL,
                apiKey,
                activeConceptPrompt,
                briefParts,
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

            val directions = buildString {
                appendLine("IDEA: $idea")
                if (paletteText.isNotBlank()) appendLine("PALETTE: $paletteText")
                if (typeText.isNotBlank()) appendLine("TYPE: $typeText")
                if (fingerprint.isNotBlank()) appendLine("FINGERPRINT: $fingerprint")

                val elementsArr = json.optJSONArray("elements")
                if (elementsArr != null && elementsArr.length() > 0) {
                    val parsedItems = ArrayList<CssRuleRag.ElementSpecItem>()
                    for (i in 0 until elementsArr.length()) {
                        val elObj = elementsArr.optJSONObject(i) ?: continue
                        val target = elObj.optString("target", "").trim()
                        val decArr = elObj.optJSONArray("decisions")
                        val rulesArr = elObj.optJSONArray("rules")
                        if (target.isNotBlank() && decArr != null && decArr.length() > 0) {
                            val decs = ArrayList<String>()
                            for (d in 0 until decArr.length()) {
                                val s = decArr.optString(d, "").trim()
                                if (s.isNotBlank()) decs.add(s)
                            }
                            val tags = ArrayList<String>()
                            if (rulesArr != null) {
                                for (r in 0 until rulesArr.length()) {
                                    val t = rulesArr.optString(r, "").trim()
                                    if (t.isNotBlank()) tags.add(t)
                                }
                            }
                            if (decs.isNotEmpty()) {
                                parsedItems.add(CssRuleRag.parseElementSpec(target, decs, tags))
                            }
                        }
                    }
                    if (parsedItems.isNotEmpty()) {
                        appendLine()
                        append(CssRuleRag.buildCoLocatedLedger(parsedItems))
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
            ApiRequestLogger.logRequest(
                context = context,
                type = "conceptBrief ($CONCEPT_MODEL)",
                websiteName = siteLabel,
                websiteUrl = websiteUrl,
                userPrompt = prompt,
                requestPayload = logInput,
                responseResult = json.toString(2),
                isSuccess = true,
                screenshotBase64 = approvedScreenshotBase64
            )
            Result.success(brief)
        } catch (e: Exception) {
            val errMsg = describe(e)
            ApiRequestLogger.logRequest(
                context = context,
                type = "conceptBrief ($CONCEPT_MODEL)",
                websiteName = siteLabel,
                websiteUrl = websiteUrl,
                userPrompt = prompt,
                requestPayload = logInput,
                responseResult = errMsg,
                isSuccess = false,
                screenshotBase64 = approvedScreenshotBase64
            )
            Result.failure(Exception("Concept pass failed ($CONCEPT_MODEL): $errMsg", e))
        }
    }

    // ───── Pass 2 (Dual-Tier CSS Synthesis with Co-Located Micro-RAG) & Pass 3 (Reviewer) ─────

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
        val siteLabel = cleanSiteLabel(websiteName, websiteUrl)
        val cleanOutline = sanitizeOutlineJson(pageOutlineJson)

        if (!isReviewPass && auditJson == null && !forceFresh) {
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

        val isReview = isReviewPass || (previous != null && !forceFresh && !auditJson.isNullOrBlank())

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
                websiteName = siteLabel,
                websiteUrl = websiteUrl,
                pageOutlineJson = cleanOutline,
                approvedScreenshotBase64 = screenshotBase64,
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
            val missingDecisions = UIBlueprint.findMissingDecisions(previous!!.designSpec, previous.customCss)
            appendLine("REVIEW PASS")
            appendLine("USER REQUEST: \"$cleanPrompt\"")
            appendLine("TARGET WEBSITE: $siteLabel")
            appendLine("PALETTE: dark=${previous.dark}, bg=${previous.bg}, surface=${previous.surface}, surface2=${previous.surface2}, fg=${previous.fg}, ink=${previous.ink}, accent=${previous.accent}")
            appendLine()
            appendLine("SCOPED RULES FOR LIVE TELEMETRY DEFECTS:")
            appendLine(CssRuleRag.retrieveScopedContractForSpec(auditJson))
            if (missingDecisions.isNotEmpty()) {
                appendLine()
                appendLine("SKIPPED DECISIONS MISSING FROM CURRENT CSS (You MUST implement CSS rules for every one of these in fixCss):")
                missingDecisions.forEach { appendLine("  - $it") }
            }
            if (previous.designSpec.isNotBlank()) {
                appendLine()
                appendLine("ORIGINAL SPECIFICATION (WITH CO-LOCATED ELEMENT RULES):")
                appendLine(previous.designSpec.take(3_600))
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
            appendLine("TARGET WEBSITE: $siteLabel")
            if (!cleanOutline.isNullOrBlank()) {
                appendLine("PAGE OUTLINE: $cleanOutline")
            }
            appendLine()
            appendLine("SPECIFICATION OF VISUAL DECISIONS & CO-LOCATED ELEMENT RULES:")
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
                thinkingConfig = if (isReview) {
                    ThinkingConfig(thinkingLevel = "minimal")
                } else {
                    val userMode = getReasoningLevel(context, activeModel)
                    ThinkingConfig(thinkingLevel = ReasoningModulatorConfig.resolveGeminiThinkingLevel(activeModel, userMode))
                }
            )
        )

        val logType = if (isReview) "reviewBlueprint ($activeModel)" else "buildBlueprint ($activeModel)"
        val requestJson = buildString {
            appendLine("=== TARGET MODEL ===")
            appendLine(if (isNemotronSynthesis) "$activeModel (NVIDIA NIM)" else activeModel)
            appendLine()
            appendLine("=== PIPELINE TIER ===")
            appendLine(if (isReview) "Pass 3: Delta Review (Scoped RAG)" else if (isFrontier) "Tier B: Uncapped Frontier Synthesis (Co-Located Micro-RAG)" else "Tier A: Fast Lite Synthesis (Co-Located Micro-RAG)")
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
                val userMode = getReasoningLevel(context, activeModel)
                val kwargs = ReasoningModulatorConfig.buildNemotronChatTemplateKwargs(userMode)
                callNvidiaNimStream(
                    model = activeModel,
                    apiKey = nvidiaApiKey,
                    systemInstruction = activeSynthesisPrompt,
                    userContent = userContextText,
                    temperature = 0.55f,
                    topP = 0.95f,
                    maxTokens = NIM_MAX_TOKENS,
                    chatTemplateKwargs = kwargs,
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
                    websiteName = siteLabel,
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
                    websiteName = siteLabel,
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
                websiteName = siteLabel,
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

    // ───── v23 System Prompts: Ultra-Compact Core + Co-Located Micro-RAG ─────

    private val UI_REVIEW_SYSTEM_PROMPT = """
You are the Pure-CSS QA Reviewer for a browser that restyles websites. You receive the original specification (with co-located element rules), live telemetry measured on the painted page, the current CSS, and sometimes a screenshot. Repair what is broken or missing. Do not redesign.
 
REVIEW TASKS
1. Telemetry: for every entry in animationWarnings, pseudoOverlays, clashingSurfaces, lowContrastText and squishedColumns, write CSS that fixes the root cause, using the theme's own palette variables and specificity high enough to beat the host.
2. Specification & Rule fidelity: treat every decision and its co-located element rules in the specification as strict requirements. If the current CSS lacks one, or the telemetry or screenshot shows it is not visibly painting (no content, zero size, buried under another layer, mismatched layer counts, keyframes never attached, overridden by the host), write CSS that realises it.
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
 
OUTPUT: ONLY valid JSON, compact, about 450 words at most:
{
  "idea": "one sentence",
  "palette": "hex colours with roles: canvas, surface, raised surface, text, headline ink, primary accent, plus any further colours the idea needs",
  "type": "heading and body typefaces (any Google Font), casing and spacing",
  "elements": [
    {"target": "a listed part, or an extra layer or motif you invent and where it attaches", "decisions": ["one concrete visual decision per string"], "rules": ["TARGET_TAG", "TECHNIQUE_TAG"]}
  ],
  "fingerprint": "at most 10 words naming what makes this world distinct"
}
Include one entry for every listed part (a part that simply inherits the world quietly still gets one decision), plus every invented element the idea needs. Each decision string must stand alone so it can be built and checked individually.
""".trimIndent()

    private val UI_SYNTHESIS_SYSTEM_PROMPT_LITE = """
You are the Pure-CSS Compiler. A specification of visual decisions follows in the user message, grouped by `[target]` element. Each element block includes its own `RULES FOR THIS ELEMENT` followed by its `D1..Dn` decisions.
 
${CssRuleRag.SHARED_CORE_CONTRACT}
 
METHOD
- Work through the specification `[target]` by `[target]`. Whenever you pick up an element, first read its `RULES FOR THIS ELEMENT`, then compile its `D1..Dn` decisions in full alignment with those rules before moving to the next element.
- Tag every CSS rule with a comment listing the decision IDs it implements (e.g. /* D1, D2 */). Every D1..Dn ID from the specification must appear in your CSS.
- Do not skip small decisions, and do not merge several decisions into one weaker effect.
- You compile, you do not redesign: add no decorative ideas of your own. Parts the specification leaves quiet still receive a clean, coherent treatment from the palette so no part of the page keeps host colours.
 
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
 
OUTPUT: ONLY valid JSON, about 650 words at most:
{
  "idea": "one vivid sentence",
  "palette": "hex colours with roles: canvas, surface, raised surface, text, headline ink, primary accent, plus any further colours the idea needs",
  "type": "heading and body typefaces (any Google Font), typographic mood",
  "elements": [
    {"target": "a listed part, or an extra layer or motif you invent and where it attaches", "decisions": ["one concrete visual decision per string"], "rules": ["TARGET_TAG", "TECHNIQUE_TAG"]}
  ],
  "fingerprint": "at most 10 words naming what makes this world distinct"
}
Include one entry for every listed part, plus every invented element the idea needs. Each decision string must stand alone so it can be built and checked individually.
""".trimIndent()

    private val UI_SYNTHESIS_SYSTEM_PROMPT_FRONTIER = """
You are a Frontier CSS Artisan. The Art Director's vision arrives grouped by `[target]` element. Each element block includes its own `RULES FOR THIS ELEMENT` followed by its `D1..Dn` sensory decisions.
 
${CssRuleRag.SHARED_CORE_CONTRACT}
 
CRAFT
- Work through the specification `[target]` by `[target]`. As you pick up each element, align strictly with its `RULES FOR THIS ELEMENT` while translating its `D1..Dn` decisions into richly crafted CSS.
- Use the whole CSS toolbox where a decision calls for it (layered gradients, masks, filters, blend modes, shadows, clip shapes, inline vector imagery, pseudo-elements, keyframes), never by habit.
- Tag every CSS rule with a comment listing the decision IDs it implements (e.g. /* D1, D2 */). Every D1..Dn ID from the specification must appear in your CSS.
- Build real material, not flat approximations: depth, lighting, texture and edge detail where the vision implies them.
- Give every part considered treatment so the whole page reads as one coherent world and no part keeps host colours.
 
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
    private fun nimReasoningKwargs(mode: String = ReasoningModulatorConfig.MODE_MEDIUM): JSONObject =
        ReasoningModulatorConfig.buildNemotronChatTemplateKwargs(mode)
    @Volatile private var lastNimFinishReason: String? = null

    private suspend fun callNvidiaNimStream(
        model: String,
        apiKey: String,
        systemInstruction: String,
        userContent: String,
        temperature: Float = 0.55f,
        topP: Float = 0.95f,
        maxTokens: Int = NIM_MAX_TOKENS,
        chatTemplateKwargs: JSONObject? = null,
        onThoughtChunk: ((String) -> Unit)? = null,
        onOutputChunk: ((String) -> Unit)? = null
    ): Pair<String, String> = withContext(Dispatchers.IO) {
        lastNimFinishReason = null
        val fullText = StringBuilder()
        val rawThoughts = StringBuilder()
        val effectiveKwargs = chatTemplateKwargs ?: nimReasoningKwargs()

        val jsonBody = JSONObject()
            .put("model", model)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", systemInstruction))
                .put(JSONObject().put("role", "user").put("content", userContent))
            )
            .put("temperature", temperature)
            .put("top_p", topP)
            .put("max_tokens", maxTokens)
            .put("chat_template_kwargs", effectiveKwargs)
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
            .put("chat_template_kwargs", effectiveKwargs)
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

    suspend fun beginInspectionBeforeTheme(): String? {
        return webView.startYoloInspectionSession()
    }

    suspend fun cancelInspectionBeforeTheme() {
        webView.evaluateJsSuspend(MydesRuntime.REMOVE_YOLO_INSPECTOR_HUD_JS)
        webView.evaluateJsSuspend("window.__mydes && window.__mydes.clear(false);")
    }

    suspend fun setTheme(
        prompt: String,
        includeScreenshot: Boolean = true,
        preApprovedYoloBase64: String? = null
    ): Result<UIBlueprint> {
        val currentUrl = withContext(Dispatchers.Main) { webView.url ?: "*" }
        val outlineJson = webView.evaluateJsSuspend(MydesRuntime.OUTLINE_PROBE_JS)

        val approvedVisual = preApprovedYoloBase64 ?: if (includeScreenshot) {
            webView.captureApprovedYoloBase64(maxDimension = 640)
        } else null

        // 1. Run Pass 1 (with User-Approved YOLO Visual) + Pass 2 (with Co-Located Micro-RAG)
        val initialBlueprint = service.themeSpec(
            context = context,
            prompt = prompt,
            websiteUrl = currentUrl,
            pageOutlineJson = outlineJson,
            screenshotBase64 = approvedVisual,
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

        // 2. Conditional Pass 3: Test live DOM + Decision Ledger. Skip Pass 3 if 0 defects & 0 skipped decisions!
        if (includeScreenshot) {
            delay(220)
            webView.evaluateJsSuspend(MydesRuntime.SNAPSHOT_FRAME_1_JS)
            delay(280)
            val auditJson = webView.evaluateJsSuspend(MydesRuntime.VISUAL_AND_DOM_AUDIT_JS)

            val missingDecisions = UIBlueprint.findMissingDecisions(
                initialBlueprint.designSpec,
                initialBlueprint.customCss
            )
            var needsRepair = missingDecisions.isNotEmpty()

            if (!needsRepair && !auditJson.isNullOrBlank() && auditJson != "null") {
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
