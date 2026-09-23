package com.dreamxi.app.data.worldcup

import com.dreamxi.app.feature.draft.Formations
import com.dreamxi.app.sim.worldcup.GoalKind
import com.dreamxi.app.sim.worldcup.NationalSide
import com.dreamxi.app.sim.worldcup.WcEntry
import com.dreamxi.app.sim.worldcup.WcGoal
import com.dreamxi.app.sim.worldcup.WcPlayer
import com.dreamxi.app.sim.worldcup.WcRealMatch
import com.dreamxi.app.sim.worldcup.WcSlot
import com.dreamxi.app.sim.worldcup.WcTournamentData
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PostgREST hands back at most a thousand rows per request. A 2026 tournament's
 * squads alone are about 1,250 players, so anything that can pass that is read
 * a page at a time rather than trusted to arrive whole.
 */
private const val PAGE = 1000L

private val PLAYER_COLUMNS = Columns.list(
    "id", "squad_id", "sofifa_id", "full_name", "overall", "rating", "positions", "club",
    "grid_cb", "grid_fb", "grid_dm", "grid_cm", "grid_cam", "grid_winger", "grid_st", "grid_edition",
)

/** One nation at one World Cup, as the draft spins it. */
data class WcPoolEntry(
    val entryId: Long,
    val squadId: Long,
    val nation: String,
    val year: Int,
    val groupLabel: String?,
    val groupPosition: Int?,
    /** The FIFA edition the tournament is rated on, e.g. "07". */
    val anchorEdition: String,
    /** The edition this squad actually came from — differs when it was borrowed. */
    val squadEdition: String,
)

/** A player in a spun squad, with both the draft's view of him and the engine's. */
data class WcDraftPlayer(
    val squadPlayerId: Long,
    /** sofifa's id, which is the same PERSON across every edition. */
    val personId: String?,
    val club: String?,
    val player: WcPlayer,
)

/**
 * Reads for World Cup mode. All reference data, all public-read, all on the
 * anon key.
 */
@Singleton
class WorldCupRepository @Inject constructor(
    private val supabase: SupabaseClient,
) {

    /** Every nation-entry that has a squad: the pool the draft spins from. */
    suspend fun draftPool(): List<WcPoolEntry> = withContext(Dispatchers.IO) {
        pagedEntries(tournamentId = null).mapNotNull { e ->
            val squadId = e.squadId ?: return@mapNotNull null
            val tournament = e.tournament ?: return@mapNotNull null
            WcPoolEntry(
                entryId = e.id,
                squadId = squadId,
                nation = e.fixtureName,
                year = tournament.year,
                groupLabel = e.groupLabel,
                groupPosition = e.groupPosition,
                anchorEdition = tournament.ratingEdition,
                squadEdition = e.squad?.ratingEdition ?: tournament.ratingEdition,
            )
        }
    }

    /** One squad, best first. */
    suspend fun squad(squadId: Long): List<WcDraftPlayer> = withContext(Dispatchers.IO) {
        supabase.from("wc_squad_players")
            .select(PLAYER_COLUMNS) {
                filter { eq("squad_id", squadId) }
            }
            .decodeList<WcSquadPlayerDto>()
            .sortedByDescending { it.rating }
            .map { it.toDraftPlayer() }
    }

    /**
     * Every squad player at every World Cup, by squad id: the magic XI's pool.
     * About 4,800 rows, so paged, and fetched once per Free Mode run.
     */
    suspend fun allSquadPlayers(): Map<Long, List<WcDraftPlayer>> = withContext(Dispatchers.IO) {
        paged { from, to ->
            supabase.from("wc_squad_players")
                .select(PLAYER_COLUMNS) {
                    order("id", Order.ASCENDING)
                    range(from, to)
                }
                .decodeList<WcSquadPlayerDto>()
        }.groupBy({ it.squadId }) { it.toDraftPlayer() }
    }

    /** The editions that exist, oldest first. */
    suspend fun years(): List<Int> = withContext(Dispatchers.IO) {
        supabase.from("wc_tournaments")
            .select(Columns.list("id", "year", "team_count", "rating_edition"))
            .decodeList<WcTournamentRefDto>()
            .map { it.year }
            .sorted()
    }

    /**
     * One real World Cup, ready to replay: every nation with its strongest
     * side picked, every real match and every real goal.
     *
     * Entry ids become the engine's team ids ("e123"), and names travel beside
     * them, so two nations can never be confused by a shared spelling.
     */
    suspend fun tournament(year: Int): LoadedWorldCup = withContext(Dispatchers.IO) {
        val tournament = supabase.from("wc_tournaments")
            .select(Columns.list("id", "year", "team_count", "rating_edition")) {
                filter { eq("year", year) }
            }
            .decodeList<WcTournamentRefDto>()
            .single()

        val entries = pagedEntries(tournamentId = tournament.id)
        val squadIds = entries.mapNotNull { it.squadId }.distinct()
        val players = paged { from, to ->
            supabase.from("wc_squad_players")
                .select(PLAYER_COLUMNS) {
                    filter { isIn("squad_id", squadIds) }
                    order("id", Order.ASCENDING)
                    range(from, to)
                }
                .decodeList<WcSquadPlayerDto>()
        }.groupBy { it.squadId }

        val matches = supabase.from("wc_matches")
            .select(
                Columns.list(
                    "id", "round", "match_date", "home_entry_id", "away_entry_id", "home_goals",
                    "away_goals", "home_pens", "away_pens", "shootout_winner_entry_id",
                ),
            ) {
                filter { eq("tournament_id", tournament.id) }
            }
            .decodeList<WcMatchDto>()

        val goals = paged { from, to ->
            supabase.from("wc_match_goals")
                .select(Columns.list("match_id", "entry_id", "scorer", "minute", "stoppage", "kind")) {
                    filter { isIn("match_id", matches.map { it.id }) }
                    order("id", Order.ASCENDING)
                    range(from, to)
                }
                .decodeList<WcGoalDto>()
        }.groupBy { it.matchId }

        // Picking 32-48 best elevens over nineteen formations is CPU work, not
        // I/O, so it leaves the network pool.
        val formations = Formations.map { f -> f.slots.map { WcSlot(it.role, it.side, it.isWingBack) } }
        val built = withContext(Dispatchers.Default) {
            entries.map { e ->
                val squad = e.squadId?.let { players[it] }.orEmpty().map { it.toDraftPlayer().player }
                WcEntry(
                    id = teamId(e.id),
                    name = e.fixtureName,
                    group = e.groupLabel.orEmpty(),
                    realPosition = e.groupPosition ?: 0,
                    finishedBottom = e.finishedBottom,
                    isHost = e.isHost,
                    lineup = NationalSide.bestLineup(squad, formations),
                )
            }
        }

        val data = WcTournamentData(
            year = year,
            entries = built,
            matches = matches.map { m ->
                WcRealMatch(
                    round = m.round,
                    date = m.matchDate.orEmpty(),
                    homeId = teamId(m.homeEntryId),
                    awayId = teamId(m.awayEntryId),
                    homeGoals = m.homeGoals,
                    awayGoals = m.awayGoals,
                    homePens = m.homePens,
                    awayPens = m.awayPens,
                    shootoutWinnerId = m.shootoutWinnerEntryId?.let(::teamId),
                    goals = goals[m.id].orEmpty().map { g ->
                        WcGoal(
                            teamId = teamId(g.entryId),
                            scorer = g.scorer,
                            minute = g.minute,
                            stoppage = g.stoppage,
                            kind = when (g.kind) {
                                "penalty" -> GoalKind.PENALTY
                                "own_goal" -> GoalKind.OWN_GOAL
                                else -> GoalKind.GOAL
                            },
                        )
                    },
                )
            },
        )
        LoadedWorldCup(data = data, names = built.associate { it.id to it.name })
    }

    private suspend fun pagedEntries(tournamentId: Long?): List<WcEntryDto> = paged { from, to ->
        supabase.from("wc_nation_entries")
            .select(
                Columns.raw(
                    "id,tournament_id,squad_id,fixture_name,group_label,group_position,finished_bottom,is_host," +
                        "tournament:wc_tournaments(id,year,team_count,rating_edition)," +
                        "squad:wc_squads(rating_edition)",
                ),
            ) {
                filter { if (tournamentId != null) eq("tournament_id", tournamentId) }
                order("id", Order.ASCENDING)
                range(from, to)
            }
            .decodeList<WcEntryDto>()
    }

    private suspend fun <T> paged(page: suspend (Long, Long) -> List<T>): List<T> {
        val out = mutableListOf<T>()
        var from = 0L
        while (true) {
            val rows = page(from, from + PAGE - 1)
            out += rows
            if (rows.size < PAGE) return out
            from += PAGE
        }
    }

    private fun WcSquadPlayerDto.toDraftPlayer() = WcDraftPlayer(
        squadPlayerId = id,
        personId = sofifaId,
        club = club,
        player = WcPlayer(
            id = sofifaId ?: "wc$id",
            name = fullName,
            rating = rating,
            positions = positions,
            grid = grid,
            gridEdition = gridEdition,
        ),
    )

    companion object {
        fun teamId(entryId: Long): String = "e$entryId"
    }
}

/** A tournament as loaded, with display names for every engine id. */
data class LoadedWorldCup(
    val data: WcTournamentData,
    val names: Map<String, String>,
)

/** A finished World Cup draft, waiting to be played. */
data class CompletedWcDraft(
    val formation: com.dreamxi.app.feature.draft.Formation,
    val picks: List<com.dreamxi.app.feature.draft.DraftedPlayer>,
    /** The engine's view of each pick, in formation order, with the slot (role and flank) he stands in. */
    val placed: List<Pair<WcSlot, WcPlayer>>,
    /**
     * Fixed when the draft completes, so the edition, the takeover draw and every
     * result survive a rotation or a trip to the background.
     */
    val seed: Long,
    /** Built in World Cup Free Mode rather than drafted; labelled as such, as club Free Mode runs are. */
    val isFreeMode: Boolean = false,
)

/**
 * Hands a finished World Cup XI from the draft to the tournament. In memory
 * only, for exactly the club mode's reason — see ActiveRun.
 */
@Singleton
class WorldCupRun @Inject constructor() {
    @Volatile
    var draft: CompletedWcDraft? = null
        private set

    fun start(draft: CompletedWcDraft) {
        this.draft = draft
    }

    fun clear() {
        draft = null
    }
}
