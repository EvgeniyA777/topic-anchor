#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
NOTES_DIR="$ROOT/notes"
TOPIC="${1:-general-note}"
LANG="${2:-ru}"
DATE="$(date +%F)"
TIME="$(date +%H%M)"
CREATED_AT="$(date +%Y-%m-%dT%H:%M)"
UUID="$(uuidgen | tr '[:upper:]' '[:lower:]')"
FILE_PATH="$NOTES_DIR/$DATE-$TIME-$UUID.md"

if [[ ! -d "$NOTES_DIR" ]]; then
  echo "ERROR: directory not found: $NOTES_DIR" >&2
  exit 1
fi

cat >"$FILE_PATH" <<EOF
---
file_type: working-note
topic: $TOPIC
created_at: $CREATED_AT
author: ${USER:-unknown}
language: $LANG
---

# Working Note: $TOPIC

## О чём файл
Кратко: цель заметки и контекст.

## Что подтверждено
- Факт 1

## Следующий шаг
- Шаг 1
EOF

echo "$FILE_PATH"
