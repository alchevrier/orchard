# ADR 052: AI Operator Pilot Surface

## Status

Accepted

The initial backend surface is implemented by `GET /api/pilot/status` and `GET /api/pilot/state-atlas`. The status compiler is deterministic and model-free; later memory and UI projections remain incremental consequences of this decision.

Extends ADR 009, ADR 016, ADR 024, ADR 028, ADR 030, ADR 044, ADR 046, ADR 049, ADR 050, and ADR 051. It preserves Orchard's workflow authority, conversation admission, model routing, resource admission, Attention, evidence, completion, and stop-authority boundaries.

## Context

Orchard is intended to be piloted by an AI operator as well as by a human engineer. The operator's role is not to replace Orchard's authority. It observes Orchard, chooses among admitted actions, explains blockers, and intervenes when the governed system needs help.

The self-hosting milestone experiment exposed a cost and visibility problem. An external AI assistant could drive Orchard through conversation, project creation, genesis, repository binding, staged delivery, and analysis retries, but doing so required many raw API probes, large JSON projections, terminal inspection, repeated local model runs, and substantial assistant-side reasoning tokens.

That is not a sustainable operator interface. If an AI pilot must reconstruct Orchard state from raw ledgers, terminal output, and broad projections, the assistant becomes an expensive runtime supervisor. It burns context maintaining state that Orchard already owns, and it can accidentally trigger repeated expensive inference while diagnosing incomplete system projections.

The experiment also showed the value of compact governed telemetry. Once repository analysis recorded running attempts and provider audit events, the question changed from:

```text
A model is running and we do not know why.
```

to:

```text
Repository analysis for workflow run 1 owns the model slot.
Profile: broad-repository-analysis-v1.
Provider: local gpt-oss:120b.
Prompt tokens: 53762.
Provider phase: plain fallback streaming completed.
Deterministic rejection: scope coverage 1 lacks pinned evidence or source operation.
```

That causal chain should be available directly to any pilot. The pilot should not mine raw implementation detail to discover the active objective, current blocker, provider phase, retry authority, evidence gaps, or lowest-cost next action.

Orchard needs a first-class **AI Operator Pilot Surface**: a compact, authority-preserving projection designed for a human, local small model, remote coding assistant, or future Orchard internal operator to monitor, diagnose, and advance work with minimal context and minimal inference.

## Decision

Orchard will expose a governed AI Operator Pilot Surface.

The surface summarizes the current operational state of Orchard in a compact, structured, non-secret, action-oriented form. It does not create new authority. It projects existing authority, evidence, resource state, model telemetry, blockers, and admitted next actions so an operator can choose the cheapest valid intervention.

The governing principle is:

> **The AI pilot reads compact authority state; Orchard owns truth, admission, evidence, and stop conditions.**

### Pilot Status Projection

Orchard will provide a compact status projection for active work. It should answer:

- what objective or project is active;
- what workflow run, staged node, claim, or completion contract currently owns attention;
- whether work is idle, ready, running, blocked, waiting for resources, waiting for admission, or terminal;
- which model or tool call is currently active;
- why it is active;
- how long it has been active;
- what resource budget it has consumed;
- whether the latest model output completed, failed, or was rejected;
- what deterministic blocker currently applies;
- what evidence exists;
- what evidence is missing;
- which retries are authorized;
- which actions are explicitly disallowed; and
- the lowest-cost admitted next actions.

A pilot projection may have an API shape like:

```json
{
  "activeObjective": {
    "objectiveId": 2,
    "projectId": 1,
    "title": "Self-host Milestone 10.2.5 implementation",
    "state": "ACTIVE"
  },
  "currentRun": {
    "runId": 1,
    "workItemId": 4,
    "state": "CONTEXT_READY",
    "workflowId": "default-delivery-task"
  },
  "currentOperation": {
    "type": "REPOSITORY_ANALYSIS",
    "state": "BLOCKED",
    "attemptId": 12,
    "diagnostic": "Scope coverage 1 does not cite pinned evidence or a concrete source operation."
  },
  "model": {
    "profileId": "broad-repository-analysis-v1",
    "providerFingerprint": "f624e16...",
    "model": "gpt-oss:120b",
    "promptTokens": 53762,
    "providerPhases": [
      "REQUEST_STARTED",
      "RESPONSE_HEADERS_RECEIVED",
      "STREAM_TERMINAL_FRAME",
      "STREAM_RECONCILED"
    ]
  },
  "evidence": {
    "present": [],
    "missing": ["SOURCE_DIFF", "BUILD", "TEST", "ACCEPTANCE"]
  },
  "authorizedActions": [
    {
      "id": "retry-focused-repository-analysis",
      "costClass": "MODEL_DELIVERY",
      "reason": "A deterministic contract rejection has authorized one correction retry."
    }
  ],
  "disallowedActions": [
    {
      "id": "repeat-broad-analysis-unchanged",
      "reason": "No new attempt basis or narrower frame was admitted."
    }
  ]
}
```

The exact representation may evolve, but the projection must remain compact enough to serve as the primary prompt context for an operator model.

### State and Transition Atlas

The pilot surface must be backed by an explicit **state and transition atlas**. An AI operator should not need to read Kotlin source, infer enum meanings, or reconstruct legal transitions from route handlers and service code.

The atlas describes the possible states, transitions, authorities, evidence gates, resource gates, and stop outcomes for Orchard's operational domains. It is a machine-readable map of the system's governed behavior.

At minimum, the atlas should cover:

- conversation objective states and controls;
- conversation command proposal, admission, execution, rejection, and correlation states;
- project genesis phases and admission predicates;
- work-definition proposal, feedback, acceptance, and assessment states;
- staged delivery plan, node, dispatch, and dependency states;
- workflow run states and evidence transitions;
- repository-analysis attempt states and retry authority;
- coding-worker execution, attempt, candidate, review, correction, and promotion states;
- provider execution phases;
- resource admission, deferral, and preemption states;
- completion assessment outcomes;
- stop-authority outcomes; and
- external operator/executor action states.

Each transition entry should identify:

- source state;
- target state;
- triggering action or observation;
- required authority;
- required evidence;
- resource predicate;
- retry or stop implication;
- emitted projection event;
- expected next operator action;
- and terminality.

A compact form may look like:

```json
{
  "domain": "REPOSITORY_ANALYSIS_ATTEMPT",
  "states": ["RUNNING", "BLOCKED", "RETRY_AUTHORIZED"],
  "transitions": [
    {
      "from": "RETRY_AUTHORIZED",
      "to": "RUNNING",
      "trigger": "repository-analysis.tick",
      "authority": "authorized retry or no current block",
      "resourcePredicate": "model lease admitted",
      "projection": "provider audit REQUEST_STARTED",
      "operatorMeaning": "A model call owns this run. Watch provider phases before retrying.",
      "terminal": false
    },
    {
      "from": "RUNNING",
      "to": "BLOCKED",
      "trigger": "deterministic plan rejection",
      "evidence": "prompt hash, provider audit events, rejection diagnostic",
      "operatorMeaning": "Do not repeat unchanged broad analysis; use diagnostic to narrow or repair grounding.",
      "terminal": false
    }
  ]
}
```

The pilot status response may include only the current slice of the atlas by default, with links or identifiers for expanded maps. This keeps the operator prompt compact while making the complete state machine available when needed.

The atlas is part of Orchard's authority model. If implementation behavior diverges from the atlas, that is a product defect. Tests should validate that exported transitions remain consistent with the service-level transition predicates.

### Operator Actions

The pilot surface lists actions; it does not silently execute them. Mutating actions still pass through Orchard's normal admission, command, workflow, and evidence authority.

Examples of operator-facing actions include:

- inspect current blocker;
- retry a specific authorized attempt;
- request human clarification;
- narrow an Attention frame;
- accept or reject a proposed command;
- open a focused diagnostic report;
- defer until resources are available;
- stop a recurrent failure loop;
- create a follow-up work item;
- route a task to a different admitted model profile;
- record external executor evidence; or
- mark an outcome blocked by policy or missing authority.

An AI pilot may recommend an action, but Orchard validates the action against the current authority state before execution.

### Cost Control

The pilot surface is a cost-control mechanism. It reduces:

- assistant-side reasoning tokens;
- local model prompt tokens;
- repeated raw-state probing;
- large projection dumps;
- accidental broad retries;
- engineer wait time;
- model residency waste;
- and repeated diagnosis of already-known blockers.

Orchard should prefer deterministic pilot summaries before invoking a model. A small local model or remote assistant should receive a compact status card rather than raw workflow ledgers whenever possible.

If a model-backed operation is running, the pilot surface must expose enough information to answer why the model is active without requiring another model call. At minimum, it should include owner, profile, provider, prompt hash, estimated tokens, elapsed time, latest provider phase, and the authority that admitted the operation.

### Provider and Workflow Telemetry

Provider telemetry is part of the pilot surface, but prompt and response content are not exposed by default. The pilot sees metadata and phases, such as:

- request started;
- response headers received;
- stream frame observed;
- terminal frame observed;
- stream reconciled;
- stream reconcile failed;
- request failed;
- prompt token estimate;
- provider-reported prompt evaluation count;
- provider-reported completion count;
- elapsed time;
- context window;
- output budget;
- binding fingerprint;
- and model identity.

Workflow telemetry includes:

- active workflow run;
- staged delivery node;
- attempt state;
- retry authority;
- stop authority;
- evidence requirements;
- evidence records;
- deterministic rejection diagnostics;
- current repository revision;
- and resource lease state.

### Memory and Reuse

When a pilot diagnosis produces a reusable lesson, Orchard records it as durable intelligence rather than leaving it in assistant conversation history.

Reusable lessons may include:

- model/profile mismatch;
- prompt budget mismatch;
- repeated invalid-output pattern;
- deterministic contract rejection;
- missing repository evidence selector;
- verification command failure;
- slow provider phase;
- or an effective correction strategy.

The stored lesson must bind to evidence: workflow run, attempt, prompt hash, provider audit events, deterministic diagnostic, repository revision, and resolution.

Future retries should consume this compact memory instead of rediscovering the same failure through another broad model invocation.

### External AI Operators

Claude Code, GitHub Copilot, local models, or other external AI systems may serve as pilots or executors. Orchard treats them as external operators or model/tool providers, not hidden sources of truth.

External AI assistance must be represented through:

- admitted operator action;
- explicit authority boundary;
- executor identity;
- produced artifact or decision;
- evidence reference;
- cost or token metadata when available;
- and completion or rejection status.

Using a remote AI operator may be tactically cheaper than repeated local inference or assistant exploration. It does not remove Orchard's need to govern authority, evidence, completion, and stopping conditions.

### Non-Goals

This ADR does not require:

- replacing human review;
- giving the pilot unrestricted write access;
- exposing secrets, prompts, private model outputs, or credentials;
- making one specific assistant vendor part of Orchard's core architecture;
- replacing existing workflow, conversation, or model-provider APIs;
- automatically trusting AI operator recommendations;
- or building a full UI before the compact API projection exists.

## Consequences

Orchard gains a product boundary for AI-assisted operation. The system becomes easier and cheaper to pilot because the operator reads compact, governed state instead of reconstructing state from raw logs and broad JSON projections.

The AI pilot becomes replaceable. A human, GitHub Copilot, Claude Code, a local small model, or a future Orchard-internal operator can all consume the same status projection and choose among the same admitted actions.

The architecture better matches Orchard's thesis. Models can propose and execute, but Orchard decides what is authorized, observable, complete, blocked, or stopped.

The implementation cost is that Orchard must maintain additional projection logic and must carefully avoid leaking prompt content, model output, credentials, or private repository data through pilot summaries.

The pilot surface also creates pressure to make every long-running model or tool operation visible as durable or queryable operational state. Hidden work becomes a product defect.

## Implementation Notes

Initial implementation should be incremental:

1. Add a read-only pilot status endpoint that composes existing workspace, conversation, workflow, repository-analysis, provider-audit, and resource projections.
2. Include active workflow run, latest attempt, provider phase timeline, evidence gap summary, and authorized next actions.
3. Add a read-only state and transition atlas endpoint or embedded atlas reference.
4. Keep the response small by default and add expansion flags only for debugging.
5. Feed deterministic rejection diagnostics into retry recommendations.
6. Record reusable pilot diagnoses as workflow memory or repository intelligence.
7. Add UI affordances after the API produces stable compact state.

The first useful endpoint is likely:

```text
GET /api/pilot/status
```

Optional focused endpoints may follow:

```text
GET /api/pilot/runs/{runId}
GET /api/pilot/blockers
GET /api/pilot/authorized-actions
GET /api/pilot/state-atlas
```

## Validation

A valid pilot surface should allow an external AI operator to answer the following without reading raw ledgers or running another model:

- What is Orchard doing right now?
- Which authority admitted it?
- Which model/tool owns resources?
- What phase is the provider in?
- What evidence exists?
- What blocker or rejection applies?
- What retry or stop authority exists?
- What is the lowest-cost next admitted action?
- What should not be retried?
- Which states and transitions are possible from here?

A self-hosting milestone run should be considered improved when the pilot can diagnose the repository-analysis blocker from one compact status response rather than from repeated terminal probing and large API dumps.

The AI operator should be able to explain the current legal next actions from the pilot status and atlas alone, without reading service implementation code.
