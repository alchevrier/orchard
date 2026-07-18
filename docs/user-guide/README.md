# Orchard User Guide

This guide covers the current desktop product at Milestone 10.0. It is written for an operator who wants Orchard to form and run a governed local software company against a new or existing Git repository.

## Start Here

1. [Install and launch Orchard](getting-started.md).
2. [Configure model providers and machine resources](models-and-resources.md).
3. [Move a product through genesis and delivery](product-workflow.md).
4. [Understand standards, campaigns, and resolution decisions](governance.md).
5. [Diagnose services or protect local data](troubleshooting.md).

## What Orchard Does

Orchard turns product intent into an evidence-producing delivery loop:

```text
Product genesis
  -> admitted architecture and repository blueprint
  -> staged work and staff assignment
  -> repository analysis and bounded coding
  -> verification and independent audit
  -> local promotion
  -> standards scan and remediation
  -> closure or governed resolution
```

Orchard is local-first. It binds local Git repositories, stores authority under `~/.orchard`, and promotes accepted candidates locally. It does not push remote branches or pull requests.

## What Orchard Does Not Assume

- A model response is not authority by itself.
- A completed task does not prove repository-level conformance.
- A proposed exception is not an active exception.
- An admitted rescan request does not mean a scan has run.
- A roadmap item does not authorize implementation.

The desktop exposes actions only where the backend can validate the transition. When Orchard blocks or escalates, inspect the projected reason instead of bypassing the gate.

## Current Limits

- Scripted setup supports Linux and macOS. Windows setup is not automated.
- The active workspace is limited to 32 entities.
- Repository promotion is local only.
- Organization policy overlays, signed identities, quorum, and active exception authority are planned but not delivered.
- Rescan, exception request, and standard clarification resolution actions are durable decisions but do not yet invoke specialized executors.

See the [Roadmap](../../ROADMAP.md) for dependency-ordered future work and the [Troubleshooting Guide](troubleshooting.md) for operational boundaries.
