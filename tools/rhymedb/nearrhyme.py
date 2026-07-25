#!/usr/bin/env python3
"""M4 prototype: near-rhyme (slant-rhyme) scoring by ARPAbet phonetic distance.

Compares the rhyme tail (from the stressed vowel to the end - which is exactly the
`rhyme_key` we already store) of a query against every distinct rhyme tail in the
dictionary, ranking by a weighted phonetic distance. Brute force on purpose: this is
about getting the *quality* right; the on-device version will use a bucket index for
speed. Ranks by (distance, then most-common-first). Perfect rhymes (distance 0) are
excluded - those are the "perfect" view.
"""
import math
import sqlite3
import sys
from collections import defaultdict

DB = "app/src/main/assets/poet_assistant3.db"

# --- ARPAbet feature tables (approximate; tuned by eyeballing output) -----------------
# Vowel: (height 0=high..3=low, backness 0=front..2=back, roundness 0..1)
VOWELS = {
    "IY": (0.0, 0.0, 0), "IH": (0.7, 0.0, 0), "EY": (1.5, 0.0, 0), "EH": (2.0, 0.0, 0),
    "AE": (3.0, 0.0, 0), "AA": (3.0, 2.0, 0), "AO": (2.5, 2.0, 1), "OW": (1.5, 2.0, 1),
    "UH": (0.7, 2.0, 1), "UW": (0.0, 2.0, 1), "AH": (1.7, 1.0, 0), "ER": (1.7, 1.0, 0.3),
    "AW": (2.5, 1.3, 0.4), "AY": (2.5, 0.7, 0), "OY": (2.0, 1.0, 0.5),
}
# Consonant: (place 0=bilabial..7=glottal, manner, voicing 0/1)
CONS = {
    "P": (0, "stop", 0), "B": (0, "stop", 1), "M": (0, "nasal", 1),
    "F": (1, "fric", 0), "V": (1, "fric", 1),
    "TH": (2, "fric", 0), "DH": (2, "fric", 1),
    "T": (3, "stop", 0), "D": (3, "stop", 1), "S": (3, "fric", 0), "Z": (3, "fric", 1),
    "N": (3, "nasal", 1), "L": (3, "lateral", 1), "R": (3.5, "rhotic", 1),
    "SH": (4, "fric", 0), "ZH": (4, "fric", 1), "CH": (4, "afr", 0), "JH": (4, "afr", 1),
    "Y": (5, "glide", 1), "K": (6, "stop", 0), "G": (6, "stop", 1), "NG": (6, "nasal", 1),
    "W": (6.5, "glide", 1), "HH": (7, "fric", 0),
}
# lateral (L) and rhotic (R) split out of "liquid" so R and L sit a manner-step apart.
MANNER = {"stop": 0, "afr": 1, "fric": 2, "nasal": 3, "lateral": 4, "rhotic": 5, "glide": 6}

VOWEL_WEIGHT = 2.0   # the vowel is the heart of a rhyme
GAP_PENALTY = 1.3    # a phoneme in one tail with nothing to align to in the other
MIXED_PENALTY = 3.0  # vowel vs consonant
W_PLACE, W_MANNER, W_VOICE = 1.0, 1.4, 0.4


def vowel_dist(a, b):
    ha, ba, ra = VOWELS[a]; hb, bb, rb = VOWELS[b]
    return math.sqrt(((ha - hb) / 3) ** 2 + ((ba - bb) / 2) ** 2 + (ra - rb) ** 2)


def cons_dist(a, b):
    pa, ma, va = CONS[a]; pb, mb, vb = CONS[b]
    return (abs(pa - pb) / 7 * W_PLACE
            + abs(MANNER[ma] - MANNER[mb]) / 5 * W_MANNER
            + abs(va - vb) * W_VOICE)


def phon_dist(a, b):
    if a == b:
        return 0.0
    av, bv = a in VOWELS, b in VOWELS
    if av and bv:
        return vowel_dist(a, b)
    if (not av) and (not bv):
        return cons_dist(a, b)
    return MIXED_PENALTY


def tail_dist(t1, t2):
    """Both tails start at the stressed vowel; compare position by position, weighting the
    stressed vowel, and penalising length mismatch."""
    d = phon_dist(t1[0], t2[0]) * VOWEL_WEIGHT
    n = min(len(t1), len(t2))
    for i in range(1, n):
        d += phon_dist(t1[i], t2[i])
    d += GAP_PENALTY * abs(len(t1) - len(t2))
    return d


def load():
    con = sqlite3.connect(DB)
    key_words = defaultdict(list)   # rhyme_key -> [(word, freq)]
    word_keys = defaultdict(set)    # word -> {rhyme_key}
    for word, key, freq in con.execute("SELECT word, rhyme_key, frequency FROM pronunciation"):
        key_words[key].append((word, freq))
        word_keys[word].add(key)
    con.close()
    # dedupe words per key keeping max freq
    for k in key_words:
        best = {}
        for w, f in key_words[k]:
            best[w] = max(best.get(w, -1), f)
        key_words[k] = best
    return key_words, word_keys


THRESHOLD = 1.6     # a candidate must be at least this phonetically close to qualify at all
FREQ_WEIGHT = 0.06  # blend closeness with commonness (distance stays dominant; freq only smooths)
MAX_ZIPF = 8.0
SCORE_CUTOFF = 1.0  # include everything scoring at least this well...
MIN_RESULTS = 25    # ...but always show at least this many (dip below the cutoff if needed)...
MAX_RESULTS = 200   # ...and never more than this.


def combined_score(dist, freq):
    """Lower is better: phonetic distance plus a rarity penalty, so common + close wins and the
    obscure tail sinks. freq is round(zipf*100)."""
    return dist + FREQ_WEIGHT * (MAX_ZIPF - freq / 100.0)


def near_rhymes(word, key_words, word_keys, threshold=THRESHOLD, cutoff=SCORE_CUTOFF):
    qkeys = [k.split() for k in word_keys.get(word, [])]
    if not qkeys:
        return []
    perfect = set(word_keys.get(word, []))  # exclude exact-key (perfect) rhymes
    scored = {}  # candidate word -> (best_dist, freq)
    for ckey, words in key_words.items():
        if ckey in perfect:
            continue
        ct = ckey.split()
        dist = min(tail_dist(qt, ct) for qt in qkeys)
        if dist <= 0 or dist > threshold:
            continue
        for w, f in words.items():
            if w == word:
                continue
            if w not in scored or dist < scored[w][0]:
                scored[w] = (dist, f)
    ranked = sorted(scored.items(), key=lambda kv: combined_score(kv[1][0], kv[1][1]))
    n_below = sum(1 for _, (d, f) in ranked if combined_score(d, f) <= cutoff)
    n = min(max(n_below, MIN_RESULTS), MAX_RESULTS, len(ranked))
    return [(w, d, f, combined_score(d, f)) for w, (d, f) in ranked[:n]]


if __name__ == "__main__":
    key_words, word_keys = load()
    seeds = sys.argv[1:] or ["night", "love", "heart", "time", "fire", "orange",
                             "month", "silver", "dream", "home"]
    for s in seeds:
        nr = near_rhymes(s, key_words, word_keys)
        last = nr[-1] if nr else None
        print(f"\n=== {s}: {len(nr)} near rhymes (last score {last[3]:.2f})" if last else f"\n=== {s}: 0")
        # show the boundary: words around positions 20-30 to judge where quality is at the cutoff
        tail = nr[max(0, len(nr) - 12):]
        print("  ...tail: " + ", ".join(f"{w}[s{sc:.2f} f{f}]" for w, d, f, sc in tail))
