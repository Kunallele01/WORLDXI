package com.dreamxi.app

import com.dreamxi.app.core.ui.passwordStrength
import com.dreamxi.app.core.ui.passwordStrengthLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The live strength meter on a password being chosen.
 *
 * It is encouragement, not a security claim — but it still has to be honest
 * about the few things it asserts, because a meter that says "strong" is
 * telling someone to stop thinking.
 */
class PasswordStrengthTest {

    @Test
    fun `nothing typed is nothing scored`() {
        assertEquals(0f, passwordStrength(""), 0.0001f)
    }

    /**
     * The one hard rule: Supabase will not accept fewer than eight characters,
     * so nothing shorter may look anything but weak. Showing green on a
     * password the server is about to reject would be a lie the player only
     * discovers after pressing the button.
     */
    @Test
    fun `nothing below eight characters can look good`() {
        for (password in listOf("a", "Ab1!", "Str0ng!", "aB3\$xY9")) {
            val score = passwordStrength(password)
            assertTrue("$password scored $score", score <= 0.34f)
            assertEquals("WEAK", passwordStrengthLabel(score))
        }
    }

    /**
     * Length is the factor that genuinely matters, and the meter has to teach
     * that rather than the opposite. A long simple password must beat a short
     * complicated one — otherwise it is telling people that adding "1!" to
     * something short has solved the problem.
     */
    @Test
    fun `length beats punctuation`() {
        val longSimple = passwordStrength("correcthorsebatterystaple")
        val shortComplex = passwordStrength("aB3\$xY9!")
        println("long simple $longSimple vs short complex $shortComplex")
        assertTrue("a 25-character password should beat an 8-character one", longSimple > shortComplex)
    }

    @Test
    fun `it climbs as you type`() {
        val steps = listOf("f", "foot", "football", "football12", "football12!", "football12!X")
            .map { passwordStrength(it) }
        println("typing: $steps")
        steps.zipWithNext { a, b -> assertTrue("$a then $b went backwards", b >= a) }
        assertTrue("should reach at least fair", steps.last() >= 0.34f)
    }

    /**
     * One character held down is not a password, however long the field gets.
     * Without this it outscores anything a person would actually think of.
     */
    @Test
    fun `a held-down key is not a password`() {
        val mashed = passwordStrength("aaaaaaaaaaaaaaaaaaaa")
        val real = passwordStrength("thequickbrownfox")
        println("mashed $mashed vs real $real")
        assertTrue("20 of the same character scored $mashed", mashed < 0.34f)
        assertTrue(real > mashed)
    }

    @Test
    fun `a genuinely good password reads strong`() {
        for (password in listOf("Th3-Quick-Brown-F0x", "correcthorsebatterystaple!")) {
            val score = passwordStrength(password)
            println("$password -> $score ${passwordStrengthLabel(score)}")
            assertEquals("STRONG", passwordStrengthLabel(score))
        }
    }

    @Test
    fun `the score never leaves its range`() {
        for (password in listOf("", "a", "a".repeat(200), "Aa1!".repeat(60))) {
            val score = passwordStrength(password)
            assertTrue("$score out of range", score in 0f..1f)
        }
    }
}
