package com.dreamxi.app.sim

/** Where a match was played: the ground and the city it stands in. */
data class Venue(val stadium: String, val city: String)

/**
 * The ground a fixture is played at: the HOME side's.
 *
 * Real clubs come from [STADIUMS], which is per club per season because grounds
 * change (West Ham, Tottenham and Atletico all moved during the years covered).
 * The user's XI plays at its own invented ground, in the capital of whichever
 * league the run is in.
 *
 * Null when a club has no entry, so the screen can fall back to plain
 * HOME/AWAY rather than print a guess.
 */
object Venues {
    const val DREAM_XI_STADIUM = "Dream XI Stadium"

    fun of(fixture: SimFixture, leagueName: String, seasonLabel: String): Venue? =
        forHost(fixture.home.isUserTeam, fixture.home.name, leagueName, seasonLabel)

    fun forHost(hostIsUser: Boolean, hostName: String, leagueName: String, seasonLabel: String): Venue? =
        if (hostIsUser) {
            Venue(DREAM_XI_STADIUM, if (leagueName == "La Liga") "Madrid" else "London")
        } else {
            STADIUMS["$leagueName|$seasonLabel|$hostName"]
        }
}
