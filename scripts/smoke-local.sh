#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL="${1:-nomic-embed-text}"
BASE_URL="${2:-http://127.0.0.1:11434}"
API_ROOT="${BASE_URL%/}/api"

if ! curl -fsS "$API_ROOT/tags" >/dev/null; then
  echo "ERROR: Ollama is not reachable at $BASE_URL" >&2
  echo "Start it with 'ollama serve' and make sure the server is listening before retrying." >&2
  exit 1
fi

echo "Running smoke batch with model=$MODEL base_url=$BASE_URL"
clojure -M -m topic-anchor.core \
  --anchor "$ROOT/fixtures/smoke/anchor.html" \
  --dir "$ROOT/fixtures/smoke" \
  --model "$MODEL" \
  --base-url "$BASE_URL" \
  --top 5
