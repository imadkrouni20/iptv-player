package com.example.iptvplayer.data

data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String? = null,
    val group: String? = null,
    val type: ChannelType = ChannelType.LIVE
)

enum class ChannelType { LIVE, MOVIE, SERIES, EPISODE }

data class Season(
    val id: String,
    val name: String,
    val number: Int
)

data class Subscription(
    val id: String,
    val name: String,
    val type: String,
    val m3uUrl: String = "",
    val host: String = "",
    val user: String = "",
    val pass: String = "",
    val mac: String = ""
)
