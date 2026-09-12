package com.dreamxi.app.feature.draft

import com.dreamxi.app.ui.theme.PlayerPosition

/**
 * A position in a formation: which role it wants, what to call it, and where
 * it sits on the pitch.
 *
 * [x] runs 0 (left touchline) to 1 (right touchline); [y] runs 0 (the
 * opponent's goal line) to 1 (your own). Normalised rather than in dp so the
 * same numbers drive the full-size pitch, the placement sheet and any later
 * thumbnail, instead of three sets of coordinates drifting apart.
 *
 * [role] is one of the ETL's eight per-season roles, which is what decides a
 * player's natural rating here. [label] is the football name for the position,
 * which is finer-grained: LB and RB are both the FB role, LW/RW/LM/RM are all
 * Winger, LWB/RWB are fullbacks pushed up. The distinction matters because the
 * label is what a manager recognises while the role is what ratings key on.
 */
data class FormationSlot(
    val id: String,
    val role: String,
    val label: String,
    val x: Float,
    val y: Float,
) {
    val gridKey: String? get() = gridKeyForRole(role)
    val isGoalkeeper: Boolean get() = role == "GK"

    /**
     * Which flank this position is on: "L", "R", or null for central ones.
     *
     * Derived from the label, and only for the two roles where we actually
     * have a player's side recorded (FB and Winger). Central positions get
     * null rather than a guess — LCM and RCM in the diamond are a drawing
     * convenience, not two different jobs, and charging someone for being on
     * the "wrong" side of a central pairing would be nonsense.
     */
    val side: String?
        get() = when {
            role != "FB" && role != "Winger" -> null
            label.startsWith("L") -> "L"
            label.startsWith("R") -> "R"
            else -> null
        }
    val group: PlayerPosition
        get() = when (role) {
            "GK" -> PlayerPosition.GOALKEEPER
            "CB", "FB" -> PlayerPosition.DEFENDER
            "DM", "CM", "CAM" -> PlayerPosition.MIDFIELDER
            else -> PlayerPosition.FORWARD
        }
}

/**
 * A shape to draft into. Chosen at Setup and fixed for the whole run.
 *
 * Fixed on purpose: the draft's central tension is committing to a shape and
 * then solving whatever the spins hand you, and a formation you could swap
 * mid-run would dissolve that. It also keeps every pick's out-of-position cost
 * stable — changing shape later would silently re-price players already
 * drafted, so an XI you were happy with could get worse while you watched.
 */
data class Formation(
    val id: String,
    val name: String,
    val description: String,
    val slots: List<FormationSlot>,
) {
    /**
     * The digits of the name: 3-5-2 -> [3, 5, 2]. These describe LINES on the
     * pitch, which is NOT the same as counting position groups, and the
     * difference bites in both directions:
     *   - 3-5-2's wing-backs are the FB role and so sit in the DEFENDER group,
     *     which would make it "5 at the back" when every manager alive calls
     *     it a back three;
     *   - 4-2-3-1's wide players are the Winger role and so sit in the FORWARD
     *     group, which would make it a front three rather than a lone striker.
     * So the name is authoritative for anything the user reads, and the groups
     * stay what they are for: colouring and rating lookups.
     */
    val lines: List<Int> get() = id.split("-").mapNotNull(String::toIntOrNull)

    val defenderCount: Int get() = lines.firstOrNull() ?: 4

    init {
        // Cheap invariants, checked at construction rather than trusted. A
        // formation with ten slots or two keepers would not fail loudly at
        // runtime — it would quietly produce a draft that cannot complete.
        require(slots.size == 11) { "$name has ${slots.size} slots, expected 11" }
        require(slots.count { it.isGoalkeeper } == 1) { "$name must have exactly one goalkeeper" }
        require(slots.map { it.id }.toSet().size == 11) { "$name has duplicate slot ids" }
    }
}

// Widest usable x for a position. Tokens are drawn about 0.19 of the pitch
// wide and are CENTRED on their coordinate, so a centre below ~0.105 hangs off
// the touchline. Wing-backs sat at 0.07 and were visibly clipped.
private const val X_WIDE_LEFT = 0.105f
private const val X_WIDE_RIGHT = 0.895f

// --- shared pitch bands ------------------------------------------------------
// So that a back four sits at the same depth in every formation that has one.
// Without shared constants the shapes drift a few percent apart and switching
// between them looks subtly broken.
private const val Y_GK = 0.93f
private const val Y_BACK = 0.775f
private const val Y_WINGBACK = 0.60f
private const val Y_HOLDING = 0.615f
private const val Y_MID = 0.475f
private const val Y_ATTACK_MID = 0.315f
private const val Y_WIDE_FWD = 0.195f
private const val Y_FWD = 0.13f

private fun gk() = FormationSlot("gk", "GK", "GK", 0.5f, Y_GK)

private fun backFour() = listOf(
    FormationSlot("lb", "FB", "LB", 0.11f, Y_BACK - 0.03f),
    FormationSlot("lcb", "CB", "CB", 0.37f, Y_BACK),
    FormationSlot("rcb", "CB", "CB", 0.63f, Y_BACK),
    FormationSlot("rb", "FB", "RB", 0.89f, Y_BACK - 0.03f),
)

private fun backThree() = listOf(
    FormationSlot("lcb", "CB", "CB", 0.26f, Y_BACK),
    FormationSlot("ccb", "CB", "CB", 0.5f, Y_BACK + 0.025f),
    FormationSlot("rcb", "CB", "CB", 0.74f, Y_BACK),
)

private fun backFive() = listOf(
    FormationSlot("lwb", "FB", "LWB", 0.105f, Y_BACK - 0.08f),
    FormationSlot("lcb", "CB", "CB", 0.29f, Y_BACK + 0.02f),
    FormationSlot("ccb", "CB", "CB", 0.5f, Y_BACK + 0.04f),
    FormationSlot("rcb", "CB", "CB", 0.71f, Y_BACK + 0.02f),
    FormationSlot("rwb", "FB", "RWB", 0.895f, Y_BACK - 0.08f),
)

/**
 * Every formation in the picker, grouped by how many defenders it uses.
 *
 * The full range real managers field, not a tidy subset. Breadth costs almost
 * nothing here — each shape is eleven lines of coordinates — while genuinely
 * changing what you hunt for during the draft: 3-5-2 needs three centre-backs
 * and two wing-backs, 4-2-4 needs four forwards, and the diamond needs no
 * wingers at all, which makes a winger-heavy spin run far less useful.
 */
val Formations: List<Formation> = listOf(
    // ---------------------------------------------------------- four defenders
    Formation(
        "4-3-3", "4-3-3", "Two wingers and a lone striker, anchored by a holding midfielder.",
        listOf(gk()) + backFour() + listOf(
            FormationSlot("dm", "DM", "DM", 0.5f, Y_HOLDING - 0.03f),
            FormationSlot("lcm", "CM", "CM", 0.28f, Y_MID),
            FormationSlot("rcm", "CM", "CM", 0.72f, Y_MID),
            FormationSlot("lw", "Winger", "LW", 0.13f, Y_WIDE_FWD),
            FormationSlot("st", "ST", "ST", 0.5f, Y_FWD),
            FormationSlot("rw", "Winger", "RW", 0.87f, Y_WIDE_FWD),
        ),
    ),
    Formation(
        "4-2-3-1", "4-2-3-1", "A double pivot behind a creative ten. The modern default.",
        listOf(gk()) + backFour() + listOf(
            FormationSlot("ldm", "DM", "DM", 0.33f, Y_HOLDING),
            FormationSlot("rdm", "DM", "DM", 0.67f, Y_HOLDING),
            FormationSlot("lam", "Winger", "LM", 0.13f, Y_ATTACK_MID),
            FormationSlot("cam", "CAM", "CAM", 0.5f, Y_ATTACK_MID),
            FormationSlot("ram", "Winger", "RM", 0.87f, Y_ATTACK_MID),
            FormationSlot("st", "ST", "ST", 0.5f, Y_FWD),
        ),
    ),
    Formation(
        "4-4-2", "4-4-2", "Two banks of four and a strike partnership.",
        listOf(gk()) + backFour() + listOf(
            FormationSlot("lm", "Winger", "LM", 0.11f, Y_MID + 0.03f),
            FormationSlot("lcm", "CM", "CM", 0.37f, Y_MID + 0.05f),
            FormationSlot("rcm", "CM", "CM", 0.63f, Y_MID + 0.05f),
            FormationSlot("rm", "Winger", "RM", 0.89f, Y_MID + 0.03f),
            FormationSlot("lst", "ST", "ST", 0.36f, Y_FWD + 0.03f),
            FormationSlot("rst", "ST", "ST", 0.64f, Y_FWD + 0.03f),
        ),
    ),
    Formation(
        "4-4-1-1", "4-4-1-1", "A 4-4-2 with one striker dropping into the hole.",
        listOf(gk()) + backFour() + listOf(
            FormationSlot("lm", "Winger", "LM", 0.11f, Y_MID + 0.05f),
            FormationSlot("lcm", "CM", "CM", 0.37f, Y_MID + 0.105f),
            FormationSlot("rcm", "CM", "CM", 0.63f, Y_MID + 0.105f),
            FormationSlot("rm", "Winger", "RM", 0.89f, Y_MID + 0.05f),
            FormationSlot("cf", "CAM", "CF", 0.5f, Y_ATTACK_MID - 0.01f),
            FormationSlot("st", "ST", "ST", 0.5f, Y_FWD),
        ),
    ),
    Formation(
        "4-1-4-1", "4-1-4-1", "A single screen in front of the defence, four across midfield.",
        listOf(gk()) + backFour() + listOf(
            FormationSlot("dm", "DM", "DM", 0.5f, Y_HOLDING + 0.01f),
            FormationSlot("lm", "Winger", "LM", 0.11f, Y_MID - 0.03f),
            FormationSlot("lcm", "CM", "CM", 0.37f, Y_MID - 0.03f),
            FormationSlot("rcm", "CM", "CM", 0.63f, Y_MID - 0.03f),
            FormationSlot("rm", "Winger", "RM", 0.89f, Y_MID - 0.03f),
            FormationSlot("st", "ST", "ST", 0.5f, Y_FWD),
        ),
    ),
    Formation(
        "4-1-2-1-2", "4-1-2-1-2 Diamond", "A midfield diamond and two strikers. No wingers at all.",
        listOf(gk()) + backFour() + listOf(
            FormationSlot("dm", "DM", "DM", 0.5f, Y_HOLDING + 0.01f),
            FormationSlot("lcm", "CM", "LCM", 0.22f, Y_MID),
            FormationSlot("rcm", "CM", "RCM", 0.78f, Y_MID),
            FormationSlot("cam", "CAM", "CAM", 0.5f, Y_ATTACK_MID),
            FormationSlot("lst", "ST", "ST", 0.36f, Y_FWD),
            FormationSlot("rst", "ST", "ST", 0.64f, Y_FWD),
        ),
    ),
    Formation(
        "4-3-2-1", "4-3-2-1 Christmas Tree", "Three midfielders, two in the hole, one striker.",
        listOf(gk()) + backFour() + listOf(
            FormationSlot("dm", "DM", "DM", 0.5f, Y_HOLDING),
            FormationSlot("lcm", "CM", "CM", 0.26f, Y_MID + 0.03f),
            FormationSlot("rcm", "CM", "CM", 0.74f, Y_MID + 0.03f),
            FormationSlot("lam", "CAM", "CAM", 0.34f, Y_ATTACK_MID),
            FormationSlot("ram", "CAM", "CAM", 0.66f, Y_ATTACK_MID),
            FormationSlot("st", "ST", "ST", 0.5f, Y_FWD),
        ),
    ),
    Formation(
        "4-2-2-2", "4-2-2-2", "A double pivot, two inside forwards and a front two.",
        listOf(gk()) + backFour() + listOf(
            FormationSlot("ldm", "DM", "DM", 0.33f, Y_HOLDING),
            FormationSlot("rdm", "DM", "DM", 0.67f, Y_HOLDING),
            FormationSlot("lam", "CAM", "CAM", 0.21f, Y_ATTACK_MID + 0.03f),
            FormationSlot("ram", "CAM", "CAM", 0.79f, Y_ATTACK_MID + 0.03f),
            FormationSlot("lst", "ST", "ST", 0.36f, Y_FWD + 0.02f),
            FormationSlot("rst", "ST", "ST", 0.64f, Y_FWD + 0.02f),
        ),
    ),
    Formation(
        "4-5-1", "4-5-1", "Five across midfield behind a lone striker. Hard to break down.",
        listOf(gk()) + backFour() + listOf(
            FormationSlot("lm", "Winger", "LM", 0.105f, Y_MID + 0.03f),
            FormationSlot("lcm", "CM", "CM", 0.30f, Y_MID + 0.05f),
            FormationSlot("ccm", "CM", "CM", 0.5f, Y_MID + 0.08f),
            FormationSlot("rcm", "CM", "CM", 0.70f, Y_MID + 0.05f),
            FormationSlot("rm", "Winger", "RM", 0.895f, Y_MID + 0.03f),
            FormationSlot("st", "ST", "ST", 0.5f, Y_FWD + 0.02f),
        ),
    ),
    Formation(
        "4-2-4", "4-2-4", "Only two in midfield and four forwards. All-out attack.",
        listOf(gk()) + backFour() + listOf(
            FormationSlot("lcm", "CM", "CM", 0.34f, Y_MID + 0.05f),
            FormationSlot("rcm", "CM", "CM", 0.66f, Y_MID + 0.05f),
            FormationSlot("lw", "Winger", "LW", 0.12f, Y_WIDE_FWD + 0.03f),
            FormationSlot("lst", "ST", "ST", 0.38f, Y_FWD),
            FormationSlot("rst", "ST", "ST", 0.62f, Y_FWD),
            FormationSlot("rw", "Winger", "RW", 0.88f, Y_WIDE_FWD + 0.03f),
        ),
    ),
    // --------------------------------------------------------- three defenders
    Formation(
        "3-5-2", "3-5-2", "Three centre-backs, wing-backs providing all the width.",
        listOf(gk()) + backThree() + listOf(
            FormationSlot("lwb", "FB", "LWB", 0.105f, Y_WINGBACK - 0.06f),
            FormationSlot("lcm", "CM", "CM", 0.32f, Y_MID + 0.04f),
            FormationSlot("ccm", "CM", "CM", 0.5f, Y_MID + 0.08f),
            FormationSlot("rcm", "CM", "CM", 0.68f, Y_MID + 0.04f),
            FormationSlot("rwb", "FB", "RWB", 0.895f, Y_WINGBACK - 0.06f),
            FormationSlot("lst", "ST", "ST", 0.36f, Y_FWD + 0.02f),
            FormationSlot("rst", "ST", "ST", 0.64f, Y_FWD + 0.02f),
        ),
    ),
    Formation(
        "3-4-3", "3-4-3", "A front three with only four in midfield. Aggressive.",
        listOf(gk()) + backThree() + listOf(
            FormationSlot("lm", "Winger", "LM", 0.105f, Y_MID + 0.03f),
            FormationSlot("lcm", "CM", "CM", 0.37f, Y_MID + 0.05f),
            FormationSlot("rcm", "CM", "CM", 0.63f, Y_MID + 0.05f),
            FormationSlot("rm", "Winger", "RM", 0.895f, Y_MID + 0.03f),
            FormationSlot("lw", "Winger", "LW", 0.14f, Y_WIDE_FWD),
            FormationSlot("st", "ST", "ST", 0.5f, Y_FWD),
            FormationSlot("rw", "Winger", "RW", 0.86f, Y_WIDE_FWD),
        ),
    ),
    Formation(
        "3-4-2-1", "3-4-2-1", "Two free eights behind a lone striker, wing-backs wide.",
        listOf(gk()) + backThree() + listOf(
            FormationSlot("lwb", "FB", "LWB", 0.105f, Y_WINGBACK - 0.05f),
            FormationSlot("lcm", "CM", "CM", 0.36f, Y_MID + 0.06f),
            FormationSlot("rcm", "CM", "CM", 0.64f, Y_MID + 0.06f),
            FormationSlot("rwb", "FB", "RWB", 0.895f, Y_WINGBACK - 0.05f),
            FormationSlot("lam", "CAM", "CAM", 0.33f, Y_ATTACK_MID),
            FormationSlot("ram", "CAM", "CAM", 0.67f, Y_ATTACK_MID),
            FormationSlot("st", "ST", "ST", 0.5f, Y_FWD),
        ),
    ),
    Formation(
        "3-4-1-2", "3-4-1-2", "A single ten feeding two strikers.",
        listOf(gk()) + backThree() + listOf(
            FormationSlot("lwb", "FB", "LWB", 0.105f, Y_WINGBACK - 0.05f),
            FormationSlot("lcm", "CM", "CM", 0.36f, Y_MID + 0.06f),
            FormationSlot("rcm", "CM", "CM", 0.64f, Y_MID + 0.06f),
            FormationSlot("rwb", "FB", "RWB", 0.895f, Y_WINGBACK - 0.05f),
            FormationSlot("cam", "CAM", "CAM", 0.5f, Y_ATTACK_MID),
            FormationSlot("lst", "ST", "ST", 0.36f, Y_FWD),
            FormationSlot("rst", "ST", "ST", 0.64f, Y_FWD),
        ),
    ),
    Formation(
        "3-1-4-2", "3-1-4-2", "A dedicated screen in front of a back three.",
        listOf(gk()) + backThree() + listOf(
            FormationSlot("dm", "DM", "DM", 0.5f, Y_HOLDING + 0.03f),
            FormationSlot("lm", "Winger", "LM", 0.105f, Y_MID - 0.02f),
            FormationSlot("lcm", "CM", "CM", 0.37f, Y_MID - 0.01f),
            FormationSlot("rcm", "CM", "CM", 0.63f, Y_MID - 0.01f),
            FormationSlot("rm", "Winger", "RM", 0.895f, Y_MID - 0.02f),
            FormationSlot("lst", "ST", "ST", 0.36f, Y_FWD + 0.02f),
            FormationSlot("rst", "ST", "ST", 0.64f, Y_FWD + 0.02f),
        ),
    ),
    // ---------------------------------------------------------- five defenders
    Formation(
        "5-3-2", "5-3-2", "A back five that becomes a three going forward.",
        listOf(gk()) + backFive() + listOf(
            FormationSlot("lcm", "CM", "CM", 0.28f, Y_MID + 0.03f),
            FormationSlot("ccm", "CM", "CM", 0.5f, Y_MID + 0.06f),
            FormationSlot("rcm", "CM", "CM", 0.72f, Y_MID + 0.03f),
            FormationSlot("lst", "ST", "ST", 0.36f, Y_FWD + 0.02f),
            FormationSlot("rst", "ST", "ST", 0.64f, Y_FWD + 0.02f),
        ),
    ),
    Formation(
        "5-4-1", "5-4-1", "Nine behind the ball. The deepest shape here.",
        listOf(gk()) + backFive() + listOf(
            FormationSlot("lm", "Winger", "LM", 0.12f, Y_MID + 0.02f),
            FormationSlot("lcm", "CM", "CM", 0.37f, Y_MID + 0.04f),
            FormationSlot("rcm", "CM", "CM", 0.63f, Y_MID + 0.04f),
            FormationSlot("rm", "Winger", "RM", 0.88f, Y_MID + 0.02f),
            FormationSlot("st", "ST", "ST", 0.5f, Y_FWD + 0.02f),
        ),
    ),
    Formation(
        "5-2-3", "5-2-3", "A back five and a front three. Built to counter.",
        listOf(gk()) + backFive() + listOf(
            FormationSlot("lcm", "CM", "CM", 0.36f, Y_MID + 0.05f),
            FormationSlot("rcm", "CM", "CM", 0.64f, Y_MID + 0.05f),
            FormationSlot("lw", "Winger", "LW", 0.14f, Y_WIDE_FWD + 0.02f),
            FormationSlot("st", "ST", "ST", 0.5f, Y_FWD),
            FormationSlot("rw", "Winger", "RW", 0.86f, Y_WIDE_FWD + 0.02f),
        ),
    ),
    Formation(
        "5-2-1-2", "5-2-1-2", "A back five, a ten, and a front two.",
        listOf(gk()) + backFive() + listOf(
            FormationSlot("lcm", "CM", "CM", 0.34f, Y_MID + 0.105f),
            FormationSlot("rcm", "CM", "CM", 0.66f, Y_MID + 0.105f),
            FormationSlot("cam", "CAM", "CAM", 0.5f, Y_ATTACK_MID + 0.02f),
            FormationSlot("lst", "ST", "ST", 0.36f, Y_FWD + 0.02f),
            FormationSlot("rst", "ST", "ST", 0.64f, Y_FWD + 0.02f),
        ),
    ),
)

val DefaultFormation: Formation = Formations.first { it.id == "4-3-3" }

fun formationById(id: String?): Formation =
    Formations.firstOrNull { it.id == id } ?: DefaultFormation
