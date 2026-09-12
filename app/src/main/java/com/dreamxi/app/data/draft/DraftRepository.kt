package com.dreamxi.app.data.draft

import com.dreamxi.app.feature.draft.DefensiveRecord
import com.dreamxi.app.feature.draft.SpinResult
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.ui.theme.PlayerPosition
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.dreamxi.app.feature.freemode.MagicCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reference-data reads for the draft. All of it is public-read (§5), so these
 * run on the anon key with no session.
 *
 * MINIMUM MINUTES. A squad's draftable list is not its full roster. Token
 * appearances are excluded — Casemiro played 8 minutes for Real Madrid in
 * 2022/23 before moving to Manchester United and was never meaningfully part
 * of that squad, so offering him as a Madrid 22/23 pick would be wrong. The
 * same 450-minute floor is used by the ETL when it computes squad strength,
 * so what the user can draft and what the squad is rated on stay the same set
 * of players.
 */
private const val MIN_DRAFTABLE_MINUTES = 450

/**
 * Every role the draft knows, fetched for the magic XI whatever the formation
 * asks for. See DraftRepository.magicCandidates.
 */
private val DRAFT_ROLES = listOf("GK", "CB", "FB", "DM", "CM", "CAM", "Winger", "ST")

/** How deep to look in each position. Eleven shirts; the rest is room to manoeuvre. */
private const val MAGIC_CANDIDATES_PER_ROLE = 40L

/**
 * A higher bar than a normal pick. The magic XI is meant to be the best a
 * league ever fielded, and 450 minutes lets in a player who was excellent
 * across five appearances — true, but not what "the best eleven of the decade"
 * should mean.
 */
private const val MIN_MAGIC_MINUTES = 900

private const val MAGIC_COLUMNS =
    "id,player_id,club_season_id,overall_rating,minutes,goals,assists,appearances,shots," +
        "shots_on_target,tackles,yellow_cards,red_cards,save_pct,clean_sheets,npxg," +
        "primary_position,position_side,pos_rating_cb,pos_rating_fb," +
        "pos_rating_dm,pos_rating_cm,pos_rating_cam,pos_rating_winger," +
        "pos_rating_st,players(id,full_name,nationality)"

/** A league as offered on the picker. */
data class League(
    val id: Long,
    val name: String,
    val country: String,
    val seasonCount: Int,
    val seasonRange: String,
)

@Singleton
class DraftRepository @Inject constructor(
    private val supabase: SupabaseClient,
) {

    suspend fun leagues(): List<League> = withContext(Dispatchers.IO) {
        val leagues = supabase.from("leagues")
            .select(Columns.list("id", "name", "country"))
            .decodeList<LeagueDto>()
        // Season count per league, so the picker can say what a league
        // actually contains rather than offering an empty one.
        val seasons = supabase.from("seasons")
            .select(Columns.list("id", "label", "league_id"))
            .decodeList<SeasonDto>()
            .groupBy { it.leagueId }
        leagues.map { l ->
            val ls = seasons[l.id].orEmpty().map { it.label }.sorted()
            League(
                id = l.id,
                name = l.name,
                country = l.country.orEmpty(),
                seasonCount = ls.size,
                seasonRange = if (ls.isEmpty()) "" else "${ls.first()} – ${ls.last()}",
            )
        }.sortedByDescending { it.seasonCount }
    }

    /**
     * Club-seasons a run may still spin.
     *
     * Enforcement of the tier caps lives in the database
     * (`draft_eligible_club_seasons`, migration_0004) rather than here, so the
     * rules cannot drift between platforms and a spin cannot be re-rolled
     * client-side until a nicer squad appears.
     *
     * [runId] is nullable only because run persistence needs auth, which is
     * not wired yet. With no run, the whole league pool is returned and the
     * caps simply do not apply — acceptable for a local draft that is not
     * being saved, and NOT a substitute for the RPC once runs exist.
     */
    internal suspend fun eligibleClubSeasons(leagueId: Long, runId: Long?): List<ClubSeasonPoolDto> =
        withContext(Dispatchers.IO) {
            if (runId != null) {
                return@withContext supabase.postgrest
                    .rpc("draft_eligible_club_seasons", buildJsonObject { put("p_run_id", runId) })
                    .decodeList<ClubSeasonPoolDto>()
            }
            val seasonIds = supabase.from("seasons")
                .select(Columns.list("id", "label", "league_id")) {
                    filter { eq("league_id", leagueId) }
                }
                .decodeList<SeasonDto>()
                .map { it.id }
            if (seasonIds.isEmpty()) return@withContext emptyList()

            supabase.from("club_season_draft_pool")
                .select(
                    Columns.list(
                        "club_season_id", "season_id", "club_name",
                        "final_position", "squad_strength", "strength_quartile",
                    ),
                ) {
                    filter { isIn("season_id", seasonIds) }
                }
                .decodeList<ClubSeasonPoolDto>()
        }

    /**
     * Defensive record for every club-season in the league, keyed by
     * club_season id.
     *
     * Loaded once per run rather than per spin: it is 100 rows, it never
     * changes, and a defender's description needs it on every row of every
     * squad list. Rank is computed here rather than stored because it is
     * relative to the season it sits in.
     */
    suspend fun defensiveRecords(leagueId: Long): Map<Long, DefensiveRecord> =
        withContext(Dispatchers.IO) {
            val seasonIds = supabase.from("seasons")
                .select(Columns.list("id", "label", "league_id")) {
                    filter { eq("league_id", leagueId) }
                }
                .decodeList<SeasonDto>()
                .map { it.id }
            if (seasonIds.isEmpty()) return@withContext emptyMap()

            supabase.from("club_seasons")
                .select(Columns.list("id", "club_name", "season_id", "goals_against")) {
                    filter { isIn("season_id", seasonIds) }
                }
                .decodeList<ClubSeasonRecordDto>()
                .filter { it.goalsAgainst != null }
                .groupBy { it.seasonId }
                .flatMap { (_, rows) ->
                    val ranked = rows.sortedBy { it.goalsAgainst }
                    ranked.mapIndexed { index, row ->
                        row.id to DefensiveRecord(
                            goalsAgainst = row.goalsAgainst!!,
                            rank = index + 1,
                            teamsInLeague = ranked.size,
                        )
                    }
                }
                .toMap()
        }

    suspend fun seasonLabels(leagueId: Long): Map<Long, String> = withContext(Dispatchers.IO) {
        supabase.from("seasons")
            .select(Columns.list("id", "label", "league_id")) {
                filter { eq("league_id", leagueId) }
            }
            .decodeList<SeasonDto>()
            .associate { it.id to it.label }
    }

    /**
     * The draftable squad for one club-season.
     *
     * [alreadyDraftedPlayerIds] carries the run's picks so far. Player identity
     * is unique across the WHOLE draft, not per club-season, so someone taken
     * in round 2 as a Liverpool 19/20 pick must not be takeable again in round
     * 7 as a Liverpool 21/22 pick. He is returned flagged rather than filtered
     * out so the UI can show him greyed and explain himself.
     */
    /**
     * The strongest players in a whole league, for Free Mode's magic XI.
     *
     * ASKED FOR PER POSITION, not as one top-N list, and the reason matters:
     * a formation with no attacking-midfield shirt would otherwise never see
     * De Bruyne at all, because he is recorded CAM. Every role is fetched
     * regardless of the formation, and the solver then decides who fills what
     * — which is how a CAM ends up in central midfield at a small cost.
     *
     * [MAGIC_CANDIDATES_PER_ROLE] deep is far more than eleven shirts need. The
     * surplus is what lets the solver route around a player it wants elsewhere,
     * and what gives the run-to-run shuffle anyone to shuffle with.
     */
    suspend fun magicCandidates(leagueId: Long): List<MagicCandidate> = withContext(Dispatchers.IO) {
        val pool = eligibleClubSeasons(leagueId, runId = null)
        if (pool.isEmpty()) return@withContext emptyList()
        val byClubSeason = pool.associateBy { it.clubSeasonId }
        val seasonLabels = seasonLabels(leagueId)
        val clubSeasonIds = pool.map { it.clubSeasonId }

        coroutineScope {
            DRAFT_ROLES.map { role ->
                async {
                    supabase.from("player_season_stats")
                        .select(Columns.raw(MAGIC_COLUMNS)) {
                            filter {
                                isIn("club_season_id", clubSeasonIds)
                                eq("primary_position", role)
                                gte("minutes", MIN_MAGIC_MINUTES)
                            }
                            order("overall_rating", Order.DESCENDING)
                            limit(MAGIC_CANDIDATES_PER_ROLE)
                        }
                        .decodeList<SquadPlayerDto>()
                }
            }.awaitAll()
        }.flatten().mapNotNull { dto ->
            val clubSeason = byClubSeason[dto.clubSeasonId ?: return@mapNotNull null]
                ?: return@mapNotNull null
            val player = dto.toDomain(draftedIds = emptySet()) ?: return@mapNotNull null
            MagicCandidate(
                player = player,
                clubSeasonId = clubSeason.clubSeasonId,
                clubName = clubSeason.clubName,
                seasonLabel = seasonLabels[clubSeason.seasonId].orEmpty(),
            )
        }
    }

    suspend fun squadFor(
        clubSeasonId: Long,
        clubName: String,
        seasonLabel: String,
        finalPosition: Int?,
        squadStrength: Double?,
        strengthQuartile: Int?,
        alreadyDraftedPlayerIds: Set<Long>,
        defence: DefensiveRecord?,
    ): SpinResult = withContext(Dispatchers.IO) {
        val rows = supabase.from("player_season_stats")
            .select(
                Columns.raw(
                    "id,player_id,overall_rating,minutes,goals,assists,appearances,shots," +
                        "shots_on_target,tackles,yellow_cards,red_cards,save_pct,clean_sheets,npxg," +
                        "primary_position,position_side,pos_rating_cb,pos_rating_fb," +
                        "pos_rating_dm,pos_rating_cm,pos_rating_cam,pos_rating_winger," +
                        "pos_rating_st,players(id,full_name,nationality)",
                ),
            ) {
                filter {
                    eq("club_season_id", clubSeasonId)
                    gte("minutes", MIN_DRAFTABLE_MINUTES)
                }
            }
            .decodeList<SquadPlayerDto>()

        SpinResult(
            clubSeasonId = clubSeasonId,
            clubName = clubName,
            seasonLabel = seasonLabel,
            finalPosition = finalPosition,
            squadStrength = squadStrength,
            strengthQuartile = strengthQuartile,
            players = rows.mapNotNull { it.toDomain(alreadyDraftedPlayerIds) }
                .sortedByDescending { it.overallRating },
            defence = defence,
        )
    }
}

private fun SquadPlayerDto.toDomain(draftedIds: Set<Long>): SquadPlayer? {
    val role = primaryPosition ?: return null
    val name = players?.fullName ?: return null
    val rating = overallRating ?: return null
    return SquadPlayer(
        playerSeasonStatId = id,
        playerId = playerId,
        fullName = name,
        role = role,
        group = groupForRole(role),
        overallRating = rating,
        minutes = minutes ?: 0,
        goals = goals ?: 0,
        assists = assists ?: 0,
        appearances = appearances ?: 0,
        shots = shots ?: 0,
        shotsOnTarget = shotsOnTarget ?: 0,
        tackles = tackles ?: 0,
        yellowCards = yellowCards ?: 0,
        redCards = redCards ?: 0,
        savePct = savePct,
        cleanSheets = cleanSheets ?: 0,
        // Per 90 rather than per season, because the engine is calibrated on
        // rates and a squad player's total would understate him badly.
        npxg90 = npxg?.takeIf { (minutes ?: 0) > 0 }?.let { it * 90.0 / minutes!! },
        nationality = players?.nationality?.takeIf { it.isNotBlank() },
        side = positionSide,
        positionRatings = buildMap {
            posCb?.let { put("cb", it) }
            posFb?.let { put("fb", it) }
            posDm?.let { put("dm", it) }
            posCm?.let { put("cm", it) }
            posCam?.let { put("cam", it) }
            posWinger?.let { put("winger", it) }
            posSt?.let { put("st", it) }
        },
        alreadyDrafted = playerId in draftedIds,
    )
}

/**
 * The 8 draft roles collapse to the 4 coarse groups used for colour-coding.
 * The two systems are not interchangeable — the 4 groups are the population
 * the rating percentiles are computed within, the 8 roles are what the XI is
 * built from — so the mapping is explicit rather than inferred from a prefix.
 */
internal fun groupForRole(role: String): PlayerPosition = when (role) {
    "GK" -> PlayerPosition.GOALKEEPER
    "CB", "FB" -> PlayerPosition.DEFENDER
    "DM", "CM", "CAM" -> PlayerPosition.MIDFIELDER
    "Winger", "ST" -> PlayerPosition.FORWARD
    else -> PlayerPosition.MIDFIELDER
}
