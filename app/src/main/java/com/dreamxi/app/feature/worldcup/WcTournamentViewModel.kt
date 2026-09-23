package com.dreamxi.app.feature.worldcup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.data.toUserFacingError
import com.dreamxi.app.data.worldcup.LoadedWorldCup
import com.dreamxi.app.data.worldcup.WorldCupRepository
import com.dreamxi.app.data.worldcup.WorldCupRun
import com.dreamxi.app.sim.TakeoverDraw
import com.dreamxi.app.sim.mixSeed
import com.dreamxi.app.sim.worldcup.GROUP_ROUND
import com.dreamxi.app.sim.worldcup.GroupResult
import com.dreamxi.app.sim.worldcup.NationalSide
import com.dreamxi.app.sim.worldcup.WcGroupTable
import com.dreamxi.app.sim.worldcup.WcTournamentSimulator
import com.dreamxi.app.sim.worldcup.WcUserTeam
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.random.Random

/** How long each stage stays up while the tournament plays itself; the season screen's pace. */
private const val AUTO_STAGE_MS = 2200L

/** Draws per nation in the takeover draw, and the floor for a small field. */
private const val DRAWS_PER_NATION = 50
private const val MIN_TAKEOVER_DRAWS = 500

/** The draw's pacing: a fixed frame budget for the bulk, then a slow tail. */
private const val BULK_FRAMES = 48
private const val BULK_FRAME_MS = 30L
private const val TAIL_DRAWS = 12

/** Drives a run from a finished World Cup XI to a finished tournament. */
@HiltViewModel
class WcTournamentViewModel @Inject constructor(
    private val repository: WorldCupRepository,
    private val run: WorldCupRun,
) : ViewModel() {

    private val _state = MutableStateFlow(WcTournamentUiState())
    val state: StateFlow<WcTournamentUiState> = _state.asStateFlow()

    private var started = false
    private var loaded: LoadedWorldCup? = null
    private var autoPlay: Job? = null

    fun start() {
        if (started) return
        started = true
        load()
    }

    fun retry() {
        _state.update { it.copy(error = null, phase = WcPhase.Loading) }
        load()
    }

    private fun load() = viewModelScope.launch {
        val draft = run.draft
        if (draft == null) {
            _state.update { it.copy(error = DreamXiError.Unexpected("This run was lost. Start a new draft.")) }
            return@launch
        }
        runCatching {
            // Which World Cup is decided now, after the draft and independent of
            // the editions the players came from — the club mode's rule for its
            // season, and the user's choice for this mode.
            val years = repository.years()
            check(years.isNotEmpty()) { "No World Cups are loaded." }
            repository.tournament(years[Random(mixSeed(draft.seed)).nextInt(years.size)])
        }.onSuccess { cup ->
            loaded = cup
            val candidates = cup.data.entries.filter { it.finishedBottom }.sortedBy { it.group }
            val takeover = TakeoverDraw.draw(candidates.map { it.id }, draft.seed, drawsFor(candidates.size))
            _state.update {
                it.copy(
                    phase = WcPhase.Drawing,
                    isFreeMode = draft.isFreeMode,
                    year = cup.data.year,
                    hosts = cup.data.entries.filter { e -> e.isHost }.map { e -> e.name },
                    names = cup.names + (WC_USER_ID to "Your XI"),
                    drawCandidates = candidates.map { e -> e.id },
                    takeover = takeover,
                    drawTick = 0,
                    error = null,
                )
            }
            runDrawAnimation()
        }.onFailure { t ->
            _state.update { it.copy(error = t.toUserFacingError("loading the World Cup")) }
        }
    }

    /**
     * How many draws a World Cup takeover runs: fifty a nation, never fewer than
     * [MIN_TAKEOVER_DRAWS].
     *
     * The club draw's 300 was sized for THREE relegated clubs — a hundred each.
     * A World Cup field is eight nations, or twelve in 2026, so 300 left them on
     * about 25-37 apiece and the lead was regularly shared: the odds were still
     * exactly one in eight (nothing about the procedure distinguishes the
     * nations, see TakeoverDraw), but the tally on screen looked like a
     * near-tie. Fifty each gives 500 for eight and 600 for twelve, and a leader
     * that has actually pulled clear.
     */
    private fun drawsFor(candidates: Int): Int =
        (candidates * DRAWS_PER_NATION).coerceAtLeast(MIN_TAKEOVER_DRAWS)

    /**
     * Plays the draw out, in the same time whatever the field size.
     *
     * The pacing is a FRAME BUDGET, not a fixed step: the bulk of the draws are
     * spent over [BULK_FRAMES] frames however many there are, so 600 draws take
     * no longer on screen than 300 did — the tally simply climbs in bigger
     * jumps early on. The last [TAIL_DRAWS] land one at a time and slow down, so
     * the moment it settles is legible, which is the whole point of drawing
     * hundreds of times instead of once. About 3.4s either way, against the
     * club draw's 4.0s for its 300.
     */
    private fun runDrawAnimation() = viewModelScope.launch {
        val total = _state.value.takeover?.ticks?.size ?: return@launch
        var tick = 0
        val bulk = (total - TAIL_DRAWS).coerceAtLeast(0)
        while (tick < bulk) {
            val step = ((bulk - tick + BULK_FRAMES - 1) / BULK_FRAMES).coerceAtLeast(1)
            tick = (tick + step).coerceAtMost(bulk)
            _state.update { it.copy(drawTick = tick) }
            delay(BULK_FRAME_MS)
        }
        while (tick < total) {
            tick++
            _state.update { it.copy(drawTick = tick) }
            // Easing out over the last few: 60ms up to 150ms as it settles.
            val left = total - tick
            delay(if (left >= TAIL_DRAWS / 2) 70L else 170L)
        }
        delay(500)
        settleDraw()
    }

    /** Puts the user's XI in the replaced nation's place and plays the whole tournament. */
    private fun settleDraw() = viewModelScope.launch {
        val draft = run.draft ?: return@launch
        val cup = loaded ?: return@launch
        val takeover = _state.value.takeover ?: return@launch
        val replaced = cup.data.entries.first { it.id == takeover.replacedTeamId }

        val played = withContext(Dispatchers.Default) {
            val user = WcUserTeam(WC_USER_ID, "Your XI", NationalSide.lineup(-1, draft.placed))
            val result = WcTournamentSimulator.play(cup.data, user, replaced.id, draft.seed)
            result to WcStages.build(result.matches)
        }
        val (result, staging) = played
        _state.update {
            it.copy(
                phase = WcPhase.PlaceWon,
                replacedId = replaced.id,
                userGroup = replaced.group,
                result = result,
                stages = staging.first,
                stageOfMatch = staging.second,
                visibleStage = -1,
                userGroupRows = groupRows(result.tables.getValue(replaced.group).map { r -> r.teamId }, emptyList()),
            )
        }
    }

    fun kickOff() = _state.update { it.copy(phase = WcPhase.Playing) }

    fun playNextStage() {
        val s = _state.value
        if (s.isAdvancing || s.isOver) return
        applyStage(s.visibleStage + 1)
    }

    /** Plays out the rest at reading pace, stoppable, resuming from wherever the user is. */
    fun playToEnd() {
        val s = _state.value
        if (s.isAdvancing || s.isOver) return
        autoPlay = viewModelScope.launch {
            _state.update { it.copy(isAdvancing = true) }
            while (!_state.value.isOver) {
                applyStage(_state.value.visibleStage + 1)
                if (_state.value.isOver) break
                delay(AUTO_STAGE_MS)
            }
            _state.update { it.copy(isAdvancing = false) }
        }
    }

    fun stopAutoPlay() {
        autoPlay?.cancel()
        autoPlay = null
        _state.update { it.copy(isAdvancing = false) }
    }

    fun skipToEnd() {
        autoPlay?.cancel()
        autoPlay = null
        _state.value.stages.lastIndex.takeIf { it >= 0 }?.let(::applyStage)
        _state.update { it.copy(isAdvancing = false) }
    }

    private fun applyStage(stage: Int) {
        val s = _state.value
        val result = s.result ?: return
        val group = s.userGroup ?: return
        val next = s.copy(visibleStage = stage)
        val groupDone = next.playedMatches.count { it.round == GROUP_ROUND && it.group == group } == 6
        // Mid-group the table is ranked live under the edition's own rules. Once
        // the group is complete the ENGINE's table is shown, because that is the
        // one the knockouts were drawn from — including any drawing of lots.
        val rows = if (groupDone) {
            result.tables.getValue(group)
        } else {
            val results = next.playedMatches
                .filter { it.round == GROUP_ROUND && it.group == group }
                .map { GroupResult(it.homeId, it.awayId, it.score.homeGoals, it.score.awayGoals) }
            groupRows(result.tables.getValue(group).map { it.teamId }, results)
        }
        _state.update {
            it.copy(
                visibleStage = stage,
                userGroupRows = rows,
                phase = if (next.isOver) WcPhase.Finished else it.phase,
            )
        }
    }

    private fun groupRows(teams: List<String>, results: List<GroupResult>) =
        WcGroupTable.rank(
            teams,
            results,
            com.dreamxi.app.sim.worldcup.TiebreakRules.forYear(_state.value.year ?: 2026),
            // Fixed, so a table mid-group does not reshuffle a level pair on
            // every recomposition.
            Random(0),
        )
}
