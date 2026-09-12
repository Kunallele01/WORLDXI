package com.dreamxi.app.feature.draft

/**
 * A one-line scouting description of what a player actually DID in that
 * season — "27 goals in 2,593 minutes. Elite scoring rate for a striker." —
 * so a squad list reads as people rather than as numbers.
 *
 * WHY THIS IS BUILT FROM RAW STATS AND NOT THE ATTRIBUTE COLUMNS.
 * `player_season_stats` carries six tidy 0-99 attributes (finishing,
 * creation, buildup, carrying, defense, physical) which look like exactly the
 * right input for this. They are not usable for prose, for two reasons:
 *
 *  - They are percentiles computed WITHIN a position group, so the same
 *    number means different things for different roles. Measured on the
 *    loaded data: Kurt Zouma (5 goals, a centre-back) scores 98 for finishing
 *    — level with Haaland's 36-goal season, because he is being ranked
 *    against other centre-backs. Calling Zouma an "elite finisher" would be
 *    the direct consequence of trusting that column.
 *  - Most of the inputs they were derived from were never loaded. xG, xA, key
 *    passes, pass completion, dribbles, progressive passes/carries,
 *    clearances and aerials are 0% populated, and interceptions are present
 *    but all zero. Whatever those six columns are measuring, it is not what
 *    their names claim.
 *
 * So this uses only the columns with real data: goals, assists, shots, shots
 * on target, tackles, cards, appearances, minutes, and (keepers only) save
 * percentage and clean sheets.
 *
 * THREE RULES, each of which exists because breaking it produced a visibly
 * wrong line when this was first run against real squads:
 *
 *  1. THRESHOLDS ARE PER ROLE. 0.20 goals per 90 is a poor striker and an
 *     outstanding defensive midfielder. [RoleBaseline] holds the measured
 *     75th/90th percentiles of the loaded population, per role.
 *  2. RATES NEED AN ABSOLUTE FLOOR TOO. Ferland Mendy's 2 goals in 1,734
 *     minutes clears the fullback 90th percentile and was described as an
 *     "elite scoring rate". Two goals is not elite anything. A rate claim now
 *     also needs a countable amount of the thing behind it.
 *  3. THERE IS ALWAYS A LINE. Toni Kroos fell through every threshold and got
 *     nothing at all, which looks like a bug in a list where his neighbours
 *     all have text. When nothing is remarkable, state the season plainly.
 */

/** Per-90 cutoffs for one role, taken from the loaded population. */
private data class RoleBaseline(
    val noun: String,
    val goalsP75: Double,
    val goalsP90: Double,
    val assistsP75: Double,
    val assistsP90: Double,
    val combinedP75: Double,
    val combinedP90: Double,
    val tacklesP90: Double,
    val shotAccuracyP90: Int,
    /** Minimum goals before a rate may be called elite (rule 2). */
    val eliteGoalFloor: Int,
    /** Minimum assists before a rate may be called elite. */
    val eliteAssistFloor: Int,
)

// Measured 2026-08-29 over all loaded player-seasons with >= 450 minutes
// (n=5,723). Re-measure when more leagues or seasons are loaded: these are
// population facts, not design choices, and they drift as the population grows.
private val Baselines = mapOf(
    "GK" to RoleBaseline("goalkeeper", 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.05, 0, 99, 99),
    "CB" to RoleBaseline("centre-back", .07, .11, .04, .08, .11, .15, 1.36, 43, 4, 4),
    "FB" to RoleBaseline("full-back", .06, .10, .12, .20, .17, .25, 1.78, 40, 4, 5),
    "DM" to RoleBaseline("defensive midfielder", .08, .14, .08, .14, .15, .23, 1.85, 40, 4, 4),
    "CM" to RoleBaseline("central midfielder", .14, .20, .14, .21, .26, .38, 1.65, 43, 6, 6),
    "CAM" to RoleBaseline("attacking midfielder", .27, .42, .23, .32, .47, .63, 1.28, 47, 9, 7),
    "Winger" to RoleBaseline("winger", .29, .43, .22, .32, .47, .65, 1.41, 48, 9, 7),
    "ST" to RoleBaseline("striker", .47, .62, .18, .26, .62, .77, 0.82, 51, 12, 6),
)

// Minutes percentiles across the whole population, for the workload clause.
private const val MINUTES_EVER_PRESENT = 2900 // ~p90
private const val MINUTES_REGULAR = 1690      // ~p50
private const val MINUTES_FRINGE = 1076       // ~p25

/**
 * A per-90 rate computed over a short spell is mostly noise, so no rate-based
 * claim is made below this. Roughly ten full matches.
 */
private const val MINUTES_FOR_RATE_CLAIM = 900

/** 38 games, for turning minutes into a share of the season. */
private const val MINUTES_IN_A_SEASON = 3420

// Keeper cutoffs (n=285 keeper-seasons).
private const val SAVE_PCT_ELITE = 74.7
private const val SAVE_PCT_GOOD = 71.4
private const val SAVE_PCT_POOR = 63.2
private const val CLEAN_SHEET_PCT_ELITE = 42
private const val CLEAN_SHEET_PCT_GOOD = 33

/**
 * Nationality as a flag plus country name, e.g. "\uD83C\uDDEB\uD83C\uDDF7 France".
 *
 * Flag EMOJI rather than image assets: no files to ship, no licensing question,
 * and it works offline. Built by mapping the country name to its ISO code and
 * that to regional-indicator codepoints. Countries we have no code for fall
 * back to the bare name, which is still correct, just flagless.
 */
fun nationalityLabel(country: String?): String? {
    val name = country?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val code = CountryCodes[name] ?: return name
    val flag = code.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }
        .joinToString("")
    return "$flag $name"
}

/**
 * Roles for whom attacking output is close to meaningless, and the team's
 * defensive record is the honest description of the season.
 *
 * A centre-back's two goals are noise. The columns that would actually
 * describe his defending — interceptions, clearances, aerial duels — are 0%
 * populated in the loaded data, so the only real defensive signal available is
 * how many the side conceded while he was in it. That is a genuinely good one:
 * "ever-present in a back line that conceded 29, fewest in the league" says
 * more about a defender's season than any per-90 rate we can compute.
 */
private val DEFENSIVE_ROLES = setOf("CB", "FB", "DM")

fun scoutLine(p: SquadPlayer, defence: DefensiveRecord? = null): String? {
    if (p.minutes <= 0) return null
    val base = Baselines[p.role] ?: return null

    // Defenders get the team's defensive record plus something individual.
    // The record alone is true but repetitive — every centre-back at Liverpool
    // 2019/20 would read identically — so the second clause has to be about
    // the man rather than the back line, and the generic workload qualifier is
    // suppressed because the record already states how much he played.
    if (p.role in DEFENSIVE_ROLES) {
        val record = defensiveHeadline(p, defence)
        if (record != null) {
            return listOfNotNull(record, defensiveDetail(p, base))
                .joinToString(" ")
                .takeIf { it.isNotBlank() }
        }
    }

    val headline = when {
        p.role == "GK" -> keeperHeadline(p, defence)
        else -> outfieldHeadline(p, base)
    }
    val qualifier = qualifier(p)

    return listOfNotNull(headline ?: plainSummary(p, base), qualifier)
        .joinToString(" ")
        .takeIf { it.isNotBlank() }
}

/** The notable thing, if there is one. Null when nothing clears a threshold. */
private fun outfieldHeadline(p: SquadPlayer, base: RoleBaseline): String? {
    val rateAllowed = p.minutes >= MINUTES_FOR_RATE_CLAIM
    val g90 = p.goals * 90.0 / p.minutes
    val a90 = p.assists * 90.0 / p.minutes
    val ga90 = g90 + a90
    val t90 = p.tackles * 90.0 / p.minutes

    if (rateAllowed) {
        // Elite tiers: percentile AND a real count behind it.
        if (g90 >= base.goalsP90 && p.goals >= base.eliteGoalFloor) {
            return "${p.goals} goals in ${p.minutes.formatted()} minutes — an elite return for ${base.noun.withArticle()}."
        }
        if (a90 >= base.assistsP90 && p.assists >= base.eliteAssistFloor) {
            return "${p.assists} assists — a genuine creative hub."
        }
        if (ga90 >= base.combinedP90 && p.goals + p.assists >= base.eliteGoalFloor) {
            return "${p.goals} goals and ${p.assists} assists — heavily involved in everything good."
        }
        // Solid tiers: lower bar, correspondingly plainer language.
        if (g90 >= base.goalsP75 && p.goals >= 3) {
            return "${p.goals} goals, a strong return from ${base.noun.withArticle()}."
        }
        if (a90 >= base.assistsP75 && p.assists >= 3) {
            return "${p.assists} assists, a reliable creative outlet."
        }
        if (ga90 >= base.combinedP75 && p.goals + p.assists >= 4) {
            return "${p.goals}G ${p.assists}A — a steady attacking contributor."
        }
        // Defensive VOLUME only, and never framed as quality. Verified across
        // all 10 loaded seasons: centre-backs at the best defences average
        // FEWER tackles+interceptions per 90 than those at the worst, because
        // good defenders position instead of lunging. "Busy" is supportable;
        // "good" is not.
        if (t90 >= base.tacklesP90 && p.tackles >= 30) {
            return "A busy tackler — ${"%.1f".format(t90)} per 90 across ${p.appearances} games."
        }
        if (p.shots >= 25 && p.shotsOnTarget > 0) {
            val accuracy = p.shotsOnTarget * 100 / p.shots
            if (accuracy >= base.shotAccuracyP90) {
                return "Accurate in front of goal — $accuracy% of ${p.shots} shots on target."
            }
        }
    }
    return null
}

/**
 * A defender's season, led by what the defence actually did.
 *
 * Only claimed when he played enough of it to have been part of the record —
 * crediting a rotation centre-back with a title-winning defence he watched
 * from the bench would be exactly the kind of flattering nonsense the rest of
 * this file avoids.
 */
private fun defensiveHeadline(p: SquadPlayer, defence: DefensiveRecord?): String? {
    if (defence == null || p.minutes < MINUTES_FOR_RATE_CLAIM) return null
    val third = (defence.teamsInLeague + 2) / 3
    val standing = when {
        defence.rank == 1 -> "the best defence in the league"
        defence.rank <= 3 -> "one of the three meanest defences in the league"
        defence.rank <= third -> "one of the better defences in the league"
        defence.rank > defence.teamsInLeague - third -> "one of the leakiest defences in the league"
        else -> null
    }
    val share = p.minutes * 100 / MINUTES_IN_A_SEASON
    val presence = when {
        share >= 85 -> "Ever-present in"
        share >= 55 -> "A regular in"
        else -> "Part of"
    }
    return when {
        standing != null -> "$presence $standing — ${defence.goalsAgainst} conceded."
        else -> "$presence a side that conceded ${defence.goalsAgainst} in ${defence.teamsInLeague * 2 - 2} games."
    }
}

/**
 * The individual half of a defender's line: the one thing about HIM worth
 * mentioning, given how little defensive data survived the load.
 *
 * Ordered by how much it actually distinguishes him. Assists separate an
 * attacking full-back from a stopper more sharply than anything else
 * available; tackle volume is a workrate note and never a quality claim,
 * because defensive volume is inversely correlated with defensive quality in
 * this dataset.
 */
private fun defensiveDetail(p: SquadPlayer, base: RoleBaseline): String? {
    val a90 = p.assists * 90.0 / p.minutes
    val g90 = p.goals * 90.0 / p.minutes
    val t90 = p.tackles * 90.0 / p.minutes
    val roleNoun = base.noun
    return when {
        p.redCards >= 2 -> "Sent off ${p.redCards.spelled()} times."
        p.redCards == 1 -> "Sent off once."
        a90 >= base.assistsP90 && p.assists >= 5 ->
            "${p.assists} assists is exceptional from ${roleNoun.withArticle()}."
        g90 >= base.goalsP90 && p.goals >= 5 ->
            "Also weighed in with ${p.goals} goals."
        a90 >= base.assistsP75 && p.assists >= 3 ->
            "${p.assists} assists going forward."
        t90 >= base.tacklesP90 && p.tackles >= 30 ->
            "A busy tackler at ${"%.1f".format(t90)} per 90."
        p.yellowCards >= 10 -> "Booked ${p.yellowCards} times."
        else -> null
    }
}

private fun keeperHeadline(p: SquadPlayer, defence: DefensiveRecord?): String? {
    val save = p.savePct
    val cleanSheetPct = if (p.appearances > 0) p.cleanSheets * 100 / p.appearances else 0
    return when {
        save != null && save >= SAVE_PCT_ELITE ->
            "Saved ${"%.0f".format(save)}% of the shots he faced, with ${p.cleanSheets} clean sheets."
        defence != null && defence.rank == 1 && p.minutes >= MINUTES_FOR_RATE_CLAIM ->
            "Kept goal for the best defence in the league — ${defence.goalsAgainst} conceded."
        cleanSheetPct >= CLEAN_SHEET_PCT_ELITE ->
            "${p.cleanSheets} clean sheets in ${p.appearances} games."
        save != null && save >= SAVE_PCT_GOOD ->
            "A dependable ${"%.0f".format(save)}% save rate over ${p.appearances} games."
        cleanSheetPct >= CLEAN_SHEET_PCT_GOOD ->
            "${p.cleanSheets} clean sheets in ${p.appearances} games."
        // Only call a keeper's season poor when the clean sheets agree.
        // Save percentage alone punishes keepers behind dominant defences:
        // Ederson's treble-winning 2022/23 shows 59% saves because City
        // conceded few but high-quality chances, and describing that as "a
        // difficult season" would be flatly wrong.
        save != null && save <= SAVE_PCT_POOR &&
            cleanSheetPct < CLEAN_SHEET_PCT_GOOD && p.minutes >= MINUTES_FOR_RATE_CLAIM ->
            "A difficult season between the posts — ${"%.0f".format(save)}% of shots saved."
        else -> null
    }
}

/**
 * The always-available fallback (rule 3). States the season without dressing
 * it up, so an unremarkable player reads as unremarkable rather than as a
 * rendering bug.
 */
private fun plainSummary(p: SquadPlayer, base: RoleBaseline): String {
    val games = if (p.appearances > 0) "${p.appearances} games" else "${p.minutes.formatted()} minutes"
    return when {
        p.role == "GK" -> "Played $games in goal."
        p.goals > 0 && p.assists > 0 -> "${p.goals}G ${p.assists}A in $games."
        p.goals > 0 -> "${p.goals} ${if (p.goals == 1) "goal" else "goals"} in $games."
        p.assists > 0 -> "${p.assists} ${if (p.assists == 1) "assist" else "assists"} in $games."
        p.tackles > 0 -> "$games at ${base.noun}, ${p.tackles} tackles."
        else -> "Played $games at ${base.noun}."
    }
}

/**
 * The trailing qualifier: workload or discipline, whichever is more notable.
 *
 * Workload is the fact the rating cannot express — a 91-rated player with 168
 * minutes is a trap, and the draft explicitly lets you pick him.
 */
private fun qualifier(p: SquadPlayer): String? = when {
    p.redCards >= 2 -> "Sent off ${p.redCards.spelled()} times."
    p.redCards == 1 -> "Sent off once."
    p.minutes >= MINUTES_EVER_PRESENT -> "Virtually ever-present."
    p.minutes < MINUTES_FRINGE ->
        "Only ${p.minutes.formatted()} minutes — a rotation option, not a regular."
    p.minutes < MINUTES_REGULAR -> "In and out of the side."
    else -> null
}

/** Small counts read better as words in prose: "Sent off twice", not "2 times". */
private fun Int.spelled(): String = when (this) {
    2 -> "twice"
    3 -> "three"
    else -> toString()
}

/** "striker" -> "a striker"; "attacking midfielder" -> "an attacking midfielder". */
private fun String.withArticle(): String =
    if (first().lowercaseChar() in "aeiou") "an $this" else "a $this"

/**
 * Country name -> ISO 3166-1 alpha-2, for flag emoji.
 *
 * Only the countries actually present in the loaded data, plus EA's own naming
 * quirks ("Korea Republic", "Republic of Ireland", "China PR"), which do not
 * match any standard list and are the reason this is a hand-checked map rather
 * than a locale lookup.
 */
private val CountryCodes: Map<String, String> = mapOf(
    "England" to "GB", "Scotland" to "GB", "Wales" to "GB", "Northern Ireland" to "GB",
    "Republic of Ireland" to "IE", "Ireland" to "IE",
    "Spain" to "ES", "France" to "FR", "Germany" to "DE", "Italy" to "IT",
    "Portugal" to "PT", "Netherlands" to "NL", "Belgium" to "BE", "Croatia" to "HR",
    "Serbia" to "RS", "Switzerland" to "CH", "Austria" to "AT", "Denmark" to "DK",
    "Sweden" to "SE", "Norway" to "NO", "Finland" to "FI", "Iceland" to "IS",
    "Poland" to "PL", "Czech Republic" to "CZ", "Czechia" to "CZ", "Slovakia" to "SK",
    "Hungary" to "HU", "Romania" to "RO", "Bulgaria" to "BG", "Greece" to "GR",
    "Turkey" to "TR", "Ukraine" to "UA", "Russia" to "RU", "Belarus" to "BY",
    "Slovenia" to "SI", "Bosnia and Herzegovina" to "BA", "North Macedonia" to "MK",
    "Albania" to "AL", "Kosovo" to "XK", "Montenegro" to "ME",
    "Brazil" to "BR", "Argentina" to "AR", "Uruguay" to "UY", "Colombia" to "CO",
    "Chile" to "CL", "Peru" to "PE", "Ecuador" to "EC", "Paraguay" to "PY",
    "Venezuela" to "VE", "Bolivia" to "BO",
    "Mexico" to "MX", "United States" to "US", "Canada" to "CA", "Costa Rica" to "CR",
    "Jamaica" to "JM", "Panama" to "PA", "Honduras" to "HN", "Curacao" to "CW",
    "Morocco" to "MA", "Algeria" to "DZ", "Tunisia" to "TN", "Egypt" to "EG",
    "Senegal" to "SN", "Ivory Coast" to "CI", "Cote d'Ivoire" to "CI",
    "Ghana" to "GH", "Nigeria" to "NG", "Cameroon" to "CM", "Mali" to "ML",
    "Guinea" to "GN", "Burkina Faso" to "BF", "DR Congo" to "CD", "Congo" to "CG",
    "Gabon" to "GA", "Togo" to "TG", "Benin" to "BJ", "Zimbabwe" to "ZW",
    "South Africa" to "ZA", "Kenya" to "KE", "Angola" to "AO", "Cape Verde" to "CV",
    "Guinea-Bissau" to "GW", "Gambia" to "GM", "Sierra Leone" to "SL",
    "Equatorial Guinea" to "GQ", "Mozambique" to "MZ", "Madagascar" to "MG",
    "Japan" to "JP", "Korea Republic" to "KR", "South Korea" to "KR",
    "China PR" to "CN", "China" to "CN", "Australia" to "AU", "New Zealand" to "NZ",
    "Iran" to "IR", "Iraq" to "IQ", "Israel" to "IL", "Saudi Arabia" to "SA",
    "Uzbekistan" to "UZ", "Armenia" to "AM", "Georgia" to "GE", "Azerbaijan" to "AZ",
    "India" to "IN", "Philippines" to "PH", "Indonesia" to "ID",
)

/** 2593 -> "2,593". Long numbers are unreadable in prose without it. */
private fun Int.formatted(): String =
    toString().reversed().chunked(3).joinToString(",").reversed()
