package com.dreamxi.app

import com.dreamxi.app.sim.SeasonSimulator
import com.dreamxi.app.sim.SimPlayer
import com.dreamxi.app.sim.SimSlot
import com.dreamxi.app.sim.SimTeam
import com.dreamxi.app.sim.SquadProfile
import com.dreamxi.app.sim.TeamStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A season has to be structurally right before its results mean anything. A
 * table that adds up wrong, or a fixture list where a side plays twice in a
 * week, would undermine the engine no matter how well calibrated it is.
 */
class SeasonSimulatorTest {

    private fun team(id: String, ovr: Int, npxg: Double, user: Boolean = false): SimTeam {
        val xi = listOf(SimSlot("GK", SimPlayer("K", "GK", 0.0, ovr, 68.7))) +
            listOf("CB", "CB", "FB", "FB", "DM", "CM", "CM", "Winger", "ST", "Winger")
                .map { SimSlot(it, SimPlayer("P", it, npxg, ovr)) }
        return SimTeam(
            id = id, name = "Club $id", season = "2023/24",
            rating = TeamStrength.rate(xi), isUserTeam = user, xi = xi,
            squad = SquadProfile.fromXi(xi),
        )
    }

    private fun league(size: Int) = (1..size).map {
        // A spread of quality, so the table has something to sort.
        team("t$it", ovr = 68 + it, npxg = 0.05 + it * 0.01)
    }

    @Test
    fun `every side plays every other twice, once at each ground`() {
        val teams = league(20)
        val season = SeasonSimulator.play(teams, seed = 1)

        assertEquals("a 20-team league is 380 matches", 380, season.fixtures.size)
        for (row in season.table) {
            assertEquals("${row.team.name} played ${row.played}", 38, row.played)
        }
        // Each ordered pairing exactly once: A home to B, and B home to A.
        val pairings = season.fixtures.map { it.home.id to it.away.id }
        assertEquals(pairings.size, pairings.toSet().size)
        for ((home, away) in pairings) {
            assertTrue("$away never hosted $home", (away to home) in pairings.toSet())
        }
    }

    @Test
    fun `no side plays twice on the same matchday`() {
        // The reason for the circle method. A shuffled fixture list produces
        // the same final table but a season that reads as broken.
        val season = SeasonSimulator.play(league(20), seed = 4)
        for ((_, matches) in season.fixtures.groupBy { it.matchday }) {
            val appearing = matches.flatMap { listOf(it.home.id, it.away.id) }
            assertEquals("a side appears twice on one matchday", appearing.size, appearing.toSet().size)
            assertEquals("a 20-team matchday is 10 games", 10, matches.size)
        }
    }

    @Test
    fun `an odd-sized league still completes`() {
        // Reachable in real data: a season that loaded 19 usable clubs, or a
        // user side replacing nobody.
        val season = SeasonSimulator.play(league(19), seed = 5)
        for (row in season.table) {
            assertEquals("${row.team.name} played ${row.played}", 36, row.played)
        }
    }

    @Test
    fun `the table adds up`() {
        val season = SeasonSimulator.play(league(20), seed = 2)
        for (row in season.table) {
            assertEquals("W+D+L must equal played", row.played, row.won + row.drawn + row.lost)
            assertEquals("points must follow results", row.won * 3 + row.drawn, row.points)
        }
        // Goals scored across the league must equal goals conceded across it.
        assertEquals(season.table.sumOf { it.goalsFor }, season.table.sumOf { it.goalsAgainst })
        // And every match awards 2 or 3 points, never more or fewer.
        val total = season.table.sumOf { it.points }
        assertTrue("league total was $total", total in (380 * 2)..(380 * 3))
    }

    @Test
    fun `the table is sorted the way football sorts it`() {
        val season = SeasonSimulator.play(league(20), seed = 3)
        season.table.zipWithNext { above, below ->
            assertTrue(
                "${above.team.name} placed above ${below.team.name} wrongly",
                above.points > below.points ||
                    (above.points == below.points && above.goalDifference > below.goalDifference) ||
                    (above.points == below.points && above.goalDifference == below.goalDifference &&
                        above.goalsFor >= below.goalsFor),
            )
        }
        assertEquals(1, season.table.first().position)
        assertEquals(20, season.table.last().position)
    }

    @Test
    fun `the same seed replays the same season`() {
        // The user must be able to come back to a run and find it unchanged.
        val teams = league(20)
        val a = SeasonSimulator.play(teams, seed = 99)
        val b = SeasonSimulator.play(teams, seed = 99)
        assertEquals(a.table.map { it.team.id to it.points }, b.table.map { it.team.id to it.points })
        assertEquals(
            a.fixtures.map { it.result.toString() },
            b.fixtures.map { it.result.toString() },
        )
    }

    @Test
    fun `a different seed gives a different season`() {
        val teams = league(20)
        val a = SeasonSimulator.play(teams, seed = 1)
        val b = SeasonSimulator.play(teams, seed = 2)
        assertTrue(
            "two seeds produced identical results",
            a.fixtures.map { it.result.toString() } != b.fixtures.map { it.result.toString() },
        )
    }

    @Test
    fun `the strongest side usually wins the league but not always`() {
        // Over many seasons the best team should win most of them and lose
        // some. A model that never sprang a surprise would make the draft
        // pointless; one that crowned a random side would make it arbitrary.
        val teams = league(20)
        val best = teams.last().id
        var titles = 0
        repeat(60) { seed ->
            if (SeasonSimulator.play(teams, seed.toLong()).table.first().team.id == best) titles++
        }
        assertTrue("best side won $titles/60", titles in 20..57)
    }

    @Test
    fun `the user's side is findable in the table`() {
        val teams = league(19) + team("mine", ovr = 88, npxg = 0.30, user = true)
        val season = SeasonSimulator.play(teams, seed = 8)
        val row = season.userRow
        assertTrue("user row missing", row != null)
        assertEquals(38, row!!.played)
        assertEquals(38, season.fixturesFor(row.team).size)

        // Where a strong side FINISHES is a probabilistic claim, so it is
        // checked across seeds rather than pinned to one. Asserting it on a
        // single season made this test fail whenever an unrelated change
        // shifted the random stream, which says nothing about the engine.
        val finishes = (1..25).map {
            SeasonSimulator.play(teams, it.toLong()).userRow!!.position
        }
        val mean = finishes.average()
        println("a title-worthy XI averaged ${"%.1f".format(mean)} across 25 seasons")
        assertTrue("averaged $mean", mean <= 3.0)
    }

    @Test
    fun `a duplicated team is rejected rather than silently played twice`() {
        val teams = league(4)
        val result = runCatching { SeasonSimulator.play(teams + teams.first(), seed = 1) }
        assertTrue("duplicate ids should not be playable", result.isFailure)
    }
}
