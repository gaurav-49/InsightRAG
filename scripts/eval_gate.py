"""Retrieval quality gate against a running stack (design doc §7.3 steps 21–23).

Loads eval/questions.json into the API, runs /api/v1/eval/run, prints a report next to the
previous baseline, writes eval/reports/latest.json, and exits non-zero when Recall@5 or
refusal accuracy is below its gate.

    python scripts/eval_gate.py [--api http://localhost:8080]
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from insightrag_client import API, Client  # noqa: E402

REPO = Path(__file__).resolve().parents[1]


def fmt(v):
    return "—" if v is None else (f"{v:.3f}" if isinstance(v, float) else str(v))


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--api", default=API)
    ap.add_argument("--questions", type=Path, default=REPO / "eval" / "questions.json")
    ap.add_argument("--report", type=Path, default=REPO / "eval" / "reports" / "latest.json")
    args = ap.parse_args()

    client = Client(args.api, Client.dev_token(args.api), timeout=300)
    client.wait_healthy()
    questions = json.loads(args.questions.read_text())["questions"]
    r = client.http.put("/api/v1/eval/questions", json={"questions": questions})
    r.raise_for_status()
    r = client.http.post("/api/v1/eval/run", json={})
    r.raise_for_status()
    report = r.json()

    k = report["k"]
    baseline = report.get("baseline") or {}
    rows = [("Recall@%d" % k, f"recallAt{k}"), ("Precision@%d" % k, f"precisionAt{k}"), ("MRR", "mrr"),
            ("Refusal accuracy", "refusalAccuracy"), ("False refusal rate", "falseRefusalRate"),
            ("Cost per query (tokens)", "costPerQueryTokens")]
    print(f"Evaluation run {report.get('runId')} — {report['questions']} questions "
          f"({report['answerable']} answerable, {report['unanswerable']} unanswerable)")
    print(f"config: {json.dumps(report['config'])}")
    print(f"{'metric':<26}{'current':>10}{'baseline':>10}")
    for label, key in rows:
        print(f"{label:<26}{fmt(report.get(key)):>10}{fmt(baseline.get(key)):>10}")
    misses = [q["key"] for q in report["perQuestion"] if q["answerable"] and (q["recall"] or 0) < 1]
    fabrications = [q["key"] for q in report["perQuestion"] if not q["answerable"] and not q["refused"]]
    print(f"answerable with missed evidence: {misses}")
    print(f"unanswerable not refused:       {fabrications}")

    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(report, indent=2) + "\n")
    gates = report["gates"]
    print(f"gates: {json.dumps(gates)}")
    if not gates["pass"]:
        print("QUALITY GATE FAILED", file=sys.stderr)
        return 1
    print("quality gate passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
