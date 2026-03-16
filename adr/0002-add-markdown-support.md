# ADR 0002: Add Markdown Support

- Status: accepted
- Date: 2026-03-15

## Context

`topic-anchor` started as a narrow local CLI for screening HTML batches against one trusted anchor file.

That core workflow also applies to Markdown-heavy folders. In this repo and adjacent workspaces, topic validation often happens on notes, knowledge files, and other content that is already stored as `.md`.

Markdown is also a reasonable early extension for v1 because text extraction is predictable: the parser can strip markup while preserving the underlying prose well enough for embedding-based comparison.

The goal is to expand usefulness without changing the basic interface, scoring policy, or report-only posture.

## Decision

Extend the existing screening pipeline to support Markdown files in v1 alongside HTML.

This means:

- discovery accepts `.md` in addition to `.html` and `.htm`
- anchor input may be HTML or Markdown
- extraction dispatch chooses the parser by file extension
- Markdown is normalized into plain text before embedding, using the same downstream scoring and classification flow as HTML
- CLI shape, Ollama integration, threshold policy, and terminal report semantics stay the same

Other file types remain out of scope for now.

## Consequences

- the tool becomes usable for common repo-native note sets without introducing a second workflow
- documentation must describe v1 as HTML plus Markdown, not HTML-only
- extraction quality now depends on both HTML and Markdown normalization
- future format additions can follow the same dispatch pattern without changing the core scoring model

## Alternatives Considered

### Keep HTML-Only In v1

Rejected because the implementation already benefits from handling Markdown with low additional complexity, and HTML-only no longer matches the main local workflow.

### Add Plain Text, PDF, DOCX, And Other Formats At The Same Time

Rejected because extraction quality varies widely across those formats. Adding them now would broaden the surface area faster than the tool can validate.

### Treat Markdown As Raw Text Without Parsing

Rejected because embedded Markdown syntax would add noisy tokens and reduce signal quality compared with extracting readable text first.
