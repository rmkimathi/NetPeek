package com.netpeek.app

import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // 1. Find the toolbar component from XML and bind it
        val toolbar = findViewById<Toolbar>(R.id.aboutToolbar)
        setSupportActionBar(toolbar)

        // 2. Enable the display of the back arrow asset via supportActionBar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val webView = findViewById<WebView>(R.id.webView)
        webView.webViewClient = WebViewClient()
        webView.settings.javaScriptEnabled = false   // not needed
        webView.loadUrl("file:///android_asset/about.html")
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}