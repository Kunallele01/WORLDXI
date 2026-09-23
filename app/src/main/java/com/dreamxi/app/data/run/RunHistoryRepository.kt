package com.dreamxi.app.data.run

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DreamXI"

/** A run as the history list shows it. */
data class PastRun(
    val id: Long,
    val kind: Kind,
    val title: String,
    /** "3rd · 74 pts", or "Out in the quarter-finals". */
    val outcome: String,
    val isFreeMode: Boolean,
    val createdAt: String,
    /** Structured, so a record can be counted rather than parsed back out of prose. */
    val position: Int? = null,
    val points: Int? = null,
    val wonIt: Boolean = false,
) {
    enum class Kind { LEAGUE, WORLD_CUP }
}

/**
 * What a player has to show for every run he has finished.
 *
 * Counted from the runs themselves rather than stored, so it can never drift
 * from the list underneath it — and so a run deleted or added needs nothing
 * kept in step.
 */
data class PlayerRecord(
    val runs: Int,
    val leagueTitles: Int,
    val worldCups: Int,
    val bestFinish: Int?,
    val mostPoints: Int?,
) {
    companion object {
        fun from(runs: List<PastRun>): PlayerRecord = PlayerRecord(
            runs = runs.size,
            leagueTitles = runs.count { it.kind == PastRun.Kind.LEAGUE && it.wonIt },
            worldCups = runs.count { it.kind == PastRun.Kind.WORLD_CUP && it.wonIt },
            bestFinish = runs.filter { it.kind == PastRun.Kind.LEAGUE }.mapNotNull { it.position }.minOrNull(),
            mostPoints = runs.mapNotNull { it.points }.maxOrNull(),
        )
    }
}

/** What a finished league season is worth recording. */
data class LeagueRunRecord(
    val leagueId: Long?,
    val seasonScope: String,
    val formation: String,
    val replacedClubSeasonId: Long?,
    val seed: Long,
    val isFreeMode: Boolean,
    val simulatedSeasonId: Long,
    val finalPosition: Int?,
    val points: Int?,
    val wins: Int?,
    val draws: Int?,
    val losses: Int?,
    val goalsFor: Int?,
    val goalsAgainst: Int?,
    /** slot id to player_season_stats id, in the order they were picked. */
    val picks: List<Pair<String, Long>>,
    /** Substitutes, which have no slot but do decide who scores. */
    val bench: List<Long>,
)

/** What a finished World Cup is worth recording. */
data class WorldCupRunRecord(
    val tournamentId: Long,
    val replacedEntryId: Long?,
    val formation: String,
    val seed: Long,
    val reachedRound: String?,
    val champion: Boolean,
    val played: Int?,
    val won: Int?,
    val drawn: Int?,
    val lost: Int?,
    val goalsFor: Int?,
    val goalsAgainst: Int?,
    val picks: List<Pair<String, Long>>,
)

@Serializable
private data class DraftRunInsert(
    @SerialName("user_id") val userId: String,
    @SerialName("league_id") val leagueId: Long?,
    @SerialName("season_scope") val seasonScope: String,
    val formation: String,
    @SerialName("replaced_club_season_id") val replacedClubSeasonId: Long?,
    val seed: Long,
    @SerialName("is_free_mode") val isFreeMode: Boolean,
)

@Serializable
private data class DraftPickInsert(
    @SerialName("run_id") val runId: Long,
    @SerialName("slot_position") val slotPosition: String?,
    @SerialName("player_season_stat_id") val playerSeasonStatId: Long,
    @SerialName("round_number") val roundNumber: Int,
    @SerialName("is_bench") val isBench: Boolean,
)

@Serializable
private data class SimulationResultInsert(
    @SerialName("run_id") val runId: Long,
    @SerialName("simulated_season_id") val simulatedSeasonId: Long,
    @SerialName("final_position") val finalPosition: Int?,
    val points: Int?,
    val wins: Int?,
    val draws: Int?,
    val losses: Int?,
    val gf: Int?,
    val ga: Int?,
)

@Serializable
private data class WcRunInsert(
    @SerialName("user_id") val userId: String,
    @SerialName("tournament_id") val tournamentId: Long,
    @SerialName("replaced_entry_id") val replacedEntryId: Long?,
    val formation: String,
    val seed: Long,
)

@Serializable
private data class WcPickInsert(
    @SerialName("run_id") val runId: Long,
    @SerialName("slot_position") val slotPosition: String,
    @SerialName("squad_player_id") val squadPlayerId: Long,
    @SerialName("round_number") val roundNumber: Int,
)

@Serializable
private data class WcResultInsert(
    @SerialName("run_id") val runId: Long,
    @SerialName("reached_round") val reachedRound: String?,
    val champion: Boolean,
    val played: Int?,
    val won: Int?,
    val drawn: Int?,
    val lost: Int?,
    @SerialName("goals_for") val goalsFor: Int?,
    @SerialName("goals_against") val goalsAgainst: Int?,
)

@Serializable
private data class IdRow(val id: Long)

@Serializable
private data class DraftRunRow(
    val id: Long,
    @SerialName("season_scope") val seasonScope: String,
    @SerialName("is_free_mode") val isFreeMode: Boolean = false,
    @SerialName("created_at") val createdAt: String,
    val leagues: LeagueName? = null,
    @SerialName("simulation_results") val results: List<SimulationResultRow> = emptyList(),
) {
    @Serializable
    data class LeagueName(val name: String)

    @Serializable
    data class SimulationResultRow(
        @SerialName("final_position") val finalPosition: Int? = null,
        val points: Int? = null,
    )
}

@Serializable
private data class WcRunRow(
    val id: Long,
    @SerialName("created_at") val createdAt: String,
    val tournament: Tournament? = null,
    @SerialName("wc_results") val results: List<ResultRow> = emptyList(),
) {
    @Serializable
    data class Tournament(val year: Int)

    @Serializable
    data class ResultRow(
        @SerialName("reached_round") val reachedRound: String? = null,
        val champion: Boolean = false,
    )
}

/**
 * Saves finished runs, and reads them back for the history screen.
 *
 * WHY A RUN IS ONLY SAVED WHEN IT IS OVER. The tables record a result, and a
 * season in progress has none. Saving one mid-flight would need the whole
 * simulated season — every scoreline, the takeover draw, the visible stage —
 * to round-trip exactly, because a run reloaded to a different table is worse
 * than one that was lost. That is the next piece of work, not this one.
 *
 * The seed is stored with every run because it is what makes the result
 * reproducible: the season the XI was dropped into, which club it replaced and
 * every scoreline all follow from it.
 *
 * NOTHING HERE IS ALLOWED TO BREAK A RUN. Every write is best-effort: a player
 * who finishes a season offline still sees his table, and simply has no row to
 * show for it later. Losing the save is a disappointment; losing the season to
 * a crash on the final whistle is a bug.
 */
@Singleton
class RunHistoryRepository @Inject constructor(
    private val client: SupabaseClient,
) {

    private fun userId(): String? = client.auth.currentUserOrNull()?.id

    /** @return the new run's id, or null if it could not be saved. */
    suspend fun saveLeagueRun(record: LeagueRunRecord): Long? = withContext(Dispatchers.IO) {
        val user = userId() ?: run {
            Log.w(TAG, "no session — a finished season went unsaved")
            return@withContext null
        }
        runCatching {
            val run = client.from("draft_runs").insert(
                DraftRunInsert(
                    userId = user,
                    leagueId = record.leagueId,
                    seasonScope = record.seasonScope,
                    formation = record.formation,
                    replacedClubSeasonId = record.replacedClubSeasonId,
                    seed = record.seed,
                    isFreeMode = record.isFreeMode,
                ),
            ) { select() }.decodeSingle<IdRow>()

            val picks = record.picks.mapIndexed { index, (slot, playerId) ->
                DraftPickInsert(run.id, slot, playerId, index + 1, isBench = false)
            } + record.bench.mapIndexed { index, playerId ->
                // A substitute has no position on the pitch. His round number
                // carries on from the XI so the order he was added in survives.
                DraftPickInsert(run.id, null, playerId, record.picks.size + index + 1, isBench = true)
            }
            if (picks.isNotEmpty()) client.from("draft_picks").insert(picks)

            client.from("simulation_results").insert(
                SimulationResultInsert(
                    runId = run.id,
                    simulatedSeasonId = record.simulatedSeasonId,
                    finalPosition = record.finalPosition,
                    points = record.points,
                    wins = record.wins,
                    draws = record.draws,
                    losses = record.losses,
                    gf = record.goalsFor,
                    ga = record.goalsAgainst,
                ),
            )
            run.id
        }.onFailure { Log.w(TAG, "could not save the season", it) }.getOrNull()
    }

    /** @return the new run's id, or null if it could not be saved. */
    suspend fun saveWorldCupRun(record: WorldCupRunRecord): Long? = withContext(Dispatchers.IO) {
        val user = userId() ?: run {
            Log.w(TAG, "no session — a finished World Cup went unsaved")
            return@withContext null
        }
        runCatching {
            val run = client.from("wc_runs").insert(
                WcRunInsert(
                    userId = user,
                    tournamentId = record.tournamentId,
                    replacedEntryId = record.replacedEntryId,
                    formation = record.formation,
                    seed = record.seed,
                ),
            ) { select() }.decodeSingle<IdRow>()

            val picks = record.picks.mapIndexed { index, (slot, squadPlayerId) ->
                WcPickInsert(run.id, slot, squadPlayerId, index + 1)
            }
            if (picks.isNotEmpty()) client.from("wc_picks").insert(picks)

            client.from("wc_results").insert(
                WcResultInsert(
                    runId = run.id,
                    reachedRound = record.reachedRound,
                    champion = record.champion,
                    played = record.played,
                    won = record.won,
                    drawn = record.drawn,
                    lost = record.lost,
                    goalsFor = record.goalsFor,
                    goalsAgainst = record.goalsAgainst,
                ),
            )
            run.id
        }.onFailure { Log.w(TAG, "could not save the World Cup run", it) }.getOrNull()
    }

    /**
     * Every run this player has finished, newest first.
     *
     * Two queries rather than one, because league runs and World Cup runs are
     * different tables with different shapes; they are merged and sorted here.
     * RLS does the filtering — there is no user_id in these queries, and there
     * must not be: the database already refuses to return anyone else's rows,
     * and a client-side filter would only be a second place for that rule to
     * be got wrong.
     */
    suspend fun history(limit: Int = 50): List<PastRun> = withContext(Dispatchers.IO) {
        if (userId() == null) return@withContext emptyList()
        val league = runCatching {
            client.from("draft_runs").select(
                Columns.raw("id, season_scope, is_free_mode, created_at, leagues(name), simulation_results(final_position, points)"),
            ) {
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }.decodeList<DraftRunRow>()
        }.onFailure { Log.w(TAG, "could not read league history", it) }.getOrDefault(emptyList())

        val worldCup = runCatching {
            client.from("wc_runs").select(
                Columns.raw("id, created_at, tournament:wc_tournaments(year), wc_results(reached_round, champion)"),
            ) {
                order("created_at", Order.DESCENDING)
                limit(limit.toLong())
            }.decodeList<WcRunRow>()
        }.onFailure { Log.w(TAG, "could not read World Cup history", it) }.getOrDefault(emptyList())

        (league.map { it.toPastRun() } + worldCup.map { it.toPastRun() })
            .sortedByDescending { it.createdAt }
            .take(limit)
    }

    private fun DraftRunRow.toPastRun(): PastRun {
        val result = results.firstOrNull()
        return PastRun(
            id = id,
            kind = PastRun.Kind.LEAGUE,
            title = listOfNotNull(leagues?.name, seasonScope.takeIf { it != "random" })
                .joinToString(" · ")
                .ifBlank { "A season" },
            outcome = when {
                result?.finalPosition == null -> "Unfinished"
                else -> "${result.finalPosition.ordinal()} · ${result.points ?: 0} pts"
            },
            isFreeMode = isFreeMode,
            createdAt = createdAt,
            position = result?.finalPosition,
            points = result?.points,
            wonIt = result?.finalPosition == 1,
        )
    }

    private fun WcRunRow.toPastRun(): PastRun = PastRun(
        id = id,
        kind = PastRun.Kind.WORLD_CUP,
        title = tournament?.year?.let { "$it World Cup" } ?: "World Cup",
        outcome = results.firstOrNull()?.let {
            if (it.champion) "World champions" else it.reachedRound?.let { r -> "Out in the $r" } ?: "Unfinished"
        } ?: "Unfinished",
        // Free Mode is not recorded separately for a World Cup run: the mode is
        // chosen per tournament and the screens label it from the run itself.
        isFreeMode = false,
        createdAt = createdAt,
        wonIt = results.firstOrNull()?.champion == true,
    )
}

private fun Int.ordinal(): String {
    val suffix = when {
        this % 100 in 11..13 -> "th"
        this % 10 == 1 -> "st"
        this % 10 == 2 -> "nd"
        this % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$this$suffix"
}
