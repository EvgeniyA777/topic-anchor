# ADR 0001: Anchor-Based Topic Screening

- Status: accepted
- Date: 2026-03-15

## Context

The intended workflow is to drop multiple files on one topic into a folder and then run downstream processing on that folder.

Duplicate detection helps remove exact or near-duplicate files, but it does not catch a file that is simply about the wrong topic. A folder may be mostly coherent while still containing one unrelated file added by mistake.

For this problem, using the centroid of the whole folder is fragile, especially for small sets or mixed sets. If the folder contains only a few files, or if it contains two different subtopics, the centroid can become ambiguous and reduce trust in the result.

The first version also needs to stay minimal. It should be a local tool, easy to reason about, and honest that its output is heuristic.

## Decision

Build a separate Clojure tool named `topic-anchor`.

The tool will detect likely off-topic files by comparing each candidate file to one user-supplied anchor file that is known to be in-topic.

Version 1 will:

- use embeddings rather than whole-folder centroid scoring
- use exactly one trusted anchor file as the semantic reference
- call Ollama over HTTP as the embedding backend
- support HTML files first
- report ranked review candidates only

The tool will not move, delete, or rewrite files in v1.

## Consequences

- The scoring model is simple and easy to explain: each file is compared to one trusted reference.
- The user must provide a good anchor file; a weak anchor weakens the result.
- The tool remains minimal and locally operable.
- The first version stays narrow in scope, which should reduce implementation and interpretation risk.
- The output is heuristic and must be documented as such.
- Future versions can add multi-anchor support, more file types, manifests, or quarantine workflows without changing the basic product intent.

## Alternatives Considered

### Folder Centroid Scoring

Rejected for v1 because small folders and mixed-topic folders can produce an ambiguous center and make the signal less trustworthy.

### Multiple Anchor Files From Day One

Rejected for v1 because it improves robustness at the cost of more interface and workflow complexity. One trusted anchor is enough to validate the core idea first.

### Provider-Agnostic Embedding Backend In v1

Rejected for v1 because the minimal target is a local tool with one concrete backend. Generalizing the provider interface can come later if the tool proves useful.

### Support All File Types In v1

Rejected for v1 because content extraction quality would vary too much across formats and would dilute the first implementation. HTML-first is sufficient for the current workflow.
