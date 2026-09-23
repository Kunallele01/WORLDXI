package com.dreamxi.app.feature.worldcup

import com.dreamxi.app.sim.worldcup.FINAL_ROUND
import com.dreamxi.app.sim.worldcup.GROUP_ROUND
import com.dreamxi.app.sim.worldcup.THIRD_PLACE_ROUND
import com.dreamxi.app.sim.worldcup.WcPlayedMatch

/**
 * Splits a played tournament into the stages the screen reveals one tap at a
 * time: three group matchdays, then each knockout round.
 *
 * GROUP MATCHDAYS ARE WORKED OUT, NOT STORED. The data carries dates, not
 * matchdays, but every group plays six matches over three rounds, two at a
 * time, so a group's matches in date order ARE its three matchdays in pairs.
 * It is worked out per group because groups do not play on the same days.
 */
object WcStages {

    private val KNOCKOUT_ORDER = listOf("Round of 32", "Round of 16", "Quarter-finals", "Semi-finals", FINAL_ROUND)

    /** The stages, and the stage of each match in [matches], position for position. */
    fun build(matches: List<WcPlayedMatch>): Pair<List<WcStage>, List<Int>> {
        val stageOf = IntArray(matches.size) { -1 }

        matches.withIndex()
            .filter { it.value.round == GROUP_ROUND }
            .groupBy { it.value.group }
            .values
            .forEach { group ->
                group.sortedWith(compareBy({ it.value.date.orEmpty() }, { it.index }))
                    .forEachIndexed { order, indexed -> stageOf[indexed.index] = order / 2 }
            }

        val stages = MutableList(3) { WcStage("Group stage · matchday ${it + 1}", "Play matchday ${it + 1}") }
        for (round in KNOCKOUT_ORDER) {
            val indices = matches.indices.filter {
                matches[it].round == round || (round == FINAL_ROUND && matches[it].round == THIRD_PLACE_ROUND)
            }
            if (indices.isEmpty()) continue
            val stage = stages.size
            stages += WcStage(
                title = round,
                playLabel = if (round == FINAL_ROUND) "Play the final" else "Play the ${roundName(round)}",
            )
            indices.forEach { stageOf[it] = stage }
        }
        check(stageOf.none { it < 0 }) { "a match fell outside every stage" }
        return stages to stageOf.toList()
    }
}
