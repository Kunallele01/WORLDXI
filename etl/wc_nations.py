"""
Maps a World Cup nation as FBref writes it to the spelling our FIFA ratings use.

FBref tags every team with a country code ("Mexico mx", "za South Africa"), and
abbreviates a few names; the ratings table uses EA's spellings. Resolved by
checking all 54 nations that appear across the 2022 and 2026 results against
every nationality in the ratings table: 51 match either exactly or once accents
and dashes are folded away — Türkiye, Curaçao, Côte d'Ivoire, Cabo Verde and
Congo DR all match on their own. Only three genuinely differ, and they are
listed below rather than discovered one failure at a time.

Older editions reuse the same two sources, so the same rules apply to them.
"""
from __future__ import annotations

import unicodedata

#: FBref's name -> the ratings table's name. Only where they truly disagree.
ALIASES = {
    "Bosnia–Herz": "Bosnia and Herzegovina",
    "IR Iran": "Iran",
    "USA": "United States",
    "Trinidad Tobago": "Trinidad and Tobago",
    # Seen in the older mirror file, which spells these out differently again.
    "Bosnia Herzegovina": "Bosnia and Herzegovina",
    "Serbia & Montenegro": "Serbia",      # defunct; EA lists its players as Serbia
    "Czechoslovakia": "Czech Republic",   # defunct
    "West Germany": "Germany",            # defunct
    "Zaire": "Congo DR",                  # renamed
}

#: Names the RATINGS TABLE spells differently from one edition to the next, so
#: a one-way alias cannot fix them: EA shipped "Czechia" in FIFA 07 and FC 26
#: but "Czech Republic" in FIFA 15, and which is correct depends entirely on
#: the edition being searched. Each group is tried in turn against whatever
#: that edition actually holds. Listed from the spellings seen across all 19
#: editions in the table, not discovered one failure at a time.
#: The FIRST spelling in each group is the canonical one stored in Supabase
#: (`canonical_name`), so 2006's "Czech Republic" and 2026's "Czechia" are one
#: nation rather than two. It is the current official name wherever there is one.
EQUIVALENTS = [
    ("Czechia", "Czech Republic"),
    ("Korea Republic", "South Korea"),
    ("Korea DPR", "North Korea"),
    ("Côte d'Ivoire", "Ivory Coast"),
    ("Cabo Verde", "Cape Verde"),
    ("China PR", "China"),
    ("Türkiye", "Turkey"),
    ("Congo DR", "DR Congo"),
    ("United States", "USA"),
]


def _candidates(name: str):
    """`name` first, then any edition-specific spelling of the same country."""
    yield name
    key = fold(name)
    for group in EQUIVALENTS:
        if any(fold(member) == key for member in group):
            for member in group:
                if fold(member) != key:
                    yield member


def strip_country_code(team: str) -> str:
    """'Mexico mx' / 'za South Africa' -> 'Mexico' / 'South Africa'.

    FBref puts a two or three letter lowercase code beside the name; no real
    nation name has a short all-lowercase word in it, so dropping those is safe.
    """
    return " ".join(w for w in str(team).split() if not (len(w) <= 3 and w.islower()))


def fold(name: str) -> str:
    """Accent- and dash-insensitive key, for matching 'Türkiye' to 'Turkiye'."""
    decomposed = unicodedata.normalize("NFD", name)
    plain = "".join(c for c in decomposed if unicodedata.category(c) != "Mn")
    return plain.lower().replace("-", " ").replace("–", " ").strip()


def canonical_name(team: str) -> str:
    """One stable name per country across every edition, for the nations table."""
    name = strip_country_code(team)
    name = ALIASES.get(name, name)
    key = fold(name)
    for group in EQUIVALENTS:
        if any(fold(member) == key for member in group):
            return group[0]
    return name


def to_rating_nation(team: str, known: set[str]) -> str | None:
    """
    The ratings-table nationality for an FBref team name, or None if we hold no
    players for it. `known` is every nationality string in the ratings table.
    """
    name = strip_country_code(team)
    if name in ALIASES:
        name = ALIASES[name]
    by_fold = {fold(k): k for k in known}
    for candidate in _candidates(name):
        if candidate in known:
            return candidate
        hit = by_fold.get(fold(candidate))
        if hit is not None:
            return hit
    return None
