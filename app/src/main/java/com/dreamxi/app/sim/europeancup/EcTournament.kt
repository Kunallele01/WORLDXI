package com.dreamxi.app.sim.europeancup

import com.dreamxi.app.sim.worldcup.NationalLineup

/**
 * The stages of a European Cup season, in order. [code] is what the database
 * stores in ec_matches.stage and ec_entries.reached_stage.
 *
 * A group "tie" is one match; a knockout tie is two legs, except the final,
 * which is one match at a neutral ground.
 */
enum class EcStage(val code: String, val label: String, val legs: Int) {
    GROUP("group", "Group stage", 1),
    ROUND_OF_16("r16", "Round of 16", 2),
    QUARTER_FINAL("qf", "Quarter-finals", 2),
    SEMI_FINAL("sf", "Semi-finals", 2),
    FINAL("final", "Final", 1);

    val isKnockout: Boolean get() = this != GROUP

    companion object {
        fun fromCode(code: String): EcStage =
            requireNotNull(entries.firstOrNull { it.code == code }) { "unknown stage '$code'" }
    }
}

/** Rules that changed over the seasons the mode covers. */
object EcRules {
    /**
     * Whether a knockout tie level on aggregate is settled by away goals before
     * extra time. UEFA abolished the rule from the 2021/22 season, so a season
     * whose starting year is 2021 or later is decided on aggregate alone, then
     * extra time in the second leg, then penalties.
     *
     * Seasons through 2020/21 still used it. 2023/24 is the only season loaded
     * so far; this is here so the older ones do not need an engine change.
     */
    fun awayGoalsApply(seasonYear: Int): Boolean = seasonYear <= 2020
}

/**
 * A club in one real season.
 *
 * @param realPosition where it really finished in its group, 1 to 4.
 * @param finishedBottom 4th in the group: one of the eight clubs the user's XI
 *   can replace.
 * @param strength mean rating of its best 14 players, on the scale the league
 *   engine uses. Estimated, not measured, when [strengthEstimated].
 * @param lineup its strongest side, or null when it has no roster: Red Star
 *   Belgrade, whose league EA does not license. The engine then rates it from
 *   [strength] alone.
 * @param reachedStage the furthest stage it really reached.
 */
data class EcEntry(
    val id: String,
    val name: String,
    val group: String,
    val realPosition: Int,
    val finishedBottom: Boolean,
    val strength: Double,
    val strengthEstimated: Boolean,
    val reachedStage: EcStage?,
    val champion: Boolean = false,
    val lineup: NationalLineup? = null,
)

/**
 * A match as it really happened.
 *
 * @param leg 1 or 2. For a group match, which meeting of the pair this was.
 * @param homePens the shootout score when there was one; a real shootout
 *   always has both.
 */
data class EcRealMatch(
    val stage: EcStage,
    val group: String?,
    val leg: Int,
    val date: String,
    val homeId: String,
    val awayId: String,
    val homeGoals: Int,
    val awayGoals: Int,
    val extraTime: Boolean = false,
    val homePens: Int? = null,
    val awayPens: Int? = null,
    val venue: String? = null,
) {
    val wentToPenalties: Boolean get() = homePens != null && awayPens != null
    fun involves(teamId: String): Boolean = homeId == teamId || awayId == teamId
}

/** One real season, everything the engine needs to replay it. */
data class EcTournamentData(
    val seasonYear: Int,
    val entries: List<EcEntry>,
    val matches: List<EcRealMatch>,
) {
    val awayGoalsRule: Boolean get() = EcRules.awayGoalsApply(seasonYear)
    val groups: Map<String, List<EcEntry>> get() = entries.groupBy { it.group }.toSortedMap()

    /** The eight clubs the takeover draw chooses between. */
    val takeoverCandidates: List<EcEntry> get() = entries.filter { it.finishedBottom }

    fun matches(stage: EcStage): List<EcRealMatch> = matches.filter { it.stage == stage }
}

/** The user's side: an id no real club uses, and his eleven in their roles. */
data class EcUserTeam(val id: String, val name: String, val lineup: NationalLineup)
