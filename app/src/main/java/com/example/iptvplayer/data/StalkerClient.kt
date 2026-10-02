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
        listOf(
            "/portal.php", "/server/load.php",
            "/stalker_portal/server/load.php", "/c"
        ).forEach { suffix ->
            if (p.endsWith(suffix)) p = p.dropLast(suffix.length).trimEnd('/')
        }
        val hostPart = p.removePrefix("http://").removePrefix("https://")
        add("http://$hostPart")
    }.distinct()

    private val endpointSuffixes = listOf(
        "/portal.php",
        "/c/portal.php",
        "/server/load.php"
    )

    private fun candidateUrls(params: String): List<String> {
        val result = mutableListOf<String>()
        activeEndpoint?.let { result += "$it?$params&JsHttpRequest=1-xml" }
        for (base in baseUrls) {
            for (suffix in endpointSuffixes) {
                val url = "$base$suffix?$params&JsHttpRequest=1-xml"
                if (!result.contains(url)) result += url
            }
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
                val msg = e.message?.take(80) ?: e.javaClass.simpleName
                errors += "${url.substringBefore("?")}: $msg"
            }
        }
        val joined = errors.joinToString("\n")
        throw Exception(joined.take(600))
    }

    fun handshake() {
        val body = request(
            params = "type=stb&action=handshake&prehash=0&token=",
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
        } catch (_: Throwable) { /* بعض البوابات لا تحتاج profile */ }
    }

    fun loadLiveChannels(): List<Channel> {
        handshake()
        getProfile()
        val body = request("type=itv&action=get_all_channels")
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
                id = "sl_${rawId.ifBlank { "i$i" }}_$i",
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
            "type=vod&action=get_ordered_list&category=*&genre=*" +
                "&force_ch_link_check=&fav=0&sortby=added&hd=0&p=1"
        )
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
        val js = try { JSONObject(body).optJSONObject("js") } catch (_: Exception) { null }
            ?: error("create_link: استجابة غير صالحة")
        var link = js.optString("cmd", "")
        if (link.isEmpty()) link = js.optString("id", "")
        val idx = link.indexOf("http")
        if (idx > 0) link = link.substring(idx)
        return link.trim()
    }
}
