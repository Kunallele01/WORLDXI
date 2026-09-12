"""
Assembles sofifa/lists.json from roster chunks read out of the browser.

The scrape's rows live in the signed-in sofifa tab. They were written into the
page as plain text, one chunk at a time, and read back with the page-text tool,
whose saved results are the files passed here. Each chunk is bracketed by
"CHUNK i ROWS n OF total" and "END CHUNK i", so a truncated read is detected
rather than silently accepted.
"""
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).parent
OUT = HERE / "sofifa" / "lists.json"
STATE = HERE / "sofifa" / "chunks.json"


def rows_from(path: Path) -> tuple[int, int, int, list[list[str]], bool]:
    raw = path.read_text(encoding="utf-8")
    try:
        text = "".join(part.get("text", "") for part in json.loads(raw))
    except (json.JSONDecodeError, AttributeError, TypeError):
        text = raw
    head = re.search(r"CHUNK (\d+) ROWS (\d+) OF (\d+)", text)
    if not head:
        raise SystemExit(f"{path.name}: no chunk header")
    i, n, total = map(int, head.groups())
    complete = f"END CHUNK {i}" in text
    body = text[head.end():text.find(f"END CHUNK {i}") if complete else len(text)]
    # One row per line, ten fields separated by "|" (no name or club contains one).
    rows = [[c.strip() for c in line.split("|")] for line in body.splitlines() if line.count("|") == 9]
    return i, n, total, rows, complete


def from_tsv(path: Path) -> None:
    """
    The route that worked: the whole roster downloaded from the sofifa tab as one
    tab-separated file (edition, P/L, id, slug, short name, positions, age,
    overall, club, nationality), with the user's permission.
    """
    records, bad = [], 0
    for line in path.read_text(encoding="utf-8").splitlines():
        cells = line.split("	")
        if len(cells) == 10:
            # The club-season scrape: league-filtered, so it carries a P/L flag.
            ed, lg, pid, slug, short, pos, age, ovr, club, nat = cells
            league = "Premier League" if lg == "P" else "La Liga"
        elif len(cells) == 9:
            # The World Cup scrape: every league, so there is no flag to carry.
            ed, pid, slug, short, pos, age, ovr, club, nat = cells
            league = ""
        else:
            bad += 1
            continue
        records.append(dict(
            edition=ed, league=league,
            id=pid, slug=slug, short=short, positions=pos.split() if pos else [],
            age=int(age) if age.isdigit() else None,
            overall=int(ovr) if ovr.isdigit() else None,
            club=club, nationality=nat,
        ))
    # MERGED, not overwritten. sofifa_editions.py rebuilds whichever editions it
    # finds here, so replacing this file with one scrape would drop the ratings
    # the already-loaded 2009/10-2013/14 seasons were built from.
    existing = json.loads(OUT.read_text(encoding="utf-8")) if OUT.exists() else []
    merged = {(r["edition"], r["id"]): r for r in existing}
    merged.update({(r["edition"], r["id"]): r for r in records})
    OUT.write_text(json.dumps(list(merged.values()), ensure_ascii=False), encoding="utf-8")
    print(f"read {len(records)} rows ({bad} malformed skipped); {OUT} now holds "
          f"{len(merged)} across editions "
          f"{sorted({r['edition'] for r in merged.values()}, key=int)}")


def main() -> None:
    sys.stdout.reconfigure(encoding="utf-8")
    if len(sys.argv) == 3 and sys.argv[1] == "--tsv":
        from_tsv(Path(sys.argv[2]))
        return
    state = json.loads(STATE.read_text(encoding="utf-8")) if STATE.exists() else {}
    for arg in sys.argv[1:]:
        i, n, total, rows, complete = rows_from(Path(arg))
        ok = complete and len(rows) == n
        print(f"chunk {i}: header says {n} rows, parsed {len(rows)}, end marker {'present' if complete else 'MISSING'}"
              f" -> {'accepted' if ok else 'REJECTED'}")
        if ok:
            state[str(i)] = {"total": total, "rows": rows}
    STATE.write_text(json.dumps(state, ensure_ascii=False), encoding="utf-8")
    have = sum(len(c["rows"]) for c in state.values())
    total = next(iter(state.values()))["total"] if state else 0
    print(f"collected {have} of {total} rows across chunks {sorted(map(int, state))}")
    if state and have == total:
        records = []
        for c in sorted(state, key=int):
            for ed, lg, pid, slug, short, pos, age, ovr, club, nat in state[c]["rows"]:
                records.append(dict(
                    edition=ed, league="Premier League" if lg == "P" else "La Liga",
                    id=pid, slug=slug, short=short, positions=pos.split() if pos else [],
                    age=int(age) if age.isdigit() else None,
                    overall=int(ovr) if ovr.isdigit() else None,
                    club=club, nationality=nat,
                ))
        OUT.write_text(json.dumps(records, ensure_ascii=False), encoding="utf-8")
        print(f"COMPLETE: wrote {OUT} with {len(records)} rows")


if __name__ == "__main__":
    main()
