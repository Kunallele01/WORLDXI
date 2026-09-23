package com.dreamxi.app.data.auth

import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.auth.user.UserInfo
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "DreamXI"

/** Who is playing, as the app needs to know it. */
data class PlayerAccount(
    val userId: String,
    /**
     * True while the account exists only on this phone.
     *
     * The distinction the whole sign-in flow turns on: an anonymous player has
     * a real user id and real rows, but nothing that could ever restore them
     * on another device or after an uninstall.
     */
    val isGuest: Boolean,
    val email: String? = null,
    /**
     * An address given but not yet confirmed.
     *
     * This project requires a confirmation click, so attaching an email does
     * NOT take effect immediately: Supabase holds it aside and the account
     * keeps no email at all until the link is used. Without this field the app
     * had no way to tell "never gave an address" from "gave one, waiting on
     * the email" — and told both of them they were still guests, straight
     * after reporting success.
     */
    val pendingEmail: String? = null,
) {
    /** Something was given; it just is not live yet. */
    val awaitingConfirmation: Boolean get() = isGuest && !pendingEmail.isNullOrBlank()
}

@Serializable
private data class ProfileRow(
    val id: String,
    val display_name: String? = null,
)

/**
 * The player's session.
 *
 * WHY ANONYMOUS FIRST. Every user-owned table has been sitting behind
 * owner-only RLS since the first schema, and nothing has ever written a row to
 * them, because a run needs a `auth.uid()` and the app never had one. The
 * cheapest way to get one is not to ask: Supabase issues an anonymous session
 * with a real user id, so runs persist from the first launch and no player
 * meets a sign-up form before they have anything worth saving.
 *
 * An anonymous account is a real account in every respect except recovery. It
 * survives restarts — supabase-kt's Android artifact persists the session
 * itself — but it lives only on this device, and uninstalling loses it. That is
 * what [PlayerAccount.isGuest] exists to let the UI say out loud.
 *
 * ATTACHING AN EMAIL UPGRADES IN PLACE. [attachEmail] adds credentials to the
 * SAME user id rather than creating a second account, so every run saved as a
 * guest is still theirs afterwards. Signing out and in again would not do
 * this — it would strand them.
 */
@Singleton
class SessionRepository @Inject constructor(
    private val client: SupabaseClient,
) {

    /** The current account, or null before the first session is established. */
    val account: Flow<PlayerAccount?> =
        client.auth.sessionStatus.map { status ->
            (status as? SessionStatus.Authenticated)?.session?.user?.let { it.toAccount() }
        }

    private fun UserInfo.toAccount() = PlayerAccount(
        userId = id,
        // An anonymous user has no way back in. Supabase marks it on the JWT,
        // but the honest test for "could this person ever recover these runs"
        // is whether there is anything to recover them WITH — and an address
        // still waiting on a confirmation click is not yet that.
        isGuest = email.isNullOrBlank(),
        email = email?.takeIf { it.isNotBlank() },
        pendingEmail = newEmail?.takeIf { it.isNotBlank() },
    )

    /**
     * Re-reads the user from the server and updates the stored session.
     *
     * Needed after attaching an email: the local session still holds the user
     * as it was before, so without this the screen keeps showing what it showed
     * a second ago and looks like nothing happened.
     */
    suspend fun refreshUser(): PlayerAccount? = runCatching {
        client.auth.retrieveUserForCurrentSession(updateSession = true).toAccount()
    }.getOrNull()

    /**
     * Whether this device already has an account, without creating one.
     *
     * Waits for the stored session to load first — asking before that is how
     * the app used to mint a new anonymous user on every launch.
     */
    suspend fun hasSession(): Boolean {
        client.auth.awaitInitialization()
        return client.auth.currentUserOrNull() != null
    }

    /**
     * Makes sure there is a session, signing in anonymously if there is not.
     *
     * Safe to call on every launch: an existing session is left alone, so this
     * never replaces one account with another.
     */
    suspend fun ensureSession(): PlayerAccount {
        // WAIT FOR THE STORED SESSION FIRST. supabase-kt loads the saved session
        // off disk in the background, so for the first moments of a cold start
        // there is no user even when there is a perfectly good account on the
        // device. Asking straight away and signing in on the answer created a
        // BRAND NEW anonymous user on every launch — orphaning the previous
        // one, and every run saved under it, silently. Nothing in the logs said
        // so beyond the same line appearing each time the app opened.
        client.auth.awaitInitialization()

        val existing = client.auth.currentUserOrNull()
        if (existing == null) {
            Log.i(TAG, "no session — signing in anonymously")
            client.auth.signInAnonymously()
        }
        val user = requireNotNull(client.auth.currentUserOrNull()) {
            "anonymous sign-in returned no user — is it enabled for this project?"
        }
        // The profile row is created by the app rather than by a database
        // trigger, so this needs no migration and no privileged function. It is
        // an upsert because a returning player already has one.
        runCatching {
            client.from("profiles").upsert(ProfileRow(id = user.id))
        }.onFailure { Log.w(TAG, "could not write profile row", it) }

        return user.toAccount()
    }

    /**
     * Turns this guest into a recoverable account, keeping the same user id and
     * therefore every run already saved.
     *
     * Depending on the project's settings Supabase may require the address to
     * be confirmed before the change takes effect; until it is, the session
     * keeps working and the runs stay put either way.
     */
    suspend fun attachEmail(email: String, password: String) {
        client.auth.updateUser {
            this.email = email
            this.password = password
        }
    }

    /** Signs an existing account back in on a device that has none. */
    suspend fun signIn(email: String, password: String) {
        client.auth.signInWith(Email) {
            this.email = email
            this.password = password
        }
    }

    /**
     * Signs out and leaves the device with NO session.
     *
     * An earlier version took a fresh anonymous session immediately afterwards,
     * reasoning that the app should never be unable to save. That was wrong in
     * the way that matters: signing out minted a new guest on the spot, so the
     * next launch found a session, skipped the sign-in screen, and dropped the
     * player into the game as a guest he never asked to become. Signing out
     * means signing out; the way back in is the front door.
     */
    suspend fun signOut() {
        client.auth.signOut()
    }
}
