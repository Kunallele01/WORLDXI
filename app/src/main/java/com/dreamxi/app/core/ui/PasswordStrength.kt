package com.dreamxi.app.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.ResultLoss
import com.dreamxi.app.ui.theme.ResultWin

/**
 * How strong a password looks, from 0 to 1.
 *
 * NOT A SECURITY CLAIM, and the scoring is deliberately simple: this exists to
 * tell someone choosing a password that "football" is worse than what they
 * could do in two more seconds, not to certify anything. Supabase enforces the
 * only rule that actually gates the account (eight characters); everything here
 * is encouragement.
 *
 * LENGTH DOMINATES, because it is the one factor that genuinely does. Sixteen
 * characters of lower-case beats eight characters of every class, and a meter
 * that says otherwise teaches the wrong lesson — people add a "1!" to a short
 * password and believe they have solved something.
 */
fun passwordStrength(password: String): Float {
    if (password.isEmpty()) return 0f

    // Up to 0.6 for length, all of it earned by twenty characters.
    val length = (password.length.coerceAtMost(20) / 20f) * 0.6f

    // Up to 0.4 for variety, split evenly across the four classes.
    var classes = 0
    if (password.any { it.isLowerCase() }) classes++
    if (password.any { it.isUpperCase() }) classes++
    if (password.any { it.isDigit() }) classes++
    if (password.any { !it.isLetterOrDigit() }) classes++
    val variety = (classes / 4f) * 0.4f

    var score = length + variety

    // One character repeated, or two alternating, is not a password however
    // long it is — "aaaaaaaaaaaa" would otherwise score better than "Th3re!".
    if (password.toSet().size <= 2) score *= 0.35f

    // Below the length Supabase will accept, nothing can look anything but
    // weak: showing green — or even amber — on a password the server is about
    // to reject would be a lie the player only finds out about after pressing
    // the button. Capped clear of the WEAK boundary rather than exactly on it,
    // which put "Str0ng!" one thousandth into FAIR.
    if (password.length < 8) score = score.coerceAtMost(0.30f)

    return score.coerceIn(0f, 1f)
}

/**
 * Red through gold to green.
 *
 * Gold rather than amber in the middle because it is the app's own accent, so
 * a middling password looks like the rest of the interface rather than like a
 * warning. The ends are the same red and green every result in the app uses.
 */
fun passwordStrengthColour(strength: Float): Color = when {
    strength <= 0.5f -> lerp(ResultLoss, AccentGold, (strength / 0.5f).coerceIn(0f, 1f))
    else -> lerp(AccentGold, ResultWin, ((strength - 0.5f) / 0.5f).coerceIn(0f, 1f))
}

/** One quiet word, so the colour is not the only thing carrying the meaning. */
fun passwordStrengthLabel(strength: Float): String = when {
    strength < 0.34f -> "WEAK"
    strength < 0.67f -> "FAIR"
    else -> "STRONG"
}
