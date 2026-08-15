package com.maritimefatigue.ai

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity

class PrivacyActivity : ComponentActivity() {
    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val webView = WebView(this).apply {
            settings.javaScriptEnabled = false
            settings.domStorageEnabled = false
            loadUrl("file:///android_asset/privacy.html")
        }
        setContentView(webView)
    }
}
