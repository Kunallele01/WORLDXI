package com.dreamxi.app.feature.draft

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Stateful entry point: owns the ViewModel, hands [DraftScreen] a snapshot and
 * callbacks. Keeping the screen itself stateless is what lets every phase of a
 * round be exercised from a @Preview without a backend.
 */
@Composable
fun DraftRoute(
    modifier: Modifier = Modifier,
    leagueId: Long? = null,
    leagueName: String? = null,
    formationId: String? = null,
    rerollsAllowed: Int = 2,
    onQuitRun: () -> Unit = {},
    onStartSeason: () -> Unit = {},
    viewModel: DraftViewModel = hiltViewModel(),
) {
    LaunchedEffect(leagueId) {
        viewModel.start(
            leagueId = leagueId,
            leagueName = leagueName,
            formationId = formationId,
            rerollsAllowed = rerollsAllowed,
        )
    }
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
        onStartSeason = { if (viewModel.beginSeason()) onStartSeason() },
        modifier = modifier,
    )
}
