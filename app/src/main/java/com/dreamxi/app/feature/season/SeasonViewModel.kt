package com.dreamxi.app.feature.season

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.data.run.ActiveRun
import com.dreamxi.app.data.season.LoadedSeason
import com.dreamxi.app.data.season.SeasonRepository
import com.dreamxi.app.data.toUserFacingError
import com.dreamxi.app.sim.FixtureOutlooks
import com.dreamxi.app.sim.SeasonAnalyst
import com.dreamxi.app.sim.SeasonProjector
import com.dreamxi.app.sim.SeasonSimulator
import com.dreamxi.app.sim.SeasonStatsBuilder
import com.dreamxi.app.sim.SimTeam
import com.dreamxi.app.sim.TakeoverDraw
import com.dreamxi.app.sim.mixSeed
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/**
 * A season a club can be drawn from must be a season that actually loaded. A
 * league with six clubs in it is not a league, and dropping the user into one
 * would produce a 10-game season and a meaningless table.
 */
private const val MIN_CLUBS_FOR_A_SEASON = 18

/**
 * How long each matchday stays on screen while the season plays itself.
 *
 * Long enough to read a scoreline, the scorers under it and where the table
 * moved. A full season takes about a minute and a half at this pace, which is
 * why Stop and Skip both exist next to it.
 */
private const val AUTO_MATCHDAY_MS = 2200L

/** Drives the run from a finished XI to a finished season. */
@HiltViewModel
class SeasonViewModel @Inject constructor(
    private val repository: SeasonRepository,
    private val activeRun: ActiveRun,
) : ViewModel() {

    private val _state = MutableStateFlow(SeasonUiState())
    val state: StateFlow<SeasonUiState> = _state.asStateFlow()

    private var started = false
    private var loaded: LoadedSeason? = null

    /**
     * The auto-play loop, held so it can be stopped.
     *
     * A season that plays itself must be interruptible: the user asked for
     * results he can actually read, and anything he can read for two seconds a
     * week is something he will want to cut short at some point.
     */
    private var autoPlay: Job? = null

    fun start() {
        if (started) return
        started = true
        load()
    }

    fun retry() {
        _state.update { it.copy(error = null, phase = SeasonPhase.Loading) }
        load()
    }

    private fun load() = viewModelScope.launch {
        val draft = activeRun.draft
        if (draft == null) {
            // Only reachable if the process was killed mid-run, since the run
            // lives in memory. Say so plainly rather than showing an empty league.
            _state.update {
                it.copy(error = DreamXiError.Unexpected("This run was lost. Start a new draft."))
            }
            return@launch
        }

        runCatching {
            // The season is chosen at simulate time and is independent of the
            // seasons the players were drafted from — that mixing is the game.
            val options = repository.seasonOptions(draft.leagueId)
                .filter { it.clubCount >= MIN_CLUBS_FOR_A_SEASON }
            check(options.isNotEmpty()) { "No fully loaded season to play in ${draft.leagueName}." }

            val chosen = options[Random(mixSeed(draft.seed)).nextInt(options.size)]
            repository.loadSeason(chosen.seasonId)
        }.onSuccess { season ->
            if (season.teams.size < MIN_CLUBS_FOR_A_SEASON) {
                _state.update {
                    it.copy(error = DreamXiError.Unexpected("That season did not load enough clubs to play."))
                }
                return@launch
            }
            loaded = season
            beginDraw(draft.seed, season)
        }.onFailure { t ->
            _state.update { it.copy(error = t.toUserFacingError("loading the season")) }
        }
    }

    private fun beginDraw(seed: Long, season: LoadedSeason) {
        val draft = activeRun.draft ?: return
        val candidates = season.relegatedTeamIds.mapNotNull { id -> season.teams.find { it.id == id } }
        val takeover = TakeoverDraw.draw(candidates.map { it.id }, seed)

        _state.update {
            it.copy(
                phase = SeasonPhase.Drawing,
                leagueName = draft.leagueName,
                isFreeMode = draft.isFreeMode,
                seasonLabel = season.label,
                drawCandidates = candidates,
                takeover = takeover,
                drawTick = 0,
                error = null,
            )
        }
        runDrawAnimation()
    }

    /**
     * Plays the draw out on screen.
     *
     * Fast at first and slowing towards the end, because a constant rate would
     * either take too long or be over before it registered. The result is
     * already decided — this is pacing, not chance — but the pacing is the
     * whole reason the draw is hundreds of draws instead of one.
     */
    private fun runDrawAnimation() = viewModelScope.launch {
        val total = _state.value.takeover?.ticks?.size ?: return@launch
        var tick = 0
        while (tick < total) {
            // Advance several draws per frame early on, one at a time at the
            // end, so the final few land individually and can be read.
            val remaining = total - tick
            val step = when {
                remaining > 120 -> 6
                remaining > 40 -> 3
                remaining > 12 -> 2
                else -> 1
            }
            val pause = when {
                remaining > 120 -> 16L
                remaining > 40 -> 28L
                remaining > 12 -> 55L
                else -> 130L
            }
            tick = (tick + step).coerceAtMost(total)
            _state.update { it.copy(drawTick = tick) }
            delay(pause)
        }
        delay(500)
        settleDraw()
    }

    /** Builds the league with the user in it, and plays the whole season. */
    private fun settleDraw() {
        val draft = activeRun.draft ?: return
        val season = loaded ?: return
        val takeover = _state.value.takeover ?: return

        val user = draftedTeam(
            picks = draft.formation.slots.mapNotNull { slot -> draft.picks.find { it.slot.id == slot.id } },
            name = "Dream XI",
            season = season.label,
            bench = draft.bench,
        )
        // The counterfactual: the user takes the replaced club's fixtures and
        // every other result in the season stays exactly as it happened.
        val result = SeasonSimulator.playCounterfactual(
            real = season.fixtures,
            teams = season.teams,
            user = user,
            replacedTeamId = takeover.replacedTeamId,
            seed = draft.seed,
        )
        val league = leagueWithUser(season.teams, user, takeover.replacedTeamId)
        val replaced = season.teams.find { it.id == takeover.replacedTeamId }

        _state.update {
            it.copy(
                phase = SeasonPhase.PlaceWon,
                userTeam = user,
                replacedClub = replaced,
                replacedClubRealPosition = season.finalPositions[takeover.replacedTeamId],
                fixtures = result.fixtures,
                totalMatchdays = result.fixtures.maxOfOrNull { f -> f.matchday } ?: 0,
                visibleMatchday = 0,
                difficultyThresholds = FixtureOutlooks.difficultyThresholds(result.fixtures, user),
                projection = null,
                table = SeasonSimulator.buildTable(league, emptyList()),
            )
        }

        // Started here rather than on demand: the draw is still animating for
        // several seconds, which is free cover for the work.
        projectSeason(user, takeover.replacedTeamId, draft.seed)
    }

    /**
     * Works out what the XI is worth over many seasons, off the main thread.
     *
     * Started as soon as the season exists, which is while the takeover draw is
     * still animating — about four seconds of cover, far more than it needs.
     * The screen simply shows nothing until it lands, so a slow device delays
     * a panel rather than blocking the run.
     */
    private fun projectSeason(user: SimTeam, replacedTeamId: String, seed: Long) =
        viewModelScope.launch(Dispatchers.Default) {
            val season = loaded ?: return@launch
            val projection = SeasonProjector.project(
                real = season.fixtures,
                teams = season.teams,
                user = user,
                replacedTeamId = replacedTeamId,
                seed = seed,
            )
            _state.update { it.copy(projection = projection) }
        }

    /** Leaves the reveal and starts the season proper. */
    fun kickOff() {
        _state.update { it.copy(phase = SeasonPhase.Playing) }
    }

    /** Plays the next week. */
    fun playNextMatchday() {
        val s = _state.value
        if (s.isAdvancing || s.isSeasonOver) return
        advanceTo(s.visibleMatchday + 1)
    }

    /**
     * Plays out the rest of the season at reading pace.
     *
     * PACE IS THE POINT. This used to sprint through all 38 matchdays in a
     * second or so, which showed the user nothing: the results and the table
     * blurred past and only the final standings were legible. It now advances
     * one week at a time with a pause long enough to actually read the
     * scoresheet, and the screen scrolls back to the match card on every step.
     *
     * It resumes from wherever the user is, so it behaves the same whether it
     * is pressed before a ball is kicked or with a dozen matchdays already
     * played, and it can be stopped at any point.
     */
    fun playToEnd() {
        val s = _state.value
        if (s.isAdvancing || s.isSeasonOver) return
        autoPlay = viewModelScope.launch {
            _state.update { it.copy(isAdvancing = true) }
            while (_state.value.visibleMatchday < _state.value.totalMatchdays) {
                applyMatchday(_state.value.visibleMatchday + 1)
                if (_state.value.isSeasonOver) break
                delay(AUTO_MATCHDAY_MS)
            }
            finishIfOver()
            _state.update { it.copy(isAdvancing = false) }
        }
    }

    /** Stops the auto-play, leaving the season exactly where it paused. */
    fun stopAutoPlay() {
        autoPlay?.cancel()
        autoPlay = null
        _state.update { it.copy(isAdvancing = false) }
    }

    /** Jumps straight to the final table, for anyone who has seen enough. */
    fun skipToEnd() {
        autoPlay?.cancel()
        autoPlay = null
        val total = _state.value.totalMatchdays
        if (total > 0) applyMatchday(total)
        finishIfOver()
        _state.update { it.copy(isAdvancing = false) }
    }

    private fun finishIfOver() {
        if (_state.value.isSeasonOver) {
            _state.update { it.copy(phase = SeasonPhase.Finished) }
            buildStats()
        }
    }

    fun openStats() = _state.update { it.copy(isViewingStats = true, hasSeenStats = true) }

    /**
     * True when leaving needs confirming — i.e. the statistics are still
     * unseen. The caller finishes directly when this is false, so a user who
     * has already read them is not asked about them again.
     */
    fun needsExitConfirmation(): Boolean = !_state.value.hasSeenStats
    fun closeStats() = _state.update { it.copy(isViewingStats = false) }
    fun askToExit() = _state.update { it.copy(isConfirmingExit = true) }
    fun dismissExit() = _state.update { it.copy(isConfirmingExit = false) }

    private fun advanceTo(matchday: Int) {
        applyMatchday(matchday)
        finishIfOver()
    }

    private fun buildStats() {
        val s = _state.value
        if (s.fixtures.isEmpty()) return
        val user = s.userTeam
        _state.update {
            it.copy(
                stats = SeasonStatsBuilder.build(s.fixtures),
                analysis = user?.let { team -> SeasonAnalyst.analyse(s.fixtures, team) },
            )
        }
    }

    private fun applyMatchday(matchday: Int) {
        val s = _state.value
        val teams = buildList {
            s.userTeam?.let { add(it) }
            addAll(s.fixtures.flatMap { listOf(it.home, it.away) })
        }.distinctBy { it.id }
        val played = s.fixtures.filter { it.matchday <= matchday }
        _state.update {
            it.copy(
                visibleMatchday = matchday,
                table = SeasonSimulator.buildTable(teams, played),
            )
        }
    }
}
