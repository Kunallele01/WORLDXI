package com.dreamxi.app.feature.draft.components

import com.dreamxi.app.core.ui.playerSurname
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import com.dreamxi.app.core.ui.components.ClubBadge
import com.dreamxi.app.feature.draft.DraftedPlayer
import com.dreamxi.app.feature.draft.Formation
import com.dreamxi.app.feature.draft.FormationSlot
import com.dreamxi.app.feature.draft.PositionFit
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.feature.draft.fitAt
import com.dreamxi.app.feature.draft.formationById
import com.dreamxi.app.feature.draft.isWrongSideAt
import com.dreamxi.app.feature.draft.ratingAt
import com.dreamxi.app.feature.draft.sidePenaltyAt
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.OutlineSubtle
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.colorForPosition
import com.dreamxi.app.ui.theme.colorForRating

/**
 * The XI laid out on a pitch.
 *
 * This is what makes the shape of a team readable in one look, which the
 * horizontal rail never could — a row of eleven chips has no geometry in it.
 * On a pitch you see that the defence is thin, or that there are three
 * excellent forwards and nothing in midfield, without reading a number.
 *
 * Two modes, same component:
 *  - REVIEW (candidate == null): each filled position shows its player, his
 *    effective rating and the club he came from.
 *  - PLACEMENT (candidate != null): every open position shows what THAT player
 *    would be worth in it, coloured by fit, and is tappable. Pick a player and
 *    watch the whole shape re-price itself around him.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PitchView(
    formation: Formation,
    picks: Map<String, DraftedPlayer>,
    modifier: Modifier = Modifier,
    candidate: SquadPlayer? = null,
    /** Position whose player has been picked up for repositioning. */
    heldSlotId: String? = null,
    onSlotClick: ((FormationSlot) -> Unit)? = null,
    /**
     * A long press on a position, where the caller wants a second meaning for
     * a shirt. Free Mode uses it for the picker once a tap has been taken over
     * by the magic cycle; the draft leaves it null and behaves as before.
     */
    onSlotLongClick: ((FormationSlot) -> Unit)? = null,
) {
    val absorb = remember { MutableInteractionSource() }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(PitchDark)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(18.dp))
            .then(
                // While placing, the pitch ABSORBS taps that miss a position.
                // Without this the grass is transparent to touch: a tap on
                // empty turf fell through to the dismiss-scrim behind and shut
                // the whole sheet, so a slightly-off tap looked like the app
                // discarding the player you were holding. No indication,
                // because a ripple on a football pitch is nonsense.
                if (onSlotClick != null) {
                    Modifier.clickable(
                        interactionSource = absorb,
                        indication = null,
                        onClick = {},
                    )
                } else {
                    Modifier
                },
            ),
    ) {
        val w = maxWidth
        val h = maxHeight
        // Token size is bounded by BOTH dimensions of the pitch.
        //
        // Width, so five-across shapes (3-5-2, 4-5-1) still fit without
        // colliding on a narrow phone. Height, because a token is not just the
        // shirt: the name sits underneath it, making the whole thing about
        // 0.93 tokens plus 3dp tall, and the vertical gap between two rows
        // comes from the pitch's HEIGHT while the size came only from its
        // width. In a back three the middle centre-back is drawn deeper than
        // the other two, which leaves 0.117 of the pitch between him and the
        // keeper — 61dp on a 520dp pitch, against a 64dp token column. His name
        // landed on the keeper's shirt, and on a shorter pitch it landed harder.
        //
        // 8.6 is the tightest row spacing any formation asks for, with the
        // caption allowed for: shirts give up a few dp where the pitch is short
        // rather than overlapping, and a formation added later cannot
        // reintroduce the collision.
        val token: Dp = pitchTokenSize(formation, w, h)

        PitchMarkings(Modifier.fillMaxSize())

        // A player picked up off the pitch behaves exactly like one picked
        // from the pool: every other position re-prices itself for HIM. Same
        // mechanism, so moving Benzema from ST to LW previews the same 89 that
        // drafting him there would have.
        val held = heldSlotId?.let { picks[it] }?.player
        val inHand = candidate ?: held

        formation.slots.forEach { slot ->
            val pick = picks[slot.id]
            val placeable = inHand != null && pick == null &&
                slot.isGoalkeeper == (inHand.role == "GK")
            // Coordinates are the token's centre, so half is subtracted; the y
            // range is inset so keeper and strikers do not sit half off-pitch.
            // Coordinates are the token's CENTRE, so half a token is
            // subtracted, then clamped so nothing can hang over a touchline
            // however wide a future formation puts a position.
            val x = (w * slot.x - token / 2).coerceIn(EdgeMargin, w - token - EdgeMargin)
            val y = pitchSlotTop(slot, h, token)
            PitchToken(
                slot = slot,
                pick = pick,
                candidate = inHand?.takeIf { placeable },
                held = slot.id == heldSlotId,
                size = token,
                modifier = Modifier
                    .offset(x = x, y = y)
                    .then(
                        // EVERY position is tappable while placing, not only
                        // the legal ones. A tap on an occupied position used to
                        // fall through to the scrim behind the pitch and
                        // silently dismiss the whole sheet, which read as the
                        // app losing your player. The caller decides what a tap
                        // means; the pitch just has to stop swallowing it.
                        when {
                            onSlotLongClick != null && onSlotClick != null -> Modifier
                                .combinedClickable(
                                    onClick = { onSlotClick(slot) },
                                    onLongClick = { onSlotLongClick(slot) },
                                )
                            onSlotClick != null -> Modifier.clickable { onSlotClick(slot) }
                            else -> Modifier
                        },
                    ),
            )
        }
    }
}

private val EdgeMargin = 4.dp

/** A hair of daylight between two shirts, so a tight fit never reads as a touch. */
private val MinClearance = 1.dp

/**
 * The closest two rows of THIS formation come, as a fraction of pitch height,
 * counting only positions whose shirts actually share horizontal space at a
 * shirt [sameColumn] wide (as a fraction of pitch width).
 *
 * The threshold has to be the real shirt width, not a guess. A fifth of the
 * pitch — a fair guess — called the 3-5-2's left and centre midfielders one
 * column when they are 71dp apart and 66dp wide, and shrank every shirt in
 * that formation to 17dp to keep two positions apart that never touched.
 */
private fun Formation.tightestRowGap(sameColumn: Float): Float =
    slots.flatMapIndexed { i, a ->
        slots.drop(i + 1)
            .filter { b -> abs(a.x - b.x) < sameColumn }
            .map { b -> abs(a.y - b.y) * 0.9f }
    }.minOrNull() ?: 1f

/**
 * The shirt size for this formation on a pitch of this size.
 *
 * Bounded by WIDTH, so five-across shapes still fit on a narrow phone, and by
 * the formation's own tightest pair of rows, so a shirt — name included — can
 * never be taller than the space between two of them. That second bound is
 * derived rather than guessed: a fixed divisor either shrank every formation to
 * suit the worst one, or left the worst one still overlapping on a small
 * screen. A back four keeps the full 66dp; only the shapes that need the room
 * give any up.
 *
 * It settles rather than solves, because the two bounds chase each other: a
 * narrower shirt shares a column with fewer positions, which frees the height
 * bound, which allows a wider shirt. Each pass only shrinks, and the moment a
 * size fits the columns it was measured against, that size is the answer.
 *
 * Shared with the test that proves no two positions can overlap, so the rule
 * and its proof cannot drift apart.
 */
internal fun pitchTokenSize(formation: Formation, width: Dp, height: Dp): Dp {
    var token = minOf(width / 5.3f, 66.dp)
    repeat(4) {
        val gap = formation.tightestRowGap(token / width)
        // pitchTokenHeight inverted: the tallest shirt that fits that gap.
        val fits = (height * gap - 3.dp - MinClearance) / 0.93f
        if (fits >= token) return token
        token = fits
    }
    return token
}

internal fun pitchTokenHeight(token: Dp): Dp = token * 0.74f + 3.dp + token * 0.19f

/** Where a position's shirt starts, top edge, inside a pitch [height] tall. */
internal fun pitchSlotTop(slot: FormationSlot, height: Dp, token: Dp): Dp =
    height * (0.05f + slot.y * 0.9f) - token / 2

private val PitchDark = Color(0xFF10231A)
private val PitchStripe = Color(0xFF13291E)
private val PitchLine = Color(0x2EFFFFFF)

/**
 * Mown stripes, both penalty areas, six-yard boxes, penalty spots, the D arcs,
 * the centre circle and corner arcs.
 *
 * Drawn rather than shipped as an image: it scales to any size with no assets,
 * and stays quiet enough on the near-black surface that a photographic pitch
 * would fight. Stripes are flat alternating bands, not a gradient — the design
 * language rules gradients out, and flat bands read as mown grass anyway.
 */
@Composable
private fun PitchMarkings(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = 1.4f
        val line = Stroke(width = stroke)

        val bands = 7
        val bandH = h / bands
        repeat(bands) { i ->
            if (i % 2 == 1) {
                drawRect(PitchStripe, topLeft = Offset(0f, i * bandH), size = Size(w, bandH))
            }
        }

        drawLine(PitchLine, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = stroke)
        drawCircle(PitchLine, radius = w * 0.135f, center = Offset(w / 2, h / 2), style = line)
        drawCircle(PitchLine, radius = stroke * 1.6f, center = Offset(w / 2, h / 2))

        listOf(true, false).forEach { bottom ->
            val boxW = w * 0.56f
            val boxH = h * 0.15f
            val smallW = w * 0.26f
            val smallH = h * 0.058f
            val boxTop = if (bottom) h - boxH else 0f
            val smallTop = if (bottom) h - smallH else 0f

            drawRect(PitchLine, Offset((w - boxW) / 2, boxTop), Size(boxW, boxH), style = line)
            drawRect(PitchLine, Offset((w - smallW) / 2, smallTop), Size(smallW, smallH), style = line)

            // Penalty spot and the arc outside the box.
            val spotY = if (bottom) h - boxH * 0.68f else boxH * 0.68f
            drawCircle(PitchLine, radius = stroke * 1.5f, center = Offset(w / 2, spotY))
            val arcR = w * 0.135f
            drawArc(
                color = PitchLine,
                startAngle = if (bottom) 180f else 0f,
                sweepAngle = 180f,
                useCenter = false,
                topLeft = Offset(w / 2 - arcR, spotY - arcR),
                size = Size(arcR * 2, arcR * 2),
                style = line,
            )
        }

        // Corner arcs.
        val cornerR = w * 0.045f
        listOf(
            Rect(-cornerR, -cornerR, cornerR, cornerR) to 0f,
            Rect(w - cornerR, -cornerR, w + cornerR, cornerR) to 90f,
            Rect(-cornerR, h - cornerR, cornerR, h + cornerR) to 270f,
            Rect(w - cornerR, h - cornerR, w + cornerR, h + cornerR) to 180f,
        ).forEach { (rect, start) ->
            drawArc(
                color = PitchLine,
                startAngle = start,
                sweepAngle = 90f,
                useCenter = false,
                topLeft = Offset(rect.left, rect.top),
                size = Size(rect.width, rect.height),
                style = line,
            )
        }
    }
}

/**
 * One position on the pitch.
 *
 * Empty positions stay visible as dashed outlines rather than blank space:
 * "what is still missing" is the most useful thing the pitch tells you
 * mid-draft, and it can only tell you that if the gaps are drawn.
 *
 * A filled position carries the club badge of the squad the player was drafted
 * from — with eleven players from up to eleven different club-seasons, that
 * badge is the whole story of the run at a glance.
 */
@Composable
private fun PitchToken(
    slot: FormationSlot,
    pick: DraftedPlayer?,
    candidate: SquadPlayer?,
    held: Boolean,
    size: Dp,
    modifier: Modifier = Modifier,
) {
    val previewing = candidate != null
    val fit = if (previewing) candidate!!.fitAt(slot) else pick?.fit
    val accent = when {
        held -> AccentGold
        previewing && fit != null -> colorForFit(fit)
        pick != null -> colorForRating(pick.effectiveRating)
        else -> colorForPosition(slot.group)
    }
    val shape = RoundedCornerShape(12.dp)
    val faceH = size * 0.74f

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = modifier.width(size)) {
        Box(Modifier.width(size).height(faceH), contentAlignment = Alignment.Center) {
            if (pick == null && !previewing) {
                // Empty: a dashed ring, so a gap reads as an invitation rather
                // than as a missing element.
                Canvas(Modifier.fillMaxSize()) {
                    drawRoundRect(
                        color = PitchLine,
                        style = Stroke(
                            width = 2f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 7f)),
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(26f, 26f),
                    )
                }
                Text(
                    text = slot.label,
                    fontFamily = DisplayFontFamily,
                    fontSize = (size.value * 0.27f).sp,
                    lineHeight = (size.value * 0.3f).sp,
                    color = OnSurfaceMuted,
                )
            } else {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(shape)
                        .background(
                            when {
                                held -> AccentGold.copy(alpha = 0.26f)
                                previewing -> accent.copy(alpha = 0.20f)
                                else -> TokenFace
                            },
                        )
                        .border(
                            width = if (previewing || held) 2.dp else 1.dp,
                            color = accent,
                            shape = shape,
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (previewing) {
                                candidate!!.ratingAt(slot).toString()
                            } else {
                                pick!!.effectiveRating.toString()
                            },
                            fontFamily = DisplayFontFamily,
                            fontSize = (size.value * 0.4f).sp,
                            lineHeight = (size.value * 0.4f).sp,
                            letterSpacing = (-0.5).sp,
                            color = accent,
                        )
                        Text(
                            text = slot.label,
                            style = MaterialTheme.typography.labelSmall,
                            fontSize = (size.value * 0.145f).sp,
                            lineHeight = (size.value * 0.17f).sp,
                            letterSpacing = 0.6.sp,
                            color = OnSurfaceMuted,
                        )
                    }
                    // Club badge, top-right, INSIDE the token.
                    //
                    // It used to be offset outwards to overhang the corner,
                    // which looked right in isolation and was wrong in place:
                    // the token clips its children, so most of the badge was
                    // simply cut off. Anything that needs to overhang has to
                    // live outside the clipping parent, and here it does not
                    // need to — the top corners are empty space anyway.
                    if (pick != null && !previewing) {
                        ClubBadge(
                            clubName = pick.clubName,
                            size = size * 0.26f,
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(top = 3.dp, end = 3.dp),
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(3.dp))
        val caption = when {
            previewing -> {
                val d = candidate!!.ratingAt(slot) - candidate.overallRating
                when {
                    d == 0 -> "NATURAL"
                    candidate.isWrongSideAt(slot) && d == -candidate.sidePenaltyAt(slot) ->
                        "$d WRONG SIDE"
                    else -> "$d"
                }
            }
            held -> "MOVING"
            pick != null -> playerSurname(pick.player.fullName)
            else -> ""
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.labelSmall,
            fontSize = (size.value * 0.15f).sp,
            lineHeight = (size.value * 0.19f).sp,
            color = when {
                held -> AccentGold
                previewing -> accent
                pick != null && pick.fit != PositionFit.Natural -> colorForFit(pick.fit)
                else -> OnSurfacePrimary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private val TokenFace = Color(0xF21A1D22)

/**
 * Legend for the fit colours. Shown once above the placement pitch — the
 * colours are self-explanatory only after being explained once, and once is
 * cheaper than a tooltip on every token.
 */
@Composable
fun FitLegend(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        listOf(
            PositionFit.Natural to "Natural",
            PositionFit.Comfortable to "Close",
            PositionFit.Stretch to "Costly",
            PositionFit.Alien to "Bad",
        ).forEach { (fit, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(colorForFit(fit)))
                Spacer(Modifier.width(4.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 9.sp,
                    color = OnSurfaceFaint,
                )
            }
        }
    }
}

@Preview(name = "Placement", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 640)
@Composable
private fun PitchPlacementPreview() {
    DreamXITheme {
        Column(Modifier.background(SurfaceRaised1).padding(12.dp)) {
            FitLegend()
            Spacer(Modifier.height(8.dp))
            PitchView(
                formation = formationById("4-3-3"),
                picks = PreviewPitchPicks,
                candidate = PreviewSquad[9],
                modifier = Modifier.height(540.dp),
                onSlotClick = {},
            )
        }
    }
}

@Preview(name = "3-5-2 empty", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 640)
@Composable
private fun Pitch352Preview() {
    DreamXITheme {
        Column(Modifier.background(SurfaceRaised1).padding(12.dp)) {
            PitchView(
                formation = formationById("3-5-2"),
                picks = emptyMap(),
                modifier = Modifier.height(580.dp),
            )
        }
    }
}
