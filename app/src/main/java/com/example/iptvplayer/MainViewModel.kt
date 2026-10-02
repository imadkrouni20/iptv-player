package com.example.iptvplayer

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.iptvplayer.data.Channel
import com.example.iptvplayer.data.IptvRepository
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
    val search: String = ""
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = IptvRepository(app)
    private val store = SubscriptionStore(app)

    private val _state = MutableStateFlow(UiState(savedType = store.getType()))
    val state: StateFlow<UiState> = _state

    fun saveM3u(url: String) {
        store.clear(); store.saveType("m3u"); store.save(mapOf("url" to url))
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val list = withContext(Dispatchers.IO) { repo.loadM3u(url) }
                _state.value = _state.value.copy(
                    loading = false,
                    liveChannels = list,
                    channels = list,
                    screen = Screen.Content,
                    savedType = "m3u"
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "خطأ")
            }
        }
    }

    fun saveXtream(host: String, user: String, pass: String) {
        store.clear(); store.saveType("xtream")
        store.save(mapOf("host" to host, "user" to user, "pass" to pass))
        _state.value = _state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            try {
                val live = withContext(Dispatchers.IO) { repo.loadXtreamLive(host, user, pass) }
                val vod = withContext(Dispatchers.IO) { repo.loadXtreamVod(host, user, pass) }
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
                    tab = 0
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(loading = false, error = e.message ?: "خطأ")
            }
        }
    }

    fun autoLoad() {
        when (store.getType()) {
            "m3u" -> store.get("url")?.let { saveM3u(it) }
            "xtream" -> {
                val h = store.get("host"); val u = store.get("user"); val p = store.get("pass")
                if (h != null && u != null && p != null) saveXtream(h, u, p)
            }
        }
    }

    fun setTab(i: Int) {
        val s = _state.value
        val list = when (i) {
            0 -> if (s.savedType == "m3u") s.liveChannels else s.liveChannels
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
        if (c.url.isBlank()) return
        _state.value = _state.value.copy(screen = Screen.Player(c.url, c.name))
    }

    fun backToList() {
        _state.value = _state.value.copy(screen = Screen.Content)
    }

    fun logout() {
        store.clear()
        _state.value = UiState()
    }
}
