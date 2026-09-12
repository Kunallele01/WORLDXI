package com.dreamxi.app

import com.dreamxi.app.sim.RealFixture
import com.dreamxi.app.sim.SeasonProjector
import com.dreamxi.app.sim.SeasonSimulator
import com.dreamxi.app.sim.SimPlayer
import com.dreamxi.app.sim.SimSlot
import com.dreamxi.app.sim.SimTeam
import com.dreamxi.app.sim.SquadProfile
import com.dreamxi.app.sim.TeamStrength
import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an XI is worth over many seasons, rather than what happened to it once.
 *
 * The projection is shown to the user BEFORE kick-off, so it is a promise the
 * engine then has to keep: if it says 18% and the same XI wins the league half
 * the time, the number was a lie. These tests mostly check that the projection
 * and the season it precedes agree with each other.
 */
class SeasonProjectionTest {

    private fun club(id: String, ovr: Int, user: Boolean = false): SimTeam {
        val xi = listOf(SimSlot("GK", SimPlayer("$id GK", "GK", 0.0, ovr, 69.0))) +
            listOf("CB", "CB", "FB", "FB", "DM", "CM", "CM", "Winger", "Winger", "ST")
                .map { SimSlot(it, SimPlayer("$id $it", it, 0.12, ovr, goals90 = 0.15)) }
        return SimTeam(
            id = id, name = "Club $id", season = "2023/24",
            rating = TeamStrength.rate(xi), isUserTeam = user, xi = xi,
            squad = SquadProfile.fromXi(xi),
        )
    }

    private val teams = (1..20).map { club("c$it", 74 + (it * 6) / 10) }

    private fun realSeason(): List<RealFixture> {
        val rng = Random(4242)
        val out = mutableListOf<RealFixture>()
        var matchday = 1
        for (i in teams.indices) {
            for (j in teams.indices) {
                if (i == j) continue
                out += RealFixture(
                    (matchday++ % 38) + 1, teams[i].id, teams[j].id,
                    rng.nextInt(0, 4), rng.nextInt(0, 4),
                )
            }
        }
        return out
    }

    private fun project(ovr: Int, seed: Long = 3, runs: Int = 400) = SeasonProjector.project(
        real = realSeason(),
        teams = teams,
        user = club("dream-xi", ovr, user = true),
        replacedTeamId = "c1",
        seed = seed,
        runs = runs,
    )!!

    @Test
    fun `the probabilities are a distribution`() {
        val p = project(84)
        val total = p.positionCounts.values.sum()
        assertEquals("every run must land somewhere", p.runs, total)
        assertTrue("positions must be inside the league", p.positionCounts.keys.all { it in 1..20 })
        val sum = (1..20).sumOf { p.chanceOf(it) }
        assertEquals("chances must sum to one", 1.0, sum, 1e-9)
    }

    @Test
    fun `expected position sits inside the distribution it came from`() {
        val p = project(84)
        val occupied = p.positionCounts.keys
        println(
            "expected finish ${"%.2f".format(p.expectedPosition)}, " +
                "title ${"%.1f".format(p.titleChance * 100)}%, " +
                "top four ${"%.1f".format(p.chanceOfTop(4) * 100)}%, " +
                "expected points ${"%.1f".format(p.expectedPoints)}",
        )
        assertTrue(p.expectedPosition >= occupied.min().toDouble())
        assertTrue(p.expectedPosition <= occupied.max().toDouble())
    }

    @Test
    fun `a stronger XI is projected higher than a weaker one`() {
        val weak = project(70)
        val strong = project(92)
        println(
            "weak XI expected ${"%.2f".format(weak.expectedPosition)}, " +
                "strong XI expected ${"%.2f".format(strong.expectedPosition)}",
        )
        assertTrue(strong.expectedPosition < weak.expectedPosition)
        assertTrue("a 92-rated XI should usually win it", strong.titleChance > 0.5)
        assertTrue("a 70-rated XI should hardly ever win it", weak.titleChance < 0.1)
        assertTrue("a 70-rated XI should be in relegation trouble", weak.relegationChance > 0.1)
    }

    @Test
    fun `top four is at least as likely as the title`() {
        // Nesting that must hold or the readout contradicts itself on screen.
        val p = project(86)
        assertTrue(p.chanceOfTop(4) >= p.titleChance)
        assertTrue(p.chanceOfTop(10) >= p.chanceOfTop(4))
        assertTrue(p.chanceOfTop(20) >= p.chanceOfTop(10))
    }

    @Test
    fun `the projection agrees with the season it precedes`() {
        // The real test of honesty. Run the actual counterfactual over many
        // seeds and check the finishing positions match what was projected.
        val user = club("dream-xi", 84, user = true)
        val real = realSeason()
        val projection = project(84, runs = 600)

        val actual = HashMap<Int, Int>()
        val trials = 300
        repeat(trials) { seed ->
            val season = SeasonSimulator.playCounterfactual(real, teams, user, "c1", seed.toLong())
            val position = season.userRow!!.position
            actual[position] = (actual[position] ?: 0) + 1
        }
        val actualMean = actual.entries.sumOf { it.key * it.value }.toDouble() / trials
        println(
            "projected mean finish ${"%.2f".format(projection.expectedPosition)}, " +
                "full-simulation mean ${"%.2f".format(actualMean)}",
        )
        assertTrue(
            "projection ${projection.expectedPosition} vs reality $actualMean",
            abs(projection.expectedPosition - actualMean) < 1.0,
        )
    }

    @Test
    fun `the shown band is contiguous`() {
        // The display fault: taking the most probable positions and sorting
        // them produced readouts like 2nd, 5th, 6th, 7th, from which a reader
        // can only conclude that third and fourth are impossible.
        val p = project(84)
        val band = p.likelyBand(5)
        println("band: " + band.joinToString { "${it.first} ${"%.0f".format(it.second * 100)}%" })
        assertTrue("band should not be empty", band.isNotEmpty())
        band.zipWithNext { a, b ->
            assertEquals("a gap appeared inside the band", a.first + 1, b.first)
        }
        assertTrue("band longer than asked for", band.size <= 5)
    }

    @Test
    fun `the band is centred on the most likely finish`() {
        val p = project(84)
        val band = p.likelyBand(5)
        val mode = p.positionCounts.maxByOrNull { it.value }!!.key
        assertTrue("the most likely place must be shown", band.any { it.first == mode })
    }

    @Test
    fun `the band stays inside the league`() {
        for (ovr in listOf(60, 75, 95)) {
            val band = project(ovr).likelyBand(5)
            assertTrue("positions below first", band.all { it.first >= 1 })
            assertTrue("positions past last", band.all { it.first <= 20 })
        }
    }

    @Test
    fun `the same seed projects the same numbers`() {
        // The user is shown this before kick-off and again at the end. It must
        // not have changed in between.
        val a = project(84, seed = 12)
        val b = project(84, seed = 12)
        assertEquals(a.positionCounts, b.positionCounts)
        assertEquals(a.expectedPosition, b.expectedPosition, 1e-9)
    }

    @Test
    fun `relegation means the bottom three`() {
        val p = project(64)
        val expected = (p.leagueSize - 2..p.leagueSize).sumOf { p.chanceOf(it) }
        assertEquals(expected, p.relegationChance, 1e-9)
        assertEquals(20, p.leagueSize)
    }

    @Test
    fun `a thousand seasons is quick enough to hide behind the draw`() {
        // The draw animation runs for about four seconds, which is the budget.
        val start = System.nanoTime()
        project(84, runs = 1000)
        val ms = (System.nanoTime() - start) / 1_000_000
        println("1000 projected seasons in ${ms}ms")
        assertTrue("took ${ms}ms, which will not hide behind the draw", ms < 2000)
    }
}
