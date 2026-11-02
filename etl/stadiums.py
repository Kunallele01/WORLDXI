"""
Stadium and city for every club in every season of the database.

SOURCE: the "Stadia and locations" table on each league season's Wikipedia page
(CC BY-SA 4.0, the same licence and credit as the World Cup data). One page per
league per season, 30 pages for the two leagues x fifteen seasons.

WHY PER SEASON AND NOT PER CLUB: grounds change. West Ham left the Boleyn Ground
for the London Stadium in 2016, Tottenham spent 2017-19 at Wembley, Atletico left
the Calderon for the Metropolitano in 2017. A per-club answer would be wrong for
some of the seasons it was asked about.

THE NAME IS WHAT THAT SEASON'S PAGE CALLS THE GROUND, not a hand-typed value.
Where Wikipedia carries a sponsor name for a season that is what shows, because
that is what the ground was called then.

Reads the club names from the live database (anon key, public read) so a club is
matched to the exact string the app holds. Anything it cannot match is printed
and left OUT of the output, never guessed.

    python etl/stadiums.py            # fetch (cached) + parse + write etl/stadiums.tsv
    python etl/stadiums.py --refresh  # ignore the cache and re-fetch

Needs SUPABASE_URL and SUPABASE_ANON_KEY (the same ones the app uses) in the
environment, or in local.properties at the repo root.
"""
import difflib
import json
import os
import re
import sys
import time
import unicodedata
import urllib.parse
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RAW = os.path.join(ROOT, "etl", "wikipedia", "raw", "stadiums")   # gitignored
OUT = os.path.join(ROOT, "etl", "stadiums.tsv")
UA = "EraXI-etl/1.0 (https://github.com/Kunallele01/WORLDXI)"
LEAGUE_PAGE = {"Premier League": "Premier_League", "La Liga": "La_Liga"}


def _props():
    env = {k: os.environ[k] for k in ("SUPABASE_URL", "SUPABASE_ANON_KEY") if k in os.environ}
    path = os.path.join(ROOT, "local.properties")
    if len(env) < 2 and os.path.exists(path):
        for line in open(path, encoding="utf8"):
            if "=" in line and not line.lstrip().startswith("#"):
                k, v = line.strip().split("=", 1)
                if k in ("SUPABASE_URL", "SUPABASE_ANON_KEY"):
                    env.setdefault(k, v)
    return env["SUPABASE_URL"], env["SUPABASE_ANON_KEY"]


def rest(path):
    url, key = _props()
    rows, offset = [], 0
    while True:
        req = urllib.request.Request(
            f"{url}/rest/v1/{path}&limit=1000&offset={offset}",
            headers={"apikey": key, "Authorization": f"Bearer {key}"})
        page = json.load(urllib.request.urlopen(req, timeout=60))
        rows += page
        if len(page) < 1000:
            return rows
        offset += 1000


def wikitext(title, refresh):
    os.makedirs(RAW, exist_ok=True)
    cache = os.path.join(RAW, re.sub(r"[^\w.-]", "_", title) + ".txt")
    if os.path.exists(cache) and not refresh:
        return open(cache, encoding="utf8").read()
    q = urllib.parse.urlencode({"action": "parse", "page": title, "prop": "wikitext",
                                "format": "json", "formatversion": 2, "redirects": 1})
    req = urllib.request.Request(f"https://en.wikipedia.org/w/api.php?{q}", headers={"User-Agent": UA})
    text = json.load(urllib.request.urlopen(req, timeout=60))["parse"]["wikitext"]
    open(cache, "w", encoding="utf8").write(text)
    time.sleep(0.5)   # be polite
    return text


# --------------------------------------------------------------------------
# wikitext -> plain text
# --------------------------------------------------------------------------
def clean(cell):
    s = cell
    s = re.sub(r"<ref[^>]*/>", "", s)
    s = re.sub(r"<ref[^>]*>.*?</ref>", "", s, flags=re.S)
    s = re.sub(r"<!--.*?-->", "", s, flags=re.S)
    # {{small|(Holloway)}} is a district note, not part of the city name
    s = re.sub(r"\{\{\s*small\s*\|.*?\}\}", "", s, flags=re.I | re.S)
    s = re.sub(r"\{\{\s*(?:nowrap|nobr|nobreak)\s*\|(.*?)\}\}", r"\1", s, flags=re.I | re.S)
    for _ in range(3):   # nested templates (flagicon etc.) are decoration
        s = re.sub(r"\{\{[^{}]*\}\}", "", s)
    s = re.sub(r"\[\[(?:[^\]|]*\|)?([^\]]*)\]\]", r"\1", s)
    s = re.sub(r"<br\s*/?>", " ", s, flags=re.I)
    s = re.sub(r"<[^>]+>", "", s)
    s = s.replace("&nbsp;", " ").replace("&amp;", "&").replace("'''", "").replace("''", "")
    s = re.sub(r"\(\s*\)", "", s)
    return re.sub(r"\s+", " ", s).strip(" ,")


def strip_cell_attrs(cell):
    """`style="..." | text` -> `text`; a bare `[[A|B]]` has a pipe too, so only
    strip when the part before the pipe looks like HTML attributes."""
    m = re.match(r'^\s*((?:[a-z-]+\s*=\s*(?:"[^"]*"|\'[^\']*\'|[^\s|]+)\s*)+)\|(?!\|)(.*)$', cell, re.S | re.I)
    return m.group(2) if m else cell


def parse_table(text):
    """The first wikitable under a Stadia / Stadiums / Venues heading, as dicts."""
    m = re.search(r"^=+\s*(Stadia|Stadiums?|Venues)[^=\n]*=+\s*$", text, re.M | re.I)
    if not m:
        return None
    start = text.find("{|", m.end())
    end = text.find("\n|}", start)
    if start < 0 or end < 0:
        return None
    body = text[start:end]
    rows = re.split(r"\n\|-[^\n]*", body)
    header = None
    out = []
    for row in rows:
        row = row.strip()
        if not row:
            continue
        if row.startswith("{|"):
            row = re.sub(r"^\{\|[^\n]*\n?", "", row)
            if not row.strip():
                continue
        if row.startswith("!"):
            cells = re.split(r"\n!|!!", row[1:])
            header = [clean(strip_cell_attrs(c)).lower() for c in cells]
            continue
        cells = re.split(r"\n\||\|\|", row[1:] if row.startswith("|") else row)
        raw = [strip_cell_attrs(c) for c in cells]
        cells = [clean(c) for c in raw]
        if header and len(cells) == len(header):
            d = dict(zip(header, cells))
            d["_raw"] = dict(zip(header, raw))
            out.append(d)
        elif not header and len(cells) >= 3:
            out.append({"team": cells[0], "location": cells[1], "stadium": cells[2],
                        "_raw": {"stadium": raw[2]}})
    return out


def column(row, exact, loose):
    """A header equal to one of [exact] wins; otherwise one merely containing a
    [loose] word. "Location of stadium" contains 'stadium' but is the CITY, which
    is why exact is tried first."""
    keys = [k for k in row if k != "_raw"]
    for k in keys:
        if k.strip(" :") in exact:
            return k
    for k in keys:
        if any(n in k for n in loose):
            return k
    return None


def full_name(raw_cell, shown):
    """The ground's name as Wikipedia's article title has it, when that only
    EXTENDS what the table shows.

    The table abbreviates ("Juegos Mediterraneos", "Santiago Bernabeu"); the
    link target is the fuller form ("Estadio de los Juegos Mediterraneos").
    When the target does not contain what the table shows (a sponsor name from
    that season, linked to the ground's current article) the table text stands:
    it is what the ground was called then."""
    m = re.search(r"\[\[([^\]|]+)", raw_cell)
    if not m:
        return shown
    target = re.sub(r"\s*\([^)]*\)\s*$", "", m.group(1)).strip()
    return target if norm(shown) and norm(shown) in norm(target) else shown


# The city a reader recognises, where Wikipedia gives the district or borough the
# ground technically stands in. Each of these is a judgement, listed so it can be
# argued with: Old Trafford is in Trafford, not the City of Manchester, but
# nobody says "Stretford" when they mean the match.
CITY = {
    "Falmer": "Brighton", "Stretford": "Manchester", "Trafford": "Manchester",
    "Greater Manchester": "Manchester", "West Bridgford": "Nottingham",
    "Seville": "Sevilla", "Palma de Mallorca": "Palma", "Vila-real": "Villarreal",
}


def tidy_city(city):
    city = re.sub(r"\s*\(.*?\)", "", city).strip()
    return CITY.get(city, city)


# --------------------------------------------------------------------------
# matching wikipedia team names to the database's club names
# --------------------------------------------------------------------------
NOISE = re.compile(r"\b(f\.?c\.?|a\.?f\.?c\.?|c\.?f\.?|c\.?d\.?|u\.?d\.?|s\.?d\.?|r\.?c\.?d\.?|"
                   r"a\.?s\.?|club|de|fútbol|futbol|real)\b")


def norm(name):
    s = unicodedata.normalize("NFKD", name).encode("ascii", "ignore").decode().lower()
    s = NOISE.sub(" ", s)
    s = re.sub(r"[^a-z0-9 ]", " ", s)
    return re.sub(r"\s+", " ", s).strip()


# The database holds the short forms Wikipedia spells out.
ALIAS = {"wolverhampton wanderers": "wolves", "queens park rangers": "qpr"}


def match(team, db_names):
    exact = {norm(n): n for n in db_names}
    t = ALIAS.get(norm(team), norm(team))
    if t in exact:
        return exact[t]
    close = difflib.get_close_matches(t, list(exact), n=1, cutoff=0.8)
    if close:
        return exact[close[0]]
    for k, v in exact.items():     # "Deportivo La Coruna" vs "Deportivo"
        if k and (k in t or t in k) and min(len(k), len(t)) >= 5:
            return v
    return None


KOTLIN_OUT = os.path.join(ROOT, "app", "src", "main", "java", "com", "dreamxi", "app", "sim", "StadiumData.kt")


def kt(s):
    """A Kotlin string literal. `$` must be escaped or Kotlin reads a template."""
    return json.dumps(s, ensure_ascii=False).replace("$", chr(92) + "$")


def write_kotlin(lines):
    """GENERATED FILE: edit this script, not StadiumData.kt."""
    body = []
    for line in lines:
        league, season, club, stadium, city = line.split("\t")
        body.append(f"    {kt(league + '|' + season + '|' + club)} to Venue({kt(stadium)}, {kt(city)}),")
    src = (
        "package com.dreamxi.app.sim\n\n"
        "// GENERATED by etl/stadiums.py from the \"Stadia and locations\" tables on each league\n"
        "// season's Wikipedia page (CC BY-SA 4.0). Do not edit by hand: change the script and\n"
        "// re-run it. One entry per club per season, because grounds change.\n\n"
        "internal val STADIUMS: Map<String, Venue> = mapOf(\n"
        + "\n".join(body)
        + "\n)\n"
    )
    with open(KOTLIN_OUT, "w", encoding="utf8", newline="\n") as f:
        f.write(src)


def main():
    refresh = "--refresh" in sys.argv
    leagues = {r["id"]: r["name"] for r in rest("leagues?select=id,name")}
    seasons = {r["id"]: r for r in rest("seasons?select=id,league_id,label,start_year")}
    by_season = {}
    for r in rest("club_seasons?select=season_id,club_name"):
        by_season.setdefault(r["season_id"], []).append(r["club_name"])

    lines, problems = [], []
    for sid, s in sorted(seasons.items(), key=lambda kv: (kv[1]["league_id"], kv[1]["start_year"])):
        league = leagues[s["league_id"]]
        y = s["start_year"]
        title = f"{y}–{str(y + 1)[2:]}_{LEAGUE_PAGE[league]}"
        rows = parse_table(wikitext(title, refresh))
        if not rows:
            problems.append(f"{league} {s['label']}: NO TABLE FOUND on {title}")
            continue
        db_names = by_season.get(sid, [])
        seen = set()
        for row in rows:
            tk = column(row, {"team", "club"}, ("team", "club"))
            sk = column(row, {"stadium", "stadiums", "ground", "venue"}, ("stadium", "ground", "venue"))
            ck = column(row, {"location", "city", "town", "home city"}, ("location", "city", "town"))
            if not (tk and sk and ck) or sk == ck:
                continue
            team, city = row[tk], tidy_city(row[ck])
            stadium = full_name(row.get("_raw", {}).get(sk, ""), row[sk])
            if not (team and stadium and city):
                continue
            club = match(team, db_names)
            if club is None:
                problems.append(f"{league} {s['label']}: no DB club for wikipedia team '{team}'")
                continue
            seen.add(club)
            lines.append("\t".join([league, s["label"], club, stadium, city]))
        for missing in sorted(set(db_names) - seen):
            problems.append(f"{league} {s['label']}: DB club '{missing}' got no stadium")

    with open(OUT, "w", encoding="utf8", newline="\n") as f:
        f.write("league\tseason\tclub\tstadium\tcity\n")
        f.write("\n".join(lines) + "\n")
    write_kotlin(lines)
    print(f"wrote {len(lines)} rows of {sum(len(v) for v in by_season.values())} club-seasons -> {OUT}")
    print(f"{len(problems)} problems")
    for p in problems:
        print("  " + p)


if __name__ == "__main__":
    main()
