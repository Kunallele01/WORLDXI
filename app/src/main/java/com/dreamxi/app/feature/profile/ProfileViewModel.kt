package com.dreamxi.app.feature.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.data.auth.PlayerAccount
import com.dreamxi.app.data.auth.SessionRepository
import com.dreamxi.app.data.run.PastRun
import com.dreamxi.app.data.run.PlayerRecord
import com.dreamxi.app.data.run.RunHistoryRepository
import com.dreamxi.app.data.toUserFacingError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ProfileUiState(
    val isLoading: Boolean = true,
    val account: PlayerAccount? = null,
    val runs: List<PastRun> = emptyList(),
    val record: PlayerRecord = PlayerRecord(0, 0, 0, null, null),
    val error: DreamXiError? = null,
    /** The keep-my-runs form, shown inline on this page rather than as a dialog. */
    val isAttaching: Boolean = false,
    val email: String = "",
    val password: String = "",
    val attachInFlight: Boolean = false,
    val attachError: String? = null,
    val justAttached: Boolean = false,
) {
    /**
     * Whether to warn that these runs live only on this phone.
     *
     * Shown whenever there is something to lose and no way to recover it. Not
     * dismissible: a warning you can wave away is one nobody reads twice, and
     * this one stays true until it is acted on.
     */
    val warnRunsAreLocal: Boolean
        get() = account?.isGuest == true && account.pendingEmail.isNullOrBlank() && runs.isNotEmpty()

    /** An address was given and the confirmation link has not been clicked yet. */
    val awaitingConfirmation: Boolean get() = account?.awaitingConfirmation == true

    val canAttach: Boolean get() = !attachInFlight && email.isNotBlank() && password.isNotBlank()
}

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val runHistory: RunHistoryRepository,
    private val session: SessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(ProfileUiState())
    val state: StateFlow<ProfileUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            session.account.collect { account -> _state.update { it.copy(account = account) } }
        }
        refresh()
    }

    fun refresh() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                // A player who has never been online has no session yet, and an
                // empty history would be a lie rather than an answer.
                session.ensureSession()
                runHistory.history()
            }.onSuccess { runs ->
                _state.update {
                    it.copy(
                        isLoading = false,
                        runs = runs,
                        record = PlayerRecord.from(runs),
                        error = null,
                    )
                }
            }.onFailure { t ->
                _state.update {
                    it.copy(isLoading = false, error = t.toUserFacingError("loading your profile"))
                }
            }
        }
    }

    fun openAttach() = _state.update { it.copy(isAttaching = true, attachError = null) }
    fun cancelAttach() = _state.update { it.copy(isAttaching = false, attachError = null) }
    fun setEmail(value: String) = _state.update { it.copy(email = value, attachError = null) }
    fun setPassword(value: String) = _state.update { it.copy(password = value, attachError = null) }

    /**
     * Adds an email and password to the account that already exists.
     *
     * The important part is what this does NOT do: it never signs out and signs
     * up again. The runs belong to the current user id, and a second account
     * would leave every one of them behind under an id nobody can reach again.
     */
    fun attach() {
        val s = _state.value
        if (!s.canAttach) return
        val email = s.email.trim()
        val problem = when {
            "@" !in email || email.startsWith("@") || email.endsWith("@") ->
                "That does not look like an email address."
            s.password.length < 8 -> "Use at least 8 characters."
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(attachError = problem) }
            return
        }
        _state.update { it.copy(attachInFlight = true, attachError = null) }
        viewModelScope.launch {
            runCatching {
                session.attachEmail(email, s.password)
                // Re-read the user: the stored session still describes him as he
                // was a moment ago, and without this the screen goes on saying
                // "guest" straight after reporting success — which is exactly
                // how this was reported.
                session.refreshUser()
            }
                .onSuccess { refreshed ->
                    _state.update {
                        it.copy(
                            attachInFlight = false,
                            isAttaching = false,
                            justAttached = true,
                            password = "",
                            account = refreshed ?: it.account,
                        )
                    }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(
                            attachInFlight = false,
                            attachError = t.message?.takeIf { m -> m.isNotBlank() }
                                ?: "That did not work. Try again.",
                        )
                    }
                }
        }
    }

    /**
     * Signs out, which only a player with an email can safely do — a guest has
     * no way back to the account he is leaving, so the screen does not offer it.
     */
    fun signOut() {
        viewModelScope.launch {
            runCatching { session.signOut() }
            refresh()
        }
    }
}
