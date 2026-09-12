package com.dreamxi.app

import com.dreamxi.app.core.ui.components.crestSlug
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Crest lookup is by generated name, so a slug that drifts from the asset
 * filenames fails silently: every club simply falls back to its generated
 * badge and nothing errors. These pin the two ways that can happen.
 */
class CrestSlugTest {

    @Test
    fun `accents are folded to ascii, not replaced`() {
        // The bug this exists for. Kotlin's lowercase() leaves an accented
        // character intact, and the Android resource charset does not allow
        // it, so the naive slug turned "Alaves" into "alav_s" and never found
        // "crest_alaves.png". Five loaded clubs are affected.
        assertEquals("alaves", crestSlug("Alavés"))
        assertEquals("almeria", crestSlug("Almería"))
        assertEquals("atletico_madrid", crestSlug("Atlético Madrid"))
        assertEquals("cadiz", crestSlug("Cádiz"))
        assertEquals("leganes", crestSlug("Leganés"))
    }

    @Test
    fun `slugs are valid android resource names`() {
        val valid = Regex("[a-z][a-z0-9_]*")
        listOf(
            "Real Madrid", "Nott'ham Forest", "Brighton & Hove Albion",
            "Sheffield Utd", "Cádiz", "Manchester City",
        ).forEach {
            val slug = crestSlug(it)
            assertTrue("'$it' -> '$slug' is not a legal resource name", valid.matches(slug))
        }
    }

    @Test
    fun `every bundled crest is reachable by some slug`() {
        // Guards the other direction: a file whose name no slug can produce is
        // dead weight that looks like working coverage.
        val dir = File("src/main/res/drawable-nodpi")
        if (!dir.isDirectory) return
        val crests = dir.listFiles { f -> f.name.startsWith("crest_") }.orEmpty()
        assertTrue("expected the bundled crests to be present", crests.size >= 50)
        crests.forEach {
            val stem = it.nameWithoutExtension.removePrefix("crest_")
            assertEquals("$stem is not a slug of itself", stem, crestSlug(stem))
        }
    }
}
