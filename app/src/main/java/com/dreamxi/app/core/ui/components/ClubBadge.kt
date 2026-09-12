package com.dreamxi.app.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnAccentGold
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.OutlineSubtle
import java.text.Normalizer
import java.util.Locale

/**
 * A club's crest, or a generated stand-in when we don't have one.
 *
 * Deliberately built as a swappable layer. Real club crests are registered
 * trademarks, so whether they ship at all is a decision that can change — and
 * if they ever have to be pulled, that must be a matter of deleting files, not
 * redesigning screens. So:
 *
 *   - if `res/drawable-nodpi/crest_<slug>.png` exists, it is drawn;
 *   - otherwise a club-coloured shield with the club's initials is generated
 *     on device.
 *
 * All 53 clubs currently loaded have a crest. The fallback still matters and
 * still has to look intentional rather than broken: the club list grows by
 * roughly 3-5 per league per extra season loaded, and by ~25 per new league,
 * so "we have them all" is a state this drifts out of every time the dataset
 * expands rather than a state it stays in.
 */
internal fun crestSlug(clubName: String): String =
    // Accents are STRIPPED, not replaced. Android resource names allow only
    // [a-z0-9_], and lowercase() leaves an accented character intact — so
    // "Alaves" with an acute e would slug to "alav_s" and never find
    // "crest_alaves.png". Normalising to NFD and dropping the combining marks
    // folds it to plain ASCII first, which is what the asset filenames use.
    Normalizer.normalize(clubName, Normalizer.Form.NFD)
        .replace(Regex("""\p{Mn}+"""), "")
        .lowercase(Locale.ROOT)
        .replace("&", "and")
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')

/**
 * Initials for the stand-in. Two letters from two-or-more-word names
 * ("Real Madrid" -> RM), three from single-word ones ("Barcelona" -> BAR),
 * so the badge is never a lonely single character.
 */
private fun initialsFor(clubName: String): String {
    val words = clubName.split(' ', '-').filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> words.take(3).joinToString("") { it.first().uppercase() }
        else -> clubName.take(3).uppercase(Locale.ROOT)
    }
}

/**
 * A stable colour per club, derived from its name.
 *
 * Not the club's real colours — we don't have those, and guessing them would
 * be wrong more often than right. What matters is that the same club is always
 * the same colour so it becomes recognisable across spins, and that the hue is
 * spread widely enough that neighbouring clubs never collide. Saturation and
 * lightness are fixed in a band that stays legible on the near-black surface.
 */
private fun clubColor(clubName: String): Color {
    val hash = clubName.fold(0) { acc, c -> acc * 31 + c.code }
    val hue = ((hash % 360) + 360) % 360
    return Color.hsl(hue.toFloat(), saturation = 0.42f, lightness = 0.46f)
}

@Composable
fun ClubBadge(
    clubName: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val context = LocalContext.current
    // Resolved by name so that dropping a file into res/drawable is the entire
    // integration step — no generated mapping table to keep in sync.
    val resId = remember(clubName) {
        runCatching {
            context.resources.getIdentifier(
                "crest_${crestSlug(clubName)}", "drawable", context.packageName,
            )
        }.getOrNull() ?: 0
    }
    val shape = RoundedCornerShape(percent = 22)

    if (resId != 0) {
        Image(
            painter = painterResource(resId),
            contentDescription = clubName,
            modifier = modifier.size(size),
        )
        return
    }

    val base = clubColor(clubName)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(base)
            .border(1.dp, OutlineSubtle, shape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initialsFor(clubName),
            fontFamily = DisplayFontFamily,
            fontSize = (size.value * 0.42f).sp,
            lineHeight = (size.value * 0.44f).sp,
            color = if (base.luminance() > 0.5f) OnAccentGold else OnSurfacePrimary,
            textAlign = TextAlign.Center,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0D0F)
@Composable
private fun ClubBadgePreview() {
    DreamXITheme {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                "Real Madrid", "Barcelona", "Manchester City",
                "Sheffield United", "Athletic Club", "Luton Town",
            ).forEach { ClubBadge(clubName = it, size = 48.dp) }
        }
    }
}
