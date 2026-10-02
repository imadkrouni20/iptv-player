package com.example.iptvplayer.data

import android.content.Context
import android.util.JsonReader
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.InputStream
import java.util.concurrent.TimeUnit

class IptvRepository(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private fun openStream(url: String): InputStream {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18")
            .build()
        val resp = http.newCall(req).execute()
        if (!resp.isSuccessful) {
            resp.close()
            error("HTTP ${resp.code}")
        }
        return resp.body?.byteStream() ?: run {
            resp.close()
            error("Empty body")
        }
    }

    fun loadM3u(url: String): List<Channel> {
        val req = Request.Builder().url(url)
            .header("User-Agent", "VLC/3.0.18 LibVLC/3.0.18").build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val stream = resp.body?.byteStream() ?: error("Empty body")
            val result = ArrayList<Channel>(2000)
            var name: String? = null
            var logo: String? = null
            var group: String? = null
            val logoRx = Regex("tvg-logo=\"([^\"]*)\"")
            val groupRx = Regex("group-title=\"([^\"]*)\"")
            var i = 0
            stream.bufferedReader().useLines { lines ->
                for (raw in lines) {
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
            }
            return result
        }
    }

    private fun xtreamUrl(host: String, user: String, pass: String, action: String): String {
        val base = host.trimEnd('/')
        return "$base/player_api.php?username=$user&password=$pass&action=$action"
    }

    // قراءة حقل من JSON: نستخدم nextString لأي نوع لأنه يدعم النص والأرقام والقيم المنطقية
    private fun readValue(r: JsonReader): String {
        return try {
            r.nextString()
        } catch (_: Exception) {
            r.skipValue()
            ""
        }
    }

    private fun streamArray(input: InputStream, onItem: (Map<String, String>) -> Unit) {
        JsonReader(input.bufferedReader()).use { r ->
            r.beginArray()
            while (r.hasNext()) {
                r.beginObject()
                val map = HashMap<String, String>(8)
                while (r.hasNext()) {
                    val key = r.nextName()
                    map[key] = readValue(r)
                }
                r.endObject()
                onItem(map)
            }
            r.endArray()
        }
    }

    fun loadXtreamLive(host: String, user: String, pass: String): List<Channel> {
        val base = host.trimEnd('/')
        val list = ArrayList<Channel>(2000)
        openStream(xtreamUrl(host, user, pass, "get_live_streams")).use { input ->
            streamArray(input) { m ->
                val id = m["stream_id"].orEmpty()
                if (id.isNotEmpty()) {
                    list += Channel(
                        id = "live_$id",
                        name = m["name"]?.ifBlank { "Channel" } ?: "Channel",
                        url = "$base/live/$user/$pass/$id.ts",
                        logo = m["stream_icon"]?.takeIf { it.isNotBlank() && it != "null" },
                        group = m["category_id"]?.takeIf { it.isNotBlank() },
                        type = ChannelType.LIVE
                    )
                }
            }
        }
        return list
    }

    fun loadXtreamVod(host: String, user: String, pass: String): List<Channel> {
        val base = host.trimEnd('/')
        val list = ArrayList<Channel>(500)
        openStream(xtreamUrl(host, user, pass, "get_vod_streams")).use { input ->
            streamArray(input) { m ->
                val id = m["stream_id"].orEmpty()
                if (id.isNotEmpty()) {
                    val ext = m["container_extension"]?.ifBlank { "mp4" } ?: "mp4"
                    list += Channel(
                        id = "vod_$id",
                        name = m["name"]?.ifBlank { "Movie" } ?: "Movie",
                        url = "$base/movie/$user/$pass/$id.$ext",
                        logo = m["stream_icon"]?.takeIf { it.isNotBlank() && it != "null" },
                        group = m["category_id"]?.takeIf { it.isNotBlank() },
                        type = ChannelType.MOVIE
                    )
                }
            }
        }
        return list
    }

    fun loadXtreamSeries(host: String, user: String, pass: String): List<Channel> {
        val list = ArrayList<Channel>(500)
        openStream(xtreamUrl(host, user, pass, "get_series")).use { input ->
            streamArray(input) { m ->
                val id = m["series_id"].orEmpty()
                if (id.isNotEmpty()) {
                    list += Channel(
                        id = "series_$id",
                        name = m["name"]?.ifBlank { "Series" } ?: "Series",
                        url = "",
                        logo = m["cover"]?.takeIf { it.isNotBlank() && it != "null" },
                        group = m["category_id"]?.takeIf { it.isNotBlank() },
                        type = ChannelType.SERIES
                    )
                }
            }
        }
        return list
    }
}
