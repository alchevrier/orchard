# ADR 029: User-Configurable Model Apertures

## Status

Accepted

## Context

A workflow-owned default aperture cannot represent every local machine or every reasoning need. Users may need to reduce context for latency or memory pressure, reserve more output for deeper bounded reasoning, or pin a profile to a specific installed model binding.

Configuration must not silently weaken workflow requirements or persist a profile that no installed binding can execute.

## Decision

Orchard preserves the versioned workflow profile as the default and stores user overrides separately in `model-profile-settings.json` beneath the workspace authority directory.

An override may set:

- input budget tokens
- output budget tokens
- an optional preferred installed binding

The effective profile copies only those budgets from the override. Reasoning class and required capabilities remain owned by the versioned workflow profile.

Before persisting an override, Orchard validates:

- input budget is at least 1,024 tokens
- output budget is at least 256 tokens
- both values are bounded integers
- at least one installed binding satisfies the required capabilities
- input plus output fits that binding's declared context window
- a preferred binding, when supplied, is compatible with the effective profile

Invalid settings return a typed error and leave the previous configuration unchanged. Valid settings are checksummed, forced to disk, and replaced only through an atomic filesystem move. If atomic replacement or directory synchronization is unavailable, the update fails.

Definition execution resolves the effective profile immediately before compiling the workflow envelope. A preferred binding restricts eligible providers; automatic routing otherwise uses the evidence-aware resolver.

The desktop settings dialog displays default and effective budgets, installed binding context capacity, and whether each binding fits the draft aperture. Backend validation remains authoritative.

## Consequences

- Users can tune latency, context pressure, and reasoning reserve without modifying workflow source.
- Workflow capabilities cannot be removed through local settings.
- Impossible machine/model combinations fail at configuration time instead of during work.
- Changing aperture changes the execution profile recorded with subsequent observations, so evidence remains attributable to the actual operating window.
- Capability projections and routing evidence remain partitioned by effective input and output aperture.
- Automatic routing and explicit pinning share the same compatibility checks.

## Boundaries

- Binding context capacity is the current machine-capacity constraint. The Ollama binding explicitly requests the same `num_ctx` value it advertises. Orchard does not yet measure live RAM, VRAM, KV-cache pressure, thermal limits, or concurrent model load.
- Settings currently apply to the built-in Work Definition execution profile; the schema supports additional profiles later.
- Removing an override through the desktop is not yet exposed; entering the documented default budgets restores default aperture behavior while preserving an explicit local record.
- Mandatory context is still never truncated. A user-selected aperture that is too small for a particular invocation produces explicit context-budget overflow.

## Alternatives Considered

### Unrestricted context-size slider

Rejected because it obscures output reserve and can persist settings that no installed binding can execute.

### Modify built-in workflow profiles

Rejected because local machine policy and versioned workflow requirements have different ownership and lifecycle.

### Automatically shrink aperture under memory pressure

Rejected for this milestone because Orchard does not yet have reliable live resource telemetry, and silent changes would weaken reproducibility.
