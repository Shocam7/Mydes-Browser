package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("Mydes Browser", appName)
  }

  @Test
  fun `test domain extraction with Android Uri`() {
    val uri = android.net.Uri.parse("https://www.example.com/some/path?query=1")
    val host = uri.host?.lowercase() ?: ""
    val domain = if (host.startsWith("www.")) host.removePrefix("www.") else host
    assertEquals("example.com", domain)
  }

  @Test
  fun `test UIBlueprint parse with pure json`() {
    val json = """
      {
        "name": "Neon Grid",
        "dark": true,
        "bg": "#0a0c10",
        "surface": "#161b24",
        "accent": "#00ffcc",
        "customCss": "body { background: var(--m-bg); }",
        "backdropHtml": "<svg><circle r='10'/></svg>",
        "customJs": "MydesBridge.enable3DTilt(1.2);",
        "concept": "A retro-futuristic grid interface"
      }
    """.trimIndent()

    val bp = com.example.data.api.UIBlueprint.parse(json, "Test")
    org.junit.Assert.assertNotNull(bp)
    assertEquals("Neon Grid", bp?.name)
    assertEquals("#0a0c10", bp?.bg)
    assertEquals("body { background: var(--m-bg); }", bp?.customCss)
    assertEquals("<svg><circle r='10'/></svg>", bp?.backdropHtml)
    assertEquals("MydesBridge.enable3DTilt(1.2);", bp?.customJs)
  }

  @Test
  fun `test UIBlueprint parse with markdown fences and aliases`() {
    val json = """
      ```json
      {
        "title": "Solar Flare",
        "dark": false,
        "bg": "#ffffff",
        "custom_css": ".card { border-radius: 12px; }",
        "html": "<canvas id='stars'></canvas>",
        "js": "console.log('init');",
        "idea": "Bright solar system theme"
      }
      ```
    """.trimIndent()

    val bp = com.example.data.api.UIBlueprint.parse(json, "Fallback")
    org.junit.Assert.assertNotNull(bp)
    assertEquals("Solar Flare", bp?.name)
    assertEquals(".card { border-radius: 12px; }", bp?.customCss)
    assertEquals("<canvas id='stars'></canvas>", bp?.backdropHtml)
    assertEquals("console.log('init');", bp?.customJs)
    assertEquals("Bright solar system theme", bp?.concept)
  }

  @Test
  fun `test UIBlueprint parse wrapped script and script generation`() {
    val script = "(function(BP){ console.log(BP); })({\"name\":\"Wrapped\",\"customCss\":\"h1{color:red;}\"});"
    val bp = com.example.data.api.UIBlueprint.parse(script, "Test")
    org.junit.Assert.assertNotNull(bp)
    assertEquals("Wrapped", bp?.name)
    assertEquals("h1{color:red;}", bp?.customCss)

    val runtimeScript = com.example.data.api.MydesRuntime.script(bp!!)
    org.junit.Assert.assertTrue(runtimeScript.contains("Wrapped"))
    org.junit.Assert.assertTrue(runtimeScript.contains("h1{color:red;}"))
  }

  @Test
  fun `test ThoughtSummarizer extracts clean statements and pending snippet`() {
    val raw = "I will examine the DOM outline. Designing a sleek cyberpunk theme with neon green accents! For typography, monospace is best. Assembling CSS now"
    val (statements, snippet) = com.example.data.api.ThoughtSummarizer.summarize(raw)

    org.junit.Assert.assertTrue(statements.isNotEmpty())
    org.junit.Assert.assertTrue(statements.any { it.contains("Examine the DOM outline", ignoreCase = true) })
    org.junit.Assert.assertTrue(statements.any { it.contains("Designing a sleek cyberpunk theme", ignoreCase = true) })
    org.junit.Assert.assertTrue(statements.any { it.contains("Typography, monospace is best", ignoreCase = true) })
    assertEquals("Assembling CSS now", snippet)
  }

  @Test
  fun `test ThoughtSummarizer strips markdown bullets and cleans text`() {
    val raw = "**1. Analyze DOM:** Found main navigation bar.\n* 2. Choose colors: Deep obsidian and cyan.\n- 3. Layout: Clean broadsheet style."
    val (statements, _) = com.example.data.api.ThoughtSummarizer.summarize(raw)

    org.junit.Assert.assertTrue(statements.isNotEmpty())
    for (s in statements) {
      org.junit.Assert.assertFalse(s.startsWith("*"))
      org.junit.Assert.assertFalse(s.startsWith("-"))
    }
  }
}
