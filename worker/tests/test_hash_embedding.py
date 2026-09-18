import json
import math
from pathlib import Path

import pytest

from insightrag_worker import hash_embedding
from insightrag_worker.embeddings import normalize_for_embedding

FIXTURES = Path(__file__).resolve().parents[2] / "contracts" / "hash-embedding-fixtures.json"


def cosine(a, b):
    return sum(x * y for x, y in zip(a, b))


def test_fnv1a_reference_values():
    # Published FNV-1a 32-bit test vectors.
    assert hash_embedding.fnv1a32(b"") == 0x811C9DC5
    assert hash_embedding.fnv1a32(b"a") == 0xE40C292C
    assert hash_embedding.fnv1a32(b"foobar") == 0xBF9CF968


@pytest.mark.parametrize("term,expected", [
    ("policies", "policy"), ("processing", "process"), ("processed", "process"),
    ("days", "day"), ("class", "class"), ("is", "is"), ("bus", "bus"), ("buses", "buse"), ("ring", "ring"),
])
def test_stemming_rules(term, expected):
    assert hash_embedding.stem(term) == expected


def test_vectors_are_unit_length_and_1536_wide():
    v = hash_embedding.embed("Reimbursement requests need receipts.")
    assert len(v) == 1536
    assert math.isclose(math.sqrt(sum(x * x for x in v)), 1.0, rel_tol=1e-9)


def test_stopword_only_text_maps_to_e0():
    v = hash_embedding.embed("the and of")
    assert v[0] == 1.0 and sum(abs(x) for x in v) == 1.0
    assert not hash_embedding.has_features("the and of")


def test_word_overlap_orders_similarity():
    q = hash_embedding.embed("notice period for resignation")
    near = hash_embedding.embed("The notice period for resignation is 60 days.")
    far = hash_embedding.embed("Laptops are refreshed every three years.")
    assert cosine(q, near) > 0.5 > cosine(q, far)


def test_matches_cross_language_fixtures():
    """The same file is asserted by the Java API's test suite (contracts/README.md)."""
    data = json.loads(FIXTURES.read_text(encoding="utf-8"))
    assert data["model"] == hash_embedding.MODEL_ID
    for case in data["cases"]:
        normalized = normalize_for_embedding(case["input"])
        assert normalized == case["normalized"]
        assert hash_embedding.terms(normalized) == case["terms"]
        vec = hash_embedding.embed(normalized)
        expected = {int(k): v for k, v in case["vector"].items()}
        assert {i for i, x in enumerate(vec) if x != 0.0} == set(expected)
        for i, x in expected.items():
            assert vec[i] == pytest.approx(x, abs=1e-9)
