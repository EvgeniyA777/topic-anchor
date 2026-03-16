#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NOTES_DIR="$ROOT/notes"
NAME_RE='^([0-9]{4}-[0-9]{2}-[0-9]{2})-([0-9]{4})-([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})\.md$'
OK=1

if [[ ! -d "$NOTES_DIR" ]]; then
  echo "ERROR: notes dir not found: $NOTES_DIR" >&2
  exit 1
fi

while IFS= read -r file; do
  base="$(basename "$file")"

  if [[ "$base" == "README.md" || "$base" == "_template.md" ]]; then
    continue
  fi

  if [[ ! "$base" =~ $NAME_RE ]]; then
    echo "INVALID_NAME: $base"
    OK=0
    continue
  fi

  file_date="${BASH_REMATCH[1]}"
  file_hhmm="${BASH_REMATCH[2]}"
  created_at="$(grep -E '^created_at:' "$file" | head -n1 | sed -E 's/^created_at:[[:space:]]*//')"

  if [[ -z "$created_at" ]]; then
    echo "MISSING_CREATED_AT: $base"
    OK=0
    continue
  fi

  if [[ ! "$created_at" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}$ ]]; then
    echo "INVALID_CREATED_AT: $base (created_at=$created_at)"
    OK=0
    continue
  fi

  created_compact="$(echo "$created_at" | sed -E 's/^([0-9]{4}-[0-9]{2}-[0-9]{2})T([0-9]{2}):([0-9]{2})$/\1-\2\3/')"
  if [[ "$file_date-$file_hhmm" != "$created_compact" ]]; then
    echo "DATETIME_MISMATCH: $base (name=$file_date-$file_hhmm, created_at=$created_at)"
    OK=0
  fi
done < <(find "$NOTES_DIR" -maxdepth 1 -type f -name '*.md' | sort)

if [[ "$OK" -eq 1 ]]; then
  echo "OK: notes naming is canonical"
  exit 0
fi

exit 1
