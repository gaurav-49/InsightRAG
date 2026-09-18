"""Tiny HTTP client shared by the operational scripts (seed, eval gate, e2e)."""
from __future__ import annotations

import os
import time
from typing import Any, Dict, List, Optional

import httpx

API = os.environ.get("INSIGHTRAG_API", "http://localhost:8080")


class Client:
    def __init__(self, base: str = API, token: Optional[str] = None, timeout: float = 60.0):
        self.base = base.rstrip("/")
        self.http = httpx.Client(base_url=self.base, timeout=timeout)
        if token:
            self.http.headers["Authorization"] = f"Bearer {token}"

    @staticmethod
    def dev_token(base: str = API, scopes: List[str] = ("admin", "query"), subject: Optional[str] = None) -> str:
        body: Dict[str, Any] = {"scopes": list(scopes)}
        if subject:
            body["subject"] = subject
        r = httpx.post(base.rstrip("/") + "/api/v1/auth/dev-token", json=body, timeout=10)
        r.raise_for_status()
        return r.json()["accessToken"]

    def wait_healthy(self, timeout_s: float = 180) -> Dict[str, Any]:
        deadline = time.time() + timeout_s
        last = None
        while time.time() < deadline:
            try:
                r = self.http.get("/api/v1/health")
                last = r.json()
                if r.status_code == 200 and last.get("status") == "UP":
                    return last
            except httpx.HTTPError as exc:
                last = str(exc)
            time.sleep(2)
        raise TimeoutError(f"API not healthy after {timeout_s}s: {last}")

    def _with_backoff(self, send, attempts: int = 10) -> httpx.Response:
        """Honour 429 Retry-After (design doc §5.6) instead of failing the script."""
        for _ in range(attempts):
            r = send()
            if r.status_code != 429:
                return r
            time.sleep(float(r.headers.get("Retry-After", "1")))
        return r

    def upload(self, filename: str, data: bytes, source: Optional[str] = None) -> httpx.Response:
        params = {"source": source} if source else None
        return self._with_backoff(lambda: self.http.post("/api/v1/documents", files={"file": (filename, data)}, params=params))

    def document(self, doc_id: str) -> Dict[str, Any]:
        r = self.http.get(f"/api/v1/documents/{doc_id}")
        r.raise_for_status()
        return r.json()

    def wait_terminal(self, doc_ids: List[str], timeout_s: float = 180) -> Dict[str, Dict[str, Any]]:
        deadline = time.time() + timeout_s
        states: Dict[str, Dict[str, Any]] = {}
        while time.time() < deadline:
            states = {d: self.document(d) for d in doc_ids}
            if all(s["status"] in ("INDEXED", "FAILED") for s in states.values()):
                return states
            time.sleep(1)
        raise TimeoutError(f"documents not terminal after {timeout_s}s: "
                           f"{ {d: s['status'] for d, s in states.items()} }")

    def query(self, question: str, **filters: Any) -> httpx.Response:
        body: Dict[str, Any] = {"question": question}
        if filters:
            body["filters"] = filters
        return self._with_backoff(lambda: self.http.post("/api/v1/query", json=body))
