package com.dreamxi.app

import com.dreamxi.app.core.ui.clubDisplayName
import com.dreamxi.app.core.ui.clubGroupLetter
import com.dreamxi.app.core.ui.clubSortKey
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Club names on screen, and where they file alphabetically.
 *
 * The rule the user settled on: strip initialisms, keep words. FC is an
 * abbreviation nobody says, so FC Barcelona files under B; "Real" is a spoken
 * word, so Real Madrid files under R. These tests pin both halves, because a
 * rule that only ever moved things would be indistinguishable from dropping
 * the first word.
 */
class ClubNameTest {

    @Test
    fun `stored short forms become the club's real name`() {
        assertEquals("FC Barcelona", clubDisplayName("Barcelona"))
        assertEquals("Manchester United", clubDisplayName("Manchester Utd"))
        assertEquals("Queens Park Rangers", clubDisplayName("QPR"))
        assertEquals("West Bromwich Albion", clubDisplayName("West Brom"))
        assertEquals("Wolverhampton Wanderers", clubDisplayName("Wolves"))
        assertEquals("RCD Espanyol", clubDisplayName("Espanyol"))
    }

    @Test
    fun `a club already stored under its real name is left alone`() {
        assertEquals("Real Madrid", clubDisplayName("Real Madrid"))
        assertEquals("Aston Villa", clubDisplayName("Aston Villa"))
        // And a club nobody has mapped still displays rather than vanishing.
        assertEquals("Some New Club", clubDisplayName("Some New Club"))
    }

    @Test
    fun `an initialism does not decide where a club files`() {
        assertEquals("B", clubGroupLetter("Barcelona"))        // FC Barcelona
        assertEquals("E", clubGroupLetter("Espanyol"))         // RCD Espanyol
        assertEquals("O", clubGroupLetter("Osasuna"))          // CA Osasuna
        assertEquals("B", clubGroupLetter("Bournemouth"))      // AFC Bournemouth
        assertEquals("C", clubGroupLetter("Celta Vigo"))       // RC Celta de Vigo
        assertEquals("L", clubGroupLetter("Las Palmas"))       // UD Las Palmas
        assertEquals("T", clubGroupLetter("Tenerife"))         // CD Tenerife
    }

    @Test
    fun `a spoken word does`() {
        assertEquals("R", clubGroupLetter("Real Madrid"))
        assertEquals("R", clubGroupLetter("Real Betis"))
        assertEquals("R", clubGroupLetter("Valladolid"))       // Real Valladolid
        assertEquals("A", clubGroupLetter("Athletic Club"))
        assertEquals("A", clubGroupLetter("Atlético Madrid"))  // Atlético de Madrid
        assertEquals("S", clubGroupLetter("Sporting Gijón"))   // Sporting de Gijón
        assertEquals("D", clubGroupLetter("La Coruña"))        // Deportivo La Coruña
    }

    @Test
    fun `a trailing initialism is not stripped, because it never affects sorting`() {
        assertEquals("Sevilla FC", clubDisplayName("Sevilla"))
        assertEquals("S", clubGroupLetter("Sevilla"))
        assertEquals("V", clubGroupLetter("Valencia"))         // Valencia CF
    }

    @Test
    fun `accents fold so a club is not stranded under its own letter`() {
        assertEquals("M", clubGroupLetter("Málaga"))
        assertEquals("A", clubGroupLetter("Alavés"))
        assertEquals("A", clubGroupLetter("Almería"))          // UD Almería
        assertEquals("H", clubGroupLetter("Hércules"))         // Hércules CF
        assertEquals("cadiz cf", clubSortKey("Cádiz"))
    }

    @Test
    fun `sorting by the key puts the list in the order the headers promise`() {
        val stored = listOf("Real Madrid", "Barcelona", "Espanyol", "Athletic Club", "Osasuna", "Sevilla")
        assertEquals(
            listOf("Athletic Club", "Barcelona", "Espanyol", "Osasuna", "Real Madrid", "Sevilla"),
            stored.sortedBy(::clubSortKey),
        )
    }
}
