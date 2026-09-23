package com.dreamxi.app

import com.dreamxi.app.feature.season.sharePercents
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projected-finish column, which reported "1st 84%, 2nd 17%".
 *
 * Both were rounded on their own — 83.5 and 16.5, each up — and the column read
 * 101%. The projection was right; only the display was wrong. Largest remainder
 * hands out the rounded total instead, so the rows can never claim more than
 * they hold.
 */
class ProjectionRoundingTest {

    // "<1%" is a row rounded to nothing, so it contributes nothing to the total —
    // reading it as 1 was a fault in this test, not in the rounding.
    private fun total(labels: List<String>) =
        labels.sumOf { if (it.startsWith("<")) 0 else it.removeSuffix("%").toInt() }

    @Test
    fun `the reported case no longer sums past a hundred`() {
        val labels = sharePercents(listOf(0.835, 0.165))
        println("0.835 / 0.165 -> $labels")
        assertEquals(listOf("84%", "16%"), labels)
        assertEquals(100, total(labels))
    }

    @Test
    fun `rows never claim more than they hold`() {
        // The bad case is a set of shares that all sit just over a half point.
        for (case in listOf(
            listOf(0.835, 0.165),
            listOf(0.125, 0.125, 0.125, 0.125, 0.125),
            listOf(0.005, 0.005, 0.99),
            listOf(0.335, 0.335, 0.33),
            listOf(0.4444, 0.2222, 0.1111, 0.1111, 0.1112),
        )) {
            val labels = sharePercents(case)
            val budget = Math.round(case.sum() * 100).toInt()
            assertTrue("$case -> $labels claims more than $budget", total(labels) <= budget)
        }
    }

    @Test
    fun `every row stays within a point of its real value`() {
        val case = listOf(0.335, 0.335, 0.33)
        val labels = sharePercents(case)
        for ((i, label) in labels.withIndex()) {
            val shown = label.removeSuffix("%").removePrefix("<").toInt()
            assertTrue("${case[i]} shown as $label", Math.abs(shown - case[i] * 100) <= 1.0)
        }
    }

    /**
     * A place with no gap to land in is genuinely unreachable and must read 0%;
     * a place that is merely unlikely must not, because the screen prints a
     * footnote explaining the zero.
     */
    @Test
    fun `an impossible place reads zero and a rare one does not`() {
        val labels = sharePercents(listOf(0.62, 0.0, 0.38))
        println("with an unreachable place -> $labels")
        assertEquals("0%", labels[1])
        assertEquals("<1%", sharePercents(listOf(0.996, 0.004))[1])
    }
}
