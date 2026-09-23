package com.dreamxi.app

import com.dreamxi.app.feature.draft.Formations
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.sim.WingBackCost
import com.dreamxi.app.feature.draft.deltaAt
import com.dreamxi.app.feature.draft.ratingAt
import com.dreamxi.app.ui.theme.PlayerPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wing-backs, which the stored grid cannot see.
 *
 * It keeps one full-back column, max(lb, rb), because EA's left and right
 * columns are identical for every player. Its lwb/rwb columns are NOT, and the
 * app was pricing every wing-back slot off the full-back number.
 *
 * The case that surfaced it: Javier Mascherano, FIFA 15, at left wing-back. EA
 * has him cb 82, lb 82, rb 82 — genuinely as good at full-back as at the back,
 * he really did play right-back for Liverpool — but lwb 80 and rwb 80. The app
 * was offering the 82.
 */
class WingBackCostTest {

    private fun mascherano() = SquadPlayer(
        playerSeasonStatId = 1L,
        playerId = 1L,
        fullName = "Javier Mascherano",
        role = "CB",
        group = PlayerPosition.DEFENDER,
        overallRating = 83,
        minutes = 2700,
        goals = 0,
        assists = 1,
        // His real FIFA 15 grid, as the ETL stores it: fb = max(lb, rb) = 82.
        positionRatings = mapOf(
            "cb" to 82, "fb" to 82, "dm" to 82, "cm" to 75,
            "cam" to 69, "winger" to 67, "st" to 64,
        ),
        side = null,
    )

    private fun slot(label: String) = Formations
        .flatMap { it.slots }
        .first { it.label == label }

    @Test
    fun `a centre-back is priced down at wing-back, not level with full-back`() {
        val player = mascherano()
        val atFullBack = player.ratingAt(slot("LB"))
        val atWingBack = player.ratingAt(slot("LWB"))
        println("Mascherano: ${player.overallRating} overall, LB $atFullBack, LWB $atWingBack")
        // EA's own numbers: level at full-back, two down at wing-back.
        assertEquals(player.overallRating, atFullBack)
        assertEquals(atFullBack - 2, atWingBack)
        assertEquals(-2, player.deltaAt(slot("LWB")))
    }

    @Test
    fun `the offset is EA's, per role, and points the way football does`() {
        // A wing-back is further up the pitch: defenders lose, attackers gain.
        assertTrue("a centre-back should lose points", WingBackCost.getValue("CB") > 0)
        assertEquals("a full-back is a wing-back already", 0, WingBackCost.getValue("FB"))
        assertTrue("a forward should not lose points", WingBackCost.getValue("ST") < 0)
        assertTrue(WingBackCost.getValue("Winger") < 0)
    }

    /**
     * Gaining at wing-back must not make anyone BETTER than at his own
     * position — the out-of-position delta is capped at zero, and a negative
     * cost must not punch through that.
     */
    @Test
    fun `nobody is worth more out of position than in it`() {
        for (role in listOf("CB", "FB", "DM", "CM", "CAM", "Winger", "ST")) {
            val player = mascherano().copy(
                role = role,
                positionRatings = mapOf(
                    "cb" to 80, "fb" to 80, "dm" to 80, "cm" to 80,
                    "cam" to 80, "winger" to 80, "st" to 80,
                ),
            )
            val delta = player.deltaAt(slot("LWB"))
            assertTrue("$role gained $delta at wing-back", delta <= 0)
        }
    }

    @Test
    fun `full-back slots are untouched`() {
        val player = mascherano()
        assertEquals(0, player.deltaAt(slot("LB")))
        assertEquals(0, player.deltaAt(slot("RB")))
    }
}
