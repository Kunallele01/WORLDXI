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
    fun `european cup names reach the league spellings the files use`() {
        assertEquals("manchester_utd", crestSlug("Manchester United"))
        assertEquals("newcastle", crestSlug("Newcastle United"))
        assertEquals("paris_saint_germain", crestSlug("Paris Saint-Germain"))
    }

    @Test
    fun `every european cup club has a bundled crest`() {
        // Each club of the loaded European Cup season, read from the strength
        // file the loader uses, must resolve to a file. Skipped where the ETL
        // output is absent (a fresh clone does not carry it).
        val strengths = File("../etl/ec/ec_strength_2023.tsv")
        val dir = File("src/main/res/drawable-nodpi")
        if (!strengths.isFile || !dir.isDirectory) return
        val clubs = strengths.readLines().drop(1).map { it.split("\t")[1] }
        assertEquals(32, clubs.size)
        val missing = clubs.filter { !File(dir, "crest_${crestSlug(it)}.png").isFile }
        assertTrue("clubs with no crest file: $missing", missing.isEmpty())
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
