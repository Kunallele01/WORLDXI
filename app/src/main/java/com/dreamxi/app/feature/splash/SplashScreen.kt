package com.dreamxi.app.feature.splash

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.R
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.SurfaceBase
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.SurfaceRaised2
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * The drawn intros are over in two seconds, which is right for them: each is a
 * single gesture — a strike, a formation snapping into place, three cells
 * settling — and holding on a finished gesture is dead air.
 */
private const val DRAWN_MS = 2000

/**
 * The painted one needs longer, and that is a property of the medium rather
 * than a preference. A drawn intro is legible the instant it resolves; an
 * illustration has depth to take in — a crowd, a lit pitch, four pylons — and
 * at two seconds it was gone before any of it registered.
 */
private const val ARTWORK_MS = 3400

/**
 * The four ways the app can open.
 *
 * FOUR DIFFERENT IDEAS, not four camera angles on one. An earlier version
 * offered three trajectories into the same goal, which is variety nobody
 * notices — by the third launch it is the same animation. These differ in what
 * they are ABOUT and in how they move:
 *
 *  - [Strike] is kinetic: a ball hit into a net.
 *  - [LineUp] is structural: eleven players taking their positions.
 *  - [Eras] is typographic: seasons rolling past and settling.
 *  - [Floodlights] is atmospheric: a stadium coming on around you.
 *  - [Stadium] is the same idea done with ARTWORK rather than geometry, to see
 *    whether an illustrated frame earns its download.
 *
 * Two of them are about the game's actual mechanic — assembling an eleven, and
 * mixing seasons — which is a better use of two seconds than decoration.
 */
internal enum class Intro(val durationMs: Int) {
    Strike(DRAWN_MS),
    LineUp(DRAWN_MS),
    Eras(DRAWN_MS),
    Floodlights(DRAWN_MS),
    Stadium(ARTWORK_MS),
}

/**
 * The opening.
 *
 * DRAWN, NOT ASSETS. Every one of these is a Canvas or plain composables, which
 * keeps the whole set at a few kilobytes rather than four videos, matches the
 * app's palette exactly by construction, and scales to any screen without a
 * single exported frame.
 *
 * It is also deliberately short and never blocks: the timer runs regardless of
 * what the app is loading behind it, and there is no ViewModel or repository in
 * the way, so it can never delay a cold start.
 */
@Composable
fun SplashScreen(
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Chosen once, so a recomposition mid-flight cannot switch animation.
    val intro = remember { Intro.entries[Random.nextInt(Intro.entries.size)] }
    IntroStage(intro = intro, onFinished = onFinished, modifier = modifier)
}

/** One intro, with its variant fixed so a preview can pin a single one. */
@Composable
internal fun IntroStage(
    intro: Intro,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(intro) {
        progress.animateTo(1f, tween(intro.durationMs, easing = LinearEasing))
        onFinished()
    }
    val t = progress.value

    Box(
        modifier = modifier.fillMaxSize().background(SurfaceBase),
        contentAlignment = Alignment.Center,
    ) {
        when (intro) {
            Intro.Strike -> StrikeIntro(t)
            Intro.LineUp -> LineUpIntro(t)
            Intro.Eras -> ErasIntro(t)
            Intro.Floodlights -> FloodlightIntro(t)
            Intro.Stadium -> StadiumIntro(t)
        }
    }
}

/**
 * The name, shared by all four.
 *
 * [from] is when it starts arriving, which differs per intro: the title should
 * land ON the moment each animation builds to, not at a fixed clock time.
 */
@Composable
private fun BoxScope.IntroTitle(t: Float, from: Float, topPadding: Int = 0) {
    val alpha = ((t - from) / 0.22f).coerceIn(0f, 1f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .align(Alignment.Center)
            .padding(top = topPadding.dp)
            .alpha(alpha),
    ) {
        Text(
            text = "ERA XI",
            fontFamily = DisplayFontFamily,
            fontSize = 52.sp,
            letterSpacing = 3.sp,
            color = OnSurfacePrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "ELEVEN PLAYERS · EVERY ERA",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 2.sp,
            color = OnSurfaceFaint,
            textAlign = TextAlign.Center,
        )
    }
}

// ------------------------------------------------------------- 1. the strike

/**
 * A ball struck into the net.
 *
 * The kinetic one. The net is a grid whose strands are polylines rather than
 * straight lines, so the bulge can vary along their length — a single line
 * could only move as a whole, which reads as the net sliding rather than
 * stretching.
 */
@Composable
private fun BoxScope.StrikeIntro(t: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val goalW = w * 0.66f
        val goalH = h * 0.24f
        val left = (w - goalW) / 2f
        val top = h * 0.18f
        val right = left + goalW
        val bottom = top + goalH

        val impact = Offset(w * 0.70f, h * 0.28f)
        val sinceHit = ((t - 0.5f) / 0.5f).coerceAtLeast(0f)
        val ripple = if (t < 0.5f) 0f else exp(-sinceHit * 5f) * goalH * 0.18f

        drawNet(left, top, right, bottom, impact, ripple)

        val frame = Color(0xFFE8E8E4)
        val postWidth = (w * 0.011f).coerceAtLeast(3f)
        drawLine(frame, Offset(left, top), Offset(right, top), postWidth, cap = StrokeCap.Round)
        drawLine(frame, Offset(left, top), Offset(left, bottom), postWidth, cap = StrokeCap.Round)
        drawLine(frame, Offset(right, top), Offset(right, bottom), postWidth, cap = StrokeCap.Round)
        drawLine(
            frame.copy(alpha = 0.25f),
            Offset(w * 0.06f, bottom), Offset(w * 0.94f, bottom), postWidth * 0.5f,
        )

        val flight = (t / 0.5f).coerceIn(0f, 1f)
        val eased = 1f - (1f - flight) * (1f - flight)
        val from = Offset(w * 0.12f, h * 0.88f)
        val control = Offset((from.x + impact.x) / 2f, (from.y + impact.y) / 2f - h * 0.22f)
        val ball = quadratic(from, control, impact, eased)
        val ballR = (w * 0.028f).coerceAtLeast(6f)

        if (flight > 0.02f) {
            for (i in 1..6) {
                val back = (eased - i * 0.045f).coerceAtLeast(0f)
                drawCircle(
                    color = AccentGold.copy(alpha = 0.16f * (1f - i / 7f)),
                    radius = ballR * (1f - i * 0.08f),
                    center = quadratic(from, control, impact, back),
                )
            }
        }
        drawCircle(Color(0xFFF7F7F3), ballR, ball)
        drawCircle(SurfaceBase, ballR * 0.34f, ball)
    }
    IntroTitle(t, from = 0.52f, topPadding = 210)
}

private fun DrawScope.drawNet(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    impact: Offset,
    ripple: Float,
) {
    val mesh = Color(0xFFE8E8E4).copy(alpha = 0.20f)
    val falloff = (right - left) * 0.22f
    val columns = 16
    val rows = 7

    fun displace(x: Float, y: Float): Offset {
        if (ripple <= 0f) return Offset(x, y)
        val d = hypot(x - impact.x, y - impact.y)
        return Offset(x, y + exp(-d / falloff) * ripple * sin(1f + d / falloff))
    }

    for (c in 0..columns) {
        val x = left + (right - left) * c / columns
        var prev = displace(x, top)
        for (r in 1..rows) {
            val next = displace(x, top + (bottom - top) * r / rows)
            drawLine(mesh, prev, next, 1.6f)
            prev = next
        }
    }
    for (r in 0..rows) {
        val y = top + (bottom - top) * r / rows
        var prev = displace(left, y)
        for (c in 1..columns) {
            val next = displace(left + (right - left) * c / columns, y)
            drawLine(mesh, prev, next, 1.6f)
            prev = next
        }
    }
}

// ------------------------------------------------------------ 2. the line-up

/** A 4-3-3, in the same normalised coordinates the real pitch uses. */
private val LINE_UP = listOf(
    0.50f to 0.93f,
    0.16f to 0.75f, 0.38f to 0.78f, 0.62f to 0.78f, 0.84f to 0.75f,
    0.28f to 0.55f, 0.50f to 0.58f, 0.72f to 0.55f,
    0.16f to 0.32f, 0.50f to 0.26f, 0.84f to 0.32f,
)

/**
 * Eleven players taking their positions.
 *
 * The structural one, and the one closest to what the app is: the side
 * assembles itself. Each man rises from below the frame on his own stagger and
 * overshoots slightly before settling, because everything that arrives at
 * exactly its destination looks mechanical.
 */
@Composable
private fun BoxScope.LineUpIntro(t: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val pitchTop = h * 0.14f
        val pitchBottom = h * 0.98f
        val pitchLeft = w * 0.06f
        val pitchRight = w * 0.94f

        // Faint markings, drawn in first so the players land ON something.
        val lineAlpha = (t / 0.25f).coerceIn(0f, 1f) * 0.16f
        val line = Color(0xFFE8E8E4).copy(alpha = lineAlpha)
        drawRect(
            color = line,
            topLeft = Offset(pitchLeft, pitchTop),
            size = Size(pitchRight - pitchLeft, pitchBottom - pitchTop),
            style = Stroke(width = 2f),
        )
        drawLine(
            line,
            Offset(pitchLeft, (pitchTop + pitchBottom) / 2f),
            Offset(pitchRight, (pitchTop + pitchBottom) / 2f),
            2f,
        )
        drawCircle(
            color = line,
            radius = (pitchRight - pitchLeft) * 0.16f,
            center = Offset(w / 2f, (pitchTop + pitchBottom) / 2f),
            style = Stroke(width = 2f),
        )

        val dotR = (w * 0.021f).coerceAtLeast(5f)
        LINE_UP.forEachIndexed { index, (fx, fy) ->
            // Staggered so they arrive as a team rather than as a block.
            val start = 0.06f + index * 0.028f
            val local = ((t - start) / 0.30f).coerceIn(0f, 1f)
            if (local <= 0f) return@forEachIndexed

            // Ease out with a small overshoot, then settle.
            val eased = 1f - (1f - local) * (1f - local)
            val overshoot = sin(local * Math.PI.toFloat()) * 0.035f * (1f - local)

            val targetX = pitchLeft + (pitchRight - pitchLeft) * fx
            val targetY = pitchTop + (pitchBottom - pitchTop) * fy
            val y = targetY + (h * 0.35f) * (1f - eased) - h * overshoot

            val isKeeper = index == 0
            drawCircle(
                color = if (isKeeper) AccentGold else Color(0xFFF7F7F3),
                radius = dotR * (0.7f + 0.3f * eased),
                center = Offset(targetX, y),
                alpha = local,
            )
            // A short shadow, so they read as standing on the pitch.
            drawCircle(
                color = Color.Black.copy(alpha = 0.35f * local),
                radius = dotR * 0.55f,
                center = Offset(targetX, y + dotR * 1.5f),
            )
        }
    }
    IntroTitle(t, from = 0.60f, topPadding = 0)
}

// ---------------------------------------------------------------- 3. the eras

/** The seasons actually in the database, so the intro promises nothing extra. */
private val SEASONS = listOf("2019/20", "2020/21", "2021/22", "2022/23", "2023/24")

/**
 * Seasons rolling past and settling, like a departure board.
 *
 * The typographic one, and the most literal statement of the mechanic: three
 * cells land on three different years, which is exactly what an XI here is.
 *
 * Only real seasons appear. Flashing 1970 or 1998 would look wonderful and
 * would be advertising players the database does not have.
 */
@Composable
private fun BoxScope.ErasIntro(t: Float) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.align(Alignment.Center).padding(bottom = 130.dp),
    ) {
        repeat(3) { cell ->
            // Each cell settles later than the last, so the row lands left to
            // right rather than all at once.
            val settleAt = 0.30f + cell * 0.11f
            val settled = t >= settleAt
            val label = if (settled) {
                SEASONS[(cell * 2 + 1) % SEASONS.size]
            } else {
                // Cycling fast, slowing as it approaches its stop.
                val speed = 34f
                SEASONS[((t * speed).toInt() + cell * 3) % SEASONS.size]
            }
            // A small settle-bounce as it stops.
            val bounce = if (settled) {
                (1f - ((t - settleAt) / 0.10f).coerceIn(0f, 1f)) * 4f
            } else {
                0f
            }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .padding(top = bounce.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (settled) SurfaceRaised2 else SurfaceRaised1)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    text = label,
                    fontFamily = DisplayFontFamily,
                    fontSize = 26.sp,
                    color = if (settled) AccentGold else OnSurfaceFaint,
                )
            }
        }
    }
    IntroTitle(t, from = 0.62f, topPadding = 120)
}

// --------------------------------------------------------- 4. the floodlights

/**
 * A stadium coming on around you.
 *
 * The atmospheric one. Four banks snap on in sequence — never fade, because
 * floodlights do not fade — each throwing a soft cone down onto a pitch that is
 * only visible once something is lighting it.
 */
@Composable
private fun BoxScope.FloodlightIntro(t: Float) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        val horizon = h * 0.42f

        val banks = listOf(0.14f, 0.38f, 0.62f, 0.86f)
        banks.forEachIndexed { index, fx ->
            val onAt = 0.10f + index * 0.09f
            val on = t >= onAt
            // A brief surge as it strikes, then steady.
            val surge = if (on) 1f + exp(-((t - onAt) * 26f)) * 0.9f else 0f
            val x = w * fx
            val headY = h * 0.16f

            // The pylon.
            drawLine(
                Color(0xFF31353C),
                Offset(x, headY), Offset(x, horizon),
                (w * 0.008f).coerceAtLeast(2f),
            )

            // The light it throws, before the lamp itself, so the lamp sits on top.
            if (on) {
                drawPath(
                    path = androidx.compose.ui.graphics.Path().apply {
                        moveTo(x - w * 0.05f, headY)
                        lineTo(x + w * 0.05f, headY)
                        lineTo(x + w * 0.30f, h)
                        lineTo(x - w * 0.30f, h)
                        close()
                    },
                    brush = Brush.verticalGradient(
                        0f to AccentGold.copy(alpha = 0.20f * surge.coerceAtMost(1.4f)),
                        1f to Color.Transparent,
                        startY = headY,
                        endY = h,
                    ),
                )
            }

            // The lamp head: a bank of bulbs.
            val bulbR = (w * 0.008f).coerceAtLeast(2.5f)
            for (row in 0..1) {
                for (col in 0..3) {
                    val bx = x - w * 0.036f + col * (w * 0.024f)
                    val by = headY - h * 0.018f + row * (h * 0.016f)
                    drawCircle(
                        color = if (on) {
                            Color(0xFFFFF6D0).copy(alpha = (0.55f + 0.45f * surge).coerceAtMost(1f))
                        } else {
                            Color(0xFF3A3F47)
                        },
                        radius = bulbR,
                        center = Offset(bx, by),
                    )
                }
            }
        }

        // The pitch, appearing only as the light reaches it.
        val lit = ((t - 0.16f) / 0.4f).coerceIn(0f, 1f)
        if (lit > 0f) {
            val line = Color(0xFFBFE3C0).copy(alpha = 0.14f * lit)
            drawLine(line, Offset(0f, horizon), Offset(w, horizon), 2f)
            drawCircle(
                color = line,
                radius = w * 0.18f,
                center = Offset(w / 2f, h * 0.78f),
                style = Stroke(width = 2f),
            )
            drawRect(
                color = line,
                topLeft = Offset(w * 0.22f, horizon),
                size = Size(w * 0.56f, h * 0.14f),
                style = Stroke(width = 2f),
            )
        }
    }
    IntroTitle(t, from = 0.46f, topPadding = 40)
}

// --------------------------------------------------------- 5. the artwork

/**
 * The one made of pixels: a painted stadium, its lights coming on.
 *
 * THE POINT OF COMPARISON. Everything else here is geometry drawn live, which
 * is right for a net or a formation and hopeless for atmosphere — wide additive
 * bloom, crowd texture, film grain and a graded night sky are all things a
 * Canvas is bad at and a bitmap is good at.
 *
 * Two frames cross-fade so the lights genuinely come ON, with a slow push-in
 * underneath. A single still would be a wallpaper; the transition is what makes
 * it an intro.
 *
 * The cost is honest and worth weighing: about 370 KB of the download, against
 * a few hundred bytes for any of the drawn ones.
 */
@Composable
private fun BoxScope.StadiumIntro(t: Float) {
    // A slow push-in. Barely perceptible frame to frame, which is the point —
    // it stops a photograph from sitting there like a photograph.
    // A more generous push-in now there is time for it to read, and the lights
    // come up early so that most of the intro is spent on the lit frame rather
    // than waiting for it.
    val zoom = 1.05f + 0.10f * t
    val lightsUp = ((t - 0.10f) / 0.30f).coerceIn(0f, 1f)

    Image(
        painter = painterResource(R.drawable.intro_stadium_dark),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { scaleX = zoom; scaleY = zoom },
    )
    Image(
        painter = painterResource(R.drawable.intro_stadium_lit),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                scaleX = zoom
                scaleY = zoom
                alpha = lightsUp
            },
    )

    // A wash under the title, so the name never has to fight the crowd texture
    // for legibility.
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to SurfaceBase.copy(alpha = 0.55f),
                    1f to SurfaceBase.copy(alpha = 0.92f),
                ),
            ),
    )

    IntroTitle(t, from = 0.44f, topPadding = 300)
}

// ------------------------------------------------------------------ shared

/** A point along a quadratic bezier at [t]. */
private fun quadratic(from: Offset, control: Offset, to: Offset, t: Float): Offset {
    val u = 1f - t
    return Offset(
        u * u * from.x + 2f * u * t * control.x + t * t * to.x,
        u * u * from.y + 2f * u * t * control.y + t * t * to.y,
    )
}

// ----------------------------------------------------------------- previews

@Preview(name = "1 · Strike", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 780)
@Composable
private fun StrikePreview() = DreamXITheme { IntroStage(Intro.Strike, {}) }

@Preview(name = "2 · Line-up", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 780)
@Composable
private fun LineUpPreview() = DreamXITheme { IntroStage(Intro.LineUp, {}) }

@Preview(name = "3 · Eras", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 780)
@Composable
private fun ErasPreview() = DreamXITheme { IntroStage(Intro.Eras, {}) }

@Preview(name = "4 · Floodlights", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 780)
@Composable
private fun FloodlightsPreview() = DreamXITheme { IntroStage(Intro.Floodlights, {}) }

@Preview(name = "5 · Stadium art", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 780)
@Composable
private fun StadiumPreview() = DreamXITheme { IntroStage(Intro.Stadium, {}) }
