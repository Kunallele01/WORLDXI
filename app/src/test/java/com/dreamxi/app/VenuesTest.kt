package com.dreamxi.app

import com.dreamxi.app.sim.STADIUMS
import com.dreamxi.app.sim.Venue
import com.dreamxi.app.sim.Venues
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Where a match is played: the home side's ground, in the city it stands in. */
class VenuesTest {

    @Test
    fun the_users_xi_plays_at_dream_xi_stadium_in_london_for_the_premier_league() {
        assertEquals(
            Venue("Dream XI Stadium", "London"),
            Venues.forHost(true, "Dream XI", "Premier League", "2018/19"),
        )
    }

    @Test
    fun the_users_xi_plays_at_dream_xi_stadium_in_madrid_for_la_liga() {
        assertEquals(
            Venue("Dream XI Stadium", "Madrid"),
            Venues.forHost(true, "Dream XI", "La Liga", "2018/19"),
        )
    }

    @Test
    fun a_ground_that_changed_is_right_for_each_season() {
        // The reason this is per club per season and not per club.
        assertEquals("Boleyn Ground", Venues.forHost(false, "West Ham", "Premier League", "2015/16")?.stadium)
        assertEquals("London Stadium", Venues.forHost(false, "West Ham", "Premier League", "2016/17")?.stadium)
        assertEquals("Wembley Stadium", Venues.forHost(false, "Tottenham", "Premier League", "2017/18")?.stadium)
        assertEquals("Vicente Calderón", Venues.forHost(false, "Atlético Madrid", "La Liga", "2016/17")?.stadium?.removePrefix("Estadio "))
        assertEquals("Wanda Metropolitano", Venues.forHost(false, "Atlético Madrid", "La Liga", "2017/18")?.stadium)
    }

    @Test
    fun the_same_club_is_keyed_by_league_so_a_name_cannot_cross_over() {
        assertNull(Venues.forHost(false, "Arsenal", "La Liga", "2018/19"))
    }

    @Test
    fun a_club_with_no_record_gives_null_not_a_guess() {
        assertNull(Venues.forHost(false, "Nobody FC", "Premier League", "2018/19"))
    }

    @Test
    fun every_entry_has_a_stadium_and_a_city_and_the_whole_database_is_covered() {
        assertEquals("2 leagues x 15 seasons x 20 clubs", 600, STADIUMS.size)
        for ((key, v) in STADIUMS) {
            assertFalse("$key has no stadium", v.stadium.isBlank())
            assertFalse("$key has no city", v.city.isBlank())
            assertFalse("$key has markup left in it: $v", Regex("[\\[\\]{}|<>=]").containsMatchIn(v.stadium + v.city))
        }
        assertNotNull(STADIUMS["Premier League|2023/24|Arsenal"])
    }
}
