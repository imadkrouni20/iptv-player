package com.example.iptvplayer.data

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class StalkerClient(
    private val portalUrl: String,
    private val mac: String,
    private val timezone: String = "Europe/Paris"
) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private var token: String = ""
    private val cookie: String = "mac=$mac; stb_lang=en; timezone=$timezone"
    private val userAgent =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Safari/533.3"

    private fun baseUrl(): String {
        var p = portalUrl.trim()
        if (!p.startsWith("http")) p = "http://$p"
        p = p.trimEnd('/')
        if (p.endsWith("/c")) p = p.dropLast(2)
        return p
    }

    private fun portalPhp(): String {
        val b = baseUrl()
        return if (b.contains("/stalker_portal")) "$b/server/load.php" else "$b/portal.php"
    }

    private fun request(params: String): String {
        val url = "${portalPhp()}?$params&JsHttpRequest=1-xml"
        val builder = Request.Builder()
            .url(url)
            .header("Cookie", cookie)
            .header("User-Agent", userAgent)
            .header("X-User-Agent", "Model: MAG250; Link: WiFi")
            .header("Accept", "*/*")
        if (token.isNotEmpty()) builder.header("Authorization", "Bearer $token")
        http.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            return resp.body?.string() ?: error("Empty body")
        }
    }

    fun handshake() {
        val body = request("type=stb&action=handshake&prehash=0&token=")
        val json = JSONObject(body)
        val js = json.optJSONObject("js") ?: error("Handshake: no js")
        token = js.optString("token", "")
        if (token.isEmpty()) error("توكن فارغ - تحقق من MAC أو البوابة")
    }

    fun getProfile() {
        request(
            "type=stb&action=get_profile&hd=1&num_banks=2&sn=${System.currentTimeMillis()}" +
                "&stb_type=MAG250&client_type=STB&image_version=218&video_out=hdmi" +
                "&device_id=&device_id2=&signature=&auth_second_step=1&hw_version=1.7-BD-00" +
                "&not_valid_token=0&metrics=%7B%7D&api_signature=262&mkv=true&hls=true"
        )
    }

    fun loadLiveChannels(): List<Channel> {
        handshake()
        getProfile()
        val body = request("type=itv&action=get_all_channels")
        val json = JSONObject(body)
        val data = json.optJSONObject("js")?.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<Channel>()
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val id = o.optString("id")
            val cmd = o.optString("cmd")
            if (cmd.isEmpty()) continue
            list += Channel(
                id = "stalker_live_$id",
                name = o.optString("name", "Channel"),
                url = cmd,
                logo = o.optString("logo").takeIf { it.isNotBlank() },
                group = o.optString("tv_genre_id").takeIf { it.isNotBlank() },
                type = ChannelType.LIVE
            )
        }
        return list
    }

    fun loadVod(): List<Channel> {
        handshake()
        getProfile()
        val body = request(
            "type=vod&action=get_ordered_list&category=*&genre=*&force_ch_link_check=" +
                "&fav=0&sortby=added&hd=0&p=1"
        )
        val json = JSONObject(body)
        val data = json.optJSONObject("js")?.optJSONArray("data") ?: return emptyList()
        val list = mutableListOf<Channel>()
        for (i in 0 until data.length()) {
            val o = data.getJSONObject(i)
            val id = o.optString("id")
            val cmd = o.optString("cmd")
            if (cmd.isEmpty()) continue
            list += Channel(
                id = "stalker_vod_$id",
                name = o.optString("name", "Movie"),
                url = cmd,
                logo = o.optString("screenshot_uri").takeIf { it.isNotBlank() },
                group = null,
                type = ChannelType.MOVIE
            )
        }
        return list
    }

    fun createLink(cmd: String, type: ChannelType): String {
        val t = if (type == ChannelType.MOVIE) "vod" else "itv"
        val encoded = URLEncoder.encode(cmd, "UTF-8")
        val body = request(
            "type=$t&action=create_link&cmd=$encoded&series=&forced_storage=undefined" +
                "&disable_ad=0&download=0&force_ch_link_check=0"
        )
        val json = JSONObject(body)
        val js = json.optJSONObject("js") ?: error("create_link: no js")
        var link = js.optString("cmd", "")
        if (link.isEmpty()) link = js.optString("id", "")
        // قد يكون "ffmpeg http://..." أو "auto http://..."
        val idx = link.indexOf("http")
        if (idx > 0) link = link.substring(idx)
        return link.trim()
    }
}
