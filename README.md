# topic-anchor

`topic-anchor` is a small Clojure CLI for finding files in a folder that look off-topic relative to one trusted anchor HTML file.

Status: v1 local CLI is implemented.

## What It Does

- scans a directory of `.html` and `.htm` files
- extracts normalized text from each candidate
- embeds the anchor and candidates through Ollama over HTTP
- ranks candidates by cosine similarity to the anchor
- prints `REVIEW`, `SUSPECT`, `OUTLIER`, or `OK` review signals in the terminal

## What It Does Not Do

- it is not duplicate detection
- it does not prove correctness or truth
- it does not move, delete, or rewrite files
- it does not support non-HTML formats in v1

## Prerequisites

- Clojure CLI
- a running Ollama server
- an embedding model such as `nomic-embed-text`

Local setup:

```bash
ollama serve
ollama pull nomic-embed-text
clojure -M:test
```

## CLI

```bash
clojure -M -m topic-anchor.core \
  --anchor ./fixtures/smoke/anchor.html \
  --dir ./fixtures/smoke \
  --model nomic-embed-text
```

Supported options:

- `--anchor`: path to one known-good in-topic HTML file
- `--dir`: directory to scan
- `--model`: Ollama embedding model name
- `--base-url`: Ollama base URL, default `http://127.0.0.1:11434`
- `--recursive`: recurse into subdirectories, default `true`
- `--include-hidden`: include hidden files and directories, default `false`
- `--top`: number of lowest-score results to repeat in the `Highlights` section, default `5`

Exit codes:

- `0`: run completed and report printed
- `2`: input or validation error
- `3`: Ollama or runtime failure

## Decision Rule

- if fewer than 6 comparable HTML files are scored, only the single lowest-scoring file is marked `REVIEW`
- if 6 or more comparable HTML files are scored:
  - compute the batch median similarity score
  - compute the median absolute deviation with a floor of `0.02`
  - mark `OUTLIER` if `score < median - 3 * mad-floor`
  - mark `SUSPECT` if `score < median - 2 * mad-floor`
  - otherwise mark `OK`

This output is heuristic. Low-scoring files should be reviewed first. High-scoring files are not guaranteed to be correct.

## Output Shape

The CLI prints a short run header followed by these blocks:

- `Highlights`: the lowest `--top` results
- `Full ranking`: all comparable files sorted lowest-score first
- `Skipped`: files that were discovered but not comparable, for example `EMPTY_TEXT`
- `Summary`: final counts by status

Row format:

```text
STATUS<TAB>SCORE<TAB>RELATIVE_PATH
```

For small batches, only the single lowest row is marked `REVIEW`; the other comparable rows are shown with `-` in the status column.

## Smoke Workflow

The repo includes a fixture batch under `fixtures/smoke/`.

Run the local smoke workflow:

```bash
./scripts/smoke-local.sh
```

The smoke script now performs two preflight checks before running the CLI:

- verifies that Ollama responds on `--base-url` via `/api/tags`
- verifies that the requested model is installed; `nomic-embed-text` and `nomic-embed-text:latest` are treated as the same installed model name
- if the model is missing, it prints `ollama pull <model>`

Override the model or base URL if needed:

```bash
./scripts/smoke-local.sh mxbai-embed-large http://127.0.0.1:11434
```

## Project Checks

```bash
clojure -M:test
clojure -M -m topic-anchor.core --help
```

## Important Docs

- implementation plan: [implementation-plan.md](./implementation-plan.md)
- architectural decision: [adr/0001-anchor-based-topic-screening.md](./adr/0001-anchor-based-topic-screening.md)
