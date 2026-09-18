// Sustained query throughput with a realistic repeat distribution (design doc §8, load row).
//
//   k6 run -e JWT_SECRET=dev-only-secret-change-me-dev-only-secret load/query-load.js
//
// Every VU mints its own token (its own client id), so the per-client token bucket sees
// multi-user traffic rather than one client being throttled.
//
// Questions are drawn Zipf-style from the labelled set, so a few questions dominate as in real
// traffic; a share are lightly paraphrased (extra words / casing) to exercise the L1 and L2
// tiers. Thresholds encode NFR-01 and the ≥30% cache-served criterion of §1.3.
import http from 'k6/http';
import encoding from 'k6/encoding';
import crypto from 'k6/crypto';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const API = __ENV.API || 'http://localhost:8080';
const questions = JSON.parse(open('../eval/questions.json')).questions.map((q) => q.question);

const cacheServed = new Rate('cache_served');
const noAnswer = new Rate('no_answer');
const hitLatency = new Trend('latency_cache_hit', true);
const missLatency = new Trend('latency_cache_miss', true);
const tiers = new Counter('tier_total');

export const options = {
  scenarios: {
    sustained: { executor: 'constant-arrival-rate', rate: Number(__ENV.RATE || 50), timeUnit: '1s',
                 duration: __ENV.DURATION || '2m', preAllocatedVUs: 50, maxVUs: 200 },
  },
  thresholds: {
    http_req_failed: ['rate<0.01'],
    latency_cache_miss: ['p(95)<4000'],
    latency_cache_hit: ['p(95)<200'],
    cache_served: ['rate>=0.30'],
  },
};

// Zipf(s=1.1) rank sampling over the question pool.
const weights = questions.map((_, i) => 1 / Math.pow(i + 1, 1.1));
const total = weights.reduce((a, b) => a + b, 0);
function pick() {
  let r = Math.random() * total;
  for (let i = 0; i < weights.length; i++) { r -= weights[i]; if (r <= 0) return questions[i]; }
  return questions[0];
}
function vary(q) {
  const x = Math.random();
  if (x < 0.15) return q.toUpperCase();          // same after normalisation -> L1
  if (x < 0.30) return 'So, ' + q;               // stop-word-only change -> L2 with hash-v1
  return q;
}

const SECRET = __ENV.JWT_SECRET || 'dev-only-secret-change-me-dev-only-secret';
let token = null;
function mint(subject) {
  const b64 = (o) => encoding.b64encode(JSON.stringify(o), 'rawurl');
  const now = Math.floor(Date.now() / 1000);
  const unsigned = `${b64({ alg: 'HS256', typ: 'JWT' })}.${b64({ iss: __ENV.JWT_ISSUER || 'insightrag', sub: subject,
    iat: now, exp: now + 3600, scope: 'query' })}`;
  return `${unsigned}.${crypto.hmac('sha256', SECRET, unsigned, 'base64rawurl')}`;
}

export default function () {
  token = token || mint(`load-vu-${__VU}`);
  const res = http.post(`${API}/api/v1/query`, JSON.stringify({ question: vary(pick()) }), {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    tags: { name: 'query' },
  });
  if (res.status === 429) return; // limiter doing its job; excluded from latency stats
  check(res, { 'status 200': (r) => r.status === 200 });
  if (res.status !== 200) return;
  const body = res.json();
  tiers.add(1, { tier: body.cacheTier });
  cacheServed.add(body.cached);
  noAnswer.add(body.status === 'NO_ANSWER');
  (body.cached ? hitLatency : missLatency).add(res.timings.duration);
}
