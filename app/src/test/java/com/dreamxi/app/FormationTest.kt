package com.dreamxi.app

import com.dreamxi.app.feature.draft.Formations
import com.dreamxi.app.feature.draft.gridKeyForRole
import com.dreamxi.app.ui.theme.PlayerPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The formation catalogue is 20 shapes of hand-written coordinates, which is
 * exactly the kind of data where a typo produces something that compiles, runs,
 * and simply looks wrong on a pitch nobody has opened yet. These check the
 * things a glance at the screen would not reliably catch.
 */
class FormationTest {

    @Test
    fun `every formation is structurally valid`() {
        // Formation's init block enforces 11 slots, one keeper and unique ids;
        // touching the list runs all of them.
        assertTrue("expected a full catalogue", Formations.size >= 19)
        Formations.forEach { f ->
            assertEquals("${f.name} slot count", 11, f.slots.size)
            assertEquals("${f.name} keepers", 1, f.slots.count { it.isGoalkeeper })
            assertEquals("${f.name} unique ids", 11, f.slots.map { it.id }.toSet().size)
        }
    }

    @Test
    fun `the shape on the pitch matches the name`() {
        // Checked by DEPTH, not by position group. A formation name counts
        // lines on the pitch: 3-5-2's wing-backs are the FB role but belong to
        // the "5", and 4-2-3-1's wide players are the Winger role but belong
        // to the "3". Counting groups gets both backwards, which is exactly
        // the bug that made 3-5-2 file itself under "5 at the back".
        Formations.forEach { f ->
            val declared = f.lines
            assertEquals("${f.name} outfielders declared", 10, declared.sum())

            val outfield = f.slots.filterNot { it.isGoalkeeper }
            val deepestFirst = outfield.sortedByDescending { it.y }
            deepestFirst.take(declared.first()).forEach {
                assertEquals(
                    "${f.name}: ${it.label} is in the back line but is not a defender",
                    PlayerPosition.DEFENDER, it.group,
                )
            }
            deepestFirst.takeLast(declared.last()).forEach {
                assertEquals(
                    "${f.name}: ${it.label} is in the front line but is not a forward",
                    PlayerPosition.FORWARD, it.group,
                )
            }
        }
    }

    @Test
    fun `every outfield position maps to a grid key`() {
        // A position whose role has no grid key would silently carry a zero
        // out-of-position penalty, making it a free slot for anyone.
        Formations.forEach { f ->
            f.slots.filterNot { it.isGoalkeeper }.forEach { slot ->
                assertTrue(
                    "${f.name} ${slot.label} has role '${slot.role}' with no grid key",
                    gridKeyForRole(slot.role) != null,
                )
            }
        }
    }

    @Test
    fun `positions stay on the pitch and do not overlap`() {
        // Tokens are ~0.19 of the pitch wide and CENTRED on their coordinate,
        // so the usable range of centres is 0.105..0.895 — not 0..1. The first
        // version of this test allowed 0.05..0.95 and therefore passed while
        // wing-backs at 0.07 were visibly hanging off the touchline. The bound
        // has to be the one the renderer actually imposes, or the test is just
        // agreeing with the bug.
        Formations.forEach { f ->
            f.slots.forEach { s ->
                assertTrue(
                    "${f.name} ${s.label} x=${s.x} would overhang the touchline",
                    s.x in 0.105f..0.895f,
                )
                assertTrue("${f.name} ${s.label} y=${s.y} off pitch", s.y in 0.05f..0.96f)
            }
            f.slots.forEachIndexed { i, a ->
                f.slots.drop(i + 1).forEach { b ->
                    val collides = abs(a.x - b.x) < 0.16f && abs(a.y - b.y) < 0.09f
                    assertTrue("${f.name}: ${a.label} and ${b.label} overlap", !collides)
                }
            }
        }
    }
}
