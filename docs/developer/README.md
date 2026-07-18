# Orchard Developer Documentation

This documentation covers the current Kotlin codebase and Milestone 10.0 runtime.

## Reading Order

1. [Architecture](architecture.md) for process, module, and authority boundaries.
2. [Development](development.md) for prerequisites, commands, and test strategy.
3. [Persistence and Recovery](persistence.md) before changing a store or serialized model.
4. [API Reference](api-reference.md) before changing backend routes or the desktop client.
5. [Extension Points](extension-points.md) before adding providers, profiles, prompts, policies, workflow authorities, or projections.

Use [ADRs](../adrs/) for rationale and the [Roadmap](../../ROADMAP.md) for intended sequencing. This guide describes delivered behavior; neither it nor the roadmap authorizes a runtime transition.

## Repository Map

```text
backend/
  src/main/kotlin/com/orchard/backend/
    OrchardApplication.kt       composition root, routes, workers
    agent/                      coding worker and repository gateway
    analysis/                   repository execution plans
    company/                    staffing, company circuit, audit, promotion
    config/                     local path model
    domain/                     core product models
    resource/                   machine capacity admission
    standards/                  standards, scans, campaigns, resolution
    vector/                     model providers, profiles, inference
    workspace/                  workspace authorities and persistence
  src/main/resources/default-system-prompts/
  src/test/kotlin/
frontend/
  src/desktopMain/kotlin/
    Main.kt
    com/orchard/frontend/
      network/                  typed Ktor client and DTOs
      ui/                       Compose projections and commands
  src/desktopTest/kotlin/
docs/
  adrs/                         accepted architecture decisions
  user-guide/                   operator documentation
  developer/                    implementation documentation
```

## Core Engineering Rules

- Treat model output as untrusted candidate data.
- Validate deterministically before admission or mutation.
- Pin decisions to IDs, revisions, hashes, and repository evidence.
- Keep coding, independent audit, company acceptance, and promotion separate.
- Preserve append-only or revisioned history.
- Use canonical local paths and reject unsafe traversal.
- Keep network services on loopback unless a later admitted architecture changes that boundary.
- Update the relevant guide and ADR/roadmap surfaces in the same change.

## Source of Truth

When documentation becomes stale, use this order:

1. executable tests and implementation;
2. accepted ADRs and serialized compatibility constraints;
3. this developer guide;
4. root README summaries.

Fix stale documentation as part of the behavior change rather than retaining two competing descriptions.
