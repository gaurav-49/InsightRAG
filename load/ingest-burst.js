// Queue depth under an ingestion burst (design doc §8): upload N distinct documents as fast as
// the ingest limiter allows, then watch insightrag_ingest_queue_lag drain on /api/v1/metrics.
//
//   k6 run -e TOKEN=$(python scripts/mint_token.py --scopes admin) load/ingest-burst.js
import http from 'k6/http';
import { check, sleep } from 'k6';

const API = __ENV.API || 'http://localhost:8080';
const filler = open('../eval/corpus/engineering-handbook.md');

export const options = { vus: 5, iterations: Number(__ENV.DOCS || 100) };

export default function () {
  const unique = `\n\nBurst document ${__VU}-${__ITER}-${Date.now()}\n`;
  const res = http.post(`${API}/api/v1/documents`, {
    file: http.file(filler + unique, `burst-${__VU}-${__ITER}.md`, 'text/markdown'),
    source: 'load-test',
  }, { headers: { Authorization: `Bearer ${__ENV.TOKEN}` } });
  check(res, { 'accepted or limited': (r) => r.status === 202 || r.status === 429 });
  if (res.status === 429) sleep(Number(res.headers['Retry-After'] || 1));
}

export function teardown() {
  for (let i = 0; i < 60; i++) {
    const m = http.get(`${API}/api/v1/metrics`).body;
    const lag = (m.match(/insightrag_ingest_queue_lag\{[^}]*\} ([0-9.]+)/) || [])[1];
    console.log(`t+${i * 5}s queue lag=${lag}`);
    if (lag === '0.0') return;
    sleep(5);
  }
}
