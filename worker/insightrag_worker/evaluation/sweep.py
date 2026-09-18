"""Offline retrieval evaluation and parameter sweep (design doc §7.3 step 24).

Runs the worker's real extraction, chunking and hash-v1 embedding over eval/corpus, searches
with exact cosine similarity (the HNSW index approximates this), and scores against
eval/questions.json. No database, queue or network is involved, so it runs anywhere in
seconds and can gate CI on its own.

    python -m insightrag_worker.evaluation.sweep                       # default grid
    python -m insightrag_worker.evaluation.sweep --sizes 512 --overlaps 64 --floors 0.2 --gate
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from .. import hash_embedding
from ..chunker import ChunkingConfig, chunk_document
from ..embeddings import chunk_embedding_input, normalize_for_embedding
from ..extract import MIME_BY_EXTENSION, extract
from ..tokenizer import RegexTokenizer, Tokenizer
from .metrics import QuestionResult, aggregate, resolve_evidence, score_question

REPO = Path(__file__).resolve().parents[3]


@dataclass
class Index:
    ids: List[int]
    texts: Dict[int, str]
    docs: Dict[int, str]
    matrix: np.ndarray  # (n, 1536), rows L2-normalised


def build_index(corpus_dir: Path, size: int, overlap: int, tokenizer: Tokenizer) -> Index:
    cfg = ChunkingConfig(size, overlap)
    ids, texts, docs, rows = [], {}, {}, []
    next_id = 1
    for path in sorted(corpus_dir.iterdir()):
        mime = MIME_BY_EXTENSION.get(path.suffix.lower())
        if not mime:
            continue
        extracted = extract(path.read_bytes(), mime)
        for c in chunk_document(extracted, tokenizer, cfg):
            ids.append(next_id)
            texts[next_id] = c.content
            docs[next_id] = path.name
            rows.append(hash_embedding.embed(chunk_embedding_input(c.content, c.section_heading)))
            next_id += 1
    return Index(ids, texts, docs, np.asarray(rows, dtype=np.float64))


def search(index: Index, question: str, k: int) -> Tuple[List[int], List[float]]:
    q = normalize_for_embedding(question)
    if not hash_embedding.has_features(q):
        return [], []
    sims = index.matrix @ np.asarray(hash_embedding.embed(q))
    order = np.argsort(-sims)[:k]
    return [index.ids[i] for i in order], [float(sims[i]) for i in order]


def apply_floor(ids: Sequence[int], scores: Sequence[float], floor: float, relative: float) -> List[Tuple[int, float]]:
    """Absolute relevance floor, plus an optional relative cutoff at ``relative`` x best score.
    Mirrors RetrievalService.applyFloor in the API."""
    if not scores:
        return []
    cutoff = max(floor, relative * scores[0])
    return [(i, s) for i, s in zip(ids, scores) if s >= cutoff]


def evaluate(index: Index, questions: Sequence[dict], k: int, floors: Sequence[float],
             relatives: Sequence[float] = (0.0,)) -> Dict[Tuple[float, float], List[QuestionResult]]:
    raw = []
    for q in questions:
        ids, scores = search(index, q["question"], k)
        groups = resolve_evidence(q.get("evidence", []), index.texts)
        raw.append((q, ids, scores, groups))
    out = {}
    for floor in floors:
        for rel in relatives:
            results = []
            for q, ids, scores, groups in raw:
                kept = apply_floor(ids, scores, floor, rel)
                results.append(score_question(
                    q["key"], q["category"], q["category"] != "unanswerable", groups,
                    [i for i, _ in kept], [s for _, s in kept], scores[0] if scores else None))
            out[(floor, rel)] = results
    return out


def load_questions(path: Path) -> List[dict]:
    return json.loads(path.read_text(encoding="utf-8"))["questions"]


def main(argv: Optional[Sequence[str]] = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--corpus", type=Path, default=REPO / "eval" / "corpus")
    ap.add_argument("--questions", type=Path, default=REPO / "eval" / "questions.json")
    ap.add_argument("--sizes", type=int, nargs="+", default=[128, 192, 256, 384, 512])
    ap.add_argument("--overlaps", type=int, nargs="+", default=[0, 32, 64])
    ap.add_argument("--floors", type=float, nargs="+",
                    default=[0.0, 0.05, 0.1, 0.12, 0.15, 0.18, 0.2, 0.22, 0.25, 0.3, 0.35, 0.4])
    ap.add_argument("--relative-floors", type=float, nargs="+", default=[0.0, 0.5, 0.7, 0.8, 0.85, 0.9])
    ap.add_argument("--k", type=int, default=5)
    ap.add_argument("--gate", action="store_true", help="exit 1 if the best configuration misses the gates")
    ap.add_argument("--recall-gate", type=float, default=0.85)
    ap.add_argument("--refusal-gate", type=float, default=0.80)
    ap.add_argument("--precision-gate", type=float, default=0.70)
    ap.add_argument("--report", type=Path, help="write the full grid as JSON here")
    args = ap.parse_args(argv)

    questions = load_questions(args.questions)
    tokenizer = RegexTokenizer()
    grid = []
    started = time.time()
    for size in args.sizes:
        for overlap in args.overlaps:
            if overlap >= size // 2:
                continue
            index = build_index(args.corpus, size, overlap, tokenizer)
            for (floor, rel), results in evaluate(index, questions, args.k, args.floors, args.relative_floors).items():
                m = aggregate(results)
                m.update({"chunkSize": size, "overlap": overlap, "floor": floor, "relativeFloor": rel,
                          "chunks": len(index.ids)})
                grid.append(m)

    def ok(m):
        return (m["recallAtK"] >= args.recall_gate and m["refusalAccuracy"] >= args.refusal_gate
                and m["precisionAtK"] >= args.precision_gate)

    # Select by measured Recall@k (§7.3: "select by measured Recall@5 rather than by judgement")
    # among configurations that pass every gate; failing that, among those that at least refuse
    # correctly; then precision and MRR break ties.
    candidates = ([m for m in grid if ok(m)]
                  or [m for m in grid if m["refusalAccuracy"] >= args.refusal_gate]
                  or grid)
    best = max(candidates, key=lambda m: (m["recallAtK"], m["precisionAtK"], m["mrr"]))

    header = (f"{'size':>5} {'ovl':>4} {'floor':>5} {'rel':>4} {'chunks':>6} {'R@k':>6} {'P@k':>6} {'MRR':>6} "
              f"{'refuse':>6} {'falseRef':>8}")
    print(header)
    print("-" * len(header))
    shown = sorted(grid, key=lambda m: (not ok(m), -m["recallAtK"], -m["precisionAtK"]))[:25]
    for m in shown:
        flag = " *" if m is best else ("  " if not ok(m) else " +")
        print(f"{m['chunkSize']:>5} {m['overlap']:>4} {m['floor']:>5.2f} {m['relativeFloor']:>4.2f} {m['chunks']:>6} {m['recallAtK']:>6.3f} "
              f"{m['precisionAtK']:>6.3f} {m['mrr']:>6.3f} {m['refusalAccuracy']:>6.3f} {m['falseRefusalRate']:>8.3f}{flag}")
    print(f"\n{len(grid)} configurations in {time.time() - started:.1f}s; * = selected, + = passes gates")
    print("selected:", json.dumps(best))
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps({"selected": best, "grid": grid}, indent=2) + "\n")
    if args.gate and not ok(best):
        print(f"GATE FAILED: recall@{args.k} {best['recallAtK']} (gate {args.recall_gate}), "
              f"precision@{args.k} {best['precisionAtK']} (gate {args.precision_gate}), "
              f"refusal {best['refusalAccuracy']} (gate {args.refusal_gate})", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
