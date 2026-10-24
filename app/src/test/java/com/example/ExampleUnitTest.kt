package com.example

import org.junit.Assert.*
import org.junit.Test

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }



  @Test
  fun testGeneratedThemeEntityCreation() {
    val entity = com.example.data.model.GeneratedThemeEntity(
      id = 1L,
      domain = "google.com",
      websiteUrl = "https://www.google.com",
      websiteTitle = "Google",
      prompt = "Cyberpunk neon terminal",
      themeName = "Cyberpunk neon terminal",
      script = "(function(){})();",
      screenshotPath = "/data/user/0/com.example/files/theme_screenshots/theme_123.jpg"
    )
    assertEquals("google.com", entity.domain)
    assertEquals("Cyberpunk neon terminal", entity.prompt)
    assertNotNull(entity.screenshotPath)
  }
}
