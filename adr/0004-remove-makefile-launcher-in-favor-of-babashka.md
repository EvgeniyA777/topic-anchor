# ADR 0004: Remove External Makefile Launcher In Favor Of Babashka

- Status: accepted
- Date: 2026-03-16

## Context

The semantic comparison workflow was being launched from an external `Makefile` that lived outside the `topic-anchor` repository.

That arrangement had several problems:

- the canonical operator workflow was split across repositories
- the launcher hardcoded assumptions about a specific `out` folder instead of asking for the real target folder
- shell quoting and interactive input handling were more fragile than the core comparison logic deserved
- path handling was tied to the shell wrapper instead of being normalized inside the tool's own runtime
- documentation for the supported workflow was drifting away from the repo that owns the comparison engine

At this point, `topic-anchor` already contains the actual semantic engine, including chunked document embeddings, pairwise similarity, clustering, and the terminal report.

The missing piece is a first-class launcher that belongs to the same repository and can drive the existing CLI in a portable way.

## Decision

Remove the external `Makefile` launcher and make `bb semantic-compare` the canonical operator workflow.

The launcher now lives inside `topic-anchor` and is responsible for:

- accepting an optional target folder argument
- prompting for the target folder when it was not passed
- canonicalizing the selected folder path for the current OS/runtime before invoking the core CLI
- listing supported files discovered in that folder
- accepting target selection by index, listed relative path, filename, or absolute path
- invoking `topic-anchor.core` with the resolved canonical `--anchor` and `--dir` inputs
- writing the emitted terminal report into the selected folder

The semantic engine itself does not move. `topic-anchor.core` remains the owner of extraction, embedding, pairwise scoring, clustering, validation, and report formatting.

## Consequences

- the supported workflow is now documented and implemented in one repository
- operator UX no longer depends on a sibling project or a hardcoded folder layout
- path normalization is performed in Clojure/Babashka instead of shell glue
- interactive folder and file selection becomes easier to maintain and test
- the low-level CLI remains available for direct use and automation
- users now need Babashka in addition to Clojure CLI for the preferred interactive launcher

## Alternatives Considered

### Keep The External Makefile

Rejected because the workflow owner would still live outside `topic-anchor`, and shell-based path/input handling would keep growing in complexity.

### Extend The Core CLI To Prompt Directly

Rejected for now because the core CLI should stay scriptable and non-interactive by default. The interactive layer belongs above it.

### Replace Makefile With Another Generic Task Runner

Rejected because `topic-anchor` is already a Clojure project, and Babashka provides a natural interactive launcher without adding a second unrelated build ecosystem.
