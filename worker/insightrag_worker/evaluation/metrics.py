"""Metric definitions shared by the offline sweep and (in Java) the /eval/run endpoint.

Ground truth is a list of *evidence groups* per question; each group is the set of chunk ids
whose text contains one evidence snippet (overlap means a snippet can live in two chunks).

* recall@k     — fraction of evidence groups with at least one chunk in the retrieved set.
* precision@k  — fraction of retrieved chunks that contain some evidence (0 if none retrieved).
* MRR          — 1 / rank of the first relevant retrieved chunk (0 if none).
* refusal acc. — fraction of unanswerable questions for which nothing cleared the floor.
* false refusal— fraction of answerable questions for which nothing cleared the floor.

"Retrieved" always means top-k *after* the relevance floor: exactly what reaches the prompt.
"""
from __future__ import annotations

import re
from dataclasses import dataclass, field
from typing import Dict, Iterable, List, Optional, Sequence, Set

_WS = re.compile(r"\s+")


def normalize_text(s: str) -> str:
    return _WS.sub(" ", s).strip().lower()


def resolve_evidence(evidence: Sequence[str], chunks: Dict[int, str]) -> List[Set[int]]:
    """Map each snippet to the ids of chunks containing it (normalised substring match)."""
    normalized = {cid: normalize_text(text) for cid, text in chunks.items()}
    groups = []
    for snippet in evidence:
        needle = normalize_text(snippet)
        groups.append({cid for cid, text in normalized.items() if needle in text})
    return groups


@dataclass
class QuestionResult:
    key: str
    category: str
    answerable: bool
    retrieved: List[int]
    scores: List[float]
    top_score: Optional[float]
    groups: List[Set[int]] = field(default_factory=list)
    recall: Optional[float] = None
    precision: Optional[float] = None
    reciprocal_rank: Optional[float] = None
    refused: bool = False
    unresolved_evidence: int = 0


def score_question(key: str, category: str, answerable: bool, groups: List[Set[int]],
                   retrieved: List[int], scores: List[float], top_score: Optional[float]) -> QuestionResult:
    r = QuestionResult(key, category, answerable, retrieved, scores, top_score, groups)
    r.refused = not retrieved
    if not answerable:
        return r
    resolvable = [g for g in groups if g]
    r.unresolved_evidence = len(groups) - len(resolvable)
    relevant: Set[int] = set().union(*resolvable) if resolvable else set()
    hit_groups = sum(1 for g in resolvable if g & set(retrieved))
    r.recall = hit_groups / len(resolvable) if resolvable else 0.0
    r.precision = (sum(1 for c in retrieved if c in relevant) / len(retrieved)) if retrieved else 0.0
    r.reciprocal_rank = 0.0
    for rank, cid in enumerate(retrieved, start=1):
        if cid in relevant:
            r.reciprocal_rank = 1.0 / rank
            break
    return r


def _mean(xs: Iterable[float]) -> float:
    xs = list(xs)
    return sum(xs) / len(xs) if xs else 0.0


def aggregate(results: Sequence[QuestionResult]) -> Dict[str, float]:
    ans = [r for r in results if r.answerable]
    neg = [r for r in results if not r.answerable]
    return {
        "questions": len(results),
        "answerable": len(ans),
        "unanswerable": len(neg),
        "recallAtK": round(_mean(r.recall for r in ans), 4),
        "precisionAtK": round(_mean(r.precision for r in ans), 4),
        "mrr": round(_mean(r.reciprocal_rank for r in ans), 4),
        "refusalAccuracy": round(_mean(1.0 if r.refused else 0.0 for r in neg), 4),
        "falseRefusalRate": round(_mean(1.0 if r.refused else 0.0 for r in ans), 4),
        "unresolvedEvidence": sum(r.unresolved_evidence for r in ans),
    }
