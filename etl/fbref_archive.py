"""
Loads the pre-strip FBref advanced stats from the worldfootballR data archive.

WHY AN ARCHIVE AND NOT FBREF ITSELF. FBref stripped its Opta-derived columns
RETROACTIVELY — old seasons included — when it changed data provider. Measured
live on Premier League 2021/22 (546 player rows): every column in the passing
and possession tables reads 0/546, clearances and blocks read 0/546, and xG,
xAG and the progressive columns no longer exist at all. What survives there is
interceptions, tackles won, shots, cards, fouls and crosses.

The worldfootballR project mirrored those tables while they were still
published, so the data is recoverable even though the source page is now
empty. Two eras are mirrored — the Opta-era `fb_big5_advanced_season_stats`
and the StatsBomb-era `fb_big5_advanced_statsbomb` — and BOTH stop at
Season_End_Year 2023, i.e. season 2022/23.

So: 2019/20 through 2022/23 can be made fully rich from here. 2023/24 cannot,
and falls back to Understat (xG, xA, key passes) plus what FBref still serves.
That asymmetry is real and must stay visible rather than being averaged over —
see `pos_stats_source` on player_season_stats.

Files are R .rds, read with pyreadr. They are cached under etl/wfr/ and
gitignored: 8 MB of third-party data that can be re-downloaded.
"""
from __future__ import annotations

import sys
import urllib.request
from pathlib import Path

import pyreadr

CACHE = Path(__file__).parent / "wfr"
BASE = "https://github.com/JaseZiv/worldfootballR_data/raw/master/data/"
OPTA_DIR = "fb_big5_advanced_season_stats/"

#: The tables we take, and what each contributes that nothing else can.
TABLES = {
    "passing": "Cmp%, key passes, xA, progressive passes",
    "possession": "touches, dribbles, carries, progressive carries",
    "defense": "tackles, blocks, interceptions, clearances, errors",
    "misc": "aerials won/lost, recoveries, fouls, crosses",
    "gca": "shot- and goal-creating actions",
    "keepers_adv": "post-shot xG, crosses stopped, sweeper actions",
    "standard": "xG, npxG, xAG",
}

#: Our league labels as they appear in the archive's `Comp` column.
COMPS = {"Premier League", "La Liga"}

#: Season_End_Year -> our season label.
#:
#: End-year 2023 (our 2022/23) is DELIBERATELY EXCLUDED even though the archive
#: contains it. It is a MID-SEASON SNAPSHOT, not a season: max minutes 1080
#: against 3420 in every other year, median 450, and not one player over 3000.
#: Van Dijk reads 990 minutes there against 3060 the season before. The rows
#: look perfectly well-formed, so ingesting them would have quietly loaded
#: about twelve games of every total as if it were a full campaign — the same
#: class of silent corruption as the interceptions-stored-as-tackles bug.
#:
#: Verified complete for the three years kept: each has a 3420-minute maximum
#: and 60-85 players past 3000 minutes.
SEASON_LABELS = {
    2020: "2019/20",
    2021: "2020/21",
    2022: "2021/22",
}


def _download(name: str) -> Path:
    CACHE.mkdir(parents=True, exist_ok=True)
    dest = CACHE / name
    if dest.exists() and dest.stat().st_size > 1000:
        return dest
    url = BASE + OPTA_DIR + name
    req = urllib.request.Request(url, headers={"User-Agent": "dreamxi-etl"})
    dest.write_bytes(urllib.request.urlopen(req, timeout=300).read())
    return dest


def load(table: str):
    """One archive table as a DataFrame, filtered to our leagues and seasons."""
    path = _download(f"big5_player_{table}.rds")
    df = list(pyreadr.read_r(str(path)).values())[0]
    df = df[df["Comp"].isin(COMPS)]
    df = df[df["Season_End_Year"].isin(SEASON_LABELS)]
    return df


def load_all() -> dict:
    out = {}
    for table in TABLES:
        df = load(table)
        out[table] = df
        print(f"  {table:12} {len(df):6} rows  ({TABLES[table]})")
    return out


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    print("Loading pre-strip FBref archive (Premier League + La Liga, 2019/20-2022/23)...")
    tables = load_all()
    df = tables["defense"]
    print("\nsanity — a defender's line that FBref no longer serves at all:")
    row = df[(df["Player"] == "Virgil van Dijk") & (df["Season_End_Year"] == 2020)]
    if len(row):
        r = row.iloc[0]
        print(f"   Van Dijk 2019/20: Tkl={r['Tkl_Tackles']} Int={r['Int']} "
              f"Clr={r['Clr']} Blocks={r['Blocks_Blocks']} Err={r['Err']}")
    misc = tables["misc"]
    row = misc[(misc["Player"] == "Virgil van Dijk") & (misc["Season_End_Year"] == 2020)]
    if len(row):
        r = row.iloc[0]
        print(f"   aerials: won={r['Won_Aerial']} lost={r['Lost_Aerial']} "
              f"win%={r['Won_percent_Aerial']}")
