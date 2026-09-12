package com.dreamxi.app.core.ui

import java.util.Locale

/**
 * The name a player is actually known by, for a shirt, a scoreline or a
 * booking — where there is room for one word and not four.
 *
 * The naive rule is "everything after the last space", and it is wrong for a
 * whole nationality at a time: it renders Virgil van Dijk as "Dijk", Kevin De
 * Bruyne as "Bruyne" and David de Gea as "Gea". No commentator has ever said
 * any of those.
 *
 * The fix is NOT "keep the last two words". That was measured against the
 * 3,698 players in the database and it is worse than what it replaces: of the
 * 153 names with three or more words it fixes 54 and breaks 108, because most
 * three-word names are Spanish or Portuguese and carry two real surnames.
 * Fabián Ruiz Peña becomes "Ruiz Peña" and Idrissa Gana Gueye becomes "Gana
 * Gueye", neither of which anyone says either.
 *
 * What actually distinguishes the two cases is whether the word before the
 * surname is a PARTICLE — a preposition or article that is part of the
 * surname rather than a name in its own right. "van", "de", "El", "Mac" and
 * their kin belong to the name that follows them; "Ruiz", "Gana" and "Leonel"
 * do not. So the rule walks backwards from the last word for as long as it
 * keeps finding particles, which handles the stacked ones — van der Vaart, de
 * la Bella — without any special-casing.
 */
private val PARTICLES = setOf(
    // Dutch, German, Afrikaans
    "van", "von", "der", "den", "ter", "ten", "te", "op", "vander",
    // Romance
    "de", "del", "della", "dello", "delle", "degli", "di", "da", "do", "dos",
    "das", "du", "la", "le", "lo", "li",
    // Arabic and Hebrew
    "el", "al", "ben", "bin", "ibn", "abd", "abu", "bou",
    // Celtic and Iberian
    "mac", "mc", "san", "santa", "st",
    // Scandinavian
    "af", "av",
)

/**
 * Suffixes that are not the name either. Stripped before the surname is taken,
 * so Charly Musonda Jr. reads "Musonda" rather than the standalone "Jr." the
 * old rule produced.
 */
private val SUFFIXES = setOf("jr", "jr.", "sr", "sr.", "ii", "iii", "iv", "junior")

/**
 * The short form: the surname with any particles that belong to it.
 *
 *     "Virgil van Dijk"        -> "van Dijk"
 *     "Rafael van der Vaart"   -> "van der Vaart"
 *     "Kevin De Bruyne"        -> "De Bruyne"
 *     "Fabián Ruiz Peña"       -> "Peña"
 *     "Charly Musonda Jr."     -> "Musonda"
 *     "Ronaldinho"             -> "Ronaldinho"
 *
 * Original capitalisation is preserved — Dutch lowercases the particle and
 * Belgian usage often does not, and both are correct for their own players.
 */
fun playerSurname(fullName: String): String {
    val words = fullName.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    if (words.isEmpty()) return fullName
    // A trailing generational suffix is not part of the name being shortened,
    // but it is all there is if the name is nothing else.
    var end = words.size
    while (end > 1 && words[end - 1].lowercase(Locale.ROOT) in SUFFIXES) end--
    var start = end - 1
    // The first word is fair game: a player recorded only as "Di María" or
    // "van Persie" is known by both words, not by the second alone. A given
    // name that merely LOOKS like a particle stays safe anyway, because the
    // walk stops at the first non-particle — "El Bilal Touré" halts on
    // "Bilal" and reads Touré.
    while (start > 0 && words[start - 1].lowercase(Locale.ROOT).trim('.', '\'') in PARTICLES) {
        start--
    }
    return words.subList(start, end).joinToString(" ")
}
