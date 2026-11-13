package com.dreamxi.app.sim.europeancup

/** Where one side of a knockout tie comes from. */
sealed interface EcSource {
    /** The club that finished [position] in group [letter]. */
    data class Group(val letter: String, val position: Int) : EcSource

    /** Whoever won the tie [slotId]. */
    data class WinnerOf(val slotId: String) : EcSource
}

/**
 * One knockout tie's place in the bracket.
 *
 * [home] hosts the first leg (or the final's nominal home side) and [away] the
 * second. [legDates] are the real dates, kept so a tie the engine plays in place
 * of a real one still falls on the days its round was played.
 */
data class EcSlot(
    val id: String,
    val stage: EcStage,
    val home: EcSource,
    val away: EcSource,
    val legDates: List<String>,
)

/**
 * The bracket, recovered from the real tournament.
 *
 * The round-of-16 pairings were drawn (group winners against runners-up of
 * other groups) and the quarter-final and semi-final pairings were drawn after
 * the round before, so no formula gives them. What the real results DO give is
 * who played whom, so each tie's two sides are traced back: in the round of 16
 * to a group position, after that to the tie the club came out of. When the
 * user's XI takes a group place, it inherits that place's whole path, which is
 * what "real results stand" has to mean for a draw that was made once.
 */
object EcBrackets {

    fun from(data: EcTournamentData): List<EcSlot> {
        val byId = data.entries.associateBy { it.id }
        val slots = mutableListOf<EcSlot>()
        var previous: Map<String, String> = emptyMap()      // club id -> the slot it came out of

        for (stage in listOf(EcStage.ROUND_OF_16, EcStage.QUARTER_FINAL, EcStage.SEMI_FINAL, EcStage.FINAL)) {
            val ties = data.matches(stage)
                .groupBy { setOf(it.homeId, it.awayId) }
                .values
                .map { legs -> legs.sortedWith(compareBy({ it.leg }, { it.date })) }
                .sortedBy { it.first().date }
            check(ties.size == expectedTies(stage)) { "${stage.label}: ${ties.size} ties" }

            val here = mutableMapOf<String, String>()
            ties.forEachIndexed { i, legs ->
                check(legs.size == stage.legs) { "${stage.label} tie ${i + 1} has ${legs.size} legs" }
                val id = "${stage.code}-${i + 1}"
                val first = legs.first()

                fun source(clubId: String): EcSource =
                    if (stage == EcStage.ROUND_OF_16) {
                        val e = checkNotNull(byId[clubId]) { "$clubId is not in the season" }
                        check(e.realPosition in 1..2) { "${e.name} reached the round of 16 from place ${e.realPosition}" }
                        EcSource.Group(e.group, e.realPosition)
                    } else {
                        EcSource.WinnerOf(checkNotNull(previous[clubId]) { "$clubId has no earlier tie" })
                    }

                slots += EcSlot(id, stage, source(first.homeId), source(first.awayId), legs.map { it.date })
                here[first.homeId] = id
                here[first.awayId] = id
            }
            previous = here
        }
        return slots
    }

    private fun expectedTies(stage: EcStage) = when (stage) {
        EcStage.ROUND_OF_16 -> 8
        EcStage.QUARTER_FINAL -> 4
        EcStage.SEMI_FINAL -> 2
        EcStage.FINAL -> 1
        EcStage.GROUP -> error("groups are not ties")
    }
}
