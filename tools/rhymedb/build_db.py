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
import shutil
import sqlite3
import sys
import time
from collections import OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
BASE = os.path.join(HERE, "poet_assistant.base.db")
# Shipped asset. The filename carries EmbeddedDb.DB_VERSION: v1 was "poet_assistant.db",
# this (v2, adds the thesaurus reverse index) is "poet_assistant2.db". Bumping the version
# is how EmbeddedDb knows to re-copy the asset over an older install's cached DB.
DEFAULT_OUT = os.path.join(REPO, "app", "src", "main", "assets", "poet_assistant2.db")


def split_list(s: str | None) -> list[str]:
    if not s:
        return []
    return [w for w in s.split(",") if w]


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


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--out", default=DEFAULT_OUT, help="output DB path")
    ap.add_argument("--validate-only", metavar="PATH",
                    help="skip building; just validate the given DB")
    args = ap.parse_args()

    if args.validate_only:
        sys.exit(0 if validate(args.validate_only) else 1)

    build(args.out)
    print("\nValidating against TestThesaurus ground truth:")
    if not validate(args.out):
        sys.exit("VALIDATION FAILED")
    print("\nAll good.")


if __name__ == "__main__":
    main()
