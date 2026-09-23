package com.dreamxi.app.feature.worldcup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamxi.app.feature.freemode.FreeModeScreen

/** World Cup Free Mode: the club Free Mode screen, fed by every nation at every World Cup. */
@Composable
fun WcFreeModeRoute(
    formationId: String?,
    onQuit: () -> Unit,
    onStartTournament: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WcFreeModeViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start(formationId) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    FreeModeScreen(
        state = state,
        clubOptions = viewModel::clubOptions,
        seasonOptions = viewModel::seasonOptions,
        onSelectSlot = viewModel::selectSlot,
        onClosePicker = { viewModel.closePicker() },
        onChooseClub = { viewModel.chooseClub(it) },
        onClearClub = { viewModel.clearClub() },
        onSelectSeason = viewModel::selectSeason,
        onPlace = viewModel::place,
        onClearSlot = viewModel::clearSlot,
        onStartSeason = { if (viewModel.beginTournament()) onStartTournament() },
        onRetry = viewModel::retry,
        onCastMagic = { viewModel.castMagic() },
        onCycleSlot = viewModel::cycleSlot,
        onQuit = onQuit,
        modifier = modifier,
    )
}
