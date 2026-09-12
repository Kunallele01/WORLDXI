"""
Adds FIFA 10-14 to fifa/fifa_ratings_normalized.csv from the sofifa roster scrape.

WHY THESE EDITIONS COME FROM A SCRAPE. The canonical Kaggle archive starts at
FIFA 15, and 2009/10-2013/14 need editions 11-15 under the N+1 rule (plus 10
as 2009/10's fallback). sofifa has them, but only through a real signed-in
browser — scripts get a Cloudflare 403 — so the rosters were read in the
page and posted to sofifa_receiver.py, which wrote sofifa/lists.json.

WHAT A ROSTER ROW CARRIES, AND WHAT IT DOESN'T.
  * sofifa id, short name ("W. Rooney"), URL slug ("wayne-rooney"), age,
    overall, positions, club, nationality — all from the league roster pages.
  * NO date of birth and NO positional grid. Old editions publish no per-
    position ratings at all (checked: a FIFA 11 detail page lists attributes
    but no LS/ST/CB grid), so there is nothing to scrape.

HOW THE GAPS ARE FILLED.
  * Identity: the Kaggle archive's player_id IS the sofifa id (Rooney is 54050
    in both), so anyone still in FIFA 15-24 inherits his real long name and
    date of birth by id. Everyone else gets a birth year from his age: sofifa
    ages are as at the edition's autumn release, so FIFA N shows age at
    roughly September of year 2000+N-1. The lookup tolerates a year either
    way, which absorbs the birthday falling before or after that date.
  * Grids are NOT invented here. They are borrowed at lookup time from a later
    edition by the same sofifa id — see FifaLookup._with_grid — so the borrowed
    edition is recorded rather than disguised as this one.

Idempotent: existing edition 10-14 rows are replaced, everything else kept.
"""
from __future__ import annotations

import csv
import json
import shutil
import sys
from pathlib import Path

from roles import _normalize

HERE = Path(__file__).parent
LISTS = HERE / "sofifa" / "lists.json"
NORMALIZED = HERE / "fifa" / "fifa_ratings_normalized.csv"
BACKUP = HERE / "fifa" / "fifa_ratings_normalized.pre_sofifa.bak"
KAGGLE = HERE / "archive" / "male_players.csv"
# Editions that come from the sofifa scrape rather than the Kaggle archive.
# 07 and 11 are the World Cup 2006 and 2010 anchors, 26 is the 2022/2026 one;
# 10 and 12-14 were the club-season expansion. All are rebuilt together, so a
# re-run of this script must be given a file containing every one of them.
SOFIFA_EDITIONS = {"07", "10", "11", "12", "13", "14", "26"}
SLOT_KEYS = ["cb", "fb", "dm", "cm", "cam", "winger", "st"]

csv.field_size_limit(10_000_000)


def kaggle_identities() -> dict[str, tuple[str, int]]:
    """sofifa id -> (long name, birth year), from the earliest edition he appears in."""
    out: dict[str, tuple[str, int]] = {}
    with open(KAGGLE, encoding="utf-8", errors="replace", newline="") as fh:
        for r in csv.DictReader(fh):
            pid, dob = r.get("player_id", ""), r.get("dob", "")
            if pid and pid not in out and len(dob) >= 4 and dob[:4].isdigit():
                out[pid] = (r.get("long_name", ""), int(dob[:4]))
    return out


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    scraped = json.loads(LISTS.read_text(encoding="utf-8"))
    ids = kaggle_identities()

    rows, seen, from_kaggle = [], set(), 0
    for p in scraped:
        key = (p["edition"], p["id"])
        if key in seen or not p.get("overall"):
            continue
        seen.add(key)
        long_name, birth_year = ids.get(p["id"], ("", None))
        if birth_year is None:
            if not p.get("age"):
                continue
            birth_year = 2000 + int(p["edition"]) - 1 - int(p["age"])
        else:
            from_kaggle += 1
        variants = {_normalize(v) for v in (p["short"], p["slug"].replace("-", " "), long_name) if v}
        variants.discard("")
        rows.append([
            p["edition"], p["id"], p["short"], ";".join(sorted(variants)), birth_year,
            p["overall"], p.get("league", ""), p["club"], p.get("nationality", ""),
            ";".join(p.get("positions", [])),
        ] + [""] * len(SLOT_KEYS))

    if not BACKUP.exists():
        shutil.copy(NORMALIZED, BACKUP)
    with open(NORMALIZED, encoding="utf-8", newline="") as fh:
        reader = csv.reader(fh)
        header = next(reader)
        # Replace only the editions this input actually carries. Clearing every
        # scraped edition would delete FIFA 10-14 whenever the file holds just
        # the World Cup ones, and those ratings are what the loaded 2009/10-
        # 2013/14 seasons were built from.
        present = {p["edition"] for p in scraped}
        unknown = present - SOFIFA_EDITIONS
        if unknown:
            raise SystemExit(f"input holds editions this script does not own: {sorted(unknown)}")
        kept = [r for r in reader if r and r[0] not in present]
    with open(NORMALIZED, "w", encoding="utf-8", newline="") as fh:
        w = csv.writer(fh)
        w.writerow(header)
        w.writerows(kept)
        w.writerows(rows)

    per_edition: dict[str, int] = {}
    for r in rows:
        per_edition[r[0]] = per_edition.get(r[0], 0) + 1
    print(f"wrote {len(rows)} sofifa rows ({from_kaggle} with a real date of birth via the "
          f"Kaggle id; the rest from age) and kept {len(kept)} existing rows")
    for e in sorted(per_edition, key=int):
        print(f"   edition {e}: {per_edition[e]}")


if __name__ == "__main__":
    main()
