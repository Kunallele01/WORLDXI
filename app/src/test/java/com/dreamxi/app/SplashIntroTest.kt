package com.dreamxi.app

import com.dreamxi.app.core.ui.NationFlags
import com.dreamxi.app.feature.splash.DRAW_COLUMNS
import com.dreamxi.app.feature.splash.DRAW_NATIONS
import com.dreamxi.app.feature.splash.DRAW_ROWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The flags intro, which is the first thing anyone sees.
 *
 * It fills a fixed grid from a list of names looked up in the GENERATED flag
 * map, so a rename in etl/wc_flags.py — the kind that already happened once,
 * "Czech Republic" becoming "Czechia" between editions — would quietly drop a
 * name and leave the grid short. The screen survives that (it wraps), but the
 * draw would repeat a flag, which looks like a bug. This is the check that
 * catches the rename instead.
 */
class SplashIntroTest {

    @Test
    fun `every nation in the splash draw has a flag`() {
        val missing = DRAW_NATIONS.filterNot { it in NationFlags }
        assertEquals("names the flag map no longer has", emptyList<String>(), missing)
    }

    @Test
    fun `the draw has more nations than the grid and never repeats a flag`() {
        val flags = DRAW_NATIONS.mapNotNull { NationFlags[it] }
        // Several names share a flag on purpose ("USA" / "United States"); the
        // draw list must carry one spelling each, or a shuffle can deal the same
        // flag into two cells.
        assertEquals("two spellings of one nation in the draw", flags.size, flags.toSet().size)
        assertTrue(
            "the draw list must fill the ${DRAW_COLUMNS}x$DRAW_ROWS grid with room to vary",
            flags.size > DRAW_COLUMNS * DRAW_ROWS,
        )
    }
}
