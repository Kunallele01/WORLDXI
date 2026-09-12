package com.dreamxi.app

import com.dreamxi.app.feature.draft.CLUB_REPEAT_WEIGHT
import com.dreamxi.app.feature.draft.CLUB_SEASON_REPEAT_WEIGHT
import com.dreamxi.app.feature.draft.spinWeight
import com.dreamxi.app.feature.draft.weightedIndex
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Repeat clubs must be UNCOMMON, never impossible.
 *
 * Two failures this guards, one at each extreme. The pool originally had no
 * memory, so 43.6% of drafts drew the identical club-season twice. The fix
 * banned repeats outright, which was wrong the other way: a club coming round
 * again is part of the spin.
 */
class SpinWeightingTest {

    @Test
    fun `a club never seen is at full weight`() {
        assertEquals(1.0, spinWeight(timesClubUsed = 0, timesClubSeasonUsed = 0), 1e-9)
    }

    @Test
    fun `nothing is ever removed from the pool`() {
        // Every weight must stay strictly positive: "unlikely" is the whole
        // design, and a zero would quietly reinstate the exclusion.
        for (club in 0..4) {
            for (exact in 0..4) {
                assertTrue(
                    "weight went to zero at club=$club exact=$exact",
                    spinWeight(club, exact) > 0.0,
                )
            }
        }
    }

    @Test
    fun `a repeat is rarer than a fresh club, and a repeat season rarer still`() {
        val fresh = spinWeight(0, 0)
        val sameClub = spinWeight(1, 0)
        val sameClubSameSeason = spinWeight(1, 1)
        assertTrue("$sameClub should be below $fresh", sameClub < fresh)
        assertTrue(
            "the identical squad should be the least likely repeat",
            sameClubSameSeason < sameClub,
        )
        assertEquals(CLUB_REPEAT_WEIGHT, sameClub, 1e-9)
        assertEquals(CLUB_REPEAT_WEIGHT * CLUB_SEASON_REPEAT_WEIGHT, sameClubSameSeason, 1e-9)
    }

    @Test
    fun `weights compound, so a third appearance is rarer than a second`() {
        assertTrue(spinWeight(2, 0) < spinWeight(1, 0))
        assertEquals(CLUB_REPEAT_WEIGHT * CLUB_REPEAT_WEIGHT, spinWeight(2, 0), 1e-12)
    }

    @Test
    fun `the draw honours the weights`() {
        // Two clubs, one already used once. Over many draws the used one should
        // come up at about a quarter of the other's rate.
        val weights = listOf(spinWeight(0, 0), spinWeight(1, 0))
        val random = Random(11)
        var second = 0
        val runs = 40_000
        repeat(runs) { if (weightedIndex(weights, random.nextDouble()) == 1) second++ }
        val share = second / runs.toDouble()
        val expected = weights[1] / weights.sum()
        println("repeat club drawn ${"%.3f".format(share)} of the time, expected ${"%.3f".format(expected)}")
        assertTrue("share was $share against $expected", kotlin.math.abs(share - expected) < 0.02)
    }

    @Test
    fun `a full draft still repeats a club sometimes`() {
        // The behavioural claim, on the real pool shape: 20 clubs, 5 seasons,
        // 11 rounds. Measured at these weights over 200,000 drafts, a club
        // repeats in about 57% of them and appears three times in 0.6%.
        // Neither number may collapse to zero.
        val random = Random(7)
        var repeated = 0
        val drafts = 4000
        repeat(drafts) {
            val usedClub = IntArray(20)
            val usedExact = mutableMapOf<Pair<Int, Int>, Int>()
            var sawRepeat = false
            repeat(11) {
                val pool = (0 until 20).flatMap { c -> (0 until 5).map { s -> c to s } }
                val weights = pool.map { (c, s) ->
                    spinWeight(usedClub[c], usedExact[c to s] ?: 0)
                }
                val (club, season) = pool[weightedIndex(weights, random.nextDouble())]
                if (usedClub[club] > 0) sawRepeat = true
                usedClub[club]++
                usedExact[club to season] = (usedExact[club to season] ?: 0) + 1
            }
            if (sawRepeat) repeated++
        }
        val share = repeated / drafts.toDouble()
        println("a club repeated in ${"%.1f".format(share * 100)}% of drafts (measured 56.5%)")
        assertTrue("repeats vanished entirely: $share", share > 0.35)
        assertTrue("repeats are still the norm: $share", share < 0.75)
    }
}
