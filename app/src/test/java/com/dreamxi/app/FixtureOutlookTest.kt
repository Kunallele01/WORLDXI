package com.dreamxi.app

import com.dreamxi.app.sim.FixtureOutlooks
import com.dreamxi.app.sim.FormResult
import com.dreamxi.app.sim.RealFixture
import com.dreamxi.app.sim.SeasonSimulator
import com.dreamxi.app.sim.SimPlayer
import com.dreamxi.app.sim.SimSlot
import com.dreamxi.app.sim.SimTeam
import com.dreamxi.app.sim.SquadProfile
import com.dreamxi.app.sim.TeamStrength
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fixtures ahead, as shown before they are played.
 *
 * The season is fully simulated before the user sees a single result, so the
 * governing rule here is that a preview must describe the fixture and never
 * leak the outcome. The rest is about the difficulty stars being informative
 * rather than decorative.
 */
class FixtureOutlookTest {

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
    private val user = club("dream-xi", 84, user = true)

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

    private val season = SeasonSimulator.playCounterfactual(realSeason(), teams, user, "c1", 5)
    private val thresholds = FixtureOutlooks.difficultyThresholds(season.fixtures, user)

    private fun upcoming(afterMatchday: Int, count: Int = 4) = FixtureOutlooks.next(
        fixtures = season.fixtures,
        team = user,
        table = SeasonSimulator.buildTable(
            teams.filterNot { it.id == "c1" } + user,
            season.fixtures.filter { it.matchday <= afterMatchday },
        ),
        afterMatchday = afterMatchday,
        thresholds = thresholds,
        count = count,
    )

    @Test
    fun `only fixtures still to come are offered`() {
        val outlooks = upcoming(afterMatchday = 12)
        assertEquals(4, outlooks.size)
        assertTrue("a played match was previewed", outlooks.all { it.matchday > 12 })
        // In matchday order, so a "run" reads as a run.
        assertEquals(outlooks.map { it.matchday }.sorted(), outlooks.map { it.matchday })
    }

    @Test
    fun `the opponent is never the user's own side`() {
        for (md in listOf(0, 5, 20, 37)) {
            for (o in upcoming(md)) {
                assertTrue("previewed a match against ourselves", o.opponent.id != user.id)
                assertTrue("previewed the club we replaced", o.opponent.id != "c1")
            }
        }
    }

    @Test
    fun `the season's end offers nothing rather than crashing`() {
        assertTrue(upcoming(afterMatchday = 38).isEmpty())
    }

    @Test
    fun `form is the opponent's real recent results, oldest first`() {
        val outlook = upcoming(afterMatchday = 20).first()
        val opponent = outlook.opponent
        val theirs = season.fixtures
            .filter { (it.home.id == opponent.id || it.away.id == opponent.id) && it.matchday <= 20 }
            .sortedBy { it.matchday }
            .takeLast(5)
        assertEquals("form should be five long by matchday 20", 5, outlook.opponentForm.size)
        val expected = theirs.map { f ->
            val gf = f.goalsFor(opponent)!!
            val ga = f.goalsAgainst(opponent)!!
            when {
                gf > ga -> FormResult.Win
                gf == ga -> FormResult.Draw
                else -> FormResult.Loss
            }
        }
        assertEquals(expected, outlook.opponentForm)
    }

    @Test
    fun `there is no form to show before a ball is kicked`() {
        assertTrue(upcoming(afterMatchday = 0).all { it.opponentForm.isEmpty() })
    }

    @Test
    fun `difficulty spreads across the whole scale over a season`() {
        // The reason difficulty is banded against the USER'S OWN fixtures
        // rather than absolute thresholds: a 90-rated XI finds nothing hard and
        // a poor one finds everything hard, so fixed bands would print the same
        // star count 38 times and say nothing.
        // Every one of the user's 38, taken in one pass. Sampling "the next
        // fixture at each matchday" instead would miss some, because this test
        // fixture crams several of a club's matches onto one matchday — real
        // calendars give each club exactly one per week.
        val levels = upcoming(afterMatchday = 0, count = 38).map { it.difficulty }
        val distinct = levels.toSet()
        println("difficulty levels used across a season: ${distinct.sorted()}")
        assertTrue("only $distinct appeared", distinct == setOf(1, 2, 3, 4, 5))
        assertTrue("levels must stay in 1..5", levels.all { it in 1..5 })
    }

    @Test
    fun `a harder fixture is worth fewer expected points`() {
        val all = upcoming(afterMatchday = 0, count = 38)
        val hardest = all.maxByOrNull { it.difficulty }!!
        val easiest = all.minByOrNull { it.difficulty }!!
        println(
            "hardest: ${hardest.opponent.name} ${"%.2f".format(hardest.expectedPoints)} xPts, " +
                "easiest: ${easiest.opponent.name} ${"%.2f".format(easiest.expectedPoints)} xPts",
        )
        assertTrue(
            "difficulty is inverted",
            hardest.expectedPoints < easiest.expectedPoints,
        )
    }

    @Test
    fun `expected points for a fixture are between zero and three`() {
        for (o in upcoming(afterMatchday = 0, count = 38)) {
            assertTrue("${o.expectedPoints} is not a points total", o.expectedPoints in 0.0..3.0)
        }
    }

    @Test
    fun `an away trip to a strong side is harder than hosting a weak one`() {
        val all = upcoming(afterMatchday = 0, count = 38)
        val awayAtBest = all.filter { !it.isHome }.minByOrNull { it.expectedPoints }!!
        val homeToWorst = all.filter { it.isHome }.maxByOrNull { it.expectedPoints }!!
        assertTrue(
            "away at ${awayAtBest.opponent.name} should beat home to ${homeToWorst.opponent.name}",
            awayAtBest.difficulty > homeToWorst.difficulty,
        )
    }
}
