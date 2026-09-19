package com.ghadirb.aimusic.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector
import com.ghadirb.aimusic.R

/**
 * Route names + bottom-nav destinations. Kept as a flat sealed structure
 * (no nested graphs) since the MVP has 7 flat top-level screens, matching
 * the doc's page list: Home / Library / Artists / Albums / Playlists /
 * Favorites / Settings, plus a Player screen reached from any of them.
 */
sealed class Screen(val route: String, val labelRes: Int, val icon: ImageVector) {
    data object Home : Screen("home", R.string.nav_home, Icons.Filled.Home)
    data object Library : Screen("library", R.string.nav_library, Icons.Filled.LibraryMusic)
    data object Artists : Screen("artists", R.string.nav_artists, Icons.Filled.Person)
    data object Albums : Screen("albums", R.string.nav_albums, Icons.Filled.Album)
    data object Playlists : Screen("playlists", R.string.nav_playlists, Icons.Filled.QueueMusic)
    data object Favorites : Screen("favorites", R.string.nav_favorites, Icons.Filled.Favorite)
    data object Folders : Screen("folders", R.string.nav_folders, Icons.Filled.Folder)
    data object Settings : Screen("settings", R.string.nav_settings, Icons.Filled.Settings)

    companion object {
        val bottomBarScreens = listOf(Home, Library, Playlists, Favorites, Folders, Settings)
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
