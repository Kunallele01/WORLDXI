package com.dreamxi.app.feature.worldcup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.data.toUserFacingError
import com.dreamxi.app.data.worldcup.CompletedWcDraft
import com.dreamxi.app.data.worldcup.WcPoolEntry
import com.dreamxi.app.data.worldcup.WorldCupRepository
import com.dreamxi.app.data.worldcup.WorldCupRun
import com.dreamxi.app.feature.draft.DraftedPlayer
import com.dreamxi.app.feature.draft.FormationSlot
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.feature.draft.formationById
import com.dreamxi.app.feature.freemode.CONJURE_MS
import com.dreamxi.app.feature.freemode.ClubOption
import com.dreamxi.app.feature.freemode.FreeModeUiState
import com.dreamxi.app.feature.freemode.MagicCandidate
import com.dreamxi.app.feature.freemode.MagicPhase
import com.dreamxi.app.feature.freemode.MagicXi
import com.dreamxi.app.feature.freemode.REVEAL_LINE_PAUSE_MS
import com.dreamxi.app.feature.freemode.REVEAL_STEP_MS
import com.dreamxi.app.feature.freemode.SeasonOption
import com.dreamxi.app.feature.freemode.lineOf
import com.dreamxi.app.feature.season.ordinal
import com.dreamxi.app.sim.worldcup.WcPlayer
import com.dreamxi.app.sim.worldcup.WcSlot
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * World Cup Free Mode: any player from any nation at any World Cup, plus the
 * magic XI.
 *
 * The club Free Mode's screen, fed by a national pool — "club" is a nation and
 * "season" a World Cup — and the same rules, which are about football rather than
 * difficulty: one real person once, out-of-position play priced, the keeper
 * boundary absolute. No bench, because a World Cup XI is eleven men.
 *
 * The magic draws from EVERY squad at EVERY World Cup (the user's choice), and
 * uses the club mode's solver unchanged: the eleven chosen together, a small
 * jitter so a second press is a different side, kept shirts filled around, and a
 * tap walking one position's ladder. What those rules mean and why they exist is
 * documented once, on FreeModeViewModel and MagicXi; this class follows them.
 */
@HiltViewModel
class WcFreeModeViewModel @Inject constructor(
    private val repository: WorldCupRepository,
    private val run: WorldCupRun,
) : ViewModel() {

    private val _state = MutableStateFlow(FreeModeUiState(worldCup = true, leagueName = "World Cup"))
    val state: StateFlow<FreeModeUiState> = _state.asStateFlow()

    private var started = false
    private var pool: List<WcPoolEntry> = emptyList()

    /** The engine's view of every player shown or conjured this run, by squad-player id. */
    private val enginePlayers = mutableMapOf<Long, WcPlayer>()

    private var magicPool: List<MagicCandidate> = emptyList()
    private var magicOriginal: Map<String, MagicCandidate> = emptyMap()

    /** Shirts filled by hand, which the next cast fills around. See FreeModeViewModel.manualSlots. */
    private val manualSlots = mutableSetOf<String>()

    fun start(formationId: String?) {
        if (started) return
        started = true
        _state.update { it.copy(formation = formationById(formationId)) }
        loadPool()
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        loadPool()
    }

    private fun loadPool() = viewModelScope.launch {
        _state.update { it.copy(isLoading = true) }
        runCatching { repository.draftPool() }
            .onSuccess { entries ->
                pool = entries
                _state.update {
                    it.copy(isLoading = false, clubs = entries.map { e -> e.nation }.distinct().sorted(), error = null)
                }
            }
            .onFailure { e ->
                _state.update { it.copy(isLoading = false, error = e.toUserFacingError("load the World Cup squads")) }
            }
    }

    /** Every nation, with how many World Cups it played. */
    fun clubOptions(): List<ClubOption> =
        pool.groupBy { it.nation }.map { (nation, entries) -> ClubOption(nation, entries.size) }

    /** One nation's World Cups, newest first, with how each went in the group. */
    fun seasonOptions(nation: String): List<SeasonOption> =
        pool.filter { it.nation == nation }
            .sortedByDescending { it.year }
            .map { e ->
                SeasonOption(
                    label = e.year.toString(),
                    finalPosition = null,
                    squadStrength = null,
                    note = e.groupPosition?.let { pos -> "${ordinal(pos)} in Group ${e.groupLabel.orEmpty()}" },
                )
            }

    fun selectSlot(slot: FormationSlot) {
        val existing = _state.value.picks[slot.id]
        _state.update {
            it.copy(
                openSlot = slot,
                selectedClub = existing?.clubName,
                selectedSeason = existing?.seasonLabel,
                squad = if (existing == null) emptyList() else it.squad,
            )
        }
        if (existing != null) loadSquad(existing.clubName, existing.seasonLabel)
    }

    fun clearClub() = _state.update { it.copy(selectedClub = null, selectedSeason = null, squad = emptyList()) }

    fun closePicker() = _state.update { it.copy(openSlot = null, squad = emptyList(), isLoadingSquad = false) }

    fun chooseClub(nation: String) =
        _state.update { it.copy(selectedClub = nation, selectedSeason = null, squad = emptyList()) }

    fun selectSeason(year: String) {
        val nation = _state.value.selectedClub ?: return
        _state.update { it.copy(selectedSeason = year, squad = emptyList()) }
        loadSquad(nation, year)
    }

    private fun loadSquad(nation: String, year: String) = viewModelScope.launch {
        val entry = pool.firstOrNull { it.nation == nation && it.year.toString() == year } ?: return@launch
        _state.update { it.copy(isLoadingSquad = true, squad = emptyList()) }
        runCatching { repository.squad(entry.squadId) }
            .onSuccess { squad ->
                val drafted = _state.value.picks.values.map { it.player.playerId }.toSet()
                squad.forEach { enginePlayers[it.squadPlayerId] = it.player }
                _state.update { it.copy(isLoadingSquad = false, squad = squad.map { p -> p.toSquadPlayer(drafted) }) }
            }
            .onFailure { e ->
                _state.update { it.copy(isLoadingSquad = false, error = e.toUserFacingError("load that squad")) }
            }
    }

    /** Puts a player in the open shirt, replacing whoever was there; remembered as hand-picked. */
    fun place(player: SquadPlayer) {
        val slotId = _state.value.openSlot?.id
        _state.update { s ->
            val slot = s.openSlot ?: return@update s
            if ((player.role == "GK") != slot.isGoalkeeper) return@update s
            val nation = s.selectedClub ?: return@update s
            val year = s.selectedSeason ?: return@update s
            val picks = s.picks.filterValues { it.player.playerId != player.playerId } +
                (slot.id to DraftedPlayer(slot, player, nation, year))
            s.copy(
                picks = picks,
                openSlot = null,
                squad = emptyList(),
                magic = if (s.magic == MagicPhase.IDLE) MagicPhase.IDLE else MagicPhase.TWEAKED,
            )
        }
        if (slotId != null && _state.value.picks[slotId]?.player?.playerId == player.playerId) {
            manualSlots += slotId
            manualSlots.retainAll(_state.value.picks.keys)
        }
    }

    fun clearSlot(slotId: String) {
        manualSlots -= slotId
        _state.update {
            it.copy(
                picks = it.picks - slotId,
                openSlot = null,
                magic = if (it.magic == MagicPhase.IDLE) MagicPhase.IDLE else MagicPhase.TWEAKED,
            )
        }
    }

    /**
     * The best eleven any World Cup has seen, around anything already hand-picked.
     * The pool is read once and kept, so a second press and every tap are instant.
     */
    fun castMagic() = viewModelScope.launch {
        val formation = _state.value.formation
        val kept = _state.value.picks
            .filterKeys { it in manualSlots }
            .takeIf { it.size < formation.slots.size }
            ?: emptyMap()
        manualSlots.clear()
        _state.update {
            it.copy(magic = MagicPhase.CONJURING, picks = kept, openSlot = null, squad = emptyList(), error = null)
        }
        delay(CONJURE_MS)

        val candidates = runCatching { magicCandidates() }.getOrElse { e ->
            _state.update { it.copy(magic = MagicPhase.IDLE, error = e.toUserFacingError("work the magic")) }
            return@launch
        }
        val xi = withContext(Dispatchers.Default) {
            MagicXi.build(
                candidates = candidates,
                formation = formation,
                keptSlotIds = kept.keys,
                keptPlayerIds = kept.values.map { it.player.playerId }.toSet(),
            )
        }
        magicOriginal = xi.associate { it.slot.id to it.candidate }
        if (xi.isEmpty()) {
            _state.update { it.copy(magic = MagicPhase.IDLE, error = DreamXiError.NoData) }
            return@launch
        }

        _state.update { it.copy(magic = MagicPhase.REVEALING) }
        var previousLine = -1
        for (pick in xi) {
            val line = lineOf(pick.slot.role)
            if (previousLine != -1 && line != previousLine) delay(REVEAL_LINE_PAUSE_MS)
            previousLine = line
            _state.update { s ->
                s.copy(
                    picks = s.picks + (pick.slot.id to DraftedPlayer(
                        slot = pick.slot,
                        player = pick.candidate.player,
                        clubName = pick.candidate.clubName,
                        seasonLabel = pick.candidate.seasonLabel,
                    )),
                )
            }
            delay(REVEAL_STEP_MS)
        }
        _state.update { it.copy(magic = MagicPhase.DONE) }
    }

    /** Swaps the man in this shirt for the next on its ladder, wrapping round. */
    fun cycleSlot(slot: FormationSlot) {
        val s = _state.value
        if (!s.magic.isCastComplete) return
        val current = s.picks[slot.id]
        val taken = s.picks.filterKeys { it != slot.id }.values.map { it.player.playerId }.toSet()
        val ladder = MagicXi.ladder(candidates = magicPool, slot = slot, exclude = taken, keep = magicOriginal[slot.id])
        if (ladder.isEmpty()) return
        val here = ladder.indexOfFirst { it.candidate.player.playerId == current?.player?.playerId }
        val next = ladder[(here + 1) % ladder.size]
        if (next.candidate.player.playerId == current?.player?.playerId) return
        _state.update { st ->
            st.copy(
                picks = st.picks + (slot.id to DraftedPlayer(
                    slot = slot,
                    player = next.candidate.player,
                    clubName = next.candidate.clubName,
                    seasonLabel = next.candidate.seasonLabel,
                )),
                magic = MagicPhase.TWEAKED,
            )
        }
    }

    /**
     * Every player at every World Cup as a magic candidate. A squad two World
     * Cups share (Japan's FIFA 11 squad served 2006 and 2010) appears under both;
     * the solver collapses each real person to one option anyway.
     */
    private suspend fun magicCandidates(): List<MagicCandidate> {
        if (magicPool.isNotEmpty()) return magicPool
        val squads = repository.allSquadPlayers()
        val candidates = withContext(Dispatchers.Default) {
            pool.flatMap { entry ->
                squads[entry.squadId].orEmpty().map { p ->
                    enginePlayers[p.squadPlayerId] = p.player
                    MagicCandidate(
                        player = p.toSquadPlayer(emptySet()),
                        clubSeasonId = entry.entryId,
                        clubName = entry.nation,
                        seasonLabel = entry.year.toString(),
                    )
                }
            }
        }
        magicPool = candidates
        return candidates
    }

    /** Hands the finished XI to the tournament; false if it is not ready. */
    fun beginTournament(): Boolean {
        val s = _state.value
        if (!s.isComplete) return false
        val ordered = s.formation.slots.mapNotNull { slot -> s.picks[slot.id] }
        val placed = ordered.map { pick ->
            val player = enginePlayers[pick.player.playerSeasonStatId] ?: return false
            WcSlot(pick.slot.role, pick.slot.side, pick.slot.isWingBack) to player
        }
        run.start(
            CompletedWcDraft(s.formation, ordered, placed, seed = System.currentTimeMillis(), isFreeMode = true),
        )
        return true
    }
}
