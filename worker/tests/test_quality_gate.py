"""Offline retrieval quality gate (design doc §7.3 step 23). Runs in CI without Docker."""
import pytest

from insightrag_worker.evaluation import sweep

pytestmark = pytest.mark.quality

# The configuration selected by the last sweep for the hash-v1 embedding (eval/reports/).
SELECTED = ["--sizes", "128", "--overlaps", "0", "--floors", "0.15", "--relative-floors", "0.7"]


def test_selected_configuration_passes_gates(capsys):
    assert sweep.main(SELECTED + ["--gate"]) == 0, capsys.readouterr().err


def test_every_evidence_snippet_exists_in_the_corpus():
    import json

    from insightrag_worker.evaluation.metrics import normalize_text

    corpus = " ".join(normalize_text(p.read_text(encoding="utf-8"))
                      for p in (sweep.REPO / "eval" / "corpus").iterdir())
    questions = json.loads((sweep.REPO / "eval" / "questions.json").read_text())["questions"]
    missing = [(q["key"], e) for q in questions for e in q["evidence"] if normalize_text(e) not in corpus]
    assert not missing
    keys = [q["key"] for q in questions]
    assert len(keys) == len(set(keys))
    assert 80 <= len(questions) <= 160
    assert sum(q["category"] == "unanswerable" for q in questions) >= 15
