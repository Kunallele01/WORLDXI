package com.dreamxi.app.feature.onboarding

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.core.ui.components.DreamXiPrimaryButton
import com.dreamxi.app.feature.draft.Formation
import com.dreamxi.app.feature.draft.FormationSlot
import com.dreamxi.app.feature.draft.formationById
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnAccentGold
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.OutlineSubtle
import com.dreamxi.app.ui.theme.SurfaceBase
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.SurfaceRaised2
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val PAGE_COUNT = 4

/**
 * What the game is, in four swipes.
 *
 * Shown once, after the front door, and reopenable from the start screen. A
 * stranger arrives at a league picker with no idea why they are choosing a
 * league or what happens to the eleven players afterwards; the idea ("would
 * your XI have won it?") is the whole hook, so it is said before anything is
 * asked of them.
 *
 * The copy is about THIS game's rules, not football in general. Each page
 * carries one idea and one picture, because a wall of explanation is what makes
 * people skip: the Skip button is always there, and costs nothing.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val pager = rememberPagerState(pageCount = { PAGE_COUNT })
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == PAGE_COUNT - 1

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBase)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "HOW IT WORKS",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 1.6.sp,
                color = AccentGold,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            )
            // Always there, and never greyed out: the way past is not hidden.
            Text(
                text = if (last) "" else "Skip",
                style = MaterialTheme.typography.labelLarge,
                color = OnSurfaceMuted,
                modifier = Modifier
                    .clickable(enabled = !last, onClick = onDone)
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }

        HorizontalPager(state = pager, modifier = Modifier.weight(1f)) { page ->
            when (page) {
                0 -> Page(
                    title = "COULD YOUR ALL-TIME XI HAVE WON A LEAGUE?",
                    body = "Pick eleven real footballers from any era, then drop them into a " +
                        "real season and find out what they were worth.",
                ) { PitchPicture(active = pager.currentPage == 0) }

                1 -> Page(
                    title = "DRAFT FROM THE WHOLE HISTORY",
                    body = "Every round spins a random club and season. Take one player from " +
                        "it. The same player in a different year is a different player, so " +
                        "no two drafts look alike.",
                ) { ReelPicture() }

                2 -> Page(
                    title = "TAKE A REAL CLUB'S PLACE",
                    body = "Your XI replaces a club that went down, and plays its real fixture " +
                        "list. Only your 38 matches are simulated. Every other result stays " +
                        "exactly as it happened.",
                ) { TablePicture() }

                else -> Page(
                    title = "THREE WAYS TO PLAY",
                    body = null,
                ) { ModesPicture() }
            }
        }

        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
            Dots(current = pager.currentPage, modifier = Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(16.dp))
            DreamXiPrimaryButton(
                text = if (last) "Let's play" else "Next",
                onClick = {
                    if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun Page(title: String, body: String?, picture: @Composable () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { picture() }
        Spacer(Modifier.height(20.dp))
        Text(
            text = title,
            fontFamily = DisplayFontFamily,
            fontSize = 32.sp,
            lineHeight = 36.sp,
            color = OnSurfacePrimary,
        )
        if (body != null) {
            Spacer(Modifier.height(10.dp))
            Text(text = body, style = MaterialTheme.typography.bodyLarge, color = OnSurfaceMuted)
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun Dots(current: Int, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(PAGE_COUNT) { i ->
            val on = i == current
            Box(
                Modifier
                    .height(8.dp)
                    .width(if (on) 26.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (on) AccentGold else SurfaceRaised2),
            )
        }
    }
}

// ---------------------------------------------------------------- pictures

/** The shapes the dots take, in turn. Eight that look clearly different from each other. */
private val ShowcaseFormations: List<Formation> = listOf(
    "4-3-3", "4-2-3-1", "3-5-2", "4-4-2", "4-1-2-1-2", "5-3-2", "3-4-3", "4-2-2-2",
).map(::formationById)

/**
 * A formation's slots in a fixed order: keeper first, then deepest to highest,
 * left to right. Dot number i is therefore "the same player" in every
 * formation, so when the shape changes each dot travels to a nearby spot
 * rather than all eleven scattering across the pitch.
 */
private fun orderedSlots(formation: Formation): List<FormationSlot> =
    formation.slots.sortedWith(compareByDescending<FormationSlot> { it.y }.thenBy { it.x })

/** [layout] centred on [centre], at [alpha]. Nothing is drawn for an unknown label or a faded-out one. */
private fun DrawScope.drawLabel(layout: TextLayoutResult?, centre: Offset, alpha: Float) {
    if (layout == null || alpha <= 0f) return
    drawText(
        textLayoutResult = layout,
        topLeft = Offset(centre.x - layout.size.width / 2f, centre.y - layout.size.height / 2f),
        alpha = alpha,
    )
}

/** How long after dot i starts moving the next one does: the move ripples through the team. */
private const val STAGGER = 0.04f

/** One full move: the last dot (index 10) needs 10 staggers on top of a whole unit of travel. */
private const val MORPH_END = 1f + 10 * STAGGER

/**
 * The eleven, as gold dots on a pitch, flowing from one formation to the next.
 *
 * It shows what the game is made of: the shape is a choice. Runs only while the
 * page is in view, so it costs nothing once the player has swiped on.
 */
@Composable
private fun PitchPicture(active: Boolean) {
    val pitch = Color(0xFF123524)
    val line = Color(0x33FFFFFF)

    var from by remember { mutableIntStateOf(0) }
    var to by remember { mutableIntStateOf(0) }
    // Starts settled on the first formation, so page one opens as a clean 4-3-3.
    val progress = remember { Animatable(MORPH_END) }

    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        // Swiped away mid-move and back: finish that move before starting another,
        // or the dots would jump.
        if (progress.value < MORPH_END) progress.animateTo(MORPH_END, tween(400, easing = LinearEasing))
        while (true) {
            delay(1700)
            from = to
            to = (to + 1) % ShowcaseFormations.size
            progress.snapTo(0f)
            progress.animateTo(MORPH_END, tween(1500, easing = LinearEasing))
        }
    }

    val fromSlots = remember(from) { orderedSlots(ShowcaseFormations[from]) }
    val toSlots = remember(to) { orderedSlots(ShowcaseFormations[to]) }

    // Every label that can appear, measured once: measuring text on each frame of
    // an animation is the thing that makes it stutter.
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = OnAccentGold,
    )
    val labels = remember(measurer, labelStyle) {
        ShowcaseFormations.flatMap { f -> f.slots.map { it.label } }
            .distinct()
            .associateWith { measurer.measure(it, labelStyle) }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .fillMaxWidth(0.78f)
                .aspectRatio(0.78f)
                .clip(RoundedCornerShape(18.dp))
                .background(pitch)
                .border(1.dp, OutlineSubtle, RoundedCornerShape(18.dp)),
        ) {
            Canvas(Modifier.fillMaxSize().padding(14.dp)) {
                val stroke = 2.dp.toPx()
                drawRect(line, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                drawLine(line, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), stroke)
                drawCircle(line, radius = size.width * 0.14f, center = center, style = androidx.compose.ui.graphics.drawscope.Stroke(stroke))
                val p = progress.value
                for (i in fromSlots.indices) {
                    // Each dot eases on its own clock, a little behind the one before.
                    val t = FastOutSlowInEasing.transform((p - i * STAGGER).coerceIn(0f, 1f))
                    val a = fromSlots[i]
                    val b = toSlots[i]
                    val c = Offset(
                        (a.x + (b.x - a.x) * t) * size.width,
                        (a.y + (b.y - a.y) * t) * size.height,
                    )
                    drawCircle(Color.Black.copy(alpha = 0.35f), radius = 17.dp.toPx(), center = c + Offset(0f, 2.dp.toPx()))
                    drawCircle(AccentGold, radius = 16.dp.toPx(), center = c)

                    // The position written on the dot changes WITH the dot: the old
                    // label fades out over the first half of the move and the new
                    // one fades in over the second, rather than both showing at
                    // once as a smear. A dot whose position does not change keeps
                    // its label at full strength throughout.
                    if (a.label == b.label) {
                        drawLabel(labels[a.label], c, 1f)
                    } else {
                        drawLabel(labels[a.label], c, (1f - 2f * t).coerceIn(0f, 1f))
                        drawLabel(labels[b.label], c, (2f * t - 1f).coerceIn(0f, 1f))
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        // Names the shape it is heading for, as soon as it starts moving.
        Crossfade(targetState = to, label = "formation name") { index ->
            Text(
                text = ShowcaseFormations[index].name,
                fontFamily = DisplayFontFamily,
                fontSize = 22.sp,
                color = AccentGold,
            )
        }
    }
}

/** A few squads dealt one after another, the middle one landed on. */
@Composable
private fun ReelPicture() {
    Column(
        Modifier.fillMaxWidth(0.9f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ReelChip("NEWCASTLE", "2014/15", landed = false)
        ReelChip("SEVILLA", "2022/23", landed = true)
        ReelChip("BURNLEY", "2017/18", landed = false)
    }
}

@Composable
private fun ReelChip(club: String, season: String, landed: Boolean) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth(if (landed) 1f else 0.86f)
            .clip(shape)
            .background(SurfaceRaised1)
            .border(if (landed) 2.dp else 1.dp, if (landed) AccentGold else OutlineSubtle, shape)
            .padding(horizontal = 18.dp, vertical = if (landed) 20.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = club,
            fontFamily = DisplayFontFamily,
            fontSize = if (landed) 26.sp else 20.sp,
            color = if (landed) OnSurfacePrimary else OnSurfaceFaint,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = season,
            style = MaterialTheme.typography.labelLarge,
            color = if (landed) AccentGold else OnSurfaceFaint,
        )
    }
}

/** A league table with one row that is not a real club. */
@Composable
private fun TablePicture() {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth(0.94f)
            .clip(shape)
            .background(SurfaceRaised1)
            .border(1.dp, OutlineSubtle, shape)
            .padding(vertical = 8.dp),
    ) {
        TableRow(1, "Manchester City", real = true)
        TableRow(2, "Arsenal", real = true)
        TableRow(3, "Liverpool", real = true)
        Text(
            text = "...",
            color = OnSurfaceFaint,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 2.dp),
        )
        TableRow(18, "Dream XI", real = false)
        TableRow(19, "Burnley", real = true)
        TableRow(20, "Sheffield United", real = true)
    }
}

@Composable
private fun TableRow(position: Int, name: String, real: Boolean) {
    val ink = if (real) OnSurfaceMuted else OnAccentGold
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (real) Color.Transparent else AccentGold)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = "$position", color = ink, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(30.dp))
        Text(text = name, color = ink, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        if (!real) {
            Text(text = "YOU", color = ink, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.4.sp)
        }
    }
}

/** The three modes, one line each. */
@Composable
private fun ModesPicture() {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ModeCard("DRAFT", "Spin for a random club and season each round, and take one player from it.")
        ModeCard("FREE MODE", "No spins. Build any XI from any club, any season, by hand.")
        ModeCard("WORLD CUP", "The same idea as a tournament: take a bottom-of-group nation's place.")
    }
}

@Composable
private fun ModeCard(title: String, line: String) {
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(SurfaceRaised1)
            .border(1.dp, OutlineSubtle, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(text = title, fontFamily = DisplayFontFamily, fontSize = 22.sp, color = AccentGold)
        Spacer(Modifier.size(2.dp))
        Text(text = line, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceMuted)
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun OnboardingPreview() {
    DreamXITheme { OnboardingScreen(onDone = {}) }
}
