package com.dreamxi.app.feature.worldcup

import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.sim.TakeoverResult
import com.dreamxi.app.sim.worldcup.FINAL_ROUND
import com.dreamxi.app.sim.worldcup.GROUP_ROUND
import com.dreamxi.app.sim.worldcup.THIRD_PLACE_ROUND
import com.dreamxi.app.sim.worldcup.WcGroupRow
import com.dreamxi.app.sim.worldcup.WcPlayedMatch
import com.dreamxi.app.sim.worldcup.WcTournamentResult

/** The id the user's side carries through a tournament. */
const val WC_USER_ID = "your-xi"

enum class WcPhase {
    /** Choosing the World Cup and loading every squad in it. */
    Loading,

    /** The draw between that edition's bottom-of-group nations, playing out. */
    Drawing,

    /** The draw has settled; the user sees whose place he takes. */
    PlaceWon,

    /** The tournament, one stage at a time. */
    Playing,

    /** The final has been played. */
    Finished,
}

/**
 * One tap's worth of tournament: a group matchday, or a knockout round. The
 * third-place match travels with the final, because nobody taps separately to
 * watch it.
 */
data class WcStage(val title: String, val playLabel: String)

/**
 * Everything the tournament screens render.
 *
 * The whole tournament is played the moment the draw settles and revealed a
 * stage at a time — the season screen's reason, and here it matters even more:
 * a knockout bracket is one joint outcome, so a tournament generated as the
 * user taps would let his pace change who meets whom. [visibleStage] is a
 * curtain over results that already exist.
 */
data class WcTournamentUiState(
    val phase: WcPhase = WcPhase.Loading,
    /** A Free Mode XI, labelled wherever the tournament is shown. */
    val isFreeMode: Boolean = false,
    val year: Int? = null,
    val hosts: List<String> = emptyList(),
    /** Display name for every id in the run, the user's side included. */
    val names: Map<String, String> = emptyMap(),
    /** The bottom-of-group nations, in the order the draw shows them. */
    val drawCandidates: List<String> = emptyList(),
    val takeover: TakeoverResult? = null,
    val drawTick: Int = 0,
    val replacedId: String? = null,
    val userGroup: String? = null,
    val result: WcTournamentResult? = null,
    val stages: List<WcStage> = emptyList(),
    /** Stage index of each match in [result]'s match list, position for position. */
    val stageOfMatch: List<Int> = emptyList(),
    /** The last stage revealed; -1 before kick-off. */
    val visibleStage: Int = -1,
    /** The user's group as it stands after [visibleStage]. */
    val userGroupRows: List<WcGroupRow> = emptyList(),
    val isAdvancing: Boolean = false,
    val error: DreamXiError? = null,
) {
    fun name(id: String): String = names[id] ?: id

    val drawTallies: Map<String, Int>
        get() = takeover?.ticks?.getOrNull(drawTick - 1)?.tallies ?: drawCandidates.associateWith { 0 }

    val inSuddenDeath: Boolean
        get() = takeover?.suddenDeathFrom?.let { drawTick > it } == true

    private fun matchesWhere(test: (Int) -> Boolean): List<WcPlayedMatch> {
        val all = result?.matches ?: return emptyList()
        return all.filterIndexed { i, _ -> test(stageOfMatch.getOrElse(i) { Int.MAX_VALUE }) }
    }

    /** The stage just played. */
    val currentMatches: List<WcPlayedMatch> get() = matchesWhere { it == visibleStage }

    /** Everything played so far. */
    val playedMatches: List<WcPlayedMatch> get() = matchesWhere { it <= visibleStage }

    val userMatch: WcPlayedMatch? get() = currentMatches.firstOrNull { it.involves(WC_USER_ID) }

    val isOver: Boolean get() = stages.isNotEmpty() && visibleStage >= stages.lastIndex

    val nextStage: WcStage? get() = stages.getOrNull(visibleStage + 1)

    val currentStage: WcStage? get() = stages.getOrNull(visibleStage)

    /**
     * Knockout matches revealed so far, round by round.
     *
     * The third-place match keeps its OWN heading and sits before the final,
     * where it is really played. Folding it in under "Final" printed the
     * play-off's score above the final's, under the final's heading.
     */
    val knockoutSoFar: List<Pair<String, List<WcPlayedMatch>>>
        get() {
            val byRound = playedMatches.filter { it.round != GROUP_ROUND }.groupBy { it.round }
            return KNOCKOUT_ORDER.mapNotNull { round -> byRound[round]?.let { round to it } }
        }

    /**
     * Where the user stands, in a few words: his group place during the group
     * stage, then the round he is in or went out in.
     */
    val userStatus: String
        get() {
            val r = result ?: return ""
            if (visibleStage < 0) return "Group ${userGroup.orEmpty()}"
            val played = playedMatches.filter { it.involves(WC_USER_ID) }
            val lastKnockout = played.lastOrNull { it.round != GROUP_ROUND && it.round != THIRD_PLACE_ROUND }
            val groupDone = played.count { it.round == GROUP_ROUND } == 3
            return when {
                isOver && r.championId == WC_USER_ID -> "World champions"
                lastKnockout != null && lastKnockout.loserId == WC_USER_ID -> "Out in the ${roundName(lastKnockout.round)}"
                lastKnockout != null -> "Through to the ${roundName(nextRound(lastKnockout.round))}"
                groupDone -> {
                    val place = userGroupRows.indexOfFirst { it.teamId == WC_USER_ID } + 1
                    val through = r.matches.any { it.round != GROUP_ROUND && it.involves(WC_USER_ID) }
                    if (through) "${place.ordinalWord()} in Group ${userGroup.orEmpty()} · through"
                    else "Out in the group stage"
                }
                else -> {
                    val place = userGroupRows.indexOfFirst { it.teamId == WC_USER_ID } + 1
                    "${place.ordinalWord()} in Group ${userGroup.orEmpty()}"
                }
            }
        }
}

/** Knockout rounds as they are played, the third-place match before the final. */
private val KNOCKOUT_ORDER = listOf(
    "Round of 32", "Round of 16", "Quarter-finals", "Semi-finals", THIRD_PLACE_ROUND, FINAL_ROUND,
)

internal fun roundName(round: String): String = when (round) {
    "Quarter-finals" -> "quarter-finals"
    "Semi-finals" -> "semi-finals"
    "Round of 16" -> "round of 16"
    "Round of 32" -> "round of 32"
    FINAL_ROUND -> "final"
    else -> round.lowercase()
}

private fun nextRound(round: String): String = when (round) {
    "Round of 32" -> "Round of 16"
    "Round of 16" -> "Quarter-finals"
    "Quarter-finals" -> "Semi-finals"
    else -> FINAL_ROUND
}

internal fun Int.ordinalWord(): String = com.dreamxi.app.feature.season.ordinal(this)
