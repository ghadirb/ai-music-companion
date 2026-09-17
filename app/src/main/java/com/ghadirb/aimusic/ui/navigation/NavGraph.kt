package com.ghadirb.aimusic.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Route names + bottom-nav destinations. Kept as a flat sealed structure
 * (no nested graphs) since the MVP has 7 flat top-level screens, matching
 * the doc's page list: Home / Library / Artists / Albums / Playlists /
 * Favorites / Settings, plus a Player screen reached from any of them.
 */
sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home : Screen("home", "Home", Icons.Filled.Home)
    data object Library : Screen("library", "Library", Icons.Filled.LibraryMusic)
    data object Artists : Screen("artists", "Artists", Icons.Filled.Person)
    data object Albums : Screen("albums", "Albums", Icons.Filled.Album)
    data object Playlists : Screen("playlists", "Playlists", Icons.Filled.QueueMusic)
    data object Favorites : Screen("favorites", "Favorites", Icons.Filled.Favorite)
    data object Settings : Screen("settings", "Settings", Icons.Filled.Settings)

    companion object {
        val bottomBarScreens = listOf(Home, Library, Playlists, Favorites, Settings)
    }
}

const val ROUTE_PLAYER = "player"
const val ROUTE_ARTIST_DETAIL = "artist/{artistName}"
const val ROUTE_ALBUM_DETAIL = "album/{albumName}"
const val ROUTE_PLAYLIST_DETAIL = "playlist/{playlistId}/{playlistName}"

fun artistDetailRoute(artistName: String) = "artist/${java.net.URLEncoder.encode(artistName, "UTF-8")}"
fun albumDetailRoute(albumName: String) = "album/${java.net.URLEncoder.encode(albumName, "UTF-8")}"
fun playlistDetailRoute(playlistId: Long, playlistName: String) =
    "playlist/$playlistId/${java.net.URLEncoder.encode(playlistName, "UTF-8")}"
