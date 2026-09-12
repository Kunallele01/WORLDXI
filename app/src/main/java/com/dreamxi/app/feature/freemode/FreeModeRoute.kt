package com.dreamxi.app.feature.freemode

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** Stateful entry point for Free Mode, mirroring DraftRoute. */
@Composable
fun FreeModeRoute(
    modifier: Modifier = Modifier,
    leagueId: Long? = null,
    leagueName: String? = null,
    formationId: String? = null,
    onQuit: () -> Unit = {},
    onStartSeason: () -> Unit = {},
    viewModel: FreeModeViewModel = hiltViewModel(),
) {
    LaunchedEffect(leagueId) { viewModel.start(leagueId, leagueName, formationId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    FreeModeScreen(
        state = state,
        clubOptions = viewModel::clubOptions,
        seasonOptions = viewModel::seasonOptions,
        onSelectSlot = viewModel::selectSlot,
        onClosePicker = viewModel::closePicker,
        onChooseClub = viewModel::chooseClub,
        onClearClub = viewModel::clearClub,
        onSelectSeason = viewModel::selectSeason,
        onPlace = viewModel::place,
        onClearSlot = viewModel::clearSlot,
        onStartSeason = { if (viewModel.beginSeason()) onStartSeason() },
        onRetry = viewModel::retry,
        onCastMagic = { viewModel.castMagic() },
        onCycleSlot = { viewModel.cycleSlot(it) },
        onQuit = onQuit,
        modifier = modifier,
    )
}
