"""Overlapping, boundary-aware chunking (design doc §5.1).

* Size and overlap are measured in tokens of the embedding model's tokeniser.
* A chunk ends at a paragraph break if one lies within the tolerance window just below the
  target size; otherwise at the last sentence end in the second half of the window; only as a
  last resort mid-sentence (at a token boundary, never mid-token).
* The next chunk begins ``overlap`` tokens before the previous chunk ended, so a fact that
  straddles a boundary survives whole in at least one neighbour.
* Every chunk is a contiguous span of the extracted text, so its character offsets, page and
  governing section heading are exact.
"""
from __future__ import annotations

import bisect
import re
from dataclasses import dataclass
from typing import List, Optional

from .extract import ExtractedDocument
from .tokenizer import Tokenizer

_PARAGRAPH_BREAK = re.compile(r"\n[ \t]*\n")
# End of sentence: terminal punctuation, optional closing quote/bracket, then whitespace.
_SENTENCE_END = re.compile(r"[.!?][\"')\]]*(?=\s)")


@dataclass(frozen=True)
class Chunk:
    ordinal: int
    content: str
    token_count: int
    char_start: int
    char_end: int
    page_number: Optional[int]
    section_heading: Optional[str]


@dataclass(frozen=True)
class ChunkingConfig:
    size_tokens: int = 512
    overlap_tokens: int = 64
    # Fraction of size_tokens below the target in which a paragraph break is preferred.
    boundary_tolerance: float = 0.25

    def __post_init__(self) -> None:
        if self.size_tokens < 16:
            raise ValueError("chunk size must be at least 16 tokens")
        if not 0 <= self.overlap_tokens < self.size_tokens // 2:
            raise ValueError("chunk overlap must be non-negative and less than half the chunk size")
        if not 0 <= self.boundary_tolerance < 1:
            raise ValueError("boundary tolerance must be in [0, 1)")


def _boundary_token_indices(pattern: "re.Pattern[str]", text: str, token_ends: List[int]) -> List[int]:
    """Token index *after which* each boundary falls (i.e. a legal exclusive chunk end)."""
    out = []
    for m in pattern.finditer(text):
        # Number of tokens that end at or before the boundary position.
        idx = bisect.bisect_right(token_ends, m.end())
        if not out or out[-1] != idx:
            out.append(idx)
    return out


def _last_in_range(sorted_vals: List[int], lo: int, hi: int) -> Optional[int]:
    """Largest value v with lo < v <= hi."""
    i = bisect.bisect_right(sorted_vals, hi)
    if i and sorted_vals[i - 1] > lo:
        return sorted_vals[i - 1]
    return None


def chunk_document(doc: ExtractedDocument, tokenizer: Tokenizer, config: ChunkingConfig) -> List[Chunk]:
    text = doc.text
    spans = tokenizer.spans(text)
    n = len(spans)
    if n == 0:
        return []
    token_ends = [e for _, e in spans]
    paragraphs = _boundary_token_indices(_PARAGRAPH_BREAK, text, token_ends)
    sentences = _boundary_token_indices(_SENTENCE_END, text, token_ends)

    size = config.size_tokens
    overlap = config.overlap_tokens
    tolerance = max(1, int(size * config.boundary_tolerance))

    chunks: List[Chunk] = []
    start = 0
    while start < n:
        hard_end = min(start + size, n)
        if hard_end == n:
            cut = n
        else:
            cut = (_last_in_range(paragraphs, max(start, hard_end - tolerance), hard_end)
                   or _last_in_range(sentences, start + size // 2, hard_end)
                   or hard_end)
        char_start, char_end = spans[start][0], spans[cut - 1][1]
        content = text[char_start:char_end]
        if content.strip():
            chunks.append(Chunk(
                ordinal=len(chunks),
                content=content,
                token_count=cut - start,
                char_start=char_start,
                char_end=char_end,
                page_number=doc.page_at(char_start),
                section_heading=doc.heading_at(char_start),
            ))
        if cut >= n:
            break
        next_start = cut - overlap
        # Guarantee progress, and never start a chunk inside the previous chunk's first half.
        start = next_start if next_start > start + size // 2 else cut
    return chunks
