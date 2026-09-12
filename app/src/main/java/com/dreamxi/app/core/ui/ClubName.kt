package com.dreamxi.app.core.ui

import java.text.Normalizer
import java.util.Locale

/**
 * What a club is called on screen, and where it files alphabetically.
 *
 * The database stores FBref's short forms — "Barcelona", "Man Utd", "QPR",
 * "West Brom" — because that is what its own feeds use and every crest, join
 * and load is keyed on them. Those names are not what a club is called, so the
 * display name lives here and NOTHING downstream changes: stored names stay the
 * key for data, this is presentation only.
 *
 * SORTING STRIPS INITIALISMS, NOT WORDS. "FC" in FC Barcelona is an
 * abbreviation of the legal form that nobody says aloud, so it files under B.
 * "Real" in Real Madrid is a word people say, so it files under R. The same
 * test puts RCD Espanyol under E, CA Osasuna under O and AFC Bournemouth under
 * B, while Athletic Club, Atlético Madrid and Sporting Gijón stay where their
 * spoken names put them. That is the convention football databases use, and it
 * is why Barcelona moves and Madrid does not.
 */
private val DISPLAY_NAMES = mapOf(
    // --- Premier League -----------------------------------------------------
    "Bournemouth" to "AFC Bournemouth",
    "Blackburn" to "Blackburn Rovers",
    "Bolton" to "Bolton Wanderers",
    "Brighton" to "Brighton & Hove Albion",
    "Huddersfield" to "Huddersfield Town",
    "Manchester Utd" to "Manchester United",
    "Newcastle" to "Newcastle United",
    "Nottingham" to "Nottingham Forest",
    "QPR" to "Queens Park Rangers",
    "Tottenham" to "Tottenham Hotspur",
    "West Brom" to "West Bromwich Albion",
    "West Ham" to "West Ham United",
    "Wolves" to "Wolverhampton Wanderers",
    // Arsenal, Aston Villa, Birmingham City, Blackpool, Brentford, Burnley,
    // Cardiff City, Chelsea, Crystal Palace, Everton, Fulham, Hull City,
    // Leeds United, Leicester City, Liverpool, Luton Town, Manchester City,
    // Middlesbrough, Norwich City, Portsmouth, Reading, Sheffield United,
    // Southampton, Stoke City, Sunderland, Swansea City, Watford and
    // Wigan Athletic are already stored under the name they are known by.

    // --- La Liga ------------------------------------------------------------
    "Almería" to "UD Almería",
    "Atlético Madrid" to "Atlético de Madrid",
    "Barcelona" to "FC Barcelona",
    "Cádiz" to "Cádiz CF",
    "Celta Vigo" to "RC Celta de Vigo",
    "Córdoba" to "Córdoba CF",
    "Eibar" to "SD Eibar",
    "Elche" to "Elche CF",
    "Espanyol" to "RCD Espanyol",
    "Getafe" to "Getafe CF",
    "Girona" to "Girona FC",
    "Granada" to "Granada CF",
    "Huesca" to "SD Huesca",
    "Hércules" to "Hércules CF",
    "La Coruña" to "Deportivo La Coruña",
    "Las Palmas" to "UD Las Palmas",
    "Leganés" to "CD Leganés",
    "Levante" to "Levante UD",
    "Mallorca" to "RCD Mallorca",
    "Málaga" to "Málaga CF",
    "Osasuna" to "CA Osasuna",
    "Racing Santander" to "Racing de Santander",
    "Sevilla" to "Sevilla FC",
    "Sporting Gijón" to "Sporting de Gijón",
    "Tenerife" to "CD Tenerife",
    "Valencia" to "Valencia CF",
    "Valladolid" to "Real Valladolid",
    "Villarreal" to "Villarreal CF",
    "Xerez" to "Xerez CD",
    "Zaragoza" to "Real Zaragoza",
    // Alavés, Athletic Club, Rayo Vallecano, Real Betis, Real Madrid and
    // Real Sociedad are already stored under the name they are known by.
)

/** Two to four capitals: a legal-form abbreviation rather than a spoken word. */
private val INITIALISM = Regex("^[A-Z]{2,4}$")

/** The club's real name, for display. Falls back to the stored name. */
fun clubDisplayName(stored: String): String = DISPLAY_NAMES[stored] ?: stored

/**
 * What the club sorts under: its display name with any leading initialism
 * dropped, accents folded, lowercased.
 */
fun clubSortKey(stored: String): String {
    val words = clubDisplayName(stored).split(' ').filter { it.isNotBlank() }
    if (words.isEmpty()) return ""
    // Never strip the only word: a club called just "FC" would sort as nothing.
    val meaningful = if (words.size > 1 && INITIALISM.matches(words.first())) words.drop(1) else words
    return unaccent(meaningful.joinToString(" ")).lowercase(Locale.ROOT)
}

/** The A-Z header this club belongs under. */
fun clubGroupLetter(stored: String): String {
    val first = clubSortKey(stored).firstOrNull()?.uppercaseChar() ?: return "#"
    return if (first.isLetter()) first.toString() else "#"
}

private fun unaccent(value: String): String =
    Normalizer.normalize(value, Normalizer.Form.NFD).replace(Regex("""\p{Mn}+"""), "")
