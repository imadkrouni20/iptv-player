package com.example.iptvplayer.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class StalkerClient(
    portalUrl: String,
    private val mac: String,
    private val timezone: String = "Africa/Casablanca"
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS)
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

    private fun rawGet(url: String): String {
        val b = Request.Builder().url(url)
            .header("Cookie", cookie)
            .header("User-Agent", userAgent)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Accept", "*/*")
        if (token.isNotEmpty()) b.header("Authorization", "Bearer $token")
        http.newCall(b.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string() ?: error("Empty body")
        }
    }

    private fun request(params: String, validate: ((String) -> Boolean)? = null): String {
        val urls = candidateUrls(params)
        val errors = mutableListOf<String>()
        for (url in urls) {
            try {
                val body = rawGet(url)
                if (validate != null && !validate(body)) {
                    errors += "${url.substringBefore("?")}: استجابة غير صالحة"
                    continue
                }
                if (activeEndpoint == null) activeEndpoint = url.substringBefore("?")
                return body
            } catch (e: Throwable) {
                errors += "${url.substringBefore("?")}: ${e.message?.take(80) ?: e.javaClass.simpleName}"
            }
        }
        throw Exception(errors.joinToString("\n").take(600))
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

    fun loadLiveChannels(): List<Channel> {
        handshake(); getProfile()
        val js = try { JSONObject(request("type=itv&action=get_all_channels")).optJSONObject("js") } catch (_: Exception) { null }
            ?: return emptyList()
        val data = js.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<Channel>()
        for (i in 0 until data.length()) {
            val o = try { data.getJSONObject(i) } catch (_: Exception) { continue }
            val rawId = o.optString("id")
            val cmd = o.optString("cmd")
            if (cmd.isEmpty()) continue
            list += Channel(
                id = "sl_${rawId.ifBlank { "i$i" }}_$i",
                name = o.optString("name", "Channel"),
                url = cmd,
                logo = o.optString("logo").takeIf { it.isNotBlank() && it != "null" },
                group = o.optString("tv_genre_id").takeIf { it.isNotBlank() },
                type = ChannelType.LIVE
            )
        }
        return list
    }

    fun loadVod(): List<Channel> {
        handshake(); getProfile()
        val body = request("type=vod&action=get_ordered_list&category=*&genre=*" +
            "&force_ch_link_check=&fav=0&sortby=added&hd=0&p=1")
        val js = try { JSONObject(body).optJSONObject("js") } catch (_: Exception) { null }
            ?: return emptyList()
        val data = js.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<Channel>()
        for (i in 0 until data.length()) {
            val o = try { data.getJSONObject(i) } catch (_: Exception) { continue }
            val rawId = o.optString("id")
            val cmd = o.optString("cmd")
            if (cmd.isEmpty()) continue
            list += Channel(
                id = "sv_${rawId.ifBlank { "i$i" }}_$i",
                name = o.optString("name", "Movie"),
                url = cmd,
                logo = o.optString("screenshot_uri").takeIf { it.isNotBlank() && it != "null" },
                group = null,
                type = ChannelType.MOVIE
            )
        }
        return list
    }

    fun loadSeries(): List<Channel> {
        handshake(); getProfile()
        val body = request("type=series&action=get_ordered_list&category=*&genre=*" +
            "&force_ch_link_check=&fav=0&sortby=added&hd=0&p=1")
        val js = try { JSONObject(body).optJSONObject("js") } catch (_: Exception) { null }
            ?: return emptyList()
        val data = js.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<Channel>()
        for (i in 0 until data.length()) {
            val o = try { data.getJSONObject(i) } catch (_: Exception) { continue }
            val rawId = o.optString("id")
            list += Channel(
                id = "ss_${rawId.ifBlank { "i$i" }}_$i",
                name = o.optString("name", "Series"),
                url = rawId,
                logo = o.optString("screenshot_uri").takeIf { it.isNotBlank() && it != "null" },
                group = null,
                type = ChannelType.SERIES
            )
        }
        return list
    }

    fun loadSeasons(seriesId: String): List<Season> {
        val body = request("type=series&action=get_ordered_list&movie_id=$seriesId")
        val js = try { JSONObject(body).optJSONObject("js") } catch (_: Exception) { null }
            ?: return emptyList()
        val arr = js.optJSONArray("data") ?: return emptyList()
        val seasons = mutableListOf<Season>()
        for (i in 0 until arr.length()) {
            val o = try { arr.getJSONObject(i) } catch (_: Exception) { continue }
            val sid = o.optString("id")
            val num = o.optInt("season_number", i + 1)
            val name = o.optString("name").ifBlank { "الموسم $num" }
            if (sid.isNotEmpty()) seasons += Season(sid, name, num)
        }
        return seasons.sortedBy { it.number }
    }

    fun loadEpisodes(seriesId: String, seasonId: String): List<Channel> {
        val body = request("type=series&action=get_ordered_list&movie_id=$seriesId&season_id=$seasonId")
        val js = try { JSONObject(body).optJSONObject("js") } catch (_: Exception) { null }
            ?: return emptyList()
        val arr = js.optJSONArray("data") ?: return emptyList()
        val eps = mutableListOf<Channel>()
        for (i in 0 until arr.length()) {
            val o = try { arr.getJSONObject(i) } catch (_: Exception) { continue }
            val id = o.optString("id")
            val cmd = o.optString("cmd")
            if (cmd.isEmpty()) continue
            val num = o.optInt("series_number", i + 1)
            val title = o.optString("name").ifBlank { "حلقة $num" }
            eps += Channel(
                id = "se_${id}_$i",
                name = "%02d. %s".format(num, title),
                url = cmd,
                logo = o.optString("screenshot_uri").takeIf { it.isNotBlank() && it != "null" },
                group = null,
                type = ChannelType.EPISODE
            )
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
