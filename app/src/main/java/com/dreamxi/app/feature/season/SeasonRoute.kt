package com.dreamxi.app.feature.season

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Stateful entry point for the season, mirroring DraftRoute. */
@Composable
fun SeasonRoute(
    modifier: Modifier = Modifier,
    onFinish: () -> Unit = {},
    viewModel: SeasonViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start() }
    val state by viewModel.state.collectAsStateWithLifecycle()

    SeasonScreen(
        state = state,
        onKickOff = viewModel::kickOff,
        onPlayWeek = viewModel::playNextMatchday,
        onPlayToEnd = viewModel::playToEnd,
        onStopAutoPlay = viewModel::stopAutoPlay,
        onSkipToEnd = viewModel::skipToEnd,
        onOpenStats = viewModel::openStats,
        onCloseStats = viewModel::closeStats,
        // Already seen them? Then there is nothing to warn about and Done
        // just means done.
        onAskExit = { if (viewModel.needsExitConfirmation()) viewModel.askToExit() else onFinish() },
        onDismissExit = viewModel::dismissExit,
        onRetry = viewModel::retry,
        onFinish = onFinish,
        modifier = modifier,
    )
}
