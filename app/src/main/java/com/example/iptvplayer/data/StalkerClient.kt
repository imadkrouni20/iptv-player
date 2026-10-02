package com.example.iptvplayer.data

import android.util.JsonReader
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class StalkerClient(
    portalUrl: String,
    private val mac: String,
    private val timezone: String = "Africa/Casablanca"
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var token: String = ""
    private var activeEndpoint: String? = null

    private val cookie = "mac=$mac; stb_lang=en; timezone=$timezone"
    private val userAgent =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Safari/533.3"

    private val baseUrls: List<String> = buildList {
        var p = portalUrl.trim()
        if (!p.startsWith("http")) p = "http://$p"
        p = p.trimEnd('/')
        listOf("/portal.php", "/server/load.php",
            "/stalker_portal/server/load.php", "/c").forEach { suffix ->
            if (p.endsWith(suffix)) p = p.dropLast(suffix.length).trimEnd('/')
        }
        add("http://" + p.removePrefix("http://").removePrefix("https://"))
    }.distinct()

    private val endpointSuffixes = listOf("/portal.php", "/c/portal.php", "/server/load.php")

    private fun candidateUrls(params: String): List<String> {
        val result = mutableListOf<String>()
        activeEndpoint?.let { result += "$it?$params&JsHttpRequest=1-xml" }
        for (base in baseUrls) for (s in endpointSuffixes) {
            val u = "$base$s?$params&JsHttpRequest=1-xml"
            if (!result.contains(u)) result += u
        }
        return result
    }

    private fun buildRequest(url: String): Request {
        val b = Request.Builder().url(url)
            .header("Cookie", cookie)
            .header("User-Agent", userAgent)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Accept", "*/*")
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        return b.build()
    }

    // طلب نصي عادي (للردود الصغيرة)
    private fun request(params: String, validate: ((String) -> Boolean)? = null): String {
        val urls = candidateUrls(params)
        val errors = mutableListOf<String>()
        for (url in urls) {
            try {
                http.newCall(buildRequest(url)).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body?.string() ?: error("Empty body")
                    if (validate != null && !validate(body)) {
                        errors += "${url.substringBefore("?")}: استجابة غير صالحة"
                        return@use
                    }
                    if (activeEndpoint == null) activeEndpoint = url.substringBefore("?")
                    return body
                }
            } catch (e: Throwable) {
                errors += "${url.substringBefore("?")}: ${e.message?.take(80)}"
            }
        }
        throw Exception(errors.joinToString("\n").take(600))
    }

    // طلب متدفّق (للردود الضخمة) - يعيد InputStream
    private fun openStream(params: String): InputStream {
        val active = activeEndpoint
            ?: throw IllegalStateException("يجب استدعاء handshake أولاً")
        val url = "$active?$params&JsHttpRequest=1-xml"
        val resp = http.newCall(buildRequest(url)).execute()
        if (!resp.isSuccessful) {
            resp.close()
            error("HTTP ${resp.code}")
        }
        return resp.body?.byteStream() ?: run {
            resp.close()
            error("Empty body")
        }
    }

    fun handshake() {
        val body = request(
            "type=stb&action=handshake&prehash=0&token=",
            validate = { it.contains("\"js\"") }
        )
        val js = try { JSONObject(body).optJSONObject("js") } catch (_: Exception) { null }
            ?: error("استجابة handshake غير صالحة")
        token = js.optString("token", "")
        if (token.isEmpty()) error("الـ MAC غير مُفعّل على هذه البوابة")
    }

    fun getProfile() {
        try {
            request(
                "type=stb&action=get_profile&hd=1&num_banks=2&sn=${System.currentTimeMillis()}" +
                    "&stb_type=MAG250&client_type=STB&image_version=218&video_out=hdmi" +
                    "&device_id=&device_id2=&signature=&auth_second_step=1&hw_version=1.7-BD-00" +
                    "&not_valid_token=0&metrics=%7B%7D&api_signature=262&mkv=true&hls=true"
            )
        } catch (_: Throwable) {}
    }

    // ====== تحليل متدفّق عام لـ {js:{data:[...]}} ======
    private fun streamJsData(input: InputStream, onItem: (Map<String, String>) -> Unit) {
        JsonReader(input.bufferedReader()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "js") {
                    r.beginObject()
                    while (r.hasNext()) {
                        if (r.nextName() == "data") {
                            r.beginArray()
                            while (r.hasNext()) {
                                r.beginObject()
                                val map = HashMap<String, String>(8)
                                while (r.hasNext()) {
                                    val key = r.nextName()
                                    try {
                                        map[key] = r.nextString()
                                    } catch (_: Exception) {
                                        r.skipValue()
                                    }
                                }
                                r.endObject()
                                onItem(map)
                            }
                            r.endArray()
                        } else r.skipValue()
                    }
                    r.endObject()
                } else r.skipValue()
            }
            r.endObject()
        }
    }

    private fun streamJsArray(input: InputStream, onItem: (Map<String, String>) -> Unit) {
        JsonReader(input.bufferedReader()).use { r ->
            r.beginObject()
            while (r.hasNext()) {
                if (r.nextName() == "js") {
                    r.beginArray()
                    while (r.hasNext()) {
                        r.beginObject()
                        val map = HashMap<String, String>(8)
                        while (r.hasNext()) {
                            val key = r.nextName()
                            try { map[key] = r.nextString() } catch (_: Exception) { r.skipValue() }
                        }
                        r.endObject()
                        onItem(map)
                    }
                    r.endArray()
                } else r.skipValue()
            }
            r.endObject()
        }
    }

    fun loadLiveChannels(): List<Channel> {
        handshake(); getProfile()
        val list = ArrayList<Channel>(2000)
        var i = 0
        openStream("type=itv&action=get_all_channels").use { input ->
            streamJsData(input) { m ->
                val cmd = m["cmd"].orEmpty()
                if (cmd.isNotEmpty()) {
                    val rawId = m["id"].orEmpty()
                    val logo = m["logo"]?.takeIf { it.isNotBlank() && it != "null" }
                    val group = m["tv_genre_id"]?.takeIf { it.isNotBlank() }
                    list += Channel(
                        id = "sl_${rawId.ifBlank { "i$i" }}_$i",
                        name = m["name"]?.ifBlank { "Channel" } ?: "Channel",
                        url = cmd,
                        logo = logo,
                        group = group,
                        type = ChannelType.LIVE
                    )
                }
                i++
            }
        }
        return list
    }

    fun loadVod(): List<Channel> {
        handshake(); getProfile()
        val list = ArrayList<Channel>(500)
        var i = 0
        openStream("type=vod&action=get_ordered_list&category=*&genre=*" +
            "&force_ch_link_check=&fav=0&sortby=added&hd=0&p=1").use { input ->
            streamJsData(input) { m ->
                val cmd = m["cmd"].orEmpty()
                if (cmd.isNotEmpty()) {
                    val rawId = m["id"].orEmpty()
                    val logo = m["screenshot_uri"]?.takeIf { it.isNotBlank() && it != "null" }
                    list += Channel(
                        id = "sv_${rawId.ifBlank { "i$i" }}_$i",
                        name = m["name"]?.ifBlank { "Movie" } ?: "Movie",
                        url = cmd,
                        logo = logo,
                        group = null,
                        type = ChannelType.MOVIE
                    )
                }
                i++
            }
        }
        return list
    }

    fun loadSeries(): List<Channel> {
        handshake(); getProfile()
        val list = ArrayList<Channel>(500)
        var i = 0
        openStream("type=series&action=get_ordered_list&category=*&genre=*" +
            "&force_ch_link_check=&fav=0&sortby=added&hd=0&p=1").use { input ->
            streamJsData(input) { m ->
                val rawId = m["id"].orEmpty()
                if (rawId.isNotEmpty()) {
                    val logo = m["screenshot_uri"]?.takeIf { it.isNotBlank() && it != "null" }
                    list += Channel(
                        id = "ss_${rawId}_$i",
                        name = m["name"]?.ifBlank { "Series" } ?: "Series",
                        url = rawId,
                        logo = logo,
                        group = null,
                        type = ChannelType.SERIES
                    )
                }
                i++
            }
        }
        return list
    }

    fun loadSeasons(seriesId: String): List<Season> {
        handshake(); getProfile()
        val seasons = mutableListOf<Season>()
        var i = 0
        openStream("type=series&action=get_ordered_list&movie_id=$seriesId").use { input ->
            streamJsData(input) { m ->
                val sid = m["id"].orEmpty()
                val num = m["season_number"]?.toIntOrNull() ?: (i + 1)
                val name = m["name"]?.ifBlank { "الموسم $num" } ?: "الموسم $num"
                if (sid.isNotEmpty()) seasons += Season(sid, name, num)
                i++
            }
        }
        return seasons.sortedBy { it.number }
    }

    fun loadEpisodes(seriesId: String, seasonId: String): List<Channel> {
        val eps = mutableListOf<Channel>()
        var i = 0
        openStream("type=series&action=get_ordered_list&movie_id=$seriesId&season_id=$seasonId").use { input ->
            streamJsData(input) { m ->
                val cmd = m["cmd"].orEmpty()
                if (cmd.isNotEmpty()) {
                    val id = m["id"].orEmpty()
                    val num = m["series_number"]?.toIntOrNull() ?: (i + 1)
                    val title = m["name"]?.ifBlank { "حلقة $num" } ?: "حلقة $num"
                    val logo = m["screenshot_uri"]?.takeIf { it.isNotBlank() && it != "null" }
                    eps += Channel(
                        id = "se_${id.ifBlank { "i$i" }}_$i",
                        name = "%02d. %s".format(num, title),
                        url = cmd,
                        logo = logo,
                        group = null,
                        type = ChannelType.EPISODE
                    )
                }
                i++
            }
        }
        return eps.sortedBy { it.name }
    }

    fun createLink(cmd: String, type: ChannelType): String {
        val t = when (type) {
            ChannelType.MOVIE -> "vod"
            ChannelType.SERIES, ChannelType.EPISODE -> "series"
            else -> "itv"
        }
        val encoded = URLEncoder.encode(cmd, "UTF-8")
        val body = request("type=$t&action=create_link&cmd=$encoded&series=" +
            "&forced_storage=undefined&disable_ad=0&download=0&force_ch_link_check=0")
        val js = try { JSONObject(body).optJSONObject("js") } catch (_: Exception) { null }
            ?: error("create_link: استجابة غير صالحة")
        var link = js.optString("cmd", "")
        if (link.isEmpty()) link = js.optString("id", "")
        val idx = link.indexOf("http")
        if (idx > 0) link = link.substring(idx)
        return link.trim()
    }
}
