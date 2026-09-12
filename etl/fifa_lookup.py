"""
Reusable lookup from (player name, birth year, season) -> FIFA/EA-FC overall
rating, used as the quality anchor in attributes.py.

The match cascade and its rationale are documented in fifa_match_report.py,
which reports coverage per season using this same class. Every index is
keyed by sofifa_id so that a single player's multiple name variants are
never mistaken for an ambiguous multi-player match.
"""
from __future__ import annotations

import csv
from collections import defaultdict
from dataclasses import dataclass, field, replace
from pathlib import Path

from roles import _normalize

FIFA_CSV = Path(__file__).parent / "fifa" / "fifa_ratings_normalized.csv"

SLOT_KEYS = ["cb", "fb", "dm", "cm", "cam", "winger", "st"]

# Which EA position tokens mean "this player belongs on the left / the right".
# Used only to LABEL a player for display; nothing restricts or penalises on
# side, because EA's own lb/rb rating columns are identical for every player
# (see migration_0005).
_LEFT = {"LB", "LWB", "LW", "LM"}
_RIGHT = {"RB", "RWB", "RW", "RM"}


@dataclass(frozen=True)
class FifaProfile:
    """Everything one matched EA row tells us about a player."""

    overall: int
    edition: str
    nationality: str = ""
    positions: tuple[str, ...] = ()
    grid: dict[str, int] = field(default_factory=dict)
    #: Edition the grid came from. Differs from `edition` only when it had to
    #: be borrowed (EA FC 25 publishes no positional grid).
    grid_edition: str = ""

    def slot_delta(self, slot: str, role_slot: str) -> int:
        """
        How many rating points this player loses playing `slot` instead of his
        natural `role_slot`. Never positive; 0 at his own position.

        A DELTA, not the raw grid value, because EA's positional ratings sit on
        a slightly different scale from overall: Benzema's FIFA 23 overall is 91
        but his `st` positional rating is 89, so using the grid directly would
        dock a striker two points for playing striker. Anchoring to his own
        natural-position grid value cancels that offset out exactly.
        """
        if not self.grid:
            return 0
        reference = self.grid.get(role_slot) or max(self.grid.values())
        target = self.grid.get(slot)
        if target is None:
            return 0
        return min(0, target - reference)

    @property
    def side(self) -> str | None:
        """L, R, B (both) or None. Display only."""
        left = bool(set(self.positions) & _LEFT)
        right = bool(set(self.positions) & _RIGHT)
        return "B" if left and right else "L" if left else "R" if right else None

# Which FIFA/EA-FC editions describe a season, in preference order.
#
# Editions ship in September rated on what players had JUST DONE, so edition
# N+1 is the best description of season N (measured: correlation of squad
# strength with final league position -0.871 under N+1 vs -0.737 under N;
# every season, no exceptions — see fifa_ratings.py's docstring).
#
# The SECOND entry is a fallback and is not optional. Under an N+1-only
# mapping, any player who left top-flight football at the end of that season
# is absent from the next edition and loses his anchor entirely, falling
# through to a stats-only rating. That produced 52 sub-60 ratings among
# players with 900+ minutes — Mariappa 34, Karim Rekik 34, Garay 37, Diego
# Costa 42, Lingard 42 — which is nonsense for a season-long starter. The
# same-year edition was set before that season kicked off, so it is a
# slightly worse description, but it is a description of the right player;
# the form modifier then adjusts it. Prefer N+1, accept N, never neither.
SEASON_EDITIONS = {
    # 2009/10-2013/14 added 2026-09-12. Editions 10-14 are scraped from sofifa
    # (the Kaggle archive starts at FIFA 15); same N+1 rule, same fallback.
    "2009/10": ["11", "10"],
    "2010/11": ["12", "11"],
    "2011/12": ["13", "12"],
    "2012/13": ["14", "13"],
    "2013/14": ["15", "14"],
    "2014/15": ["16", "15"],
    "2015/16": ["17", "16"],
    "2016/17": ["18", "17"],
    "2017/18": ["19", "18"],
    "2018/19": ["20", "19"],
    "2019/20": ["21", "20"],
    "2020/21": ["22", "21"],
    "2021/22": ["23", "22"],
    "2022/23": ["24", "23"],
    "2023/24": ["25", "24"],
}


def initial_form(norm_name: str) -> str:
    """'lionel andres messi cuccittini' -> 'l messi' (FIFA short-name shape)."""
    parts = norm_name.split()
    return f"{parts[0][0]} {parts[-1]}" if len(parts) >= 2 else norm_name


# Tokens that carry no identifying power in a club name; dropped before
# comparing, so "Deportivo Alavés" still matches FBref's "Alavés".
_CLUB_STOPWORDS = {
    "fc", "cf", "sd", "ud", "cd", "rc", "rcd", "ac", "afc", "sc", "ss", "club",
    "de", "deportivo", "real", "athletic", "atletico", "united", "city",
    "wanderers", "hotspur", "albion", "county", "town", "sporting",
}


def _club_tokens(club: str) -> set[str]:
    return {t for t in _normalize(club).split() if t not in _CLUB_STOPWORDS and len(t) > 2}


def clubs_agree(a: str, b: str) -> bool:
    """
    Loose club-name agreement across FBref's short forms and FIFA's official
    ones: 'Alavés'/'Deportivo Alavés', 'Eibar'/'SD Eibar', 'Nottingham'/
    'Nottingham Forest'. Falls back to a 5-character prefix comparison for
    'Wolves'/'Wolverhampton Wanderers', which share no whole token.

    Verified not to collide on the pairs that matter: Arsenal/Chelsea and
    Real Sociedad/Real Madrid both return False (the 'real' stopword is what
    would otherwise have fused the latter).
    """
    ta, tb = _club_tokens(a), _club_tokens(b)
    if not ta or not tb:
        return False
    if ta & tb:
        return True
    return any(x[:5] == y[:5] for x in ta for y in tb if len(x) >= 5 and len(y) >= 5)


def family_first_initial_form(norm_name: str) -> str:
    """
    Same FIFA short-name shape, but assuming the source wrote the FAMILY
    name FIRST: 'son heung min' -> 'h son'.

    Needed because ~1700 rows in the FIFA data are East Asian players whose
    `long_name` is stored in Hangul/Han script ('손흥민 孙兴慜'). Normalization
    strips non-Latin characters entirely, so the ONLY usable name left is
    the short form ('H. Son') — there is no full Latin name to subset-match
    against. FBref meanwhile mixes conventions: 'Son Heung-min' is
    family-first while 'Takehiro Tomiyasu' is given-first. initial_form()
    already covers the given-first case; this covers the other.
    """
    parts = norm_name.split()
    return f"{parts[1][0]} {parts[0]}" if len(parts) >= 2 else norm_name


class FifaLookup:
    #: Editions to borrow a positional grid from, newest first. Only editions
    #: from the canonical archive appear here — FC 25 has no grid to lend.
    GRID_FALLBACK_EDITIONS = ("24", "23", "22", "21", "20")
    #: Editions scraped from sofifa rosters, which carry no grid of their own.
    SCRAPED_EDITIONS = frozenset({"10", "11", "12", "13", "14"})
    #: Where those borrow a grid from, BY SOFIFA ID, nearest first.
    ID_GRID_FALLBACK_EDITIONS = ("15", "16", "17", "18")

    def __init__(self, path: Path = FIFA_CSV):
        # Every index below is keyed by EDITION ("21", "24", ...), not by
        # season. get_with_tier() takes a season and walks SEASON_EDITIONS.
        # Indexes map to a SOFIFA_ID, not to an overall rating. The id identifies
        # the player; the rating is only one of his attributes, and keying on it
        # made positions, nationality and the positional grid unreachable
        # through the very same match that had already found him. `profiles`
        # holds everything else about the matched row.
        self.profiles: dict[tuple[str, str], FifaProfile] = {}
        self.by_key: dict[tuple[str, str, int], str] = {}
        # Same key, but keeping EVERY id rather than the last one written.
        # Two different real people can share a name AND a birth year, and a
        # plain dict silently resolves that by insertion order.
        self.by_key_all: dict[tuple[str, str, int], set[str]] = defaultdict(set)
        self.by_name: dict[tuple[str, str], set[str]] = defaultdict(set)
        self.by_initial: dict[tuple[str, str], set[str]] = defaultdict(set)
        self.by_year: dict[tuple[str, int], list[tuple[frozenset, str]]] = defaultdict(list)
        # (edition, surname, birth_year) -> {sofifa_id}
        self.by_surname: dict[tuple[str, str, int], set[str]] = defaultdict(set)
        # (edition, first-initial) -> {sofifa_id} — guards the surname tier
        self._first_initial: dict[tuple[str, str], set[str]] = defaultdict(set)
        # (edition, initial-form, birth_year) -> {sofifa_id}
        self.by_initial_year: dict[tuple[str, str, int], set[str]] = defaultdict(set)
        # (edition, birth_year) -> [(name_tokens, club, sofifa_id)]
        self.by_year_club: dict[tuple[str, int], list[tuple[frozenset, str, str]]] = defaultdict(list)
        # (edition, sofifa_id) -> (birth_year, club). Lets a loose match be
        # checked against something other than the name that found it.
        self._identity: dict[tuple[str, str], tuple[int, str]] = {}
        self.editions: set[str] = set()

        if not path.exists():
            return
        with open(path, encoding="utf-8") as fh:
            for r in csv.DictReader(fh):
                s, by, ov, sid = (r["edition"], int(r["birth_year"]),
                                  int(r["overall"]), r["sofifa_id"])
                self.editions.add(s)
                self.profiles[(s, sid)] = FifaProfile(
                    overall=ov,
                    edition=s,
                    nationality=(r.get("nationality") or "").strip(),
                    positions=tuple(p for p in (r.get("positions") or "").split(";") if p),
                    grid={
                        k: int(r[f"pos_{k}"])
                        for k in SLOT_KEYS
                        if (r.get(f"pos_{k}") or "").strip().isdigit()
                    },
                    grid_edition=s,
                )
                all_toks = frozenset(
                    tok for v in r["norm_variants"].split(";") if v for tok in v.split()
                )
                self.by_year_club[(s, by)].append((all_toks, r.get("club", ""), sid))
                self._identity[(s, sid)] = (by, r.get("club", ""))
                for n in {v for v in r["norm_variants"].split(";") if v}:
                    self.by_key[(s, n, by)] = sid
                    self.by_key_all[(s, n, by)].add(sid)
                    self.by_name[(s, n)].add(sid)
                    self.by_initial[(s, initial_form(n))].add(sid)
                    self.by_initial_year[(s, initial_form(n), by)].add(sid)
                    self.by_year[(s, by)].append((frozenset(n.split()), sid))
                    parts = n.split()
                    if parts:
                        self.by_surname[(s, parts[-1], by)].add(sid)
                        self._first_initial[(s, parts[0][0])].add(sid)

    def _subset(self, season: str, tokens: frozenset, by: int) -> str | None:
        if not tokens:
            return None
        hits = {sid for toks, sid in self.by_year.get((season, by), []) if tokens <= toks}
        return next(iter(hits)) if len(hits) == 1 else None

    def _club_match(self, season: str, tokens: frozenset, by: int, club: str) -> str | None:
        """
        Same club + at least one shared name token + unique. Club agreement is
        strong independent evidence, so this recovers players no name-shape
        heuristic reaches: transliterations (Tsyhankov/Tsygankov), spelling
        drift (Agirregabiria/Aguirregabiria), and nickname-vs-legal-name
        (Papu Gómez / Alejandro Darío Gómez, Kiké / Enrique García Martínez).
        Tokens shorter than 3 characters are ignored so an initial can't carry
        a match on its own.
        """
        if not club or not tokens:
            return None
        useful = {t for t in tokens if len(t) >= 3}
        if not useful:
            return None
        hits: set[str] = set()
        for d in (0, -1, 1):
            for ftoks, fclub, sid in self.by_year_club.get((season, by + d), []):
                if useful & ftoks and clubs_agree(club, fclub):
                    hits.add(sid)
            if len(hits) == 1:
                return next(iter(hits))
        return next(iter(hits)) if len(hits) == 1 else None

    @property
    def seasons(self) -> set[str]:
        """Seasons this table can actually answer for — i.e. those whose
        preference chain includes at least one loaded edition."""
        return {s for s, eds in SEASON_EDITIONS.items()
                if any(e in self.editions for e in eds)}

    def get(self, full_name: str, birth_year: int, season: str, club: str = "") -> int | None:
        """Returns the FIFA overall for this player-season, or None."""
        return self.get_with_tier(full_name, birth_year, season, club)[0]

    #: EA position token -> the coarse family FBref records (GK / DF / MF / FW).
    POSITION_FAMILY = {
        "GK": "GK", "CB": "DF", "LB": "DF", "RB": "DF", "LWB": "DF", "RWB": "DF",
        "CDM": "MF", "CM": "MF", "CAM": "MF", "LM": "MF", "RM": "MF", "LAM": "MF", "RAM": "MF",
        "LW": "FW", "RW": "FW", "ST": "FW", "CF": "FW", "LF": "FW", "RF": "FW",
    }

    def get_profile(
        self, full_name: str, birth_year: int, season: str, club: str = "",
        position_hint: str = "",
    ) -> tuple[FifaProfile | None, str | None]:
        """
        The full matched EA row — rating, nationality, positions and the
        positional grid — plus the tier that found it.

        Everything goes through the SAME cascade as get(). Nothing about the
        match differs; only how much of the found row is handed back. A second
        matcher for "positions" would be a copy of this logic that drifts, and
        that has already happened once on this project (fifa_match_report kept
        its own copy, silently fell out of sync, and under-reported coverage).

        `position_hint` is the position the SOURCE recorded for this player-
        season (FBref's "DF", "MF,FW", ...). When given, a candidate whose EA
        positions contradict it is rejected and the search CONTINUES — the next
        edition, then the looser tiers — rather than returning a stranger. See
        _fits. Empty (the default, and every caller before 2026-09-12) keeps
        the original behaviour exactly.
        """
        editions = [e for e in SEASON_EDITIONS.get(season, ()) if e in self.editions]
        for edition in editions:
            sid, tier = self._strict(full_name, birth_year, edition, club)
            if sid is not None and self._fits(edition, sid, position_hint):
                return self._with_grid(edition, sid, full_name, birth_year, club), tier
        for edition in editions:
            sid, tier = self._loose(full_name, birth_year, edition, club)
            if sid is not None and self._fits(edition, sid, position_hint):
                return self._with_grid(edition, sid, full_name, birth_year, club), tier
        return None, None

    def _fits(self, edition: str, sid: str, position_hint: str) -> bool:
        """
        Whether this candidate could be the player the source describes.

        UNIQUENESS IS STILL NOT IDENTITY. The scraped editions 10-14 hold only
        two leagues, so a short or one-word name is often unique there without
        being the right man. Staging 2009/10-2013/14 found fifteen such
        bindings, and the pattern was always the same: José Ángel, Sporting
        Gijón's left-back, matched to "Nene" (José Ángel Hitos), a 51-rated
        Granada striker with the same birth year, because the real one had
        moved to Roma and was absent from FIFA 12 — which is tried before FIFA
        11, where he IS present. Several "Roberto"s and a "Diego" were bound to
        namesake keepers or centre-backs the same way.

        Two contradictions are treated as proof of a different person: keeper
        against outfielder, which the game already treats as absolute; and a
        purely-defender record against a purely-forward EA row, or the reverse.
        Anything subtler (a midfielder listed as a forward) is a real ambiguity
        in how sources label positions, not evidence, and is let through.
        """
        if not position_hint:
            return True
        fbref = {t.strip() for t in position_hint.split(",") if t.strip()}
        ea = {self.POSITION_FAMILY[p] for p in self.profiles[(edition, sid)].positions
              if p in self.POSITION_FAMILY}
        if not fbref or not ea:
            return True
        if ("GK" in fbref) != ("GK" in ea):
            return False
        return not ((fbref == {"DF"} and ea == {"FW"}) or (fbref == {"FW"} and ea == {"DF"}))

    def _with_grid(
        self, edition: str, sid: str, full_name: str, birth_year: int, club: str
    ) -> FifaProfile:
        """
        The matched profile, with the positional grid borrowed from another
        edition when the matched one has none.

        EA FC 25 — the anchor edition for 2023/24 — ships no positional grid at
        all, only overall ratings. Borrowing one from FC 24 is safe in a way
        borrowing a RATING would not be: aptitude for a position barely moves
        year to year, while a rating can move several points in a season. The
        rating always stays the matched edition's; only the grid is borrowed.
        gridEdition records that it happened, so it is visible rather than silent.

        The borrow RE-MATCHES BY NAME rather than reusing the player id. FC 25's
        export carries no stable id, so its rows are given synthesised ones —
        looking the player up by id in another edition therefore always misses,
        which is exactly the bug this replaced (every 2023/24 player came back
        with an empty grid).
        """
        profile = self.profiles[(edition, sid)]
        if profile.grid:
            return profile
        # Editions 10-14 come from a sofifa roster scrape, and those editions
        # publish no positional grid at all. Their ids are REAL sofifa ids —
        # the same ids the Kaggle archive uses from FIFA 15 on — so the grid is
        # borrowed by id from the nearest later edition, not re-matched by
        # name. That is strictly safer than the name route FC 25 has to take,
        # and it is still the same player's aptitude from a year or two on,
        # which moves far less than his rating does.
        if edition in self.SCRAPED_EDITIONS:
            for other in self.ID_GRID_FALLBACK_EDITIONS:
                fallback = self.profiles.get((other, sid))
                if fallback and fallback.grid:
                    return replace(
                        profile,
                        grid=fallback.grid,
                        grid_edition=other,
                        positions=profile.positions or fallback.positions,
                    )
        for other in self.GRID_FALLBACK_EDITIONS:
            if other == edition or other not in self.editions:
                continue
            other_sid, _ = self._strict(full_name, birth_year, other, club)
            if other_sid is None:
                other_sid = self._loose(full_name, birth_year, other, club)[0]
            if other_sid is None:
                continue
            fallback = self.profiles.get((other, other_sid))
            if fallback and fallback.grid:
                return replace(
                    profile,
                    grid=fallback.grid,
                    grid_edition=other,
                    positions=profile.positions or fallback.positions,
                )
        return profile

    def get_with_tier(
        self, full_name: str, birth_year: int, season: str, club: str = ""
    ) -> tuple[int | None, str | None]:
        """
        As get(), but also reports WHICH tier produced the match.

        The coverage report calls this rather than re-implementing the
        cascade. An earlier version of the report kept its own copy, which
        silently drifted out of sync when a tier was added here — so the
        report understated coverage and attributed matches to the wrong tier.
        """
        editions = [e for e in SEASON_EDITIONS.get(season, ()) if e in self.editions]
        # TIER order beats EDITION order: every strict tier is tried against
        # the whole preference chain before any fuzzy tier is tried at all.
        #
        # Edition-major ordering was wrong, and Ben Foster is the proof. He
        # left Watford when they went down in 2022, so he is absent from
        # FIFA 23 (2021/22's preferred edition) but present and exact in
        # FIFA 22. Edition-major let a DIFFERENT "B. Foster" in FIFA 23 win
        # on the initial-form tier at OVR 58, beating an exact full-name
        # match on the fallback edition. A fuzzy hit on the better edition is
        # not worth an exact hit on a slightly worse one.
        for edition in editions:
            sid, tier = self._strict(full_name, birth_year, edition, club)
            if sid is not None:
                return self.profiles[(edition, sid)].overall, tier
        for edition in editions:
            sid, tier = self._loose(full_name, birth_year, edition, club)
            if sid is not None:
                return self.profiles[(edition, sid)].overall, tier
        return None, None

    def _strict(
        self, full_name: str, birth_year: int, season: str, club: str = ""
    ) -> tuple[str | None, str | None]:
        """Tiers that require the full name (or a token superset of it):
        safe enough to trust across editions. `season` here is an edition key."""
        n = _normalize(full_name)
        toks = frozenset(n.split())

        v = self._exact(season, n, birth_year, club)
        if v is not None:
            return v, "exact"
        v = self._subset(season, toks, birth_year)
        if v is not None:
            return v, "subset"
        for d in (-1, 1):
            v = self._exact(season, n, birth_year + d, club)
            if v is not None:
                return v, "year±1"
            v = self._subset(season, toks, birth_year + d)
            if v is not None:
                return v, "year±1"
        return None, None

    def _exact(self, edition: str, n: str, birth_year: int, club: str) -> str | None:
        """
        The unambiguous exact name + birth-year match, or None.

        Name and birth year together are USUALLY an identity, but not always:
        EA FC 24 carries two men called Luiz Felipe, both born 1997 — Real
        Betis's centre-back and a goalkeeper at Vizela. The index used to be a
        plain dict, so whichever row was read second silently won, and Betis's
        defender inherited a keeper's positional grid (st 21, cb 22).

        When several candidates share the key, the club decides. If it cannot,
        return None and let the later tiers try rather than pick one at
        random — a coin-flip between two real people is the failure this whole
        cascade exists to avoid.
        """
        cands = self.by_key_all.get((edition, n, birth_year), set())
        if len(cands) == 1:
            return next(iter(cands))
        if len(cands) > 1 and club:
            agreed = {sid for sid in cands
                      if clubs_agree(self._identity.get((edition, sid), (0, ""))[1], club)}
            if len(agreed) == 1:
                return next(iter(agreed))
        return None

    def _corroborated(self, edition: str, sid: str, birth_year: int, club: str) -> bool:
        """
        Does this candidate agree with us on WHO he is, beyond his name?

        Birth year within a year, or a club that agrees. One or the other is
        enough — FBref and EA disagree about a birth year often enough that
        demanding both would throw away good matches, and a player at the
        right club of roughly the right age is not a coincidence.
        """
        known = self._identity.get((edition, sid))
        if known is None:
            return True          # nothing to check against; leave as before
        fifa_year, fifa_club = known
        if abs(fifa_year - birth_year) <= 1:
            return True
        return bool(club) and bool(fifa_club) and clubs_agree(fifa_club, club)

    def _loose(
        self, full_name: str, birth_year: int, season: str, club: str = ""
    ) -> tuple[str | None, str | None]:
        """Name-shape and club heuristics — only reached once every edition
        has failed the strict tiers. `season` here is an edition key."""
        n = _normalize(full_name)
        toks = frozenset(n.split())
        for tier, index, key in (("name", self.by_name, (season, n)),
                                 ("initial", self.by_initial, (season, initial_form(n)))):
            uniq = index.get(key, set())
            if len(uniq) == 1:
                sid = next(iter(uniq))
                # UNIQUENESS ALONE IS NOT IDENTITY, and these two tiers used to
                # accept it. initial_form keeps a first initial and the LAST
                # token, so every Spanish double surname ending in the same
                # name collapses together: "Dani Carvajal", "Daniel Olmo
                # Carvajal" and "Diego Sanchez Carvajal" all become
                # "d carvajal". Whichever of them happened to be alone in an
                # edition won the match, which is why Real Madrid's right-back
                # was a goalkeeper born in 1987 for three seasons and Dani
                # Olmo for three more — the source of the corrupt positional
                # grids that looked like bad EA data.
                #
                # Every other loose tier is already guarded by birth year or
                # club. These now are too: agree on one or the other, or the
                # match is not made.
                if self._corroborated(season, sid, birth_year, club):
                    return sid, tier

        parts = n.split()
        if len(parts) >= 2:
            # Family-name-first initial form, for the East Asian rows whose
            # only surviving Latin name is FIFA's short form (see
            # family_first_initial_form). Guarded by exact birth year AND
            # uniqueness.
            #
            # Ordered BEFORE the surname fallback deliberately. It is the more
            # specific signal, and the surname tier is actively wrong for these
            # names: it treats the last token as a family name, but in
            # "Son Heung-min" that token is a given-name syllable. Run second,
            # it bound Son Heung-min (89) to Song Seung Min (64) — same trailing
            # "min", same leading initial "s".
            cands = self.by_initial_year.get(
                (season, family_first_initial_form(n), birth_year), set()
            )
            if len(cands) == 1:
                return next(iter(cands)), "family-first"

            cands = {
                sid
                for sid in self.by_surname.get((season, parts[-1], birth_year), set())
                if sid in self._first_initial.get((season, parts[0][0]), ())
            }
            if len(cands) == 1:
                return next(iter(cands)), "surname"

        # Club-aware, OUTSIDE the >=2-token guard on purpose: mononyms are
        # precisely the names the other tiers can't reach ("Kiké", "Djené",
        # "Joselu"), and club agreement is what identifies them. Gated inside
        # that guard it silently skipped every single-name player.
        v = self._club_match(season, toks, birth_year, club)
        if v is not None:
            return v, "club"

        # Club alone (no shared name token) is NOT accepted — that would bind
        # any unmatched player to a random team-mate of the same age.
        return None, None
