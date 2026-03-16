# topic-anchor Implementation Plan

## Current Status

`topic-anchor` is now a working local `report-only` Clojure CLI for HTML batches.

The current repo includes:

- a runnable CLI entrypoint in `src/topic_anchor/core.clj`
- HTML discovery and extraction
- Ollama HTTP embedding calls
- cosine similarity scoring and threshold policy
- automated tests plus a local smoke workflow

## Goal

`topic-anchor` is a small Clojure tool for flagging files in a folder that look off-topic relative to one trusted anchor file.

The tool uses embeddings, not duplicate detection. The user supplies one file that is definitely in-topic, and the tool ranks other files by semantic similarity to that anchor.

## v1 Decisions

- Implementation language: Clojure
- Project shape: this directory is the separate sibling project
- Input model: one trusted anchor file plus one target directory
- Backend: Ollama over HTTP
- First supported formats: `.html`, `.htm`
- Default behavior: report only
- Output: terminal ranking with `OK`, `SUSPECT`, `OUTLIER`, `REVIEW`, or `-` for non-flagged small-sample rows

Current command shape:

```bash
clojure -M -m topic-anchor.core --anchor ./good.html --dir ./batch --model nomic-embed-text
```

## Non-Goals

- It does not prove that a file is correct, useful, or authoritative.
- It does not infer exact intent or truth from the file contents.
- It does not replace human review for borderline cases.
- It does not move, delete, or rewrite files in v1.
- It does not support every file type in v1.
- It does not auto-discover the topic from the whole folder in v1.

## Current Interface

Required inputs:

- `--anchor`: path to one known-good in-topic HTML file
- `--dir`: directory to scan
- `--model`: Ollama embedding model name

Optional inputs:

- `--base-url`: Ollama base URL, default `http://127.0.0.1:11434`
- `--recursive`: recurse into subdirectories, default `true`
- `--include-hidden`: include hidden files and directories, default `false`
- `--top`: number of lowest-score results to highlight, default `5`

Current behavior:

- parse the anchor HTML and each candidate HTML into normalized text
- request one embedding for the anchor and one for each candidate
- compute cosine similarity between each candidate and the anchor
- sort lowest score first
- print a terminal report with header, highlights, full ranking, skipped files, and summary
- return exit code `2` for input errors and `3` for Ollama/runtime failures

## Threshold Policy

Similarity score:

- each candidate gets one cosine similarity score against the anchor embedding
- lower score means less semantic similarity to the anchor

Status rules:

- if fewer than 6 comparable HTML files are scored, do not emit hard `OK`, `SUSPECT`, or `OUTLIER`
- for these small sets, rank all files by score and mark only the single lowest-scoring file as `REVIEW`
- if 6 or more comparable HTML files are scored, compute:
  - `median-score = median(all similarity scores)`
  - `mad = median(abs(score - median-score))`
  - `mad-floor = max(mad, 0.02)`
- then label:
  - `OUTLIER` if `score < median-score - 3 * mad-floor`
  - `SUSPECT` if `score < median-score - 2 * mad-floor`
  - `OK` otherwise

Interpretation rules:

- `REVIEW` is a small-sample fallback, not a hard judgment
- `SUSPECT` means the file is materially less similar than the folder norm and should be checked
- `OUTLIER` means the file is strongly separated from the folder norm and should be checked first
- status is based on the current folder batch, not on a global universal threshold

## Failure Modes And Limits

What the tool does:

- gives a heuristic semantic similarity signal relative to one anchor file
- helps surface likely wrong-file drops in a mostly coherent folder

What the tool does not do:

- guarantee topic membership
- separate multiple valid topics inside one folder
- work well if the anchor file is itself weak or off-topic

What the user may infer:

- low-scoring files deserve manual review first
- the folder may contain one or more thematic outliers

What the user must not infer:

- a low score means the file is definitely wrong
- a high score means the file is definitely correct
- the tool understands document intent beyond the embedding signal

Known risks:

- tiny file sets are harder to classify confidently
- HTML extraction quality affects the embedding signal
- Ollama availability and model quality directly affect results

## Local Verification

Project checks:

```bash
clojure -M:test
clojure -M -m topic-anchor.core --help
./scripts/smoke-local.sh
```

Smoke workflow inputs:

- fixtures live under `fixtures/smoke/`
- the local smoke script calls the real CLI against that batch
- it requires a reachable Ollama server and a loaded embedding model

Reference ADR:

- [adr/0001-anchor-based-topic-screening.md](./adr/0001-anchor-based-topic-screening.md)
