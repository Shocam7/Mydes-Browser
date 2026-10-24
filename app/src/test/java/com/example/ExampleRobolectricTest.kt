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
}
