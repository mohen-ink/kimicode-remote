package com.mohen.kimicoderemote

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

data class HistoryEntry(
    val url: String,
    val title: String,
    val timestamp: Long
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
                    timestamp = obj.getLong("ts")
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: Context, url: String, title: String?) {
        val entries = load(context).toMutableList()
        entries.removeAll { it.url == url }
        entries.add(
            0,
            HistoryEntry(
                url = url,
                title = title?.takeIf { it.isNotBlank() } ?: url,
                timestamp = System.currentTimeMillis()
            )
        )
        while (entries.size > MAX_ENTRIES) entries.removeAt(entries.size - 1)
        save(context, entries)
    }

    fun clear(context: Context) {
        prefs(context).edit().remove(KEY).apply()
    }

    private fun save(context: Context, entries: List<HistoryEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("url", entry.url)
                    .put("title", entry.title)
                    .put("ts", entry.timestamp)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
