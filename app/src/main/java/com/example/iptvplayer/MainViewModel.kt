package com.example.iptvplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.iptvplayer.data.Channel
import com.example.iptvplayer.data.IptvRepository
import com.example.iptvplayer.data.StalkerClient
import com.example.iptvplayer.data.Subscription
import com.example.iptvplayer.data.SubscriptionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

sealed class Screen {
    object SubscriptionsList : Screen()
    object AddSubscription : Screen()
    object Content : Screen()
    data class Player(val url: String, val title: String) : Screen()
}

enum class SortMode { DEFAULT, AZ, ZA }

data class UiState(
    val screen: Screen = Screen.SubscriptionsList,
    val loading: Boolean = false,
    val error: String? = null,
    val subscriptions: List<Subscription> = emptyList(),
    val currentSub: Subscription? = null,
    val channels: List<Channel> = emptyList(),
    val liveChannels: List<Channel> = emptyList(),
    val vodChannels: List<Channel> = emptyList(),
    val seriesChannels: List<Channel> = emptyList(),
    val tab: Int = 0,
    val search: String = "",
    val sortMode: SortMode = SortMode.DEFAULT
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = IptvRepository(app)
    private val store = SubscriptionStore(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    init { refreshSubscriptions() }

    fun refreshSubscriptions() {
        _state.value = _state.value.copy(subscriptions = store.loadAll())
    }

    fun goAddSubscription() {
        _state.value = _state.value.copy(screen = Screen.AddSubscription, error = null)
    }

    fun goSubscriptionsList() {
        _state.value = _state.value.copy(
            screen = Screen.SubscriptionsList,
            error = null,
            liveChannels = emptyList(), vodChannels = emptyList(),
            seriesChannels = emptyList(), channels = emptyList(),
            currentSub = null, search = "", tab = 0, sortMode = SortMode.DEFAULT
        )
        refreshSubscriptions()
    }

    fun addM3u(name: String, url: String) {
        val sub = Subscription(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "M3U" }, type = "m3u", m3uUrl = url
        )
        store.upsert(sub); refreshSubscriptions(); connect(sub)
    }

    fun addXtream(name: String, host: String, user: String, pass: String) {
        val sub = Subscription(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Xtream" }, type = "xtream",
            host = host, user = user, pass = pass
        )
        store.upsert(sub); refreshSubscriptions(); connect(sub)
    }

    fun addStalker(name: String, portal: String, mac: String) {
        val sub = Subscription(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "Stalker" }, type = "stalker",
            host = portal, mac = mac
        )
        store.upsert(sub); refreshSubscriptions(); connect(sub)
    }

    fun deleteSubscription(id: String) {
        store.delete(id); refreshSubscriptions()
    }

    private fun safeError(t: Throwable): String {
        val m = t.message?.trim().orEmpty()
        return if (m.isEmpty()) t.javaClass.simpleName else m.take(500)
    }

    fun connect(sub: Subscription) {
        store.setLastOpened(sub.id)
        _state.value = _state.value.copy(loading = true, error = null, currentSub = sub)
        viewModelScope.launch {
            try {
                when (sub.type) {
                    "m3u" -> {
                        val list = withContext(Dispatchers.IO) { repo.loadM3u(sub.m3uUrl) }
                        _state.value = _state.value.copy(
                            loading = false, liveChannels = list,
                            channels = applySort(list, _state.value.sortMode),
                            screen = Screen.Content, tab = 0
                        )
                    }
                    "xtream" -> {
                        val live = withContext(Dispatchers.IO) {
                            repo.loadXtreamLive(sub.host, sub.user, sub.pass)
                        }
                        val vod = try {
                            withContext(Dispatchers.IO) {
                                repo.loadXtreamVod(sub.host, sub.user, sub.pass)
                            }
                        } catch (_: Throwable) { emptyList() }
                        val series = try {
                            withContext(Dispatchers.IO) {
                                repo.loadXtreamSeries(sub.host, sub.user, sub.pass)
                            }
                        } catch (_: Throwable) { emptyList() }
                        _state.value = _state.value.copy(
                            loading = false,
                            liveChannels = live, vodChannels = vod, seriesChannels = series,
                            channels = applySort(live, _state.value.sortMode),
                            screen = Screen.Content, tab = 0
                        )
                    }
                    "stalker" -> {
                        val client = StalkerClient(sub.host, sub.mac)
                        val live = withContext(Dispatchers.IO) { client.loadLiveChannels() }
                        val vod = try {
                            withContext(Dispatchers.IO) { client.loadVod() }
                        } catch (_: Throwable) { emptyList() }
                        _state.value = _state.value.copy(
                            loading = false,
                            liveChannels = live, vodChannels = vod,
                            seriesChannels = emptyList(),
                            channels = applySort(live, _state.value.sortMode),
                            screen = Screen.Content, tab = 0
                        )
                    }
                }
            } catch (t: Throwable) {
                _state.value = _state.value.copy(loading = false, error = safeError(t))
            }
        }
    }

    private fun applySort(src: List<Channel>, mode: SortMode): List<Channel> = when (mode) {
        SortMode.DEFAULT -> src
        SortMode.AZ -> src.sortedBy { it.name.lowercase() }
        SortMode.ZA -> src.sortedByDescending { it.name.lowercase() }
    }

    private fun currentTabSource(s: UiState): List<Channel> = when (s.tab) {
        0 -> s.liveChannels
        1 -> s.vodChannels
        else -> s.seriesChannels
    }

    private fun recomputeChannels() {
        val s = _state.value
        val src = currentTabSource(s)
        val filtered = if (s.search.isBlank()) src
            else src.filter { it.name.contains(s.search, ignoreCase = true) }
        _state.value = s.copy(channels = applySort(filtered, s.sortMode))
    }

    fun setTab(i: Int) {
        _state.value = _state.value.copy(tab = i, search = "")
        recomputeChannels()
    }
    fun setSearch(q: String) {
        _state.value = _state.value.copy(search = q); recomputeChannels()
    }
    fun setSortMode(mode: SortMode) {
        _state.value = _state.value.copy(sortMode = mode); recomputeChannels()
    }
    fun clearError() {
        _state.value = _state.value.copy(error = null)
    }

    fun openPlayer(c: Channel) {
        val s = _state.value
        if (s.currentSub?.type == "stalker") {
            _state.value = s.copy(loading = true, error = null)
            viewModelScope.launch {
                try {
                    val sub = s.currentSub
                    val client = StalkerClient(sub.host, sub.mac)
                    val realUrl = withContext(Dispatchers.IO) {
                        client.handshake()
                        client.getProfile()
                        client.createLink(c.url, c.type)
                    }
                    if (realUrl.isBlank()) error("فشل الحصول على رابط البث")
                    _state.value = _state.value.copy(
                        loading = false, screen = Screen.Player(realUrl, c.name)
                    )
                } catch (t: Throwable) {
                    _state.value = _state.value.copy(loading = false, error = safeError(t))
                }
            }
        } else {
            if (c.url.isBlank()) return
            _state.value = s.copy(screen = Screen.Player(c.url, c.name))
        }
    }

    fun backToList() {
        _state.value = _state.value.copy(screen = Screen.Content)
    }
}
