# ADR 023: Read-Only Repository Binding

## Status
Accepted

## Context
Orchard Projects currently describe delivery intent but have no durable relationship to the software repository where that intent will be implemented. Workflow lifecycle and evidence contracts need a stable project boundary before commits, diffs, builds, and test outcomes can be associated with governed work.

A user-selected path is not sufficient authority by itself. It may point inside a worktree, contain symbolic links, move after binding, or become temporarily unavailable. Git metadata also changes independently of Orchard and should not be copied into authoritative state as if it were immutable.

## Decision
Orchard binds one canonical local Git worktree root to an Orchard Project ID. The user selects a directory, and the backend validates that it is an absolute existing directory inside a Git worktree. Orchard resolves the selection to its real path and normalizes it to the top-level directory reported by Git.

Repository bindings are stored in a checksummed, human-readable manifest under the authoritative workspace directory. The manifest is written to a temporary file, flushed, and atomically replaced. A binding records only the Project ID and canonical repository path.

Repository metadata is a live observation rather than authority. Orchard derives availability, current branch, origin remote, working-tree cleanliness, and build system when producing the workspace read model. If the path disappears or Git inspection fails, the durable binding remains and the repository is reported unavailable.

All Git inspection is read-only. Orchard sets `GIT_OPTIONAL_LOCKS=0` and limits this milestone to root discovery, branch, configuration, and status commands. It does not fetch, check out, stage, commit, modify Git configuration, or write into the bound repository.

The workspace API owns repository binding because it is deterministic user input, not model-generated Architect intent. The desktop frontend supplies the selected path and renders backend-derived metadata; it does not inspect Git directly.

## Consequences
- Projects have a durable physical boundary for future workflow and evidence records.
- Selecting a nested directory consistently binds the repository root.
- Repository moves and temporary unavailability do not destroy Orchard project state.
- Live metadata may change between workspace reads and must not be treated as completion evidence.
- Milestone 4 can define evidence contracts against a stable Project-to-repository identity.
- Remote repositories, multiple worktrees per Project, and repository mutation require later decisions.