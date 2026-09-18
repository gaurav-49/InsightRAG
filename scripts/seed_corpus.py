"""Upload every file in a directory (default eval/corpus) and wait until each is terminal.

    python scripts/seed_corpus.py [--dir eval/corpus] [--source handbook]
"""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from insightrag_client import API, Client  # noqa: E402

REPO = Path(__file__).resolve().parents[1]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", type=Path, default=REPO / "eval" / "corpus")
    ap.add_argument("--source", default="eval-corpus")
    ap.add_argument("--api", default=API)
    args = ap.parse_args()

    client = Client(args.api, Client.dev_token(args.api))
    client.wait_healthy()
    ids = []
    for path in sorted(args.dir.iterdir()):
        if path.is_file():
            r = client.upload(path.name, path.read_bytes(), args.source)
            r.raise_for_status()
            body = r.json()
            ids.append(body["documentId"])
            print(f"{r.status_code} {path.name} -> {body['documentId']} ({'duplicate' if body['duplicate'] else 'queued'})")
    states = client.wait_terminal(ids)
    failed = {d: s for d, s in states.items() if s["status"] == "FAILED"}
    for d, s in states.items():
        print(f"  {s['status']:<8} {s['chunkCount']:>4} chunks  {s['filename']}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
