package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.example.data.MydesDatabase
import com.example.data.repository.BrowserRepository
import com.example.data.repository.WebsiteColorRepository
import com.example.ui.BrowserScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.viewmodel.BrowserViewModel

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: BrowserViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Enable whole-document drawing mode for WebView so webView.draw(canvas) captures the exact rendered page
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            try {
                android.webkit.WebView.enableSlowWholeDocumentDraw()
            } catch (_: Exception) {}
        }

        // Hardware acceleration flag & high refresh rate (90Hz/120Hz) matching Chrome browser
        val hasDriNode = try {
            val dri = java.io.File("/dev/dri")
            dri.exists() && (dri.listFiles()?.isNotEmpty() == true)
        } catch (_: Exception) {
            false
        }
        if (hasDriNode) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                android.view.WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            display?.supportedModes?.maxByOrNull { it.refreshRate }?.let { maxMode ->
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = maxMode.modeId
                }
            }
        }

        com.example.data.PrepackageDataSeeder.seedIfNeeded(applicationContext)

        val database = MydesDatabase.getInstance(applicationContext)
        val browserRepository = BrowserRepository(database.mydesDao())
        val websiteColorRepository = WebsiteColorRepository(database.mydesDao(), lifecycleScope)

        viewModel = ViewModelProvider(
            this,
            BrowserViewModel.provideFactory(application, browserRepository, websiteColorRepository)
        )[BrowserViewModel::class.java]

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    BrowserScreen(viewModel = viewModel)
                }
            }
        }
    }
}
