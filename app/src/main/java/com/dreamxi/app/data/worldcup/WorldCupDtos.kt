package com.dreamxi.app.data.worldcup

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire shapes for World Cup mode's reference reads (migrations 0007 and 0008).
 * Kept apart from the engine's types so a schema change lands here and the
 * tournament keeps talking in entries, squads and matches.
 */

@Serializable
internal data class WcTournamentRefDto(
    val id: Long,
    val year: Int,
    @SerialName("team_count") val teamCount: Int = 0,
    @SerialName("rating_edition") val ratingEdition: String = "",
)

@Serializable
internal data class WcSquadRefDto(
    @SerialName("rating_edition") val ratingEdition: String,
)

@Serializable
internal data class WcEntryDto(
    val id: Long,
    @SerialName("tournament_id") val tournamentId: Long,
    @SerialName("squad_id") val squadId: Long? = null,
    @SerialName("fixture_name") val fixtureName: String,
    @SerialName("group_label") val groupLabel: String? = null,
    @SerialName("group_position") val groupPosition: Int? = null,
    @SerialName("finished_bottom") val finishedBottom: Boolean = false,
    @SerialName("is_host") val isHost: Boolean = false,
    val tournament: WcTournamentRefDto? = null,
    val squad: WcSquadRefDto? = null,
)

@Serializable
internal data class WcSquadPlayerDto(
    val id: Long,
    @SerialName("squad_id") val squadId: Long,
    @SerialName("sofifa_id") val sofifaId: String? = null,
    @SerialName("full_name") val fullName: String,
    val overall: Int,
    val rating: Double,
    val positions: List<String> = emptyList(),
    val club: String? = null,
    // EA's positional grid as the change from his primary role (migration_0009);
    // all null when he has no grid within two editions.
    @SerialName("grid_cb") val gridCb: Int? = null,
    @SerialName("grid_fb") val gridFb: Int? = null,
    @SerialName("grid_dm") val gridDm: Int? = null,
    @SerialName("grid_cm") val gridCm: Int? = null,
    @SerialName("grid_cam") val gridCam: Int? = null,
    @SerialName("grid_winger") val gridWinger: Int? = null,
    @SerialName("grid_st") val gridSt: Int? = null,
    @SerialName("grid_edition") val gridEdition: String? = null,
) {
    /** The grid by role, or null when he has none — see WcPlayer.grid. */
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
internal data class WcMatchDto(
    val id: Long,
    val round: String,
    @SerialName("match_date") val matchDate: String? = null,
    @SerialName("home_entry_id") val homeEntryId: Long,
    @SerialName("away_entry_id") val awayEntryId: Long,
    @SerialName("home_goals") val homeGoals: Int,
    @SerialName("away_goals") val awayGoals: Int,
    @SerialName("home_pens") val homePens: Int? = null,
    @SerialName("away_pens") val awayPens: Int? = null,
    @SerialName("shootout_winner_entry_id") val shootoutWinnerEntryId: Long? = null,
)

@Serializable
internal data class WcGoalDto(
    @SerialName("match_id") val matchId: Long,
    @SerialName("entry_id") val entryId: Long,
    val scorer: String,
    val minute: Int,
    val stoppage: Int? = null,
    val kind: String,
)
