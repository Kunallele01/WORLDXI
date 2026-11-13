package com.dreamxi.app.data.europeancup

import com.dreamxi.app.feature.draft.Formations
import com.dreamxi.app.sim.europeancup.EcEntry
import com.dreamxi.app.sim.europeancup.EcRealMatch
import com.dreamxi.app.sim.europeancup.EcStage
import com.dreamxi.app.sim.europeancup.EcTournamentData
import com.dreamxi.app.sim.worldcup.NationalSide
import com.dreamxi.app.sim.worldcup.WcPlayer
import com.dreamxi.app.sim.worldcup.WcSlot
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** PostgREST returns at most a thousand rows a request; anything that can pass that is paged. */
private const val PAGE = 1000L

private val PLAYER_COLUMNS = Columns.list(
    "id", "squad_id", "sofifa_id", "full_name", "nationality", "overall", "rating", "rating_edition",
    "positions", "birth_year",
    "grid_cb", "grid_fb", "grid_dm", "grid_cm", "grid_cam", "grid_winger", "grid_st",
)

/**
 * Reads for European Cup mode. All reference data, all public-read, all on the
 * anon key. Mirrors [com.dreamxi.app.data.worldcup.WorldCupRepository].
 */
@Singleton
class EuropeanCupRepository @Inject constructor(
    private val supabase: SupabaseClient,
) {

    /** The seasons that are loaded, oldest first. */
    suspend fun seasons(): List<Int> = withContext(Dispatchers.IO) {
        supabase.from("ec_seasons")
            .select(Columns.list("id", "season_year", "label", "format", "team_count"))
            .decodeList<EcSeasonDto>()
            .map { it.seasonYear }
            .sorted()
    }

    /**
     * One real season, ready to replay: every club with its strongest side
     * picked, and every real match.
     *
     * Entry ids become the engine's team ids ("e123"), and names travel beside
     * them, so two clubs can never be confused by a shared spelling.
     */
    suspend fun tournament(seasonYear: Int): LoadedEuropeanCup = withContext(Dispatchers.IO) {
        val season = supabase.from("ec_seasons")
            .select(Columns.list("id", "season_year", "label", "format", "team_count")) {
                filter { eq("season_year", seasonYear) }
            }
            .decodeList<EcSeasonDto>()
            .single()

        val entries = paged { from, to ->
            supabase.from("ec_entries")
                .select(
                    Columns.raw(
                        "id,season_id,club_id,squad_id,group_label,group_position,finished_bottom,reached_stage," +
                            "club:ec_clubs(name)," +
                            "squad:ec_squads(has_roster,team_strength,strength_source)",
                    ),
                ) {
                    filter { eq("season_id", season.id) }
                    order("id", Order.ASCENDING)
                    range(from, to)
                }
                .decodeList<EcEntryDto>()
        }

        val squadIds = entries.filter { it.squad.hasRoster }.map { it.squadId }
        val players = paged { from, to ->
            supabase.from("ec_squad_players")
                .select(PLAYER_COLUMNS) {
                    filter { isIn("squad_id", squadIds) }
                    order("id", Order.ASCENDING)
                    range(from, to)
                }
                .decodeList<EcSquadPlayerDto>()
        }.groupBy { it.squadId }

        val matches = paged { from, to ->
            supabase.from("ec_matches")
                .select(
                    Columns.list(
                        "id", "stage", "group_label", "leg", "match_date", "home_entry_id", "away_entry_id",
                        "home_goals", "away_goals", "extra_time", "home_pens", "away_pens", "venue",
                    ),
                ) {
                    filter { eq("season_id", season.id) }
                    order("id", Order.ASCENDING)
                    range(from, to)
                }
                .decodeList<EcMatchDto>()
        }

        // Picking thirty best elevens over nineteen formations is CPU work, not
        // I/O, so it leaves the network pool.
        val formations = Formations.map { f -> f.slots.map { WcSlot(it.role, it.side, it.isWingBack) } }
        val built = withContext(Dispatchers.Default) {
            entries.map { e ->
                val squad = players[e.squadId].orEmpty().map { it.toPlayer() }
                EcEntry(
                    id = teamId(e.id),
                    name = e.club.name,
                    group = e.groupLabel,
                    realPosition = e.groupPosition,
                    finishedBottom = e.finishedBottom,
                    strength = e.squad.teamStrength,
                    strengthEstimated = e.squad.estimated,
                    reachedStage = if (e.reachedStage == "winner") EcStage.FINAL else EcStage.fromCode(e.reachedStage),
                    champion = e.reachedStage == "winner",
                    lineup = if (e.squad.hasRoster) NationalSide.bestLineup(squad, formations) else null,
                )
            }
        }

        val data = EcTournamentData(
            seasonYear = seasonYear,
            entries = built,
            matches = matches.map { m ->
                EcRealMatch(
                    stage = EcStage.fromCode(m.stage),
                    group = m.groupLabel,
                    leg = m.leg,
                    date = m.matchDate.orEmpty(),
                    homeId = teamId(m.homeEntryId),
                    awayId = teamId(m.awayEntryId),
                    homeGoals = m.homeGoals,
                    awayGoals = m.awayGoals,
                    extraTime = m.extraTime,
                    homePens = m.homePens,
                    awayPens = m.awayPens,
                    venue = m.venue,
                )
            },
        )
        LoadedEuropeanCup(
            data = data,
            names = built.associate { it.id to it.name },
            seasonId = season.id,
            label = season.label,
        )
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

    private fun EcSquadPlayerDto.toPlayer() = WcPlayer(
        id = sofifaId ?: "ec$id",
        name = fullName,
        rating = rating,
        positions = positions,
        grid = grid,
        gridEdition = if (grid != null) ratingEdition else null,
    )

    companion object {
        fun teamId(entryId: Long): String = "e$entryId"
    }
}

/** A season as loaded, with display names for every engine id. */
data class LoadedEuropeanCup(
    val data: EcTournamentData,
    val names: Map<String, String>,
    /** The row this season came from, so a finished run can be filed against it. */
    val seasonId: Long,
    /** "2023/24". */
    val label: String,
)
