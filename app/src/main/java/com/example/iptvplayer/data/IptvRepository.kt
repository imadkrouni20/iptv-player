package com.example.iptvplayer.data

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

class IptvRepository(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun fetch(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string() ?: error("Empty body")
        }
    }

    fun loadM3u(url: String): List<Channel> {
        val text = fetch(url)
        val result = mutableListOf<Channel>()
        var name: String? = null
        var logo: String? = null
        var group: String? = null
        val logoRx = Regex("tvg-logo=\"([^\"]*)\"")
        val groupRx = Regex("group-title=\"([^\"]*)\"")
        var i = 0
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            when {
                line.startsWith("#EXTINF") -> {
                    name = line.substringAfterLast(',').trim()
                    logo = logoRx.find(line)?.groupValues?.get(1)
                    group = groupRx.find(line)?.groupValues?.get(1)
                }
                line.startsWith("http") && !name.isNullOrBlank() -> {
                    val lower = line.lowercase()
                    val type = when {
                        lower.contains("/movie/") -> ChannelType.MOVIE
                        lower.contains("/series/") -> ChannelType.SERIES
                        else -> ChannelType.LIVE
                    }
                    result += Channel("m3u_${i++}", name!!, line, logo, group, type)
                    name = null; logo = null; group = null
                }
            }
        }
        return result
    }

    private fun xtreamApi(host: String, user: String, pass: String, action: String?): String {
        val base = host.trimEnd('/')
        val url = buildString {
            append("$base/player_api.php?username=$user&password=$pass")
            if (action != null) append("&action=$action")
        }
        return fetch(url)
    }

    private fun parseArray(json: String): JSONArray = JSONArray(json)

    fun loadXtreamLive(host: String, user: String, pass: String): List<Channel> {
        val arr = parseArray(xtreamApi(host, user, pass, "get_live_streams"))
        val base = host.trimEnd('/')
        val list = mutableListOf<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            list += Channel(
                id = "live_$id",
                name = o.optString("name", "Channel"),
                url = "$base/live/$user/$pass/$id.ts",
                logo = o.optString("stream_icon").takeIf { it.isNotBlank() },
                group = o.optString("category_id").takeIf { it.isNotBlank() },
                type = ChannelType.LIVE
            )
        }
        return list
    }

    fun loadXtreamVod(host: String, user: String, pass: String): List<Channel> {
        val arr = parseArray(xtreamApi(host, user, pass, "get_vod_streams"))
        val base = host.trimEnd('/')
        val list = mutableListOf<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id")
            val ext = o.optString("container_extension", "mp4").ifBlank { "mp4" }
            list += Channel(
                id = "vod_$id",
                name = o.optString("name", "Movie"),
                url = "$base/movie/$user/$pass/$id.$ext",
                logo = o.optString("stream_icon").takeIf { it.isNotBlank() },
                group = o.optString("category_id").takeIf { it.isNotBlank() },
                type = ChannelType.MOVIE
            )
        }
        return list
    }

    fun loadXtreamSeries(host: String, user: String, pass: String): List<Channel> {
        val arr = parseArray(xtreamApi(host, user, pass, "get_series"))
        val list = mutableListOf<Channel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("series_id")
            list += Channel(
                id = "series_$id",
                name = o.optString("name", "Series"),
                url = "",
                logo = o.optString("cover").takeIf { it.isNotBlank() },
                group = o.optString("category_id").takeIf { it.isNotBlank() },
                type = ChannelType.SERIES
            )
        }
        return list
    }
}

class SubscriptionStore(context: Context) {
    private val prefs = context.getSharedPreferences("sub_prefs", Context.MODE_PRIVATE)

    fun saveType(t: String) = prefs.edit().putString("type", t).apply()
    fun getType(): String? = prefs.getString("type", null)

    fun save(map: Map<String, String>) {
        val e = prefs.edit()
        map.forEach { (k, v) -> e.putString(k, v) }
        e.apply()
    }
    fun get(key: String): String? = prefs.getString(key, null)
    fun clear() = prefs.edit().clear().apply()
}
