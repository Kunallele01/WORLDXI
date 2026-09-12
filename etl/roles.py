"""
Maps each player-season to a specific position ROLE (one of the 8 groups
from PROJECT_SPEC_v2.md §7: GK, CB, FB, DM, CM, CAM, Winger, ST), sourced
from Transfermarkt squad pages rather than FBref's coarse position tags
(FBref only distinguishes DF/MF/FW/GK, sometimes with a secondary tag —
not enough to tell a CB from a full-back, or a striker from a winger).

This is a SEPARATE concern from attributes.py's position grouping (which is
just for fair percentile comparison and stays at the coarse 4-group level —
see the conversation this was designed in). Roles here matter for a
different reason: which formation SLOT a player is eligible to fill during
the draft (§2), so a "left winger" round doesn't fill with pure strikers
and a midfield trio doesn't come out as three indistinguishable CM clones.

Name matching between sources is fuzzy on purpose: Transfermarkt and FBref
don't always agree on how a player's name is written (nicknames vs full
names — "Danilo" vs "Danilo Santos", "Toti" vs "Toti Gomes"; accents —
"Josko Gvardiol" vs "Joško Gvardiol"; name order — "Heung-min Son" vs
"Son Heung-min"). The matching cascade below handles all of these.
"""
from __future__ import annotations

import re
import unicodedata
from pathlib import Path

RAW_DIR = Path(__file__).parent / "raw"

TM_CLUB_ID_TO_FBREF_NAME_PL = {
    "11": "Arsenal", "29": "Everton", "31": "Liverpool", "148": "Tottenham",
    "281": "Manchester City", "350": "Sheffield United", "379": "West Ham",
    "405": "Aston Villa", "543": "Wolves", "631": "Chelsea", "703": "Nottingham",
    "762": "Newcastle", "873": "Crystal Palace", "931": "Fulham",
    "985": "Manchester Utd", "989": "Bournemouth", "1031": "Luton Town",
    "1132": "Burnley", "1148": "Brentford", "1237": "Brighton",
}

TM_CLUB_ID_TO_FBREF_NAME_PL_2022_23 = {
    # Club composition differs from the 2023/24 pilot map above (promotion/
    # relegation): includes Leicester City, Leeds United, Southampton;
    # excludes Luton Town, Burnley, Sheffield United (not in the league
    # that season). Same club can have a different TM id across seasons if
    # it changed divisions in between (not the case for any of these 20).
    "11": "Arsenal", "29": "Everton", "31": "Liverpool", "148": "Tottenham",
    "180": "Southampton", "281": "Manchester City", "379": "West Ham",
    "399": "Leeds United", "405": "Aston Villa", "543": "Wolves",
    "631": "Chelsea", "703": "Nottingham", "762": "Newcastle",
    "873": "Crystal Palace", "931": "Fulham", "985": "Manchester Utd",
    "989": "Bournemouth", "1003": "Leicester City", "1148": "Brentford",
    "1237": "Brighton",
}

TM_CLUB_ID_TO_FBREF_NAME_PL_2021_22 = {
    # 2021/22 PL clubs: includes Leicester City, Leeds United, Southampton,
    # Norwich City, Watford, Burnley; excludes Luton Town, Sheffield United,
    # Fulham, Bournemouth, Nottingham (not in the league that season).
    "11": "Arsenal", "29": "Everton", "31": "Liverpool", "148": "Tottenham",
    "180": "Southampton", "281": "Manchester City", "379": "West Ham",
    "399": "Leeds United", "405": "Aston Villa", "543": "Wolves",
    "631": "Chelsea", "762": "Newcastle", "873": "Crystal Palace",
    "985": "Manchester Utd", "1003": "Leicester City", "1010": "Watford",
    "1123": "Norwich City", "1132": "Burnley", "1148": "Brentford",
    "1237": "Brighton",
}

TM_CLUB_ID_TO_FBREF_NAME_PL_2020_21 = {
    # 2020/21 PL clubs: includes Leicester City, Leeds United, Southampton,
    # Fulham, Sheffield United, West Brom; excludes Norwich City, Watford,
    # Burnley stays, Bournemouth/Aston Villa stays — squads differ from the
    # 2021/22 and 2022/23 maps above (promotion/relegation churn each season).
    "11": "Arsenal", "29": "Everton", "31": "Liverpool", "148": "Tottenham",
    "180": "Southampton", "281": "Manchester City", "350": "Sheffield United",
    "379": "West Ham", "399": "Leeds United", "405": "Aston Villa",
    "543": "Wolves", "631": "Chelsea", "762": "Newcastle",
    "873": "Crystal Palace", "931": "Fulham", "984": "West Brom",
    "985": "Manchester Utd", "1003": "Leicester City", "1132": "Burnley",
    "1237": "Brighton",
}

TM_CLUB_ID_TO_FBREF_NAME_PL_2019_20 = {
    # 2019/20 PL clubs: includes Bournemouth, Watford, Norwich City,
    # Sheffield United; excludes West Brom, Fulham (not in the league that
    # season — Villa/Norwich/Sheffield Utd were the promoted sides that year).
    "11": "Arsenal", "29": "Everton", "31": "Liverpool", "148": "Tottenham",
    "180": "Southampton", "281": "Manchester City", "350": "Sheffield United",
    "379": "West Ham", "405": "Aston Villa", "543": "Wolves",
    "631": "Chelsea", "762": "Newcastle", "873": "Crystal Palace",
    "985": "Manchester Utd", "989": "Bournemouth", "1003": "Leicester City",
    "1010": "Watford", "1123": "Norwich City", "1132": "Burnley",
    "1237": "Brighton",
}

TM_CLUB_ID_TO_FBREF_NAME_LALIGA = {
    "13": "Atlético Madrid", "131": "Barcelona", "150": "Real Betis", "237": "Mallorca",
    "331": "Osasuna", "367": "Rayo Vallecano", "368": "Sevilla", "418": "Real Madrid",
    "472": "Las Palmas", "621": "Athletic Club", "681": "Real Sociedad", "940": "Celta Vigo",
    "1049": "Valencia", "1050": "Villarreal", "1108": "Alavés", "2687": "Cádiz",
    "3302": "Almería", "3709": "Getafe", "12321": "Girona", "16795": "Granada",
}

TM_CLUB_ID_TO_FBREF_NAME_LALIGA_2022_23 = {
    # 2022/23 La Liga clubs: includes Espanyol, Valladolid, Elche;
    # excludes Las Palmas, Alavés, Granada (not in the league that season).
    "13": "Atlético Madrid", "131": "Barcelona", "150": "Real Betis",
    "237": "Mallorca", "331": "Osasuna", "366": "Valladolid",
    "367": "Rayo Vallecano", "368": "Sevilla", "418": "Real Madrid",
    "621": "Athletic Club", "681": "Real Sociedad", "714": "Espanyol",
    "940": "Celta Vigo", "1049": "Valencia", "1050": "Villarreal",
    "1531": "Elche", "2687": "Cádiz", "3302": "Almería", "3709": "Getafe",
    "12321": "Girona",
}

TM_CLUB_ID_TO_FBREF_NAME_LALIGA_2021_22 = {
    # 2021/22 La Liga clubs: includes Alavés, Levante, Granada; excludes
    # Almería, Girona, Las Palmas, Valladolid (not in the league that season).
    "131": "Barcelona", "418": "Real Madrid", "13": "Atlético Madrid",
    "1050": "Villarreal", "368": "Sevilla", "681": "Real Sociedad",
    "1049": "Valencia", "150": "Real Betis", "621": "Athletic Club",
    "3709": "Getafe", "714": "Espanyol", "940": "Celta Vigo",
    "331": "Osasuna", "16795": "Granada", "3368": "Levante",
    "237": "Mallorca", "1531": "Elche", "2687": "Cádiz",
    "367": "Rayo Vallecano", "1108": "Alavés",
}

TM_CLUB_ID_TO_FBREF_NAME_LALIGA_2020_21 = {
    # 2020/21 La Liga clubs: includes Huesca, Valladolid, Eibar; excludes
    # Espanyol, Mallorca, Leganés (not in the league that season).
    "13": "Atlético Madrid", "418": "Real Madrid", "131": "Barcelona",
    "368": "Sevilla", "681": "Real Sociedad", "150": "Real Betis",
    "1050": "Villarreal", "940": "Celta Vigo", "16795": "Granada",
    "621": "Athletic Club", "331": "Osasuna", "2687": "Cádiz",
    "1049": "Valencia", "3368": "Levante", "3709": "Getafe",
    "1108": "Alavés", "1531": "Elche", "5358": "Huesca",
    "366": "Valladolid", "1533": "Eibar",
}

TM_CLUB_ID_TO_FBREF_NAME_LALIGA_2019_20 = {
    # 2019/20 La Liga clubs: includes Leganés, Espanyol, Mallorca; excludes
    # Huesca, Cádiz, Elche (not in the league that season).
    "418": "Real Madrid", "131": "Barcelona", "13": "Atlético Madrid",
    "368": "Sevilla", "1050": "Villarreal", "681": "Real Sociedad",
    "16795": "Granada", "3709": "Getafe", "1049": "Valencia",
    "331": "Osasuna", "621": "Athletic Club", "3368": "Levante",
    "366": "Valladolid", "1533": "Eibar", "150": "Real Betis",
    "1108": "Alavés", "940": "Celta Vigo", "237": "Mallorca",
    "1244": "Leganés", "714": "Espanyol",
}

ROLE_TO_GROUP = {
    "Goalkeeper": "GK",
    "Centre-Back": "CB",
    "Left-Back": "FB",
    "Right-Back": "FB",
    "Left Midfield": "Winger",
    "Right Midfield": "Winger",
    "Defensive Midfield": "DM",
    "Central Midfield": "CM",
    "Attacking Midfield": "CAM",
    "Left Winger": "Winger",
    "Right Winger": "Winger",
    "Second Striker": "ST",
    "Centre-Forward": "ST",
}


def _normalize(name: str) -> str:
    name = name.replace("-", " ").replace("'", "")
    decomposed = unicodedata.normalize("NFKD", name)
    ascii_only = "".join(c for c in decomposed if not unicodedata.combining(c))
    normalized = re.sub(r"[^a-z ]", "", ascii_only.lower())
    return re.sub(r"\s+", " ", normalized).strip()


def load_roles_by_club(
    filename: str = "transfermarkt_roles_2023-2024_PL.txt",
    club_id_map: dict[str, str] = TM_CLUB_ID_TO_FBREF_NAME_PL,
) -> dict[str, dict[str, str]]:
    """Returns {fbref_club_name: {normalized_player_name: role_group}}."""
    text = (RAW_DIR / filename).read_text(encoding="utf-8")
    by_club: dict[str, dict[str, str]] = {name: {} for name in club_id_map.values()}
    for line in text.splitlines():
        line = line.strip()
        if not line:
            continue
        club_id, name, role = line.split("|")
        club = club_id_map.get(club_id)
        if not club or role not in ROLE_TO_GROUP:
            continue
        by_club[club][_normalize(name)] = ROLE_TO_GROUP[role]
    return by_club


FALLBACK_GROUP_BY_FBREF_TAG = {"GK": "GK", "DF": "CB", "MF": "CM", "FW": "ST"}


def resolve_role_group(player_name: str, team: str, fbref_position: str, roles_by_club: dict[str, dict[str, str]]) -> str:
    """Transfermarkt role if matched; otherwise a coarse fallback derived
    from FBref's own position tag, so every player always gets a role
    group — never left blank (per §7.2's graceful-degradation principle)."""
    role = match_role(player_name, team, roles_by_club)
    if role:
        return role
    return FALLBACK_GROUP_BY_FBREF_TAG.get(fbref_position.split(",")[0], "CM")


def match_role(player_name: str, team: str, roles_by_club: dict[str, dict[str, str]]) -> str | None:
    """
    Looks up a role for (player_name, team) using a fallback cascade:
    exact normalized match -> token-set match (handles name-order swaps) ->
    substring match either direction (handles nickname vs full name) ->
    surname-only match (last resort, only within this club's squad).
    Returns None if nothing matches (caller should fall back to a broad
    group derived from FBref's own coarse position tag).
    """
    club_roles = roles_by_club.get(team, {})
    if not club_roles:
        return None

    target = _normalize(player_name)
    if target in club_roles:
        return club_roles[target]

    target_tokens = set(target.split())
    for name, role in club_roles.items():
        if set(name.split()) == target_tokens:
            return role

    for name, role in club_roles.items():
        if name and (name in target or target in name):
            return role

    target_surname = target.split()[-1] if target.split() else ""
    if target_surname:
        surname_matches = [role for name, role in club_roles.items() if name.split() and name.split()[-1] == target_surname]
        if len(surname_matches) == 1:
            return surname_matches[0]

    return None


if __name__ == "__main__":
    from build_dataset import build_player_seasons

    roles_by_club = load_roles_by_club()
    seasons = build_player_seasons()

    matched, unmatched = 0, []
    for s in seasons:
        role = match_role(s.player, s.team, roles_by_club)
        if role:
            matched += 1
        else:
            unmatched.append((s.player, s.team, s.position))

    print(f"{matched}/{len(seasons)} player-seasons matched to a Transfermarkt role")
    print(f"{len(unmatched)} unmatched:")
    for name, team, pos in unmatched:
        print(f"   {name!r:30s} {team:20s} (FBref position: {pos})")
