# Orchard Documentation

This directory is the maintained documentation portal for Orchard.

## Choose Your Path

### Use Orchard

Start with the [User Guide](user-guide/README.md) when you want to install, configure, operate, or recover Orchard.

- [Getting Started](user-guide/getting-started.md): prerequisites, installation, launch, and first project.
- [Product Workflow](user-guide/product-workflow.md): product genesis, company execution, promotion, standards, remediation, and resolution.
- [Models and Resources](user-guide/models-and-resources.md): Ollama, LM Studio, OpenAI-compatible endpoints, provider policy, credentials, and machine limits.
- [Governance](user-guide/governance.md): authority boundaries, standards, campaigns, exceptions, and decision states.
- [Troubleshooting and Data](user-guide/troubleshooting.md): service checks, common failures, logs, local state, and recovery precautions.

### Develop Orchard

Start with the [Developer Documentation](developer/README.md) when you want to build, test, inspect, or extend the codebase.

- [Architecture](developer/architecture.md): module boundaries, runtime services, authority flow, and background workers.
- [Development](developer/development.md): local build, test selection, run commands, and change workflow.
- [Persistence and Recovery](developer/persistence.md): runtime paths, store types, checksums, locking, replay, and compatibility rules.
- [API Reference](developer/api-reference.md): HTTP servers, endpoint groups, response behavior, and source-of-truth guidance.
- [Extension Points](developer/extension-points.md): providers, profiles, toolchain policy packs, standards, workflows, prompts, and UI integration.

### Understand Decisions and Direction

- [Architecture Decision Records](adrs/): accepted decisions and their consequences.
- [Roadmap](../ROADMAP.md): dependency-ordered product intent, milestone states, and exit evidence.
- [README](../README.md): current status, quick start, and delivered milestone history.

## Documentation Authority

Documentation explains the product but does not bypass Orchard's runtime controls.

Authority precedence is:

1. Admitted runtime records and revision-pinned evidence.
2. Accepted ADRs and non-bypassable integrity invariants.
3. The roadmap for intended sequence and future boundaries.
4. User and developer guides for current operation and implementation.
5. README summaries and conversational context.

When implementation and a guide disagree, treat the implementation and accepted ADRs as current truth, then fix the guide in the same change. Never silently edit an ADR to match new behavior; add a successor decision when architecture changes.

## Maintenance Rules

Update documentation in the same change as the behavior it describes.

- User-visible workflow, configuration, state, or recovery changes update `docs/user-guide/`.
- API, storage, architecture, build, test, or extension changes update `docs/developer/`.
- New authority boundaries or changed architectural decisions require an ADR.
- Changed milestone sequence or scope updates `ROADMAP.md`.
- Keep the root README concise and link to the maintained guide instead of duplicating detailed instructions.
- Use repository-relative links and literal identifiers so both people and Orchard retrieval can resolve the source.

All tracked files under `docs/` receive foundation weight during bounded repository context collection. Clear titles and domain terms still matter because query relevance determines which guides remain in a limited context alongside source and ADR evidence.
