"""Consistent-hash ring with virtual nodes (design doc §5.8).

Used to pin every job for a given document to one worker. Adding or removing a worker moves
only the keys on the arcs that worker gains or loses (≈ 1/N of the keyspace) instead of the
≈ (N-1)/N that ``hash(key) % N`` remaps.
"""
from __future__ import annotations

import bisect
import hashlib
from typing import Dict, Iterable, List, Optional


def _point(value: str) -> int:
    return int.from_bytes(hashlib.sha1(value.encode("utf-8")).digest()[:8], "big")


class HashRing:
    def __init__(self, nodes: Iterable[str] = (), virtual_nodes: int = 64):
        if virtual_nodes < 1:
            raise ValueError("virtual_nodes must be >= 1")
        self.virtual_nodes = virtual_nodes
        self._points: List[int] = []
        self._owners: Dict[int, str] = {}
        self._nodes: set = set()
        for node in nodes:
            self.add(node)

    @property
    def nodes(self) -> frozenset:
        return frozenset(self._nodes)

    def add(self, node: str) -> None:
        if node in self._nodes:
            return
        self._nodes.add(node)
        for i in range(self.virtual_nodes):
            p = _point(f"{node}#{i}")
            # A collision between two nodes' virtual points is astronomically unlikely with
            # 64-bit points; resolve it deterministically anyway so every worker agrees.
            if p in self._owners and self._owners[p] < node:
                continue
            if p not in self._owners:
                bisect.insort(self._points, p)
            self._owners[p] = node

    def remove(self, node: str) -> None:
        if node not in self._nodes:
            return
        self._nodes.discard(node)
        remaining = sorted(self._nodes)
        self._points, self._owners = [], {}
        self._nodes = set()
        for n in remaining:
            self.add(n)

    def owner(self, key: str) -> Optional[str]:
        """Hash the key and walk clockwise to the first virtual node."""
        if not self._points:
            return None
        i = bisect.bisect_right(self._points, _point(key))
        if i == len(self._points):
            i = 0  # wrap around the ring
        return self._owners[self._points[i]]
