#!/usr/bin/env python3
"""Derive the shipped poet_assistant.db from the pristine base DB.

This is the first, decoupled slice of the M3 rhyme-data pipeline. For now it only
speeds up the thesaurus:

  * adds an index on thesaurus(word) so the *forward* lookup ("word = ?") stops
    being a full table scan; and
  * builds thesaurus_reverse, an *inverted* copy of the thesaurus (same
    word/word_type/synonyms/antonyms shape) so the *reverse* lookup ("who lists this
    word as a synonym/antonym?") becomes an indexed query instead of a
    leading-wildcard LIKE that scans all 200k rows (~3.5s on the Palm).

    thesaurus_reverse row (X, T, syns, ants) means: syns is the comma-joined list of
    words of type T that have X in *their* synonyms; ants likewise for antonyms. The
    comma-list shape keeps it ~4x smaller than an exploded edge table, and the
    referring words are stored in the same order the old LIKE scan produced them
    (thesaurus rowid order), so results stay byte-identical.

The base DB is never mutated in place: we copy it and transform the copy, so the
base doubles as a backup and the build is reproducible.

Usage:
    python tools/rhymedb/build_db.py [--out PATH] [--validate-only PATH]
"""
from __future__ import annotations

import argparse
import os
import re
import shutil
import sqlite3
import sys
import time
from collections import OrderedDict, defaultdict

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
BASE = os.path.join(HERE, "poet_assistant.base.db")
CMUDICT = os.path.join(HERE, "cmudict.dict")
# Shipped asset. The filename carries EmbeddedDb.DB_VERSION: v1 was "poet_assistant.db",
# v2 added the thesaurus reverse index, v3 (this one) adds the CMUdict `pronunciation` table.
# Bumping the version is how EmbeddedDb re-copies the asset over an older install's cached DB.
DEFAULT_OUT = os.path.join(REPO, "app", "src", "main", "assets", "poet_assistant3.db")


def split_list(s: str | None) -> list[str]:
    if not s:
        return []
    return [w for w in s.split(",") if w]


# ---------------------------------------------------------------------------
# CMUdict -> pronunciation table (M3 rhyme engine)
# ---------------------------------------------------------------------------

_VOWEL = re.compile(r"[0-2]$")  # ARPAbet vowels carry a stress digit (0/1/2)
_WORD = re.compile(r"[a-z]+('[a-z]+)?")  # a real word: letters + optional internal apostrophe

# ARPAbet consonant -> broad manner class, for the near-rhyme bucket key (tuned properly in M4).
_MANNER = {
    "P": "stop", "B": "stop", "T": "stop", "D": "stop", "K": "stop", "G": "stop",
    "F": "fric", "V": "fric", "TH": "fric", "DH": "fric", "S": "fric", "Z": "fric",
    "SH": "fric", "ZH": "fric", "HH": "fric",
    "CH": "afr", "JH": "afr",
    "M": "nas", "N": "nas", "NG": "nas",
    "L": "liq", "R": "liq",
    "W": "gld", "Y": "gld",
}


def _is_vowel(ph: str) -> bool:
    return _VOWEL.search(ph) is not None


def parse_cmudict(path: str):
    """Yield (word, phonemes:list[str]) for real single-word entries, in file order.

    Variants (word(2), word(3)) come through as the same word on consecutive lines.
    Filters out hyphenated / dotted / numbered tokens and single letters other than a/i.
    """
    with open(path, encoding="latin-1") as f:
        for line in f:
            line = line.split("#", 1)[0].strip()  # drop trailing comments
            if not line:
                continue
            parts = line.split()
            token, phones = parts[0], parts[1:]
            if not phones:
                continue
            m = re.match(r"^(.*)\(\d+\)$", token)
            word = m.group(1) if m else token
            if not _WORD.fullmatch(word):
                continue
            if len(word) == 1 and word not in ("a", "i"):
                continue
            yield word, phones


def syllable_count(phones: list[str]) -> int:
    return sum(1 for p in phones if _is_vowel(p))


def rhyme_key(phones: list[str]) -> str | None:
    """From the last primary-stressed vowel (fallback: secondary, then last vowel) to the
    end, stress digits stripped. Two words share this iff they perfectly rhyme."""
    vidx = [i for i, p in enumerate(phones) if _is_vowel(p)]
    if not vidx:
        return None
    start = next((i for i in reversed(vidx) if phones[i].endswith("1")), None)
    if start is None:
        start = next((i for i in reversed(vidx) if phones[i].endswith("2")), vidx[-1])
    return " ".join(_VOWEL.sub("", p) for p in phones[start:])


def near_key(phones: list[str]) -> str:
    """Coarsened rhyme key for near-rhyme bucketing: the last stressed vowel kept as-is,
    coda consonants collapsed to manner class. Starting point; M4's scorer refines it."""
    vidx = [i for i, p in enumerate(phones) if _is_vowel(p)]
    if not vidx:
        return ""
    start = next((i for i in reversed(vidx) if phones[i].endswith("1")), None)
    if start is None:
        start = next((i for i in reversed(vidx) if phones[i].endswith("2")), vidx[-1])
    vowel = _VOWEL.sub("", phones[start])
    coda = [_MANNER.get(p, p) for p in phones[start + 1:]]
    return " ".join([vowel] + coda)


def build_pronunciation(con: sqlite3.Connection) -> None:
    """Build the `pronunciation` table from CMUdict, ranked by wordfreq frequency."""
    if not os.path.exists(CMUDICT):
        sys.exit(f"CMUdict not found: {CMUDICT}\n"
                 f"  Download: curl -sSL -o {CMUDICT} "
                 f"https://raw.githubusercontent.com/cmusphinx/cmudict/master/cmudict.dict")
    try:
        from wordfreq import zipf_frequency
    except ImportError:
        sys.exit("wordfreq is required for the pronunciation table: pip install wordfreq")

    # google_ngram frequency as a tiebreaker for the ~20% of words wordfreq can't rank.
    ngram = {w: (f or 0) for w, f in
             con.execute("SELECT word, MAX(google_ngram_frequency) FROM stems GROUP BY word")}

    con.execute("DROP TABLE IF EXISTS pronunciation")
    con.execute(
        "CREATE TABLE pronunciation ("
        " word TEXT NOT NULL,"
        " variant INTEGER NOT NULL,"
        " phonemes TEXT NOT NULL,"       # full ARPAbet, stress digits retained
        " syllables INTEGER NOT NULL,"   # count of vowel phonemes
        " rhyme_key TEXT NOT NULL,"      # last stressed vowel -> end, stress stripped
        " near_key TEXT NOT NULL,"       # coarsened key for near rhymes (M4)
        " frequency INTEGER NOT NULL DEFAULT 0,"    # round(zipf * 100)
        " google_ngram INTEGER NOT NULL DEFAULT 0)"  # tiebreaker
    )

    t0 = time.time()
    freq_cache: dict[str, int] = {}
    variant_of: dict[str, int] = defaultdict(int)
    rows = []
    for word, phones in parse_cmudict(CMUDICT):
        key = rhyme_key(phones)
        if key is None:
            continue
        if word not in freq_cache:
            freq_cache[word] = round(zipf_frequency(word, "en") * 100)
        variant = variant_of[word]
        variant_of[word] += 1
        rows.append((word, variant, " ".join(phones), syllable_count(phones),
                     key, near_key(phones), freq_cache[word], ngram.get(word, 0)))
    con.executemany(
        "INSERT INTO pronunciation VALUES (?,?,?,?,?,?,?,?)", rows)
    con.execute("CREATE INDEX idx_pron_rhyme ON pronunciation(rhyme_key, frequency DESC)")
    con.execute("CREATE INDEX idx_pron_near ON pronunciation(near_key, frequency DESC)")
    con.execute("CREATE INDEX idx_pron_word ON pronunciation(word)")
    con.commit()
    print(f"Built {len(rows)} pronunciations "
          f"({len(freq_cache)} words) in {time.time()-t0:.1f}s")


def build(out_path: str) -> None:
    if not os.path.exists(BASE):
        sys.exit(f"Base DB not found: {BASE}")
    print(f"Copying base -> {out_path}")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    shutil.copyfile(BASE, out_path)

    con = sqlite3.connect(out_path)
    try:
        # Forward lookup speedup.
        con.execute("CREATE INDEX IF NOT EXISTS idx_thesaurus_word ON thesaurus(word)")

        # Inverted table, same shape as thesaurus.
        con.execute("DROP TABLE IF EXISTS thesaurus_reverse")
        con.execute(
            "CREATE TABLE thesaurus_reverse ("
            " word VARCHAR (80) NOT NULL,"
            " word_type VARCHAR(4) NOT NULL,"
            " synonyms TEXT,"
            " antonyms TEXT)"
        )

        # Scan the forward table in rowid order (~alphabetical by word) so the referring
        # words accumulate in the same order the old LIKE scan yielded them.
        t0 = time.time()
        syn: dict[tuple[str, str], "OrderedDict[str, None]"] = {}
        ant: dict[tuple[str, str], "OrderedDict[str, None]"] = {}
        for word, wtype, s, a in con.execute(
            "SELECT word, word_type, synonyms, antonyms FROM thesaurus ORDER BY rowid"
        ):
            for tok in split_list(s):
                syn.setdefault((tok, wtype), OrderedDict())[word] = None
            for tok in split_list(a):
                ant.setdefault((tok, wtype), OrderedDict())[word] = None

        rows = []
        for key in set(syn) | set(ant):
            rw, wtype = key
            s_list = ",".join(syn.get(key, {}).keys())
            a_list = ",".join(ant.get(key, {}).keys())
            rows.append((rw, wtype, s_list, a_list))
        con.executemany(
            "INSERT INTO thesaurus_reverse (word, word_type, synonyms, antonyms)"
            " VALUES (?,?,?,?)",
            rows,
        )
        con.execute(
            "CREATE INDEX idx_thesaurus_reverse_word ON thesaurus_reverse(word)"
        )
        con.commit()
        edges = sum(len(v) for v in syn.values()) + sum(len(v) for v in ant.values())
        print(f"Built {len(rows)} reverse rows ({edges} edges) in {time.time()-t0:.1f}s")

        # M3 rhyme engine.
        build_pronunciation(con)

        con.execute("VACUUM")
        con.commit()
    finally:
        con.close()

    size_mb = os.path.getsize(out_path) / (1024 * 1024)
    base_mb = os.path.getsize(BASE) / (1024 * 1024)
    print(f"Output size: {size_mb:.1f} MB (base was {base_mb:.1f} MB, +{size_mb-base_mb:.1f})")


def forward_words(con, word):
    """(synonyms, antonyms) sets of `word`'s own forward relations, for exclusion."""
    syn, ant = set(), set()
    for s, a in con.execute(
        "SELECT synonyms, antonyms FROM thesaurus WHERE word=?", (word,)
    ):
        syn.update(split_list(s))
        ant.update(split_list(a))
    return syn, ant


def reverse_lookup(con, word):
    """Mirror of the Kotlin reverse path: per-type reverse syn/ant, with the word's
    own forward relations excluded (syn excludes forward syn, ant excludes forward ant)."""
    fwd_syn, fwd_ant = forward_words(con, word)
    syn_by_type, ant_by_type = {}, {}
    for wtype, s, a in con.execute(
        "SELECT word_type, synonyms, antonyms FROM thesaurus_reverse WHERE word=?",
        (word,),
    ):
        sl = [w for w in split_list(s) if w not in fwd_syn]
        al = [w for w in split_list(a) if w not in fwd_ant]
        if sl:
            syn_by_type[wtype] = sl
        if al:
            ant_by_type[wtype] = al
    return syn_by_type, ant_by_type


def _check(label, got, expected, state):
    if got != expected:
        state["ok"] = False
        print(f"MISMATCH {label}:\n  expected: {expected}\n  got     : {got}")
    else:
        print(f"OK {label} ({len(got)} words)")


def validate(out_path: str) -> bool:
    """Reproduce the reverse results TestThesaurus.kt asserts, from the new table."""
    con = sqlite3.connect(out_path)
    state = {"ok": True}
    try:
        # TestThesaurus.testReverseLookupEnabledMistake
        expected_noun = [
            "balls-up", "ballup", "betise", "bloomer", "blooper", "blot", "blunder",
            "boner", "boo-boo", "botch", "bungle", "cockup", "confusion", "corrigendum",
            "distortion", "erratum", "flub", "folly", "foolishness", "foul-up", "fuckup",
            "imbecility", "incursion", "lapse", "literal", "literal error", "mess-up",
            "miscalculation", "miscue", "misestimation", "misprint", "misreckoning",
            "mix-up", "offside", "omission", "oversight", "parapraxis", "pratfall",
            "renege", "revoke", "skip", "slip-up", "smear", "smirch", "spot", "stain",
            "stupidity", "typo", "typographical error",
        ]
        expected_verb = [
            "confound", "confuse", "fall for", "misjudge", "misremember", "stumble",
            "trip up",
        ]
        syn, ant = reverse_lookup(con, "mistake")
        _check("mistake NOUN reverse synonyms", syn.get("NOUN", []), expected_noun, state)
        _check("mistake VERB reverse synonyms", syn.get("VERB", []), expected_verb, state)

        # TestThesaurus.testReverseLookupEnabledNonattendance
        syn, ant = reverse_lookup(con, "nonattendance")
        _check("nonattendance reverse synonyms", syn.get("NOUN", []),
               ["absence", "hooky", "nonappearance", "truancy"], state)
        _check("nonattendance reverse antonyms", ant.get("NOUN", []),
               ["attending"], state)
    finally:
        con.close()
    return state["ok"]


def sample_rhymes(out_path: str, words=("night", "day", "love", "orange"), per=10):
    """Print perfect rhymes (grouped by syllable count, common-first) straight from the
    built pronunciation table - the query the Kotlin rhymer will run - for eyeballing."""
    con = sqlite3.connect(out_path)
    try:
        for word in words:
            keys = [r[0] for r in con.execute(
                "SELECT DISTINCT rhyme_key FROM pronunciation WHERE word=?", (word,))]
            if not keys:
                print(f"\n=== {word}: not in pronunciation table ===")
                continue
            placeholders = ",".join("?" * len(keys))
            rows = con.execute(
                f"SELECT DISTINCT word, syllables FROM pronunciation "
                f"WHERE rhyme_key IN ({placeholders}) AND word != ? "
                f"ORDER BY syllables, frequency DESC, google_ngram DESC, word",
                (*keys, word)).fetchall()
            secs: dict[int, list[str]] = defaultdict(list)
            for w, syl in rows:
                if w not in secs[syl]:
                    secs[syl].append(w)
            print(f"\n=== {word} ({len(rows)} rhymes) ===")
            for syl in sorted(secs):
                print(f"  {syl}syl: " + ", ".join(secs[syl][:per]))
    finally:
        con.close()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=DEFAULT_OUT, help="output DB path")
    ap.add_argument("--validate-only", metavar="PATH",
                    help="skip building; just validate the given DB")
    args = ap.parse_args()

    if args.validate_only:
        sys.exit(0 if validate(args.validate_only) else 1)

    build(args.out)
    print("\nValidating thesaurus reverse against TestThesaurus ground truth:")
    if not validate(args.out):
        sys.exit("VALIDATION FAILED")
    print("\nSample perfect rhymes from the pronunciation table:")
    sample_rhymes(args.out)
    print("\nAll good.")


if __name__ == "__main__":
    main()
