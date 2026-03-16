# topic-anchor Implementation Plan

## Current Status

`topic-anchor` is now a working local `report-only` Clojure CLI for HTML and Markdown batches.

The current repo includes:

- a babashka-first interactive launcher in `bb.edn`
- a runnable CLI entrypoint in `src/topic_anchor/core.clj`
- launcher orchestration in `src/topic_anchor/launcher.clj`
- supported file discovery for `.html`, `.htm`, and `.md`
- extraction dispatch in `src/topic_anchor/document.clj`
- HTML extraction in `src/topic_anchor/html.clj`
- Markdown extraction in `src/topic_anchor/markdown.clj`
- Ollama HTTP embedding calls
- chunked document embeddings with overlap
- pairwise cosine similarity scoring across the whole batch
- cluster-based target analysis
- automated tests plus a local smoke workflow

## Goal

`topic-anchor` is a small Clojure tool for telling the user where a selected file sits inside the semantic structure of a folder.

The tool uses embeddings, not duplicate detection. The user selects one file to inspect, and the tool analyzes that file against the whole batch through pairwise similarity and clustering.

The canonical operator flow is now:

```bash
bb semantic-compare [optional-folder]
```

If the folder is omitted, the launcher prompts for it, canonicalizes the path for the current OS/runtime, lists the discovered supported files, and then prompts for the target file selection.

## v1 Decisions

- Implementation language: Clojure
- Launcher: Babashka task in this repo
- Project shape: this directory is the separate sibling project
- Input model: one selected target file plus one target directory
- Backend: Ollama over HTTP
- Supported formats in v1: `.html`, `.htm`, `.md`
- Default behavior: report only
- Output: terminal cluster report with target verdict, cluster placement, and per-cluster ranking

Canonical launcher shape:

```bash
bb semantic-compare ./batch
```

Underlying CLI shape:

```bash
clojure -M -m topic-anchor.core --anchor ./good.html --dir ./batch --model nomic-embed-text
```

## Non-Goals

- It does not prove that a file is correct, useful, or authoritative.
- It does not infer exact intent or truth from the file contents.
- It does not replace human review for borderline cases.
- It does not move, delete, or rewrite files in v1.
- It does not support every file type in v1.
- It does not guarantee that the largest cluster is semantically "correct" in any universal sense.

## Current Interface

Primary launcher:

- `bb semantic-compare`: prompt for folder, canonicalize it, list supported files, prompt for target file
- `bb semantic-compare <folder>`: skip folder prompt and go straight to target selection

Launcher behavior:

- resolves the selected folder to a canonical path for the current system before invoking the CLI
- lists discovered `.html`, `.htm`, and `.md` files with stable relative paths
- accepts target selection by numeric index, listed relative path, filename, or absolute path
- writes the terminal report to `topic-anchor-semantic-report.txt` in the selected folder
- keeps the clustering and ranking report semantics unchanged

Required inputs:

- `--anchor`: path to the selected target file; the option name is retained for compatibility
- `--dir`: directory to scan
- `--model`: Ollama embedding model name

Optional inputs:

- `--cluster-threshold`: similarity threshold used to form strong-neighbor graph edges, default `0.9`
- `--chunk-size`: characters per embedding chunk, default `1800`
- `--chunk-overlap`: characters of overlap between chunks, default `200`
- `--base-url`: Ollama base URL, default `http://127.0.0.1:11434`
- `--recursive`: recurse into subdirectories, default `true`
- `--include-hidden`: include hidden files and directories, default `false`
- `--top`: retained for compatibility; current cluster reporting does not use it

Current behavior:

- if launched through `bb`, prompt for the folder when not passed, canonicalize it, and prompt for the target file
- parse the selected target file and each candidate file into normalized text
- chunk each text with overlap and average chunk embeddings into one document embedding
- compute pairwise cosine similarity across the full batch
- build semantic clusters from strong-neighbor graph edges
- print a terminal report with target analysis, cluster summaries, cluster member rankings, and skipped files
- return exit code `2` for input errors and `3` for Ollama/runtime failures
- perform a live Ollama preflight against `/api/tags` before embedding calls
- fail fast with a pull hint if the requested embedding model is not installed

## Clustering Policy

Document representation:

- each document is chunked with overlap before embedding
- chunk embeddings are averaged into one document vector

Graph construction:

- compute pairwise cosine similarity between every two document vectors
- create an undirected edge if `similarity >= cluster-threshold`
- connected components of this graph are treated as clusters

Interpretation rules:

- `IN_CLUSTER` means the selected file belongs to the top-ranked cluster
- `SEPARATE_CLUSTER` means the selected file belongs to a non-singleton cluster that is not the top-ranked cluster
- `OUTLIER` means the selected file forms a singleton cluster
- member ranking inside a cluster is based on average cluster similarity, then strong-neighbor count, then overall average similarity

## Failure Modes And Limits

What the tool does:

- gives a heuristic structural view of a folder through embeddings and clusters
- helps show whether the selected file sits inside the dominant cluster, a side cluster, or by itself

What the tool does not do:

- guarantee topic membership
- prove that the largest cluster is the "right" topic
- remove ambiguity from heavily mixed folders without human judgment

What the user may infer:

- the selected file is structurally close to one cluster, another cluster, or to none
- the dominant cluster is the best local approximation of the folder's main topic
- low-ranked cluster members are weaker members of their cluster than higher-ranked members

What the user must not infer:

- `IN_CLUSTER` means the selected file is definitely correct
- `OUTLIER` means the selected file is definitely wrong
- the tool understands document intent beyond the embedding signal

Known risks:

- graph connectivity can merge borderline documents into a larger cluster through chains of strong edges
- threshold choice changes cluster shape materially
- extraction quality affects the embedding signal
- Ollama availability and model quality directly affect results

## Local Verification

Project checks:

```bash
clojure -M:test
bb semantic-compare ./fixtures/smoke
clojure -M -m topic-anchor.core --help
./scripts/smoke-local.sh
```

Smoke workflow inputs:

- fixtures live under `fixtures/smoke/`
- the local smoke script calls the real CLI against that batch
- it requires a reachable Ollama server and a loaded embedding model
- the smoke script checks `/api/tags` before running the CLI
- `nomic-embed-text` and `nomic-embed-text:latest` are treated as the same installed model name during the preflight check

Reference ADR:

- [adr/0001-anchor-based-topic-screening.md](./adr/0001-anchor-based-topic-screening.md)
- [adr/0002-add-markdown-support.md](./adr/0002-add-markdown-support.md)
- [adr/0003-replace-anchor-centric-screening-with-pairwise-clustering.md](./adr/0003-replace-anchor-centric-screening-with-pairwise-clustering.md)
