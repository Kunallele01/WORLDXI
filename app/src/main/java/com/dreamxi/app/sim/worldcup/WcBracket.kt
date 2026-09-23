package com.dreamxi.app.sim.worldcup

/** Where one side of a knockout tie comes from. */
sealed interface SlotSource {
    /** The team finishing [position] in group [letter]. */
    data class Group(val letter: String, val position: Int) : SlotSource

    /**
     * 2026 only: the third-placed team FIFA's Annex C assigns to the winner of
     * group [winnerGroup], which depends on which eight groups' thirds qualified.
     */
    data class ThirdAgainst(val winnerGroup: String) : SlotSource

    data class WinnerOf(val slotId: String) : SlotSource
    data class LoserOf(val slotId: String) : SlotSource
}

/**
 * One tie in a knockout bracket.
 *
 * Brackets are GENERATED (see [WcBrackets]) and listed in bracket order: every
 * slot appears after the slots it draws from, so a single pass resolves the lot.
 */
data class BracketSlot(
    val id: String,
    val round: String,
    val home: SlotSource,
    val away: SlotSource,
)
