package com.dreamxi.app.feature.auth

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.dreamxi.app.R
import com.dreamxi.app.ui.theme.OutlineSubtle
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dreamxi.app.core.ui.passwordStrength
import com.dreamxi.app.core.ui.components.DreamXiPrimaryButton
import com.dreamxi.app.core.ui.components.DreamXiTextField
import com.dreamxi.app.ui.theme.AccentGold
import com.dreamxi.app.ui.theme.DisplayFontFamily
import com.dreamxi.app.ui.theme.DreamXITheme
import com.dreamxi.app.ui.theme.OnAccentGold
import com.dreamxi.app.ui.theme.OnSurfaceFaint
import com.dreamxi.app.ui.theme.OnSurfaceMuted
import com.dreamxi.app.ui.theme.OnSurfacePrimary
import com.dreamxi.app.ui.theme.ResultLoss
import com.dreamxi.app.ui.theme.SurfaceBase
import com.dreamxi.app.ui.theme.SurfaceRaised1
import com.dreamxi.app.ui.theme.SurfaceRaised2

@Composable
fun AuthRoute(onSignedIn: () -> Unit, force: Boolean = false, modifier: Modifier = Modifier) {
    val viewModel: AuthViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(force) { viewModel.start(force) }
    LaunchedEffect(state.done) { if (state.done) onSignedIn() }
    AuthScreen(
        state = state,
        onSetMode = viewModel::setMode,
        onEmail = viewModel::setEmail,
        onPassword = viewModel::setPassword,
        onSubmit = viewModel::submit,
        onGuest = viewModel::continueAsGuest,
        modifier = modifier,
    )
}

/**
 * The way in.
 *
 * SIGNING IN IS OFFERED, NOT DEMANDED. "Play as guest" sits on this screen with
 * the same weight as the form, because the account it creates is real — runs
 * save from the first one, and attaching an email later keeps every one of
 * them. What a guest cannot do is get them back on another phone, which is
 * said here rather than discovered later.
 *
 * Stateless, so every case — both modes, in flight, each error — can be seen in
 * a @Preview without a backend.
 */
@Composable
internal fun AuthScreen(
    state: AuthUiState,
    onSetMode: (AuthMode) -> Unit,
    onEmail: (String) -> Unit,
    onPassword: (String) -> Unit,
    onSubmit: () -> Unit,
    onGuest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().background(SurfaceBase)) {
        // ARTWORK ABOVE, SHEET BELOW — the one screen in the app built this
        // way. Everywhere else is cards floating on black; the front door
        // earns its own architecture without leaving the palette, so it reads
        // as a different room in the same house rather than a different app.
        Image(
            painter = painterResource(R.drawable.intro_stadium_lit),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.25f
                    scaleY = 1.25f
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
        )
        // Only enough to hold the title; the sheet does the rest of the work
        // that a full-screen scrim used to do badly.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to SurfaceBase.copy(alpha = 0.30f),
                        0.45f to SurfaceBase.copy(alpha = 0.20f),
                        1f to SurfaceBase.copy(alpha = 0.55f),
                    ),
                ),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            Spacer(Modifier.height(64.dp))
            Text(
                text = "ERA XI",
                fontFamily = DisplayFontFamily,
                fontSize = 56.sp,
                letterSpacing = 5.sp,
                color = OnSurfacePrimary,
            )
            Spacer(Modifier.height(14.dp))
            Box(Modifier.width(56.dp).height(3.dp).background(AccentGold))
            Spacer(Modifier.height(14.dp))
            Text(
                text = "ELEVEN PLAYERS \u00b7 EVERY ERA",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 3.sp,
                color = OnSurfacePrimary.copy(alpha = 0.78f),
            )

            Spacer(Modifier.weight(1f))

            if (!state.checked) return@Column

            // The sheet: rounded at the top, square at the bottom, running off
            // the screen. A card with four rounded corners floats; this one
            // stands on the floor, which is what makes it read as a surface
            // rather than another panel.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                    .background(SurfaceBase)
                    .padding(horizontal = 24.dp)
                    .padding(top = 26.dp)
                    .navigationBarsPadding()
                    .padding(bottom = 22.dp),
            ) {
                ModeSwitch(state.mode, onSetMode, enabled = !state.inFlight)
                Spacer(Modifier.height(24.dp))

                DreamXiTextField(
                    value = state.email,
                    onValueChange = onEmail,
                    label = "Email",
                    placeholder = "you@example.com",
                    enabled = !state.inFlight,
                    keyboardType = KeyboardType.Email,
                    error = state.emailError,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(18.dp))
                DreamXiTextField(
                    value = state.password,
                    onValueChange = onPassword,
                    label = "Password",
                    placeholder = if (state.mode == AuthMode.CreateAccount) "At least 8 characters" else "",
                    enabled = !state.inFlight,
                    isPassword = true,
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                    error = state.passwordError,
                    // Only while CHOOSING one. On the sign-in side the password
                    // already exists and rating it would be both noise and a
                    // hint about an account that is not necessarily yours.
                    strength = if (state.mode == AuthMode.CreateAccount) {
                        passwordStrength(state.password)
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                if (state.formError != null) {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        text = state.formError,
                        style = MaterialTheme.typography.bodySmall,
                        color = ResultLoss,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                Spacer(Modifier.height(22.dp))
                DreamXiPrimaryButton(
                    text = when {
                        state.inFlight -> "\u2026"
                        state.mode == AuthMode.SignIn -> "Sign in"
                        else -> "Create account"
                    },
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(18.dp))
                Text(
                    text = "Play as guest",
                    style = MaterialTheme.typography.titleMedium,
                    color = AccentGold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(enabled = !state.inFlight, onClick = onGuest)
                        .padding(vertical = 8.dp),
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Runs save to this phone only until you add an email.",
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceMuted,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Sign in or create an account: two states of one screen, not two screens.
 *
 * Tabs with a rule under the live one, rather than a gold pill. A filled pill
 * is a BUTTON, and putting one immediately above a real button had two things
 * on the sheet claiming to be the thing to press.
 */
@Composable
private fun ModeSwitch(mode: AuthMode, onSetMode: (AuthMode) -> Unit, enabled: Boolean) {
    Row(Modifier.fillMaxWidth()) {
        AuthMode.entries.forEach { option ->
            val selected = option == mode
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .weight(1f)
                    .clickable(enabled = enabled && !selected) { onSetMode(option) }
                    .padding(bottom = 10.dp),
            ) {
                Text(
                    text = if (option == AuthMode.SignIn) "Sign in" else "Create account",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) OnSurfacePrimary else OnSurfaceFaint,
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (selected) AccentGold else OutlineSubtle),
                )
            }
        }
    }
}

@Preview(name = "Sign in", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 800)
@Composable
private fun AuthSignInPreview() = DreamXITheme {
    AuthScreen(AuthUiState(email = "kunal@example.com", password = "secret123", checked = true), {}, {}, {}, {}, {})
}

@Preview(name = "Create account", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 800)
@Composable
private fun AuthCreatePreview() = DreamXITheme {
    AuthScreen(AuthUiState(mode = AuthMode.CreateAccount, checked = true), {}, {}, {}, {}, {})
}

@Preview(name = "Rejected", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 800)
@Composable
private fun AuthErrorPreview() = DreamXITheme {
    AuthScreen(
        AuthUiState(
            email = "kunal@example.com",
            password = "wrong",
            formError = "Invalid login credentials",
            checked = true,
        ),
        {}, {}, {}, {}, {},
    )
}
