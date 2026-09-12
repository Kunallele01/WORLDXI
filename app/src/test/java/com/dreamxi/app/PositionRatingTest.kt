package com.dreamxi.app

import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.feature.draft.formationById
import com.dreamxi.app.feature.draft.ratingAt
import com.dreamxi.app.ui.theme.PlayerPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The out-of-position model, checked against real EA numbers.
 *
 * The grids below are the actual values backfilled for those player-seasons,
 * not invented ones — the whole point of the model is that it reflects what EA
 * says about each individual, so testing it with tidy made-up numbers would
 * verify the arithmetic while missing every real question.
 */
class PositionRatingTest {

    private val formation = formationById("4-3-3")
    // Slot ids are formation-specific: 4-3-3's centre-backs are "lcb"/"rcb"
    // and its central midfielders "lcm"/"rcm", so look up by LABEL instead,
    // which is stable across shapes.
    private fun slot(label: String) = formation.slots.first { it.label == label }

    /** Real Madrid 2021/22, EA edition 23. */
    private val benzema = SquadPlayer(
        1, 1, "Karim Benzema", "ST", PlayerPosition.FORWARD, 91, 2593, 27, 12,
        positionRatings = mapOf(
            "cb" to 55, "fb" to 60, "dm" to 64, "cm" to 81,
            "cam" to 88, "winger" to 87, "st" to 89,
        ),
    )

    /** Left winger, EA lists him LW/LM only. */
    private val vinicius = SquadPlayer(
        2, 2, "Vinicius Junior", "Winger", PlayerPosition.FORWARD, 86, 2690, 17, 10,
        side = "L",
        positionRatings = mapOf(
            "cb" to 46, "fb" to 57, "dm" to 57, "cm" to 76,
            "cam" to 84, "winger" to 86, "st" to 80,
        ),
    )

    /** EA lists him on both flanks, so switching wings should cost nothing. */
    private val twoFooted = vinicius.copy(playerId = 3, fullName = "Both Flanks", side = "B")

    private val leftBack = SquadPlayer(
        4, 4, "Ferland Mendy", "FB", PlayerPosition.DEFENDER, 82, 1734, 2, 1,
        side = "L",
        positionRatings = mapOf(
            "cb" to 80, "fb" to 82, "dm" to 79, "cm" to 74,
            "cam" to 70, "winger" to 72, "st" to 66,
        ),
    )

    @Test
    fun `a player at his own position keeps exactly his overall`() {
        // EA's positional ratings sit on a different scale from overall
        // (Benzema is 91 overall but 89 at 'st'), so anchoring the delta to his
        // own natural value is what stops a striker being docked for playing
        // striker. This is the regression test for that.
        assertEquals(91, benzema.ratingAt(slot("ST")))
        assertEquals(86, vinicius.ratingAt(slot("LW")))
        assertEquals(82, leftBack.ratingAt(slot("LB")))
    }

    @Test
    fun `out of position costs what EA says it costs`() {
        // Adjacent roles are cheap, alien ones are brutal, and it is asymmetric.
        assertEquals("Benzema at CM", 83, benzema.ratingAt(slot("CM")))
        assertEquals("Benzema at DM", 66, benzema.ratingAt(slot("DM")))
        assertEquals("Benzema at CB", 57, benzema.ratingAt(slot("CB")))
        assertTrue(
            "a striker at centre-back should be a disaster",
            benzema.overallRating - benzema.ratingAt(slot("CB")) > 25,
        )
    }

    @Test
    fun `a left winger pays to play on the right`() {
        // The bug this exists for: EA's lw and rw rating columns are identical
        // for every player, so the grid alone said a left winger could switch
        // flanks for free. Vinicius Junior has essentially never played RW.
        val left = vinicius.ratingAt(slot("LW"))
        val right = vinicius.ratingAt(slot("RW"))
        assertEquals(86, left)
        assertTrue("switching flanks must cost something", right < left)
        assertEquals("winger wrong-flank cost", 2, left - right)
    }

    @Test
    fun `a two-footed winger switches flanks for free`() {
        // 28% of wingers are listed on both flanks. Charging them would be
        // punishing players for a versatility EA explicitly records.
        assertEquals(twoFooted.ratingAt(slot("LW")), twoFooted.ratingAt(slot("RW")))
    }

    @Test
    fun `a full-back pays more than a winger for the wrong flank`() {
        // Inverted wingers are routine; inverted full-backs are awkward.
        val fbCost = leftBack.ratingAt(slot("LB")) - leftBack.ratingAt(slot("RB"))
        val wingerCost = vinicius.ratingAt(slot("LW")) - vinicius.ratingAt(slot("RW"))
        assertEquals("full-back wrong-flank cost", 4, fbCost)
        assertTrue("full-backs should suffer more than wingers", fbCost > wingerCost)
    }

    @Test
    fun `an unknown side is never treated as the wrong side`() {
        // 2% of full-backs and wingers resolve no side at all. Absence of
        // evidence must not become evidence of a mismatch.
        val unknown = vinicius.copy(side = null)
        assertEquals(unknown.ratingAt(slot("LW")), unknown.ratingAt(slot("RW")))
    }

    @Test
    fun `a player with no EA grid carries no positional penalty`() {
        // 27 of 5,723 player-seasons resolve no grid. They should be neutral
        // everywhere rather than guessed at.
        val ungraded = benzema.copy(positionRatings = emptyMap())
        assertEquals(91, ungraded.ratingAt(slot("ST")))
        assertEquals(91, ungraded.ratingAt(slot("CB")))
    }

    @Test
    fun `crossing the goalkeeper boundary is catastrophic, not plausible`() {
        // The stored grid holds outfield columns only, so a keeper's reference
        // used to fall back to his best outfield value (~32), which made
        // Courtois read as an 87-rated centre-back.
        val courtois = SquadPlayer(
            5, 5, "Thibaut Courtois", "GK", PlayerPosition.GOALKEEPER, 90, 2970, 0, 0,
            positionRatings = mapOf(
                "cb" to 29, "fb" to 29, "dm" to 31, "cm" to 32,
                "cam" to 32, "winger" to 29, "st" to 31,
            ),
        )
        assertTrue(
            "a keeper outfield must not look draftable",
            courtois.ratingAt(slot("CB")) < 45,
        )
        assertTrue(
            "an outfielder in goal must not look draftable",
            benzema.ratingAt(slot("GK")) < 45,
        )
    }
}
