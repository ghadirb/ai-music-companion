package com.ghadirb.aimusic.ui.screens.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.ghadirb.aimusic.R
import com.ghadirb.aimusic.data.local.entity.TrackEntity
import com.ghadirb.aimusic.data.repository.MusicRepository

/**
 * The doc's mock-up: greeting + mood cards. All four cards are backed by real
 * data: "today's pick" (RecommendationEngine), "rediscover" (MusicRepository.
 * rediscoverTracks), and "night"/"driving" from AudioAnalyzer's on-device
 * energy/mood scoring (MusicRepository.nightSuitableTracks/drivingSuitableTracks).
 * The night/driving rails stay empty with a small note until AudioAnalysisWorker
 * has processed enough of the library in the background — see README "AI Roadmap".
 */
@Composable
fun HomeScreen(
    repository: MusicRepository,
    onTrackClick: (TrackEntity, List<TrackEntity>) -> Unit
) {
    val viewModel: HomeViewModel = viewModel(
        factory = viewModelFactory { initializer { HomeViewModel(repository) } }
    )
    val picks by viewModel.todaysPicks.collectAsState()
    val rediscover by viewModel.rediscoverPicks.collectAsState()
    val night by viewModel.nightPicks.collectAsState()
    val driving by viewModel.drivingPicks.collectAsState()
    val hasLibrary by viewModel.hasLibrary.collectAsState()

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)) {
        Text(stringResource(R.string.home_greeting), style = MaterialTheme.typography.headlineMedium)
        Text(
            stringResource(R.string.home_question),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(top = 4.dp, bottom = 20.dp)
        )

        // hasLibrary is null for one instant while the first trackCount() check is
        // still running — render nothing rather than guessing, so the screen doesn't
        // flash the full card layout and then immediately collapse to the empty state.
        when (hasLibrary) {
            null -> return@Column
            false -> {
                Text(stringResource(R.string.empty_library), style = MaterialTheme.typography.bodyMedium)
                return@Column
            }
            true -> Unit
        }

        MoodCard(emoji = "🎧", title = stringResource(R.string.card_today_pick))
        Spacer(Modifier.height(12.dp))
        TrackRail(tracks = picks, onTrackClick = { track -> onTrackClick(track, picks) })

        Spacer(Modifier.height(20.dp))
        MoodCard(emoji = "🌙", title = stringResource(R.string.card_night))
        Spacer(Modifier.height(12.dp))
        if (night.isNotEmpty()) {
            TrackRail(tracks = night, onTrackClick = { track -> onTrackClick(track, night) })
        } else {
            Text(
                stringResource(R.string.mood_cards_analyzing),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(20.dp))
        MoodCard(emoji = "🚗", title = stringResource(R.string.card_driving))
        Spacer(Modifier.height(12.dp))
        if (driving.isNotEmpty()) {
            TrackRail(tracks = driving, onTrackClick = { track -> onTrackClick(track, driving) })
        } else {
            Text(
                stringResource(R.string.mood_cards_analyzing),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Spacer(Modifier.height(20.dp))
        MoodCard(emoji = "🔄", title = stringResource(R.string.card_rediscover))
        Spacer(Modifier.height(12.dp))
        TrackRail(tracks = rediscover, onTrackClick = { track -> onTrackClick(track, rediscover) })
    }
}

@Composable
private fun TrackRail(tracks: List<TrackEntity>, onTrackClick: (TrackEntity) -> Unit) {
    if (tracks.isEmpty()) return
    LazyRow {
        items(tracks, key = { it.id }) { track ->
            Card(
                modifier = Modifier
                    .padding(end = 12.dp)
                    .width(140.dp)
                    .height(90.dp),
                shape = RoundedCornerShape(16.dp),
                onClick = { onTrackClick(track) }
            ) {
                Column(Modifier.fillMaxSize().padding(12.dp)) {
                    Text(track.title, maxLines = 1, fontWeight = FontWeight.SemiBold)
                    Text(track.artist, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun MoodCard(emoji: String, title: String) {
    Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.titleMedium)
        }
    }
}
