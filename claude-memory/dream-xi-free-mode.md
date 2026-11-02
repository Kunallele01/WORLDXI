---
name: dream-xi-free-mode
description: "Free Mode (built) — pick any player from any club/season, no spins, feeds the same simulation, runs labelled FREE MODE"
metadata: 
  node_type: memory
  type: project
  originSessionId: a8dd7611-b8d2-4a24-8702-86d5bacbe29d
  modified: 2026-08-29T23:09:18.553Z
---

**BUILT AND ON-DEVICE 2026-08-31.** `feature/freemode/` — ViewModel, screen and
route. Reached by a secondary "Free mode — build any XI" button on Setup, which
carries the same league and formation the draft would use.

**What it is.** A second, unrestricted way to assemble an XI, sitting alongside
the existing spin-based draft rather than replacing it:

1. User selects a **league** (as at Setup today).
2. User taps a **position** on the pitch.
3. Two chip rows: **club** × **season of that club**. Picking a pair shows that
   squad; the user takes whoever he wants. The list is NOT filtered to players
   who suit the position (the user chose this): every man is listed with what he
   would be worth standing there, so Xavi at centre forward is available and
   visibly costly rather than hidden.
4. Repeat for all eleven.
5. The finished XI enters **the same simulation the draft feeds** — takeover
   draw, counterfactual season, match reports, season statistics. No separate
   engine, no separate screens.

**The point is explicitly fun, not challenge.** The user's words: it
"guarantees 90 OVR squads easily". That is the intent, so do NOT add balancing,
rating caps, budgets or a squad-strength weighting to it — the constraint-free
feel is the feature. The spin draft remains the mode where scarcity matters.

**Free Mode runs are LABELLED as such** on the season screens
(`CompletedDraft.isFreeMode` → `SeasonUiState.isFreeMode` → gold "· FREE MODE"
in the header). The user asked for this explicitly: a 90-rated XI winning a
league is a different achievement from a drafted one doing it.

**Kept from the draft** (these are about football, not difficulty): player
uniqueness (one real person once), out-of-position pricing off the EA grid, the
formation lock, and the absolute goalkeeper boundary. `PitchView` is reused
unchanged.

**Watch the extrapolated region.** A 90-rated XI sits far above anything in the
measured data. The engine saturates attack above 3.00 npxG (the highest real XI
ever recorded) so it will not produce absurd scorelines, but Free Mode is the
mode most likely to push past where the fit was validated — see
[[dream-xi-simulation-engine]].

See [[dream-xi-draft-screen-state]] for what the existing draft does.


-------------------------------------------------------------------------------
MAGIC XI BUTTON — built 2026-09-05
-------------------------------------------------------------------------------

"Work the magic" fills all eleven shirts with the strongest side the league can
field. `feature/freemode/MagicXi.kt` (pure, tested), `DraftRepository.
magicCandidates()`, `FreeModeViewModel.castMagic()`, `MagicPhase` drives the UI.

**THE ELEVEN ARE CHOSEN TOGETHER, NOT SLOT BY SLOT — do not "simplify" this
into a greedy fill.** Measured on La Liga: greedy takes Ronaldo for the first
winger shirt, then finds Messi has nowhere better left than central midfield
and pays a 4-point out-of-position cost to put him there. Solved as one
assignment (Kuhn-Munkres, `Hungarian` in the same file) both play wide and
Suarez leads the line — a stronger AND more sensible side. The reveal animation
still fills shirts back-to-front; only the arithmetic happens up front.

**Two decisions the user made when asked:**
- out-of-position IS allowed, priced by `coverDelta`. Natural-position-only was
  rejected because it bars De Bruyne (recorded CAM) from a CM shirt and hands
  it to Pogba. Cost of allowing it: Casemiro shows up at CB for the PL, which
  is defensible (he played there for United).
- the side is deliberately NOT deterministic. Each candidate's value gets a
  random nudge under `MagicXi.JITTER` (1.0 rating point) before solving, so
  near-equals trade places between presses while the total stays within a point
  of the true optimum. Without it, one league + one formation = one fixed XI
  forever and no reason to press twice.

**Candidates are fetched PER ROLE for all 8 roles regardless of the formation**
(40 deep, 900+ minutes). Fetching "top N in the league" instead would never
surface De Bruyne for a 4-3-3, because he is recorded CAM and that formation
has no CAM shirt.

**The bench needs the eleven clubs' squads, which the user never opened.**
`castMagic` fetches them in parallel AFTER the reveal — they are only needed
for `BenchSelection`, and loading eleven squads first would put a blank screen
where the animation belongs. `benchFor()` is now shared by magic and manual
picks so the two cannot drift; changing any magic pick relocks the bench by the
same rule and drops `MagicPhase` back to IDLE.

**A TEST TRAP WORTH REMEMBERING.** The first brute-force reference for the
solver ranked assignments by total value alone, and "failed" the solver on
    row0 [-4, -5]   row1 [-5, forbidden]
by scoring -4 — fielding ONE player and abandoning the other shirt. A higher
number and not a team. The reference now ranks filled shirts first, then value,
and matches the solver on 3,000 random matrices. Reference semantics were the
bug, not the solver.

Reference XIs at the time of building (mean effective rating):
  La Liga 90.4 — Oblak; Alba, Ramos, Godin, Marcelo; Casemiro, Modric, Kroos;
                 Messi, Suarez, Ronaldo
  PL      89.6 — De Gea; Alexander-Arnold, Van Dijk, Casemiro, Cancelo;
                 Rodri, De Bruyne, Bruno Fernandes; Salah, Haaland, Hazard

**REVEAL PACING — corrected after testing, 2026-09-05.** First build ran the
reveal at 150ms a shirt; the user's verdict was "too fast to see properly",
which is the same note he gave on the stadium intro. Now 300ms a shirt plus a
250ms pause where the team changes LINE (keeper / defence / midfield / attack),
so the structure of the reveal is legible instead of eleven shirts arriving on
one metronome. About 4.9s end to end including the banner. Constants:
`REVEAL_STEP_MS`, `REVEAL_LINE_PAUSE_MS`, `CONJURE_MS` in FreeModeViewModel.kt.

**The pattern to carry forward: this user consistently wants reveal animations
SLOWER than the first attempt.** Two for two now — the asset intro and this.
When in doubt, err long.

## Magic button fills in around your picks (2026-09-12)

Pressing "Work the magic" with some shirts already filled by hand KEEPS those and solves only the rest; the kept men are also removed from the candidate pool so nobody is offered twice. Pressing it again on a finished side re-solves all eleven — the locks are consumed by the cast that used them (`manualSlots` is cleared at the start of `castMagic`). If every shirt is hand-picked there is nothing to fill, so it falls back to a full reshuffle rather than doing nothing. The button reads "Fill the rest with magic" while shirts are filled and nothing has been cast yet.

**Why:** the user asked for it in those words, including that a re-press after a full side may reshuffle hand-picked players too.

**How to apply:** keeping is a parameter of `MagicXi.build` (keptSlotIds / keptPlayerIds), NOT a filter afterwards — the free shirts are chosen knowing who is already on the pitch. A test of mine asserted a 94-rated wide forward stays on the wing when one wing is kept; the solver correctly moved him to striker instead (94 at his own position + an 88 winger beats 92 wide + an 88 striker). The arithmetic was right and my expectation was wrong — check the sum before trusting the intuition. See [[dream-xi-free-mode]].

## Club picker: list, search, A-Z rail, real club names (2026-09-12)

The horizontal chip strip of 41 PL / 36 La Liga clubs was replaced by a three-step picker inside the same sheet: clubs -> that club's seasons -> squad. Clubs are a vertical list with a search box, sticky letter headers and a tappable A-Z rail; each row shows the crest, the real club name and how many seasons exist. The season step shows finishing position and squad strength, because that is what a season is chosen on — the old picker offered bare labels. Back retraces the steps (squad -> seasons -> clubs -> closed); the step is DERIVED from selectedClub/selectedSeason rather than stored, so there is no second source of truth.

**Why:** the user could not tell how far through the club strip he was or what he had scrolled past, and wanted clubs under their real names.

**How to apply:** display names live in `core/ui/ClubName.kt` (stored FBref short forms stay the key for crests, joins and loads — display only). Sorting STRIPS INITIALISMS, KEEPS WORDS: FC Barcelona files under B, RCD Espanyol under E, CA Osasuna under O, AFC Bournemouth under B, but Real Madrid stays under R and Athletic/Atletico/Sporting under A/A/S. The user challenged this ("wouldnt you make Real Madrid appear in M?") and the answer is the abbreviation-vs-word test, not dropping the first word. `ClubBadge` takes the STORED name; only labels use the display name. `stickyHeader` is a LazyListScope member in this Compose version — importing it explicitly fails to resolve.
