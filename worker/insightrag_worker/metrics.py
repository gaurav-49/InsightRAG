"""Worker Prometheus metrics (design doc §9.3), served on WORKER_METRICS_PORT."""
from prometheus_client import Counter, Gauge, Histogram

JOBS = Counter("insightrag_worker_jobs_total", "Ingestion jobs by outcome", ["outcome"])
DURATION = Histogram("insightrag_worker_job_duration_seconds", "Wall time to index one document",
                     buckets=(0.5, 1, 2.5, 5, 10, 30, 60, 120, 180, 300, 600))
CHUNKS = Counter("insightrag_worker_chunks_total", "Chunks written")
EMBED_CALLS = Counter("insightrag_worker_embedding_calls_total", "Embedding provider calls (batches)")
QUEUE_LAG = Gauge("insightrag_ingest_queue_lag", "Messages not yet delivered to any worker")
QUEUE_PENDING = Gauge("insightrag_ingest_queue_pending", "Messages delivered but not acknowledged")
QUEUE_OLDEST_AGE = Gauge("insightrag_ingest_queue_oldest_age_seconds", "Age of the oldest unfinished message")
LIVE_WORKERS = Gauge("insightrag_worker_live_members", "Workers currently on the hash ring")
