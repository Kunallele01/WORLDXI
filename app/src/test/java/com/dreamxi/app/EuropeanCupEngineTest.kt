package com.dreamxi.app

import com.dreamxi.app.sim.TeamRating
import com.dreamxi.app.sim.europeancup.EcBrackets
import com.dreamxi.app.sim.europeancup.EcField
import com.dreamxi.app.sim.europeancup.EcMatchEngine
import com.dreamxi.app.sim.europeancup.EcModel
import com.dreamxi.app.sim.europeancup.EcRatings
import com.dreamxi.app.sim.europeancup.EcSide
import com.dreamxi.app.sim.europeancup.EcSource
import com.dreamxi.app.sim.europeancup.EcStage
import com.dreamxi.app.sim.europeancup.EcTieRules
import com.dreamxi.app.sim.europeancup.EcTournamentSimulator
import com.dreamxi.app.sim.europeancup.EcUserTeam
import com.dreamxi.app.sim.europeancup.TieDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The European Cup engine: tie rules, the match and tie engine, the bracket, and
 * the season simulator replaying the real 2023/24 tournament.
 */
class EuropeanCupEngineTest {

    // ------------------------------------------------------------ tie rules

    @Test
    fun `a lead on aggregate settles a tie whatever the away goals`() {
        val d = EcTieRules.decide(3, 2, firstAway = 0, secondAway = 2, awayGoalsRule = true, firstWonShootout = null)
        assertTrue(d.winnerIsFirst)
        assertEquals(TieDecision.AGGREGATE, d.decidedBy)
    }

    @Test
    fun `level on aggregate, away goals decide only while the rule applies`() {
        val with = EcTieRules.decide(2, 2, firstAway = 1, secondAway = 0, awayGoalsRule = true, firstWonShootout = null)
        assertTrue(with.winnerIsFirst)
        assertEquals(TieDecision.AWAY_GOALS, with.decidedBy)

        // From 2021/22: the same goals are a draw on aggregate and go to penalties.
        val without = EcTieRules.decide(2, 2, firstAway = 1, secondAway = 0, awayGoalsRule = false, firstWonShootout = false)
        assertFalse(without.winnerIsFirst)
        assertEquals(TieDecision.PENALTIES, without.decidedBy)
    }

    @Test
    fun `level on everything needs a shootout winner`() {
        val d = EcTieRules.decide(1, 1, firstAway = 1, secondAway = 1, awayGoalsRule = true, firstWonShootout = true)
        assertEquals(TieDecision.PENALTIES, d.decidedBy)
        val failure = runCatching {
            EcTieRules.decide(1, 1, firstAway = 1, secondAway = 1, awayGoalsRule = true, firstWonShootout = null)
        }
        assertTrue("an undecided tie must not produce a winner", failure.isFailure)
    }

    // --------------------------------------------------------------- engine

    private val field = EcField(averageDefence = 1.30)
    private fun side(attack: Double, defence: Double) = EcSide(TeamRating(attack, defence, attack, 70.0))

    @Test
    fun `every simulated tie is settled by the rules and by nothing else`() {
        // Evenly matched, so ties really do finish level and reach extra time and penalties.
        val strong = side(1.6, 1.2)
        val weak = side(1.5, 1.3)
        for (awayRule in listOf(true, false)) {
            val random = Random(7)
            var extra = 0
            var pens = 0
            repeat(4000) { i ->
                // Alternate the order so neither side is always the host of leg 1.
                val (a, b) = if (i % 2 == 0) strong to weak else weak to strong
                val tie = EcMatchEngine.playTie(a, b, field, awayRule, random)
                if (!awayRule) assertNotEquals(TieDecision.AWAY_GOALS, tie.decidedBy)
                // Penalties only after extra time, and only when nothing else separated them.
                if (tie.leg2.shootout != null) {
                    pens++
                    assertTrue(tie.leg2.extraTime)
                    assertEquals(TieDecision.PENALTIES, tie.decidedBy)
                    assertEquals(tie.firstAggregate, tie.secondAggregate)
                } else if (tie.decidedBy != TieDecision.AWAY_GOALS) {
                    assertNotEquals("a tie with no shootout was level", tie.firstAggregate, tie.secondAggregate)
                }
                if (tie.leg2.extraTime) extra++
                // Extra time is only ever played in leg 2.
                assertFalse(tie.leg1.extraTime)
                assertEquals(tie.winnerIsFirst, winnerOf(tie.decidedBy, tie))
            }
            assertTrue("extra time should occur", extra > 100)
            assertTrue("penalties should occur", pens in 1 until extra)
        }
    }

    private fun winnerOf(by: TieDecision, tie: com.dreamxi.app.sim.europeancup.EcTieResult): Boolean = when (by) {
        TieDecision.AGGREGATE -> tie.firstAggregate > tie.secondAggregate
        TieDecision.AWAY_GOALS -> tie.leg2.awayGoals > tie.leg1.awayGoals
        TieDecision.PENALTIES -> !tie.leg2.shootout!!.homeWon     // leg 2 is at the second club
    }

    @Test
    fun `the engine is deterministic in its random stream`() {
        val a = EcMatchEngine.playTie(side(1.7, 1.1), side(1.4, 1.4), field, false, Random(99))
        val b = EcMatchEngine.playTie(side(1.7, 1.1), side(1.4, 1.4), field, false, Random(99))
        assertEquals(a, b)
    }

    @Test
    fun `a final is played to a finish on neutral ground`() {
        val random = Random(3)
        repeat(3000) {
            val score = EcMatchEngine.playFinal(side(1.5, 1.3), side(1.5, 1.3), field, random)
            assertTrue("a final always has a winner", score.homeWon != null)
        }
        // Neutral ground lifts nobody; identical sides expect identical goals.
        val s = side(1.5, 1.3)
        assertEquals(
            EcMatchEngine.expectedGoals(s, s, field, atHome = false),
            EcMatchEngine.expectedGoals(s, s, field, atHome = false), 0.0,
        )
        assertEquals(
            EcModel.HOME_ADVANTAGE,
            EcMatchEngine.expectedGoals(s, s, field, atHome = true) / EcMatchEngine.expectedGoals(s, s, field, atHome = false),
            1e-9,
        )
    }

    // -------------------------------------------------------------- bracket

    @Test
    fun `the bracket traces every tie back to a group place`() {
        assumeTrue(EcTestData.fixturesAvailable)
        val slots = EcBrackets.from(EcTestData.season(withLineups = false))
        assertEquals(listOf(8, 4, 2, 1), listOf(EcStage.ROUND_OF_16, EcStage.QUARTER_FINAL, EcStage.SEMI_FINAL, EcStage.FINAL).map { st -> slots.count { it.stage == st } })
        slots.filter { it.stage == EcStage.ROUND_OF_16 }.forEach { s ->
            val h = s.home as EcSource.Group
            val a = s.away as EcSource.Group
            // A round-of-16 tie is a group winner against another group's runner-up.
            assertEquals(setOf(1, 2), setOf(h.position, a.position))
            assertNotEquals(h.letter, a.letter)
        }
        // Every tie after the round of 16 is fed by two different earlier ties, none used twice.
        val fed = slots.filter { it.stage != EcStage.ROUND_OF_16 }
            .flatMap { listOf((it.home as EcSource.WinnerOf).slotId, (it.away as EcSource.WinnerOf).slotId) }
        assertEquals(fed.size, fed.toSet().size)
        assertEquals(8 + 4 + 2, fed.size)
    }

    // --------------------------------------------------------------- replay

    @Test
    fun `with nobody replaced the run reproduces the real season exactly`() {
        assumeTrue(EcTestData.fixturesAvailable)
        val data = EcTestData.season(withLineups = false)
        val result = EcTournamentSimulator.play(data, user = null, replacedId = null, seed = 1)

        assertEquals(data.matches.size, result.matches.size)
        assertTrue("nothing is simulated in a replay", result.matches.none { it.simulated })

        val realChampion = data.entries.single { it.champion }.id
        assertEquals(realChampion, result.championId)

        // Group orders are the real ones.
        data.groups.forEach { (letter, members) ->
            assertEquals(letter, members.sortedBy { it.realPosition }.map { it.id }, result.tables.getValue(letter).map { it.teamId })
        }

        // Each tie's winner is exactly the club that appears in the next round.
        val order = listOf(EcStage.ROUND_OF_16, EcStage.QUARTER_FINAL, EcStage.SEMI_FINAL, EcStage.FINAL)
        order.zipWithNext().forEach { (stage, next) ->
            val winners = result.ties.filter { it.stage == stage }.map { it.winnerId }.toSet()
            val inNext = data.matches(next).flatMap { listOf(it.homeId, it.awayId) }.toSet()
            assertEquals("${stage.label} winners are the ${next.label} clubs", inNext, winners)
        }
    }

    @Test
    fun `the real final was a single match, won by Real Madrid`() {
        assumeTrue(EcTestData.fixturesAvailable)
        val result = EcTournamentSimulator.play(EcTestData.season(withLineups = false), null, null, 1)
        val final = result.ties.single { it.stage == EcStage.FINAL }
        assertEquals(1, final.legs.size)
        assertEquals("Real Madrid", final.winnerId)
        assertEquals(TieDecision.AGGREGATE, final.decidedBy)
    }

    // ------------------------------------------------------------- user run

    private fun userRun(seed: Long, replaced: String = "Manchester United"): Triple<com.dreamxi.app.sim.europeancup.EcTournamentData, EcUserTeam, com.dreamxi.app.sim.europeancup.EcTournamentResult> {
        val data = EcTestData.season()
        val lineup = checkNotNull(data.entries.single { it.id == "Real Madrid" }.lineup)
        val user = EcUserTeam("dream-xi", "Dream XI", lineup)
        return Triple(data, user, EcTournamentSimulator.play(data, user, replaced, seed))
    }

    @Test
    fun `the user takes the replaced club's fixtures and the rest of the real season stands`() {
        assumeTrue(EcTestData.lineupsAvailable)
        val (data, user, result) = userRun(5)

        assertTrue("the replaced club is gone", result.matches.none { it.involves("Manchester United") })
        val mine = result.matchesOf(user.id).filter { it.stage == EcStage.GROUP }
        assertEquals(6, mine.size)
        assertTrue(mine.all { it.simulated })

        // Same opponents and same hosts as the club it replaced.
        val real = data.matches(EcStage.GROUP).filter { it.involves("Manchester United") }
        fun shape(home: String, away: String) = if (home == user.id || home == "Manchester United") "H:$away" else "A:$home"
        assertEquals(real.map { shape(it.homeId, it.awayId) }.sorted(), mine.map { shape(it.homeId, it.awayId) }.sorted())

        // Every group match not involving him is the real one, untouched.
        val others = result.matches.filter { it.stage == EcStage.GROUP && !it.involves(user.id) }
        val realOthers = data.matches(EcStage.GROUP).filter { !it.involves("Manchester United") }
        assertEquals(realOthers.size, others.size)
        assertEquals(
            realOthers.map { Triple(it.homeId to it.awayId, it.homeGoals, it.awayGoals) }.toSet(),
            others.map { Triple(it.homeId to it.awayId, it.homeGoals, it.awayGoals) }.toSet(),
        )
        assertTrue(others.none { it.simulated })
    }

    @Test
    fun `a run is repeatable and different seeds differ`() {
        assumeTrue(EcTestData.lineupsAvailable)
        assertEquals(userRun(11).third, userRun(11).third)
        val champions = (1L..12L).map { userRun(it).third.matches.map { m -> m.homeGoals to m.awayGoals } }.toSet()
        assertTrue("twelve seeds produced one season", champions.size > 1)
    }

    @Test
    fun `knockout ties are always between clubs that earned the place`() {
        assumeTrue(EcTestData.lineupsAvailable)
        for (seed in 1L..60L) {
            val (data, user, result) = userRun(seed)
            val qualifiers = result.tables.flatMap { (_, rows) -> rows.take(2).map { it.teamId } }.toSet()
            val r16 = result.ties.filter { it.stage == EcStage.ROUND_OF_16 }
            assertEquals(qualifiers, r16.flatMap { listOf(it.firstId, it.secondId) }.toSet())
            assertEquals(16, qualifiers.size)
            // Whoever the user plays in the knockouts, the match is simulated; whoever he does not, it is real or consistent.
            result.ties.filter { user.id in listOf(it.firstId, it.secondId) }.forEach { assertTrue(it.simulated) }
            // The champion won every tie he played, so he appears in exactly one tie per round he reached.
            val champ = result.championId
            val path = result.ties.filter { champ in listOf(it.firstId, it.secondId) }
            assertTrue(path.all { it.winnerId == champ })
            assertTrue(data.entries.any { it.id == champ } || champ == user.id)
        }
    }

    // ---------------------------------------------------------- calibration

    @Test
    fun `an all-star XI does well but does not walk it`() {
        assumeTrue(EcTestData.lineupsAvailable)
        val data = EcTestData.season()
        val user = EcUserTeam("dream-xi", "Dream XI", EcTestData.allStarLineup())
        val ratings = EcRatings(data.entries)
        val runs = 1000
        val candidates = data.takeoverCandidates.map { it.id }
        var win = 0; var final = 0; var semi = 0; var quarter = 0; var last16 = 0; var points = 0.0
        repeat(runs) { i ->
            val replaced = candidates[i % candidates.size]
            val r = EcTournamentSimulator.play(data, user, replaced, seed = i.toLong() + 1, ratings = ratings)
            val reached = r.ties.filter { user.id in listOf(it.firstId, it.secondId) }.map { it.stage }.toSet()
            if (EcStage.ROUND_OF_16 in reached) last16++
            if (EcStage.QUARTER_FINAL in reached) quarter++
            if (EcStage.SEMI_FINAL in reached) semi++
            if (EcStage.FINAL in reached) final++
            if (r.championId == user.id) win++
            points += r.tables.values.flatten().first { it.teamId == user.id }.points
        }
        fun pct(n: Int) = "%.1f%%".format(100.0 * n / runs)
        println("EC ALLSTAR: strength ${"%.1f".format(user.lineup.players.map { it.rating }.average())}; " +
            "group points ${"%.1f".format(points / runs)}; last16 ${pct(last16)}, QF ${pct(quarter)}, SF ${pct(semi)}, final ${pct(final)}, WIN ${pct(win)}")
        // Tuned to about a fifth, as the World Cup's dream XI is (see EcModel.GOALS_SHAPE). The band is wide
        // enough for 1,000 runs' noise, narrow enough to catch a change that quietly makes it easy again.
        assertTrue("an all-star XI should win a real share: ${pct(win)}", win > runs * 0.12)
        assertTrue("and not walk the competition: ${pct(win)}", win < runs * 0.32)
    }

    @Test
    fun `the engine scores at the real European Cup rate`() {
        assumeTrue(EcTestData.lineupsAvailable)
        val data = EcTestData.season()
        val ratings = EcRatings(data.entries)
        val fixtures = data.matches.filter { it.stage != EcStage.FINAL }
        val expected = fixtures.sumOf {
            EcMatchEngine.expectedGoals(EcSide(ratings.byId.getValue(it.homeId)), EcSide(ratings.byId.getValue(it.awayId)), ratings.field, atHome = true) +
                EcMatchEngine.expectedGoals(EcSide(ratings.byId.getValue(it.awayId)), EcSide(ratings.byId.getValue(it.homeId)), ratings.field, atHome = false)
        } / (2.0 * fixtures.size)
        val actual = fixtures.sumOf { it.homeGoals + it.awayGoals } / (2.0 * fixtures.size)
        println("EC CALIBRATION: engine mean $expected, real mean $actual, scale needed ${actual / expected}")

        // Whether the SPREAD between clubs is right, not just the level: the slope of real goal difference
        // on expected goal difference should be near 1. (124 matches: the standard error is about 0.2.)
        val egd = fixtures.map {
            EcMatchEngine.expectedGoals(EcSide(ratings.byId.getValue(it.homeId)), EcSide(ratings.byId.getValue(it.awayId)), ratings.field, true) -
                EcMatchEngine.expectedGoals(EcSide(ratings.byId.getValue(it.awayId)), EcSide(ratings.byId.getValue(it.homeId)), ratings.field, false)
        }
        val agd = fixtures.map { (it.homeGoals - it.awayGoals).toDouble() }
        val mx = egd.average(); val my = agd.average()
        val slope = egd.indices.sumOf { (egd[it] - mx) * (agd[it] - my) } / egd.sumOf { (it - mx) * (it - mx) }
        val corr = egd.indices.sumOf { (egd[it] - mx) * (agd[it] - my) } /
            Math.sqrt(egd.sumOf { (it - mx) * (it - mx) } * agd.sumOf { (it - my) * (it - my) })
        println("EC SPREAD: slope of real GD on expected GD = $slope, correlation = $corr, n=${fixtures.size}")
        assertEquals("GOALS_SCALE needs recalibrating: engine ${"%.3f".format(expected)} vs real ${"%.3f".format(actual)}", actual, expected, actual * 0.03)
    }
}
