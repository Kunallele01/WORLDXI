package com.dreamxi.app.feature.draft

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamxi.app.data.draft.ClubSeasonPoolDto
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.data.draft.DraftRepository
import com.dreamxi.app.data.run.ActiveRun
import com.dreamxi.app.data.run.CompletedDraft
import com.dreamxi.app.feature.draft.DefensiveRecord
import com.dreamxi.app.data.toUserFacingError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.random.Random

/**
 * Drives one draft run.
 *
 * The mechanic this implements, which is easy to get subtly wrong: every
 * round spins a random (SEASON, club) pair from the chosen league's entire
 * loaded history — the season is NOT fixed at setup. One draft can mix 2019/20
 * Liverpool with 2023/24 Girona, and that cross-era mixing is the point of the
 * game. The simulated season and the club the XI replaces are both decided
 * later, at simulate time, not here.
 */
@HiltViewModel
class DraftViewModel @Inject constructor(
    private val repository: DraftRepository,
    private val activeRun: ActiveRun,
) : ViewModel() {

    private val _state = MutableStateFlow(DraftUiState())
    val state: StateFlow<DraftUiState> = _state.asStateFlow()

    private var leagueId: Long? = null
    private var runId: Long? = null
    private var pool: List<ClubSeasonPoolDto> = emptyList()

    /**
     * Whether the pool has ever loaded successfully.
     *
     * Distinguishes "we asked and this league genuinely has no clubs" from
     * "we never got an answer". Without it an offline start produced an empty
     * pool, and pressing Spin then reported "Nothing to draft from — pick a
     * different league", overwriting the correct offline message with advice
     * that sends the user to try the other league instead of their Wi-Fi.
     */
    private var poolLoaded = false

    /**
     * Every squad this run has spun, kept so the bench can be filled from them
     * when the XI is finished. A run visits eleven club-seasons and takes one
     * player from each; the eight substitutes come from the players it passed
     * over. See BenchSelection.
     */
    private val visited = mutableListOf<VisitedSquad>()
    private var seasonLabels: Map<Long, String> = emptyMap()
    private var defensiveRecords: Map<Long, DefensiveRecord> = emptyMap()

    /**
     * [leagueId] is null until the Setup screen passes a chosen league through,
     * in which case the first loaded league is used. Resolved from the database
     * rather than defaulted to a literal id — league ids are assigned by the
     * ETL and are not 1-based (they are currently 4 and 5), so a hardcoded
     * fallback silently yields an empty pool and a draft that cannot spin.
     *
     * [runId] is null until run persistence (which needs auth) is wired. With
     * a run, spin eligibility comes from the database RPC and the tier caps
     * apply; without one, the draft is local and uncapped.
     */
    fun start(
        leagueId: Long?,
        leagueName: String?,
        formationId: String? = null,
        rerollsAllowed: Int = 2,
        runId: Long? = null,
    ) {
        if (this.leagueId != null) return
        this.runId = runId
        _state.value = DraftUiState(
            leagueName = leagueName.orEmpty(),
            formation = formationById(formationId),
            rerollsAllowed = rerollsAllowed,
        )
        viewModelScope.launch {
            runCatching {
                val resolvedId: Long
                val resolvedName: String
                if (leagueId != null) {
                    resolvedId = leagueId
                    resolvedName = leagueName.orEmpty()
                } else {
                    val first = repository.leagues().firstOrNull { it.seasonCount > 0 }
                        ?: error("No leagues loaded")
                    resolvedId = first.id
                    resolvedName = first.name
                }
                this@DraftViewModel.leagueId = resolvedId
                seasonLabels = repository.seasonLabels(resolvedId)
                pool = repository.eligibleClubSeasons(resolvedId, runId)
                defensiveRecords = repository.defensiveRecords(resolvedId)
                poolLoaded = true
                _state.update { it.copy(leagueName = resolvedName, error = null) }
            }.onFailure { e ->
                _state.update { it.copy(error = e.toUserFacingError("load club pool")) }
            }
        }
    }

    fun spin() = resolveSpin(isReroll = false)

    /**
     * A "change spin": reject the squad on screen and draw again.
     *
     * The rejected club-season is excluded from the redraw. Redrawing from the
     * full pool could return the same squad the user just rejected, which
     * would spend a scarce token for literally nothing — the one outcome that
     * would make the feature feel broken.
     */
    fun changeSpin() {
        val s = _state.value
        if (s.rerollsRemaining <= 0 || s.spin == null) return
        _state.update { it.copy(rerollsUsed = it.rerollsUsed + 1) }
        resolveSpin(isReroll = true, excludeClubSeasonId = s.spin.clubSeasonId)
    }

    private fun resolveSpin(isReroll: Boolean, excludeClubSeasonId: Long? = null) {
        val league = leagueId ?: return
        val current = _state.value
        if (current.isSpinning || current.isComplete) return

        viewModelScope.launch {
            // Refresh eligibility per spin when a run exists: the caps depend
            // on what has already been spun, so a pool cached at start would
            // let a capped tier keep appearing.
            if (runId != null) {
                runCatching { pool = repository.eligibleClubSeasons(league, runId) }
            }
            // An empty pool we never managed to load is a CONNECTION problem,
            // not an empty league. Try once more so a user who has just fixed
            // their Wi-Fi can simply press Spin again, and report the real
            // failure if it still will not load.
            if (pool.isEmpty() && !poolLoaded) {
                val reloaded = runCatching {
                    seasonLabels = repository.seasonLabels(league)
                    repository.eligibleClubSeasons(league, runId)
                }
                reloaded
                    .onSuccess { pool = it; poolLoaded = true }
                    .onFailure { e ->
                        _state.update { it.copy(error = e.toUserFacingError("load club pool")) }
                        return@launch
                    }
            }

            // Repeats are made UNLIKELY, not impossible.
            //
            // The pool used to have no memory at all, so 43.6% of drafts drew
            // the identical club-season twice and better than one in four saw
            // some club three times. Banning repeats fixed that and overshot —
            // a club coming round again is part of the spin, it just should not
            // be as likely as a club the user has not seen. See spinWeight.
            val candidates = pool.filter { it.clubSeasonId != excludeClubSeasonId }
            if (candidates.isEmpty()) {
                // Only now is "this league has nothing in it" an honest claim.
                _state.update { it.copy(error = DreamXiError.NoData) }
                return@launch
            }

            _state.update {
                it.copy(
                    isSpinning = true,
                    spin = null,
                    selectedPlayer = null,
                    error = null,
                    reelClubNames = candidates.map(ClubSeasonPoolDto::clubName).distinct().shuffled(),
                )
            }

            val picked = _state.value.picks.values
            val weights = candidates.map { cs ->
                val label = seasonLabels[cs.seasonId]
                spinWeight(
                    timesClubUsed = picked.count { it.clubName == cs.clubName },
                    timesClubSeasonUsed = picked.count {
                        it.clubName == cs.clubName && it.seasonLabel == label
                    },
                )
            }
            val chosen = candidates[weightedIndex(weights, Random.nextDouble())]
            val draftedIds = _state.value.picks.values.map { it.player.playerId }.toSet()

            val result = runCatching {
                repository.squadFor(
                    clubSeasonId = chosen.clubSeasonId,
                    clubName = chosen.clubName,
                    seasonLabel = seasonLabels[chosen.seasonId] ?: "",
                    finalPosition = chosen.finalPosition,
                    squadStrength = chosen.squadStrength,
                    strengthQuartile = chosen.strengthQuartile,
                    alreadyDraftedPlayerIds = draftedIds,
                    defence = defensiveRecords[chosen.clubSeasonId],
                )
            }

            // Hold the reel for a beat even if the query returned instantly.
            // A spin that resolves in 40ms reads as a glitch rather than a
            // draw; the anticipation IS the mechanic.
            delay(SPIN_REEL_MS)

            result
                .onSuccess { spin -> _state.update { it.copy(isSpinning = false, spin = spin) } }
                .onFailure { e ->
                    _state.update {
                        it.copy(isSpinning = false, error = e.toUserFacingError("load squad"))
                    }
                }
        }
    }

    /**
     * Retry after a failure. Reloads the pool first when it is empty — an
     * offline start leaves nothing to spin, so retrying the spin alone would
     * fail again for a reason the user already fixed.
     */
    fun retry() {
        _state.update { it.copy(error = null) }
        if (!poolLoaded || pool.isEmpty()) reload()
    }

    private fun reload() {
        val league = leagueId ?: return
        viewModelScope.launch {
            runCatching {
                seasonLabels = repository.seasonLabels(league)
                pool = repository.eligibleClubSeasons(league, runId)
                poolLoaded = true
            }.onFailure { e ->
                _state.update { it.copy(error = e.toUserFacingError("reload club pool")) }
            }
        }
    }

    /**
     * Selecting a player immediately raises the pitch as a placement sheet.
     * The two are one gesture, not two: choosing a player is only meaningful
     * as the first half of deciding where he goes.
     */
    fun selectPlayer(player: SquadPlayer?) {
        val usable = player?.takeUnless { it.alreadyDrafted }
        _state.update { it.copy(selectedPlayer = usable, isPlacing = usable != null) }
    }

    fun cancelPlacement() {
        _state.update { it.copy(selectedPlayer = null, isPlacing = false) }
    }

    /** Open the pitch to review and rearrange the XI, with nothing in hand. */
    fun viewTeam() = _state.update { it.copy(isViewingTeam = true, heldSlotId = null) }

    fun closeTeam() = _state.update { it.copy(isViewingTeam = false, heldSlotId = null) }

    /** Pick a placed player up, or put him back down if he was already held. */
    fun holdPlaced(slotId: String) = _state.update {
        if (it.heldSlotId == slotId) it.copy(heldSlotId = null) else it.copy(heldSlotId = slotId)
    }

    /**
     * Move the held player to [targetSlotId], swapping with whoever is there.
     *
     * Ratings are NOT recomputed here and must not be: a DraftedPlayer derives
     * its effective rating from the position it holds, so moving Benzema from
     * ST to LW makes him an 89 and moving him back makes him a 91 again, with
     * no stored number to go stale. That is the whole reason the rating is
     * derived rather than saved at pick time.
     */
    fun moveHeldTo(targetSlotId: String) {
        _state.update { s ->
            val fromId = s.heldSlotId ?: return@update s
            if (fromId == targetSlotId) return@update s.copy(heldSlotId = null)
            val moving = s.picks[fromId] ?: return@update s.copy(heldSlotId = null)
            val target = s.formation.slots.firstOrNull { it.id == targetSlotId }
                ?: return@update s
            val displaced = s.picks[targetSlotId]

            // Goalkeeper stays locked in both directions, exactly as at draft
            // time. A swap is two moves, so BOTH have to be legal.
            if ((moving.player.role == "GK") != target.isGoalkeeper) return@update s
            val fromSlot = s.formation.slots.first { it.id == fromId }
            if (displaced != null && (displaced.player.role == "GK") != fromSlot.isGoalkeeper) {
                return@update s
            }

            val next = s.picks.toMutableMap()
            next[targetSlotId] = moving.copy(slot = target)
            if (displaced != null) next[fromId] = displaced.copy(slot = fromSlot) else next.remove(fromId)
            s.copy(picks = next, heldSlotId = null)
        }
    }

    fun confirmPick(player: SquadPlayer, slot: FormationSlot) {
        // The squad this pick came from joins the bench pool. Recorded on the
        // PICK rather than on the spin so a rejected squad cannot contribute a
        // substitute — the user turned that one down.
        _state.value.spin?.let { spin ->
            if (visited.none { it.clubName == spin.clubName && it.seasonLabel == spin.seasonLabel }) {
                visited += VisitedSquad(spin.clubName, spin.seasonLabel, spin.players)
            }
        }

        _state.update { s ->
            // Guard the invariants that still exist, and ONLY those.
            //
            // This used to also require the slot's role to equal the player's,
            // left over from when positions were locked. Once any outfielder
            // could fill any outfield position, that check silently rejected
            // every out-of-position pick: the UI offered all ten positions, the
            // button animated on press, and the pick went nowhere. Natural-
            // position picks still worked, which is why a draft got five or six
            // rounds in before appearing to freeze.
            //
            // The real invariants now: the position must still be open, the
            // player must not already be in the XI (identity is unique across
            // the whole draft, not per club-season), and the goalkeeper
            // boundary must not be crossed.
            if (slot.id in s.picks) return@update s
            if (player.playerId in s.picks.values.map { it.player.playerId }) return@update s
            if ((player.role == "GK") != slot.isGoalkeeper) return@update s
            val spin = s.spin ?: return@update s
            val picks = s.picks + (slot.id to DraftedPlayer(slot, player, spin.clubName, spin.seasonLabel))
            s.copy(
                picks = picks,
                // Filled the moment the XI is complete, so the finished-squad
                // screen can show it rather than it appearing from nowhere at
                // the first scoreline.
                bench = if (picks.size == s.formation.slots.size) {
                    BenchSelection.pick(visited, picks.values.toList(), s.formation)
                } else {
                    s.bench
                },
                selectedPlayer = null,
                isPlacing = false,
                heldSlotId = null,
                spin = null,
                round = (s.round + 1).coerceAtMost(s.totalRounds),
            )
        }
    }

    /**
     * Hands the finished XI to the season and returns true if it is playable.
     *
     * The seed is fixed HERE, once, rather than when the season screen opens.
     * A seed made later would be remade on every rotation and every return
     * from the background, quietly reshuffling the league and the takeover
     * draw underneath a run the user was halfway through.
     */
    fun beginSeason(): Boolean {
        val s = _state.value
        val league = leagueId ?: return false
        if (!s.isComplete) return false
        activeRun.start(
            CompletedDraft(
                leagueId = league,
                leagueName = s.leagueName,
                formation = s.formation,
                picks = s.formation.slots.mapNotNull { slot -> s.picks[slot.id] },
                bench = s.bench,
                seed = System.currentTimeMillis(),
            ),
        )
        return true
    }

    private companion object {
        const val SPIN_REEL_MS = 900L
    }
}
