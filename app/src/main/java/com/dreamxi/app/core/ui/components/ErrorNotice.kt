package com.dreamxi.app.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.ResultLoss
import com.dreamxi.app.ui.theme.SurfaceRaised1

/**
 * A failure the user can actually act on.
 *
 * This exists because the first version rendered the raw exception:
 *
 *   "Couldn't load the club pool: HTTP request to https://tppp….supabase.co
 *    /rest/v1/leagues?select=id%2Cname (GET) failed with message: Unable to
 *    resolve host …: No address associated with hostname"
 *
 * Every word of that is true and none of it is usable. It names our backend,
 * leaks a URL, and buries the only actionable fact — the phone's Wi-Fi was
 * off — inside a DNS error. A player seeing that concludes the app is broken,
 * not that they are offline.
 *
 * So errors are classified into a small set the UI can phrase properly, with
 * the technical detail kept for the log. Anything genuinely unexpected still
 * gets a plain sentence and a retry, never a stack trace.
 */
sealed interface DreamXiError {
    val title: String
    val message: String
    val retryable: Boolean get() = true

    data object Offline : DreamXiError {
        override val title = "You're offline"
        override val message =
            "Dream XI needs a connection to load clubs and players. " +
                "Turn on Wi-Fi or mobile data, then try again."
    }

    data object ServerUnreachable : DreamXiError {
        override val title = "Can't reach Dream XI"
        override val message =
            "The connection worked but our servers didn't answer. " +
                "This is usually temporary — try again in a moment."
    }

    data object NoData : DreamXiError {
        override val title = "Nothing to draft from"
        override val message =
            "No clubs are loaded for this league yet. Pick a different league to get started."
        override val retryable = false
    }

    data class Unexpected(val detail: String) : DreamXiError {
        override val title = "Something went wrong"
        override val message = "We couldn't finish that. Try again — if it keeps happening, restart the app."
    }
}

@Composable
fun ErrorNotice(
    error: DreamXiError,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceRaised1)
            .border(1.dp, ResultLoss.copy(alpha = 0.35f), RoundedCornerShape(16.dp))
            .padding(horizontal = 20.dp, vertical = if (compact) 16.dp else 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(ResultLoss, CircleShape))
            Text(
                text = error.title.uppercase(),
                fontFamily = DisplayFontFamily,
                fontSize = if (compact) 20.sp else 26.sp,
                lineHeight = if (compact) 24.sp else 30.sp,
                color = OnSurfacePrimary,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = error.message,
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
        if (error.retryable) {
            Spacer(Modifier.height(16.dp))
            DreamXiSecondaryButton(
                text = "Try again",
                onClick = onRetry,
                modifier = Modifier.width(180.dp),
            )
        }
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0C0D0F)
@Composable
private fun ErrorNoticePreview() {
    DreamXITheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ErrorNotice(error = DreamXiError.Offline, onRetry = {})
            ErrorNotice(error = DreamXiError.NoData, onRetry = {}, compact = true)
            ErrorNotice(error = DreamXiError.Unexpected("boom"), onRetry = {}, compact = true)
        }
    }
}
