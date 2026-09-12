# ADR 051: Resource-Bounded Persistence and Stop Authority

## Status

Proposed

Extends ADR 024, ADR 028, ADR 029, ADR 030, ADR 046, ADR 049, and ADR 050. It preserves their workflow admission, model aperture, machine resource, correction lineage, Attention, evidence, completion, and acceptance boundaries.

## Context

Orchard admits model execution against current machine capacity and user-delegated resource policy. Workflow profiles constrain context aperture, coding workers have bounded retry counts, and some services detect repeated identical model outcomes.

These mechanisms answer local questions: whether one invocation fits available capacity, whether one caller exhausted a fixed retry count, or whether one output repeated. They do not define whether continued inference remains justified across a purpose-linked claim, workflow, project, or engineer time horizon.

Local inference is often described as free because it produces no per-token invoice. It still consumes scarce resources:

- wall-clock time;
- CPU, GPU, unified memory, and model residency;
- energy and thermal headroom;
- queue capacity for other project work;
- engineer attention and feedback latency;
- elapsed time before evidence or delivery; and
- opportunity cost from delaying a different approach.

A large local model can occupy most of a machine for minutes per attempt. Repeating a materially unchanged plan under slightly revised prompts may consume hours while producing no new authority, evidence, or falsifiable hypothesis. A remote frontier model can produce the same loop with direct financial cost in addition to time and attention.

Model persistence cannot govern its own stopping condition. A model can always propose another refinement and explain why it may work. That explanation is not evidence that another attempt has positive value. Prompt changes alone do not create a novel basis for retry.

The harmful loop is:

```text
same purpose-linked claim
  -> same authority and plan
    -> same failure class
      -> prompt revision
        -> another expensive inference
          -> no new evidence
```

Orchard needs an explicit authority that decides when persistence is justified, when work should wait for resources, and when the current approach must stop and escalate.

## Decision

Orchard introduces **Resource-Bounded Persistence** and an Orchard-owned **Stop Authority** for model-backed work.

Every purpose-linked workflow or claim receives a versioned execution budget. Continued inference is authorized only while the next attempt has a novel basis, fits the remaining resource and time envelope, and is preferable to the available stop, decomposition, rerouting, or human-decision alternatives.

The governing principle is:

> **Persistence is authorized by new information and expected progress, not by the model's willingness to continue.**

### Resource Budget

A resource budget may constrain:

- maximum model invocations;
- maximum repair attempts;
- maximum cumulative input and output tokens;
- maximum cumulative inference duration;
- maximum wall-clock duration from admission to evidence;
- maximum machine occupancy by resource class;
- maximum model-load or residency time;
- maximum consecutive outcomes in one failure class;
- maximum engineer wait or response deadline;
- maximum direct provider cost;
- priority relative to queued work; and
- escalation thresholds.

Budgets are typed and scoped. A task, claim, workflow, project, role, skill, or model binding may contribute constraints. Orchard compiles the effective budget using deterministic precedence and mandatory floors. A child budget cannot weaken a mandatory project, security, operational, or organizational limit.

A zero-dollar local invocation still consumes cumulative inference time, machine occupancy, engineer wait, and opportunity budget.

### Attempt Basis

Every successor model attempt records an `AttemptBasis` that identifies what materially differs from the prior failed attempt. Valid bases include:

- new admitted authority;
- new repository evidence;
- a new candidate revision;
- a new failed or passed observation;
- a corrected deterministic projection;
- a different bounded skill or verification method;
- a materially narrower Attention frame;
- a model binding selected for a demonstrated capability gap;
- an explicitly authorized hypothesis that can be falsified; or
- a human clarification or governance decision.

The following do not independently establish novelty:

- rewording the prompt;
- increasing confidence or explanation length;
- asking the same model to reconsider;
- resending unchanged context;
- changing temperature or seed without an experimental purpose;
- repeating a failed action with no new source or diagnostic; or
- describing the same plan in different prose.

Orchard computes an attempt fingerprint from the purpose-linked claim, authority revisions, repository or candidate revision, Attention frame, skill and method identities, model binding, failure class, and declared hypothesis. An unchanged or semantically equivalent fingerprint consumes the recurrent-failure limit and cannot silently reset the retry budget.

### Retry Admission

A retry is admitted only when all mandatory predicates hold:

```text
RetryAllowed iff
  unresolved purpose-linked claim exists
  and attempt basis is materially novel
  and required authority remains valid
  and resource budget remains
  and deadline remains feasible
  and no higher-authority stop condition applies
  and the attempt has a defined observation that can change current knowledge
```

Orchard does not require a fictitious precise monetary expected-value calculation. Policies may use deterministic thresholds, comparative priority, measured historical outcomes, and explicit user budgets. The system must record why another attempt was admitted rather than claiming an unverifiable optimality calculation.

### Stop Outcomes

Stopping is a valid governed result, not an implementation failure. Orchard records one typed outcome:

- `RESOURCE_DEFERRED`: current capacity is unavailable but authority and budget remain valid;
- `DEADLINE_BLOCKED`: the attempt cannot complete within the admitted time boundary;
- `BUDGET_EXHAUSTED`: cumulative time, tokens, attempts, money, or occupancy reached its limit;
- `RECURRENT_FAILURE`: materially equivalent attempts repeat the same failure class without new evidence;
- `MODEL_CAPABILITY_LIMIT`: available evidence shows the current binding or profile cannot reliably perform the required transition;
- `SKILL_REQUIRED`: the current method lacks a required specialized capability;
- `ARCHITECTURE_REQUIRED`: the failure belongs to context, authority, ownership, design, or system structure rather than another implementation retry;
- `HUMAN_DECISION_REQUIRED`: progress requires judgment, clarification, acceptance, or authority that inference cannot supply;
- `POLICY_BLOCKED`: security, compliance, operational, or organizational policy forbids continuation; or
- `ABANDONED`: authorized project authority ends pursuit of the claim or outcome.

Each stop record pins the project purpose and claim, current authority and repository state, consumed budget, attempt history, recurrent failure classification, latest evidence, recommended transition, and record hash.

### Attention and Evidence Integration

Attention frames expose:

- the unresolved purpose-linked claim;
- current claim state;
- accepted and failed evidence;
- the current attempt basis;
- cumulative and remaining budget;
- prior failure classes;
- the exact observation expected from the next attempt; and
- stop conditions that apply to the invocation.

After failure, Orchard compiles the smallest successor frame that can change current knowledge:

```text
unresolved claim
  -> latest failed evidence
    -> exact candidate revision
      -> failure class
        -> responsible owner
          -> novel bounded hypothesis or skill
            -> observation to produce
```

Previously satisfied claims and unrelated owners remain non-actionable unless deterministic dependency analysis invalidates them.

A model cannot report that another attempt is justified merely because it can suggest one. It may propose an `AttemptBasis`, but Orchard validates novelty, authority, budget, and expected observation before admission.

### Skills and Model Routing

Resource-Bounded Persistence applies to skills as well as direct model calls. A skill invocation records its budget demand, expected output or evidence, and whether it produced new information.

Repeated failure may cause Orchard to:

- narrow or decompose the claim;
- select a different admitted skill;
- route to a compatible model profile;
- defer until machine capacity is available;
- request human clarification;
- reopen design or architecture;
- mark the claim blocked; or
- stop the project outcome under its completion contract.

Changing a model or skill is not automatically novel. The transition must address a recorded capability or method mismatch and retain the same purpose and evidence boundaries.

### Engineer Time and Queue Opportunity

Engineer attention is an explicit resource. A workflow may define:

- maximum acceptable feedback latency;
- working-session deadline;
- unattended background deadline;
- maximum time before requiring an actionable status update;
- maximum accumulated waiting time for one claim; and
- whether queued interactive work preempts maintenance or autonomous delivery.

Orchard records queue delay separately from active inference time. Resource deferral should not be confused with model failure, and model execution time should not hide the period during which an engineer cannot make progress.

When continued inference would exceed the engineer's admitted time horizon, Orchard stops or defers with a concrete status rather than beginning work whose result will arrive too late to be useful.

### Durable Accounting and Replay

Attempt, budget-consumption, and stop records are immutable and replayable. Accounting pins:

- model and binding fingerprint;
- Attention frame and skill hashes;
- input and output tokens;
- queue, load, inference, tool, and verification durations;
- measured or estimated machine demand;
- direct provider cost when applicable;
- attempt basis and fingerprint;
- result and failure classification;
- evidence produced; and
- budget state before and after the attempt.

Replay recomputes deterministic counters and rejects a continuation whose authority, novelty, or budget did not permit it. Restart cannot reset attempt counts, elapsed project deadlines, recurrent-failure state, or cost accounting.

### User and Operator Projection

Orchard projects enough information for an engineer to make an informed decision:

```text
Claim: Legacy records remain readable after restart
State: EVIDENCE_FAILED
Attempts: 3 of 3
Active inference: 24 minutes
Queue wait: 7 minutes
Failure class: unchanged decoder/replay mismatch
New evidence since prior attempt: none
Budget state: exhausted
Stop outcome: ARCHITECTURE_REQUIRED
Recommended transition: revise migration and persistence design
```

The projection distinguishes estimates from measurements. It does not imply that local compute has a precise monetary price when none is configured.

## Consequences

- Orchard can stop repetitive model loops before they consume disproportionate money, machine time, or engineer attention.
- Local inference is treated as resource-bearing even without per-token billing.
- Prompt rewrites no longer reset retry authority when the underlying experiment is unchanged.
- Failure can route to architecture, skills, another model profile, human judgment, or explicit abandonment instead of defaulting to another coding attempt.
- Engineers receive actionable stop reasons and cumulative cost evidence rather than repeated optimistic retries.
- Model and workflow quality can be measured by evidence gained per unit of time, compute, and attention, not only by output tokens or eventual success.
- Durable budget and attempt records add policy, storage, telemetry, clock, and replay complexity.
- Conservative thresholds may stop an attempt that could have succeeded; explicit continuation authority can create a successor budget without rewriting historical exhaustion.

## Boundaries

- Resource budgets do not decide project purpose or weaken required evidence.
- Budget exhaustion cannot convert an unresolved claim into accepted completion.
- Orchard does not silently lower verification quality, remove mandatory Attention context, or skip a required skill to fit a budget.
- Expected progress is policy-guided and evidence-informed, not a claim that Orchard can calculate perfect economic utility.
- Host telemetry remains subject to the measurement limitations documented by ADR 030.
- Stopping one claim does not necessarily stop unrelated project work unless dependency or project policy requires it.
- Emergency, regulated, and safety-critical projects may require stricter stop, escalation, and human-authority rules.
- The first implementation may support attempt, token, inference-time, wall-clock, and recurrent-failure budgets before energy, thermal, direct cost, and engineer-attention accounting are fully measured.

## Alternatives Considered

### Treat local inference as free

Rejected because local execution consumes time, machine capacity, energy, queue opportunity, and engineer attention.

### Let each worker own a fixed retry count

Rejected because isolated counts do not capture cumulative claim, workflow, project, time, or cross-model consumption and can be reset by routing or prompt changes.

### Let the model decide whether to continue

Rejected because the model benefits from proposing another attempt and has no authority over project budgets, queue opportunity, or engineer time.

### Retry until success or cancellation

Rejected because repeated materially unchanged attempts may never produce new knowledge and can indefinitely delay architectural correction.

### Use only financial token cost

Rejected because local inference may have negligible direct cost while imposing substantial wall-clock, hardware, and opportunity costs.

### Automatically switch to a larger model after failure

Rejected because model size does not repair missing authority, invalid plans, absent evidence, or architecture gaps. Routing requires a recorded capability-based attempt basis and remaining budget.

### Silently reduce context or verification to meet the budget

Rejected because doing so weakens reproducibility and can turn resource pressure into false completion.
