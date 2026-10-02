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
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private var token: String = ""
    private var activeEndpoint: String? = null

    private val cookie = "mac=$mac; stb_lang=en; timezone=$timezone"
    private val userAgent =
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Safari/533.3"

    private val baseCandidates: List<String> = buildList {
        var p = portalUrl.trim()
        if (!p.startsWith("http")) p = "http://$p"
        p = p.trimEnd('/')
        listOf(
            "/portal.php",
            "/server/load.php",
            "/stalker_portal/server/load.php",
            "/c"
        ).forEach { suffix ->
            if (p.endsWith(suffix)) p = p.dropLast(suffix.length).trimEnd('/')
        }
        add(p)
        if (p.startsWith("http://")) add("https://" + p.removePrefix("http://"))
    }.distinct()

    private val endpointSuffixes = listOf(
        "/portal.php",
        "/server/load.php",
        "/stalker_portal/server/load.php",
        "/c/portal.php"
    )

    private fun candidateUrls(params: String): List<String> {
        val result = mutableListOf<String>()
        for (base in baseCandidates) {
            for (suffix in endpointSuffixes) {
                result += "$base$suffix?$params&JsHttpRequest=1-xml"
            }
        }
        val active = activeEndpoint
        if (active != null) {
            val activeUrl = "$active?$params&JsHttpRequest=1-xml"
            result.remove(activeUrl)
            result.add(0, activeUrl)
        }
        return result
    }

    private fun rawGet(url: String): String {
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

    private fun request(params: String, validate: ((String) -> Boolean)? = null): String {
        val urls = candidateUrls(params)
        val tried = mutableListOf<String>()
        var lastError: Exception? = null
        for (url in urls) {
            tried += url.substringBefore("?")
            try {
                val body = rawGet(url)
                if (validate != null && !validate(body)) {
                    lastError = IllegalStateException("استجابة غير صالحة")
                    continue
                }
                if (activeEndpoint == null) activeEndpoint = url.substringBefore("?")
                return body
            } catch (e: Exception) {
                lastError = e
            }
        }
        val triedList = tried.distinct().joinToString("\n")
        throw lastError?.let { Exception("${it.message}\n\nالمسارات المجرَّبة:\n$triedList") }
            ?: error("فشل الاتصال\n$triedList")
    }

    fun handshake() {
        val body = request(
            params = "type=stb&action=handshake&prehash=0&token=",
            validate = { it.contains("\"js\"") }
        )
        val json = JSONObject(body)
        val js = json.optJSONObject("js") ?: error("استجابة handshake غير صالحة: $body")
        token = js.optString("token", "")
        if (token.isEmpty()) error("توكن فارغ - الـ MAC غير مُفعّل على هذه البوابة")
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
        val idx = link.indexOf("http")
        if (idx > 0) link = link.substring(idx)
        return link.trim()
    }
}
