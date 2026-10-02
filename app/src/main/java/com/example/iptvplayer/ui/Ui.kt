package com.example.iptvplayer.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.example.iptvplayer.MainViewModel
import com.example.iptvplayer.Screen
import com.example.iptvplayer.UiState
import com.example.iptvplayer.data.Channel

@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    MaterialTheme {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            when (val s = state.screen) {
                Screen.AddSubscription -> AddSubscriptionScreen(vm, state)
                Screen.Content -> ContentScreen(vm, state)
                is Screen.Player -> PlayerScreen(vm, s.url, s.title)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSubscriptionScreen(vm: MainViewModel, state: UiState) {
    var mode by remember { mutableStateOf(0) }
    var m3u by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var portal by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("00:1A:79:") }

    Column(
        Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("إضافة اشتراك IPTV", style = MaterialTheme.typography.headlineSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("M3U") })
            FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Xtream") })
            FilterChip(selected = mode == 2, onClick = { mode = 2 }, label = { Text("Stalker") })
        }
        when (mode) {
            0 -> {
                OutlinedTextField(
                    value = m3u, onValueChange = { m3u = it },
                    label = { Text("رابط M3U") },
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { vm.saveM3u(m3u.trim()) },
                    enabled = m3u.isNotBlank() && !state.loading
                ) { Text("اتصال") }
            }
            1 -> {
                OutlinedTextField(host, { host = it }, label = { Text("Host (http://...)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { vm.saveXtream(host.trim(), user.trim(), pass.trim()) },
                    enabled = host.isNotBlank() && user.isNotBlank() && pass.isNotBlank() && !state.loading
                ) { Text("اتصال") }
            }
            else -> {
                OutlinedTextField(portal, { portal = it }, label = { Text("Portal URL (http://host:port/c)") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(mac, { mac = it }, label = { Text("MAC Address (00:1A:79:XX:XX:XX)") }, modifier = Modifier.fillMaxWidth())
                Button(
                    onClick = { vm.saveStalker(portal.trim(), mac.trim()) },
                    enabled = portal.isNotBlank() && mac.isNotBlank() && !state.loading
                ) { Text("اتصال") }
            }
        }
        if (state.loading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp))
                Text("جارٍ التحميل...")
            }
        }
        state.error?.let { Text("خطأ: $it", color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
fun ChannelRow(ch: Channel, onClick: () -> Unit) {
    Column {
        ListItem(
            headlineContent = { Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            supportingContent = {
                val g = ch.group
                if (g != null && g.isNotEmpty()) Text("تصنيف: $g")
            },
            modifier = Modifier.clickable { onClick() }
        )
        HorizontalDivider()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentScreen(vm: MainViewModel, state: UiState) {
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("اشتراكي") },
            actions = { TextButton(onClick = { vm.logout() }) { Text("خروج") } }
        )
        val showTabs = state.savedType == "xtream" || state.savedType == "stalker"
        if (showTabs) {
            val tabs = if (state.savedType == "stalker") listOf("قنوات", "أفلام")
                       else listOf("قنوات", "أفلام", "مسلسلات")
            TabRow(selectedTabIndex = state.tab) {
                tabs.forEachIndexed { i, title ->
                    Tab(selected = state.tab == i, onClick = { vm.setTab(i) }, text = { Text(title) })
                }
            }
        }
        OutlinedTextField(
            value = state.search,
            onValueChange = { vm.setSearch(it) },
            label = { Text("بحث") },
            modifier = Modifier.fillMaxWidth().padding(8.dp)
        )
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (state.loading) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp))
                    Text("جارٍ التحميل...")
                }
            }
            state.error?.let {
                Text("خطأ: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            for (ch in state.channels) {
                ChannelRow(ch = ch, onClick = { vm.openPlayer(ch) })
            }
        }
    }
}

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: MainViewModel, url: String, title: String) {
    val context = LocalContext.current
    val player = remember {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent("VLC/3.0.18 LibVLC/3.0.18")
            .setAllowCrossProtocolRedirects(true)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(http))
            .build().apply {
                setMediaItem(MediaItem.fromUri(url))
                prepare()
                playWhenReady = true
            }
    }
    DisposableEffect(Unit) { onDispose { player.release() } }
    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { TextButton(onClick = { vm.backToList() }) { Text("رجوع") } }
        )
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply { this.player = player; useController = true } },
            modifier = Modifier.fillMaxSize()
        )
    }
}
