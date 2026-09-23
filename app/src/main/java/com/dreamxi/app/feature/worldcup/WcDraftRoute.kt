package com.dreamxi.app.feature.worldcup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamxi.app.feature.draft.DraftScreen

/** The World Cup draft: the club draft's screen, fed by a national pool. */
@Composable
fun WcDraftRoute(
    formationId: String?,
    rerollsAllowed: Int,
    onQuitRun: () -> Unit,
    onStartTournament: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WcDraftViewModel = hiltViewModel(),
) {
    LaunchedEffect(Unit) { viewModel.start(formationId, rerollsAllowed) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    DraftScreen(
        state = state,
        onSpin = viewModel::spin,
        onChangeSpin = viewModel::changeSpin,
        onSelectPlayer = viewModel::selectPlayer,
        onConfirmPick = viewModel::confirmPick,
        onRetry = viewModel::retry,
        onCancelPlacement = viewModel::cancelPlacement,
        onViewTeam = viewModel::viewTeam,
        onCloseTeam = viewModel::closeTeam,
        onHoldPlaced = viewModel::holdPlaced,
        onMoveHeldTo = viewModel::moveHeldTo,
        onQuitRun = onQuitRun,
        onStartSeason = { if (viewModel.beginTournament()) onStartTournament() },
        modifier = modifier,
    )
}
