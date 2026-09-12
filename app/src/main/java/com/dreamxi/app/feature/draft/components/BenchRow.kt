package com.dreamxi.app.feature.draft.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.dreamxi.app.feature.draft.BenchPlayer
import com.dreamxi.app.feature.draft.sidedRoleLabel
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.SurfaceRaised2

/**
 * The substitutes, under the pitch.
 *
 * Shown rather than left as plumbing because the user asked to see them, and
 * because a bench that silently takes a share of the goals would otherwise
 * produce names on the scorer list that appear from nowhere. Every one of these
 * is a real player from a club the run passed through.
 *
 * Deliberately not interactive. The bench is filled automatically — eight more
 * rounds of picking players nobody cares about would tax the part of the draft
 * that works — so it reads as information rather than as something to fiddle
 * with.
 */
@Composable
fun BenchRow(bench: List<BenchPlayer>, modifier: Modifier = Modifier) {
    if (bench.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "SUBSTITUTES",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "auto-picked from the clubs you drafted from",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        // Two rows of four rather than one scrolling strip: eight names fit
        // across a phone only if they are stacked, and a bench is read as a
        // group rather than scanned along.
        for (pair in bench.chunked(2)) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                for (sub in pair) {
                    BenchCard(sub, Modifier.weight(1f))
                }
                // Keeps the last row aligned when the bench is an odd size.
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun BenchCard(sub: BenchPlayer, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceRaised1)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = sidedRoleLabel(sub.role),
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceFaint,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(SurfaceRaised2)
                .padding(horizontal = 6.dp, vertical = 3.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = sub.player.fullName,
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfacePrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = sub.clubName,
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${sub.effectiveRating}",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0D0F, widthDp = 380)
@Composable
private fun BenchRowPreview() {
    DreamXITheme {
        BenchRow(bench = emptyList(), modifier = Modifier.padding(16.dp))
    }
}
