package com.mohen.kimicoderemote

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

data class HistoryEntry(
    val url: String,
    val title: String,
    val timestamp: Long,
    val cookies: String = ""
) {
    val host: String
        get() = try {
            URL(url).host
        } catch (e: Exception) {
            url
        }
}

object HistoryStore {

    private const val PREFS = "history"
    private const val KEY = "entries"
    private const val MAX_ENTRIES = 50

    fun load(context: Context): List<HistoryEntry> {
        val json = prefs(context).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(json)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                HistoryEntry(
                    url = obj.getString("url"),
                    title = obj.optString("title", obj.getString("url")),
                    timestamp = obj.getLong("ts"),
                    cookies = obj.optString("cookies", "")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, url: String, title: String?, cookies: String? = null) {
        val entries = load(context).toMutableList()
        entries.removeAll { it.url == url }
        entries.add(
            0,
            HistoryEntry(
                url = url,
                title = title?.takeIf { it.isNotBlank() } ?: url,
                timestamp = System.currentTimeMillis(),
                cookies = cookies ?: entries.firstOrNull { it.url == url }?.cookies ?: ""
            )
        )
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        save(context, entries)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    /** 当前 WebView 中该 url 的 cookie 快照（含会话 cookie）。 */
    fun captureCookies(url: String): String =
        CookieManager.getInstance().getCookie(url) ?: ""

    /** 将快照写回 CookieManager，返回是否恢复了 cookie。 */
    fun restoreCookies(entry: HistoryEntry): Boolean {
        if (entry.cookies.isBlank()) return false
        val manager = CookieManager.getInstance()
        val url = if (entry.url.startsWith("http://") || entry.url.startsWith("https://")) {
            entry.url
        } else {
            "http://${entry.host}"
        }
        entry.cookies.split(";")
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains("=") }
            .forEach { manager.setCookie(url, "$it; Path=/") }
        manager.flush()
        return true
    }

    private fun save(context: Context, entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("ts", entry.timestamp)
                    .put("cookies", entry.cookies)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
