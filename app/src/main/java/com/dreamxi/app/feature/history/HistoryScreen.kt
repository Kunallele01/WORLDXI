package com.dreamxi.app.feature.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamxi.app.core.ui.components.DreamXiSecondaryButton
import com.dreamxi.app.core.ui.components.ErrorNotice
import com.dreamxi.app.data.run.PastRun
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.OutlineSubtle
import com.dreamxi.app.ui.theme.ResultLoss
import com.dreamxi.app.ui.theme.SurfaceBase
import com.dreamxi.app.ui.theme.SurfaceRaised1

/** Past runs and results, read back from Supabase under the player's own session. */
@Composable
fun HistoryScreen(
    modifier: Modifier = Modifier,
    onAttachAccount: () -> Unit = {},
) {
    val viewModel: HistoryViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    HistoryScreen(
        state = state,
        onRetry = viewModel::refresh,
        onAttachAccount = { onAttachAccount(); viewModel.openAttach() },
        modifier = modifier,
    )
    if (state.isAttaching) {
        AttachAccountDialog(
            inFlight = state.attachInFlight,
            error = state.attachError,
            onDismiss = viewModel::dismissAttach,
            onSubmit = viewModel::attachEmail,
        )
    }
}

/**
 * Turning a guest into an account that can be recovered.
 *
 * Email and password rather than Google, for now, because Google needs an
 * OAuth client and the app's signing fingerprints registered before it does
 * anything at all, and a player who wants to keep his runs should not have to
 * wait for that. The same call adds either, so Google can be a second button
 * here later without changing what happens to the runs.
 */
@Composable
private fun AttachAccountDialog(
    inFlight: Boolean,
    error: String?,
    onDismiss: () -> Unit,
    onSubmit: (String, String) -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { if (!inFlight) onDismiss() },
        containerColor = SurfaceRaised1,
        title = {
            Text("Keep your runs", style = MaterialTheme.typography.titleLarge, color = OnSurfacePrimary)
        },
        text = {
            Column {
                Text(
                    text = "Add an email and your runs stop being tied to this phone. " +
                        "Nothing already saved is lost or moved — the same account simply " +
                        "gains a way back in.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = OnSurfaceMuted,
                )
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    enabled = !inFlight,
                    singleLine = true,
                    label = { Text("Email") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    enabled = !inFlight,
                    singleLine = true,
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (error != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(text = error, style = MaterialTheme.typography.bodySmall, color = ResultLoss)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSubmit(email, password) }, enabled = !inFlight) {
                Text(if (inFlight) "Saving…" else "Keep my runs", color = AccentGold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !inFlight) {
                Text("Not now", color = OnSurfaceMuted)
            }
        },
    )
}

/** Stateless, so every case can be seen in a @Preview without a backend. */
@Composable
internal fun HistoryScreen(
    state: HistoryUiState,
    onRetry: () -> Unit,
    onAttachAccount: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(SurfaceBase)) {
        when {
            state.error != null -> ErrorNotice(
                error = state.error,
                onRetry = onRetry,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )

            state.isLoading -> CircularProgressIndicator(
                color = AccentGold,
                strokeWidth = 3.dp,
                modifier = Modifier.align(Alignment.Center),
            )

            state.runs.isEmpty() -> EmptyHistory(Modifier.align(Alignment.Center))

            else -> LazyColumn(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(
                        text = "YOUR RUNS",
                        style = MaterialTheme.typography.labelSmall,
                        color = OnSurfaceFaint,
                    )
                }
                if (state.warnRunsAreLocal) {
                    item { LocalOnlyWarning(count = state.runs.size, onAttachAccount = onAttachAccount) }
                }
                items(state.runs) { run -> RunRow(run) }
            }
        }
    }
}

/**
 * The warning the whole anonymous-first choice owes the player.
 *
 * A guest account is real in every way except recovery: the runs are saved, in
 * the database, under an id that exists — and nothing on earth can hand them
 * back if this phone is wiped. That is worth saying plainly, next to the runs
 * it applies to, rather than in a settings screen nobody opens.
 */
@Composable
private fun LocalOnlyWarning(count: Int, onAttachAccount: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised1)
            .border(1.dp, ResultLoss.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(
            text = "THESE RUNS ARE ONLY ON THIS PHONE",
            style = MaterialTheme.typography.labelSmall,
            color = ResultLoss,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (count == 1) {
                "Your run is saved to this device's account. Uninstall the app or lose " +
                    "the phone and it goes with it. Add an email and it follows you."
            } else {
                "All $count runs are saved to this device's account. Uninstall the app " +
                    "or lose the phone and they go with it. Add an email and they follow you."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(10.dp))
        DreamXiSecondaryButton(text = "Keep my runs", onClick = onAttachAccount)
    }
}

@Composable
private fun RunRow(run: PastRun, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised1)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = run.title,
                style = MaterialTheme.typography.titleMedium,
                color = OnSurfacePrimary,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = buildString {
                    append(
                        when (run.kind) {
                            PastRun.Kind.LEAGUE -> "League"
                            PastRun.Kind.WORLD_CUP -> "World Cup"
                        },
                    )
                    // A Free Mode side is labelled wherever it appears: winning
                    // a league with a hand-picked XI is not the same
                    // achievement as doing it with a drafted one.
                    if (run.isFreeMode) append(" · Free Mode")
                },
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
            )
        }
        Text(
            text = run.outcome,
            style = MaterialTheme.typography.bodyMedium,
            color = AccentGold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier.padding(32.dp),
    ) {
        Text(
            text = "Nothing here yet",
            style = MaterialTheme.typography.titleLarge,
            color = OnSurfacePrimary,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Finish a season or a World Cup and it will be waiting here.",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}

private val PreviewRuns = listOf(
    PastRun(1, PastRun.Kind.LEAGUE, "Premier League · 2021/22", "1st · 89 pts", false, "2026-09-23"),
    PastRun(2, PastRun.Kind.WORLD_CUP, "2018 World Cup", "World champions", false, "2026-09-22"),
    PastRun(3, PastRun.Kind.LEAGUE, "La Liga · 2019/20", "4th · 71 pts", true, "2026-09-21"),
)

@Preview(name = "History", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 640)
@Composable
private fun HistoryPreview() = DreamXITheme {
    HistoryScreen(
        state = HistoryUiState(
            isLoading = false,
            runs = PreviewRuns,
            account = com.dreamxi.app.data.auth.PlayerAccount("guest", isGuest = true),
        ),
        onRetry = {},
        onAttachAccount = {},
    )
}

@Preview(name = "History · empty", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 400)
@Composable
private fun HistoryEmptyPreview() = DreamXITheme {
    HistoryScreen(state = HistoryUiState(isLoading = false), onRetry = {}, onAttachAccount = {})
}
