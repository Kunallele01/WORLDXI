package com.dreamxi.app.feature.worldcup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.data.toUserFacingError
import com.dreamxi.app.data.worldcup.CompletedWcDraft
import com.dreamxi.app.data.worldcup.WcDraftPlayer
import com.dreamxi.app.data.worldcup.WcPoolEntry
import com.dreamxi.app.data.worldcup.WorldCupRepository
import com.dreamxi.app.data.worldcup.WorldCupRun
import com.dreamxi.app.feature.draft.DraftMode
import com.dreamxi.app.feature.draft.DraftUiState
import com.dreamxi.app.feature.draft.FormationSlot
import com.dreamxi.app.feature.draft.SpinResult
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.feature.draft.formationById
import com.dreamxi.app.feature.draft.gridKeyForRole
import com.dreamxi.app.feature.draft.spinWeight
import com.dreamxi.app.feature.draft.weightedIndex
import com.dreamxi.app.feature.season.ordinal
import com.dreamxi.app.sim.worldcup.NationalSide
import com.dreamxi.app.sim.worldcup.WcPlayer
import com.dreamxi.app.sim.worldcup.WcSlot
import com.dreamxi.app.ui.theme.PlayerPosition
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/** Held for a beat so a spin reads as a draw rather than a glitch, as the club draft does. */
private const val SPIN_REEL_MS = 900L

private val OUTFIELD_ROLES = listOf("CB", "FB", "DM", "CM", "CAM", "Winger", "ST")

/**
 * Drives a World Cup draft.
 *
 * The club draft's mechanic with a national pool: every round spins one
 * nation at one World Cup ("Brazil · 2006") from all 208, and any player in
 * that squad can go anywhere on the pitch. Which World Cup the XI then plays is
 * decided AFTER the draft, exactly as the club mode decides its season.
 *
 * The screen is the club draft's own DraftScreen. Only two things are
 * translated for it: a squad's context line, and each player's positional
 * grid and flank, built from exactly what the engine prices him on (his own EA
 * grid or the banded fallback, and his EA side) — so the rating the pitch shows
 * for a player out of position is exactly the one the tournament plays.
 */
@HiltViewModel
class WcDraftViewModel @Inject constructor(
    private val repository: WorldCupRepository,
    private val run: WorldCupRun,
) : ViewModel() {

    private val _state = MutableStateFlow(DraftUiState(mode = DraftMode.WorldCup, leagueName = "World Cup"))
    val state: StateFlow<DraftUiState> = _state.asStateFlow()

    private var started = false
    private var pool: List<WcPoolEntry> = emptyList()
    private var poolLoaded = false

    /** The engine's view of every player shown this run, by squad-player id. */
    private val enginePlayers = mutableMapOf<Long, WcPlayer>()

    fun start(formationId: String?, rerollsAllowed: Int) {
        if (started) return
        started = true
        _state.value = DraftUiState(
            mode = DraftMode.WorldCup,
            leagueName = "World Cup",
            formation = formationById(formationId),
            rerollsAllowed = rerollsAllowed,
        )
        reload()
    }

    fun spin() = resolveSpin(excludeEntryId = null)

    /** Reject the squad on screen and draw again, never landing on the same one. */
    fun changeSpin() {
        val s = _state.value
        if (s.rerollsRemaining <= 0 || s.spin == null) return
        _state.update { it.copy(rerollsUsed = it.rerollsUsed + 1) }
        resolveSpin(excludeEntryId = s.spin.clubSeasonId)
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        if (!poolLoaded || pool.isEmpty()) reload()
    }

    private fun reload() = viewModelScope.launch {
        runCatching { repository.draftPool() }
            .onSuccess {
                pool = it
                poolLoaded = true
                _state.update { s -> s.copy(error = if (it.isEmpty()) DreamXiError.NoData else null) }
            }
            .onFailure { e -> _state.update { it.copy(error = e.toUserFacingError("load the World Cup squads")) } }
    }

    private fun resolveSpin(excludeEntryId: Long?) {
        val current = _state.value
        if (current.isSpinning || current.isComplete) return

        viewModelScope.launch {
            // An empty pool that never loaded is a connection problem, not an
            // empty tournament list — try again before saying anything else.
            if (pool.isEmpty() && !poolLoaded) {
                runCatching { repository.draftPool() }
                    .onSuccess { pool = it; poolLoaded = true }
                    .onFailure { e ->
                        _state.update { it.copy(error = e.toUserFacingError("load the World Cup squads")) }
                        return@launch
                    }
            }
            val candidates = pool.filter { it.entryId != excludeEntryId }
            if (candidates.isEmpty()) {
                _state.update { it.copy(error = DreamXiError.NoData) }
                return@launch
            }

            _state.update {
                it.copy(
                    isSpinning = true,
                    spin = null,
                    selectedPlayer = null,
                    error = null,
                    reelClubNames = candidates.map { e -> "${e.nation} ${e.year}" }.distinct().shuffled(),
                )
            }

            // Repeats made unlikely, never impossible, with the club draft's
            // weights: a nation already drafted from counts as a repeat club, the
            // same nation at the same World Cup as a repeat club-season. Those
            // weights were measured on club pools and are reused here unmeasured.
            val picked = _state.value.picks.values
            val weights = candidates.map { e ->
                spinWeight(
                    timesClubUsed = picked.count { it.clubName == e.nation },
                    timesClubSeasonUsed = picked.count { it.clubName == e.nation && it.seasonLabel == e.year.toString() },
                )
            }
            val chosen = candidates[weightedIndex(weights, Random.nextDouble())]
            val result = runCatching { repository.squad(chosen.squadId) }
            delay(SPIN_REEL_MS)

            result
                .onSuccess { squad ->
                    val drafted = _state.value.picks.values.map { it.player.playerId }.toSet()
                    squad.forEach { enginePlayers[it.squadPlayerId] = it.player }
                    val players = squad.map { it.toSquadPlayer(drafted) }
                    _state.update {
                        it.copy(
                            isSpinning = false,
                            spin = SpinResult(
                                clubSeasonId = chosen.entryId,
                                clubName = chosen.nation,
                                seasonLabel = chosen.year.toString(),
                                finalPosition = null,
                                squadStrength = null,
                                strengthQuartile = null,
                                players = players,
                                detail = detailFor(chosen),
                            ),
                        )
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(isSpinning = false, error = e.toUserFacingError("load squad")) }
                }
        }
    }

    fun selectPlayer(player: SquadPlayer?) {
        val usable = player?.takeUnless { it.alreadyDrafted }
        _state.update { it.copy(selectedPlayer = usable, isPlacing = usable != null) }
    }

    fun cancelPlacement() = _state.update { it.copy(selectedPlayer = null, isPlacing = false) }
    fun viewTeam() = _state.update { it.copy(isViewingTeam = true, heldSlotId = null) }
    fun closeTeam() = _state.update { it.copy(isViewingTeam = false, heldSlotId = null) }

    fun holdPlaced(slotId: String) = _state.update {
        if (it.heldSlotId == slotId) it.copy(heldSlotId = null) else it.copy(heldSlotId = slotId)
    }

    fun moveHeldTo(targetSlotId: String) = _state.update { it.withHeldMovedTo(targetSlotId) }

    fun confirmPick(player: SquadPlayer, slot: FormationSlot) = _state.update { it.withPick(player, slot) }

    /**
     * Hands the finished XI to the tournament and returns true if it is playable.
     * The seed is fixed here, once, for the club draft's reason: made any later,
     * a rotation would reshuffle the edition and the draw under the user.
     */
    fun beginTournament(): Boolean {
        val s = _state.value
        if (!s.isComplete) return false
        val ordered = s.formation.slots.mapNotNull { slot -> s.picks[slot.id] }
        val placed = ordered.map { pick ->
            val player = enginePlayers[pick.player.playerSeasonStatId] ?: return false
            WcSlot(pick.slot.role, pick.slot.side, pick.slot.isWingBack) to player
        }
        run.start(CompletedWcDraft(s.formation, ordered, placed, seed = System.currentTimeMillis()))
        return true
    }

    /**
     * "4th in Group H", plus where the squad's ratings really came from when a
     * nation's own edition could not field eleven and a nearer one was
     * borrowed — shown so a 2026 squad of 2024 players is never passed off as
     * something it is not.
     */
    private fun detailFor(e: WcPoolEntry): String = buildString {
        if (e.groupPosition != null && e.groupLabel != null) {
            append("Finished ${ordinal(e.groupPosition)} in Group ${e.groupLabel}")
        }
        if (e.squadEdition != e.anchorEdition) {
            if (isNotEmpty()) append(" · ")
            append("squad rated on ${editionName(e.squadEdition)}, the nearest edition with a full squad")
        }
    }

}

/** "07" -> "FIFA 07", "24" -> "EA FC 24": EA's own name for each edition. */
internal fun editionName(edition: String): String =
    if ((edition.toIntOrNull() ?: 0) >= 24) "EA FC $edition" else "FIFA $edition"

/**
 * A World Cup player in the draft's shape. His positional grid is the engine's
 * cost of each role before any flank, and his side is his EA side, so the club
 * draft's own pricing (grid delta, then SquadPlayer.sidePenaltyAt) lands exactly
 * on the rating the engine plays him at — WcScreensTest holds the two together.
 */
internal fun WcDraftPlayer.toSquadPlayer(drafted: Set<Long>): SquadPlayer {
    val role = player.primaryRole
    val overall = player.overall
    val personId = personId?.toLongOrNull() ?: -squadPlayerId
    // His positional grid, from the measured cost of each role — built so
    // the draft's own out-of-position pricing lands on the engine's number.
    val grid = if (player.isKeeper) {
        emptyMap()
    } else {
        OUTFIELD_ROLES.mapNotNull { r ->
            // The UNCAPPED difference, so the draft's own grid arithmetic caps
            // it once at the end exactly as the engine does. Baking in a capped
            // value here made a wing-back a point cheaper on the pitch than in
            // the match that followed.
            val cost = NationalSide.rawCost(player, r)
            gridKeyForRole(r)?.let { it to overall + cost }
        }.toMap()
    }
    return SquadPlayer(
        playerSeasonStatId = squadPlayerId,
        playerId = personId,
        fullName = player.name,
        role = role,
        group = when (role) {
            "GK" -> PlayerPosition.GOALKEEPER
            "CB", "FB" -> PlayerPosition.DEFENDER
            "DM", "CM", "CAM" -> PlayerPosition.MIDFIELDER
            else -> PlayerPosition.FORWARD
        },
        overallRating = overall,
        minutes = 0,
        goals = 0,
        assists = 0,
        positionRatings = grid,
        alreadyDrafted = personId in drafted,
        side = player.side,
        club = club,
        eaPositions = player.positions,
    )
}
