package com.mohen.kimicoderemote

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.UUID

internal class WebViewBarColors(
    private val webView: WebView,
    private val installer: String,
    onColors: (Int, Int) -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackLock = Any()
    private var active = true
    private var documentId: String? = null
    private var documentUrl: DocumentUrl? = null
    private val documentUrls = mutableSetOf<DocumentUrl>()
    private var documentCommitted = false
    private var colorsCallback: ((Int, Int) -> Unit)? = onColors
    private var lastColors: Pair<Int, Int>? = null

    init {
        webView.addJavascriptInterface(this, "KimiBarColor")
    }

    fun onPageStarted(url: String?) {
        synchronized(callbackLock) {
            if (!active) return
            documentId = null
            documentUrl = null
            documentUrls.clear()
            documentCommitted = false
            mainHandler.removeCallbacksAndMessages(null)
            lastColors = null
        }
        webView.evaluateJavascript(DISPOSE_WATCHER, null)
        synchronized(callbackLock) {
            if (!active) return
            documentId = UUID.randomUUID().toString()
            documentUrl = documentUrl(url)?.also { documentUrls.add(it) }
        }
    }

    fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
        synchronized(callbackLock) {
            if (!active || view !== webView || documentId == null) return
            val updated = documentUrl(url) ?: return
            if (updated != documentUrl(webView.url)) return
            val previous = documentUrl ?: return
            if (updated.origin != previous.origin || (isReload && updated !in documentUrls)) return
            documentUrl = updated
            if (documentUrls.size >= 64) documentUrls.clear()
            documentUrls.add(updated)
        }
        install(view)
    }

    fun onPageCommitVisible(view: WebView?, url: String?) {
        synchronized(callbackLock) {
            if (!isCurrentPage(view, url)) return
            documentCommitted = true
        }
        install(view)
    }

    fun onPageFinished(view: WebView?, url: String?) {
        synchronized(callbackLock) {
            if (!isCurrentPage(view, url) || (!documentCommitted && webView.progress < 100)) return
        }
        install(view)
    }

    private fun isCurrentPage(view: WebView?, url: String?): Boolean {
        val callbackUrl = documentUrl(url) ?: return false
        return active && view === webView && callbackUrl in documentUrls &&
            documentUrl(webView.url) == documentUrl
    }

    private fun install(view: WebView?) {
        val (id, url) = synchronized(callbackLock) {
            if (!active || view !== webView) return
            val currentUrl = webView.url ?: return
            if (documentUrl(currentUrl) != documentUrl) return
            (documentId ?: return) to currentUrl
        }
        val script = """
            (function() {
                var expected = new URL(${JSONObject.quote(url)}, window.location.href);
                var actual = new URL(window.location.href);
                expected.hash = actual.hash = '';
                if (actual.href !== expected.href) return;
                window.__kimiBarDocument = ${JSONObject.quote(id)};
                $installer
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
    }

    private fun documentUrl(value: String?): DocumentUrl? {
        if (value == null) return null
        val uri = Uri.parse(value)
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        val port = if (uri.port == -1) {
            if (scheme == "https") 443 else 80
        } else uri.port
        return DocumentUrl(
            origin = Triple(scheme, host, port),
            userInfo = uri.encodedUserInfo,
            path = uri.encodedPath?.ifEmpty { "/" } ?: "/",
            query = uri.encodedQuery
        )
    }

    private data class DocumentUrl(
        val origin: Triple<String, String, Int>,
        val userInfo: String?,
        val path: String,
        val query: String?
    )

    @JavascriptInterface
    fun onColors(documentId: String, top: String, bottom: String) {
        val topColor = parseOpaqueColor(top) ?: return
        val bottomColor = parseOpaqueColor(bottom) ?: return
        synchronized(callbackLock) {
            if (!active || documentId != this.documentId) return
            mainHandler.post {
                synchronized(callbackLock) {
                    if (!active || documentId != this.documentId) return@post
                    val colors = topColor to bottomColor
                    if (colors == lastColors) return@post
                    lastColors = colors
                    colorsCallback?.invoke(topColor, bottomColor)
                }
            }
        }
    }

    fun release() {
        synchronized(callbackLock) {
            if (!active) return
            active = false
            documentId = null
            documentUrl = null
            documentUrls.clear()
            colorsCallback = null
            lastColors = null
            mainHandler.removeCallbacksAndMessages(null)
        }
        webView.removeJavascriptInterface("KimiBarColor")
        webView.evaluateJavascript(DISPOSE_WATCHER, null)
    }

    private fun parseOpaqueColor(value: String): Int? {
        if (value.length !in 1..10 || value.any { it !in '0'..'9' }) return null
        val color = value.toLongOrNull() ?: return null
        if (color !in 0xFF000000L..0xFFFFFFFFL) return null
        return color.toInt()
    }

    private companion object {
        const val DISPOSE_WATCHER =
            "if (window.__kimiBarWatcher) window.__kimiBarWatcher.dispose();"
    }
}
