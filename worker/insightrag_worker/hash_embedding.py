"""``hash-v1`` feature-hashing embedding. Specification: contracts/README.md.

The Java API carries an independent implementation of the same function for query
embedding; both are checked against contracts/hash-embedding-fixtures.json.
"""
from __future__ import annotations

import math
import re
import unicodedata
from typing import Dict, List

DIMENSIONS = 1536
MODEL_ID = "hash-v1"

STOP_WORDS = frozenset(
    """
    a about above after again against all am an and any are as at be because been before being
    below between both but by can could did do does doing down during each few for from further
    had has have having he her here hers herself him himself his how i if in into is it its itself
    just me more most my myself no nor not of off on once only or other our ours ourselves out
    over own same she should so some such than that the their theirs them themselves then there
    these they this those through to too under until up very was we were what when where which
    while who whom why will with would you your yours yourself yourselves
    """.split()
)

_TOKEN = re.compile(r"[a-z0-9]+")
_FNV_OFFSET = 0x811C9DC5
_FNV_PRIME = 0x01000193


def fnv1a32(data: bytes) -> int:
    h = _FNV_OFFSET
    for b in data:
        h ^= b
        h = (h * _FNV_PRIME) & 0xFFFFFFFF
    return h


def stem(term: str) -> str:
    n = len(term)
    if n > 4 and term.endswith("ies"):
        return term[:-3] + "y"
    if n > 5 and term.endswith("ing"):
        return term[:-3]
    if n > 4 and term.endswith("ed"):
        return term[:-2]
    if n > 3 and term.endswith("s") and not term.endswith("ss"):
        return term[:-1]
    return term


def terms(text: str) -> List[str]:
    lowered = unicodedata.normalize("NFKC", text).lower()
    return [stem(t) for t in _TOKEN.findall(lowered) if t not in STOP_WORDS]


def features(text: str) -> Dict[str, float]:
    """Feature -> value before hashing (exposed for tests and the fixture generator)."""
    ts = terms(text)
    counts: Dict[str, int] = {}
    weights: Dict[str, float] = {}
    for t in ts:
        counts[t] = counts.get(t, 0) + 1
        weights[t] = 1.0
    for a, b in zip(ts, ts[1:]):
        f = a + "_" + b
        counts[f] = counts.get(f, 0) + 1
        weights[f] = 0.5
    return {f: weights[f] * (1.0 + math.log(c)) for f, c in counts.items()}


def embed(text: str) -> List[float]:
    vec = [0.0] * DIMENSIONS
    feats = features(text)
    if not feats:
        vec[0] = 1.0
        return vec
    # Iterate in sorted order so float accumulation order is identical to the Java side.
    for f in sorted(feats):
        h = fnv1a32(f.encode("utf-8"))
        sign = -1.0 if (h >> 20) & 1 else 1.0
        vec[h % DIMENSIONS] += sign * feats[f]
    norm = math.sqrt(sum(v * v for v in vec))
    if norm == 0.0:  # every feature cancelled out through sign collisions
        vec = [0.0] * DIMENSIONS
        vec[0] = 1.0
        return vec
    return [v / norm for v in vec]


def has_features(text: str) -> bool:
    return bool(terms(text))
