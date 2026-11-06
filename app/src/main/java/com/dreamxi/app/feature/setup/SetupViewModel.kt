package com.dreamxi.app.feature.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.data.draft.DraftRepository
import com.dreamxi.app.data.draft.League
import com.dreamxi.app.data.run.ActiveRun
import com.dreamxi.app.data.run.ResumeOffer
import com.dreamxi.app.data.run.RunSnapshotStore
import com.dreamxi.app.data.toUserFacingError
import com.dreamxi.app.feature.draft.DefaultFormation
import com.dreamxi.app.feature.draft.Formation
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
/** The two games a run can be: a league season, or a World Cup. */
enum class RunMode { League, WorldCup }

data class SetupUiState(
    val mode: RunMode = RunMode.League,
    val leagues: List<League> = emptyList(),
    val selectedLeagueId: Long? = null,
    val formation: Formation = DefaultFormation,
    val rerollsAllowed: Int = 2,
    val isLoading: Boolean = true,
    val error: DreamXiError? = null,
    /** A run left unfinished, if there is one, offered as the way back in. */
    val resume: ResumeOffer? = null,
) {
    val selectedLeague: League? get() = leagues.firstOrNull { it.id == selectedLeagueId }
    val canStart: Boolean get() = selectedLeague?.let { it.seasonCount > 0 } == true
}

/** Where "Continue" leads. */
sealed interface ResumeTarget {
    /** A season in progress: [com.dreamxi.app.data.run.ActiveRun] has been restored. */
    data object Season : ResumeTarget

    /** A draft in progress: the draft screen restores it itself, given these. */
    data class Draft(
        val leagueId: Long,
        val leagueName: String,
        val formationId: String,
        val rerollsAllowed: Int,
    ) : ResumeTarget
}

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val repository: DraftRepository,
    private val store: RunSnapshotStore,
    private val activeRun: ActiveRun,
) : ViewModel() {

    private val _state = MutableStateFlow(SetupUiState())
    val state: StateFlow<SetupUiState> = _state.asStateFlow()

    init {
        load()
        refreshResume()
    }

    /**
     * Looks again for a run to continue. Called whenever this screen comes back
     * into view, because finishing or quitting a run changes the answer while
     * this view model is still alive underneath it.
     */
    fun refreshResume() {
        viewModelScope.launch {
            val offer = withContext(Dispatchers.IO) { store.offer() }
            _state.update { it.copy(resume = offer) }
        }
    }

    /**
     * Gets the saved run ready and says where to go, or null if it has gone
     * (unreadable, or finished elsewhere) — in which case the offer is dropped.
     */
    fun prepareContinue(): ResumeTarget? {
        val offer = _state.value.resume ?: return null
        val target: ResumeTarget? = when (offer.kind) {
            ResumeOffer.Kind.SEASON -> ResumeTarget.Season.takeIf { activeRun.restore() }
            ResumeOffer.Kind.DRAFT -> store.loadDraft()?.let {
                ResumeTarget.Draft(it.leagueId, it.leagueName, it.formation.id, it.rerollsAllowed)
            }
        }
        if (target == null) refreshResume()
        return target
    }

    /** Throws the unfinished run away. */
    fun discardResume() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                store.clearSeason()
                store.clearDraft()
            }
            _state.update { it.copy(resume = null) }
        }
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

    /** Formation and change spins carry across, so switching modes loses nothing. */
    fun selectMode(mode: RunMode) = _state.update { it.copy(mode = mode) }

    /** Difficulty dial: 1-3 change spins, matching the DB check constraint. */
    fun setRerolls(count: Int) = _state.update { it.copy(rerollsAllowed = count.coerceIn(1, 3)) }

    fun selectFormation(formation: Formation) = _state.update { it.copy(formation = formation) }
}
