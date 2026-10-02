package com.example.iptvplayer.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.example.iptvplayer.MainViewModel
import com.example.iptvplayer.Screen
import com.example.iptvplayer.SortMode
import com.example.iptvplayer.UiState
import com.example.iptvplayer.data.ChannelType
import com.example.iptvplayer.data.Subscription

private val AppColors = darkColorScheme(
    primary = Color(0xFF9C88FF), onPrimary = Color(0xFF14042E),
    primaryContainer = Color(0xFF2C1A63), onPrimaryContainer = Color(0xFFD6CCFF),
    secondary = Color(0xFF00D2A8), onSecondary = Color(0xFF00201A),
    background = Color(0xFF0B0B14), onBackground = Color(0xFFEDEDF5),
    surface = Color(0xFF15151F), onSurface = Color(0xFFEDEDF5),
    surfaceVariant = Color(0xFF23232F), onSurfaceVariant = Color(0xFFB9B9C6),
    outline = Color(0xFF3A3A48), error = Color(0xFFFF6B6B)
)

@Composable
fun App(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    MaterialTheme(colorScheme = AppColors) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
            when (val s = state.screen) {
                Screen.SubscriptionsList -> SubscriptionsListScreen(vm, state)
                Screen.AddSubscription -> AddSubscriptionScreen(vm, state)
                Screen.Content -> ContentScreen(vm, state)
                is Screen.SeriesDetail -> SeriesDetailScreen(vm, state, s.seriesName)
                is Screen.Player -> PlayerScreen(vm, s.url, s.title)
            }
        }
    }
}

@Composable
fun ErrorCard(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.15f))
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Top) {
            Text(message, color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f), maxLines = 12,
                overflow = TextOverflow.Ellipsis)
            IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Close, contentDescription = "إغلاق",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
fun PosterBox(url: String?, label: String, size: Int = 56) {
    Box(
        Modifier.size(size.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model = url,
                contentDescription = label,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Text(label, color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionsListScreen(vm: MainViewModel, state: UiState) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("اشتراكاتي", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { vm.goAddSubscription() },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary) {
                Icon(Icons.Default.Add, contentDescription = "إضافة")
            }
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            state.error?.let { ErrorCard(it) { vm.clearError() } }
            if (state.subscriptions.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("لا توجد اشتراكات بعد", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Text("اضغط + لإضافة اشتراكك الأول",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item { Spacer(Modifier.height(8.dp)) }
                    items(state.subscriptions, key = { it.id }) { sub ->
                        SubscriptionCard(sub, { vm.connect(sub) }, { vm.deleteSubscription(sub.id) })
                    }
                    item { Spacer(Modifier.height(80.dp)) }
                }
            }
        }
    }
}

@Composable
fun SubscriptionCard(sub: Subscription, onOpen: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(sub.type.uppercase().take(3),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.size(14.dp))
            Column(Modifier.weight(1f)) {
                Text(sub.name, style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                val detail = if (sub.type == "m3u") sub.m3uUrl else sub.host
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onOpen) {
                Icon(Icons.Default.PlayArrow, contentDescription = "تشغيل",
                    tint = MaterialTheme.colorScheme.secondary)
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "حذف",
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSubscriptionScreen(vm: MainViewModel, state: UiState) {
    var mode by remember { mutableStateOf(0) }
    var name by remember { mutableStateOf("") }
    var m3u by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var user by remember { mutableStateOf("") }
    var pass by remember { mutableStateOf("") }
    var portal by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("00:1A:79:") }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("إضافة اشتراك", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.goSubscriptionsList() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface)
            )
        }
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(name, { name = it }, label = { Text("اسم الاشتراك") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("M3U") })
                FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Xtream") })
                FilterChip(selected = mode == 2, onClick = { mode = 2 }, label = { Text("Stalker") })
            }
            when (mode) {
                0 -> {
                    OutlinedTextField(m3u, { m3u = it }, label = { Text("رابط M3U") },
                        modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.addM3u(name.trim(), m3u.trim()) },
                        enabled = m3u.isNotBlank() && !state.loading,
                        modifier = Modifier.fillMaxWidth()) { Text("حفظ واتصال") }
                }
                1 -> {
                    OutlinedTextField(host, { host = it }, label = { Text("Host") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(user, { user = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(pass, { pass = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.addXtream(name.trim(), host.trim(), user.trim(), pass.trim()) },
                        enabled = host.isNotBlank() && user.isNotBlank() && pass.isNotBlank() && !state.loading,
                        modifier = Modifier.fillMaxWidth()) { Text("حفظ واتصال") }
                }
                else -> {
                    OutlinedTextField(portal, { portal = it }, label = { Text("Portal URL") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(mac, { mac = it }, label = { Text("MAC Address") }, modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.addStalker(name.trim(), portal.trim(), mac.trim()) },
                        enabled = portal.isNotBlank() && mac.isNotBlank() && !state.loading,
                        modifier = Modifier.fillMaxWidth()) { Text("حفظ واتصال") }
                }
            }
            if (state.loading) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("جارٍ الاتصال...")
                }
            }
            state.error?.let { ErrorCard(it) { vm.clearError() } }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentScreen(vm: MainViewModel, state: UiState) {
    var sortMenu by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(state.currentSub?.name?.ifBlank { "اشتراك" } ?: "اشتراك",
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Bold)
                },
                navigationIcon = {
                    IconButton(onClick = { vm.goSubscriptionsList() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { sortMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "ترتيب")
                        }
                        DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                            DropdownMenuItem(text = { Text("افتراضي") },
                                onClick = { vm.setSortMode(SortMode.DEFAULT); sortMenu = false })
                            DropdownMenuItem(text = { Text("أ - ي") },
                                onClick = { vm.setSortMode(SortMode.AZ); sortMenu = false })
                            DropdownMenuItem(text = { Text("ي - أ") },
                                onClick = { vm.setSortMode(SortMode.ZA); sortMenu = false })
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            val isXtream = state.currentSub?.type == "xtream"
            val isStalker = state.currentSub?.type == "stalker"
            if (isXtream || isStalker) {
                val tabs = listOf("قنوات", "أفلام", "مسلسلات")
                TabRow(selectedTabIndex = state.tab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary) {
                    tabs.forEachIndexed { i, t ->
                        Tab(selected = state.tab == i, onClick = { vm.setTab(i) },
                            text = { Text(t) })
                    }
                }
            }
            state.error?.let { ErrorCard(it) { vm.clearError() } }
            OutlinedTextField(
                value = state.search, onValueChange = { vm.setSearch(it) },
                label = { Text("بحث") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp))
            if (state.loading) {
                Row(Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("جارٍ التحميل...")
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.channels, key = { it.id }) { ch ->
                    val isSeries = ch.type == ChannelType.SERIES
                    val isPoster = ch.type == ChannelType.MOVIE || ch.type == ChannelType.SERIES
                    ListItem(
                        leadingContent = {
                            PosterBox(
                                url = ch.logo,
                                label = ch.name.take(2).uppercase(),
                                size = if (isPoster) 60 else 52
                            )
                        },
                        headlineContent = {
                            Text(ch.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            val g = ch.group
                            if (g != null && g.isNotEmpty()) Text("تصنيف: $g")
                        },
                        trailingContent = {
                            if (isSeries) {
                                Text("مواسم ›", color = MaterialTheme.colorScheme.primary,
                                    style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        modifier = Modifier.clickable {
                            if (isSeries && isStalker) vm.openSeries(ch) else vm.openPlayer(ch)
                        }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeriesDetailScreen(vm: MainViewModel, state: UiState, title: String) {
    var seasonMenu by remember { mutableStateOf(false) }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text(title.ifBlank { "المسلسل" }, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.backFromSeries() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { seasonMenu = true }) {
                            Icon(Icons.Default.Sort, contentDescription = "المواسم")
                        }
                        DropdownMenu(expanded = seasonMenu,
                            onDismissRequest = { seasonMenu = false }) {
                            state.seasons.forEachIndexed { i, s ->
                                DropdownMenuItem(
                                    text = { Text(s.name) },
                                    onClick = { vm.selectSeason(i); seasonMenu = false }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                    actionIconContentColor = MaterialTheme.colorScheme.onSurface)
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            val season = state.seasons.getOrNull(state.currentSeasonIdx)
            if (season != null) {
                Text("الموسم الحالي: ${season.name}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
            }
            if (state.seriesLoading) {
                Row(Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("جارٍ تحميل الحلقات...")
                }
            }
            state.error?.let { ErrorCard(it) { vm.clearError() } }
            LazyColumn(Modifier.fillMaxSize()) {
                items(state.episodes, key = { it.id }) { ep ->
                    ListItem(
                        leadingContent = {
                            Box(Modifier.size(40.dp)
                                .background(MaterialTheme.colorScheme.primaryContainer,
                                    RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        },
                        headlineContent = {
                            Text(ep.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        },
                        modifier = Modifier.clickable { vm.playEpisode(ep) }
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
                }
            }
        }
    }
}

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(vm: MainViewModel, url: String, title: String) {
    val context = LocalContext.current
    val view = LocalView.current
    var fullscreen by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        val activity = context as? Activity
        val original = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        onDispose {
            activity?.requestedOrientation =
                original ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

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

    DisposableEffect(fullscreen) {
        val activity = view.context as? Activity
        if (activity != null) {
            val controller = WindowCompat.getInsetsController(activity.window, view)
            if (fullscreen) {
                controller.hide(WindowInsetsCompat.Type.systemBars())
                controller.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            } else {
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose {
            val activity = view.context as? Activity
            if (activity != null) {
                WindowCompat.getInsetsController(activity.window, view)
                    .show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx -> PlayerView(ctx).apply {
                this.player = player
                useController = true
                setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
            } },
            modifier = Modifier.fillMaxSize()
        )
        Row(Modifier.align(Alignment.TopEnd).padding(8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = { vm.backToList() }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "رجوع", tint = Color.White)
            }
            IconButton(onClick = { fullscreen = !fullscreen }) {
                Icon(
                    if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                    contentDescription = "ملء الشاشة",
                    tint = Color.White
                )
            }
        }
        if (!fullscreen) {
            Text(title, color = Color.White, maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.TopStart).padding(16.dp))
        }
    }
}
