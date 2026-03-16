# topic-anchor

`topic-anchor` is a small Clojure tool for finding files in a folder that look off-topic relative to one trusted anchor file.

Status: planning and bootstrap stage.

## What It Is

- a local semantic screening tool
- one trusted anchor file in, ranked review candidates out
- focused on catching wrong-topic file drops before downstream processing

## What It Is Not

- not duplicate detection
- not a truth or quality checker
- not a guarantee that a file is correct or incorrect
- not a file mover or deleter in v1

## Current v1 Shape

- implementation language: Clojure
- embedding backend: Ollama over HTTP
- first supported formats: `.html`, `.htm`
- default behavior: report only
- scoring model: compare every candidate file to one trusted anchor file

Planned command shape:

```bash
clojure -M -m topic-anchor.core --anchor ./good.html --dir ./batch --model nomic-embed-text
```

## Decision Rule

- if fewer than 6 comparable files are scored, rank the files and mark only the single lowest result as `REVIEW`
- if 6 or more comparable files are scored:
  - compute the median similarity score for the batch
  - compute median absolute deviation with a floor of `0.02`
  - mark `OUTLIER` if score is below `median - 3 * mad-floor`
  - mark `SUSPECT` if score is below `median - 2 * mad-floor`
  - otherwise mark `OK`

This output is heuristic. Users may infer that low-scoring files deserve manual review first. Users must not infer that a low score proves the file is wrong.

## Important Docs

- implementation plan: [implementation-plan.md](./implementation-plan.md)
- architectural decision: [adr/0001-anchor-based-topic-screening.md](./adr/0001-anchor-based-topic-screening.md)

## Bootstrap Notes

This repo now includes the initial Clojure project scaffold, but the end-to-end topic screening pipeline is still in progress.

Current bootstrap pieces:

- `deps.edn`
- `src/topic_anchor/core.clj`
- `test/` runner for `clojure -M:test`

Quick checks:

```bash
clojure -M -m topic-anchor.core --help
clojure -M:test
```

Planned next steps:

- HTML discovery and extraction
- Ollama setup and model instructions
- full ranking/report pipeline
