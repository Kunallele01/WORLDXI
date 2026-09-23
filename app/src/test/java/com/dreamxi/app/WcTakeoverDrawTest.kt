package com.dreamxi.app

import com.dreamxi.app.sim.TAKEOVER_DRAWS
import com.dreamxi.app.sim.TakeoverDraw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The World Cup takeover draw, over a field of eight or twelve nations rather
 * than the club season's three relegated clubs.
 *
 * Two different things are being checked, and only one of them was ever in
 * doubt. FAIRNESS is exact at any number of draws — nothing in the procedure
 * distinguishes the nations — so it is asserted here for a twelve-nation field
 * to prove the bigger pool did not break it. SEPARATION is not: with 300 draws
 * shared twelve ways the lead was regularly tied at the end, which is what sent
 * the draw into sudden death and made the tally on screen look like a coin
 * flip. Fifty draws a nation is what fixed that, and this measures it.
 */
class WcTakeoverDrawTest {

    private fun nations(count: Int) = (1..count).map { "n$it" }

    private fun drawsFor(candidates: Int) = (candidates * 50).coerceAtLeast(500)

    @Test
    fun `every nation is equally likely with twelve in the draw`() {
        val field = nations(12)
        val wins = mutableMapOf<String, Int>()
        val runs = 6_000
        repeat(runs) { seed ->
            val result = TakeoverDraw.draw(field, seed.toLong(), drawsFor(field.size))
            wins.merge(result.replacedTeamId, 1, Int::plus)
        }
        val expected = runs.toDouble() / field.size
        println("twelve-nation draw over $runs runs: ${wins.toSortedMap()}")
        for (nation in field) {
            assertEquals("$nation came out ${wins[nation]} times, expected ~$expected", expected, (wins[nation] ?: 0).toDouble(), expected * 0.18)
        }
        // Order must not matter either — not that a seed picks the same nation
        // when the list is reversed (it cannot: the draw picks by index), but
        // that no POSITION in the list is favoured. Reversed, each nation must
        // still come out about as often.
        val reversed = mutableMapOf<String, Int>()
        repeat(runs) { seed ->
            reversed.merge(TakeoverDraw.draw(field.reversed(), seed.toLong(), drawsFor(field.size)).replacedTeamId, 1, Int::plus)
        }
        for (nation in field) {
            assertEquals(
                "$nation: ${wins[nation]} forwards, ${reversed[nation]} reversed",
                expected, (reversed[nation] ?: 0).toDouble(), expected * 0.18,
            )
        }
    }

    @Test
    fun `fifty draws a nation gives a clear leader where 300 shared did not`() {
        for (size in listOf(8, 12)) {
            val field = nations(size)
            fun measure(draws: Int): Pair<Double, Double> {
                var suddenDeath = 0
                var margin = 0.0
                val runs = 2_000
                repeat(runs) { seed ->
                    val r = TakeoverDraw.draw(field, seed.toLong(), draws)
                    if (r.suddenDeathFrom != null) suddenDeath++
                    val tallies = r.finalTallies.values.sortedDescending()
                    margin += (tallies[0] - tallies[1]).toDouble()
                }
                return suddenDeath * 100.0 / runs to margin / runs
            }
            val (oldTies, oldMargin) = measure(TAKEOVER_DRAWS)
            val (newTies, newMargin) = measure(drawsFor(size))
            println(
                "$size nations: at ${TAKEOVER_DRAWS} draws ${"%.1f".format(oldTies)}% went to sudden death, " +
                    "winning margin ${"%.1f".format(oldMargin)}; at ${drawsFor(size)} draws " +
                    "${"%.1f".format(newTies)}%, margin ${"%.1f".format(newMargin)}",
            )
            assertTrue("more draws should separate the field better", newMargin > oldMargin)
            assertTrue("and should not need sudden death more often", newTies <= oldTies + 1.0)
        }
    }

    @Test
    fun `the field size sets the number of draws`() {
        assertEquals(500, drawsFor(8))
        assertEquals(600, drawsFor(12))
        assertEquals(500, drawsFor(3))
        // Every draw is on screen, so the tick list is the count itself (plus any sudden death).
        assertTrue(TakeoverDraw.draw(nations(12), 1L, 600).ticks.size >= 600)
    }
}
