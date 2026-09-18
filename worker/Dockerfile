FROM python:3.12-slim
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 PIP_NO_CACHE_DIR=1
WORKDIR /app
COPY pyproject.toml ./
COPY insightrag_worker ./insightrag_worker
RUN pip install ".[tiktoken]" \
 # Bake the cl100k BPE into the image so the openai tokenizer never downloads at runtime.
 && python -c "import tiktoken; tiktoken.get_encoding('cl100k_base')"
RUN useradd --system --uid 10001 insightrag && mkdir -p /data/uploads && chown -R insightrag /data
USER insightrag
EXPOSE 9100
HEALTHCHECK --interval=15s --timeout=3s --start-period=20s --retries=5 \
  CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:9100/metrics', timeout=2)" || exit 1
ENTRYPOINT ["insightrag-worker"]
