---
name: dream-xi-fifa-grid-faults
description: "~1.2% of player-seasons carry a positional grid or primary_position that is wrong; what causes it, why no automatic rule can fix it, and why the bench is now immune"
metadata: 
  node_type: memory
  type: project
  originSessionId: a8dd7611-b8d2-4a24-8702-86d5bacbe29d
  modified: 2026-09-01T13:31:44.751Z
---

**FAULT B IS SOLVED — 2026-09-05. It was never bad EA data; it was our own
matcher.** See the ROOT CAUSE section at the bottom, which supersedes the
"no automatic rule works" conclusion for B. Fault C (wrong `primary_position`
on Transfermarkt-sourced seasons) is still open but is now much smaller.

**Original diagnosis (2026-09-01) preserved below**, because the symptom
descriptions are still accurate and the reasoning shows why it looked
unfixable from the data alone.

**The symptom.** Auto-picked bench players in positions they never played:
Carvajal at DM, Lewandowski as a winger, Valverde at full-back, Dembélé at ST.

**Three distinct faults, and only one was the bench logic.**

**A — the ranking was effectively "highest rating wins".** 29% of bench slots
went to a converted player (48% at DM, 42% at CM). In most cases the penalty
applied was literally ZERO, because `coverDelta` compares a player's grid at the
target position against his grid at his OWN and clamps at zero — so whenever EA
rates him as well or better somewhere he does not play, there is no penalty at
all. Even a real penalty is too weak: Lewandowski's winger grid is 85 against
his ST 89, and four points never beats a six-point rating gap in a pool of
eleven elite squads. **FIXED** — `BenchSelection` now prefers a player whose
RECORDED position matches the slot and only falls back to conversions when
nobody natural is left. This also makes the bench immune to faults B and C.

**B — about 1.2% of grids belong to a different player** (47 of 3,823 regulars).
Carvajal is the clearest case because both versions exist:

    2021/22  cb 78  fb 81  dm 79  cm 77  cam 75  winger 75  st 70   correct
    2023/24  cb 56  fb 64  dm 68  cm 80  cam 83  winger 81  st 75   someone else

Four of his five seasons carry the second. It is what put a right-back on a
bench as a defensive midfielder: with his full-back value corrupted to 64, a DM
value of 68 looks like an UPGRADE, so the penalty is zero. Same pattern for
Rodri, Vitinha, Emerson, Iván Alejo — short or ambiguous names, consistent with
a collision in the grid re-match, which keys on NAME because FC 25 uses
synthesised ids. Of the 47, **20 are repairable from the same player's own good
season; 27 need a genuine re-match with club disambiguation.**

**C — some `primary_position` values are wrong.** Dembélé is recorded ST in all
four Barcelona seasons; EA's grid says winger 85 against ST 77. Here the GRID is
right and the POSITION is wrong — the opposite of B.

**WHY NO AUTOMATIC RULE WORKS, and this is the important part.** Rodri (4
seasons all peaking at CAM) and Dembélé (3 seasons all peaking at Winger) are
INDISTINGUISHABLE to a heuristic — both are consistent, both disagree with their
recorded position — yet Rodri's grid is wrong and Dembélé's position is wrong.
Any "trust the consistent grid" rule fixes Dembélé and breaks Rodri. Any
"synthesise a grid from the recorded position" rule fixes Rodri and breaks
Dembélé. Order matters too: correcting positions BEFORE repairing grids would
move Carvajal to CAM, because his corrupt grid peaks there.

**Still worth doing eventually** — a corrupt grid also misleads the DRAFT's
on-pitch pricing, so Carvajal currently shows as a fine DM when Real Madrid is
spun, which misinforms the user's own pick rather than just the bench.
See [[dream-xi-rating-anchor]] for how the grid is built.


-------------------------------------------------------------------------------
ROOT CAUSE FOUND AND FIXED 2026-09-05 — the grids were fine, the MATCH was not
-------------------------------------------------------------------------------

Triggered by the user asking why Dani Carvajal was listed as a GOALKEEPER in
the newly loaded 2014/15-2016/17 seasons. He was never a corrupt EA row: the
lookup was returning a DIFFERENT PERSON, and the "corrupt grid" was that other
man's real grid. Two independent bugs in `fifa_lookup.py`, both now fixed.

**BUG 1 — the `name` and `initial` loose tiers accepted uniqueness as identity.**
Every other tier is guarded by birth year or club; those two were not. And
`initial_form` keeps only a first initial and the LAST token, so every Spanish
double surname ending in the same name collapses together:

    Dani Carvajal            -> "d carvajal"
    Daniel Olmo Carvajal     -> "d carvajal"     (Dani Olmo!)
    Diego Sanchez Carvajal   -> "d carvajal"     (a goalkeeper)

Whoever happened to be ALONE in a given edition won the match. That is the
whole of fault B: Carvajal's "someone else's grid" with cam 83 / winger 81 was
**Dani Olmo's**, and in editions 16-18, where Olmo is absent, he became a
goalkeeper born 1987. Ben Davies of Spurs was matched to a Ben Davies at
Rangers; Chory Castro, Michel, Pedro, Paulinho and Fabricio all the same shape.
FIX: `_corroborated()` — a loose match must agree on birth year (within 1) or
on club. 46 wrong matches removed; 27 immediately re-matched correctly by the
guarded tiers, 19 lost their (wrong) anchor.

**BUG 2 — `by_key` was a plain dict, so exact name + birth year silently
resolved collisions by insertion order.** EA FC 24 carries TWO men called Luiz
Felipe, both born 1997: Real Betis's centre-back and a goalkeeper at Vizela.
The keeper was read second and won, so Betis's defender carried st 21 / cb 22.
FIX: `by_key_all` keeps every candidate and `_exact()` disambiguates on club,
returning None rather than guessing when it cannot.

**MEASURED RESULT across all 20 league-seasons (11,157 rows):**
- outfielders carrying a keeper-shaped grid at 900+ minutes: 1 -> **0**
- keepers carrying an outfield grid: **0**
- regulars (1500+ min) rated under 60: **1** (Juanpi, Malaga 2015/16, 47 —
  genuinely no anchor, not a mis-match)
- 30 position corrections and ~108 rating corrections in total

**THE GENERAL LESSON, worth applying anywhere else names are matched:**
uniqueness is not identity. A name that is unique in one snapshot is only
unique by accident — check it against something the name did not supply
(birth year, club, goals scored). Both bugs were invisible in every summary
statistic: coverage stayed at 99%, because a wrong match counts as a match.
