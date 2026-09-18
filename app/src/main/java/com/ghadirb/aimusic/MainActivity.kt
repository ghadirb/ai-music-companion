package com.ghadirb.aimusic

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.ui.navigation.ROUTE_ALBUM_DETAIL
import com.ghadirb.aimusic.ui.navigation.ROUTE_ARTIST_DETAIL
import com.ghadirb.aimusic.ui.navigation.ROUTE_PLAYER
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

    @OptIn(UnstableApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as AiMusicApp

        setContent {
            AiMusicCompanionTheme {
                Surface {
                    AppRoot(repository = app.repository)
                }
            }
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class, UnstableApi::class)
@Composable
private fun AppRoot(repository: com.ghadirb.aimusic.data.repository.MusicRepository) {
    val audioPermission = rememberPermissionState(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            Manifest.permission.READ_MEDIA_AUDIO
        else
            Manifest.permission.READ_EXTERNAL_STORAGE
    )

    when (audioPermission.status) {
        is PermissionStatus.Granted -> MainScaffold(repository)
        is PermissionStatus.Denied -> PermissionRequestScreen(onRequest = { audioPermission.launchPermissionRequest() })
    }
}

@Composable
private fun PermissionRequestScreen(onRequest: () -> Unit) {
    Column {
        Text(
            stringResource(R.string.permission_rationale),
            modifier = Modifier.padding(24.dp)
        )
        Button(onClick = onRequest, modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(stringResource(R.string.grant_permission))
        }
    }
}

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold(repository: com.ghadirb.aimusic.data.repository.MusicRepository) {
    val navController = rememberNavController()
    val playerViewModel = rememberPlayerViewModel(repository)
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    fun openPlayer(track: TrackEntity, queue: List<TrackEntity>) {
        playerViewModel.playQueue(queue, track)
        navController.navigate(ROUTE_PLAYER)
    }

    val screenTitle = when {
        currentRoute == null -> stringResource(R.string.app_name)
        currentRoute == ROUTE_PLAYER -> "در حال پخش"
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(screenTitle) },
                navigationIcon = {
                    if (showBackButton) {
                        IconButton(onClick = { navController.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                        }
                    }
                }
            )
        },
        bottomBar = { AppBottomBar(navController) }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(padding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen(repository = repository, onTrackClick = ::openPlayer)
            }
            composable(Screen.Library.route) {
                LibraryScreen(repository = repository, onTrackClick = ::openPlayer)
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
            composable(Screen.Settings.route) { SettingsScreen() }
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

@Composable
private fun AppBottomBar(navController: NavHostController) {
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    NavigationBar {
        Screen.bottomBarScreens.forEach { screen ->
            val label = stringResource(screen.labelRes)
            NavigationBarItem(
                selected = currentRoute == screen.route,
                onClick = {
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(screen.icon, contentDescription = label) },
                label = { Text(label) }
            )
        }
    }
}
