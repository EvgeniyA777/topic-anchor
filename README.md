# topic-anchor

`topic-anchor` is a small Clojure CLI for locating a selected HTML or Markdown file inside the semantic cluster structure of a folder.

Status: v1 local CLI is implemented.

## What It Does

- scans a directory of `.html`, `.htm`, and `.md` files
- extracts normalized text from each file
- chunks long documents with overlap, embeds each chunk through Ollama over HTTP, and averages chunk vectors into one document embedding
- computes pairwise cosine similarity across the whole batch
- groups files into semantic clusters using a similarity threshold
- reports the selected target file as `IN_CLUSTER`, `SEPARATE_CLUSTER`, or `OUTLIER`
- prints the target file's cluster rank and rank inside its cluster

## What It Does Not Do

- it is not duplicate detection
- it does not prove correctness or truth
- it does not move, delete, or rewrite files
- it does not support formats beyond HTML and Markdown in v1

## Prerequisites

- Clojure CLI
- Babashka
- a running Ollama server
- an embedding model such as `nomic-embed-text`

Local setup:

```bash
ollama serve
ollama pull nomic-embed-text
clojure -M:test
```

## Launcher

Primary operator workflow:

```bash
bb semantic-compare
```

Or pass the folder up front:

```bash
bb semantic-compare ./fixtures/smoke
```

The launcher:

- asks for the target folder if none was passed
- canonicalizes the folder path for the current system before invoking the CLI
- lists the supported files discovered in that folder
- accepts the target by number, listed relative path, filename, or absolute path
- writes the terminal report to `topic-anchor-semantic-report.txt` inside the selected folder

The launcher keeps the clustering output unchanged. It only replaces the old external `Makefile` wrapper.

## Cross-Repo Wrapper

Install the global command from the `topic-anchor` repo root:

```bash
cd /Users/ae/workspaces/topic-anchor
bb install-user
```

After adding the printed install directory to `PATH` and reloading the shell, run it from the repo or folder you want to inspect:

```bash
topic-anchor
```

Or point it at a specific folder:

```bash
topic-anchor ./out
```

The wrapper:

- treats the current working directory as the target folder when no folder argument was passed
- locates the `topic-anchor` app home from `TOPIC_ANCHOR_HOME` first, then from the wrapper's own path
- starts `bb semantic-compare` from the resolved `topic-anchor` home
- canonicalizes target paths through JVM path APIs, so path separators and absolute path forms follow the current OS automatically

`bb install-user`:

- installs one public command: `topic-anchor`
- is intended to be run from the `topic-anchor` repo root
- prefers a symlink on Unix-like systems and falls back to a generated wrapper when needed
- installs a Windows `.bat` shim on Windows
- prints exact PATH instructions if the install directory is not already configured
- refuses to overwrite a different existing `topic-anchor` command in `PATH`

Supported launcher environment variables:

- `TOPIC_ANCHOR_MODEL`
- `TOPIC_ANCHOR_TOP`
- `TOPIC_ANCHOR_CHUNK_SIZE`
- `TOPIC_ANCHOR_CHUNK_OVERLAP`
- `TOPIC_ANCHOR_CLUSTER_THRESHOLD`
- `TOPIC_ANCHOR_BASE_URL`
- `TOPIC_ANCHOR_RECURSIVE`
- `TOPIC_ANCHOR_INCLUDE_HIDDEN`
- `TOPIC_ANCHOR_CLOJURE_CMD`
- `TOPIC_ANCHOR_HOME`
- `TOPIC_ANCHOR_BB_CMD`
- `TOPIC_ANCHOR_BIN_DIR`

## Core CLI

```bash
clojure -M -m topic-anchor.core \
  --anchor ./fixtures/smoke/anchor.html \
  --dir ./fixtures/smoke \
  --model nomic-embed-text
```

Supported options:

- `--anchor`: path to the selected target file to inspect; the flag name is kept for compatibility
- `--dir`: directory to scan
- `--model`: Ollama embedding model name
- `--cluster-threshold`: similarity threshold for strong-neighbor edges, default `0.9`
- `--chunk-size`: characters per embedding chunk, default `1800`
- `--chunk-overlap`: characters of overlap between chunks, default `200`
- `--base-url`: Ollama base URL, default `http://127.0.0.1:11434`
- `--recursive`: recurse into subdirectories, default `true`
- `--include-hidden`: include hidden files and directories, default `false`
- `--top`: legacy option retained for compatibility; current cluster reporting does not use it

Exit codes:

- `0`: run completed and report printed
- `2`: input or validation error
- `3`: Ollama or runtime failure

## Decision Rule

- embed every comparable file in the batch into one document vector
- compute pairwise cosine similarity for every document pair
- connect files whose similarity is at least `--cluster-threshold`
- take connected components of that graph as semantic clusters
- evaluate the selected target file against those clusters:
  - `OUTLIER`: target forms a singleton cluster
  - `IN_CLUSTER`: target is inside the top-ranked cluster
  - `SEPARATE_CLUSTER`: target belongs to a non-singleton cluster that is not the top-ranked one

This output is heuristic. A target can still be misclassified if the batch is noisy, the extraction is poor, or the embedding model is weak.

## Output Shape

The CLI prints a run header followed by these blocks:

- `Target analysis`: verdict, target cluster rank, target cluster size, target rank in cluster, target average similarity, target strong neighbors
- `Clusters`: one summary row per cluster
- `Cluster N Members`: ranked members inside each cluster
- `Skipped`: files that were discovered but not comparable, for example `EMPTY_TEXT`

Cluster summary row format:

```text
CLUSTER<TAB>SIZE<TAB>COHESION<TAB>TARGET
```

Cluster member row format:

```text
RANK<TAB>ROLE<TAB>AVG_ALL<TAB>AVG_CLUSTER<TAB>STRONG<TAB>RELATIVE_PATH
```

Where:

- `ROLE` is `TARGET` for the selected file and `-` otherwise
- `AVG_ALL` is mean similarity to the whole batch
- `AVG_CLUSTER` is mean similarity inside the file's cluster
- `STRONG` is the number of neighbors at or above `--cluster-threshold`

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
bb semantic-compare ./fixtures/smoke
bb install-user
# after PATH is updated from the installer output:
topic-anchor ./fixtures/smoke
clojure -M -m topic-anchor.core --help
```

## Important Docs

- implementation plan: [implementation-plan.md](./implementation-plan.md)
- architectural decision: [adr/0001-anchor-based-topic-screening.md](./adr/0001-anchor-based-topic-screening.md)
- markdown support decision: [adr/0002-add-markdown-support.md](./adr/0002-add-markdown-support.md)
- pairwise clustering decision: [adr/0003-replace-anchor-centric-screening-with-pairwise-clustering.md](./adr/0003-replace-anchor-centric-screening-with-pairwise-clustering.md)
- launcher decision: [adr/0004-remove-makefile-launcher-in-favor-of-babashka.md](./adr/0004-remove-makefile-launcher-in-favor-of-babashka.md)
- cross-repo wrapper decision: [adr/0005-add-cross-repo-os-agnostic-wrapper.md](./adr/0005-add-cross-repo-os-agnostic-wrapper.md)

## License

Apache License 2.0. See [LICENSE](./LICENSE).
