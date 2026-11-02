package com.dreamxi.app

import com.dreamxi.app.feature.season.nth
import com.dreamxi.app.sim.CardEvent
import com.dreamxi.app.sim.GoalEvent
import com.dreamxi.app.sim.GoalNumbers
import com.dreamxi.app.sim.MatchEvents
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The scoresheet numbers a player's repeat goals and assists so a reader does
 * not have to count "Messi 56', Messi 72', Messi 74'" by eye.
 */
class GoalNumbersTest {

    private fun goal(min: Int, scorer: String, assist: String?, team: String = "us") =
        GoalEvent(minute = min, teamId = team, scorer = scorer, assist = assist)

    @Test
    fun scorers_count_up_through_the_match_in_minute_order() {
        // Given out of order: the numbering must follow the clock, not the list.
        val events = MatchEvents(
            goals = listOf(
                goal(74, "Messi", "Marcelo"),
                goal(25, "Neymar", "Iniesta"),
                goal(56, "Messi", "Casemiro"),
                goal(72, "Messi", "Ronaldo"),
            ),
        )
        // minute order: Neymar 25, Messi 56, Messi 72, Messi 74
        assertEquals(listOf(1, 1, 2, 3), events.goalNumbers().map { it.scorer })
    }

    @Test
    fun assists_are_counted_separately_from_goals() {
        val events = MatchEvents(
            goals = listOf(
                goal(31, "Marcelo", "Fabregas"),
                goal(72, "Messi", "Marcelo"),
                goal(74, "Messi", "Marcelo"),
                goal(81, "Villa", "Marcelo"),
            ),
        )
        val n = events.goalNumbers()
        // Fabregas's first assist, then Marcelo's first, second and third.
        assertEquals(listOf(1, 1, 2, 3), n.map { it.assist })
        // Marcelo scoring once does not touch his assist count, or vice versa.
        assertEquals(listOf(1, 1, 2, 1), n.map { it.scorer })
    }

    @Test
    fun a_goal_with_no_assist_has_assist_number_zero() {
        val n = MatchEvents(goals = listOf(goal(10, "Messi", null))).goalNumbers()
        assertEquals(listOf(GoalNumbers(scorer = 1, assist = 0)), n)
    }

    @Test
    fun the_same_name_on_the_other_side_is_a_different_player() {
        val events = MatchEvents(
            goals = listOf(
                goal(10, "Silva", null, team = "us"),
                goal(20, "Silva", null, team = "them"),
                goal(30, "Silva", null, team = "us"),
            ),
        )
        assertEquals(listOf(1, 1, 2), events.goalNumbers().map { it.scorer })
    }

    @Test
    fun bookings_do_not_disturb_the_numbering() {
        val events = MatchEvents(
            goals = listOf(goal(30, "Messi", null), goal(60, "Messi", null)),
            cards = listOf(CardEvent(minute = 45, teamId = "us", player = "Messi", isRed = false)),
        )
        assertEquals(listOf(1, 2), events.goalNumbers().map { it.scorer })
    }

    @Test
    fun first_goal_shows_nothing_and_later_ones_show_the_count() {
        assertEquals("", nth(0))
        assertEquals("", nth(1))
        assertEquals(" ×2", nth(2))
        assertEquals(" ×3", nth(3))
    }
}
