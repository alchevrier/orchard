# ADR 053: Piloted and Autonomous Operation Modes

## Status

Accepted

The initial runtime mode, background model-dispatch gates, pilot projection, and state-atlas domain are implemented. Per-execution initiator provenance remains incremental follow-up work.

Extends ADR 009, ADR 019, ADR 030, ADR 036, ADR 044, ADR 046, ADR 051, and ADR 052. It preserves their workflow authority, explicit admission, resource control, evidence, stop authority, and AI operator boundaries.

## Context

Orchard currently starts background reconciliation loops when the backend starts. Those loops can automatically invoke repository analysis, coding, automated review, audit, baseline analysis, campaign resolution, and admitted conversation command recovery.

This behavior is appropriate when Orchard has been delegated autonomous operation. It is not appropriate when an external AI copilot or human operator is piloting Orchard one transition at a time.

The self-hosting milestone experiment exposed the distinction. The AI pilot started Orchard to inspect status, but startup reconciliation immediately consumed retry authority and began local model inference before the pilot could review the state or choose the next action. The execution remained governed by existing workflow authority, but the timing and resource consumption were autonomous rather than piloted.

A pilot cannot reliably minimize cost, inspect stop conditions, or approve the next expensive action if merely starting Orchard can trigger background model work. Conversely, disabling all background processing would prevent deterministic projection, recovery, and read-only operation that should remain available in piloted mode.

Orchard therefore needs an explicit runtime operating mode that controls who initiates model-backed transitions without changing the underlying workflow authority.

## Decision

Orchard introduces two runtime operation modes:

- `AUTONOMOUS`: Orchard may dispatch eligible background model-backed work according to existing workflow, resource, persistence, and stop authority.
- `PILOTED`: Orchard does not initiate background model-backed work. A human or AI pilot must invoke an explicitly advertised action through the API.

The mode is selected at process startup with:

```text
ORCHARD_OPERATION_MODE=AUTONOMOUS|PILOTED
```

The default is `AUTONOMOUS` to preserve current behavior for existing installations and scripts. Invalid values fail startup rather than silently selecting a mode.

The governing principle is:

> **Operation mode controls who initiates an authorized transition; it does not create or weaken authority.**

### Piloted Mode

In `PILOTED` mode Orchard continues to:

- load and validate durable authority;
- serve workspace, conversation, pilot, report, and provider projections;
- compile deterministic status and the state atlas;
- accept explicit API commands;
- enforce workflow, evidence, resource, retry, and stop predicates;
- execute a model-backed transition when an authorized pilot explicitly invokes its endpoint;
- reconcile deterministic projections that do not invoke a model; and
- record every explicit action and result normally.

In `PILOTED` mode Orchard does not automatically initiate:

- repository baseline model analysis;
- repository execution-plan analysis;
- coding-worker model execution;
- automated candidate review;
- independent model-backed company audit;
- model-backed campaign resolution;
- model-backed design reanalysis; or
- admitted conversation command recovery when execution may invoke a model.

An explicit endpoint remains subject to the same authority and resource controls as autonomous execution. Piloted mode is not an unrestricted manual override.

### Autonomous Mode

In `AUTONOMOUS` mode Orchard retains existing background dispatch behavior. Eligible work may progress without a pilot request, but only when all existing admission, evidence, resource, retry, and stop predicates permit it.

Autonomous mode does not authorize unlimited retries. ADR 051 remains controlling: recurrent failure, exhausted budgets, missing skills, policy blocks, or absent novel attempt basis must stop or defer work.

### Deterministic Work

Operation mode applies to initiation of model-backed or externally costly work. Deterministic housekeeping may continue in both modes, including:

- workspace and staged-plan projection;
- dependency reconciliation;
- immutable event correlation;
- report projection;
- accepted artifact indexing;
- and read-only status compilation.

A deterministic operation that can indirectly invoke a model must be classified as model-backed and suppressed from background execution in `PILOTED` mode.

### Pilot Projection

`GET /api/pilot/status` exposes the active operation mode. Authorized actions remain explicit. In `PILOTED` mode, a model-backed action may be advertised, but it is not executed until the pilot invokes it.

The state atlas includes an `ORCHARD_OPERATION_MODE` domain so an operator can determine the runtime initiation policy without reading configuration code.

The pilot preflight verifies provider reachability before any model-backed action. Provider readiness does not itself authorize execution.

### Restart Semantics

Restart preserves workflow and retry authority but does not change the selected initiation policy.

In `PILOTED` mode, restart recovery may record an interrupted running attempt and expose a retry action, but it must not automatically consume that retry authority.

In `AUTONOMOUS` mode, restart recovery may resume an authorized attempt when existing persistence and stop policy allow it.

### Auditability

Every process startup reports its selected operation mode. Pilot status includes the mode in its compact response.

Model execution records should distinguish initiation source:

- `AUTONOMOUS_SCHEDULER`;
- `PILOT_API`;
- `CONVERSATION_COMMAND`; or
- another explicit governed initiator.

The initial implementation may expose mode and gate schedulers before adding initiation source to every historical event schema.

### Non-Goals

This ADR does not:

- create new workflow authority;
- allow a pilot to bypass admission or stop decisions;
- disable deterministic status and projection work;
- require a model to choose the operating mode;
- make provider readiness equivalent to action authorization;
- or define a third hybrid mode before concrete usage requires it.

## Consequences

Operators can start Orchard safely for inspection without automatically spending model tokens or consuming retry authority.

Self-hosting becomes easier to reason about: `PILOTED` mode supports deliberate one-transition-at-a-time experiments, while `AUTONOMOUS` mode validates unattended progression.

The same durable project can be opened in either mode, but concurrent Orchard processes must not operate on the same authority home.

Some interrupted admitted commands may remain pending in piloted mode until explicitly resumed. Pilot status must make that waiting state visible rather than describing it as unexplained inactivity.

## Implementation Notes

The first implementation should:

1. Parse `ORCHARD_OPERATION_MODE` at backend startup and fail on invalid values.
2. Default to `AUTONOMOUS` for compatibility.
3. Gate background repository analysis, coding, model-backed baseline, automated review, audit, campaign resolution, design reanalysis, and pending command execution.
4. Preserve deterministic dispatch, projections, reporting, and explicit API endpoints.
5. Add `operationMode` to pilot status.
6. Add `ORCHARD_OPERATION_MODE` to the state atlas.
7. Add focused tests proving piloted mode disables automatic model dispatch while explicit service calls remain available.

## Validation

In `PILOTED` mode:

- starting Orchard with an eligible retry must not create a new running analysis attempt;
- pilot status must advertise the retry action;
- provider preflight must not itself invoke a model;
- invoking the advertised endpoint must execute exactly one authorized attempt;
- restarting Orchard must not consume retry authority automatically; and
- deterministic projections must remain available.

In `AUTONOMOUS` mode:

- the same eligible retry may be dispatched by the background scheduler;
- existing resource and stop authority remain enforced; and
- behavior remains compatible with installations that do not set the new environment variable.
