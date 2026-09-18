"""Tokenisers used to measure chunk size (design doc §5.1: tokens of the embedding model,
not characters).

Each tokeniser returns character spans so the chunker can cut the original text exactly at
token boundaries and record character offsets.
"""
from __future__ import annotations

import logging
import re
from typing import List, Tuple

log = logging.getLogger(__name__)

Span = Tuple[int, int]


class Tokenizer:
    name = "base"

    def spans(self, text: str) -> List[Span]:  # pragma: no cover - interface
        raise NotImplementedError

    def count(self, text: str) -> int:
        return len(self.spans(text))


class RegexTokenizer(Tokenizer):
    """Word pieces and individual punctuation marks; long words are split every 8 characters
    to approximate sub-word tokenisers (hash and nomic models have no public BPE here)."""

    name = "regex-v1"
    _PATTERN = re.compile(r"[A-Za-z]{1,8}|\d{1,4}|[^\sA-Za-z\d]")

    def spans(self, text: str) -> List[Span]:
        return [m.span() for m in self._PATTERN.finditer(text)]


class TiktokenTokenizer(Tokenizer):
    def __init__(self, encoding: str = "cl100k_base"):
        import tiktoken  # optional dependency

        self._enc = tiktoken.get_encoding(encoding)
        self.name = f"tiktoken:{encoding}"

    def spans(self, text: str) -> List[Span]:
        tokens = self._enc.encode(text, disallowed_special=())
        _, offsets = self._enc.decode_with_offsets(tokens)
        spans: List[Span] = []
        for i, start in enumerate(offsets):
            end = offsets[i + 1] if i + 1 < len(offsets) else len(text)
            if end > start:
                spans.append((start, end))
        return spans

    def count(self, text: str) -> int:
        return len(self._enc.encode(text, disallowed_special=()))


def for_provider(provider: str) -> Tokenizer:
    if provider.lower() == "openai":
        try:
            return TiktokenTokenizer()
        except Exception as exc:  # tiktoken missing or its BPE file unavailable offline
            log.warning("tiktoken unavailable, falling back to regex tokenizer", extra={"error": str(exc)})
    return RegexTokenizer()
