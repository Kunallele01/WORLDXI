package com.dreamxi.app.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.data.draft.DraftRepository
import com.dreamxi.app.data.draft.League
import com.dreamxi.app.data.toUserFacingError
import com.dreamxi.app.feature.draft.DefaultFormation
import com.dreamxi.app.feature.draft.Formation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Setup: choose the league a run is played in, and the difficulty.
 *
 * League is the ONLY thing fixed here. Season is not chosen at setup — every
 * draft round spins its own random (season, club) pair from this league's
 * whole loaded history, and the season that actually gets simulated is picked
 * later, after the XI is complete. Adding a season picker here would quietly
 * break the core mechanic.
 */
data class SetupUiState(
    val leagues: List<League> = emptyList(),
    val selectedLeagueId: Long? = null,
    val formation: Formation = DefaultFormation,
    val rerollsAllowed: Int = 2,
    val isLoading: Boolean = true,
    val error: DreamXiError? = null,
) {
    val selectedLeague: League? get() = leagues.firstOrNull { it.id == selectedLeagueId }
    val canStart: Boolean get() = selectedLeague?.let { it.seasonCount > 0 } == true
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val repository: DraftRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching { repository.leagues() }
                .onSuccess { leagues ->
                    _state.update {
                        it.copy(
                            leagues = leagues,
                            isLoading = false,
                            // Preselect only when there is no real choice to
                            // make; with two leagues loaded, picking one for
                            // the user is the decision they came here to make.
                            selectedLeagueId = it.selectedLeagueId
                                ?: leagues.singleOrNull()?.id,
                            error = if (leagues.isEmpty()) DreamXiError.NoData else null,
                        )
                    }
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(isLoading = false, error = e.toUserFacingError("load leagues"))
                    }
                }
        }
    }

    fun selectLeague(id: Long) = _state.update { it.copy(selectedLeagueId = id) }

    /** Difficulty dial: 1-3 change spins, matching the DB check constraint. */
    fun setRerolls(count: Int) = _state.update { it.copy(rerollsAllowed = count.coerceIn(1, 3)) }

    fun selectFormation(formation: Formation) = _state.update { it.copy(formation = formation) }
}
