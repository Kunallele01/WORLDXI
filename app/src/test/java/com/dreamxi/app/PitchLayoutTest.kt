package com.dreamxi.app

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dreamxi.app.feature.draft.Formations
import com.dreamxi.app.feature.draft.components.pitchSlotTop
import com.dreamxi.app.feature.draft.components.pitchTokenHeight
import com.dreamxi.app.feature.draft.components.pitchTokenSize
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No two shirts on the pitch may overlap, in any formation, on any screen.
 *
 * The reported failure: in a back three the middle centre-back is drawn deeper
 * than the other two, and his NAME — which hangs below the shirt and is part of
 * its height — landed on the goalkeeper. Shirt size was bounded by the pitch's
 * width alone while row spacing comes from its height, so a short pitch made it
 * worse and no amount of width told the layout anything about it.
 */
class PitchLayoutTest {

    /** Phone widths and the pitch heights they leave, narrow and short included. */
    private val sizes = listOf(
        320.dp to 400.dp,
        320.dp to 520.dp,
        360.dp to 440.dp,
        393.dp to 520.dp,
        412.dp to 600.dp,
        480.dp to 700.dp,
        600.dp to 900.dp,
    )

    @Test
    fun `no two positions overlap in any formation at any pitch size`() {
        var worst = Float.MAX_VALUE
        var worstCase = ""
        for ((w, h) in sizes) {
            for (formation in Formations) {
                val token = pitchTokenSize(formation, w, h)
                val tokenHeight = pitchTokenHeight(token)
                for (i in formation.slots.indices) {
                    for (j in i + 1 until formation.slots.size) {
                        val a = formation.slots[i]
                        val b = formation.slots[j]
                        // Only positions that share horizontal space can collide.
                        val apart = abs(a.x - b.x) * w.value
                        if (apart >= token.value) continue

                        val topA = pitchSlotTop(a, h, token).value
                        val topB = pitchSlotTop(b, h, token).value
                        val clear = abs(topA - topB) - tokenHeight.value
                        if (clear < worst) {
                            worst = clear
                            worstCase = "${formation.id} ${a.label}/${b.label} at ${w.value.toInt()}x${h.value.toInt()}"
                        }
                        assertTrue(
                            "${formation.id}: ${a.label} and ${b.label} overlap by " +
                                "${"%.1f".format(-clear)}dp at ${w.value.toInt()}x${h.value.toInt()}dp",
                            clear >= 0f,
                        )
                    }
                }
            }
        }
        println("tightest pair anywhere: $worstCase, ${"%.1f".format(worst)}dp clear")
    }

    /**
     * The fix must not be silently undone by making shirts bigger again: the
     * reported case had about 4dp of overlap on a normal phone, so the bound
     * that removed it has to stay the one doing the work there.
     */
    @Test
    fun `the height bound is what saves the reported case`() {
        val w = 393.dp
        val h = 520.dp
        val backThree = Formations.first { it.slots.count { s -> s.role == "CB" } == 3 }
        val widthOnly: Dp = minOf(w / 5.3f, 66.dp)
        val actual = pitchTokenSize(backThree, w, h)
        println("393x520: width alone would give ${widthOnly.value}dp, both give ${actual.value}dp")
        assertTrue("the height bound is not binding on a normal phone", actual < widthOnly)
        // A back four has room to spare and must not be shrunk for someone else's problem.
        val backFour = Formations.first { it.id == "4-3-3" }
        assertEquals("a back four lost size it did not need to", widthOnly, pitchTokenSize(backFour, w, h))

        // And with the old rule the back three really did collide.
        val middle = backThree.slots.first { it.role == "CB" && it.x == 0.5f }
        val keeper = backThree.slots.first { it.isGoalkeeper }
        val oldClear = abs(
            pitchSlotTop(middle, h, widthOnly).value - pitchSlotTop(keeper, h, widthOnly).value,
        ) - pitchTokenHeight(widthOnly).value
        println("old rule, ${backThree.id}: ${"%.1f".format(oldClear)}dp clear between CB and GK")
        assertTrue("the bug this test exists for did not reproduce", oldClear < 0f)
    }
}
