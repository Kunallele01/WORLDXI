package com.dreamxi.app.feature.profile

import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.width
import com.dreamxi.app.R
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
import androidx.compose.runtime.LaunchedEffect
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
import com.dreamxi.app.core.ui.passwordStrength
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
fun ProfileRoute(
    onBack: () -> Unit,
    onSignIn: () -> Unit,
    onSignedOut: () -> Unit = onSignIn,
    modifier: Modifier = Modifier,
) {
    val viewModel: ProfileViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Signed out means no session at all now, so there is nothing for this
    // screen to show — the player belongs back at the way in.
    LaunchedEffect(state.signedOut) { if (state.signedOut) onSignedOut() }
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
        onSignIn = onSignIn,
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
    onSignIn: () -> Unit,
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
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                // Header and record ride on the artwork as one banner, so the
                // page opens on a ground rather than on a list.
                item { Banner(state.account, state.record, onBack) }

                if (state.isAttaching) {
                    item {
                        Inset { AttachForm(
                            email = state.email,
                            password = state.password,
                            inFlight = state.attachInFlight,
                            error = state.attachError,
                            canSubmit = state.canAttach,
                            onEmail = onEmail,
                            onPassword = onPassword,
                            onAttach = onAttach,
                            onCancel = onCancelAttach,
                            onSignIn = onSignIn,
                        ) }
                    }
                } else if (state.awaitingConfirmation) {
                    item { Inset { AwaitingConfirmation(state.account?.pendingEmail.orEmpty(), onOpenAttach) } }
                } else if (state.account?.isGuest != false) {
                    // EVERY guest gets this, not only one with runs to lose. A
                    // guest with nothing saved used to see no account block at
                    // all, which left him with no way to sign in, no way to
                    // leave, and nothing on screen admitting he was a guest.
                    item { Inset { GuestBlock(state.runs.size, onOpenAttach, onSignIn) } }
                } else {
                    item { Inset { SignedIn(state.account.email.orEmpty(), state.justAttached, onSignOut) } }
                }

                item {
                    Inset {
                        Text(
                            text = "EVERY RUN",
                            style = MaterialTheme.typography.labelSmall,
                            letterSpacing = 1.sp,
                            color = OnSurfaceFaint,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                if (state.runs.isEmpty()) {
                    item { Inset { EmptyRuns() } }
                } else {
                    items(state.runs) { run -> Inset { RunRow(run) } }
                }
            }
        }
    }
}

/** Side padding for everything below the banner, which is full-bleed. */
@Composable
private fun Inset(content: @Composable () -> Unit) {
    Box(Modifier.padding(horizontal = 16.dp)) { content() }
}

/**
 * The top of the page: the ground, who you are, and what you have won.
 *
 * The artwork is the UNLIT stadium, where the sign-in screen uses the lit one —
 * the same place before kick-off rather than during it, so the two screens are
 * obviously the same app without being the same picture.
 */
@Composable
private fun Banner(account: PlayerAccount?, record: PlayerRecord, onBack: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Image(
            painter = painterResource(R.drawable.intro_stadium_dark),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier.matchParentSize(),
        )
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to SurfaceBase.copy(alpha = 0.55f),
                        0.55f to SurfaceBase.copy(alpha = 0.86f),
                        1f to SurfaceBase,
                    ),
                ),
        )
        Column(Modifier.padding(horizontal = 16.dp).padding(top = 8.dp, bottom = 18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "\u2039",
                    fontFamily = DisplayFontFamily,
                    fontSize = 34.sp,
                    color = OnSurfacePrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onBack)
                        .padding(horizontal = 10.dp),
                )
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Avatar(account)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = account?.email?.substringBefore("@")
                            ?: account?.pendingEmail?.substringBefore("@")
                            ?: "Guest",
                        fontFamily = DisplayFontFamily,
                        fontSize = 30.sp,
                        color = OnSurfacePrimary,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = when {
                            account?.email != null -> account.email
                            !account?.pendingEmail.isNullOrBlank() -> "Confirmation pending"
                            else -> "This phone only"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        letterSpacing = 1.sp,
                        color = if (account?.isGuest == false) AccentGold else OnSurfaceMuted,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            RecordBand(record)
        }
    }
}

/** Initials on gold, the same fallback the club badge uses when it has no crest. */
@Composable
private fun Avatar(account: PlayerAccount?, size: androidx.compose.ui.unit.Dp = 58.dp) {
    val initial = account?.email?.firstOrNull()?.uppercase()
        ?: account?.pendingEmail?.firstOrNull()?.uppercase()
        ?: "G"
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(AccentGold)
            .border(2.dp, SurfaceBase, CircleShape),
    ) {
        Text(
            text = initial,
            fontFamily = DisplayFontFamily,
            fontSize = (size.value * 0.46f).sp,
            color = OnAccentGold,
        )
    }
}

/** What he has to show for it: four numbers, no prose. */
@Composable
private fun RecordBand(record: PlayerRecord) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Stat("RUNS", record.runs.toString(), Modifier.weight(1f))
        Stat("TITLES", record.leagueTitles.toString(), Modifier.weight(1f))
        Stat("WORLD CUPS", record.worldCups.toString(), Modifier.weight(1f))
        Stat("BEST", record.bestFinish?.let { ordinal(it) } ?: "\u2014", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    // A number he has actually put on the board is worth marking. Zero and "no
    // best finish yet" stay quiet rather than being dressed up as achievements.
    val earned = value != "0" && value != "\u2014"
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceBase.copy(alpha = 0.88f))
            .border(
                width = 1.dp,
                color = if (earned) AccentGold.copy(alpha = 0.45f) else OutlineSubtle,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(vertical = 14.dp, horizontal = 4.dp),
    ) {
        Text(
            text = value,
            fontFamily = DisplayFontFamily,
            fontSize = 28.sp,
            color = if (earned) AccentGold else OnSurfaceMuted,
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
private fun GuestBlock(runs: Int, onOpenAttach: () -> Unit, onSignIn: () -> Unit) {
    val hasRuns = runs > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised1)
            .border(
                width = 1.dp,
                // Red only when there is something to lose. A guest who has not
                // finished a run yet is being told how it works, not warned.
                color = if (hasRuns) ResultLoss.copy(alpha = 0.5f) else OutlineSubtle,
                shape = RoundedCornerShape(14.dp),
            )
            .padding(16.dp),
    ) {
        Text(
            text = if (hasRuns) "THESE RUNS ARE ONLY ON THIS PHONE" else "PLAYING AS A GUEST",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = if (hasRuns) ResultLoss else AccentGold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = when {
                runs == 1 ->
                    "Your run is saved to this device's account. Uninstall the app or " +
                        "lose the phone and it goes with it. Add an email and it follows you."
                hasRuns ->
                    "All $runs runs are saved to this device's account. Uninstall the app " +
                        "or lose the phone and they go with it. Add an email and they follow you."
                else ->
                    "Everything you play is saved, but only to this phone. Add an email " +
                        "and it follows you to any device you sign in on."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(14.dp))
        DreamXiPrimaryButton(
            text = if (hasRuns) "Keep my runs" else "Add an email",
            onClick = onOpenAttach,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(10.dp))
        // The way OUT of a guest account, which nothing on this screen used to
        // offer: sign-out is only shown to a real account, so a guest could
        // neither leave nor sign into an account he already had.
        DreamXiSecondaryButton(
            text = "Sign in to another account",
            onClick = onSignIn,
            modifier = Modifier.fillMaxWidth(),
        )
        if (hasRuns) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Signing in leaves these runs with the guest account.",
                style = MaterialTheme.typography.labelSmall,
                color = OnSurfaceFaint,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * Given an address, waiting on the click.
 *
 * This project requires email confirmation, so an attached address does nothing
 * until the link is used — the account keeps no email at all in the meantime.
 * Saying "your runs are safe" at that point would be a lie, and saying "these
 * runs are only on this phone" with no explanation looks like the attach
 * failed. It is its own state because it IS its own state.
 */
@Composable
private fun AwaitingConfirmation(email: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceRaised1)
            .border(1.dp, AccentGold.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Text(
            text = "CONFIRM YOUR EMAIL",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.sp,
            color = AccentGold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (email.isBlank()) {
                "We sent you a confirmation link. Until you open it, these runs are " +
                    "still only on this phone."
            } else {
                "We sent a link to $email. Until you open it, these runs are still " +
                    "only on this phone — the address is not attached to the account yet."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(14.dp))
        // The way out, which this state used to lack entirely. If email
        // confirmation has since been switched off, the queued address is never
        // applied on its own — giving it again is what clears this.
        DreamXiPrimaryButton(
            text = "Try again",
            onClick = onRetry,
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
    onSignIn: () -> Unit,
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
            strength = passwordStrength(password),
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
        Spacer(Modifier.height(12.dp))
        // The other case: an account already exists, on another phone. Signing
        // into it is NOT the same as keeping these runs — they belong to the
        // account being left behind — so it says so rather than being an
        // equally weighted button.
        Text(
            text = "Already have an account? Sign in",
            style = MaterialTheme.typography.bodySmall,
            color = AccentGold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(enabled = !inFlight, onClick = onSignIn)
                .padding(vertical = 8.dp),
        )
        Text(
            text = "Runs saved on this phone stay with the guest account you are leaving.",
            style = MaterialTheme.typography.labelSmall,
            color = OnSurfaceFaint,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
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
        onEmail = {}, onPassword = {}, onAttach = {}, onSignOut = {}, onSignIn = {},
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
        onEmail = {}, onPassword = {}, onAttach = {}, onSignOut = {}, onSignIn = {},
    )
}

@Preview(name = "Profile · empty", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 700)
@Composable
private fun ProfileEmptyPreview() = DreamXITheme {
    ProfileScreen(
        state = ProfileUiState(isLoading = false, account = PlayerAccount("u", isGuest = true)),
        onBack = {}, onRetry = {}, onOpenAttach = {}, onCancelAttach = {},
        onEmail = {}, onPassword = {}, onAttach = {}, onSignOut = {}, onSignIn = {},
    )
}
