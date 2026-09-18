"""Mint an HS256 JWT for the API without the dev-token endpoint (e.g. for load tests).

    python scripts/mint_token.py --scopes query admin --secret "$JWT_SECRET"
"""
from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import os
import time
import uuid


def b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def mint(secret: str, scopes, subject: str, ttl: int, issuer: str) -> str:
    header = {"alg": "HS256", "typ": "JWT"}
    now = int(time.time())
    claims = {"iss": issuer, "sub": subject, "iat": now, "exp": now + ttl, "scope": " ".join(scopes)}
    signing = b64(json.dumps(header, separators=(",", ":")).encode()) + "." + b64(json.dumps(claims, separators=(",", ":")).encode())
    sig = hmac.new(secret.encode(), signing.encode(), hashlib.sha256).digest()
    return signing + "." + b64(sig)


if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    ap.add_argument("--secret", default=os.environ.get("JWT_SECRET", "dev-only-secret-change-me-dev-only-secret"))
    ap.add_argument("--scopes", nargs="+", default=["query"])
    ap.add_argument("--subject", default=str(uuid.uuid4()))
    ap.add_argument("--ttl", type=int, default=3600)
    ap.add_argument("--issuer", default=os.environ.get("JWT_ISSUER", "insightrag"))
    a = ap.parse_args()
    print(mint(a.secret, a.scopes, a.subject, a.ttl, a.issuer))
