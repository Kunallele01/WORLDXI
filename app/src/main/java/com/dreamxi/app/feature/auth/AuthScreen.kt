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
        // The lit stadium from the intro, already shipped for it. A front door
        // built from four grey boxes on black is a settings page; the same form
        // over the same artwork the player just watched is an arrival. It costs
        // nothing new — the asset is in the apk either way.
        Image(
            painter = painterResource(R.drawable.intro_stadium_lit),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            // Cropped in and pushed up. Fitted to the screen the picture reads
            // as a wide shot of a small ground with a lot of empty sky; scaled
            // past the frame and anchored high, the floodlights and the stands
            // fill the part of the screen that is actually visible above the
            // card, which is the only part the player ever sees.
            // Anchored to the TOP of the picture, not its middle: the
            // floodlights are the part worth seeing, and scaling about the
            // centre climbed straight past them into the stands — which put a
            // bright band of crowd through the tagline. 1.25 crops in enough to
            // fill the frame while keeping the lights in it.
            alignment = Alignment.TopCenter,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.25f
                    scaleY = 1.25f
                    transformOrigin = TransformOrigin(0.5f, 0f)
                },
        )
        // The scrim only has to protect the TEXT, and the first version
        // protected the whole screen: 0.55 at the top and near-solid by the
        // middle turned a painted stadium into a black rectangle with a smudge
        // on it. The artwork is the reason this screen exists now, so the top
        // is left almost clear and the floor comes in under the card.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to SurfaceBase.copy(alpha = 0.20f),
                        0.30f to SurfaceBase.copy(alpha = 0.30f),
                        0.52f to SurfaceBase.copy(alpha = 0.72f),
                        0.72f to SurfaceBase.copy(alpha = 0.95f),
                        1f to SurfaceBase,
                    ),
                ),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Sits in the lit part of the stadium rather than above it.
            Spacer(Modifier.height(72.dp))
            Text(
                text = "ERA XI",
                fontFamily = DisplayFontFamily,
                fontSize = 58.sp,
                letterSpacing = 5.sp,
                color = OnSurfacePrimary,
            )
            Spacer(Modifier.height(14.dp))
            // A gold rule rather than more grey text: the accent reaches the top
            // of the screen instead of living only on one button near the foot.
            Box(Modifier.width(56.dp).height(3.dp).background(AccentGold))
            Spacer(Modifier.height(14.dp))
            Text(
                text = "ELEVEN PLAYERS · EVERY ERA",
                style = MaterialTheme.typography.labelSmall,
                letterSpacing = 3.sp,
                color = OnSurfacePrimary.copy(alpha = 0.75f),
            )

            if (!state.checked) return@Column

            Spacer(Modifier.height(36.dp))

            // The form on its own raised card, so it reads as a panel standing
            // on the scene rather than fields floating over a photograph.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    // Nearly solid, and DARKER than the scene behind it, so the
                    // card reads as a panel standing on the stadium. A surface
                    // three shades off the background simply merged with it,
                    // which is what made the whole screen read as one grey.
                    .background(SurfaceBase.copy(alpha = 0.92f))
                    .border(1.dp, AccentGold.copy(alpha = 0.22f), RoundedCornerShape(22.dp))
                    .padding(horizontal = 20.dp, vertical = 22.dp),
            ) {
                ModeSwitch(state.mode, onSetMode, enabled = !state.inFlight)
                Spacer(Modifier.height(22.dp))

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
                Spacer(Modifier.height(20.dp))
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
                        state.inFlight -> "…"
                        state.mode == AuthMode.SignIn -> "Sign in"
                        else -> "Create account"
                    },
                    onClick = onSubmit,
                    enabled = state.canSubmit,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(22.dp))
            Text(
                text = "Play as guest",
                style = MaterialTheme.typography.titleMedium,
                color = AccentGold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = !state.inFlight, onClick = onGuest)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Your runs still save — but only on this phone, and only until " +
                    "you add an email.",
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** Sign in or create an account: two states of one screen, not two screens. */
@Composable
private fun ModeSwitch(mode: AuthMode, onSetMode: (AuthMode) -> Unit, enabled: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceRaised1)
            .padding(4.dp),
    ) {
        AuthMode.entries.forEach { option ->
            val selected = option == mode
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(9.dp))
                    .background(if (selected) AccentGold else SurfaceRaised1)
                    .clickable(enabled = enabled && !selected) { onSetMode(option) }
                    .padding(vertical = 11.dp),
            ) {
                Text(
                    text = if (option == AuthMode.SignIn) "Sign in" else "Create account",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) OnAccentGold else OnSurfaceMuted,
                )
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(SurfaceRaised2))
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
