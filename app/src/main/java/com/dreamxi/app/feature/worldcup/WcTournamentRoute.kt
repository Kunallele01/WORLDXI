package com.dreamxi.app.feature.worldcup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Stateful entry point for the tournament, mirroring SeasonRoute. */
@Composable
fun WcTournamentRoute(
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WcTournamentViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsStateWithLifecycle()
    WcTournamentScreen(
        state = state,
        onKickOff = viewModel::kickOff,
        onPlayStage = viewModel::playNextStage,
        onPlayToEnd = viewModel::playToEnd,
        onStopAutoPlay = viewModel::stopAutoPlay,
        onSkipToEnd = viewModel::skipToEnd,
        onRetry = viewModel::retry,
        onDone = onDone,
        modifier = modifier,
    )
}
