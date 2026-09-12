package com.dreamxi.app

import com.dreamxi.app.sim.TAKEOVER_DRAWS
import com.dreamxi.app.sim.TakeoverDraw
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The draw exists to be watched, but its job is to be fair. A procedure with
 * hundreds of steps is exactly the kind of thing that can pick up a quiet bias
 * — most obviously by settling a tie on list order — and nobody would ever
 * notice from playing. So the odds are asserted, not assumed.
 */
class TakeoverDrawTest {

    private val three = listOf("club-luton", "club-burnley", "club-sheffield")

    @Test
    fun `each of the three is chosen a third of the time`() {
        val counts = mutableMapOf<String, Int>()
        val runs = 6000
        repeat(runs) { seed ->
            val r = TakeoverDraw.draw(three, seed.toLong())
            counts[r.replacedTeamId] = (counts[r.replacedTeamId] ?: 0) + 1
        }
        val expected = runs / 3.0
        for (club in three) {
            val share = (counts[club] ?: 0) / runs.toDouble() * 100
            println("$club chosen ${"%.2f".format(share)}%")
            assertTrue(
                "$club came out ${counts[club]} times in $runs, expected about ${expected.toInt()}",
                kotlin.math.abs((counts[club] ?: 0) - expected) < expected * 0.06,
            )
        }
    }

    @Test
    fun `no club is favoured by the order it is passed in`() {
        // The failure this guards: resolving a tie by list position would hand
        // the place to whoever the database query happened to return first,
        // and the shares above would still look fine on average.
        val forward = (0 until 3000).count { TakeoverDraw.draw(three, it.toLong()).replacedTeamId == three[0] }
        val reversed = (0 until 3000).count {
            TakeoverDraw.draw(three.reversed(), it.toLong()).replacedTeamId == three[0]
        }
        println("first-in-list chosen $forward, same club last-in-list $reversed")
        // Both are draws from the same fair process, so neither position helps.
        assertTrue("forward $forward", kotlin.math.abs(forward - 1000) < 130)
        assertTrue("reversed $reversed", kotlin.math.abs(reversed - 1000) < 130)
    }

    @Test
    fun `the draw is long enough to be worth watching`() {
        val r = TakeoverDraw.draw(three, seed = 42)
        assertTrue("only ${r.ticks.size} draws", r.ticks.size >= TAKEOVER_DRAWS)
        assertEquals("every draw must count for someone", r.ticks.size, r.finalTallies.values.sum())
        // The lead should change hands, or there is nothing to watch.
        assertTrue("lead never changed", r.leadChanges > 0)
        // The tally shown at each tick must match the draws up to that point.
        r.ticks.forEachIndexed { i, tick -> assertEquals(i + 1, tick.tallies.values.sum()) }
    }

    @Test
    fun `a level draw goes to sudden death rather than to list order`() {
        // Ties for the lead are common enough over 300 draws that this must be
        // handled properly rather than left to chance.
        val tied = (0 until 400).map { TakeoverDraw.draw(three, it.toLong()) }
        val wentToSuddenDeath = tied.count { it.suddenDeathFrom != null }
        println("$wentToSuddenDeath of 400 draws needed sudden death")
        assertTrue("sudden death never triggered — is the tie path dead code?", wentToSuddenDeath > 0)
        for (r in tied.filter { it.suddenDeathFrom != null }) {
            // However it got there, exactly one club must lead at the end.
            val best = r.finalTallies.values.max()
            assertEquals(1, r.finalTallies.count { it.value == best })
        }
    }

    @Test
    fun `the same seed draws the same club`() {
        val a = TakeoverDraw.draw(three, seed = 7)
        val b = TakeoverDraw.draw(three, seed = 7)
        assertEquals(a.replacedTeamId, b.replacedTeamId)
        assertEquals(a.ticks.map { it.drawnTeamId }, b.ticks.map { it.drawnTeamId })
    }

    @Test
    fun `a season that loaded only one relegated club still resolves`() {
        val r = TakeoverDraw.draw(listOf("club-only"), seed = 1)
        assertEquals("club-only", r.replacedTeamId)
        assertTrue(r.ticks.isNotEmpty())
    }

    @Test
    fun `a duplicated club is rejected rather than given two chances`() {
        val result = runCatching { TakeoverDraw.draw(three + three[0], seed = 1) }
        assertTrue("a repeated club would double its odds", result.isFailure)
    }
}
