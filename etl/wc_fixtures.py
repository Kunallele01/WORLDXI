"""
Every World Cup match we ship, from the two sources that between them cover it.

WHY TWO SOURCES. The worldfootballR mirror (`wc_results.rds`) runs 1930-2022
but its 2022 rows carry no scorelines, and it has nothing at all for 2026. Both
of those were read off FBref in a real browser (Cloudflare blocks scripts) into
`wc_results_2022_2026.tsv`. So the rule is simply: 2022 and 2026 come from the
TSV, everything older from the mirror. `load_all` asserts the mirror really is
scoreless for 2022 rather than trusting that note.

SHOOTOUTS ARE RECORDED DIFFERENTLY IN EACH. The TSV has explicit penalty
columns; the mirror only says so in prose ("Spain won on penalty kicks
following extra time"), so the winner is parsed back out of `Notes`. Both end
up in the same `shootout_winner` field, because a knockout tournament cannot be
simulated without knowing who actually went through.

Team names keep FBref's spelling with its country code stripped; mapping them
onto the ratings table's spellings is `wc_nations.to_rating_nation`'s job, done
at the point of use rather than baked in here.
"""
from __future__ import annotations

import csv
from dataclasses import dataclass
from pathlib import Path

import wc_nations

HERE = Path(__file__).parent
MIRROR = HERE / "wc_results.rds"
RECENT = HERE / "wc_results_2022_2026.tsv"

#: The editions World Cup mode ships, as agreed: 2006 through 2026.
EDITIONS = (2006, 2010, 2014, 2018, 2022, 2026)
#: WC year -> the FIFA edition that rates it, under the same N+1 rule the club
#: seasons use (the game released in the autumn of the tournament's year).
RATING_EDITION = {2006: "07", 2010: "11", 2014: "15", 2018: "19", 2022: "23", 2026: "26"}
#: Host nations, as the fixtures spell them. Hosts score x1.42 what the model
#: expects (etl/wc_calibration.py), so the engine has to know.
HOSTS = {
    2006: frozenset({"Germany"}),
    2010: frozenset({"South Africa"}),
    2014: frozenset({"Brazil"}),
    2018: frozenset({"Russia"}),
    2022: frozenset({"Qatar"}),
    2026: frozenset({"USA", "Canada", "Mexico"}),
}
#: Editions the mirror cannot supply scorelines for.
FROM_TSV = frozenset({2022, 2026})

_PENALTY_PHRASE = " won on penalty kicks"


@dataclass(frozen=True)
class Match:
    edition: int
    round: str
    date: str
    home: str
    home_goals: int | None
    away: str
    away_goals: int | None
    pens_home: int | None
    pens_away: int | None
    shootout_winner: str | None
    source: str

    @property
    def played(self) -> bool:
        return self.home_goals is not None and self.away_goals is not None


def _int(value) -> int | None:
    text = str(value).strip()
    if not text or text.lower() in {"nan", "none", ""}:
        return None
    try:
        return int(float(text))
    except ValueError:
        return None


def _shootout_from_notes(notes: str) -> str | None:
    """'Spain won on penalty kicks following extra time' -> 'Spain'."""
    text = str(notes or "")
    if _PENALTY_PHRASE not in text:
        return None
    return text.split(_PENALTY_PHRASE)[0].strip() or None


def load_mirror() -> list[Match]:
    import pyreadr

    frame = list(pyreadr.read_r(str(MIRROR)).values())[0]
    wanted = [e for e in EDITIONS if e not in FROM_TSV]
    out: list[Match] = []
    for row in frame.to_dict("records"):
        edition = _int(row.get("Season_End_Year"))
        if edition not in wanted:
            continue
        notes = row.get("Notes") or ""
        winner = _shootout_from_notes(notes)
        out.append(Match(
            edition=edition,
            round=str(row.get("Round") or "").strip(),
            date=str(row.get("Date") or "").strip(),
            home=wc_nations.strip_country_code(row.get("Home") or ""),
            home_goals=_int(row.get("HomeGoals")),
            away=wc_nations.strip_country_code(row.get("Away") or ""),
            away_goals=_int(row.get("AwayGoals")),
            pens_home=None,
            pens_away=None,
            shootout_winner=wc_nations.strip_country_code(winner) if winner else None,
            source="mirror",
        ))
    return out


def load_recent() -> list[Match]:
    out: list[Match] = []
    with open(RECENT, encoding="utf-8", newline="") as fh:
        for row in csv.reader(fh, delimiter="\t"):
            if not row or not row[0].strip() or row[0].strip().lower() == "edition":
                continue
            cells = (row + [""] * 10)[:10]
            edition = _int(cells[0])
            if edition not in FROM_TSV:
                continue
            home = wc_nations.strip_country_code(cells[3])
            away = wc_nations.strip_country_code(cells[6])
            pens_h, pens_a = _int(cells[7]), _int(cells[8])
            winner = None
            if pens_h is not None and pens_a is not None:
                winner = home if pens_h > pens_a else away
            out.append(Match(
                edition=edition,
                round=cells[1].strip(),
                date=cells[2].strip(),
                home=home,
                home_goals=_int(cells[4]),
                away=away,
                away_goals=_int(cells[5]),
                pens_home=pens_h,
                pens_away=pens_a,
                shootout_winner=winner,
                source="fbref",
            ))
    return out


def load_all() -> list[Match]:
    """Every shipped match, oldest first. Raises if a source is not what we think."""
    import pyreadr

    frame = list(pyreadr.read_r(str(MIRROR)).values())[0]
    mirror_2022 = frame[frame["Season_End_Year"] == 2022]
    if len(mirror_2022) and mirror_2022["HomeGoals"].notna().any():
        raise SystemExit(
            "the mirror now carries 2022 scorelines; decide which source wins "
            "before trusting the TSV blindly"
        )
    matches = load_mirror() + load_recent()
    return sorted(matches, key=lambda m: (m.edition, m.date, m.home))


def with_shootout_scores(matches: list[Match]) -> list[Match]:
    """
    The same matches with every shootout's score filled in from the Wikipedia
    parse (wikipedia/wc_events.json, written by wc_wikipedia.py).

    The mirror records who won a 2006-2018 shootout but not the score, so 14
    of the 23 shootouts would otherwise carry a winner and no score. The parse
    only writes a score after checking it agrees with the winner recorded here.
    """
    import dataclasses
    import json

    path = HERE / "wikipedia" / "wc_events.json"
    if not path.exists():
        raise SystemExit(f"{path} is missing — run wc_wikipedia.py first")
    scores = {
        (s["edition"], s["date"], s["home"], s["away"]): (s["home_pens"], s["away_pens"])
        for s in json.loads(path.read_text(encoding="utf-8")).get("shootouts", [])
    }
    out = []
    for m in matches:
        got = scores.get((m.edition, m.date, m.home, m.away))
        if m.shootout_winner and m.pens_home is None and got:
            m = dataclasses.replace(m, pens_home=got[0], pens_away=got[1])
        out.append(m)
    return out


def participants(matches: list[Match], edition: int) -> set[str]:
    """Every nation that played in one edition, as FBref spells it."""
    return {
        team
        for m in matches if m.edition == edition
        for team in (m.home, m.away) if team
    }


if __name__ == "__main__":
    import sys

    sys.stdout.reconfigure(encoding="utf-8")
    all_matches = load_all()
    print(f"{len(all_matches)} matches across {len(EDITIONS)} editions\n")
    for ed in EDITIONS:
        rows = [m for m in all_matches if m.edition == ed]
        played = [m for m in rows if m.played]
        shootouts = [m for m in rows if m.shootout_winner]
        teams = participants(all_matches, ed)
        src = rows[0].source if rows else "-"
        print(f"  {ed}: {len(rows):3d} matches ({len(played)} scored), "
              f"{len(teams)} nations, {len(shootouts)} shootouts   [{src}]")
        rounds = {}
        for m in rows:
            rounds[m.round] = rounds.get(m.round, 0) + 1
        print("        " + ", ".join(f"{r} {n}" for r, n in sorted(rounds.items(), key=lambda kv: -kv[1])))
