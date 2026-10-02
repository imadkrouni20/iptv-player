package com.example.iptvplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.iptvplayer.data.Channel
import com.example.iptvplayer.data.IptvRepository
import com.example.iptvplayer.data.StalkerClient
import com.example.iptvplayer.data.SubscriptionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed class Screen {
    object AddSubscription : Screen()
    object Content : Screen()
    data class Player(val url: String, val title: String) : Screen()
}

data class UiState(
    val screen: Screen = Screen.AddSubscription,
    val loading: Boolean = false,
    val error: String? = null,
    val channels: List<Channel> = emptyList(),
    val liveChannels: List<Channel> = emptyList(),
    val vodChannels: List<Channel> = emptyList(),
    val seriesChannels: List<Channel> = emptyList(),
    val tab: Int = 0,
    val savedType: String? = null,
    val savedName: String? = null,
    val search: String = ""
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = IptvRepository(app)
    private val store = SubscriptionStore(app)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    fun saveM3u(url: String, name: String) {
        store.clear(); store.saveType("m3u")
        store.save(mapOf("url" to url, "name" to name))
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { repo.loadM3u(url) }
                _state.value = _state.value.copy(
                    loading = false,
                    liveChannels = list,
                    channels = list,
                    screen = Screen.Content,
                    savedType = "m3u",
                    savedName = name
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "خطأ")
            }
        }
    }

    fun saveXtream(host: String, user: String, pass: String, name: String) {
        store.clear(); store.saveType("xtream")
        store.save(mapOf("host" to host, "user" to user, "pass" to pass, "name" to name))
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val live = withContext(Dispatchers.IO) { repo.loadXtreamLive(host, user, pass) }
                val vod = try {
                    withContext(Dispatchers.IO) { repo.loadXtreamVod(host, user, pass) }
                } catch (_: Exception) { emptyList() }
                val series = try {
                    withContext(Dispatchers.IO) { repo.loadXtreamSeries(host, user, pass) }
                } catch (_: Exception) { emptyList() }
                _state.value = _state.value.copy(
                    loading = false,
                    liveChannels = live,
                    vodChannels = vod,
                    seriesChannels = series,
                    channels = live,
                    screen = Screen.Content,
                    savedType = "xtream",
                    savedName = name,
                    tab = 0
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "خطأ")
            }
        }
    }

    fun saveStalker(portal: String, mac: String, name: String) {
        store.clear(); store.saveType("stalker")
        store.save(mapOf("host" to portal, "mac" to mac, "name" to name))
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val client = StalkerClient(portal, mac)
                val live = withContext(Dispatchers.IO) { client.loadLiveChannels() }
                val vod = try {
                    withContext(Dispatchers.IO) { client.loadVod() }
                } catch (_: Exception) { emptyList() }
                _state.value = _state.value.copy(
                    loading = false,
                    liveChannels = live,
                    vodChannels = vod,
                    seriesChannels = emptyList(),
                    channels = live,
                    screen = Screen.Content,
                    savedType = "stalker",
                    savedName = name,
                    tab = 0
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "خطأ")
            }
        }
    }

    fun setTab(i: Int) {
        val s = _state.value
        val list = when (i) {
            0 -> s.liveChannels
            1 -> s.vodChannels
            else -> s.seriesChannels
        }
        _state.value = s.copy(tab = i, channels = list, search = "")
    }

    fun setSearch(q: String) {
        val s = _state.value
        val source = when (s.tab) {
            0 -> s.liveChannels
            1 -> s.vodChannels
            else -> s.seriesChannels
        }
        val filtered = if (q.isBlank()) source else source.filter { it.name.contains(q, ignoreCase = true) }
        _state.value = s.copy(search = q, channels = filtered)
    }

    fun openPlayer(c: Channel) {
        val s = _state.value
        if (s.savedType == "stalker") {
            _state.value = s.copy(loading = true, error = null)
            viewModelScope.launch {
                try {
                    val host = store.get("host") ?: error("لا يوجد host")
                    val mac = store.get("mac") ?: error("لا يوجد mac")
                    val client = StalkerClient(host, mac)
                    val realUrl = withContext(Dispatchers.IO) {
                        client.handshake()
                        client.getProfile()
                        client.createLink(c.url, c.type)
                    }
                    if (realUrl.isBlank()) error("فشل الحصول على رابط البث")
                    _state.value = _state.value.copy(
                        loading = false,
                        screen = Screen.Player(realUrl, c.name)
                    )
                } catch (e: Exception) {
                    _state.value = _state.value.copy(loading = false, error = e.message ?: "خطأ")
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

    fun logout() {
        store.clear()
        _state.value = UiState()
    }
}
