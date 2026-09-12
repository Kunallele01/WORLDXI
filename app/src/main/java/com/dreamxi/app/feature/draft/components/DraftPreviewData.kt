package com.dreamxi.app.feature.draft.components

import com.dreamxi.app.feature.draft.DraftUiState
import com.dreamxi.app.feature.draft.formationById
import com.dreamxi.app.feature.draft.DraftedPlayer
import com.dreamxi.app.feature.draft.SpinResult
import com.dreamxi.app.feature.draft.SquadPlayer
import com.dreamxi.app.ui.theme.PlayerPosition

/**
 * Preview fixtures, shared by every @Preview in the draft feature.
 *
 * Real numbers from the loaded dataset rather than invented ones — previews
 * are the fastest way to catch a layout that only works for tidy values, and
 * ratings, minutes and squad quartiles all have real distributions that
 * made-up data flatters (see the rating-scale calibration in Color.kt for
 * what happens when a visual is tuned against imagined values).
 */
internal val PreviewSquad = listOf(
    SquadPlayer(1, 101, "Thibaut Courtois", "GK", PlayerPosition.GOALKEEPER, 89, 2970, 0, 0,
        appearances = 33, savePct = 77.0, cleanSheets = 15, nationality = "Belgium"),
    SquadPlayer(2, 102, "Éder Militão", "CB", PlayerPosition.DEFENDER, 84, 2612, 2, 1,
        appearances = 31, tackles = 48),
    SquadPlayer(3, 103, "David Alaba", "CB", PlayerPosition.DEFENDER, 84, 2438, 3, 2, nationality = "Austria",
        positionRatings = mapOf("cb" to 84, "fb" to 84, "dm" to 84, "cm" to 82, "cam" to 80, "winger" to 79, "st" to 77)),
    SquadPlayer(4, 104, "Daniel Carvajal", "FB", PlayerPosition.DEFENDER, 82, 1908, 1, 4,
        appearances = 24, tackles = 42, nationality = "Spain", side = "R",
        positionRatings = mapOf("cb" to 79, "fb" to 82, "dm" to 80, "cm" to 78, "cam" to 76, "winger" to 76, "st" to 71)),
    SquadPlayer(5, 105, "Ferland Mendy", "FB", PlayerPosition.DEFENDER, 80, 1657, 0, 1),
    SquadPlayer(6, 106, "Casemiro", "DM", PlayerPosition.MIDFIELDER, 89, 2506, 4, 2,
        appearances = 31, tackles = 62, yellowCards = 11, nationality = "Brazil",
        positionRatings = mapOf("cb" to 88, "fb" to 83, "dm" to 88, "cm" to 83, "cam" to 77, "winger" to 73, "st" to 77)),
    SquadPlayer(7, 107, "Luka Modrić", "CM", PlayerPosition.MIDFIELDER, 87, 2412, 3, 8),
    SquadPlayer(8, 108, "Toni Kroos", "CM", PlayerPosition.MIDFIELDER, 88, 2280, 1, 5),
    SquadPlayer(9, 109, "Federico Valverde", "CM", PlayerPosition.MIDFIELDER, 82, 1544, 2, 3),
    SquadPlayer(10, 110, "Vinícius Júnior", "Winger", PlayerPosition.FORWARD, 86, 2932, 17, 10,
        appearances = 35, shots = 96, shotsOnTarget = 44, tackles = 28, nationality = "Brazil", side = "L",
        positionRatings = mapOf("cb" to 46, "fb" to 57, "dm" to 57, "cm" to 76, "cam" to 84, "winger" to 86, "st" to 80)),
    SquadPlayer(11, 111, "Rodrygo", "Winger", PlayerPosition.FORWARD, 79, 1401, 7, 3,
        nationality = "Brazil", side = "R",
        positionRatings = mapOf("cb" to 48, "fb" to 58, "dm" to 58, "cm" to 74,
            "cam" to 78, "winger" to 79, "st" to 76)),
    SquadPlayer(12, 112, "Marco Asensio", "Winger", PlayerPosition.FORWARD, 81, 1265, 10, 2),
    SquadPlayer(13, 113, "Karim Benzema", "ST", PlayerPosition.FORWARD, 91, 2711, 27, 12,
        appearances = 32, shots = 110, shotsOnTarget = 58, tackles = 12, nationality = "France",
        positionRatings = mapOf("cb" to 55, "fb" to 60, "dm" to 64, "cm" to 81, "cam" to 88, "winger" to 87, "st" to 89)),
    SquadPlayer(14, 114, "Mariano Díaz", "ST", PlayerPosition.FORWARD, 74, 168, 1, 0, appearances = 6),
    SquadPlayer(15, 115, "Nacho Fernández", "CB", PlayerPosition.DEFENDER, 79, 1183, 1, 0),
    SquadPlayer(16, 116, "Eden Hazard", "Winger", PlayerPosition.FORWARD, 83, 617, 1, 1, alreadyDrafted = true),
    SquadPlayer(17, 117, "Lucas Vázquez", "FB", PlayerPosition.DEFENDER, 78, 1560, 2, 5),
    SquadPlayer(18, 118, "Eduardo Camavinga", "CM", PlayerPosition.MIDFIELDER, 77, 1173, 1, 2),
)

internal val PreviewSpin = SpinResult(
    clubSeasonId = 42,
    clubName = "Real Madrid",
    seasonLabel = "2021/22",
    finalPosition = 1,
    squadStrength = 84.93,
    strengthQuartile = 4,
    players = PreviewSquad,
)

private val PreviewFormation = formationById("4-3-3")

private fun slot(id: String) = PreviewFormation.slots.first { it.id == id }

/** Picks keyed by slot id, matching how DraftUiState stores them. */
internal val PreviewPitchPicks: Map<String, DraftedPlayer> = mapOf(
    "gk" to DraftedPlayer(slot("gk"), PreviewSquad[0], "Real Madrid", "2021/22"),
    "lcb" to DraftedPlayer(slot("lcb"), PreviewSquad[1], "Real Madrid", "2021/22"),
    "rcb" to DraftedPlayer(slot("rcb"), PreviewSquad[2], "Real Madrid", "2021/22"),
    "dm" to DraftedPlayer(slot("dm"), PreviewSquad[5], "Real Madrid", "2021/22"),
    // A striker parked at right-back — the case the fit colours exist for.
    "rb" to DraftedPlayer(
        slot("rb"),
        SquadPlayer(
            90, 190, "Harry Kane", "ST", PlayerPosition.FORWARD, 90, 3283, 23, 14,
            appearances = 37, nationality = "England",
            positionRatings = mapOf(
                "cb" to 62, "fb" to 65, "dm" to 67, "cm" to 79,
                "cam" to 86, "winger" to 85, "st" to 89,
            ),
        ),
        "Tottenham",
        "2020/21",
    ),
)

internal val PreviewState = DraftUiState(
    leagueName = "La Liga",
    formation = PreviewFormation,
    round = 6,
    picks = PreviewPitchPicks,
    spin = PreviewSpin,
    rerollsAllowed = 2,
    rerollsUsed = 1,
)

/**
 * A finished XI, for the completion screen preview.
 *
 * Deliberately messy: eleven players from six different club-seasons, with two
 * genuinely out of position (a striker at right-back, a winger on the wrong
 * flank). A tidy all-natural XI would make the completion screen look right
 * while hiding every case it exists to communicate.
 */
private fun pick(slotId: String, player: SquadPlayer, club: String, season: String) =
    DraftedPlayer(slot(slotId), player, club, season)

private fun forward(id: Long, name: String, ovr: Int, side: String? = null, nat: String = "Brazil") =
    SquadPlayer(
        id, id, name, "Winger", PlayerPosition.FORWARD, ovr, 2400, 12, 8,
        appearances = 30, nationality = nat, side = side,
        positionRatings = mapOf(
            "cb" to ovr - 38, "fb" to ovr - 28, "dm" to ovr - 28, "cm" to ovr - 10,
            "cam" to ovr - 2, "winger" to ovr, "st" to ovr - 4,
        ),
    )

private fun midfielder(id: Long, name: String, ovr: Int, role: String, nat: String) =
    SquadPlayer(
        id, id, name, role, PlayerPosition.MIDFIELDER, ovr, 2500, 4, 6,
        appearances = 31, nationality = nat,
        positionRatings = mapOf(
            "cb" to ovr - 12, "fb" to ovr - 8, "dm" to ovr - 1, "cm" to ovr,
            "cam" to ovr - 3, "winger" to ovr - 6, "st" to ovr - 9,
        ),
    )

private fun defender(id: Long, name: String, ovr: Int, role: String, nat: String, side: String? = null) =
    SquadPlayer(
        id, id, name, role, PlayerPosition.DEFENDER, ovr, 2600, 2, 2,
        appearances = 31, nationality = nat, side = side,
        positionRatings = mapOf(
            "cb" to ovr, "fb" to ovr - 3, "dm" to ovr - 4, "cm" to ovr - 12,
            "cam" to ovr - 18, "winger" to ovr - 20, "st" to ovr - 19,
        ),
    )

internal val PreviewCompletePicks: Map<String, DraftedPlayer> = mapOf(
    "gk" to pick("gk", PreviewSquad[0], "Real Madrid", "2021/22"),
    "lb" to pick("lb", defender(201, "Andrew Robertson", 87, "FB", "Scotland", "L"), "Liverpool", "2019/20"),
    "lcb" to pick("lcb", defender(202, "Virgil van Dijk", 90, "CB", "Netherlands"), "Liverpool", "2019/20"),
    "rcb" to pick("rcb", PreviewSquad[1], "Real Madrid", "2021/22"),
    // A right-back slot filled by a LEFT-sided full-back: the wrong-flank case.
    "rb" to pick("rb", defender(203, "Alphonso Davies", 84, "FB", "Canada", "L"), "Bayern", "2020/21"),
    "dm" to pick("dm", PreviewSquad[5], "Real Madrid", "2021/22"),
    "lcm" to pick("lcm", midfielder(204, "Kevin De Bruyne", 91, "CM", "Belgium"), "Manchester City", "2022/23"),
    "rcm" to pick("rcm", PreviewSquad[6], "Real Madrid", "2021/22"),
    "lw" to pick("lw", PreviewSquad[9], "Real Madrid", "2021/22"),
    // A LEFT winger on the RIGHT wing: the case that started this.
    "rw" to pick("rw", forward(205, "Sadio Mané", 88, "L", "Senegal"), "Liverpool", "2019/20"),
    "st" to pick("st", PreviewSquad[12], "Real Madrid", "2021/22"),
)

internal val PreviewCompleteState = DraftUiState(
    leagueName = "La Liga",
    formation = PreviewFormation,
    round = 11,
    picks = PreviewCompletePicks,
    rerollsAllowed = 2,
    rerollsUsed = 2,
)
