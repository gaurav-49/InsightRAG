.PHONY: help up down logs test test-api test-api-it test-worker test-worker-it sweep seed eval e2e load clean

VENV := worker/.venv
PY := $(VENV)/bin/python

help:
	@grep -E '^[a-z-]+:.*?## ' $(MAKEFILE_LIST) | awk -F':.*?## ' '{printf "  %-16s %s\n", $$1, $$2}'

$(PY):
	python3 -m venv $(VENV) && $(PY) -m pip install -q -e 'worker[dev,tiktoken]'

up: ## Build and start the full stack (api, worker, postgres, redis)
	docker compose up -d --build

down: ## Stop the stack (keeps volumes)
	docker compose down

logs: ## Follow api and worker logs
	docker compose logs -f api worker

test: test-worker test-api ## Unit + contract tests (no Docker needed)

test-api: ## Java unit and WireMock contract tests
	cd api && ./mvnw -q -B test

test-api-it: ## Java unit + contract + Testcontainers integration tests
	cd api && ./mvnw -q -B verify -Pintegration

test-worker: $(PY) ## Python unit tests and the offline quality gate
	cd worker && ../$(PY) -m pytest -q -m 'not integration'

test-worker-it: $(PY) ## Python integration tests (Testcontainers)
	cd worker && ../$(PY) -m pytest -q -m integration

sweep: $(PY) ## Chunk size / overlap / floor sweep over eval/ (offline)
	cd worker && ../$(PY) -m insightrag_worker.evaluation.sweep --report ../eval/reports/sweep-hash-v1.json

seed: $(PY) ## Upload eval/corpus to the running stack
	$(PY) scripts/seed_corpus.py

eval: $(PY) ## Retrieval quality gate against the running stack
	$(PY) scripts/eval_gate.py

e2e: $(PY) ## End-to-end checks against the running stack
	$(PY) scripts/e2e.py

load: ## k6 query load test against the running stack
	k6 run -e JWT_SECRET=$${JWT_SECRET:-dev-only-secret-change-me-dev-only-secret} load/query-load.js

clean: ## Stop the stack and delete its volumes
	docker compose down -v
