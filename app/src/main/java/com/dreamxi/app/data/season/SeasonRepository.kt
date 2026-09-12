package com.dreamxi.app.data.season

import com.dreamxi.app.sim.RealFixture
import com.dreamxi.app.sim.ScorerWeight
import com.dreamxi.app.sim.SimModel
import com.dreamxi.app.sim.SimPlayer
import com.dreamxi.app.sim.SimSlot
import com.dreamxi.app.sim.SimTeam
import com.dreamxi.app.sim.SquadProfile
import com.dreamxi.app.sim.TeamStrength
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The same 450-minute floor the draft and the ETL use. An opponent XI must be
 * the players who actually played that season, not whoever appeared once.
 */
private const val MIN_MINUTES = 450

/** A season the user's XI can be dropped into. */
data class SeasonOption(
    val seasonId: Long,
    val label: String,
    val leagueId: Long,
    val clubCount: Int,
)

@Serializable
internal data class SeasonRowDto(
    val id: Long,
    val label: String,
    @SerialName("league_id") val leagueId: Long,
)

@Serializable
internal data class ClubSeasonRowDto(
    val id: Long,
    @SerialName("club_name") val clubName: String,
    @SerialName("season_id") val seasonId: Long,
    @SerialName("final_position") val finalPosition: Int? = null,
)

/**
 * A real league season, loaded and rated.
 *
 * [finalPositions] is where each club ACTUALLY finished that year, which is
 * how the relegation places are identified. It is not the simulator's output
 * and never becomes it — the whole point is that the user's XI takes the place
 * of a club that really went down.
 */
data class LoadedSeason(
    val seasonId: Long,
    val label: String,
    val teams: List<SimTeam>,
    val finalPositions: Map<String, Int>,
    /**
     * Every match that really happened that season. The counterfactual is
     * built on these: the user's XI inherits the replaced club's 38 and the
     * other 342 keep their real scorelines.
     */
    val fixtures: List<RealFixture> = emptyList(),
) {
    /**
     * The clubs that really finished in the relegation places, best-placed
     * first. Normally 18th, 19th and 20th.
     *
     * Taken from the real final table rather than from the bottom of whatever
     * loaded, so a season missing a club cannot promote a mid-table side into
     * the drop zone. If fewer than three of them loaded a usable XI, the draw
     * runs between however many did.
     */
    val relegatedTeamIds: List<String>
        get() = finalPositions.entries
            .sortedByDescending { it.value }
            .take(3)
            .sortedBy { it.value }
            .map { it.key }
}

@Serializable
internal data class SimPlayerRefDto(
    @SerialName("full_name") val fullName: String? = null,
)

@Serializable
internal data class FixtureDto(
    val matchday: Int? = null,
    @SerialName("home_club_season_id") val homeClubSeasonId: Long,
    @SerialName("away_club_season_id") val awayClubSeasonId: Long,
    @SerialName("home_goals") val homeGoals: Int,
    @SerialName("away_goals") val awayGoals: Int,
)

@Serializable
internal data class SimStatDto(
    @SerialName("club_season_id") val clubSeasonId: Long,
    @SerialName("primary_position") val primaryPosition: String? = null,
    val minutes: Int? = null,
    @SerialName("overall_rating") val overallRating: Int? = null,
    val npxg: Double? = null,
    @SerialName("save_pct") val savePct: Double? = null,
    val goals: Int? = null,
    val assists: Int? = null,
    @SerialName("yellow_cards") val yellowCards: Int? = null,
    @SerialName("red_cards") val redCards: Int? = null,
    val players: SimPlayerRefDto? = null,
)

/**
 * Loads real club-seasons as opposition for the simulator.
 *
 * A club's XI here is the eleven who played the most minutes, each in his own
 * listed position — which is exactly the fixture the engine was validated
 * against (r = 0.916 against real league tables). It is deliberately NOT the
 * eleven best-rated players: the point of the opposition is to behave like the
 * team that really played, and the team that really played was picked by a
 * manager dealing with injuries and rotation, not by rating.
 */
@Singleton
class SeasonRepository @Inject constructor(
    private val supabase: SupabaseClient,
) {

    /** Seasons available to simulate, with how many clubs each actually loaded. */
    suspend fun seasonOptions(leagueId: Long): List<SeasonOption> = withContext(Dispatchers.IO) {
        val seasons = supabase.from("seasons")
            .select(Columns.list("id", "label", "league_id")) {
                filter { eq("league_id", leagueId) }
            }
            .decodeList<SeasonRowDto>()
        if (seasons.isEmpty()) return@withContext emptyList()

        val counts = supabase.from("club_seasons")
            .select(Columns.list("id", "club_name", "season_id", "final_position")) {
                filter { isIn("season_id", seasons.map { it.id }) }
            }
            .decodeList<ClubSeasonRowDto>()
            .groupingBy { it.seasonId }
            .eachCount()

        seasons
            .map { SeasonOption(it.id, it.label, it.leagueId, counts[it.id] ?: 0) }
            .sortedBy { it.label }
    }

    /**
     * Every club in a season, rated and ready to play.
     *
     * Clubs that cannot field eleven qualifying players are dropped rather
     * than padded. A half-loaded club would be rated off six men and would
     * hand out free points all season, which is worse for the user than a
     * slightly shorter league.
     */
    suspend fun loadSeason(seasonId: Long): LoadedSeason = withContext(Dispatchers.IO) {
        val clubs = supabase.from("club_seasons")
            .select(Columns.list("id", "club_name", "season_id", "final_position")) {
                filter { eq("season_id", seasonId) }
            }
            .decodeList<ClubSeasonRowDto>()

        val label = supabase.from("seasons")
            .select(Columns.list("id", "label", "league_id")) {
                filter { eq("id", seasonId) }
            }
            .decodeList<SeasonRowDto>()
            .firstOrNull()?.label.orEmpty()

        if (clubs.isEmpty()) return@withContext LoadedSeason(seasonId, label, emptyList(), emptyMap())

        val stats = supabase.from("player_season_stats")
            .select(
                Columns.raw(
                    "club_season_id,primary_position,minutes,overall_rating,npxg,save_pct," +
                        "goals,assists,yellow_cards,red_cards,players(full_name)",
                ),
            ) {
                filter { isIn("club_season_id", clubs.map { it.id }) }
            }
            .decodeList<SimStatDto>()
            .groupBy { it.clubSeasonId }

        val teams = clubs.mapNotNull { club ->
            val squadRows = stats[club.id].orEmpty()
            // The XI that decides how good the club is: the eleven who played
            // most, above the same minutes floor the draft and the ETL use.
            val xi = squadRows
                .filter {
                    it.primaryPosition != null && it.overallRating != null &&
                        (it.minutes ?: 0) >= MIN_MINUTES
                }
                .sortedByDescending { it.minutes ?: 0 }
                .take(11)
                .map { it.toSlot() }
            if (xi.size < 11) return@mapNotNull null

            SimTeam(
                id = teamId(club.id),
                name = club.clubName,
                season = label,
                rating = TeamStrength.rate(xi),
                xi = xi,
                // But the WHOLE squad decides who scores — including everyone
                // below the minutes floor, who between them account for 31% of
                // a real club's goals.
                squad = SquadProfile.fromSeasonTotals(
                    squadRows
                        .filter { (it.minutes ?: 0) > 0 }
                        .map { r ->
                            ScorerWeight(
                                name = r.players?.fullName.orEmpty().ifBlank { "Unknown" },
                                // A squad player who featured for a third of a
                                // season cannot be booked in the tenth minute of
                                // every match he appears in.
                                startRate = SimModel.startRate(
                                    (r.minutes ?: 0) / SimModel.FULL_SEASON_MINUTES,
                                ),
                                goals = (r.goals ?: 0).toDouble(),
                                assists = (r.assists ?: 0).toDouble(),
                                yellows = (r.yellowCards ?: 0).toDouble(),
                                reds = (r.redCards ?: 0).toDouble(),
                            )
                        },
                ),
            )
        }

        val loadedIds = teams.map { it.id }.toSet()

        val fixtures = supabase.from("fixtures")
            .select(
                Columns.list(
                    "matchday", "home_club_season_id", "away_club_season_id",
                    "home_goals", "away_goals",
                ),
            ) {
                filter { isIn("home_club_season_id", clubs.map { it.id }) }
            }
            .decodeList<FixtureDto>()
            .mapNotNull { f ->
                val home = teamId(f.homeClubSeasonId)
                val away = teamId(f.awayClubSeasonId)
                // A fixture involving a club that could not field a rateable XI
                // is dropped along with the club, so the table stays consistent.
                if (home !in loadedIds || away !in loadedIds) return@mapNotNull null
                RealFixture(f.matchday ?: 0, home, away, f.homeGoals, f.awayGoals)
            }

        LoadedSeason(
            seasonId = seasonId,
            label = label,
            teams = teams,
            finalPositions = clubs
                .filter { it.finalPosition != null && teamId(it.id) in loadedIds }
                .associate { teamId(it.id) to it.finalPosition!! },
            fixtures = fixtures,
        )
    }
}

/** Stable simulator id for a club-season row. */
fun teamId(clubSeasonId: Long): String = "club-$clubSeasonId"

/**
 * A real player in his real position, so no out-of-position cost applies.
 *
 * A missing npxG is filled from his position's measured rate rather than
 * treated as zero. Roughly 5% of regulars are missing one — the mid-season
 * transfers Understat could not be matched to — and scoring them at zero would
 * quietly gut whichever clubs happened to do the most business.
 */
private fun SimStatDto.toSlot(): SimSlot {
    val role = primaryPosition!!
    val rating = overallRating!!
    val mins = minutes ?: 0
    val rate = npxg?.takeIf { mins > 0 }?.let { it * 90.0 / mins }
        ?: TeamStrength.positionalYield(role, rating)
    fun per90(v: Int?): Double = if (mins > 0) (v ?: 0) * 90.0 / mins else 0.0
    return SimSlot(
        role = role,
        player = SimPlayer(
            // Real names, because a match report reading "scored by" and then
            // nothing is worse than no match report at all.
            name = players?.fullName.orEmpty().ifBlank { "Unknown" },
            naturalRole = role,
            npxg90 = if (role == "GK") 0.0 else rate,
            overallRating = rating,
            savePct = savePct,
            goals90 = per90(goals),
            assists90 = per90(assists),
            yellow90 = per90(yellowCards),
            red90 = per90(redCards),
        ),
        positionPenalty = 0,
    )
}
