package com.dreamxi.app

import com.dreamxi.app.data.auth.PlayerAccount
import com.dreamxi.app.data.run.PastRun
import com.dreamxi.app.feature.profile.ProfileUiState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the app warns that runs live only on this phone.
 *
 * The warning is the price of signing people in anonymously: their runs are
 * genuinely saved, under a real account, and nothing can hand them back if the
 * phone is wiped. It has to appear whenever both halves of that are true and
 * disappear the moment one stops being true — a warning shown when it does not
 * apply is one nobody reads when it does.
 */
class ProfileStateTest {

    private fun run(id: Long) =
        PastRun(id, PastRun.Kind.LEAGUE, "Premier League", "4th · 71 pts", false, "2026-09-23")

    private val guest = PlayerAccount("u1", isGuest = true)
    private val attached = PlayerAccount("u1", isGuest = false, email = "a@b.com")

    @Test
    fun `a guest with runs is warned`() {
        val state = ProfileUiState(isLoading = false, runs = listOf(run(1)), account = guest)
        assertTrue(state.warnRunsAreLocal)
    }

    @Test
    fun `a guest with nothing saved is not warned`() {
        // Nothing to lose yet, so the warning would only be noise before the
        // first run — which is exactly when a player is least able to judge it.
        val state = ProfileUiState(isLoading = false, runs = emptyList(), account = guest)
        assertFalse(state.warnRunsAreLocal)
    }

    @Test
    fun `attaching an email silences the warning`() {
        val state = ProfileUiState(isLoading = false, runs = listOf(run(1), run(2)), account = attached)
        assertFalse(state.warnRunsAreLocal)
    }

    @Test
    fun `no account yet means no claim either way`() {
        // Before the first session there is nothing to say: an offline launch
        // must not accuse the app of losing runs it has not taken yet.
        val state = ProfileUiState(isLoading = false, runs = emptyList(), account = null)
        assertFalse(state.warnRunsAreLocal)
    }

    /**
     * The distinction the whole flow rests on: a guest is defined by having no
     * way back in, not by how the session was created. An account with an email
     * is recoverable; one without is not, whatever else is true of it.
     */
    /**
     * The state that broke this in the wild: an address given, the confirmation
     * link not yet clicked. Supabase holds the address aside and the account
     * keeps NO email until it is used, so both "signed in" and "guest with
     * nothing" are wrong — one is a lie, the other looks like the attach failed.
     */
    @Test
    fun `an address awaiting confirmation is neither signed in nor a plain guest`() {
        val pending = PlayerAccount("u1", isGuest = true, pendingEmail = "a@b.com")
        val state = ProfileUiState(isLoading = false, runs = listOf(run(1)), account = pending)
        assertTrue("should say the email is pending", state.awaitingConfirmation)
        assertFalse("the plain red warning would read as a failed attach", state.warnRunsAreLocal)
    }

    @Test
    fun `a guest with no pending address still gets the plain warning`() {
        val state = ProfileUiState(isLoading = false, runs = listOf(run(1)), account = guest)
        assertTrue(state.warnRunsAreLocal)
        assertFalse(state.awaitingConfirmation)
    }

    @Test
    fun `a confirmed account is never waiting on anything`() {
        val state = ProfileUiState(isLoading = false, runs = listOf(run(1)), account = attached)
        assertFalse(state.awaitingConfirmation)
        assertFalse(state.warnRunsAreLocal)
    }

    @Test
    fun `an account is a guest exactly when it has no email`() {
        assertTrue(PlayerAccount("u", isGuest = true).isGuest)
        assertFalse(PlayerAccount("u", isGuest = false, email = "someone@example.com").isGuest)
    }
}
