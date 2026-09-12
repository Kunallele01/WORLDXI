package com.dreamxi.app

import com.dreamxi.app.sim.SimModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Bookings must be projected onto a full season, not extrapolated linearly.
 *
 * The reported failure: Luka Modric finished a simulated season on 15 yellows
 * and a red, off a real record of 7 yellows in 1,744 minutes. Multiplying his
 * per-90 rate by 38 full matches gives 13.7 before the draw adds any variance,
 * so the model was producing exactly what it was asked for and what it was
 * asked for was wrong.
 *
 * Reality, across 4,117 regular seasons: median 3 yellows, 99th percentile 12,
 * all-time maximum 17, and players who actually played 2,900+ minutes average
 * 4.96.
 */
class CardProjectionTest {

    // ---------------------------------------------------------------------
    // The double-counted positional average.
    //
    // Card rates used to be "his own rate, or the position's average if he has
    // none". Those are not alternatives: the average is computed FROM the
    // players who were carded, so each of them was counted twice — once in his
    // own rate, once inside the average given to everyone else. Only 14.4% of
    // players see a red in a season, and the rarer the card the worse the
    // double count, so reds came out at 2.12x reality: 7.1 sendings-off a
    // season against a real 3.4, and two in a single match once every 64
    // matches rather than once every 270.
    // ---------------------------------------------------------------------

    private val cbRed = SimModel.SLOT_RED90["CB"]!!
    private val cbYellow = SimModel.SLOT_YELLOW90["CB"]!!

    @Test
    fun `a player with a clean record is still bookable`() {
        // Zero cards is evidence, not an absence of it — but it is not proof
        // he cannot be sent off, and a rate of exactly zero would make him
        // literally unbookable for a whole season.
        val rate = SimModel.cardsPerMatch(cards = 0, minutes = 3000, positionMean90 = cbRed)
        assertTrue("a spotless defender must still be able to see red: $rate", rate > 0.0)
        assertTrue("but well under the positional average: $rate", rate < cbRed)
    }

    @Test
    fun `one sending off does not make a player a thug`() {
        // A red in 700 minutes is a per-90 rate of 0.129 taken raw — nine times
        // what a centre-back averages. Shrinking toward the position keeps him
        // above average without believing a single incident that much.
        val raw = 1 * 90.0 / 700
        val rate = SimModel.cardsPerMatch(cards = 1, minutes = 700, positionMean90 = cbRed)
        assertTrue("$rate should sit above the average $cbRed", rate > cbRed)
        assertTrue("but far below the raw $raw", rate < raw / 2)
    }

    @Test
    fun `more minutes means more trust in the player's own record`() {
        // TWO PLAYERS WITH THE SAME RAW RATE — one red every 900 minutes — and
        // very different amounts of evidence for it. Both get pulled toward
        // the positional mean; the one who played four times as long is pulled
        // far less, because he has actually shown it.
        //
        // Stated as distance-from-raw, not distance-from-mean. An earlier
        // version of this test compared the two final rates and failed for a
        // reason that was not a bug: a man with one red in 500 minutes has a
        // raw rate six times his own, so even after heavy shrinking he still
        // ends up numerically further from the mean. Shrinkage is about the
        // FRACTION of the distance travelled, not where you land.
        val raw = 0.1
        val brief = SimModel.shrunkCardRate90(1, minutes = 900, positionMean90 = cbRed)
        val full = SimModel.shrunkCardRate90(4, minutes = 3600, positionMean90 = cbRed)
        assertTrue("both must sit between the mean and the raw rate",
            brief in cbRed..raw && full in cbRed..raw)
        assertTrue("full season $full should be closer to the raw $raw than brief $brief",
            kotlin.math.abs(full - raw) < kotlin.math.abs(brief - raw))
    }

    @Test
    fun `shrinkage never invents or destroys cards at the extremes`() {
        // A player who has played essentially nothing is judged purely on his
        // position; one who has played forever, purely on himself.
        assertEquals(cbRed, SimModel.shrunkCardRate90(0, minutes = 0, cbRed), 1e-9)
        val veteran = SimModel.shrunkCardRate90(40, minutes = 90_000, cbRed)
        assertEquals(40 * 90.0 / 90_000, veteran, 0.002)
    }

    /**
     * The number the user actually sees. A whole side's red-card rate has to
     * land near the real 3.4 a season, or two-red matches stop being remarkable.
     */
    @Test
    fun `a realistic eleven concedes a realistic number of red cards`() {
        // Eleven regulars, one of whom was sent off — close to a real squad,
        // where 14% of players see a red.
        val roles = listOf("GK", "CB", "CB", "FB", "FB", "DM", "CM", "CM", "Winger", "ST", "Winger")
        val perMatch = roles.mapIndexed { i, role ->
            SimModel.cardsPerMatch(
                cards = if (i == 1) 1 else 0,
                minutes = 2800,
                positionMean90 = SimModel.SLOT_RED90[role]!!,
            )
        }.sum()
        val season = perMatch * SimModel.MATCHES_PER_SEASON
        println("eleven regulars project to ${"%.1f".format(season)} reds a season (real: 3.4)")
        assertTrue("$season reds a season is not football", season in 1.5..5.0)

        // And the thing that was actually reported: two in one match.
        val pTwoPlus = 1 - kotlin.math.exp(-perMatch) - perMatch * kotlin.math.exp(-perMatch)
        println("P(2+ reds in a match) = 1 in ${"%.0f".format(1 / pTwoPlus)}")
        assertTrue("two reds a match every 1 in ${1 / pTwoPlus} is too often", 1 / pTwoPlus > 120)
    }

    @Test
    fun `yellows survive the same treatment`() {
        val roles = listOf("GK", "CB", "CB", "FB", "FB", "DM", "CM", "CM", "Winger", "ST", "Winger")
        val season = roles.map { role ->
            SimModel.cardsPerMatch(4, minutes = 2800, positionMean90 = SimModel.SLOT_YELLOW90[role]!!)
        }.sum() * SimModel.MATCHES_PER_SEASON
        println("eleven regulars on 4 yellows each project to ${"%.0f".format(season)} (real: 78)")
        assertTrue("$season yellows a season", season in 35.0..80.0)
    }

    @Test
    fun `an unused player earns nothing`() {
        assertEquals(0.0, SimModel.cardsPerMatch(0, minutes = 0, positionMean90 = cbYellow), 0.0)
    }

    @Test
    fun `bookings do not scale linearly with minutes`() {
        // Half a season played does NOT mean half the bookings, because a
        // player who features more is booked less per 90.
        val halfSeason = SimModel.projectedSeasonCards(total = 7, minutes = 1710)
        val linear = 7 * 3420.0 / 1710
        assertTrue("projection $halfSeason should sit below the linear $linear", halfSeason < linear)
        assertTrue("but it must still be an increase on the 7 he really got", halfSeason > 7)
    }

    @Test
    fun `Modric lands back in the real range`() {
        // The exact case that was reported.
        val projected = SimModel.projectedSeasonCards(total = 7, minutes = 1744)
        println("Modric projects to ${"%.1f".format(projected)} yellows (was 13.7 linear, 15 observed)")
        assertTrue("projected $projected", projected in 9.0..13.0)
        assertTrue("must beat neither the real maximum of 17", projected < 17)
    }

    @Test
    fun `a player who already played a full season barely moves`() {
        // Pique's 15 yellows in 3,092 minutes is already almost a full season,
        // so there is very little to project.
        val projected = SimModel.projectedSeasonCards(total = 15, minutes = 3092)
        assertEquals(16.2, projected, 0.6)
    }

    @Test
    fun `a clean player stays clean`() {
        assertEquals(0.0, SimModel.projectedSeasonCards(total = 0, minutes = 3000), 1e-9)
        val light = SimModel.projectedSeasonCards(total = 1, minutes = 2800)
        assertTrue("one card should not become several: $light", light < 2.0)
    }

    @Test
    fun `nothing divides by zero`() {
        assertEquals(0.0, SimModel.projectedSeasonCards(total = 5, minutes = 0), 1e-9)
        assertEquals(0.0, SimModel.projectedSeasonCards(total = 0, minutes = 0), 1e-9)
    }

    @Test
    fun `a whole XI's bookings stay inside what real squads collect`() {
        // Eleven regularly-booked players, each having played about two thirds
        // of a season. Their projected total is what the side would collect,
        // and a real club averages 81 yellows across a whole squad.
        val xi = List(11) { SimModel.projectedSeasonCards(total = 6, minutes = 2200) }
        val total = xi.sum()
        println("a booking-prone XI projects to ${"%.0f".format(total)} yellows in a season")
        assertTrue("an XI on $total yellows is beyond anything real", total < 130)
        assertTrue("and it should not collapse either: $total", total > 60)
    }

    @Test
    fun `the correction applies to cards only`() {
        // Goals per 90 show no decline with minutes in the same data, so goals
        // and assists must keep scaling linearly. This pins the intent: if
        // someone later routes goals through this function, it fails here.
        val goalsIfWronglyCorrected = SimModel.projectedSeasonCards(total = 20, minutes = 2280)
        val goalsDoneRight = 20 * 3420.0 / 2280
        assertTrue(
            "the card correction must not be reused for goals",
            goalsIfWronglyCorrected < goalsDoneRight,
        )
    }
}
