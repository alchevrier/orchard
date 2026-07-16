# ADR 028: Context-Bounded Model Profiles

## Status

Accepted

## Context

Workflow correctness cannot depend on an LLM remembering the workflow inside its context window. Small local models are productive only when Orchard owns durable state, supplies one bounded reasoning problem, and validates the result outside the model.

Workflows must also remain independent of a named model. A larger model may be useful with the same narrow operating window, while model selection should improve from actual execution and human satisfaction rather than generic benchmark reputation.

## Decision

Workflow steps request a versioned `ModelExecutionProfile`. The profile declares a reasoning class, input budget, output budget, and required capabilities without naming a model.

Installed providers expose `ModelBindingProfile` records containing provider, model, context capacity, capabilities, inference configuration, and an optional model digest. The profile resolver selects only compatible bindings.

The Work Definition step requests `bounded-definition-reasoning-v1`:

- 12,000 conservatively estimated input tokens
- 2,000 output tokens
- bounded definition reasoning
- strict JSON capability

Before inference, Orchard compiles an immutable workflow-step envelope containing the profile, current step identity, allowed and forbidden actions, output schema, and complete authoritative definition collaboration context. Mandatory context is never silently truncated. An envelope that exceeds the operating budget is rejected before model invocation. A provider-measured prompt count above the budget also prevents proposal publication.

Every attempted invocation appends a checksummed `ModelExecutionObservation` containing:

- requested profile and selected binding
- workflow step and work item
- envelope, prompt, and output hashes
- measured or conservatively estimated token counts
- latency and schema validity

Proposal provenance pins the corresponding execution ID, profile, complete binding fingerprint, envelope hash, full prompt hash, and output hash. Human satisfaction is derived from the authoritative collaboration and accepted-definition journals: feedback means revision requested, while acceptance is classified as unchanged or edited with changed-field distance. This avoids a cross-journal transaction between a human decision and telemetry. Deterministic workflow outcomes and human satisfaction remain separate dimensions.

`ModelCapabilityProfile` is a rebuildable projection of raw execution and satisfaction observations. It exposes sample count, schema-validity rate, human outcomes, edit distance, median latency, and confidence. It is not authority and cannot produce workflow transitions.

Routing remains conservative:

1. Reject incompatible bindings.
2. Keep evidence from different model digests or inference configurations in separate binding fingerprints.
3. With fewer than three reliable samples, select the compatible binding with the smallest context window.
4. For bindings with at least three samples and 80% schema validity, prefer schema reliability, human acceptance, unchanged acceptance, and then latency.

## Consequences

- The workflow remains durable even when a model has a small context window or loses all prior conversational state.
- Models are replaceable implementations of execution profiles.
- Stronger models can receive the same narrow context aperture for deeper bounded reasoning.
- Context overflow is explicit and testable rather than hidden by truncation.
- Orchard learns operational model capability from local work without becoming a generic benchmark product.
- Model reputation can influence executor routing but never acceptance, readiness, or completion authority.
- The checksummed model-experience journal recovers its valid prefix and quarantines a malformed final record; interior corruption fails startup without deleting later evidence.

## Boundaries

- Token budgeting uses provider counts after execution and a conservative byte-based estimate before execution until tokenizer-specific counting is available.
- The current application has one installed production binding, `phi3:mini`; multi-binding selection is implemented and tested through provider abstractions.
- The current satisfaction signals are feedback and acceptance behavior, not a general rating UI.
- Model digest is represented by the binding contract but remains optional when the provider does not expose it synchronously.
- Public benchmark suites, leaderboards, fine-tuning, and autonomous repository implementation are outside this milestone.

## Alternatives Considered

### Put the workflow in a large prompt

Rejected because context loss would become workflow-state loss, and large prefill cost would be paid on every invocation.

### Bind workflows directly to model names

Rejected because workflow requirements and model implementations evolve independently.

### Use one aggregate quality score

Rejected because deterministic validity, user satisfaction, latency, and confidence have different meanings and should remain inspectable.

### Build a benchmark subsystem inside Orchard

Rejected because Orchard needs operational evidence and replayable records, not a generic model leaderboard with access to proprietary workspace context.
