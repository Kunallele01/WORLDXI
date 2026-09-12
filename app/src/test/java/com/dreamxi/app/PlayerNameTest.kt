package com.dreamxi.app

import com.dreamxi.app.core.ui.playerSurname
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The name on the shirt.
 *
 * Every case below is a real player in the database. The two groups matter
 * equally: the first is what the old "everything after the last space" rule
 * got wrong, and the second is what the obvious fix — "keep the last two
 * words" — would newly break. Measured over the 3,698 loaded players, that
 * blanket rule fixes 54 names and breaks 108, which is why the real rule turns
 * on particles instead of word count.
 */
class PlayerNameTest {

    private fun assertName(expected: String, full: String) =
        assertEquals(full, expected, playerSurname(full))

    @Test
    fun `a particle belongs to the surname it introduces`() {
        assertName("van Dijk", "Virgil van Dijk")
        assertName("van Aanholt", "Patrick van Aanholt")
        assertName("van Hecke", "Jan Paul van Hecke")
        assertName("de Gea", "David de Gea")
        assertName("de Jong", "Frenkie de Jong")
        assertName("De Bruyne", "Kevin De Bruyne")
        assertName("ter Stegen", "Marc-André ter Stegen")
        assertName("Di María", "Ángel Di María")
        assertName("dos Santos", "Giovani dos Santos")
        assertName("El Ghazi", "Anwar El Ghazi")
        assertName("Ben Yedder", "Wissam Ben Yedder")
        assertName("Mac Allister", "Alexis Mac Allister")
        assertName("Le Normand", "Robin Le Normand")
        assertName("San José", "Mikel San José")
        assertName("Lo Celso", "Giovani Lo Celso")
    }

    @Test
    fun `stacked particles all come along`() {
        assertName("van der Vaart", "Rafael van der Vaart")
        assertName("van de Beek", "Donny van de Beek")
        assertName("de la Bella", "Alberto de la Bella")
        assertName("de la Hoz", "César de la Hoz")
        assertName("Van Der Heyden", "Siebe Van Der Heyden")
    }

    /**
     * The reason the rule is not simply "last two words". These are Spanish,
     * Portuguese and African names carrying two real surnames or a middle
     * name, and the second-to-last word is a name in its own right.
     */
    @Test
    fun `two real surnames keep only the last`() {
        assertName("Peña", "Fabián Ruiz Peña")
        assertName("Gueye", "Idrissa Gana Gueye")
        assertName("Anguissa", "Andre-Frank Zambo Anguissa")
        assertName("Lokonga", "Albert Sambi Lokonga")
        assertName("Moreno", "Abel Gómez Moreno")
        assertName("Indi", "Bruno Martins Indi")
        assertName("Manga", "Bruno Ecuele Manga")
        assertName("Fofana", "David Datro Fofana")
        assertName("Garai", "Aritz López Garai")
    }

    /** A first name that looks like a particle is still a first name. */
    @Test
    fun `the walk stops at the first word that is not a particle`() {
        assertName("Touré", "El Bilal Touré")
    }

    /** A generational suffix is not the name. The old rule rendered this "Jr.". */
    @Test
    fun `a suffix is stripped rather than displayed`() {
        assertName("Musonda", "Charly Musonda Jr.")
        assertName("Musonda", "Charly Musonda Jr")
        assertName("Silva", "Alberto Silva Junior")
    }

    @Test
    fun `ordinary names are untouched`() {
        assertName("Salah", "Mohamed Salah")
        assertName("Haaland", "Erling Haaland")
        assertName("Alexander-Arnold", "Trent Alexander-Arnold")
        assertName("Ronaldinho", "Ronaldinho")
    }

    /**
     * A name that IS only a particled surname keeps both words — the first
     * word is allowed to be consumed, since dropping it would leave "Persie".
     */
    @Test
    fun `a bare particled surname survives intact`() {
        assertName("van Persie", "van Persie")
        assertName("Di María", "Di María")
    }

    @Test
    fun `blank and ragged input does not crash`() {
        assertEquals("", playerSurname(""))
        assertEquals("Salah", playerSurname("  Mohamed   Salah  "))
    }
}
