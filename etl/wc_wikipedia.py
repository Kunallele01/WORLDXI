"""
Real goalscorers, real group letters, and FIFA's 2026 third-place table, from
Wikipedia's World Cup match pages.

    python wc_wikipedia.py      parse, cross-check, write wikipedia/wc_events.json
                                and wikipedia/wc2026_third_place.tsv

WHY WIKIPEDIA. The user chose to show who REALLY scored in every match his XI
did not play. FBref has that data behind Cloudflare; Wikipedia has it in plain
wikitext, fetchable by script, under CC BY-SA 4.0 — which permits reuse with
attribution, unlike scraped commercial stats. Each edition's 8 or 12 group
pages plus its knockout page (and the pages those transclude: every final,
2026's round of 32, and 2022's "Battle of Lusail") hold one football box per
match. The raw pages live in wikipedia/raw/ and are re-fetched only if missing.

WHAT IS TAKEN FROM A BOX: date, the two FIFA team codes, the score, extra time,
the shootout score, and each goal as (scorer, minute, stoppage, kind). An own
goal is listed under the side that BENEFITED, which is also the side it is
credited to here; its kind records that the scorer played for the opponent.

TWO MARKUP GENERATIONS. 2006-2022 pages write minutes through the {{goal}}
template ("{{goal|17||61}}", "{{goal|62|pen.}}"). 2026's group pages write them
as plain text ("9'", "45+2' o.g.", "50" without the apostrophe). Both parse to
the same thing.

MATCHING TO OUR FIXTURES. Boxes name teams by FIFA code, our fixtures by
FBref's name. A box is matched to the fixture played the same day with the
same score (either orientation); where two matches share a day and a score, the
code-to-name map built from the unambiguous ones settles it. The map is then
asserted one-to-one per edition.

VERIFIED BEFORE ANYTHING IS WRITTEN:
  * all 424 fixtures matched to exactly one box;
  * every match's parsed goals add up to its real score for both sides;
  * the group letters partition each edition into fours that agree with the
    groups rebuilt from the fixtures;
  * all 495 rows of the 2026 third-place table assign each qualifying third
    exactly once, and only to a group winner whose published slot allows that
    group (Match 79: "1A v 3C/E/F/H/I", and so on).
"""
from __future__ import annotations

import json
import re
import sys
from collections import Counter, defaultdict
from dataclasses import asdict, dataclass
from datetime import date, timedelta
from pathlib import Path

import wc_fixtures
import wc_groups

HERE = Path(__file__).parent
RAW = HERE / "wikipedia" / "raw"
OUT_EVENTS = HERE / "wikipedia" / "wc_events.json"
OUT_THIRDS = HERE / "wikipedia" / "wc2026_third_place.tsv"

BOX_START = re.compile(r"\{\{\s*(?:#invoke:\s*football box\s*\|\s*main|football ?box)\b", re.I)
FIELD = re.compile(r"^\|\s*([A-Za-z0-9_]+)\s*=(.*)$")
#: The three-letter FIFA code inside any flag markup: "{{fb-rt|GER}}",
#: "{{#invoke:flag|fb|MEX}}", or 2022's "{{#invoke:flagg|main|unpre|avar=fb|QAT}}".
CODE = re.compile(r"\|([A-Z]{3})\s*(?=\||\}\})")

#: Round-of-32 slots with a third-placed opponent, from FIFA's published
#: schedule (Matches 74, 77, 79-82, 85, 87): which groups' thirds each group
#: winner may meet. Used only to cross-check the 495-row table.
THIRD_SLOTS_2026 = {
    "1A": "CEFHI", "1B": "EFGIJ", "1D": "BEFIJ", "1E": "ABCDF",
    "1G": "AEHIJ", "1I": "CDFGH", "1K": "DEIJL", "1L": "EHIJK",
}


@dataclass
class Goal:
    team_code: str
    scorer: str
    minute: int
    stoppage: int | None
    kind: str  # "goal", "penalty", "own_goal"

    @property
    def extra_time(self) -> bool:
        return self.minute > 90


@dataclass
class Box:
    source: str
    day: date
    code1: str
    code2: str
    score1: int
    score2: int
    aet: bool
    pens: tuple[int, int] | None
    goals: list[Goal]


# ----------------------------------------------------------------------------
# Parsing
# ----------------------------------------------------------------------------

def _balanced(text: str, start: int) -> str:
    depth, i = 0, start
    while i < len(text):
        if text.startswith("{{", i):
            depth += 1
            i += 2
        elif text.startswith("}}", i):
            depth -= 1
            i += 2
            if depth == 0:
                return text[start:i]
        else:
            i += 1
    raise ValueError("unbalanced template")


def _fields(body: str) -> dict[str, str]:
    out: dict[str, str] = {}
    current = None
    for line in body.split("\n")[1:]:
        m = FIELD.match(line)
        if m:
            current = m.group(1).lower()
            out[current] = m.group(2)
        elif current is not None:
            out[current] += "\n" + line
    return out


def _score(value: str) -> tuple[int, int] | None:
    found = re.findall(r"(\d+)\s*[–\-]\s*(\d+)", value)
    return (int(found[-1][0]), int(found[-1][1])) if found else None


def _scorer(line: str) -> tuple[str, str]:
    """(display name, the rest of the line holding the minutes)."""
    m = re.search(r"\[\[([^\]|]+)(?:\|([^\]]+))?\]\]", line)
    if m:
        name = (m.group(2) or m.group(1)).strip()
        return name, line[m.end():]
    m = re.match(r"\s*([^\d{]+?)\s*(?=\d|\{\{)", line)
    return (m.group(1).strip() if m else line.strip()), (line[m.end():] if m else "")


def _kind(note: str) -> str:
    note = note.lower().replace(" ", "")
    if "o.g" in note or note.startswith("og"):
        return "own_goal"
    if "pen" in note:
        return "penalty"
    return "goal"


def _minute(token: str) -> tuple[int, int | None] | None:
    m = re.search(r"(\d+)\s*(?:\+\s*(\d+))?", token)
    if not m:
        return None
    return int(m.group(1)), (int(m.group(2)) if m.group(2) else None)


def _goals(value: str, code: str) -> list[Goal]:
    goals: list[Goal] = []
    for raw in value.split("\n"):
        line = raw.strip().lstrip("*").strip()
        if not line or not re.search(r"\d", line):
            continue
        name, rest = _scorer(line)
        templates = re.findall(r"\{\{\s*goal\s*\|([^}]*)\}\}", rest, re.I)
        if templates:
            for params in templates:
                tokens = params.split("|")
                i = 0
                while i < len(tokens):
                    parsed = _minute(tokens[i]) if re.search(r"\d", tokens[i]) else None
                    if parsed is None:
                        i += 1
                        continue
                    note = ""
                    if i + 1 < len(tokens) and not re.search(r"\d", tokens[i + 1]):
                        note = tokens[i + 1]
                        i += 1
                    goals.append(Goal(code, name, parsed[0], parsed[1], _kind(note)))
                    i += 1
        else:
            for part in rest.split(","):
                parsed = _minute(part)
                if parsed:
                    goals.append(Goal(code, name, parsed[0], parsed[1], _kind(part)))
    return goals


def parse_page(path: Path) -> list[Box]:
    text = path.read_text(encoding="utf-8")
    boxes = []
    for m in BOX_START.finditer(text):
        body = _balanced(text, m.start())
        f = _fields(body)
        codes1 = CODE.findall(f.get("team1", ""))
        codes2 = CODE.findall(f.get("team2", ""))
        d = re.findall(r"\d+", f.get("date", ""))
        score = _score(f.get("score", ""))
        if not (codes1 and codes2 and len(d) >= 3 and score):
            continue
        pens = _score(f.get("penaltyscore", "")) if f.get("penaltyscore") else None
        c1, c2 = codes1[0], codes2[0]
        boxes.append(Box(
            source=path.name,
            day=date(int(d[0]), int(d[1]), int(d[2])),
            code1=c1, code2=c2, score1=score[0], score2=score[1],
            aet=f.get("aet", "").strip().lower() in {"yes", "y"} or pens is not None,
            pens=pens,
            goals=_goals(f.get("goals1", ""), c1) + _goals(f.get("goals2", ""), c2),
        ))
    return boxes


# ----------------------------------------------------------------------------
# Matching to our fixtures
# ----------------------------------------------------------------------------

def match_edition(year: int, fixtures: list[wc_fixtures.Match], boxes: list[Box]):
    """(fixture -> (box, flipped)), code -> fixture name."""
    code_to_name: dict[str, str] = {}
    assigned: dict[int, tuple[Box, bool]] = {}
    remaining = list(boxes)

    def candidates(box: Box):
        out = []
        for idx, fx in enumerate(fixtures):
            if idx in assigned:
                continue
            if abs((date.fromisoformat(fx.date) - box.day).days) > 1:
                continue
            for flipped in (False, True):
                s1, s2 = (box.score2, box.score1) if flipped else (box.score1, box.score2)
                c1, c2 = (box.code2, box.code1) if flipped else (box.code1, box.code2)
                if (fx.home_goals, fx.away_goals) != (s1, s2):
                    continue
                if code_to_name.get(c1, fx.home) != fx.home or code_to_name.get(c2, fx.away) != fx.away:
                    continue
                exact = fx.date == box.day.isoformat()
                out.append((idx, flipped, exact))
        return out

    progress = True
    while remaining and progress:
        progress = False
        for box in list(remaining):
            cands = candidates(box)
            exact = [c for c in cands if c[2]]
            pick = exact if len(exact) == 1 else cands
            if len(pick) != 1:
                continue
            idx, flipped, _ = pick[0]
            fx = fixtures[idx]
            c1, c2 = (box.code2, box.code1) if flipped else (box.code1, box.code2)
            code_to_name[c1] = fx.home
            code_to_name[c2] = fx.away
            assigned[idx] = (box, flipped)
            remaining.remove(box)
            progress = True
    return assigned, code_to_name, remaining


# ----------------------------------------------------------------------------
# 2026 third-place table
# ----------------------------------------------------------------------------

def parse_third_table() -> list[tuple[int, str, dict[str, str]]]:
    text = (RAW / "2026_third_place_table.wiki").read_text(encoding="utf-8")
    header_slots = re.findall(r"\|\s*(1[A-L])<br>vs", text)
    rows = []
    for chunk in text.split("\n|-")[1:]:
        num = re.search(r'scope="row"\s*\|\s*(\d+)', chunk)
        if not num:
            continue
        qualifying = "".join(re.findall(r"'''([A-L])'''", chunk))
        assigned = re.findall(r"\b3([A-L])\b", chunk)[:8]
        rows.append((int(num.group(1)), qualifying, dict(zip(header_slots, assigned))))
    return rows


# ----------------------------------------------------------------------------

def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    fixtures = wc_fixtures.load_all()
    problems: list[str] = []
    events: dict = {"editions": {}, "goals": [], "shootouts": [], "source": (
        "Wikipedia, 'YYYY FIFA World Cup Group X', 'knockout stage' and linked match "
        "pages; CC BY-SA 4.0")}

    for year in wc_fixtures.EDITIONS:
        pages = sorted(RAW.glob(f"{year}_*.wiki"))
        pages = [p for p in pages if "third_place" not in p.name]
        boxes, seen = [], set()
        for page in pages:
            for box in parse_page(page):
                key = (box.day, frozenset((box.code1, box.code2)))
                if key in seen:
                    continue
                seen.add(key)
                boxes.append(box)
        ed_fixtures = [f for f in fixtures if f.edition == year]
        assigned, codes, leftover = match_edition(year, ed_fixtures, boxes)
        print(f"{year}: {len(boxes)} boxes from {len(pages)} pages, matched "
              f"{len(assigned)}/{len(ed_fixtures)} fixtures, {len(codes)} team codes")
        for box in leftover:
            problems.append(f"{year}: unmatched box {box.day} {box.code1} {box.score1}-{box.score2} {box.code2} ({box.source})")
        for idx, fx in enumerate(ed_fixtures):
            if idx not in assigned:
                problems.append(f"{year}: fixture without a box: {fx.date} {fx.home} {fx.home_goals}-{fx.away_goals} {fx.away}")
        # A second code for one nation is a page typo, not an ambiguity — 2022
        # writes Spain as "SPA" in one box and "ESP" elsewhere. Each code still
        # names exactly one nation, which is what the matching relies on.
        aliases = {n: sorted(c for c, m in codes.items() if m == n) for n in set(codes.values())}
        for nation, cs in aliases.items():
            if len(cs) > 1:
                print(f"   note: {nation} appears under codes {cs}")
        if len(set(codes.values())) != len(set(f.home for f in ed_fixtures) | set(f.away for f in ed_fixtures)):
            problems.append(f"{year}: codes cover {len(set(codes.values()))} nations, fixtures have more")

        name_of = codes
        # Goals must reproduce the score, side by side.
        for idx, (box, flipped) in assigned.items():
            fx = ed_fixtures[idx]
            tally = Counter(name_of[g.team_code] for g in box.goals)
            if tally.get(fx.home, 0) != fx.home_goals or tally.get(fx.away, 0) != fx.away_goals:
                problems.append(
                    f"{year} {fx.round}: {fx.home} {fx.home_goals}-{fx.away_goals} {fx.away} but goals parse to "
                    f"{tally.get(fx.home, 0)}-{tally.get(fx.away, 0)} ({box.source}: "
                    + "; ".join(f"{g.scorer} {g.minute} {g.kind}" for g in box.goals) + ")")
            if box.pens is not None:
                p1, p2 = (box.pens[1], box.pens[0]) if flipped else box.pens
                if fx.pens_home is not None and (p1, p2) != (fx.pens_home, fx.pens_away):
                    problems.append(f"{year}: shootout {fx.home}-{fx.away} is {fx.pens_home}-{fx.pens_away}, box says {p1}-{p2}")
                if fx.shootout_winner != (fx.home if p1 > p2 else fx.away):
                    problems.append(f"{year}: {fx.home}-{fx.away} shootout {p1}-{p2} disagrees with winner {fx.shootout_winner}")
                # The mirror records who won a 2006-2018 shootout but not the
                # score; every box has it, so every shootout gets one.
                events["shootouts"].append({
                    "edition": year, "date": fx.date, "home": fx.home, "away": fx.away,
                    "home_pens": p1, "away_pens": p2,
                })
            elif fx.shootout_winner:
                problems.append(f"{year}: {fx.home}-{fx.away} went to penalties but its box has no shootout score")
            for g in box.goals:
                events["goals"].append({
                    "edition": year, "date": fx.date, "home": fx.home, "away": fx.away,
                    "team": name_of[g.team_code], "scorer": g.scorer, "minute": g.minute,
                    "stoppage": g.stoppage, "kind": g.kind,
                })

        # Letters from the group pages, checked against the rebuilt groups.
        letters: dict[str, str] = {}
        for page in pages:
            m = re.match(rf"{year}_Group_([A-L])\.wiki", page.name)
            if not m:
                continue
            for box in parse_page(page):
                for code in (box.code1, box.code2):
                    if code in name_of:
                        letters[name_of[code]] = m.group(1)
        rebuilt = wc_groups.groups_of(fixtures, year)
        for group in rebuilt:
            got = {letters.get(t) for t in group}
            if len(got) != 1 or None in got:
                problems.append(f"{year}: rebuilt group {group} carries letters {got}")
        by_letter = Counter(letters.values())
        if any(n != 4 for n in by_letter.values()):
            problems.append(f"{year}: letters do not split into fours: {dict(by_letter)}")
        events["editions"][str(year)] = {"letters": letters, "codes": name_of}

    thirds = parse_third_table()
    print(f"\n2026 third-place table: {len(thirds)} rows")
    if len(thirds) != 495:
        problems.append(f"third-place table has {len(thirds)} rows, not 495")
    combos = set()
    for num, qualifying, assigned in thirds:
        if len(qualifying) != 8 or len(assigned) != 8:
            problems.append(f"row {num}: {qualifying} -> {assigned}")
            continue
        combos.add(qualifying)
        if sorted(assigned.values()) != sorted(qualifying):
            problems.append(f"row {num}: assigns {sorted(assigned.values())} for qualifiers {qualifying}")
        for slot, group in assigned.items():
            if group not in THIRD_SLOTS_2026[slot]:
                problems.append(f"row {num}: {slot} meets 3{group}, outside its published slot {THIRD_SLOTS_2026[slot]}")
    if len(combos) != 495:
        problems.append(f"third-place table covers {len(combos)} distinct combinations, not 495")
    real = thirds[66] if len(thirds) > 66 else None
    print(f"   row 67 (what really happened): {real}")

    kinds = Counter(g["kind"] for g in events["goals"])
    et = sum(1 for g in events["goals"] if g["minute"] > 90)
    print(f"\n{len(events['goals'])} goals: {dict(kinds)}, {et} in extra time")
    top = Counter((g["edition"], g["scorer"], g["team"]) for g in events["goals"] if g["kind"] != "own_goal")
    for ed in wc_fixtures.EDITIONS:
        scored = [(v, k) for k, v in top.items() if k[0] == ed]
        if scored:
            best = max(scored)
            print(f"   {ed} top scorer: {best[1][1]} ({best[1][2]}) {best[0]}")

    if problems:
        print(f"\n{len(problems)} PROBLEMS:")
        for p in problems[:60]:
            print("  - " + p)
        raise SystemExit(1)
    OUT_EVENTS.write_text(json.dumps(events, ensure_ascii=False, indent=1), encoding="utf-8")
    with open(OUT_THIRDS, "w", encoding="utf-8", newline="") as fh:
        fh.write("option\tqualifying_groups\t" + "\t".join(THIRD_SLOTS_2026) + "\n")
        for num, qualifying, assigned in thirds:
            fh.write(f"{num}\t{qualifying}\t" + "\t".join(assigned[s] for s in THIRD_SLOTS_2026) + "\n")
    print(f"\nVERIFIED — wrote {OUT_EVENTS.name} and {OUT_THIRDS.name}")


if __name__ == "__main__":
    main()
