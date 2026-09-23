package com.dreamxi.app

import com.dreamxi.app.sim.CardEvent
import com.dreamxi.app.sim.GoalEvent
import com.dreamxi.app.sim.MatchEvents
import com.dreamxi.app.sim.MatchReporter
import com.dreamxi.app.sim.MatchResult
import com.dreamxi.app.sim.ScorerWeight
import com.dreamxi.app.sim.SquadProfile
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scoresheet, as a match report rather than two team sheets.
 *
 * The reported failure: the opposition scored in the 5th and the user in the
 * 27th, and both appeared on the same line. Each side's goals were listed down
 * its own column, so the two lists advanced together and position on the page
 * meant nothing. [MatchEvents.inOrder] is what the screens read instead.
 */
class ScoresheetOrderTest {

    @Test
    fun `both sides' events come back in the order they happened`() {
        val events = MatchEvents(
            goals = listOf(
                GoalEvent(27, "us", "Ours", null),
                GoalEvent(5, "them", "Theirs", null),
                GoalEvent(62, "us", "Ours Again", "Someone"),
            ),
            cards = listOf(CardEvent(41, "them", "Booked", isRed = false)),
        )
        assertEquals(
            listOf(5 to "them", 27 to "us", 41 to "them", 62 to "us"),
            events.inOrder().map { it.minute to it.teamId },
        )
    }

    @Test
    fun `a goal comes before a booking given in the same minute`() {
        val events = MatchEvents(
            goals = listOf(GoalEvent(70, "us", "Scorer", null)),
            cards = listOf(CardEvent(70, "them", "Booked", isRed = false)),
        )
        assertTrue(events.inOrder().first() is GoalEvent)
    }

    @Test
    fun `nothing is dropped and nothing is invented`() {
        // Over real reports, not hand-built ones: every goal and card the
        // reporter produced must appear on the sheet exactly once.
        val squad = SquadProfile(
            players = listOf(
                ScorerWeight("A Striker", goals = 1.0, assists = 0.5, yellows = 1.0, reds = 0.1, startRate = 1.0),
                ScorerWeight("A Midfielder", goals = 0.4, assists = 1.0, yellows = 2.0, reds = 0.1, startRate = 0.8),
            ),
            yellowsPerMatch = 1.6,
            redsPerMatch = 0.1,
        )
        val random = Random(7)
        var lines = 0
        repeat(500) {
            val events = MatchReporter.report(
                "us", squad, "them", squad,
                MatchResult(2, 1, 1.8, 1.1), random,
            )
            val sheet = events.inOrder()
            assertEquals(events.goals.size + events.cards.size, sheet.size)
            assertEquals(events.goals.toSet(), sheet.filterIsInstance<GoalEvent>().toSet())
            assertEquals(events.cards.toSet(), sheet.filterIsInstance<CardEvent>().toSet())
            assertEquals(sheet.map { it.minute }, sheet.map { it.minute }.sorted())
            lines += sheet.size
        }
        println("scoresheet lines checked: $lines")
        assertTrue(lines > 1_000)
    }
}
