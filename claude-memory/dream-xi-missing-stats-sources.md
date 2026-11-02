---
name: dream-xi-missing-stats-sources
description: "DONE — rich stats are loaded. Understat (all 5 seasons) + a worldfootballR mirror of pre-strip FBref (3 seasons). What is still missing and why."
metadata: 
  node_type: memory
  type: project
  originSessionId: a8dd7611-b8d2-4a24-8702-86d5bacbe29d
  modified: 2026-08-29T16:07:52.932Z
---

Investigated and LOADED 2026-08-29. The unpopulated columns in `player_season_stats`
split cleanly into recoverable and permanently gone.

**FBref is reachable again** — Cloudflare still 403s plain HTTP, but a real
browser session loads it fine after the challenge clears (~5s). The earlier
note that it "lost its advanced-stats feed" is right in substance but needed
pinning down: the Opta-derived columns were stripped RETROACTIVELY, for old
seasons too, and in two different ways.

Measured on PL 2021/22 (546 player rows):
- `passing` table: every advanced column **0/546 populated** — headers still
  render, cells are empty. Includes pass %, key passes, xA, passes into final
  third. `progressive_passes` no longer exists as a column at all.
- `standard` table: `xg`, `npxg`, `xg_assist`, `progressive_carries`,
  `progressive_passes` **columns entirely absent**.
- `possession` table: all 20 stat columns **0/546** — touches, take-ons
  (dribbles), carries, progressive carries, miscontrols.
- `defense` table: `clearances`, `blocks`, `tackles` (total), tackles-by-third
  all **0/546**. BUT `interceptions` and `tackles_won` are **546/546**.
- `misc` table: `interceptions`, `tackles_won`, `fouls`, `fouled`, `offsides`,
  `crosses`, `own_goals`, cards all **546/546**. Aerial duel columns removed
  entirely.
- `shooting` table: fully intact (shots, SoT, SoT%, per-90s, pens) — no xG.

**UNDERSTAT is the fix for the xG family.** One POST returns a whole
league-season:

    POST https://understat.com/main/getPlayersStats/
    Content-Type: application/x-www-form-urlencoded
    X-Requested-With: XMLHttpRequest
    body: league=EPL&season=2021        (La_liga, Bundesliga, Serie_A, Ligue_1)

Season is the START year (2021 = 2021/22). Response is `{success, players:[…]}`
— note **top level `players`**, NOT `response.players`; reading the wrong path
returns an empty list and looks like rate limiting. Fields per player:
`id, player_name, games, time, goals, xG, assists, xA, shots, key_passes,
yellow_cards, red_cards, position, team_title, npg, npxG, xGChain, xGBuildup`.

Verified 100% populated across all ten loaded league-seasons:

    EPL     2019:515  2020:524  2021:537  2022:554  2023:570
    La_liga 2019:556  2020:570  2021:603  2022:584  2023:598

Plain HTTP to understat.com returns an 18KB placeholder — it needs a real
browser session, same as FBref. Requests must be SEQUENTIAL with a short
delay; firing all ten in parallel returns empties.

**Verdict per column:**

| column | source | status |
|---|---|---|
| xg, xa, key_passes | Understat | recoverable |
| npxG, xGChain, xGBuildup | Understat | bonus, no column yet |
| interceptions | FBref defense/misc | recoverable (currently all-zero in DB) |
| crosses, fouls, fouled, offsides | FBref misc | bonus, no column yet |
| pass_completion_pct | — | GONE |
| dribbles_completed | — | GONE (take-ons stripped) |
| progressive_carries, progressive_passes | — | GONE |
| clearances | — | GONE |
| aerial_won, aerial_lost | — | GONE (columns removed) |

Nothing here changes `overall_rating`, which is FIFA-anchored (see
[[dream-xi-rating-anchor]]). It matters for player descriptions and for what a
match engine can reason about. Note also that the six derived attribute columns
(finishing/creation/buildup/carrying/defense/physical) were computed from
inputs that were mostly never loaded and are NOT trustworthy — Kurt Zouma
scores 98 for finishing off 5 goals; see [[dream-xi-etl-pilot]].

-------------------------------------------------------------------------------
OUTCOME — loaded 2026-08-29 (migration_0006 + etl/backfill_rich_stats.py)
-------------------------------------------------------------------------------

**The pre-strip FBref data was recoverable after all**, from the worldfootballR
mirror (`JaseZiv/worldfootballR_data`, `data/fb_big5_advanced_season_stats/`,
R .rds read with pyreadr). Free, no signup. See `etl/fbref_archive.py`.

It covers **2019/20, 2020/21, 2021/22 ONLY**. A fourth season is present and was
REJECTED: end-year 2023 is a mid-season snapshot — max 1080 minutes against
3420 elsewhere, median 450, no player over 3000, Van Dijk 990 against 3060 the
year before. The rows are well-formed, so loading it would have entered ~12
games of every total as a full season.

**Understat needs no browser** — my first test was wrong. The POST response is
GZIPPED and served as text/javascript, so urllib returns what looks like a
binary blob. Decompress it and it is plain JSON. `etl/understat.py` pulls all
ten league-seasons in one pass, no Chrome.

Loaded, of 5,723 player-seasons:

    interceptions                        100% (real values, was all-zero)
    xg / xa / npxg                        94%   all five seasons (understat)
    key_passes                            97%
    clearances, aerials, blocks, errors,  58%   2019/20-2021/22 (fbref_archive)
      recoveries, touches, dribbles,
      progressive carries/passes,
      pass_completion_pct, SCA, GCA
    psxg, psxg_prevented,                  4%   keepers only, 3 seasons
      crosses_stopped_pct, sweeper_actions

`player_season_stats.stats_source` = 'fbref_archive' (3,346) or 'understat'
(2,222); 155 rows matched neither.

**THE RULE THIS CREATES: the simulation must read only columns every season
has** — goals, assists, shots, tackles, cards, minutes, xG, xA, npxG, key
passes, interceptions (once the gap below is closed), and GK saves/save%/clean
sheets. Reading clearances or aerials would make every 2022/23 and 2023/24
player silently worse for lacking data rather than for being worse. The rich
columns are for DESCRIPTIONS, where thinner text for two seasons is harmless.

STILL MISSING, and it is small:
  - interceptions for 2022/23 + 2023/24 (2,377 rows still zero). FBref serves
    these LIVE — needs a browser scrape of 4 league-seasons.
  - 113 Understat rows skipped as mid-season transfers: it reports one combined
    row per player-season ("Southampton,Arsenal") while we hold one row per
    club. Splitting by minutes would assume equal form at both clubs, which a
    mid-season move usually disproves. Skipped deliberately, not failed.
  - 35 genuinely unmatched regulars.

TWO TRAPS worth remembering, both cost an attempt:
  - Archive counts are numpy floats (12.0). PostgREST rejects those against an
    integer column: "invalid input syntax for type integer". Cast first.
  - pandas `itertuples()` silently renames any column that is not a valid
    Python identifier — `#OPA_Sweeper` became `_31` — so getattr returned None
    for every row and sweeper_actions filled with nothing, with no error.
    Use `to_dict("records")`.

-------------------------------------------------------------------------------
API-FOOTBALL ASSESSED 2026-08-29 — IT CANNOT CLOSE THE GAP. DO NOT BUY IT.
-------------------------------------------------------------------------------

Tested against the user's own free account (100 req/day, ~10 req/min; the free
plan DOES serve historical seasons 2022 and 2023, which was the main worry).

The player statistics payload has exactly these blocks:
    games, substitutes, shots, goals, passes, tackles, duels, dribbles,
    fouls, cards, penalty

**There is NO clearances field and NO aerial field anywhere in it.** `duels`
carries only {total, won} — ground and aerial combined, so it is not an aerial
substitute. Confirmed by string-searching the whole payload for "clearance"
and "aerial": both absent.

Fill rates, players with >=450 minutes, seasons 2022 and 2023:
    key passes 89-100% | tackles 93-100% | blocks 86-100%
    interceptions 93-100% | duels won 100% | dribbles 86-100%
    match rating 100% | pass accuracy ONLY 20-33%

So buying it would add, for 2022/23 + 2023/24: blocks, dribbles, a rough
duels-won number and a match rating. It would NOT add clearances, aerials,
progressive carries/passes, touches, SCA/GCA or post-shot xG. Parity with the
2019/20-2021/22 archive tier is NOT PURCHASABLE here at any price.

And if those four fields are ever wanted, the FREE tier suffices: a full pull
is ~45-51 pages per league-season x 4 = ~190 requests, i.e. two days inside the
100/day cap. No subscription needed.

Rate limiting is per-minute and SILENT — over-fast loops return empty
`response` arrays rather than an error, which reads exactly like "this season
has no data". Space requests ~7s apart.

-------------------------------------------------------------------------------
INTERCEPTIONS FOR 2022/23 + 2023/24 — APPLIED 2026-09-05
-------------------------------------------------------------------------------

FBref still serves interceptions and tackles-won live for these seasons. Pulled
all four league-seasons to `etl/fbref_live/interceptions_2022_2024.tsv`
(1,668 players at >=450 minutes; 93% non-zero).

**WRITTEN 2026-09-05** via `etl/backfill_interceptions.py --apply`.
On the day it finally ran, 1,667 of 1,668 matched (up from 1,660 — the
positions/identity backfills since 29 Aug closed the other seven). Only
Bertrand Traore (Villarreal, 2023/24) is unmatched. 1,557 non-zero values
plus 110 genuine zeros written.

Verified after the write: regulars (>=450 min) carrying a non-zero
interception count now read 91/92/92/94/92% across the five seasons — the two
new seasons are in line with the three that always had data, so the column is
uniform at last. 797 rows in those seasons are still zero, and that is
CORRECT: median 144 minutes, and every high-minute one is a GOALKEEPER
(Ramsdale, Oblak, Onana, de Gea all 3420 min) — FBref does not credit keepers
with interceptions. Do not "fix" those.

The two-Rodri case proves the club disambiguation works: Man City 31/25 and
Real Betis 11/8 landed on the right rows, as did Casemiro's Man Utd rows
against his empty Real Madrid 2022/23 row.

Scraping mechanics worth reusing: build the rows in the page, stash each
league-season in localStorage (same origin survives navigation), then dump as
PIPE-delimited text — tabs collapse to single spaces in text extraction and
player names contain spaces, which destroys the columns. get_page_text saves
to a file only when the output exceeds ~50KB and truncates the file at ~50KB,
so pad a smaller dump past the threshold to get it written to disk cheaply
instead of flooding the transcript.
