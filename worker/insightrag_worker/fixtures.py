"""Regenerate contracts/hash-embedding-fixtures.json (run after an intentional hash-v1 change,
which also requires re-embedding every corpus built with it)."""
from __future__ import annotations

import json
import sys
from pathlib import Path

from . import hash_embedding
from .embeddings import normalize_for_embedding

TEXTS = [
    "What is the notice period for employees?",
    "How much notice must I give before resigning?",
    "The   notice period is 60 days\tfor employees on permanent contracts.",
    "Policies, policies and more POLICIES: processing, processed, classes, running.",
    "the and of to a in is",
    "",
    "Café résumé naïve — ﬁle ligatures ２０２６ fullwidth digits",
    "Section 4.2: Travel & Expense — reimbursements over $500 require VP approval within 30 days.",
]


def build() -> dict:
    cases = []
    for text in TEXTS:
        normalized = normalize_for_embedding(text)
        vec = hash_embedding.embed(normalized)
        sparse = {str(i): round(v, 12) for i, v in enumerate(vec) if v != 0.0}
        cases.append({
            "input": text,
            "normalized": normalized,
            "terms": hash_embedding.terms(normalized),
            "vector": sparse,
        })
    return {"model": hash_embedding.MODEL_ID, "dimensions": hash_embedding.DIMENSIONS, "cases": cases}


def main() -> None:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2] / "contracts" / "hash-embedding-fixtures.json"
    out.write_text(json.dumps(build(), indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"wrote {out}")


if __name__ == "__main__":
    main()
