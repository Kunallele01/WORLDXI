package com.dreamxi.app

import com.dreamxi.app.sim.GoalMinutes
import com.dreamxi.app.sim.MatchReporter
import com.dreamxi.app.sim.MatchResult
import com.dreamxi.app.sim.ScorerWeight
import com.dreamxi.app.sim.SimModel
import com.dreamxi.app.sim.SquadProfile
import java.io.File
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The late-goal lean, against the real goals it was measured from.
 *
 * Goal minutes used to be drawn flat across the ninety — honestly, since no
 * club season in the database records a minute. The World Cup scrape brought
 * back 1,084 real ones, and etl/goal_minutes.py fits them into GoalMinutes.kt.
 * This holds the shipped curve to the data file the fit read, so a regenerated
 * curve that drifts from the source, or an engine that stops using it, fails
 * here rather than quietly going flat again.
 */
class GoalMinuteShapeTest {

    private val bands = listOf(1..15, 16..30, 31..45, 46..60, 61..75, 76..90)

    private fun bandOf(minute: Int) = bands.indexOfFirst { minute in it }

    /** The real goals, straight off the World Cup export the fit was measured on. */
    private fun realGoals(): List<Pair<Int, Int>> =
        File("src/test/resources/wc/goals.tsv").readLines()
            .drop(1)
            .mapNotNull { line ->
                val cols = line.split("\t")
                val minute = cols.getOrNull(6)?.toIntOrNull() ?: return@mapNotNull null
                if (minute > 90) null else minute to (cols.getOrNull(7)?.toIntOrNull() ?: 0)
            }

    private fun shares(minutes: List<Int>): List<Double> {
        val counts = IntArray(bands.size)
        for (m in minutes) counts[bandOf(m)]++
        return counts.map { it * 100.0 / minutes.size }
    }

    @Test
    fun `the shipped curve reproduces the real distribution`() {
        val real = realGoals().map { it.first }
        assertTrue("the World Cup goals are missing", real.size > 1_000)

        val random = Random(4)
        val drawn = List(400_000) { GoalMinutes.draw(random).minute }

        val realShares = shares(real)
        val drawnShares = shares(drawn)
        println("band        real   drawn")
        for (i in bands.indices) {
            println("%-8s %6.1f%% %6.1f%%".format("${bands[i].first}-${bands[i].last}", realShares[i], drawnShares[i]))
        }
        for (i in bands.indices) {
            assertEquals("band ${bands[i]}", realShares[i], drawnShares[i], 1.5)
        }
    }

    @Test
    fun `the stoppage-time lump survives into the draw`() {
        val random = Random(9)
        val drawn = List(200_000) { GoalMinutes.draw(random) }
        val atNinety = drawn.count { it.minute == 90 } * 100.0 / drawn.size
        val atFortyFive = drawn.count { it.minute == 45 } * 100.0 / drawn.size
        println("minute 90: ${"%.1f".format(atNinety)}%, minute 45: ${"%.1f".format(atFortyFive)}%")
        // Measured: 9.9% and 3.0%. A flat draw would put 1.1% in each, so this
        // is the assertion that the 90th-minute winner exists at all.
        assertEquals(9.9, atNinety, 0.6)
        assertEquals(3.0, atFortyFive, 0.5)
    }

    /**
     * Added time is carried rather than rounded away. Printing every late goal
     * as a flat "90'" is what made two goals in one match look like a duplicate.
     */
    @Test
    fun `goals on the whistle minutes carry added time, and no others do`() {
        val random = Random(21)
        val drawn = List(200_000) { GoalMinutes.draw(random) }
        assertTrue(
            "added time outside the whistle minutes",
            drawn.none { it.stoppage != null && it.minute != 45 && it.minute != 90 },
        )
        val real = realGoals()
        for (mark in listOf(45, 90)) {
            val realRate = real.filter { it.first == mark }.let { at ->
                at.count { it.second > 0 } * 100.0 / at.size
            }
            val ours = drawn.filter { it.minute == mark }.let { at ->
                at.count { it.stoppage != null } * 100.0 / at.size
            }
            println("minute $mark in added time: real ${"%.0f".format(realRate)}%, ours ${"%.0f".format(ours)}%")
            assertEquals("minute $mark", realRate, ours, 4.0)
        }
        // 90+13 is the longest in the data; nothing may exceed what was measured.
        assertTrue(drawn.mapNotNull { it.stoppage }.max() <= 13)
        assertTrue(drawn.mapNotNull { it.stoppage }.min() >= 1)
        // played orders added time properly, which is what the scoresheet sorts on.
        assertEquals(94, GoalMinutes.Moment(90, 4).played)
    }

    @Test
    fun `the second half outscores the first, as it really does`() {
        val random = Random(11)
        val drawn = List(200_000) { GoalMinutes.draw(random).minute }
        val second = drawn.count { it > 45 } * 100.0 / drawn.size
        val real = realGoals().map { it.first }
        val realSecond = real.count { it > 45 } * 100.0 / real.size
        println("second half: real ${"%.1f".format(realSecond)}%, drawn ${"%.1f".format(second)}%")
        assertEquals(realSecond, second, 1.5)
    }

    @Test
    fun `a substitute still cannot score before he came on, and still leans late`() {
        val entry = SimModel.SUB_ENTRY_EARLIEST
        val random = Random(13)
        val drawn = List(100_000) { GoalMinutes.draw(random, from = entry).minute }
        assertTrue("drew a minute before he was on", drawn.min() >= entry)
        assertTrue(drawn.max() <= 90)
        // Truncated, not redrawn: within his window the tilt is still there, so
        // the back half of it outscores the front half.
        val mid = (entry + 90) / 2
        val late = drawn.count { it > mid }
        println("substitute window $entry-90: ${late * 100.0 / drawn.size}% in the back half")
        assertTrue("a substitute's goals came out flat", late > drawn.size / 2)
    }

    /**
     * Goals repel each other. Drawn independently they landed on top of one
     * another — two in the same minute, three inside five — which is what the
     * user reported seeing (44 and 46, then 63 and 64, then 78, 80 and 82).
     */
    @Test
    fun `two goals in one match are never closer than the measured gap`() {
        val random = Random(23)
        var pairs = 0
        var within2 = 0
        repeat(4_000) {
            // Match sizes in the spirit of the real ones: most matches 1-4 goals.
            val count = listOf(2, 2, 3, 3, 4, 5, 6)[random.nextInt(7)]
            val taken = mutableListOf<Int>()
            repeat(count) {
                val moment = GoalMinutes.draw(random, taken = taken)
                taken += moment.played
            }
            for (i in taken.indices) {
                for (j in i + 1 until taken.size) {
                    pairs++
                    val gap = abs(taken[i] - taken[j])
                    assertTrue("two goals $gap apart", gap >= GoalMinutes.MIN_SEPARATION)
                    if (gap <= 2) within2++
                }
            }
        }
        val rate = within2 * 100.0 / pairs
        println("pairs within two minutes: ${"%.2f".format(rate)}% (real 1.82%, independent draws 5.2%)")
        assertTrue("still clustering like independent draws", rate < 3.0)
    }

    @Test
    fun `the match reporter uses the curve for goals and leaves bookings flat`() {
        val squad = SquadProfile(
            players = listOf(
                ScorerWeight("A Striker", goals = 1.0, assists = 0.5, yellows = 1.0, reds = 0.05, startRate = 1.0),
                ScorerWeight("A Midfielder", goals = 0.5, assists = 1.0, yellows = 1.5, reds = 0.05, startRate = 1.0),
            ),
            yellowsPerMatch = 1.8,
            redsPerMatch = 0.05,
        )
        val random = Random(17)
        val goals = mutableListOf<Int>()
        val cards = mutableListOf<Int>()
        var reports = 0
        repeat(20_000) {
            val events = MatchReporter.report(
                "us", squad, "them", squad,
                MatchResult(2, 1, 1.7, 1.2), random,
            )
            // Both sides draw against one list, so no two goals in the match —
            // whoever scored them — may share a moment.
            val played = events.goals.map { it.played }
            assertEquals("two goals at the same moment", played.size, played.toSet().size)
            reports++
            goals += events.goals.map { it.minute }
            cards += events.cards.map { it.minute }
        }
        val goalSecond = goals.count { it > 45 } * 100.0 / goals.size
        val cardSecond = cards.count { it > 45 } * 100.0 / cards.size
        println("$reports reports; goals ${"%.1f".format(goalSecond)}% second half, bookings ${"%.1f".format(cardSecond)}%")
        assertEquals("goals should carry the measured lean", 58.3, goalSecond, 2.0)
        // Bookings are deliberately NOT shaped: the scrape carries goals only.
        assertEquals("bookings should still be flat", 50.0, cardSecond, 2.0)
    }
}
