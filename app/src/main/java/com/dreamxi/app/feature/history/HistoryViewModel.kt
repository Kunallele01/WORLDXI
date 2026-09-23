package com.dreamxi.app.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dreamxi.app.core.ui.components.DreamXiError
import com.dreamxi.app.data.auth.PlayerAccount
import com.dreamxi.app.data.auth.SessionRepository
import com.dreamxi.app.data.run.PastRun
import com.dreamxi.app.data.run.RunHistoryRepository
import com.dreamxi.app.data.toUserFacingError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryUiState(
    val isLoading: Boolean = true,
    val runs: List<PastRun> = emptyList(),
    val account: PlayerAccount? = null,
    val error: DreamXiError? = null,
    /** The "keep my runs" sheet, and what it is doing. */
    val isAttaching: Boolean = false,
    val attachInFlight: Boolean = false,
    val attachError: String? = null,
    val attachDone: Boolean = false,
) {
    /**
     * Whether to warn that these runs live only on this phone.
     *
     * Shown whenever there is something to lose and no way to recover it. It
     * disappears the moment an email is attached, rather than being dismissible
     * — a warning you can wave away is one nobody reads twice, and this one is
     * true until it is acted on.
     */
    val warnRunsAreLocal: Boolean get() = account?.isGuest == true && runs.isNotEmpty()
}

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val runHistory: RunHistoryRepository,
    private val session: SessionRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(HistoryUiState())
    val state: StateFlow<HistoryUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            session.account.collect { account -> _state.update { it.copy(account = account) } }
        }
        refresh()
    }

    fun openAttach() = _state.update {
        it.copy(isAttaching = true, attachError = null, attachDone = false)
    }

    fun dismissAttach() = _state.update { it.copy(isAttaching = false, attachError = null) }

    /**
     * Adds an email and password to the account that already exists.
     *
     * The important part is what this does NOT do: it never signs out and signs
     * up again. The runs belong to the current user id, and creating a second
     * account would leave every one of them behind under an id nobody can reach
     * any more. [SessionRepository.attachEmail] updates this user in place.
     */
    fun attachEmail(email: String, password: String) {
        val address = email.trim()
        val problem = when {
            address.isEmpty() || "@" !in address || address.startsWith("@") || address.endsWith("@") ->
                "That does not look like an email address."
            password.length < 8 -> "Use at least 8 characters."
            else -> null
        }
        if (problem != null) {
            _state.update { it.copy(attachError = problem) }
            return
        }
        _state.update { it.copy(attachInFlight = true, attachError = null) }
        viewModelScope.launch {
            runCatching { session.attachEmail(address, password) }
                .onSuccess {
                    _state.update {
                        it.copy(attachInFlight = false, attachDone = true, isAttaching = false)
                    }
                }
                .onFailure { t ->
                    _state.update {
                        it.copy(
                            attachInFlight = false,
                            // The server's own words here, not a mapped category:
                            // "a user with this email already exists" is exactly
                            // what the player needs to read, and the generic
                            // network mapping would hide it.
                            attachError = t.message?.takeIf { m -> m.isNotBlank() }
                                ?: "That did not work. Try again.",
                        )
                    }
                }
        }
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
                _state.update { it.copy(isLoading = false, runs = runs, error = null) }
            }.onFailure { t ->
                _state.update {
                    it.copy(isLoading = false, error = t.toUserFacingError("loading your history"))
                }
            }
        }
    }
}
