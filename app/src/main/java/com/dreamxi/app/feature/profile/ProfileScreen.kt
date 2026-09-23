package com.dreamxi.app.feature.profile

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.core.ui.components.DreamXiPrimaryButton
import com.dreamxi.app.core.ui.components.DreamXiSecondaryButton
import com.dreamxi.app.core.ui.components.DreamXiTextField
import com.dreamxi.app.core.ui.components.ErrorNotice
import com.dreamxi.app.data.auth.PlayerAccount
import com.dreamxi.app.data.run.PastRun
import com.dreamxi.app.data.run.PlayerRecord
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnAccentGold
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.OutlineSubtle
import com.dreamxi.app.ui.theme.ResultLoss
import com.dreamxi.app.ui.theme.SurfaceBase
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.SurfaceRaised2

@Composable
fun ProfileRoute(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: ProfileViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    ProfileScreen(
        state = state,
        onBack = onBack,
        onRetry = viewModel::refresh,
        onOpenAttach = viewModel::openAttach,
        onCancelAttach = viewModel::cancelAttach,
        onEmail = viewModel::setEmail,
        onPassword = viewModel::setPassword,
        onAttach = viewModel::attach,
        onSignOut = viewModel::signOut,
        modifier = modifier,
    )
}

/**
 * Who the player is, what he has won, and everything he has played.
 *
 * THE RECORD COMES FIRST, deliberately. Account management is the reason this
 * screen exists technically, and the least interesting thing on it: a player
 * opens his profile to see that he has won two titles and a World Cup, not to
 * read his email address back to himself.
 */
@Composable
internal fun ProfileScreen(
    state: ProfileUiState,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onOpenAttach: () -> Unit,
    onCancelAttach: () -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onAttach: () -> Unit,
    onSignOut: () -> Unit,
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

            else -> LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item { Header(state.account, onBack) }
                item { RecordBand(state.record) }

                if (state.isAttaching) {
                    item {
                        AttachForm(
                            email = state.email,
                            password = state.password,
                            inFlight = state.attachInFlight,
                            error = state.attachError,
                            canSubmit = state.canAttach,
                            onEmail = onEmail,
                            onPassword = onPassword,
                            onAttach = onAttach,
                            onCancel = onCancelAttach,
                        )
                    }
                } else if (state.warnRunsAreLocal) {
                    item { LocalOnlyWarning(state.runs.size, onOpenAttach) }
                } else if (state.account?.isGuest == false) {
                    item { SignedIn(state.account.email.orEmpty(), state.justAttached, onSignOut) }
                }

                item {
                    Text(
                        text = "EVERY RUN",
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp,
                        color = OnSurfaceFaint,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
                if (state.runs.isEmpty()) {
                    item { EmptyRuns() }
                } else {
                    items(state.runs) { run -> RunRow(run) }
                }
            }
        }
    }
}

@Composable
private fun Header(account: PlayerAccount?, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "‹",
            fontFamily = DisplayFontFamily,
            fontSize = 30.sp,
            color = OnSurfaceMuted,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onBack)
                .padding(horizontal = 10.dp, vertical = 2.dp),
        )
        Spacer(Modifier.size(6.dp))
        Avatar(account)
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = account?.email?.substringBefore("@") ?: "Guest",
                style = MaterialTheme.typography.titleLarge,
                color = OnSurfacePrimary,
            )
            Text(
                text = account?.email ?: "This phone only",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
            )
        }
    }
}

/** Initials on gold, the same fallback the club badge uses when it has no crest. */
@Composable
private fun Avatar(account: PlayerAccount?, size: androidx.compose.ui.unit.Dp = 46.dp) {
    val initial = account?.email?.firstOrNull()?.uppercase() ?: "G"
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.size(size).clip(CircleShape).background(AccentGold),
    ) {
        Text(
            text = initial,
            fontFamily = DisplayFontFamily,
            fontSize = (size.value * 0.44f).sp,
            color = OnAccentGold,
        )
    }
}

/** What he has to show for it: four numbers, no prose. */
@Composable
private fun RecordBand(record: PlayerRecord) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Stat("RUNS", record.runs.toString(), Modifier.weight(1f))
        Stat("TITLES", record.leagueTitles.toString(), Modifier.weight(1f))
        Stat("WORLD CUPS", record.worldCups.toString(), Modifier.weight(1f))
        Stat("BEST", record.bestFinish?.let { ordinal(it) } ?: "—", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised1)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
            .padding(vertical = 14.dp),
    ) {
        Text(
            text = value,
            fontFamily = DisplayFontFamily,
            fontSize = 26.sp,
            color = if (value == "0" || value == "—") OnSurfaceMuted else AccentGold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceFaint,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LocalOnlyWarning(count: Int, onOpenAttach: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised1)
            .border(1.dp, ResultLoss.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "THESE RUNS ARE ONLY ON THIS PHONE",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = ResultLoss,
        )
        Spacer(Modifier.height(8.dp))
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
        Spacer(Modifier.height(14.dp))
        DreamXiPrimaryButton(
            text = "Keep my runs",
            onClick = onOpenAttach,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** The upgrade, inline on the page rather than in a dialog over it. */
@Composable
private fun AttachForm(
    email: String,
    password: String,
    inFlight: Boolean,
    error: String?,
    canSubmit: Boolean,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onAttach: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised1)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "KEEP YOUR RUNS",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = AccentGold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "Nothing already saved moves or is lost — this account simply gains " +
                "a way back in.",
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(16.dp))
        DreamXiTextField(
            value = email,
            onValueChange = onEmail,
            label = "Email",
            placeholder = "you@example.com",
            enabled = !inFlight,
            keyboardType = KeyboardType.Email,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
        DreamXiTextField(
            value = password,
            onValueChange = onPassword,
            label = "Password",
            placeholder = "At least 8 characters",
            enabled = !inFlight,
            isPassword = true,
            keyboardType = KeyboardType.Password,
            error = error,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(16.dp))
        DreamXiPrimaryButton(
            text = if (inFlight) "…" else "Keep my runs",
            onClick = onAttach,
            enabled = canSubmit,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        DreamXiSecondaryButton(text = "Not now", onClick = onCancel, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun SignedIn(email: String, justAttached: Boolean, onSignOut: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised1)
            .border(1.dp, OutlineSubtle, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(
            text = if (justAttached) "YOUR RUNS ARE SAFE" else "SIGNED IN",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = AccentGold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = if (justAttached) {
                "$email now holds every run on this account, on any phone you sign in on."
            } else {
                "Signed in as $email. Your runs follow this account."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(14.dp))
        DreamXiSecondaryButton(text = "Sign out", onClick = onSignOut, modifier = Modifier.fillMaxWidth())
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
            .border(
                width = 1.dp,
                // A run he won is worth spotting from across the list.
                color = if (run.wonIt) AccentGold.copy(alpha = 0.55f) else OutlineSubtle,
                shape = RoundedCornerShape(14.dp),
            )
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
                    // Labelled wherever it appears: winning a league with a
                    // hand-picked XI is not the achievement a drafted one is.
                    if (run.isFreeMode) append(" · Free Mode")
                },
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
            )
        }
        Text(
            text = run.outcome,
            style = MaterialTheme.typography.bodyMedium,
            color = if (run.wonIt) AccentGold else OnSurfaceMuted,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun EmptyRuns() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised2)
            .padding(28.dp),
    ) {
        Text(
            text = "Nothing here yet",
            style = MaterialTheme.typography.titleMedium,
            color = OnSurfacePrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Finish a season or a World Cup and it will be waiting here.",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
    }
}

private fun ordinal(value: Int): String {
    val suffix = when {
        value % 100 in 11..13 -> "th"
        value % 10 == 1 -> "st"
        value % 10 == 2 -> "nd"
        value % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$value$suffix"
}

private val PreviewRuns = listOf(
    PastRun(1, PastRun.Kind.LEAGUE, "Premier League · 2021/22", "1st · 89 pts", false, "2026-09-23", 1, 89, true),
    PastRun(2, PastRun.Kind.WORLD_CUP, "2018 World Cup", "World champions", false, "2026-09-22", null, null, true),
    PastRun(3, PastRun.Kind.LEAGUE, "La Liga · 2019/20", "4th · 71 pts", true, "2026-09-21", 4, 71, false),
    PastRun(4, PastRun.Kind.LEAGUE, "Premier League · 2023/24", "11th · 48 pts", false, "2026-09-20", 11, 48, false),
)

@Preview(name = "Profile · guest", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 900)
@Composable
private fun ProfileGuestPreview() = DreamXITheme {
    ProfileScreen(
        state = ProfileUiState(
            isLoading = false,
            account = PlayerAccount("u", isGuest = true),
            runs = PreviewRuns,
            record = PlayerRecord.from(PreviewRuns),
        ),
        onBack = {}, onRetry = {}, onOpenAttach = {}, onCancelAttach = {},
        onEmail = {}, onPassword = {}, onAttach = {}, onSignOut = {},
    )
}

@Preview(name = "Profile · signed in", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 900)
@Composable
private fun ProfileSignedInPreview() = DreamXITheme {
    ProfileScreen(
        state = ProfileUiState(
            isLoading = false,
            account = PlayerAccount("u", isGuest = false, email = "kunal@example.com"),
            runs = PreviewRuns,
            record = PlayerRecord.from(PreviewRuns),
        ),
        onBack = {}, onRetry = {}, onOpenAttach = {}, onCancelAttach = {},
        onEmail = {}, onPassword = {}, onAttach = {}, onSignOut = {},
    )
}

@Preview(name = "Profile · empty", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 700)
@Composable
private fun ProfileEmptyPreview() = DreamXITheme {
    ProfileScreen(
        state = ProfileUiState(isLoading = false, account = PlayerAccount("u", isGuest = true)),
        onBack = {}, onRetry = {}, onOpenAttach = {}, onCancelAttach = {},
        onEmail = {}, onPassword = {}, onAttach = {}, onSignOut = {},
    )
}
