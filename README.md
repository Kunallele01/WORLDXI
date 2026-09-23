# ERA XI

An Android game about a question football fans argue about and never settle:
**could your all-time XI actually have won a league?**

You assemble eleven real footballers — not "Messi" but "Messi, Barcelona
2011/12", because the same person in a different year is a different player —
and then drop that XI into a real season to find out what it was worth.

## The idea that makes it different

The season is a **counterfactual, not a re-simulation**.

Your XI takes the place of one real club, and plays that club's actual fixture
list. Only your own 38 matches are simulated. The other 342 keep their real
historical scorelines — so Manchester City still win 2021/22 unless *you*
personally take points off them, and the table you finish in is the real table
with your results swapped in.

Re-simulating the whole league was tried and abandoned: it rewrote history
(Barcelona winning 2021/22 in one run), and a season where everything is
invented tells you nothing about whether your eleven were good.

## Three ways to play

| Mode | What it is |
|---|---|
| **Draft** | Eleven rounds. Each spins a random *club and season* out of the whole database, and you take one player into one position. 2019/20 Liverpool can meet 2023/24 Girona — the cross-era mixing is the point. |
| **Free Mode** | No spins. Build any XI from any club, any season, by hand — or press "work the magic" and let the solver pick the best eleven available. |
| **World Cup** | The same counterfactual, as a tournament. Your side takes the place of a nation that finished bottom of its group at any World Cup from 2006 to 2026, and plays the draw out. |

In the World Cup the **knockout bracket cascades**. A tie keeps its real
scoreline only when the two sides that arrive are the two that really met in
that round. Change your group and a different nation goes down that side of the
draw, so one upset can rewrite a whole branch while the other branch plays out
exactly as it happened. With nobody replaced, the engine reproduces every real
tournament exactly — there is a test that holds it to that.

## How the simulation works

Poisson scorelines from attack and defence ratings, fitted on real seasons.
What matters more than the model is the rule the codebase is built on:

> **No constant that was not measured.**

Every number in `sim/` was fitted against real football and carries its fit
quality in a comment beside it — how many matches, what the error was, and
where the evidence stops. Where something could not be measured, the code says
so and takes the assumption that adds least, rather than inventing a plausible
figure. A few examples of what that looks like in practice:

- Goal minutes were drawn **flat across the ninety** for a long time, and the
  comment explaining why said plainly that real goals lean late but no club
  season in the database records a minute. When the World Cup scrape supplied
  1,084 real minutes, the curve was fitted from them — including added time,
  which carries nearly one goal in ten.
- A knockout-specific scoring factor was fitted, **failed validation, and was
  rejected**. The note recording that sits in `WcModel.kt` so nobody refits it.
- Wrong-flank penalties are flagged in the source as the only unmeasured
  numbers in the rating model, because EA's left and right rating columns are
  identical for every player and there is nothing to derive a magnitude from.

Out-of-position ratings come from EA's own per-player positional grids rather
than a penalty anyone invented: if the data says a defensive midfielder is
seven points worse at left-back, that is what he is worth there.

## Data

| Source | Used for | Licence |
|---|---|---|
| **Wikipedia** | World Cup fixtures, results, scorers, goal minutes, shootouts (2006–2026) | CC BY-SA 4.0 — credited in the app's tournament screen |
| **FBref** | Club fixtures and box-score statistics | Terms of the source |
| **Understat** | Expected goals and assists | Terms of the source |
| **EA / FIFA ratings** | Player overall ratings and positional grids | Terms of the source |

Roughly: two leagues (Premier League, La Liga) across fifteen seasons each,
plus every World Cup squad from 2006 to 2026.

**The raw datasets are not redistributed here.** `etl/archive/`, `etl/staged/`
and the raw Wikipedia page dumps are gitignored, so a fresh clone can run the
app and the test suite but cannot re-run the scrapers without fetching the
sources itself. What *is* committed is the small derived output the tests and
the fitted constants read — the World Cup match and goal export, and the parsed
Wikipedia events.

Generated Kotlin files (`WcOutput.kt`, `WcPositionCosts.kt`, `WingBackCost.kt`,
`GoalMinutes.kt`) hold **aggregate statistics fitted from** those sources, not
the sources themselves. Each says at the top which script generated it.

## Building it

Android Studio, JDK 21, `minSdk 24`.

The app reads a Supabase project for its data. Put your own credentials in
`local.properties` (gitignored, never committed):

```properties
SUPABASE_URL=https://<your-project>.supabase.co
SUPABASE_ANON_KEY=<your anon key>
```

Then apply `supabase/schema.sql` followed by the migrations in
`supabase/migration_*.sql`, in order, in the Supabase SQL editor. Each
migration carries its own `GRANT`s — this project has "expose new tables
automatically" switched off, so a migration that forgets them produces a table
the app cannot read.

```bash
./gradlew :app:testDebugUnitTest     # 244 unit tests
./gradlew :app:installDebug          # onto a connected device
```

The whole `sim/` package is pure Kotlin with no Android imports, so the engine
and its tests run on the JVM without an emulator.

## Layout

```
app/src/main/java/com/dreamxi/app/
  sim/              the engine — pure Kotlin, no Android
  sim/worldcup/     the tournament engine and its generated constants
  feature/          draft, free mode, season, statistics, World Cup, splash
  data/             Supabase repositories
etl/                Python: scrapers, joiners, calibration, generators
supabase/           schema and migrations, applied by hand in order
```

Anything in `sim/` named after a measurement — `WcOutput.kt`,
`WcPositionCosts.kt`, `GoalMinutes.kt`, `WingBackCost.kt` — is **generated** by
the script named at the top of the file. Edit the script and re-run it; do not
edit the Kotlin.

## Testing

244 unit tests. They are mostly not "does this method return 3" — they check
claims about football that could quietly stop being true:

- every real World Cup replays exactly when nobody is replaced
- the rating the draft screen shows is the rating the engine actually plays,
  checked across ~197,000 player-and-position pairs
- the shipped goal-minute curve still matches the real goals it was fitted on
- no two shirts on the pitch can overlap, in any formation, at any screen size
- a substitute cannot score or assist before he came on

## Status

Playable end to end on a device. Known gaps, kept honestly in
`PROJECT_OVERVIEW.md`:

- **No auth and no persistence.** A run lives in memory and dies with the
  process — this is the biggest structural hole.
- Home, Onboarding, History and Profile are placeholder screens.
- Defensive modelling is limited by data: FBref stripped clearances, aerials
  and progressive actions in January 2026 and there is no free replacement, so
  the engine reads only columns every season has.

`PROJECT_OVERVIEW.md` also keeps a list of **things already tried and
rejected**, with the evidence for each, so they do not get re-proposed.

## Licence

No licence is declared yet, which by default means all rights reserved. The
third-party data listed above keeps its own terms regardless — in particular
the World Cup match data is Wikipedia's under CC BY-SA 4.0, and the app credits
it on screen.
