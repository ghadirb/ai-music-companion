package com.ghadirb.aimusic

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.ghadirb.aimusic.billing.MyketBillingGateway
import com.ghadirb.aimusic.premium.PremiumFeature
import com.ghadirb.aimusic.billing.PurchaseUiState
import com.ghadirb.aimusic.ui.premium.LocalPremiumAccess
import com.ghadirb.aimusic.ui.premium.PremiumAccess
import com.ghadirb.aimusic.ui.premium.PremiumScreen
import com.ghadirb.aimusic.ui.premium.PremiumViewModel
import com.ghadirb.aimusic.ui.premium.UpgradeDialog
import com.ghadirb.aimusic.ui.screens.smartplaylist.SmartPlaylistScreen
import com.ghadirb.aimusic.ui.screens.stats.StatisticsScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.compose.ui.platform.LocalContext
import com.ghadirb.aimusic.ui.components.LocalQueueActions
import com.ghadirb.aimusic.ui.components.MiniPlayerBar
import com.ghadirb.aimusic.ui.components.QueueActions
import androidx.compose.runtime.CompositionLocalProvider
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.ui.navigation.ROUTE_ALBUM_DETAIL
import com.ghadirb.aimusic.ui.navigation.ROUTE_ARTIST_DETAIL
import com.ghadirb.aimusic.ui.navigation.ROUTE_PLAYER
import com.ghadirb.aimusic.ui.navigation.ROUTE_PREMIUM
import com.ghadirb.aimusic.ui.navigation.ROUTE_SMART_PLAYLIST
import com.ghadirb.aimusic.ui.navigation.ROUTE_STATS
import com.ghadirb.aimusic.ui.navigation.ROUTE_HISTORY
import com.ghadirb.aimusic.ui.screens.history.HistoryScreen
import com.ghadirb.aimusic.ui.navigation.ROUTE_TASTE
import com.ghadirb.aimusic.ui.screens.stats.TasteEvolutionScreen
import com.ghadirb.aimusic.ui.navigation.ROUTE_PLAYLIST_DETAIL
import com.ghadirb.aimusic.ui.navigation.Screen
import com.ghadirb.aimusic.ui.navigation.albumDetailRoute
import com.ghadirb.aimusic.ui.navigation.artistDetailRoute
import com.ghadirb.aimusic.ui.navigation.playlistDetailRoute
import com.ghadirb.aimusic.ui.screens.albums.AlbumDetailScreen
import com.ghadirb.aimusic.ui.screens.albums.AlbumsScreen
import com.ghadirb.aimusic.ui.screens.artists.ArtistDetailScreen
import com.ghadirb.aimusic.ui.screens.artists.ArtistsScreen
import com.ghadirb.aimusic.ui.screens.favorites.FavoritesScreen
import com.ghadirb.aimusic.ui.screens.folders.FoldersScreen
import com.ghadirb.aimusic.ui.screens.home.HomeScreen
import com.ghadirb.aimusic.ui.screens.library.LibraryScreen
import com.ghadirb.aimusic.ui.screens.player.PlayerScreen
import com.ghadirb.aimusic.ui.screens.player.rememberPlayerViewModel
import com.ghadirb.aimusic.ui.screens.playlists.PlaylistDetailScreen
import com.ghadirb.aimusic.ui.screens.playlists.PlaylistsScreen
import com.ghadirb.aimusic.ui.screens.settings.SettingsScreen
import com.ghadirb.aimusic.ui.theme.AiMusicCompanionTheme
import java.net.URLDecoder

class MainActivity : ComponentActivity() {
    private var openPlayerRequest by mutableStateOf(false)
    private lateinit var billingGateway: MyketBillingGateway

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AiMusicApp
        billingGateway = MyketBillingGateway(this)
        app.purchases.gateway = billingGateway
        openPlayerRequest = intent.getBooleanExtra("open_player", false)
        val uiPreferences = getSharedPreferences("ui_preferences", MODE_PRIVATE)

        setContent {
            var darkTheme by rememberSaveable { mutableStateOf(uiPreferences.getBoolean("dark_theme", true)) }
            AiMusicCompanionTheme(darkTheme = darkTheme) {
                Surface {
                    AppRoot(
                        repository = app.repository,
                        openPlayerOnLaunch = openPlayerRequest,
                        onPlayerOpened = { openPlayerRequest = false },
                        darkTheme = darkTheme,
                        onThemeChange = { enabled ->
                            darkTheme = enabled
                            uiPreferences.edit().putBoolean("dark_theme", enabled).apply()
                        }
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_player", false)) openPlayerRequest = true
    }

    override fun onDestroy() {
        if (::billingGateway.isInitialized) {
            billingGateway.dispose()
            val app = application as AiMusicApp
            if (app.purchases.gateway === billingGateway) app.purchases.gateway = null
        }
        super.onDestroy()
    }
}

@OptIn(ExperimentalPermissionsApi::class, UnstableApi::class)
@Composable
private fun AppRoot(
    repository: com.ghadirb.aimusic.data.repository.MusicRepository,
    openPlayerOnLaunch: Boolean = false,
    onPlayerOpened: () -> Unit,
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit
) {
    val audioPermission = rememberPermissionState(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
    )

    val notificationPermission = rememberPermissionState(Manifest.permission.POST_NOTIFICATIONS)
    val notificationsNeedPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        notificationPermission.status !is PermissionStatus.Granted

    when (audioPermission.status) {
        is PermissionStatus.Granted -> MainScaffold(
            repository = repository,
            openPlayerOnLaunch = openPlayerOnLaunch,
            onPlayerOpened = onPlayerOpened,
            darkTheme = darkTheme,
            onThemeChange = onThemeChange,
            notificationsNeedPermission = notificationsNeedPermission,
            onRequestNotificationPermission = { notificationPermission.launchPermissionRequest() }
        )
        is PermissionStatus.Denied -> PermissionRequestScreen(onRequest = { audioPermission.launchPermissionRequest() })
    }
}

@Composable
private fun PermissionRequestScreen(onRequest: () -> Unit) {
    Column(Modifier.padding(24.dp)) {
        Text(
            stringResource(R.string.permission_title),
            style = MaterialTheme.typography.headlineSmall
        )
        Text(
            stringResource(R.string.permission_detail),
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onRequest) {
            Text(stringResource(R.string.permission_continue))
        }
    }
}

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(
    repository: com.ghadirb.aimusic.data.repository.MusicRepository,
    openPlayerOnLaunch: Boolean = false,
    onPlayerOpened: () -> Unit,
    darkTheme: Boolean,
    onThemeChange: (Boolean) -> Unit,
    notificationsNeedPermission: Boolean,
    onRequestNotificationPermission: () -> Unit
) {
    val navController = rememberNavController()
    val playerViewModel = rememberPlayerViewModel(repository)
    val context = LocalContext.current
    val app = context.applicationContext as AiMusicApp
    val premiumViewModel: PremiumViewModel = viewModel(
        factory = viewModelFactory { initializer { PremiumViewModel(app.entitlements, app.purchases) } }
    )
    var upgradeFeature by remember { mutableStateOf<PremiumFeature?>(null) }
    val purchaseState by premiumViewModel.purchaseState.collectAsState()
    val premiumAccess = remember(premiumViewModel) {
        PremiumAccess(premiumViewModel.entitlement, premiumViewModel::isAllowed) { feature -> upgradeFeature = feature }
    }
    // Refresh the plan in the background only if a (cached, offline-verified) premium token is close to expiry.
    LaunchedEffect(Unit) { if (app.entitlements.needsRefresh()) premiumViewModel.refresh() }
    LaunchedEffect(purchaseState) {
        if (purchaseState is PurchaseUiState.Success && upgradeFeature != null) upgradeFeature = null
    }
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val playerUiState by playerViewModel.uiState.collectAsState()

    LaunchedEffect(openPlayerOnLaunch) {
        if (openPlayerOnLaunch) {
            if (currentRoute != ROUTE_PLAYER) {
                navController.navigate(ROUTE_PLAYER) { launchSingleTop = true }
            }
            onPlayerOpened()
        }
    }

    fun openPlayerScreen() {
        if (navController.currentDestination?.route != ROUTE_PLAYER) {
            navController.navigate(ROUTE_PLAYER) { launchSingleTop = true }
        }
    }

    fun openPlayer(track: TrackEntity, queue: List<TrackEntity>) {
        playerViewModel.playQueue(queue, track)
        openPlayerScreen()
    }

    val queueActions = remember(playerViewModel, premiumAccess) {
        QueueActions(
            playNext = { playerViewModel.playNext(it) },
            addToQueue = { playerViewModel.addToQueue(it) },
            // The ONLY place Smart Radio is gated: every entry point (song, artist, album, playlist, favourites, mix) goes through here.
            startRadio = { seeds, label -> premiumAccess.require(PremiumFeature.SMART_RADIO) { playerViewModel.startRadio(seeds, label) } }
        )
    }

    val screenTitle = when {
        currentRoute == null -> stringResource(R.string.app_name)
        currentRoute == ROUTE_PLAYER -> "در حال پخش"
        currentRoute == ROUTE_PREMIUM -> "پرمیوم"
        currentRoute == ROUTE_STATS -> "آمار"
        currentRoute == ROUTE_HISTORY -> "تاریخچهٔ پخش"
        currentRoute == ROUTE_TASTE -> "تکامل سلیقه"
        currentRoute == ROUTE_SMART_PLAYLIST -> "پلی‌لیست هوشمند"
        currentRoute == ROUTE_PLAYLIST_DETAIL ->
            backStackEntry?.arguments?.getString("playlistName")
                ?.let { URLDecoder.decode(it, "UTF-8") } ?: "پلی‌لیست"
        currentRoute == ROUTE_ARTIST_DETAIL ->
            backStackEntry?.arguments?.getString("artistName")
                ?.let { URLDecoder.decode(it, "UTF-8") } ?: "خواننده"
        currentRoute == ROUTE_ALBUM_DETAIL ->
            backStackEntry?.arguments?.getString("albumName")
                ?.let { URLDecoder.decode(it, "UTF-8") } ?: "آلبوم"
        else -> Screen.bottomBarScreens.firstOrNull { it.route == currentRoute }?.let { stringResource(it.labelRes) }
            ?: stringResource(R.string.app_name)
    }
    val showBackButton = currentRoute != null &&
        Screen.bottomBarScreens.none { it.route == currentRoute }

    // Playback problems (e.g. the audio file was deleted/moved) surface as a snackbar on any screen.
    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(playerViewModel) {
        playerViewModel.playbackErrors.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                        }
                    }
                },
                actions = {
                    if (currentRoute != Screen.Favorites.route) {
                        IconButton(onClick = { navController.navigate(Screen.Favorites.route) }) {
                            Icon(Icons.Filled.Favorite, contentDescription = "علاقه‌مندی‌ها")
                        }
                    }
                    if (currentRoute != Screen.Settings.route) {
                        IconButton(onClick = { navController.navigate(Screen.Settings.route) }) {
                            Icon(Icons.Filled.Settings, contentDescription = "تنظیمات")
                        }
                    }
                }
            )
        },
        bottomBar = {
            Column {
                val nowPlaying = playerUiState.currentTrack
                if (nowPlaying != null && currentRoute != ROUTE_PLAYER) {
                    MiniPlayerBar(
                        track = nowPlaying,
                        isPlaying = playerUiState.isPlaying,
                        onOpen = ::openPlayerScreen,
                        onToggle = playerViewModel::togglePlayPause,
                        onNext = playerViewModel::skipNext
                    )
                }
                AppBottomBar(navController)
            }
        }
    ) { padding ->
        CompositionLocalProvider(LocalQueueActions provides queueActions, LocalPremiumAccess provides premiumAccess) {
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(
                    repository = repository,
                    onTrackClick = ::openPlayer,
                    onOpenSmartPlaylist = { navController.navigate(ROUTE_SMART_PLAYLIST) { launchSingleTop = true } },
                    onOpenStats = { navController.navigate(ROUTE_STATS) { launchSingleTop = true } },
                    onOpenHistory = { navController.navigate(ROUTE_HISTORY) { launchSingleTop = true } },
                    onOpenPremium = { navController.navigate(ROUTE_PREMIUM) { launchSingleTop = true } },
                    // Gated centrally through PremiumAccess (no scattered checks in the UI).
                    onOpenTaste = { premiumAccess.require(PremiumFeature.TASTE_EVOLUTION) { navController.navigate(ROUTE_TASTE) { launchSingleTop = true } } }
                )
            }
            composable(Screen.Library.route) {
                LibraryScreen(
                    repository = repository,
                    onTrackClick = ::openPlayer,
                    currentTrackId = playerUiState.currentTrack?.id,
                    onArtistClick = { navController.navigate(artistDetailRoute(it)) },
                    onAlbumClick = { navController.navigate(albumDetailRoute(it)) },
                    onPlaylistClick = { id, name -> navController.navigate(playlistDetailRoute(id, name)) }
                )
            }
            composable(Screen.Artists.route) {
                ArtistsScreen(repository = repository) { artist ->
                    navController.navigate(artistDetailRoute(artist))
                }
            }
            composable(Screen.Albums.route) {
                AlbumsScreen(repository = repository) { album ->
                    navController.navigate(albumDetailRoute(album))
                }
            }
            composable(Screen.Playlists.route) {
                PlaylistsScreen(repository = repository) { id, name ->
                    navController.navigate(playlistDetailRoute(id, name))
                }
            }
            composable(Screen.Favorites.route) {
                FavoritesScreen(repository = repository, onTrackClick = ::openPlayer)
            }
            composable(Screen.Folders.route) {
                FoldersScreen(
                    repository = repository,
                    currentTrackId = playerUiState.currentTrack?.id,
                    onTrackClick = ::openPlayer
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    repository = repository,
                    darkTheme = darkTheme,
                    onThemeChange = onThemeChange,
                    notificationsNeedPermission = notificationsNeedPermission,
                    onRequestNotificationPermission = onRequestNotificationPermission,
                    onOpenPremium = { navController.navigate(ROUTE_PREMIUM) { launchSingleTop = true } },
                    onOpenStats = { navController.navigate(ROUTE_STATS) { launchSingleTop = true } }
                )
            }
            composable(ROUTE_PREMIUM) { PremiumScreen(premiumViewModel) }
            composable(ROUTE_STATS) { StatisticsScreen(repository) }
            composable(ROUTE_HISTORY) {
                HistoryScreen(
                    repository = repository,
                    currentTrackId = playerUiState.currentTrack?.id,
                    onTrackClick = ::openPlayer
                )
            }
            composable(ROUTE_TASTE) { TasteEvolutionScreen(repository) }
            composable(ROUTE_SMART_PLAYLIST) {
                SmartPlaylistScreen(
                    repository = repository,
                    cloudApi = app.cloudApi,
                    cloudConsent = app.cloudConsent,
                    currentTrackId = playerUiState.currentTrack?.id,
                    onTrackClick = ::openPlayer
                )
            }
            composable(ROUTE_PLAYER) {
                PlayerScreen(repository = repository, playerViewModel = playerViewModel)
            }
            composable(
                ROUTE_ARTIST_DETAIL,
                arguments = listOf(navArgument("artistName") { type = NavType.StringType })
            ) { backStackEntry ->
                val encoded = backStackEntry.arguments?.getString("artistName").orEmpty()
                ArtistDetailScreen(
                    artistName = URLDecoder.decode(encoded, "UTF-8"),
                    repository = repository,
                    onTrackClick = ::openPlayer
                )
            }
            composable(
                ROUTE_ALBUM_DETAIL,
                arguments = listOf(navArgument("albumName") { type = NavType.StringType })
            ) { backStackEntry ->
                val encoded = backStackEntry.arguments?.getString("albumName").orEmpty()
                AlbumDetailScreen(
                    albumName = URLDecoder.decode(encoded, "UTF-8"),
                    repository = repository,
                    onTrackClick = ::openPlayer
                )
            }
            composable(
                ROUTE_PLAYLIST_DETAIL,
                arguments = listOf(
                    navArgument("playlistId") { type = NavType.LongType },
                    navArgument("playlistName") { type = NavType.StringType }
                )
            ) { backStackEntry ->
                val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: return@composable
                PlaylistDetailScreen(
                    playlistId = playlistId,
                    repository = repository,
                    onTrackClick = ::openPlayer
                )
            }
        }
        }
    }

    upgradeFeature?.let { feature ->
        UpgradeDialog(
            feature = feature,
            purchaseState = purchaseState,
            skus = premiumViewModel.offeredSkus,
            onBuy = premiumViewModel::buy,
            onRestore = premiumViewModel::restore,
            onDismiss = { upgradeFeature = null; premiumViewModel.resetPurchaseState() }
        )
    }
}

@Composable
private fun AppBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        LazyRow(modifier = Modifier.fillMaxWidth()) {
            items(Screen.bottomBarScreens, key = { it.route }) { screen ->
            val label = stringResource(screen.labelRes)
            NavigationBarItem(
                modifier = Modifier.width(88.dp),
                selected = currentRoute == screen.route,
                onClick = {
                    // Do NOT combine popUpTo{saveState}+restoreState here: the saved state is keyed by the first
                    // popped screen, so a tab could "restore" the player/detail screen that was open above it
                    // instead of showing the tab itself (why tabs, incl. Home, sometimes didn't open directly).
                    if (screen.route == Screen.Home.route) {
                        if (!navController.popBackStack(Screen.Home.route, inclusive = false)) {
                            navController.navigate(Screen.Home.route) {
                                popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                                launchSingleTop = true
                            }
                        }
                    } else if (currentRoute != screen.route) {
                        navController.navigate(screen.route) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                },
                icon = { Icon(screen.icon, contentDescription = label) },
                label = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                alwaysShowLabel = true
            )
            }
        }
    }
}
