import pytest

from insightrag_worker.chunker import ChunkingConfig, chunk_document
from insightrag_worker.extract import ExtractedDocument, extract
from insightrag_worker.tokenizer import RegexTokenizer

TOK = RegexTokenizer()


WORDS = "alpha bravo charlie delta echo foxtrot golf hotel india juliet kilo lima mike".split()


def sentence(i: int, words: int = 12) -> str:
    """``words`` single-token words plus a full stop: exactly words + 1 regex tokens."""
    return " ".join(WORDS[(i + j) % len(WORDS)] for j in range(words)) + "."


def doc_of(paragraphs):
    return ExtractedDocument(text="\n\n".join(paragraphs))


def test_short_document_is_one_chunk():
    doc = doc_of(["Hello world. This is short."])
    chunks = chunk_document(doc, TOK, ChunkingConfig(64, 8))
    assert len(chunks) == 1
    assert chunks[0].content == doc.text
    assert (chunks[0].char_start, chunks[0].char_end) == (0, len(doc.text))


def test_chunks_respect_size_and_are_exact_spans():
    paras = [" ".join(sentence(p * 10 + s) for s in range(4)) for p in range(20)]
    doc = doc_of(paras)
    cfg = ChunkingConfig(size_tokens=100, overlap_tokens=16)
    chunks = chunk_document(doc, TOK, cfg)
    assert len(chunks) > 5
    for c in chunks:
        assert c.token_count <= cfg.size_tokens
        assert doc.text[c.char_start:c.char_end] == c.content
        assert TOK.count(c.content) == c.token_count
    assert [c.ordinal for c in chunks] == list(range(len(chunks)))


def test_prefers_paragraph_breaks_within_tolerance():
    # Each paragraph is ~52 tokens (4 x 13); with size 120 and tolerance 0.25 the cut window is
    # tokens 90..120, which contains a paragraph break at ~104.
    paras = [" ".join(sentence(p * 10 + s) for s in range(4)) for p in range(6)]
    doc = doc_of(paras)
    chunks = chunk_document(doc, TOK, ChunkingConfig(120, 0, 0.25))
    first = chunks[0]
    assert doc.text[first.char_end:first.char_end + 2] == "\n\n"


def test_falls_back_to_sentence_boundary():
    # One giant paragraph: no paragraph breaks, so cuts land after a full stop.
    doc = doc_of([" ".join(sentence(s) for s in range(40))])
    chunks = chunk_document(doc, TOK, ChunkingConfig(100, 10))
    for c in chunks[:-1]:
        assert c.content.endswith(".")


def test_mid_sentence_split_only_as_last_resort():
    doc = doc_of([" ".join(f"word{j}" for j in range(500))])  # no punctuation at all
    chunks = chunk_document(doc, TOK, ChunkingConfig(100, 10))
    assert len(chunks) >= 5
    assert all(c.token_count <= 100 for c in chunks)


def test_overlap_carries_tail_into_next_chunk():
    doc = doc_of([" ".join(f"word{j}" for j in range(400))])
    cfg = ChunkingConfig(100, 20)
    chunks = chunk_document(doc, TOK, cfg)
    for prev, nxt in zip(chunks, chunks[1:]):
        assert nxt.char_start < prev.char_end, "consecutive chunks must overlap"
        shared = doc.text[nxt.char_start:prev.char_end]
        assert TOK.count(shared) == cfg.overlap_tokens


def test_fact_straddling_boundary_survives_whole_in_one_chunk():
    filler = " ".join(f"filler{j}" for j in range(95))
    fact = "The notice period is sixty days"
    doc = doc_of([filler + " " + fact + " " + " ".join(f"tail{j}" for j in range(200))])
    chunks = chunk_document(doc, TOK, ChunkingConfig(100, 16))
    assert any(fact in c.content for c in chunks)


def test_every_token_is_covered():
    doc = doc_of([" ".join(sentence(p * 10 + s) for s in range(3)) for p in range(30)])
    chunks = chunk_document(doc, TOK, ChunkingConfig(80, 12))
    covered = set()
    for c in chunks:
        covered.update(range(c.char_start, c.char_end))
    for s, e in TOK.spans(doc.text):
        assert s in covered


def test_metadata_page_and_heading():
    md = "# Leave Policy\n\nIntro text here.\n\n## Notice\n\n" + " ".join(sentence(i) for i in range(30))
    doc = extract(md.encode(), "text/markdown")
    chunks = chunk_document(doc, TOK, ChunkingConfig(64, 8))
    assert chunks[0].section_heading == "Leave Policy"
    assert chunks[-1].section_heading == "Notice"
    assert all(c.page_number is None for c in chunks)

    paged = ExtractedDocument(text="a " * 50 + "\n\n" + "b " * 50, page_starts=[0, 102])
    pc = chunk_document(paged, TOK, ChunkingConfig(32, 4))
    assert pc[0].page_number == 1 and pc[-1].page_number == 2


@pytest.mark.parametrize("size,overlap", [(8, 0), (64, 32), (64, -1)])
def test_invalid_config_rejected(size, overlap):
    with pytest.raises(ValueError):
        ChunkingConfig(size, overlap)


def test_empty_text_gives_no_chunks():
    assert chunk_document(ExtractedDocument(text="   \n\n  "), TOK, ChunkingConfig(64, 8)) == []
