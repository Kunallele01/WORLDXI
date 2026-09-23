package com.dreamxi.app.feature.auth

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
fun AuthRoute(onSignedIn: () -> Unit, modifier: Modifier = Modifier) {
    val viewModel: AuthViewModel = hiltViewModel()
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .fillMaxSize()
            .background(SurfaceBase)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 40.dp),
    ) {
        Spacer(Modifier.height(20.dp))
        Text(
            text = "ERA XI",
            fontFamily = DisplayFontFamily,
            fontSize = 44.sp,
            letterSpacing = 3.sp,
            color = OnSurfacePrimary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "ELEVEN PLAYERS · EVERY ERA",
            style = MaterialTheme.typography.labelSmall,
            letterSpacing = 2.sp,
            color = OnSurfaceFaint,
        )

        Spacer(Modifier.height(44.dp))
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
        Spacer(Modifier.height(16.dp))
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

        Spacer(Modifier.height(24.dp))
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

        Spacer(Modifier.height(28.dp))
        Divider()
        Spacer(Modifier.height(20.dp))

        Text(
            text = "Play as guest",
            style = MaterialTheme.typography.titleMedium,
            color = AccentGold,
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .clickable(enabled = !state.inFlight, onClick = onGuest)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Your runs still save — but only on this phone, and only until " +
                "you add an email.",
            style = MaterialTheme.typography.bodySmall,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center,
        )
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
    AuthScreen(AuthUiState(email = "kunal@example.com", password = "secret123"), {}, {}, {}, {}, {})
}

@Preview(name = "Create account", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 800)
@Composable
private fun AuthCreatePreview() = DreamXITheme {
    AuthScreen(AuthUiState(mode = AuthMode.CreateAccount), {}, {}, {}, {}, {})
}

@Preview(name = "Rejected", showBackground = true, backgroundColor = 0xFF0C0D0F, heightDp = 800)
@Composable
private fun AuthErrorPreview() = DreamXITheme {
    AuthScreen(
        AuthUiState(
            email = "kunal@example.com",
            password = "wrong",
            formError = "Invalid login credentials",
        ),
        {}, {}, {}, {}, {},
    )
}
