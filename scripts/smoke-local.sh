#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MODEL="${1:-nomic-embed-text}"
BASE_URL="${2:-http://127.0.0.1:11434}"
HOSTPORT="${BASE_URL#http://}"
HOSTPORT="${HOSTPORT#https://}"
HOSTPORT="${HOSTPORT%%/*}"
HOST="${HOSTPORT%%:*}"
PORT="${HOSTPORT##*:}"

if [[ -z "$HOST" || -z "$PORT" || "$HOST" == "$PORT" ]]; then
  echo "ERROR: unsupported base URL format: $BASE_URL" >&2
  echo "Use an explicit host:port URL such as http://127.0.0.1:11434" >&2
  exit 1
fi

TAGS_RESPONSE="$(
  printf 'GET /api/tags HTTP/1.1\r\nHost: %s\r\nConnection: close\r\n\r\n' "$HOST" |
    nc -w 3 "$HOST" "$PORT"
)"

if [[ -z "$TAGS_RESPONSE" ]]; then
  echo "ERROR: Ollama is not reachable at $BASE_URL" >&2
  echo "Start it with 'ollama serve' and make sure the server is listening before retrying." >&2
  exit 1
fi

if ! grep -Eq "\"name\":\"${MODEL}(:latest)?\"" <<<"$TAGS_RESPONSE"; then
  echo "ERROR: Ollama model '$MODEL' is not installed at $BASE_URL" >&2
  echo "Run: ollama pull $MODEL" >&2
  exit 1
fi

echo "Running smoke batch with model=$MODEL base_url=$BASE_URL"
clojure -M -m topic-anchor.core \
  --anchor "$ROOT/fixtures/smoke/anchor.html" \
  --dir "$ROOT/fixtures/smoke" \
  --model "$MODEL" \
  --base-url "$BASE_URL" \
  --top 5
