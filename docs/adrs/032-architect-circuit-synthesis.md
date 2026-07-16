# ADR 032: Architect Circuit Synthesis

## Status

Accepted

## Context

Staged delivery circuits make logical ordering, stage policy, and typed artifact gates durable authority. Milestone 8.0 still requires a human to construct every graph manually. For larger Stories and Epics, identifying useful parallel branches and contract boundaries is a reasoning task well suited to a local model.

Allowing the model to write an active circuit directly would make model output authoritative. It would also create a stale-context race if hierarchy or plan authority changed during inference. Orchard needs model-assisted decomposition without moving acceptance, graph validation, or execution authority into the model.

## Decision

Orchard adds a proposal-only `SYNTHESIZE_CIRCUIT` workflow step for Epic and Story scopes.

The step compiles one immutable envelope containing:

- the exact scope and direct children
- accepted Work Definitions for Task and Bug members
- valid artifact evidence kinds for each member
- the active plan revision and hash
- registered stage workflow definitions
- allowed proposal actions and forbidden authority actions
- the required strict JSON output schema

Circuit synthesis uses the dedicated `bounded-circuit-synthesis-v1` model execution profile. Profile routing, user aperture overrides, live resource admission, token limits, strict JSON decoding, and model-execution evidence use the existing governed model runtime.

A successful generation appends an immutable `CircuitProposal` to `circuit-proposals.jsonl`. The proposal binds its normalized plan content to the model execution ID, profile, binding fingerprint, prompt version, prompt hash, context hash, raw output hash, actor, and creation time. Observations remain distinct from assumptions.

Generation never creates or revises a `StagedDeliveryPlan`. Before recording a proposal, deterministic Kotlin policy revalidates hierarchy coverage, stage workflow identities, graph edges, artifact contracts, collection limits, and the active plan base revision/hash. If context changed during inference, generation returns a stale conflict and records no proposal.

The desktop loads the latest current-base proposal into the existing circuit editor. A human can inspect observations and assumptions, revise any field, and submit through the normal staged-plan acceptance command. Accepted plan authority pins the proposal ID and hash and records whether it was accepted unchanged. Structural human revision size covers title, stages, workflow selections, membership, dependencies, consumed artifacts, and produced artifacts.

Model capability memory derives unchanged and edited acceptance outcomes from accepted plan authority. A stale proposal cannot overwrite a newer plan because its pinned base revision and hash fail optimistic concurrency.

## Consequences

- The model assists with decomposition without gaining acceptance or execution authority.
- Generated graphs use only current work-item IDs, registered workflows, and valid evidence kinds.
- Human edits remain first-class authority rather than being attributed to the model.
- Proposal quality becomes measurable per model binding and aperture.
- Proposal, model execution, and accepted plan journals recover independently and cross-validate at startup.
- The existing manual planner remains available when no model is installed or generation is rejected.

## Boundaries

- Synthesis organizes existing direct children. It cannot create, split, rename, or delete work items.
- Epic proposals use completion dependencies only because Story artifact aggregation is not yet defined.
- Feedback-specific proposal revisions are not modeled in this milestone. Regeneration creates a new immutable proposal against the current base.
- The desktop exposes generation and editable acceptance but does not visualize historical unaccepted proposals as a separate timeline.
- The dedicated synthesis profile can be configured through the existing model-profile API; a multi-profile desktop settings editor remains future UX work.
- Eligible nodes are still started explicitly. Durable automatic dispatch is Milestone 8.2.
- One Orchard backend process owns each workspace authority directory.

## Alternatives Considered

### Let Architect write the active plan

Rejected because strict JSON and schema validity do not establish human intent or grant authority.

### Generate free-form planning prose

Rejected because prose cannot be deterministically validated against hierarchy, workflow, dependency, and artifact contracts.

### Reuse the Work Definition profile and proposal journal

Rejected because circuit synthesis has a distinct reasoning class, output schema, satisfaction signal, and scope identity.

### Apply a generated proposal automatically when validation passes

Rejected because deterministic validity proves structural consistency, not that the proposed decomposition reflects human intent.
