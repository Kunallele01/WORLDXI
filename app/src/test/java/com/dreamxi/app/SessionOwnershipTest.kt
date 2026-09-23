package com.dreamxi.app

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Only the player creates an account, and only from the way in.
 *
 * This rule has been broken twice, both times invisibly:
 *
 *  - Application.onCreate signed in anonymously on launch, which made the
 *    sign-in screen unreachable — every new player already had a guest account
 *    by the time he saw the front door.
 *  - signOut() took a fresh anonymous session immediately afterwards, so
 *    signing out minted a new guest, and the next launch skipped the sign-in
 *    screen and dropped the player into the game as someone he never chose to
 *    be. Opening the profile did the same thing by a different route.
 *
 * Neither showed up as an error. Both looked like the app deciding on its own
 * that the player was a guest, which is exactly how they were reported. The
 * cheapest guard is structural: ensureSession may be CALLED only where the
 * player is being asked, which is the auth feature.
 */
class SessionOwnershipTest {

    private val source = File("src/main/java/com/dreamxi/app")

    private fun kotlinFiles(): List<File> =
        source.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** A call, not a mention: comments and the declaration itself do not count. */
    private fun callsEnsureSession(file: File): Boolean =
        file.readLines().any { line ->
            val code = line.substringBefore("//")
            "ensureSession(" in code && "fun ensureSession" !in code
        }

    @Test
    fun `only the auth feature may create a session`() {
        assertTrue("source tree not found at ${source.absolutePath}", source.isDirectory)
        val callers = kotlinFiles()
            .filter { callsEnsureSession(it) }
            .map { it.relativeTo(source).path.replace('\\', '/') }
            .sorted()
        println("ensureSession callers: $callers")
        assertEquals(
            "Creating a session anywhere else means the app decides the player is a " +
                "guest without being asked. Ask on the auth screen instead.",
            // SessionRepository only DECLARES it; the auth view model is the one
            // place that asks for it, on the player's own instruction.
            listOf("feature/auth/AuthViewModel.kt"),
            callers,
        )
    }

    /**
     * Signing out must leave the device with nothing. The moment it takes a new
     * session, the next launch has one, skips the front door, and the player is
     * a guest again.
     */
    @Test
    fun `signing out does not take a new session`() {
        val repository = File(source, "data/auth/SessionRepository.kt").readText()
        val body = repository.substringAfter("suspend fun signOut()").substringBefore("\n    }")
        assertTrue("signOut must call auth.signOut()", "signOut()" in body)
        assertTrue("signOut must not create a session: $body", "ensureSession" !in body)
    }
}
