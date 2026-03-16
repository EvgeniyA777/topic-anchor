# ADR 0005: Add Global OS-Agnostic Wrapper And User Installer

- Status: accepted
- Date: 2026-03-16

## Context

After moving the canonical interactive workflow into `bb semantic-compare`, the operator still had to start from the `topic-anchor` repository or manually `cd` into it before running the tool.

That was workable for local development, but it was not the intended day-to-day usage pattern. The target of analysis is usually some other repository or output folder, and the operator wants to start the comparison from there.

This creates two related operator problems:

- the command should be runnable from outside the `topic-anchor` repository
- when no explicit target folder is passed, the current working directory should be treated as the folder to analyze
- the wrapper must not hardcode one path style or one shell environment
- path canonicalization must follow the current operating system instead of assuming Unix separators or one fixed mount layout
- the user should not need to remember an absolute path to the wrapper script inside the repo

The semantic engine itself is already inside `topic-anchor.core`, and the interactive folder/file selection flow is already inside `bb semantic-compare`. What is missing is a thin cross-repo entrypoint that can locate the app home and forward control to the existing launcher safely.

## Decision

Add one global wrapper command named `topic-anchor` and a user-level installer task `bb install-user`.

The wrapper:

- runs as a Babashka script
- accepts an optional explicit target folder
- uses the current working directory as the target folder when no folder argument is passed
- resolves the `topic-anchor` app home from `TOPIC_ANCHOR_HOME` first, then from the wrapper script location
- canonicalizes both the app home and the target folder through JVM path APIs
- delegates to `bb semantic-compare` from the resolved app home

The installer:

- installs the public command into a user-level bin directory
- chooses the install directory based on the current OS, unless `TOPIC_ANCHOR_BIN_DIR` overrides it
- prefers a symlink on Unix-like systems and falls back to a generated script if symlinks are not available
- installs a Windows batch shim on Windows
- does not edit shell config or profile files automatically
- prints exact PATH instructions when the install directory is not already configured
- fails safely if a different `topic-anchor` command already exists in `PATH`

The wrapper remains intentionally thin. It does not duplicate semantic analysis logic, target selection logic, or report rendering. Those concerns stay in the existing launcher and core CLI.

## Consequences

- operators can run `topic-anchor` directly from the repository or folder they want to inspect
- the wrapper is portable across operating systems because path resolution uses JVM path handling instead of shell-specific assumptions
- installations that move the wrapper outside the repository can still work by setting `TOPIC_ANCHOR_HOME`
- the command becomes discoverable through normal PATH-based usage instead of an absolute repo path
- the launcher surface becomes two-layered:
  - `topic-anchor` for cross-repo entry
  - `bb semantic-compare` for in-repo interactive launch
- Babashka remains a runtime dependency for the preferred interactive workflows
- PATH setup stays explicit and user-controlled rather than being silently pushed into dotfiles

## Alternatives Considered

### Keep Only `bb semantic-compare`

Rejected because it forces the operator to start from the `topic-anchor` repository even when the target of analysis is elsewhere.

### Reintroduce An External Shell Wrapper

Rejected because that would reintroduce shell- and OS-specific path handling outside the repository, which is exactly what the Babashka migration was meant to remove.

### Keep `topic-anchor-here` As The Public Command

Rejected because it is not a discoverable or intuitive command name for everyday use, and it still leaves the user guessing how to expose the wrapper globally.

### Put Cross-Repo Detection Into The Core CLI

Rejected because `topic-anchor.core` should remain a direct, scriptable CLI that receives explicit inputs. Cross-repo launch ergonomics belong above it, not inside it.
