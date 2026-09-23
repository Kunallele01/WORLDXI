package com.dreamxi.app

import com.dreamxi.app.sim.worldcup.GROUP_ROUND
import com.dreamxi.app.sim.worldcup.WcTournamentSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * With nobody replaced, a World Cup must come back EXACTLY as it happened.
 *
 * This is the test that makes the counterfactual trustworthy. Every other run
 * differs from reality only where the user's side changed something, and that
 * is only true if the machinery around him — group tables, the generated
 * brackets, 2026's third-place table, the cascade that keeps real ties real —
 * reproduces history when he changes nothing. Any fault in those shows up here
 * as a real knockout tie that failed to happen.
 */
class WcTournamentReplayTest {

    @Test
    fun `every real tournament replays exactly with nobody replaced`() {
        for (year in WcFixtures.years) {
            val data = WcFixtures.tournaments.getValue(year)
            val result = WcTournamentSimulator.play(data, user = null, replacedId = null, seed = 42L)

            assertEquals("$year: match count", data.matches.size, result.matches.size)
            assertFalse("$year: nothing may be simulated", result.matches.any { it.simulated })

            for (real in data.matches) {
                val played = result.matches.filter {
                    it.round == real.round && it.homeId == real.homeId && it.awayId == real.awayId && it.date == real.date
                }
                assertEquals("$year ${real.round} ${real.homeId} v ${real.awayId} should occur once", 1, played.size)
                assertEquals("$year ${real.homeId} v ${real.awayId}", real.homeGoals, played[0].score.homeGoals)
                assertEquals("$year ${real.homeId} v ${real.awayId}", real.awayGoals, played[0].score.awayGoals)
                if (WcFixtures.isKnockout(real)) {
                    assertEquals("$year ${real.round} winner", WcFixtures.realWinner(real), played[0].winnerId)
                }
            }

            for ((letter, rows) in result.tables) {
                val real = data.entries.filter { it.group == letter }.sortedBy { it.realPosition }.map { it.id }
                assertEquals("$year group $letter order", real, rows.map { it.teamId })
            }

            val final = data.matches.single { it.round == "Final" }
            assertEquals("$year champion", WcFixtures.realWinner(final), result.championId)
            println("$year: replayed ${result.matches.size} matches, champion ${result.championId}")
        }
    }

    @Test
    fun `2026's replay sends the real eight third-placed teams through`() {
        val data = WcFixtures.tournaments.getValue(2026)
        val result = WcTournamentSimulator.play(data, null, null, seed = 7L)
        val realThirds = data.entries
            .filter { it.realPosition == 3 }
            .filter { e -> data.matches.any { it.round != GROUP_ROUND && (it.homeId == e.id || it.awayId == e.id) } }
            .map { it.id }
            .toSet()
        assertEquals(8, result.qualifiedThirds.size)
        assertEquals(realThirds, result.qualifiedThirds.toSet())
    }

    @Test
    fun `real matches carry their real scorers and extra time`() {
        val data = WcFixtures.tournaments.getValue(2022)
        val result = WcTournamentSimulator.play(data, null, null, seed = 1L)
        val final = result.matches.single { it.round == "Final" }
        assertEquals(listOf(23, 36, 80, 81, 108, 118), final.goals.map { it.minute }.sorted())
        assertTrue("the 2022 final went to extra time", final.score.extraTime)
        assertEquals(1, final.score.homeExtra)
        assertEquals(1, final.score.awayExtra)
        assertEquals(4, final.score.shootout?.homeScore)
        assertEquals("Argentina", result.championId)
        val top = result.topScorers().first()
        assertEquals("Mbappé", top.first.first)
        assertEquals(8, top.second)
    }
}
