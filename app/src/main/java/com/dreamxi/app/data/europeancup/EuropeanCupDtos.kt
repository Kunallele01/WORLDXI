package com.dreamxi.app.data.europeancup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Row shapes for migration_0011's ec_* tables, decoded by supabase-kt.

@Serializable
internal data class EcSeasonDto(
    val id: Long,
    @SerialName("season_year") val seasonYear: Int,
    val label: String,
    val format: String,
    @SerialName("team_count") val teamCount: Int,
)

@Serializable
internal data class EcClubRefDto(val name: String)

@Serializable
internal data class EcSquadRefDto(
    @SerialName("has_roster") val hasRoster: Boolean,
    @SerialName("team_strength") val teamStrength: Double,
    @SerialName("strength_source") val strengthSource: String,
) {
    /** Estimated from results rather than measured from a squad. */
    val estimated: Boolean get() = strengthSource != "squad_top14"
}

@Serializable
internal data class EcEntryDto(
    val id: Long,
    @SerialName("season_id") val seasonId: Long,
    @SerialName("club_id") val clubId: Long,
    @SerialName("squad_id") val squadId: Long,
    @SerialName("group_label") val groupLabel: String,
    @SerialName("group_position") val groupPosition: Int,
    @SerialName("finished_bottom") val finishedBottom: Boolean = false,
    @SerialName("reached_stage") val reachedStage: String,
    val club: EcClubRefDto,
    val squad: EcSquadRefDto,
)

@Serializable
internal data class EcSquadPlayerDto(
    val id: Long,
    @SerialName("squad_id") val squadId: Long,
    @SerialName("sofifa_id") val sofifaId: String? = null,
    @SerialName("full_name") val fullName: String,
    val nationality: String? = null,
    val overall: Int,
    val rating: Double,
    @SerialName("rating_edition") val ratingEdition: String,
    val positions: List<String> = emptyList(),
    @SerialName("birth_year") val birthYear: Int? = null,
    // EA's positional grid as the change from his primary role; all null for a
    // keeper. Same meaning as wc_squad_players (migration_0009).
    @SerialName("grid_cb") val gridCb: Int? = null,
    @SerialName("grid_fb") val gridFb: Int? = null,
    @SerialName("grid_dm") val gridDm: Int? = null,
    @SerialName("grid_cm") val gridCm: Int? = null,
    @SerialName("grid_cam") val gridCam: Int? = null,
    @SerialName("grid_winger") val gridWinger: Int? = null,
    @SerialName("grid_st") val gridSt: Int? = null,
) {
    /** The grid by role, or null when he has none (a keeper). */
    val grid: Map<String, Int>?
        get() {
            val cells = listOf(
                "CB" to gridCb, "FB" to gridFb, "DM" to gridDm, "CM" to gridCm,
                "CAM" to gridCam, "Winger" to gridWinger, "ST" to gridSt,
            )
            if (cells.any { it.second == null }) return null
            return cells.associate { (role, delta) -> role to delta!! }
        }
}

@Serializable
internal data class EcMatchDto(
    val id: Long,
    val stage: String,
    @SerialName("group_label") val groupLabel: String? = null,
    val leg: Int,
    @SerialName("match_date") val matchDate: String? = null,
    @SerialName("home_entry_id") val homeEntryId: Long,
    @SerialName("away_entry_id") val awayEntryId: Long,
    @SerialName("home_goals") val homeGoals: Int,
    @SerialName("away_goals") val awayGoals: Int,
    @SerialName("extra_time") val extraTime: Boolean = false,
    @SerialName("home_pens") val homePens: Int? = null,
    @SerialName("away_pens") val awayPens: Int? = null,
    val venue: String? = null,
)
