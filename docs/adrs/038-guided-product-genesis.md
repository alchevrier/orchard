# ADR 038: Guided Product Genesis

## Status

Accepted

## Context

Orchard had accumulated authoritative workflow, design, evidence, coding-worker, and policy-pack capabilities, but the desktop application exposed them as freely navigable project-management surfaces. A user could approach implementation before establishing what the product should feel like, which first epic proves that experience, or which architecture and repository shape derive from it.

A conventional wizard would hide controls without creating authority. A conventional chat would retain intent only as prose and allow a model response to appear more authoritative than it is. Orchard needs a guided local experience in which conversation forms candidate structure, deterministic code controls transitions, and the screen continuously projects durable truth.

The founding product design is upstream of architecture. Audience, product promise, primary journey, interaction principles, emotional qualities, exclusions, and accessibility constraints determine the first vertical slice. That slice determines architectural decisions. Those decisions determine repository topology, toolchain, modules, policies, and verification commands.

## Decision

Orchard introduces a versioned Project Genesis circuit:

```text
CLASSIFICATION
    -> EXPERIENCE
    -> ARCHITECTURE
    -> BLUEPRINT
    -> ADMISSION
    -> READY
```

The backend derives the only legal next phase. Clients submit phase-specific candidate fields with the exact displayed base revision and hash. Stale, incomplete, out-of-order, and cross-phase submissions are rejected without mutation.

### Project Classification

Genesis distinguishes:

- `GREENFIELD_LOCAL` for a new locally governed product;
- `EXISTING_LOCAL` for work constrained by an existing local Git repository; and
- `ORGANIZATION_GOVERNED` for work whose authority depends on verified organizational policy.

Existing-local admission requires a repository binding. Organization-governed genesis may be explored but cannot be admitted until Orchard supports verified organizational policy sources.

### Experience Before Architecture

The Experience Contract records:

- audience;
- product promise;
- primary journey;
- interaction principles;
- emotional qualities;
- what the product must not feel like; and
- accessibility commitments.

Architecture formation then pins a first epic as the experience-proving vertical slice. Components record responsibility, dependencies, requirement correlations, and repository paths. Architectural decisions record stable IDs, status, context, decision, consequences, component correlations, and requirement correlations.

Repository Blueprint formation is downstream of those decisions. It records root name, toolchain, modules, verification commands, and policy-pack identities.

### Conversation as Candidate Ingress

A bounded local Genesis Architect may propose the current phase's structured submission from conversational input. Its envelope includes the authoritative genesis projection, allowed output shape, user message, and exact available first-epic identities.

The model cannot:

- admit genesis;
- start implementation;
- create a repository;
- change prior authority; or
- propose fields belonging to another phase.

The service overwrites model-supplied base revision and hash with current backend authority and validates phase shape before returning a proposal. Proposal generation does not append a journal event. Only explicit human application sends the proposal through the normal deterministic transition endpoint.

### Durable Authority

Accepted transitions append checksummed records to:

```text
~/.orchard/projects/workspace/project-genesis.jsonl
```

Each revision has a monotonic global genesis ID, per-project revision, phase, actor, timestamp, full structured state, and SHA-256 hash. Replay validates event order, revision order, IDs, and hashes. Recoverable JSONL tail handling follows Orchard's existing journal convention.

Admission creates a new immutable `READY` revision and changes candidate architectural decisions to admitted status. It does not grant a model or client permission to mutate implementation directly.

### Dispatch Interlock

The production `WorkspaceStore` rejects every workflow start unless the governing Project has a current admitted `READY` genesis revision. Manual workflow starts and durable circuit dispatch already converge on `WorkspaceStore.startWorkflow`, so one interlock covers both paths before coding-worker admission.

Existing projects that predate this decision are projected at `CLASSIFICATION`. They require explicit genesis formation and admission before new production workflow runs. Test and embedded transient stores opt into this enforcement so unrelated historical unit fixtures retain their original scope.

### Desktop Experience

Compose Desktop renders one stable guided workspace:

- a non-clickable progress spine;
- one current Architect question;
- conversational proposal ingress;
- precise structured fallback controls;
- smooth semantic phase transitions;
- a continuously visible read-only product projection; and
- contextual repository binding for existing-local work.

The user may inspect established intent, experience, architecture, correlated decisions, blueprint, revision, and admission status. The user cannot navigate forward independently of backend state. The shell preserves spatial continuity while content resolves within it. Motion is feedback for real state transitions, not simulated progress.

## Consequences

- Product feel becomes explicit authority upstream of architecture and repository setup.
- The first epic is a vertical experience slice rather than a repository-scaffolding task.
- Architecture and ADR-like decisions become structured, correlated state instead of disconnected Markdown alone.
- Natural-language assistance remains useful without granting model authority.
- Restart resumes the exact authoritative phase and full projection.
- Direct API calls cannot bypass genesis admission to start implementation.
- The old project-management dashboard remains compiled temporarily but is no longer the primary reachable UI.

## Boundaries

- Version 1 supports one active project projection in the desktop shell.
- Greenfield repository materialization and candidate promotion remain later delivery stages. The blueprint is authority, not an instruction to write files immediately.
- Architecture decisions are projected in Orchard but are not yet exported to repository Markdown ADRs.
- Editing an already admitted genesis into a successor revision is not included. A later conversational change circuit must compute invalidation and supersession effects.
- Organizational identity, signatures, remote policy sources, and multi-client concurrency remain outside this local milestone.
- Motion honors stable layout and bounded transitions; platform reduced-motion detection requires a later accessibility adapter.

## Alternatives Considered

### Keep the dashboard and disable invalid buttons

Rejected because free navigation communicates that features are peers even when design authority must precede implementation.

### Use an unrestricted conversational interface

Rejected because prose alone cannot provide durable correlations, deterministic transition legality, stale-write rejection, or explicit admission.

### Create the repository before designing the first epic

Rejected because toolchain and topology would become accidental upstream constraints. The experience-proving epic must drive architecture and repository blueprint formation.

### Let the model append genesis revisions

Rejected because model output is candidate data. Human application plus deterministic backend validation remains the authority boundary.

### Put the interlock in the coding worker

Rejected because workflow admission is the earlier common boundary for manual starts and durable dispatch. The coding worker must consume existing authority rather than create it.
