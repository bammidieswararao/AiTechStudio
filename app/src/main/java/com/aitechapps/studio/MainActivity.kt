package com.aitechapps.studio

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.webkit.ConsoleMessage
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast

/** Hosts the studio page in a WebView. There is no network permission, so nothing can leave the phone. */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var bridge: StudioBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bg = Color.parseColor("#10141a")
        val root = FrameLayout(this)
        root.setBackgroundColor(bg)
        web = WebView(this)
        web.setBackgroundColor(bg)
        root.addView(web, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)

        // Edge-to-edge is forced on Android 15, so keep the page clear of the bars, notch and keyboard.
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                v.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            insets
        }
        root.requestApplyInsets()

        val debuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (debuggable) WebView.setWebContentsDebuggingEnabled(true)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.allowFileAccess = true
        s.allowContentAccess = false
        s.mediaPlaybackRequiresUserGesture = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.textZoom = 100
        s.cacheMode = WebSettings.LOAD_DEFAULT

        bridge = StudioBridge(this, web)
        web.addJavascriptInterface(bridge, "Android")

        web.webViewClient = object : WebViewClient() {
            // The app is fully local: never navigate away from the bundled page.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                request.url.scheme != "file"

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                Log.e("AiTechStudio", "WebView renderer gone, crashed=${detail.didCrash()}")
                Toast.makeText(this@MainActivity, "The editor ran out of memory and restarted. Try a smaller size.", Toast.LENGTH_LONG).show()
                recreate()
                return true
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (debuggable) Log.d("AiTechStudio", "js: ${m.message()} (${m.sourceId()}:${m.lineNumber()})")
                return true
            }
        }
        web.loadUrl("file:///android_asset/studio/index.html")
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        bridge.onResult(requestCode, if (resultCode == RESULT_OK) data?.data else null)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (bridge.isBusy) {
            Toast.makeText(this, "An export is running. Tap Cancel on the screen to stop it.", Toast.LENGTH_SHORT).show()
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
    }

    override fun onDestroy() {
        try { bridge.release() } catch (_: Exception) { }
        try {
            (web.parent as? ViewGroup)?.removeView(web)
            web.removeJavascriptInterface("Android")
            web.destroy()
        } catch (_: Exception) { }
        super.onDestroy()
    }
}
