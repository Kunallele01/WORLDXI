package com.dreamxi.app.feature.setup

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamxi.app.data.draft.League
import com.dreamxi.app.feature.draft.Formation

@Composable
fun SetupRoute(
    onStartRun: (League, Formation, Int) -> Unit,
    onStartFreeMode: (League, Formation) -> Unit = { _, _ -> },
    onStartWorldCup: (Formation, Int) -> Unit = { _, _ -> },
    onStartWorldCupFreeMode: (Formation) -> Unit = {},
    modifier: Modifier = Modifier,
    viewModel: SetupViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    SetupScreen(
        state = state,
        onSelectLeague = viewModel::selectLeague,
        onSetRerolls = viewModel::setRerolls,
        onSelectFormation = viewModel::selectFormation,
        onStart = { league -> onStartRun(league, state.formation, state.rerollsAllowed) },
        onStartFreeMode = { league -> onStartFreeMode(league, state.formation) },
        onSelectMode = viewModel::selectMode,
        onStartWorldCup = { onStartWorldCup(state.formation, state.rerollsAllowed) },
        onStartWorldCupFreeMode = { onStartWorldCupFreeMode(state.formation) },
        onRetry = viewModel::load,
        modifier = modifier,
    )
}
