"""
One-off: merges the re-scraped FBref interceptions column into the existing
raw defense files. See parse_raw.parse_defense_compact's docstring for why
interceptions were missing (extraction dropped a column that was never
actually purged, so defenders were rated on ~half their defensive signal).

Alignment strategy: the interceptions were extracted from the same FBref
table, in the same DOM row order, as the original defense files. So they
merge positionally by row index. That is only safe if the row counts AND
the boundary player names match exactly — both are asserted per season and
the script aborts on any mismatch rather than writing misaligned stats.

Idempotent: seasons whose file already carries interceptions are skipped.
"""
from __future__ import annotations

import sys
from pathlib import Path

RAW = Path(__file__).parent / "raw"

# season key -> (defense filename, is_compact)
TARGETS = {
    "pl2324": ("defense_2023-2024_PL.txt", False),
    "pl2223": ("defense_2022-2023_PL.txt", True),
    "pl2122": ("defense_2021-2022_PL.txt", True),
    "pl2021": ("defense_2020-2021_PL.txt", True),
    "pl1920": ("defense_2019-2020_PL.txt", True),
    "laliga2324": ("defense_LaLiga_2023-2024.txt", False),
    "laliga2223": ("defense_2022-2023_LaLiga.txt", True),
    "laliga2122": ("defense_2021-2022_LaLiga.txt", True),
    "laliga2021": ("defense_2020-2021_LaLiga.txt", True),
    "laliga1920": ("defense_2019-2020_LaLiga.txt", True),
}

APPLY = "--apply" in sys.argv


def load_interceptions() -> dict[str, dict]:
    text = (RAW / "_interceptions_by_season.txt").read_text(encoding="utf-8")
    out: dict[str, dict] = {}
    lines = [l for l in text.splitlines() if l.strip()]
    for i in range(0, len(lines), 2):
        header, data = lines[i], lines[i + 1]
        assert header.startswith("###"), f"bad header: {header[:40]}"
        parts = header[3:].split("|")
        key = parts[0]
        meta = dict(p.split("=", 1) for p in parts[1:])
        vals = [int(v) for v in data.split(",")]
        assert len(vals) == int(meta["n"]), (
            f"{key}: declared n={meta['n']} but parsed {len(vals)} values"
        )
        out[key] = dict(values=vals, first=meta["first"], last=meta["last"])
    return out


def player_names_compact(lines: list[str]) -> list[str]:
    return [l.split("|")[0] for l in lines]


def player_names_verbose(lines: list[str]) -> list[str]:
    """
    "Rk Player Nation Pos Squad ... Matches" — the player name sits between
    the rank and the nation token. Reuses parse_raw's own NATION_RE rather
    than a local copy: country codes are 2 OR 3 lowercase letters ("es ESP"
    but "eng ENG"), and a hand-rolled 2-letter-only version silently failed
    on every English player.
    """
    from parse_raw import NATION_RE

    names = []
    for l in lines:
        m = NATION_RE.search(l)
        before = l[: m.start()].strip() if m else ""
        _, _, player = before.partition(" ")
        names.append(player.strip() if player else "?")
    return names


total_ok = 0
for key, (fname, compact) in TARGETS.items():
    path = RAW / fname
    if not path.exists():
        print(f"  SKIP {key}: {fname} not found")
        continue
    raw_lines = [l for l in path.read_text(encoding="utf-8").splitlines() if l.strip()]
    body = [l for l in raw_lines if not l.startswith("Rk ")]

    inter = load_interceptions().get(key)
    if inter is None:
        print(f"  SKIP {key}: no interceptions data")
        continue

    # idempotency check
    if compact and body and len(body[0].split("|")) == 4:
        print(f"  SKIP {key}: already has interceptions")
        continue

    names = player_names_compact(body) if compact else player_names_verbose(body)

    # --- verification gate: abort rather than write misaligned data ---
    problems = []
    if len(body) != len(inter["values"]):
        problems.append(f"row count {len(body)} != interceptions {len(inter['values'])}")
    if names and names[0] != inter["first"]:
        problems.append(f"first player {names[0]!r} != {inter['first']!r}")
    if names and names[-1] != inter["last"]:
        problems.append(f"last player {names[-1]!r} != {inter['last']!r}")
    if problems:
        print(f"  FAIL {key} ({fname}): " + "; ".join(problems))
        continue

    merged = []
    for line, iv in zip(body, inter["values"]):
        if compact:
            merged.append(f"{line}|{iv}")
        else:
            # insert before the trailing "Matches" token
            merged.append(line[: line.rfind("Matches")].rstrip() + f" {iv} Matches")

    print(f"  OK   {key} ({fname}): {len(merged)} rows verified"
          + ("" if APPLY else " [dry run]"))
    if APPLY:
        header = [l for l in raw_lines if l.startswith("Rk ")]
        if header:
            header = [header[0].replace(" Matches", " Int Matches")]
        path.write_text("\n".join(header + merged) + "\n", encoding="utf-8")
    total_ok += 1

print(f"\n{'MERGED' if APPLY else 'VERIFIED (dry run — pass --apply to write)'}: {total_ok}/{len(TARGETS)} seasons")
