# ADR 0003: Replace Anchor-Centric Screening With Pairwise Clustering

- Status: accepted
- Date: 2026-03-16

## Context

The first implementation of `topic-anchor` treated one user-selected file as a trusted semantic anchor and scored every other file only against that one reference.

That model was easy to explain, but it had a critical failure mode: if the selected file was itself the stray file, the run could still report a reassuring result because the whole score distribution was being interpreted relative to the wrong reference.

In practice, the desired workflow is not "assume the chosen file is correct." It is "tell me where this chosen file sits inside the semantic structure of the folder."

This requires comparing all files to each other, not only to the selected file.

## Decision

Move the core screening model from one-to-many anchor scoring to pairwise batch analysis.

The updated workflow is:

- the user still selects one file path through the existing `--anchor` CLI flag for compatibility
- the selected file is treated as the target under inspection, not as a semantic ground truth
- every comparable file in the folder gets a document embedding
- document embeddings are built by chunking extracted text with overlap, embedding each chunk, and averaging chunk vectors
- the tool computes pairwise cosine similarity across the full batch
- files are grouped into clusters using a configurable similarity threshold
- the report focuses on the selected target file:
  - target verdict: `IN_CLUSTER`, `SEPARATE_CLUSTER`, or `OUTLIER`
  - target cluster rank
  - target rank inside its cluster
  - cluster membership and member ordering

The CLI remains local, terminal-first, and report-only.

## Consequences

- the result no longer depends on assuming the selected file is a trusted anchor
- a single stray file can be identified as an outlier even when it is chosen as the target
- mixed folders can be represented as multiple clusters instead of being flattened into one score distribution
- the terminal report becomes more structural and less summary-score driven
- the `--anchor` flag name is now semantically imperfect; it remains for backward compatibility and may be renamed later
- cluster quality now depends on the chosen similarity threshold and on the embedding model

## Alternatives Considered

### Keep One-to-Many Anchor Scoring

Rejected because a bad selected file can invert the interpretation and produce misleading results.

### Folder Centroid Scoring

Rejected because a centroid collapses mixed folders into one average point and is fragile when multiple topics are present.

### Pairwise Scoring Without Clustering

Rejected because a raw matrix is harder to interpret operationally than a clustered report with explicit target placement.

### LLM-Based Semantic Judging

Rejected for now because the current tool is intended to stay local, embedding-based, deterministic in shape, and easy to run from the terminal.
