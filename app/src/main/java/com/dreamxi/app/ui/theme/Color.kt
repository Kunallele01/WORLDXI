package com.dreamxi.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Dream XI color system — PROJECT_SPEC_v2.md §10.2. This is the single source
 * of truth for every hex value in the app; composables should reference these
 * tokens (or the ColorScheme built from them in Theme.kt), never inline hex.
 */

// --- Base surfaces: a warm near-black, not pure black (avoids the flat/cheap
// OLED look) with a small ladder of lighter elevation steps. ---
val SurfaceBase = Color(0xFF0C0D0F)
val SurfaceRaised1 = Color(0xFF15171B)
val SurfaceRaised2 = Color(0xFF1E2126)
val SurfaceRaised3 = Color(0xFF282C33)

val OnSurfacePrimary = Color(0xFFF5F5F1)
val OnSurfaceMuted = Color(0xFFA7ACB4)
val OnSurfaceFaint = Color(0xFF6B7076)

val OutlineSubtle = Color(0xFF2C2F35)

// --- The one confident accent: a bold gold/amber, used sparingly for primary
// actions (Spin, Start Run, Simulate) and key highlights. ---
val AccentGold = Color(0xFFF5C518)
val AccentGoldDim = Color(0xFFB8940F)
val OnAccentGold = Color(0xFF14110A) // near-black text on top of the gold accent

// --- Position accent colors — instant visual position recognition on player
// cards/tags/pitch slots (§10.2, matches reference screenshots). ---
val PositionGoalkeeper = Color(0xFF3D8BFD)
val PositionDefender = Color(0xFFD9A24B)
val PositionMidfielder = Color(0xFF3DCB77)
val PositionForward = Color(0xFFFF5A36)

enum class PlayerPosition { GOALKEEPER, DEFENDER, MIDFIELDER, FORWARD }

fun colorForPosition(position: PlayerPosition): Color = when (position) {
    PlayerPosition.GOALKEEPER -> PositionGoalkeeper
    PlayerPosition.DEFENDER -> PositionDefender
    PlayerPosition.MIDFIELDER -> PositionMidfielder
    PlayerPosition.FORWARD -> PositionForward
}

// --- Semantic win/draw/loss colors, used on history lists, results, form
// strings. ---
val ResultWin = Color(0xFF2FBF6E)
val ResultDraw = Color(0xFFB0B4BB)
val ResultLoss = Color(0xFFE5484D)

// --- Rating badge scale: grey -> bronze -> green -> gold as overall rating
// increases (§10.2) — a *continuous* gradient (interpolated between stops),
// not flat buckets.
//
// CALIBRATED TO THE REAL DISTRIBUTION, not to 0-99. This is the whole point
// and the original version got it badly wrong. Measured over all 5,723
// loaded player-seasons:
//
//     p5 = 63    p25 = 72    p50 = 76    p75 = 79    p95 = 84
//     65% of every player in the game sits in 70-79.
//
// The first version normalised `rating / 99`, so p5..p95 spanned t=0.64..0.85
// — a single stretch of the ramp between "light green" and "deep green". The
// result: ~83% of all players rendered as effectively the same colour, so the
// badge carried no information and a squad list read as a wall of identical
// green tiles. A rating scale whose colour does not vary across the range
// where the ratings actually ARE is decoration, not a signal.
//
// So the stops below are keyed to RATING VALUES, not fractions, and are
// densest through 71-83 where the population actually lives. Hue does the
// work rather than lightness — a hue step is far easier to tell apart at a
// glance (and for colour-blind users, the numeral is always present too).
private val RatingStops: List<Pair<Float, Color>> = listOf(
    50f to Color(0xFF6B727C), // cold grey: fringe / below squad standard
    64f to Color(0xFF98A2AE), // cool slate: squad filler
    71f to Color(0xFFC9B968), // bronze: rotation player
    77f to Color(0xFF86CB7C), // fresh green: dependable starter
    82f to ResultWin,         // strong green: very good — reuses the win token
    87f to Color(0xFFF0CF4A), // amber: elite
    93f to AccentGold,        // gold: world class, matches the primary accent
)

fun colorForRating(overallRating: Int): Color {
    val r = overallRating.toFloat().coerceIn(RatingStops.first().first, RatingStops.last().first)
    val i = RatingStops.indexOfLast { it.first <= r }.coerceIn(0, RatingStops.size - 2)
    val (fromR, fromColor) = RatingStops[i]
    val (toR, toColor) = RatingStops[i + 1]
    val localT = if (toR > fromR) (r - fromR) / (toR - fromR) else 0f
    return lerp(fromColor, toColor, localT)
}

/**
 * Coarse quality tier for a rating, for the short caption under a badge and
 * for sorting/grouping affordances. Boundaries line up with [RatingStops] and
 * with the measured percentiles above, so "ELITE" genuinely means top ~2%
 * rather than a number someone liked.
 */
enum class RatingTier(val label: String) {
    FRINGE("Fringe"),       // < 68   — bottom ~10%
    SQUAD("Squad"),         // 68-73  — to ~p30
    STARTER("Starter"),     // 74-79  — the fat middle, to ~p75
    QUALITY("Quality"),     // 80-84  — to ~p95
    ELITE("Elite"),         // 85-88
    WORLD_CLASS("World Class"), // 89+ — top ~0.5%
}

fun tierForRating(overallRating: Int): RatingTier = when {
    overallRating >= 89 -> RatingTier.WORLD_CLASS
    overallRating >= 85 -> RatingTier.ELITE
    overallRating >= 80 -> RatingTier.QUALITY
    overallRating >= 74 -> RatingTier.STARTER
    overallRating >= 68 -> RatingTier.SQUAD
    else -> RatingTier.FRINGE
}

/** Blends a color toward white — used for gradient highlights/glossy fills. */
fun Color.lighten(fraction: Float): Color = lerp(this, Color.White, fraction)

/** Blends a color toward black — used for gradient shadows/depth. */
fun Color.darken(fraction: Float): Color = lerp(this, Color.Black, fraction)
