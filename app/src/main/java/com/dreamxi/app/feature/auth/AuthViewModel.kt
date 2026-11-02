package com.dreamxi.app.feature.auth

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamxi.app.data.auth.SessionRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.auth.exception.AuthRestException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

/** Sign in to an account that exists, or make one. */
enum class AuthMode { SignIn, CreateAccount }

data class AuthUiState(
    val mode: AuthMode = AuthMode.SignIn,
    val email: String = "",
    val password: String = "",
    val inFlight: Boolean = false,
    val emailError: String? = null,
    val passwordError: String? = null,
    val formError: String? = null,
    /** Set once there is a session; the screen's caller moves on. */
    val done: Boolean = false,
    /**
     * False until we know whether this device already has an account.
     *
     * The form must not be drawn before that is settled. Drawing it first and
     * navigating a moment later flashed a sign-in screen at every returning
     * player, which reads as the app glitching — and, worse, looks exactly like
     * it signed them in without asking.
     */
    val checked: Boolean = false,
) {
    val canSubmit: Boolean get() = !inFlight && email.isNotBlank() && password.isNotBlank()
}

@HiltViewModel
class AuthViewModel @Inject constructor(
    private val session: SessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(AuthUiState())
    val state: StateFlow<AuthUiState> = _state.asStateFlow()

    /**
     * Looks for an existing session, unless the player asked for this screen.
     *
     * A player who already has a session — signed in, or a guest from an
     * earlier launch — never sees this screen after the intro. It checks rather
     * than calling ensureSession, because ensureSession would TAKE a guest
     * session and wave everyone past the front door on their first launch.
     *
     * [force] is the exception: he came here FROM his profile to sign into an
     * account he made on another phone, and skipping him past it because he
     * already has a session would be the screen refusing the one thing he
     * asked it for.
     */
    fun start(force: Boolean) {
        if (started) return
        started = true
        viewModelScope.launch {
            val existing = !force && runCatching { session.hasSession() }.getOrDefault(false)
            _state.update { it.copy(checked = true, done = existing) }
        }
    }

    private var started = false

    fun setMode(mode: AuthMode) = _state.update {
        it.copy(mode = mode, formError = null, emailError = null, passwordError = null)
    }

    fun setEmail(value: String) = _state.update { it.copy(email = value, emailError = null, formError = null) }

    fun setPassword(value: String) =
        _state.update { it.copy(password = value, passwordError = null, formError = null) }

    /**
     * Straight to the draft, with an account that exists but cannot be
     * recovered.
     *
     * Kept on the sign-in screen rather than hidden, because the alternative is
     * a player who has not decided yet bouncing off a form — and because the
     * account this creates is real: his runs save from the first one, and
     * attaching an email later keeps every one of them.
     */
    fun continueAsGuest() {
        if (_state.value.inFlight) return
        _state.update { it.copy(inFlight = true, formError = null) }
        viewModelScope.launch {
            runCatching { session.ensureSession() }
                .onSuccess { _state.update { s -> s.copy(inFlight = false, done = true) } }
                .onFailure { t -> fail(t) }
        }
    }

    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return

        val email = s.email.trim()
        val emailProblem = "That does not look like an email address."
            .takeIf { "@" !in email || email.startsWith("@") || email.endsWith("@") }
        val passwordProblem = "Use at least 8 characters."
            .takeIf { s.mode == AuthMode.CreateAccount && s.password.length < 8 }
        if (emailProblem != null || passwordProblem != null) {
            _state.update { it.copy(emailError = emailProblem, passwordError = passwordProblem) }
            return
        }

        _state.update { it.copy(inFlight = true, formError = null) }
        viewModelScope.launch {
            runCatching {
                when (s.mode) {
                    AuthMode.SignIn -> session.signIn(email, s.password)
                    // Creating an account from the sign-in screen means there is
                    // no guest session to preserve — but ensureSession has taken
                    // one by now on some devices, so this UPGRADES rather than
                    // replacing. Either way the player ends up with one account
                    // and everything that was saved under it.
                    AuthMode.CreateAccount -> {
                        session.ensureSession()
                        session.attachEmail(email, s.password)
                    }
                }
            }
                .onSuccess { _state.update { st -> st.copy(inFlight = false, done = true) } }
                .onFailure { t -> fail(t) }
        }
    }

    private fun fail(t: Throwable) {
        Log.w("DreamXI", "auth failed", t)
        _state.update { it.copy(inFlight = false, formError = authMessage(t)) }
    }

    /**
     * Words for the player, never the library's.
     *
     * supabase-kt's `message` is a debug dump — the request URL, the headers,
     * the HTTP method — which is what used to be drawn on screen. The error
     * CODE is what carries the meaning, so the wording is chosen from that.
     *
     * Sign-in deliberately does NOT say whether the email exists. Supabase
     * returns the same `invalid_credentials` for an unknown address and a wrong
     * password, because telling them apart lets anyone test which emails have
     * accounts. The message points at both possibilities instead.
     */
    private fun authMessage(t: Throwable): String {
        var cause: Throwable? = t
        while (cause != null) {
            if (cause is AuthRestException) {
                return when (cause.error) {
                    "invalid_credentials" ->
                        "That email and password do not match. Check them, or create an account if you are new."
                    "user_already_exists", "email_exists" ->
                        "An account with that email already exists. Sign in instead."
                    "weak_password" ->
                        "That password is too easy to guess. Try a longer or less common one."
                    "email_not_confirmed" ->
                        "Confirm your email first — there is a link waiting in your inbox."
                    "over_request_rate_limit", "over_email_send_rate_limit" ->
                        "Too many attempts. Wait a minute and try again."
                    else -> "That did not work. Try again."
                }
            }
            if (cause is IOException) return "Cannot reach the server. Check your connection and try again."
            cause = cause.cause.takeIf { it !== cause }
        }
        return "That did not work. Try again."
    }
}
