"""
European Cup (Champions League format) fixtures for one season, from Wikipedia.

    python ec_fixtures.py 2023        fetch (if missing), parse, assert, write
                                      wikipedia/ec_matches_2023.json

WHY WIKIPEDIA. FBref is behind Cloudflare; Wikipedia's season pages carry one
football box per match in plain wikitext (CC BY-SA 4.0, attribution required,
same as the World Cup data). Raw pages cache in wikipedia/raw/ec/.

WHAT IS TAKEN: stage, group, leg, date, the two clubs, the 90-minute-or-ET
score, whether it went to extra time, the shootout score, and the venue.
Group letters come from the page's own "Group X" headings, never inferred.

VERIFIED BEFORE ANYTHING IS WRITTEN (2023/24, the pilot season):
  * 96 group matches, 16 round of 16, 8 quarter-final, 4 semi-final, 1 final;
  * every club plays exactly 6 group matches; 4 clubs per group;
  * knockout ties are exactly paired, two legs each (the final is one);
  * the group tables rebuilt here (points, then head-to-head, then overall
    goal difference and goals) put the clubs in the order EXPECTED_FOURTH names.
"""
from __future__ import annotations

import json
import re
import sys
import urllib.parse
import urllib.request
from collections import defaultdict
from dataclasses import asdict, dataclass
from pathlib import Path

HERE = Path(__file__).parent
RAW = HERE / "wikipedia" / "raw" / "ec"
UA = "EraXI-etl/1.0 (personal project; github.com/Kunallele01/WORLDXI)"

# Group-stage clubs that finished 4th, i.e. the eight clubs the user's XI may
# replace. Used only as an assertion, never as an input.
EXPECTED_FOURTH = {
    2023: {"A": "Manchester United", "B": "Sevilla", "C": "Union Berlin", "D": "Red Bull Salzburg",
           "E": "Celtic", "F": "Newcastle United", "G": "Red Star Belgrade", "H": "Antwerp"},
}

# Wikipedia's display names -> one canonical name per club. Everything not listed
# keeps the displayed text.
ALIASES = {
    "Bayern Munich": "Bayern Munich", "Man United": "Manchester United",
    "Paris Saint-Germain": "Paris Saint-Germain", "Internazionale": "Inter Milan",
    "Inter": "Inter Milan", "Milan": "AC Milan", "Atlético Madrid": "Atlético Madrid",
    "Atlético de Madrid": "Atlético Madrid", "Borussia Dortmund": "Borussia Dortmund",
    "RB Leipzig": "RB Leipzig", "Red Star Belgrade": "Red Star Belgrade",
    "Crvena zvezda": "Red Star Belgrade", "Union Berlin": "Union Berlin",
    "1. FC Union Berlin": "Union Berlin", "Copenhagen": "Copenhagen", "Real Sociedad": "Real Sociedad",
}


@dataclass
class Match:
    season: int
    stage: str          # "group" | "r16" | "qf" | "sf" | "final"
    group: str | None
    leg: int            # 1 or 2 (group: 1 = first meeting; final: 1)
    date: str
    home: str
    away: str
    home_goals: int
    away_goals: int
    extra_time: bool
    shootout: str | None    # "4-2" from the HOME side's view, or None
    venue: str


def fetch(title: str) -> str:
    name = title.replace(" ", "_").replace("–", "-").replace("/", "_") + ".txt"
    path = RAW / name
    if path.exists():
        return path.read_text(encoding="utf-8")
    RAW.mkdir(parents=True, exist_ok=True)
    q = urllib.parse.urlencode({"action": "parse", "page": title, "prop": "wikitext",
                                "format": "json", "formatversion": "2", "redirects": "1"})
    req = urllib.request.Request("https://en.wikipedia.org/w/api.php?" + q, headers={"User-Agent": UA})
    text = json.load(urllib.request.urlopen(req, timeout=60))["parse"]["wikitext"]
    path.write_text(text, encoding="utf-8")
    return text


BOX = re.compile(r"\{\{\s*(?:#invoke:\s*football box\s*\|\s*main|football ?box)\b", re.I)
FIELD = re.compile(r"^\|\s*([A-Za-z0-9_]+)\s*=(.*)$")


def balanced(text: str, start: int) -> str:
    depth, i = 0, start
    while i < len(text):
        if text.startswith("{{", i):
            depth += 1; i += 2
        elif text.startswith("}}", i):
            depth -= 1; i += 2
            if depth == 0:
                return text[start:i]
        else:
            i += 1
    raise ValueError("unbalanced box")


def fields(body: str) -> dict[str, str]:
    out, key = {}, None
    for line in body.splitlines()[1:]:
        m = FIELD.match(line)
        if m:
            key = m.group(1).lower(); out[key] = m.group(2).strip()
        elif key:
            out[key] += "\n" + line
    return out


def club(raw: str) -> str:
    s = re.sub(r"\{\{\s*fbaicon[^}]*\}\}", "", raw, flags=re.I)
    s = re.sub(r"<!--.*?-->|<ref.*?(?:/>|</ref>)", "", s, flags=re.S)
    m = re.search(r"\[\[(?:[^\]|]*\|)?([^\]]+)\]\]", s)
    name = (m.group(1) if m else s).strip()
    return ALIASES.get(name, name)


def parse_date(v: str) -> str:
    m = re.search(r"Start date\|(\d{4})\|(\d{1,2})\|(\d{1,2})", v)
    return f"{m.group(1)}-{int(m.group(2)):02d}-{int(m.group(3)):02d}"


def parse_boxes(text: str):
    """Yield (heading_stack, fields) for every football box, in page order."""
    heads: list[str] = []
    pos = 0
    for m in re.finditer(r"^(={2,4})\s*([^=\n]+?)\s*\1\s*$|" + BOX.pattern, text, re.M | re.I):
        if m.group(2):
            lvl = len(m.group(1))
            heads = heads[: lvl - 2] + [m.group(2).strip()]
            continue
        yield list(heads), fields(balanced(text, m.start()))


def score(v: str):
    m = re.search(r"(\d+)\s*[–-]\s*(\d+)", v)
    return (int(m.group(1)), int(m.group(2))) if m else None


def to_match(season: int, stage: str, group, f: dict[str, str]) -> Match:
    sc = score(f["score"])
    assert sc, f"no score: {f}"
    pen = None
    if f.get("penaltyscore"):
        p = score(f["penaltyscore"])
        pen = f"{p[0]}-{p[1]}" if p else None
    venue = re.sub(r"\[\[(?:[^\]|]*\|)?([^\]]+)\]\]", r"\1", f.get("stadium", "")).strip()
    return Match(season, stage, group, 1, parse_date(f["date"]), club(f["team1"]), club(f["team2"]),
                 sc[0], sc[1], f.get("aet", "").lower() == "yes" or bool(pen), pen, venue)


STAGE_BY_HEADING = {"Round of 16": "r16", "Quarter-finals": "qf", "Semi-finals": "sf"}


def collect(season: int) -> list[Match]:
    tag = f"{season}–{str(season + 1)[2:]}"
    matches: list[Match] = []
    for heads, f in parse_boxes(fetch(f"{tag} UEFA Champions League group stage")):
        g = next((h.split()[1] for h in heads if re.fullmatch(r"Group [A-H]", h)), None)
        assert g, heads
        matches.append(to_match(season, "group", g, f))
    for heads, f in parse_boxes(fetch(f"{tag} UEFA Champions League knockout phase")):
        stage = next((STAGE_BY_HEADING[h] for h in heads if h in STAGE_BY_HEADING), None)
        if stage and "score" in f:
            matches.append(to_match(season, stage, None, f))
    for _, f in parse_boxes(fetch(f"{season + 1} UEFA Champions League final")):
        if "score" in f and "date" in f:
            matches.append(to_match(season, "final", None, f)); break

    # Legs: the earlier of a pair is leg 1; group matches the same way.
    seen: dict[tuple, list[Match]] = defaultdict(list)
    for m in sorted(matches, key=lambda m: m.date):
        seen[(m.stage, m.group, frozenset((m.home, m.away)))].append(m)
    for ms in seen.values():
        for i, m in enumerate(ms, 1):
            m.leg = i
    return matches


def tables(matches: list[Match]) -> dict[str, list[tuple]]:
    out = {}
    for g in sorted({m.group for m in matches if m.stage == "group"}):
        gm = [m for m in matches if m.group == g and m.stage == "group"]
        clubs = sorted({m.home for m in gm} | {m.away for m in gm})

        def stat(cs, sub):
            r = {c: [0, 0, 0] for c in cs}      # pts, gd, gf
            for m in sub:
                if m.home in r and m.away in r:
                    for c, gf, ga in ((m.home, m.home_goals, m.away_goals), (m.away, m.away_goals, m.home_goals)):
                        r[c][0] += 3 if gf > ga else 1 if gf == ga else 0
                        r[c][1] += gf - ga; r[c][2] += gf
            return r
        overall = stat(clubs, gm)
        # UEFA: points; then among the tied clubs only: h2h points, h2h GD, h2h GF; then overall GD, GF.
        order, by_pts = [], defaultdict(list)
        for c in clubs:
            by_pts[overall[c][0]].append(c)
        for pts in sorted(by_pts, reverse=True):
            tied = by_pts[pts]
            h = stat(tied, gm) if len(tied) > 1 else {tied[0]: [0, 0, 0]}
            tied.sort(key=lambda c: (h[c][0], h[c][1], h[c][2], overall[c][1], overall[c][2]), reverse=True)
            order += tied
        out[g] = [(c, *overall[c]) for c in order]
    return out


def official_order(season: int) -> dict[str, list[str]]:
    """Wikipedia's own final group order (the sports table's team_order), as ground truth."""
    tag = f"{season}–{str(season + 1)[2:]}"
    text = fetch(f"Template:{tag} UEFA Champions League group tables")
    out = {}
    for m in re.finditer(r"Group ([A-H])\s*=\s*\{\{#invoke:Sports table", text):
        block = text[m.start(): text.find("\n}}", m.start())]
        order = re.search(r"\|team_order\s*=\s*([^\n]+)", block).group(1).split(",")
        names = {c: club(n) for c, n in re.findall(r"\|name_([A-Z0-9]+)\s*=\s*([^\n]+)", block)}
        out[m.group(1)] = [names[c.strip()] for c in order]
    return out


def verify(season: int, matches: list[Match]) -> dict[str, list[tuple]]:
    from collections import Counter
    n = Counter(m.stage for m in matches)
    assert n == {"group": 96, "r16": 16, "qf": 8, "sf": 4, "final": 1}, n
    played = Counter()
    for m in matches:
        if m.stage == "group":
            played[m.home] += 1; played[m.away] += 1
    assert len(played) == 32 and set(played.values()) == {6}, played
    for stage, ties in (("r16", 8), ("qf", 4), ("sf", 2)):
        pairs = Counter(frozenset((m.home, m.away)) for m in matches if m.stage == stage)
        assert len(pairs) == ties and set(pairs.values()) == {2}, (stage, pairs)
    tb = tables(matches)
    for g, order in official_order(season).items():
        mine = [r[0] for r in tb[g]]
        assert mine == order, (g, mine, order)
    if season in EXPECTED_FOURTH:
        for g, t in tb.items():
            assert t[3][0] == EXPECTED_FOURTH[season][g], (g, t)
    return tb


def main() -> None:
    season = int(sys.argv[1]) if len(sys.argv) > 1 else 2023
    ms = collect(season)
    tb = verify(season, ms)
    out = HERE / "wikipedia" / f"ec_matches_{season}.json"
    out.write_text(json.dumps({"matches": [asdict(m) for m in ms],
                               "group_tables": {g: [list(r) for r in t] for g, t in tb.items()}},
                              ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"{season}/{season + 1}: {len(ms)} matches -> {out.name}")
    for g, t in tb.items():
        print(f" Group {g}: " + " > ".join(f"{c} {p}" for c, p, *_ in t))
    shoot = [m for m in ms if m.shootout]
    print("shootouts:", [(m.home, m.away, m.shootout) for m in shoot])


if __name__ == "__main__":
    main()
