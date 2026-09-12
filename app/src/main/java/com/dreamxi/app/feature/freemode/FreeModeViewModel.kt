package com.dreamxi.app.feature.freemode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.data.draft.ClubSeasonPoolDto
import com.dreamxi.app.data.draft.DraftRepository
import com.dreamxi.app.data.run.ActiveRun
import com.dreamxi.app.data.run.CompletedDraft
import com.dreamxi.app.data.toUserFacingError
import com.dreamxi.app.feature.draft.BenchPlayer
import com.dreamxi.app.feature.draft.BenchSelection
import com.dreamxi.app.feature.draft.DraftedPlayer
import com.dreamxi.app.feature.draft.VisitedSquad
import com.dreamxi.app.feature.draft.Formation
import com.dreamxi.app.feature.draft.FormationSlot
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.feature.draft.formationById
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Free Mode: build any XI you like, from any club, from any season.
 *
 * The sandbox beside the draft, not a replacement for it. There are no spins,
 * no rerolls and no scarcity, and that is the entire point — the user asked for
 * a mode where a 90-rated side is easy to assemble. So DO NOT add rating caps,
 * budgets or balancing here. The spin draft is where scarcity lives; this is
 * where it deliberately does not.
 *
 * What it does keep from the draft, because these are about football rather
 * than about difficulty:
 *  - the formation is fixed for the run,
 *  - a real person may be picked only once,
 *  - out-of-position play still costs what EA's grid says it costs,
 *  - the goalkeeper boundary is still absolute.
 */
@HiltViewModel
class FreeModeViewModel @Inject constructor(
    private val repository: DraftRepository,
    private val activeRun: ActiveRun,
) : ViewModel() {

    private val _state = MutableStateFlow(FreeModeUiState())
    val state: StateFlow<FreeModeUiState> = _state.asStateFlow()

    private var leagueId: Long? = null

    /**
     * Every squad the user has opened and taken someone from, kept so the bench
     * can be filled from them. Free Mode has no spins, so the pool is whichever
     * clubs he actually picked from.
     */
    private val visited = mutableListOf<VisitedSquad>()
    private var pool: List<ClubSeasonPoolDto> = emptyList()
    private var started = false

    /**
     * The candidates the last cast was solved from, kept so that tapping a
     * shirt can walk down the ladder without going back to the network. It is
     * a few hundred rows and it is already paid for; re-fetching would put a
     * spinner on a gesture that has to feel instant.
     */
    private var magicPool: List<MagicCandidate> = emptyList()

    /**
     * Who the cast originally put in each shirt, so that walking a shirt's
     * whole cycle comes back to the magic pick rather than to whoever happens
     * to top the ladder.
     */
    private var magicOriginal: Map<String, MagicCandidate> = emptyMap()

    fun start(leagueId: Long?, leagueName: String?, formationId: String?) {
        if (started) return
        started = true
        this.leagueId = leagueId
        _state.update {
            it.copy(
                leagueName = leagueName.orEmpty(),
                formation = formationId?.let(::formationById) ?: it.formation,
            )
        }
        loadPool()
    }

    fun retry() {
        _state.update { it.copy(error = null) }
        loadPool()
    }

    private fun loadPool() = viewModelScope.launch {
        val league = leagueId ?: repository.leagues().firstOrNull()?.id ?: return@launch
        leagueId = league
        _state.update { it.copy(isLoading = true) }
        runCatching {
            val labels = repository.seasonLabels(league)
            val clubSeasons = repository.eligibleClubSeasons(league, runId = null)
            labels to clubSeasons
        }.onSuccess { (labels, clubSeasons) ->
            pool = clubSeasons
            _state.update {
                it.copy(
                    isLoading = false,
                    seasonLabels = labels,
                    clubs = clubSeasons.map(ClubSeasonPoolDto::clubName).distinct().sorted(),
                    error = null,
                )
            }
        }.onFailure { e ->
            _state.update {
                it.copy(isLoading = false, error = e.toUserFacingError("load the player pool"))
            }
        }
    }

    /** Opens the picker for a position. */
    fun selectSlot(slot: FormationSlot) {
        val existing = _state.value.picks[slot.id]
        // A FILLED position reopens on its own club and season, so swapping one
        // Real Madrid player for another is a single tap.
        //
        // An EMPTY one starts at the club list. It used to inherit whichever
        // club was last open, which was harmless when club and season were chip
        // rows — but now that choosing a club is step one, it dropped the user
        // straight into a squad he never asked for and gave him no way to see
        // he was in it.
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

    /** Steps back from a club's seasons to the club list. */
    fun clearClub() {
        _state.update { it.copy(selectedClub = null, selectedSeason = null, squad = emptyList()) }
    }

    fun closePicker() {
        _state.update { it.copy(openSlot = null, squad = emptyList(), isLoadingSquad = false) }
    }

    fun selectClub(club: String) {
        // A club changes which seasons exist, so an incompatible season must not
        // survive the change and silently load nothing.
        val seasons = seasonsFor(club)
        val season = _state.value.selectedSeason.takeIf { it in seasons } ?: seasons.firstOrNull()
        _state.update { it.copy(selectedClub = club, selectedSeason = season, squad = emptyList()) }
        if (season != null) loadSquad(club, season)
    }

    fun selectSeason(season: String) {
        val club = _state.value.selectedClub ?: return
        _state.update { it.copy(selectedSeason = season, squad = emptyList()) }
        loadSquad(club, season)
    }

    fun seasonsFor(club: String): List<String> {
        val labels = _state.value.seasonLabels
        return pool
            .filter { it.clubName == club }
            .mapNotNull { labels[it.seasonId] }
            .distinct()
            .sorted()
    }

    /**
     * Every club in this league, with how many of its seasons are loaded.
     *
     * Ordering is left to the screen, which sorts on the club's real name (see
     * ClubName) rather than the short form the database stores.
     */
    fun clubOptions(): List<ClubOption> =
        pool.groupBy { it.clubName }.map { (name, rows) -> ClubOption(name, rows.size) }

    /**
     * One club's seasons, newest first, with how that side finished and how
     * strong its squad was.
     *
     * A season is chosen ON these two facts — "the year they won it", "their
     * best squad" — and until now the picker showed a bare list of labels, so
     * the choice was made blind.
     */
    fun seasonOptions(club: String): List<SeasonOption> {
        val labels = _state.value.seasonLabels
        return pool
            .filter { it.clubName == club }
            .mapNotNull { row ->
                labels[row.seasonId]?.let {
                    SeasonOption(it, row.finalPosition, row.squadStrength)
                }
            }
            .sortedByDescending { it.label }
    }

    /**
     * Picks the club without loading anything: the squad arrives once a season
     * is chosen. The old picker loaded the first season the moment a club was
     * tapped, which fetched a squad the user had not asked for.
     */
    fun chooseClub(club: String) {
        _state.update {
            it.copy(selectedClub = club, selectedSeason = null, squad = emptyList())
        }
    }

    /**
     * The bench for a given eleven, or nothing until the eleven is complete.
     *
     * Shared by the magic fill and ordinary picking so the two cannot drift:
     * swapping a magic pick for someone else has to relock the substitutes by
     * exactly the rule that chose them in the first place.
     */
    private fun benchFor(
        picks: Map<String, DraftedPlayer>,
        formation: Formation,
    ): List<BenchPlayer> {
        if (picks.size != formation.slots.size) return emptyList()
        return BenchSelection.pick(
            visited = visited.filter { v ->
                picks.values.any { it.clubName == v.clubName && it.seasonLabel == v.seasonLabel }
            },
            xi = picks.values.toList(),
            formation = formation,
        )
    }

    /**
     * Shirts the user filled himself, which the next cast fills AROUND.
     *
     * Consumed by that cast and then cleared, which is the behaviour the user
     * asked for: pressing the button on a side that is already finished means
     * "give me a different eleven", and at that point every shirt is fair game
     * again — including one hand-picked before the first cast. Kept next to
     * castMagic and place, the only two places that touch it.
     */
    private val manualSlots = mutableSetOf<String>()

    /**
     * Fills the eleven with the best side the league can field, AROUND anything
     * the user has already chosen.
     *
     * Hand-picked shirts are handed to the solver as fixed, so the rest of the
     * side is chosen knowing they are there — see [MagicXi.build]. Pressing the
     * button again once a side is complete re-solves all eleven, because the
     * locks are released by the cast that used them.
     *
     * The shirts are revealed one at a time, back to front, but the choice was
     * made in one pass before the first one lands — see [MagicXi] for why
     * filling them in order genuinely picks a worse team.
     *
     * The squads behind those players are fetched AFTER the reveal, not before
     * it. They are needed only for the bench, nobody is waiting on them, and
     * loading eleven squads before showing anything would put a blank screen
     * where the animation should be.
     */
    fun castMagic() = viewModelScope.launch {
        val league = leagueId ?: return@launch
        val formation = _state.value.formation
        // With every shirt hand-picked there is nothing left to fill, so the
        // button re-solves the lot rather than appearing to do nothing.
        val kept = _state.value.picks
            .filterKeys { it in manualSlots }
            .takeIf { it.size < formation.slots.size }
            ?: emptyMap()
        manualSlots.clear()
        _state.update {
            it.copy(
                magic = MagicPhase.CONJURING,
                picks = kept,
                bench = emptyList(),
                openSlot = null,
                squad = emptyList(),
                error = null,
            )
        }
        delay(CONJURE_MS)

        val candidates = runCatching { repository.magicCandidates(league) }.getOrElse { e ->
            _state.update {
                it.copy(magic = MagicPhase.IDLE, error = e.toUserFacingError("work the magic"))
            }
            return@launch
        }
        magicPool = candidates
        val xi = MagicXi.build(
            candidates = candidates,
            formation = formation,
            keptSlotIds = kept.keys,
            keptPlayerIds = kept.values.map { it.player.playerId }.toSet(),
        )
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

        // Now the bench. Each club-season the eleven came from has to be loaded
        // before BenchSelection has anything to choose from — the user opened
        // none of them himself.
        coroutineScope {
            xi.map { it.candidate }
                .distinctBy { it.clubSeasonId }
                .map { async { ensureVisited(it) } }
                .awaitAll()
        }
        _state.update { s ->
            s.copy(magic = MagicPhase.DONE, bench = benchFor(s.picks, s.formation))
        }
    }

    /**
     * Swaps the man in this shirt for the next best available, and keeps going
     * round on every tap.
     *
     * "Available" excludes the other ten but NOT the man being replaced, so the
     * ladder wraps back to him rather than dead-ending — a tap is always a
     * change, and enough taps always get you back where you started.
     *
     * Only offered once the magic has been cast, because the ladder is drawn
     * from that cast's pool. A hand-built XI has no such pool and a tap there
     * opens the picker as it always did.
     */
    fun cycleSlot(slot: FormationSlot) = viewModelScope.launch {
        val s = _state.value
        if (!s.magic.isCastComplete) return@launch
        val current = s.picks[slot.id]
        val taken = s.picks
            .filterKeys { it != slot.id }
            .values.map { it.player.playerId }.toSet()
        val ladder = MagicXi.ladder(
            candidates = magicPool,
            slot = slot,
            exclude = taken,
            keep = magicOriginal[slot.id],
        )
        if (ladder.isEmpty()) return@launch
        // An empty shirt has no rung, so -1 + 1 lands on the best man — which
        // is what "the next best for this position" means for a hole.
        val here = ladder.indexOfFirst { it.candidate.player.playerId == current?.player?.playerId }
        val next = ladder[(here + 1) % ladder.size]
        if (next.candidate.player.playerId == current?.player?.playerId) return@launch

        // The shirt changes NOW; the bench catches up when his squad lands.
        // Waiting for the network here would put a pause on a gesture whose
        // whole appeal is that it answers instantly.
        _state.update { st ->
            val picks = st.picks + (slot.id to DraftedPlayer(
                slot = slot,
                player = next.candidate.player,
                clubName = next.candidate.clubName,
                seasonLabel = next.candidate.seasonLabel,
            ))
            st.copy(picks = picks, bench = benchFor(picks, st.formation), magic = MagicPhase.TWEAKED)
        }
        ensureVisited(next.candidate)
        _state.update { it.copy(bench = benchFor(it.picks, it.formation)) }
    }

    /**
     * Loads the squad behind a magic pick, so the bench has somewhere to draw
     * substitutes from. Nobody opened these clubs by hand.
     */
    private suspend fun ensureVisited(c: MagicCandidate) {
        if (visited.any { it.clubName == c.clubName && it.seasonLabel == c.seasonLabel }) return
        val spin = runCatching {
            repository.squadFor(
                clubSeasonId = c.clubSeasonId,
                clubName = c.clubName,
                seasonLabel = c.seasonLabel,
                finalPosition = null,
                squadStrength = null,
                strengthQuartile = null,
                alreadyDraftedPlayerIds = emptySet(),
                defence = null,
            )
        }.getOrNull() ?: return
        // Re-checked after the await: two picks from the same club-season can
        // be in flight at once.
        if (visited.none { it.clubName == spin.clubName && it.seasonLabel == spin.seasonLabel }) {
            visited += VisitedSquad(spin.clubName, spin.seasonLabel, spin.players)
        }
    }

    private fun loadSquad(club: String, season: String) = viewModelScope.launch {
        val entry = pool.firstOrNull {
            it.clubName == club && _state.value.seasonLabels[it.seasonId] == season
        } ?: return@launch

        _state.update { it.copy(isLoadingSquad = true, squad = emptyList()) }
        runCatching {
            repository.squadFor(
                clubSeasonId = entry.clubSeasonId,
                clubName = entry.clubName,
                seasonLabel = season,
                finalPosition = entry.finalPosition,
                squadStrength = entry.squadStrength,
                strengthQuartile = entry.strengthQuartile,
                // Identity is unique across the XI here exactly as in the draft.
                alreadyDraftedPlayerIds = _state.value.picks.values
                    .map { it.player.playerId }.toSet(),
                defence = null,
            )
        }.onSuccess { spin ->
            if (visited.none { v -> v.clubName == club && v.seasonLabel == season }) {
                visited += VisitedSquad(club, season, spin.players)
            }
            _state.update { it.copy(isLoadingSquad = false, squad = spin.players) }
        }.onFailure { e ->
            _state.update {
                it.copy(isLoadingSquad = false, error = e.toUserFacingError("load that squad"))
            }
        }
    }

    /**
     * Puts a player in the open position, replacing whoever was there.
     *
     * The shirt is remembered as hand-picked, so the next cast fills in around
     * it instead of overwriting it — see [manualSlots].
     */
    fun place(player: SquadPlayer) {
        val slotId = _state.value.openSlot?.id
        _state.update { s ->
            val slot = s.openSlot ?: return@update s
            if ((player.role == "GK") != slot.isGoalkeeper) return@update s
            // One real person, once. He may already be standing somewhere else.
            val cleared = s.picks.filterValues { it.player.playerId != player.playerId }
            val club = s.selectedClub ?: return@update s
            val season = s.selectedSeason ?: return@update s
            val picks = cleared + (slot.id to DraftedPlayer(slot, player, club, season))
            s.copy(
                picks = picks,
                bench = benchFor(picks, s.formation),
                openSlot = null,
                squad = emptyList(),
                // It stops being the league's BEST eleven the moment he changes
                // it, so the banner has to stop saying so. It does not stop
                // being a magic side: the gestures must not flip under the
                // user's thumb halfway through editing, and the ladder a tap
                // walks is still there.
                magic = if (s.magic == MagicPhase.IDLE) MagicPhase.IDLE else MagicPhase.TWEAKED,
            )
        }
        // Recorded OUTSIDE the update above, which may run more than once when
        // the state loses a race; and only once the player is really standing
        // there, since that block has its own reasons to refuse (a keeper in an
        // outfield shirt, no club selected).
        if (slotId != null && _state.value.picks[slotId]?.player?.playerId == player.playerId) {
            manualSlots += slotId
            // He can only be in one shirt. If he moved here from another, that
            // one is empty now and its lock would otherwise keep an empty shirt
            // reserved against the next cast.
            manualSlots.retainAll(_state.value.picks.keys)
        }
    }

    /** Empties a position, releasing any claim it had on the next cast. */
    fun clearSlot(slotId: String) {
        manualSlots -= slotId
        _state.update {
            val picks = it.picks - slotId
            it.copy(
                picks = picks,
                bench = benchFor(picks, it.formation),
                openSlot = null,
                magic = if (it.magic == MagicPhase.IDLE) MagicPhase.IDLE else MagicPhase.TWEAKED,
            )
        }
    }

    /** Hands the finished XI to the season. Returns false if it is not ready. */
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
                isFreeMode = true,
            ),
        )
        return true
    }

    fun dismissError() = _state.update { it.copy(error = null) }

}

/** One club in the picker: the stored name, and how many of its seasons exist. */
data class ClubOption(val name: String, val seasons: Int)

/** One season of one club, with the two facts a season is actually chosen on. */
data class SeasonOption(
    val label: String,
    val finalPosition: Int?,
    val squadStrength: Double?,
)

/** Everything the Free Mode screen renders. */
/** Where the magic fill has got to. Drives the overlay and the reveal. */
enum class MagicPhase {
    IDLE,

    /** "Working the magic" — the pool is being read and the side solved. */
    CONJURING,

    /** Shirts landing one at a time, back to front. */
    REVEALING,

    /** Eleven placed and substitutes locked; waiting for the user to confirm. */
    DONE,

    /**
     * A cast side the user has since changed. Behaves exactly like [DONE] — the
     * shirts still cycle — and exists only so the banner can stop claiming this
     * is the strongest eleven the league can field, which after a swap it
     * isn't.
     */
    TWEAKED,
    ;

    /** Whether a cast has finished, so the shirts are live to a tap. */
    val isCastComplete: Boolean get() = this == DONE || this == TWEAKED
}

/** How long the banner holds before the first shirt lands. */
private const val CONJURE_MS = 900L

/**
 * Between shirts.
 *
 * SLOWED FROM 150ms, which the user could not follow — eleven shirts landed in
 * a second and a half and the whole point of revealing them one at a time was
 * lost. The reveal is the reward for pressing the button; it is watched
 * deliberately, not endured, so it can afford to take its time.
 */
private const val REVEAL_STEP_MS = 300L

/**
 * An extra beat when the reveal moves from one line to the next.
 *
 * The order is keeper, then defence, then midfield, then attack, and that
 * structure is invisible if every shirt arrives on the same metronome. Pausing
 * at the seams is what makes it read as a team being built rather than a list
 * being filled.
 */
private const val REVEAL_LINE_PAUSE_MS = 250L

/** Which line of the team a shirt belongs to, for the pause above. */
private fun lineOf(role: String): Int = when (role) {
    "GK" -> 0
    "CB", "FB" -> 1
    "DM", "CM", "CAM" -> 2
    else -> 3
}

data class FreeModeUiState(
    val leagueName: String = "",
    val formation: Formation = com.dreamxi.app.feature.draft.DefaultFormation,
    val picks: Map<String, DraftedPlayer> = emptyMap(),
    val clubs: List<String> = emptyList(),
    val seasonLabels: Map<Long, String> = emptyMap(),
    val selectedClub: String? = null,
    val selectedSeason: String? = null,
    val squad: List<SquadPlayer> = emptyList(),
    val openSlot: FormationSlot? = null,
    val isLoading: Boolean = false,
    val isLoadingSquad: Boolean = false,
    /** Auto-filled once all eleven are placed. See BenchSelection. */
    val bench: List<BenchPlayer> = emptyList(),
    val magic: MagicPhase = MagicPhase.IDLE,
    val error: DreamXiError? = null,
) {
    val isComplete: Boolean get() = picks.size == formation.slots.size
    val filled: Int get() = picks.size
    val total: Int get() = formation.slots.size

    /** Effective-rating average, the number Free Mode exists to push upwards. */
    val xiRating: Int?
        get() = picks.values.map { it.effectiveRating }
            .takeIf { it.isNotEmpty() }
            ?.let { Math.round(it.average()).toInt() }
}
