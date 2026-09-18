import uuid
from collections import Counter

from insightrag_worker.ring import HashRing

KEYS = [str(uuid.UUID(int=i * 7919 + 13)) for i in range(20_000)]


def assignment(ring):
    return {k: ring.owner(k) for k in KEYS}


def test_empty_ring_has_no_owner():
    assert HashRing().owner("x") is None


def test_deterministic_and_order_independent():
    a = HashRing(["w1", "w2", "w3"])
    b = HashRing(["w3", "w1", "w2"])
    assert assignment(a) == assignment(b)


def test_virtual_nodes_smooth_distribution():
    counts = Counter(assignment(HashRing(["w1", "w2", "w3"], virtual_nodes=128)).values())
    share = [c / len(KEYS) for c in counts.values()]
    assert max(share) - min(share) < 0.15
    single = Counter(assignment(HashRing(["w1", "w2", "w3"], virtual_nodes=1)).values())
    # With one point per worker the split is typically far more uneven.
    assert max(single.values()) >= max(counts.values())


def test_adding_a_worker_moves_only_its_arc():
    before = assignment(HashRing(["w1", "w2", "w3"]))
    after = assignment(HashRing(["w1", "w2", "w3", "w4"]))
    moved = [k for k in KEYS if before[k] != after[k]]
    assert all(after[k] == "w4" for k in moved), "keys only move to the new worker"
    assert 0.15 < len(moved) / len(KEYS) < 0.35  # ≈ 1/4


def test_modulo_partitioning_would_remap_about_three_quarters():
    def modulo(n):
        return {k: int(uuid.UUID(k)) % n for k in KEYS}
    before, after = modulo(3), modulo(4)
    moved = sum(before[k] != after[k] for k in KEYS) / len(KEYS)
    assert moved > 0.7


def test_removing_a_worker_moves_only_its_keys():
    ring = HashRing(["w1", "w2", "w3", "w4"])
    before = assignment(ring)
    ring.remove("w2")
    after = assignment(ring)
    for k in KEYS:
        if before[k] != "w2":
            assert after[k] == before[k]
        else:
            assert after[k] != "w2"
