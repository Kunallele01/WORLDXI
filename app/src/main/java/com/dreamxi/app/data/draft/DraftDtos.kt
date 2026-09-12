package com.dreamxi.app.data.draft

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for the draft's reference-data reads. Kept separate from the
 * feature's domain models (`feature/draft/DraftModels.kt`) so a schema change
 * lands in one place and the UI keeps talking in slots and players.
 */

@Serializable
internal data class LeagueDto(
    val id: Long,
    val name: String,
    val country: String? = null,
)

@Serializable
internal data class SeasonDto(
    val id: Long,
    val label: String,
    @SerialName("league_id") val leagueId: Long,
)

/**
 * A row of `club_season_draft_pool` (migration_0004). The quartile is computed
 * by the view via `ntile(4)`, never stored — boundaries shift as more seasons
 * are loaded, and a stored tier would silently go stale.
 */
@Serializable
internal data class ClubSeasonPoolDto(
    @SerialName("club_season_id") val clubSeasonId: Long,
    @SerialName("season_id") val seasonId: Long,
    @SerialName("club_name") val clubName: String,
    @SerialName("final_position") val finalPosition: Int? = null,
    @SerialName("squad_strength") val squadStrength: Double? = null,
    @SerialName("strength_quartile") val strengthQuartile: Int? = null,
)

@Serializable
internal data class PlayerRefDto(
    val id: Long,
    @SerialName("full_name") val fullName: String,
    val nationality: String? = null,
)

@Serializable
internal data class SquadPlayerDto(
    val id: Long,
    @SerialName("player_id") val playerId: Long,
    // Only selected by the Free Mode magic query, which reaches across a
    // whole league instead of one squad and so has to say which club-season
    // each row belongs to. Nullable so the per-squad query, which already
    // knows, need not ask for it.
    @SerialName("club_season_id") val clubSeasonId: Long? = null,
    @SerialName("overall_rating") val overallRating: Int? = null,
    val minutes: Int? = null,
    val goals: Int? = null,
    val assists: Int? = null,
    val appearances: Int? = null,
    val shots: Int? = null,
    @SerialName("shots_on_target") val shotsOnTarget: Int? = null,
    val tackles: Int? = null,
    @SerialName("yellow_cards") val yellowCards: Int? = null,
    @SerialName("red_cards") val redCards: Int? = null,
    @SerialName("save_pct") val savePct: Double? = null,
    /**
     * Non-penalty expected goals for the season. The simulation's central
     * attacking input — an XI's summed npxG/90 predicts real goals at r=+0.864.
     * Null for the 5% of regulars Understat could not be matched to (mid-season
     * transfers), who fall back to their position's rate; see SimPlayer.
     */
    val npxg: Double? = null,
    @SerialName("clean_sheets") val cleanSheets: Int? = null,
    @SerialName("primary_position") val primaryPosition: String? = null,
    // EA's rating for this player at each draft slot (migration_0005). Nullable
    // individually: 27 of 5,723 player-seasons resolve no grid at all.
    @SerialName("pos_rating_cb") val posCb: Int? = null,
    @SerialName("pos_rating_fb") val posFb: Int? = null,
    @SerialName("pos_rating_dm") val posDm: Int? = null,
    @SerialName("pos_rating_cm") val posCm: Int? = null,
    @SerialName("pos_rating_cam") val posCam: Int? = null,
    @SerialName("pos_rating_winger") val posWinger: Int? = null,
    @SerialName("pos_rating_st") val posSt: Int? = null,
    @SerialName("position_side") val positionSide: String? = null,
    val players: PlayerRefDto? = null,
)

/** club_seasons row used for the per-season defensive ranking. */
@Serializable
internal data class ClubSeasonRecordDto(
    val id: Long,
    @SerialName("club_name") val clubName: String,
    @SerialName("season_id") val seasonId: Long,
    @SerialName("goals_against") val goalsAgainst: Int? = null,
)
