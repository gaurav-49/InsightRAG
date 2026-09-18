"""Redis Streams consumer-group plumbing (design doc §4.3, §5.2, §5.7, §5.8).

Delivery is at-least-once. On top of the consumer group this module adds:

* **Membership** — live workers heartbeat into a sorted set; every worker builds the same
  consistent-hash ring from it.
* **Pinning** — a message delivered to a worker that does not own the document on the ring is
  handed to the owner with ``XCLAIM`` (ownership transfer inside the group, no copy). The owner
  drains its own pending list first on every loop. A message is handed off at most once, so
  two workers with momentarily different membership views cannot ping-pong it.
* **Reclaim** — ``XAUTOCLAIM`` takes over messages idle longer than the visibility timeout
  (their worker crashed).
* **Dead-lettering** — a per-document attempt counter; past the threshold the message moves to
  the DLQ stream instead of blocking the queue.
"""
from __future__ import annotations

import logging
import time
from dataclasses import dataclass
from typing import Dict, List, Optional, Tuple

import redis

from .ring import HashRing

log = logging.getLogger(__name__)

MEMBERS_KEY = "insightrag:workers"
ATTEMPTS_KEY = "insightrag:ingest:attempts"
HANDOFF_PREFIX = "insightrag:ingest:handoff:"


@dataclass(frozen=True)
class Message:
    id: str
    fields: Dict[str, str]

    @property
    def document_id(self) -> Optional[str]:
        return self.fields.get("documentId")

    @property
    def storage_key(self) -> Optional[str]:
        return self.fields.get("storageKey")


def _decode(entries) -> List[Message]:
    out = []
    for msg_id, fields in entries or []:
        mid = msg_id.decode() if isinstance(msg_id, bytes) else msg_id
        decoded = {}
        for k, v in (fields or {}).items():
            decoded[k.decode() if isinstance(k, bytes) else k] = v.decode() if isinstance(v, bytes) else v
        out.append(Message(mid, decoded))
    return out


class StreamQueue:
    def __init__(self, client: redis.Redis, stream: str, group: str, consumer: str, dlq_stream: str,
                 virtual_nodes: int = 64, membership_ttl_s: int = 30):
        self.r = client
        self.stream = stream
        self.group = group
        self.consumer = consumer
        self.dlq_stream = dlq_stream
        self.virtual_nodes = virtual_nodes
        self.membership_ttl_s = membership_ttl_s
        self._ring = HashRing([consumer], virtual_nodes)
        self._ring_built_at = 0.0

    # -- setup / membership -------------------------------------------------------------

    def ensure_group(self) -> None:
        try:
            self.r.xgroup_create(self.stream, self.group, id="0", mkstream=True)
        except redis.ResponseError as exc:
            if "BUSYGROUP" not in str(exc):
                raise

    def heartbeat(self) -> None:
        now = time.time()
        pipe = self.r.pipeline()
        pipe.zadd(MEMBERS_KEY, {self.consumer: now})
        pipe.zremrangebyscore(MEMBERS_KEY, "-inf", now - self.membership_ttl_s * 4)
        pipe.execute()

    def leave(self) -> None:
        self.r.zrem(MEMBERS_KEY, self.consumer)

    def live_members(self) -> List[str]:
        cutoff = time.time() - self.membership_ttl_s
        members = self.r.zrangebyscore(MEMBERS_KEY, cutoff, "+inf")
        return sorted(m.decode() if isinstance(m, bytes) else m for m in members)

    def ring(self, max_age_s: float = 5.0) -> HashRing:
        if time.time() - self._ring_built_at > max_age_s:
            members = set(self.live_members()) | {self.consumer}
            self._ring = HashRing(sorted(members), self.virtual_nodes)
            self._ring_built_at = time.time()
        return self._ring

    # -- consuming ----------------------------------------------------------------------

    def read_own_pending(self, count: int = 10) -> List[Message]:
        """Entries already assigned to this consumer: handed-off, reclaimed, or left over from
        before a restart."""
        resp = self.r.xreadgroup(self.group, self.consumer, {self.stream: "0"}, count=count)
        return _decode(resp[0][1]) if resp else []

    def read_new(self, block_ms: int, count: int = 1) -> List[Message]:
        resp = self.r.xreadgroup(self.group, self.consumer, {self.stream: ">"}, count=count, block=block_ms)
        return _decode(resp[0][1]) if resp else []

    def reclaim_stale(self, min_idle_ms: int, count: int = 10) -> List[Message]:
        resp = self.r.xautoclaim(self.stream, self.group, self.consumer, min_idle_ms, "0-0", count=count)
        # redis-py returns [next_id, messages, deleted_ids] on Redis >= 7
        messages = resp[1] if resp else []
        return [m for m in _decode(messages) if m.fields]

    def owner_of(self, document_id: str) -> str:
        return self.ring().owner(document_id) or self.consumer

    def try_handoff(self, msg: Message) -> Optional[str]:
        """Transfer the message to its ring owner. Returns the owner, or None if this worker
        should process it itself."""
        if not msg.document_id:
            return None
        owner = self.owner_of(msg.document_id)
        if owner == self.consumer:
            return None
        # At most one hop per message: the marker is set only by the first handoff.
        if not self.r.set(HANDOFF_PREFIX + msg.id, owner, nx=True, ex=24 * 3600):
            return None
        claimed = self.r.xclaim(self.stream, self.group, owner, 0, [msg.id], justid=True)
        if not claimed:
            return None
        log.info("handed off job to ring owner", extra={"message_id": msg.id, "owner": owner})
        return owner

    def refresh(self, msg: Message) -> None:
        """Reset the idle clock on a long-running job so XAUTOCLAIM does not steal it."""
        self.r.xclaim(self.stream, self.group, self.consumer, 0, [msg.id], justid=True)

    def ack(self, msg: Message) -> None:
        pipe = self.r.pipeline()
        pipe.xack(self.stream, self.group, msg.id)
        pipe.delete(HANDOFF_PREFIX + msg.id)
        if msg.document_id:
            pipe.hdel(ATTEMPTS_KEY, msg.document_id)
        pipe.execute()

    def record_attempt(self, msg: Message) -> int:
        return int(self.r.hincrby(ATTEMPTS_KEY, msg.document_id or msg.id, 1))

    def forget_attempt(self, msg: Message) -> None:
        """Undo record_attempt for a delivery that did no work (e.g. deferred as busy)."""
        self.r.hincrby(ATTEMPTS_KEY, msg.document_id or msg.id, -1)

    def dead_letter(self, msg: Message, reason: str, attempts: int) -> None:
        fields = dict(msg.fields)
        fields.update({"reason": reason[:1000], "attempts": str(attempts), "originalId": msg.id,
                       "deadLetteredAt": str(int(time.time() * 1000))})
        self.r.xadd(self.dlq_stream, fields, maxlen=100_000, approximate=True)
        self.ack(msg)

    # -- observability ------------------------------------------------------------------

    def depth(self) -> Tuple[int, int, Optional[float]]:
        """(undelivered lag, pending count, age in seconds of the oldest unfinished message)."""
        lag, pending = 0, 0
        oldest_ms: Optional[int] = None
        for g in self.r.xinfo_groups(self.stream):
            name = g["name"].decode() if isinstance(g["name"], bytes) else g["name"]
            if name != self.group:
                continue
            pending = int(g.get("pending") or 0)
            lag = int(g.get("lag") or 0)
            last = g.get("last-delivered-id")
            last = last.decode() if isinstance(last, bytes) else last
            if lag and last:
                nxt = self.r.xrange(self.stream, min="(" + last, count=1)
                if nxt:
                    oldest_ms = int(_decode(nxt)[0].id.split("-")[0])
        if pending:
            summary = self.r.xpending(self.stream, self.group)
            min_id = summary.get("min")
            if min_id:
                min_id = min_id.decode() if isinstance(min_id, bytes) else min_id
                ms = int(min_id.split("-")[0])
                oldest_ms = ms if oldest_ms is None else min(oldest_ms, ms)
        age = None if oldest_ms is None else max(0.0, time.time() - oldest_ms / 1000)
        return lag, pending, age
