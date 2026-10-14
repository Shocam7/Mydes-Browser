package com.example.data.api

import android.content.Context
import com.example.BuildConfig
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

@JsonClass(generateAdapter = true)
data class GeminiRequest(
    val contents: List<Content>,
    @param:Json(name = "systemInstruction") val systemInstruction: Content? = null,
    @param:Json(name = "generationConfig") val generationConfig: GenerationConfig? = null
)

@JsonClass(generateAdapter = true)
data class Content(
    val parts: List<Part>
)

@JsonClass(generateAdapter = true)
data class Part(
    val text: String? = null,
    @param:Json(name = "inlineData") val inlineData: InlineData? = null
)

@JsonClass(generateAdapter = true)
data class InlineData(
    @param:Json(name = "mimeType") val mimeType: String,
    val data: String
)

@JsonClass(generateAdapter = true)
data class GenerationConfig(
    val temperature: Float = 0.4f
)

@JsonClass(generateAdapter = true)
data class GeminiResponse(
    val candidates: List<Candidate>? = null
)

@JsonClass(generateAdapter = true)
data class Candidate(
    val content: Content? = null
)

interface GeminiApi {
    @POST("v1beta/models/gemini-3.8-flash:generateContent")
    suspend fun generateContent(
        @Query("key") apiKey: String,
        @Body request: GeminiRequest
    ): GeminiResponse
}

class GeminiStylerService {

    private val moshi = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()

    private val requestAdapter = moshi.adapter(GeminiRequest::class.java)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://generativelanguage.googleapis.com/")
        .client(okHttpClient)
        .addConverterFactory(MoshiConverterFactory.create(moshi))
        .build()

    private val api = retrofit.create(GeminiApi::class.java)

    /**
     * Sends the styling request to Gemini and returns executable JavaScript.
     */
    suspend fun generateUserScript(
        context: Context,
        userPrompt: String,
        websiteName: String,
        websiteUrl: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY

        val systemPrompt = """
            You are an expert web UI/UX designer and client-side user-script engineer for Mydes Browser.
            The user is viewing the website: "$websiteName" ($websiteUrl).
            The user wants to visually customize and restyle this website with the following prompt:
            "$userPrompt"

            Your task is to write a client-side JavaScript user-script that visually transforms and styles this website according to the user's creative request.
            For example, if the user asks for:
            - "masonry art wall": turn image cards, articles, or sections into a staggered masonry art gallery with textured borders, elegant shadows, framing, and artistic typography.
            - "solar system": deep space dark canvas, glowing orbital rings around containers, starry animated backgrounds, celestial gold and neon cyan accents, planetary spherical icons.
            - "cyberpunk terminal", "retro vaporwave", "minimalist editorial newspaper", etc.: dramatically transform the visual theme, fonts, cards, backgrounds, and layout.

            CRITICAL CODE REQUIREMENTS:
            1. Return ONLY pure executable JavaScript code. Do NOT output markdown code blocks (no ``` or ```javascript), no headers, and no commentary.
            2. Wrap the script in an immediately invoked function expression:
               (function() {
                   var existing = document.getElementById('mydes-ai-styler-style');
                   if (existing) existing.remove();

                   var style = document.createElement('style');
                   style.id = 'mydes-ai-styler-style';
                   style.textContent = `... comprehensive custom CSS here ...`;
                   document.head.appendChild(style);
               })();
            3. Make the CSS strong with `!important` flags where needed to override the host site's existing styles.
            4. Keep the core functionality, links, and text readable and usable.
        """.trimIndent()

        val request = GeminiRequest(
            contents = listOf(
                Content(parts = listOf(Part(text = "Please create the user-script to restyle $websiteName: $userPrompt")))
            ),
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
            generationConfig = GenerationConfig(temperature = 0.5f)
        )
        val requestJson = try { requestAdapter.toJson(request) } catch (_: Exception) { request.toString() }

        try {
            if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
                val fallback = getFallbackScript(userPrompt, websiteName)
                if (fallback != null) {
                    ApiRequestLogger.logRequest(context, "generateUserScript", websiteName, websiteUrl, userPrompt, requestJson, fallback, true)
                    return@withContext Result.success(fallback)
                }
                val err = "Please set your GEMINI_API_KEY in the Secrets panel in AI Studio."
                ApiRequestLogger.logRequest(context, "generateUserScript", websiteName, websiteUrl, userPrompt, requestJson, err, false)
                return@withContext Result.failure(Exception(err))
            }

            val response = api.generateContent(apiKey, request)
            val rawOutput = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: run {
                    val err = "Gemini returned an empty response."
                    ApiRequestLogger.logRequest(context, "generateUserScript", websiteName, websiteUrl, userPrompt, requestJson, err, false)
                    return@withContext Result.failure(Exception(err))
                }

            val cleanedScript = cleanScriptOutput(rawOutput)
            ApiRequestLogger.logRequest(context, "generateUserScript", websiteName, websiteUrl, userPrompt, requestJson, cleanedScript, true)
            Result.success(cleanedScript)
        } catch (e: Exception) {
            val fallback = getFallbackScript(userPrompt, websiteName)
            if (fallback != null) {
                ApiRequestLogger.logRequest(context, "generateUserScript", websiteName, websiteUrl, userPrompt, requestJson, fallback, true)
                Result.success(fallback)
            } else {
                ApiRequestLogger.logRequest(context, "generateUserScript", websiteName, websiteUrl, userPrompt, requestJson, e.message ?: "Error", false)
                Result.failure(e)
            }
        }
    }

    /**
     * Feedback loop: takes screenshot of current rendering, screen dimensions, and optimizes the user script accordingly.
     */
    suspend fun generateUserScriptWithFeedback(
        context: Context,
        userPrompt: String,
        websiteName: String,
        websiteUrl: String,
        screenshotBase64: String?,
        screenWidth: Int,
        screenHeight: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY

        val systemPrompt = """
            You are an expert web UI/UX designer and client-side user-script engineer for Mydes Browser.
            The user is viewing the website: "$websiteName" ($websiteUrl) with screen dimensions ${screenWidth}x${screenHeight}px.
            The user wants to visually customize and restyle this website with the prompt: "$userPrompt".

            CRITICAL LAYOUT & SELECTOR RULES:
            1. BE EXTREMELY CAREFUL WITH SELECTORS: Do NOT apply universal styles, thick borders, or large padding/margins globally to generic tags like `div`, `article`, `section`, or `li`. On content-heavy, e-commerce, or complex sites (like Amazon, eBay, YouTube, Reddit), doing so destroys grid layouts, product cards, and flex containers.
            2. Target specific elements: Use precise selectors (e.g. `.s-result-item`, `[data-component-type="s-search-result"]`, `.card`, `.product-item`, `article`, `header`, `footer`) rather than blanket container styling.
            3. Screenshot Feedback Analysis: If an attached screenshot is provided, analyze the rendered result. If the layout is broken, cluttered, or elements overlap, fix the CSS rules and selectors immediately in this optimized version.
            4. Return ONLY pure executable JavaScript wrapped in an IIFE setting 'mydes-ai-styler-style' style element. No markdown blocks, no commentary.
        """.trimIndent()

        val parts = mutableListOf<Part>()
        parts.add(Part(text = "Please analyze the attached screenshot (if any) and screen dimensions (${screenWidth}x${screenHeight}) for $websiteName, and provide an optimized, flawless user-script for prompt: $userPrompt"))
        
        if (!screenshotBase64.isNullOrBlank()) {
            parts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = screenshotBase64)))
        }

        val request = GeminiRequest(
            contents = listOf(Content(parts = parts)),
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
            generationConfig = GenerationConfig(temperature = 0.4f)
        )
        val requestJson = try { requestAdapter.toJson(request) } catch (_: Exception) { request.toString() }

        try {
            if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
                val fallback = getFallbackScript(userPrompt, websiteName) ?: "(function(){})();"
                ApiRequestLogger.logRequest(context, "generateUserScriptWithFeedback", websiteName, websiteUrl, userPrompt, requestJson, fallback, true)
                return@withContext Result.success(fallback)
            }

            val response = api.generateContent(apiKey, request)
            val rawOutput = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: run {
                    val err = "Gemini returned empty response."
                    ApiRequestLogger.logRequest(context, "generateUserScriptWithFeedback", websiteName, websiteUrl, userPrompt, requestJson, err, false)
                    return@withContext Result.failure(Exception(err))
                }

            val cleaned = cleanScriptOutput(rawOutput)
            ApiRequestLogger.logRequest(context, "generateUserScriptWithFeedback", websiteName, websiteUrl, userPrompt, requestJson, cleaned, true)
            Result.success(cleaned)
        } catch (e: Exception) {
            val fallback = getFallbackScript(userPrompt, websiteName)
            if (fallback != null) {
                ApiRequestLogger.logRequest(context, "generateUserScriptWithFeedback", websiteName, websiteUrl, userPrompt, requestJson, fallback, true)
                Result.success(fallback)
            } else {
                ApiRequestLogger.logRequest(context, "generateUserScriptWithFeedback", websiteName, websiteUrl, userPrompt, requestJson, e.message ?: "Error", false)
                Result.failure(e)
            }
        }
    }

    /**
     * Adapts and refines the existing website theme for a specific newly loaded webpage on the same site.
     */
    suspend fun adaptUserScriptForWebpage(
        context: Context,
        basePrompt: String,
        baseScript: String,
        pageTitle: String,
        pageUrl: String,
        domain: String
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY

        val systemPrompt = """
            You are an expert web UI/UX designer and client-side user-script engineer for Mydes Browser.
            The user previously applied the theme style: "$basePrompt" for the website "$domain".
            The user has now navigated to a new webpage on the same website:
            Webpage Title: "$pageTitle"
            Webpage URL: "$pageUrl"

            Your task is to adapt and update the client-side JavaScript user-script for this specific webpage.
            Ensure that the theme ("$basePrompt") remains completely coherent, cohesive, and visually integrated on this new webpage.

            CRITICAL CODE REQUIREMENTS:
            1. Return ONLY pure executable JavaScript code. Do NOT output markdown code blocks (no ``` or ```javascript), no headers, and no commentary.
            2. Wrap the script in an immediately invoked function expression setting 'mydes-ai-styler-style'.
            3. Use `!important` flags where necessary. Keep text readable.
        """.trimIndent()

        val promptSummary = if (baseScript.length > 500) baseScript.take(500) + "..." else baseScript
        val request = GeminiRequest(
            contents = listOf(
                Content(parts = listOf(Part(text = "Adapt user-script theme '$basePrompt' for webpage '$pageTitle' ($pageUrl). Base script reference: $promptSummary")))
            ),
            systemInstruction = Content(parts = listOf(Part(text = systemPrompt))),
            generationConfig = GenerationConfig(temperature = 0.4f)
        )
        val requestJson = try { requestAdapter.toJson(request) } catch (_: Exception) { request.toString() }

        try {
            if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
                val fallback = adaptFallbackScript(baseScript, basePrompt, pageTitle)
                ApiRequestLogger.logRequest(context, "adaptUserScriptForWebpage", domain, pageUrl, basePrompt, requestJson, fallback, true)
                return@withContext Result.success(fallback)
            }

            val response = api.generateContent(apiKey, request)
            val rawOutput = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: run {
                    val fallback = adaptFallbackScript(baseScript, basePrompt, pageTitle)
                    ApiRequestLogger.logRequest(context, "adaptUserScriptForWebpage", domain, pageUrl, basePrompt, requestJson, fallback, true)
                    return@withContext Result.success(fallback)
                }

            val cleanedScript = cleanScriptOutput(rawOutput)
            ApiRequestLogger.logRequest(context, "adaptUserScriptForWebpage", domain, pageUrl, basePrompt, requestJson, cleanedScript, true)
            Result.success(cleanedScript)
        } catch (e: Exception) {
            val fallback = adaptFallbackScript(baseScript, basePrompt, pageTitle)
            ApiRequestLogger.logRequest(context, "adaptUserScriptForWebpage", domain, pageUrl, basePrompt, requestJson, fallback, true)
            Result.success(fallback)
        }
    }

    private fun adaptFallbackScript(baseScript: String, prompt: String, pageTitle: String): String {
        if (baseScript.contains("mydes-ai-styler-style")) {
            return baseScript
        }
        return getFallbackScript(prompt, pageTitle) ?: baseScript
    }

    private fun cleanScriptOutput(raw: String): String {
        val trimmed = raw.trim()
        val codeBlockRegex = Regex("```(?:javascript|js)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
        val match = codeBlockRegex.find(trimmed)
        if (match != null) {
            return match.groupValues[1].trim()
        }
        val iifeStart = trimmed.indexOf("(function")
        if (iifeStart != -1) {
            val iifeEnd = trimmed.lastIndexOf(")")
            if (iifeEnd > iifeStart) {
                return trimmed.substring(iifeStart, iifeEnd + 1).trim()
            }
        }
        return trimmed.removePrefix("```javascript")
            .removePrefix("```js")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
    }

    private fun getFallbackScript(prompt: String, websiteName: String): String? {
        val p = prompt.lowercase()
        return when {
            p.contains("masonry") || p.contains("art") || p.contains("wall") -> """
                (function() {
                    var existing = document.getElementById('mydes-ai-styler-style');
                    if (existing) existing.remove();
                    var style = document.createElement('style');
                    style.id = 'mydes-ai-styler-style';
                    style.textContent = '''
                        body { background: #121214 !important; color: #f1f1f5 !important; font-family: -apple-system, sans-serif !important; }
                        img { border-radius: 12px !important; box-shadow: 0 8px 24px rgba(0,0,0,0.5) !important; transition: transform 0.3s ease !important; }
                        img:hover { transform: scale(1.02) !important; }
                        .s-result-item, article, .card, .product-item { background: #1e1e24 !important; border: 1px solid #2a2a35 !important; border-radius: 16px !important; padding: 12px !important; margin-bottom: 16px !important; box-shadow: 0 4px 12px rgba(0,0,0,0.3) !important; }
                        h1, h2, h3, h4 { color: #ffffff !important; letter-spacing: -0.5px !important; }
                    ''';
                    document.head.appendChild(style);
                })();
            """.trimIndent()
            p.contains("solar") || p.contains("space") || p.contains("cyber") -> """
                (function() {
                    var existing = document.getElementById('mydes-ai-styler-style');
                    if (existing) existing.remove();
                    var style = document.createElement('style');
                    style.id = 'mydes-ai-styler-style';
                    style.textContent = '''
                        body { background: #08090d !important; color: #00e5ff !important; font-family: monospace !important; }
                        .s-result-item, article, .card, div { border-color: #00e5ff33 !important; }
                        a { color: #00e5ff !important; text-shadow: 0 0 8px rgba(0,229,255,0.4) !important; }
                    ''';
                    document.head.appendChild(style);
                })();
            """.trimIndent()
            else -> null
        }
    }
}
