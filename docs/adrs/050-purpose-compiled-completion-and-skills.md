# ADR 050: Purpose-Compiled Completion and Skills

## Status

Proposed

Extends ADR 004, ADR 021, ADR 025, ADR 034, ADR 035, ADR 038, and ADR 049. It preserves their evidence, design, admission, Attention, acceptance, and promotion boundaries.

## Context

Orchard already records product purpose during Project Genesis. Audience, product promise, primary journey, experience constraints, architecture, repository topology, and the first vertical slice are established before implementation. Orchard also compiles work-definition and design acceptance criteria into revision-scoped workflow gates.

These authorities do not yet define project-level completion. A workflow may complete because its exact gates passed, and every ticket in a plan may reach a terminal state, without demonstrating that the resulting project fulfills the purpose for which it exists.

A universal engineering checklist cannot close this gap. Compilation, unit tests, lint, review, deployment, and documentation are useful evidence-producing activities, but their relevance depends on project purpose. A reusable library, exploratory prototype, local desktop tool, regulated service, and multi-tenant operational system require different outcome claims and different evidence boundaries.

The same gap affects skills. A catalog of optional model instructions does not ensure that the capability needed by a purpose-linked claim is used. A model may omit security review, choose an irrelevant testing method, or claim completion after producing code and test source. Code is an implementation artifact. Test source is an evidence-producing instrument. Neither proves that the project does what it claims.

Without a purpose-rooted completion model, Attention can keep an invocation correlated to a task while the task itself remains weakly related to project success. Orchard needs one root authority from which outcomes, claims, work, required capabilities, evidence, and completion are derived.

## Decision

Orchard makes **admitted project purpose** the root of its delivery authority graph.

The governing hierarchy is:

```text
PROJECT_PURPOSE
  -> INTENDED_OUTCOME
    -> REQUIRED_CAPABILITY
      -> REQUIREMENT
        -> CLAIM
          -> WORK
            -> VERIFICATION_METHOD
              -> OBSERVATION
                -> EVIDENCE
                  -> ACCEPTANCE
                    -> PROJECT_COMPLETION
```

Project purpose defines why the project exists and which observable outcomes constitute success. Orchard compiles that authority into a versioned `ProjectCompletionContract`. Attention projects the relevant path through the contract for each role and invocation. Skills provide specialized methods for advancing or evaluating graph transitions. Evidence records what was actually observed. Acceptance determines whether the observations sufficiently support the purpose-linked claims.

The enduring policy is:

> **Project purpose defines done. Attention preserves the active path to done. Skills provide bounded methods along that path. Evidence demonstrates what is true.**

### Project Purpose Authority

Project purpose is admitted authority, not a generated slogan. It identifies:

- the audience or beneficiary;
- the problem or opportunity the project exists to address;
- the product promise or intended change in the world;
- the primary outcomes that demonstrate that promise;
- mandatory quality, security, operational, compliance, and accessibility obligations;
- explicit exclusions and non-goals; and
- the authority responsible for accepting purpose fulfillment.

Project Genesis remains the initial source for this authority. A later change to admitted purpose creates a successor revision and requires deterministic impact analysis across completion contracts, designs, active work, evidence, and accepted outcomes. Conversation or model output cannot silently revise purpose.

### Project Completion Contract

Orchard compiles admitted purpose into a versioned, content-hashed `ProjectCompletionContract`. The contract contains:

- project and purpose identity, revision, and hash;
- intended outcomes;
- required claims for each outcome;
- applicable quality, security, operational, compliance, accessibility, migration, and lifecycle claims;
- evidence-producing methods and required observation boundaries;
- automated and human acceptance authorities;
- aggregation rules for child outcomes and work;
- explicit non-goals; and
- invalidation dependencies.

Every required claim has a stable identity. A claim describes observable project behavior, not an implementation activity. For example:

```text
Claim: A legacy record remains readable after upgrade and restart.
```

is valid, while:

```text
Claim: Add a compatibility decoder.
```

is an implementation instruction and cannot substitute for the outcome claim.

### Project-Specific Done

`Done` is a project-specific, evidence-backed acceptance judgment that the delivered outcome fulfills the admitted project purpose at an exact release or repository state.

For project `P` at target state `R`:

```text
Done(P, R) iff every required purpose-linked claim has accepted supporting evidence at R,
all mandatory cross-cutting claims are satisfied,
no blocking contradiction or invalidation remains,
and the configured acceptance authority records completion.
```

Orchard must never derive project completion from any of these conditions alone:

- all planned code was written;
- all tickets are closed;
- all tests passed;
- all model-proposed operations completed;
- all implementation claims match the diff; or
- a model, worker, reviewer, or conversation says the project is done.

These facts may contribute evidence. None independently proves purpose fulfillment.

Completion is hierarchical but not reducible to child status:

```text
Task done
  = task claims have accepted evidence

Story or outcome done
  = its observable outcome claims have accepted evidence

Project done
  = the admitted project purpose is demonstrated by its completion contract
```

All child work may be terminal while the project remains incomplete because an outcome claim lacks evidence. Conversely, an exploratory project may be complete after a bounded experiment, measurements, limitations, and an admitted decision even though no production system was shipped.

### Claim and Evidence Semantics

Orchard distinguishes:

```text
Code                     = implementation artifact
Test source              = evidence-producing instrument
Test execution           = observation
Passing result           = potential evidence for a specific claim
Accepted evidence        = evidence judged sufficient under the contract
Project completion       = all required purpose-linked claims accepted
```

Evidence supports a claim only when it pins:

- the claim and criterion identity;
- the admitted verification method;
- the required observation boundary;
- the exact candidate, release, or repository revision;
- the actual execution or accountable human observation;
- the result and output or observation hash; and
- the applicable contract and authority revisions.

Evidence produced at the wrong boundary is insufficient. A serializer unit test does not prove a public migration journey. A successful compilation does not prove authorization. An API success test does not prove denied attempts are audited. A test written by a model is not evidence until the admitted method executes it against the target state.

### Purpose-Driven Attention

Every role-specific Attention projection begins at project purpose and includes the shortest admitted path relevant to the invocation:

```text
purpose
  -> outcome
    -> claim
      -> current authority state
        -> active work or evidence gap
```

Coding Attention focuses on the authorized operation that advances a purpose-linked claim. Testing Attention focuses on the observation boundary and evidence method for a claim. Review Attention focuses on whether a candidate claim matches the diff and whether the resulting evidence supports the purpose-linked outcome. Repair Attention focuses on the exact unresolved claim, failed evidence, responsible owner, permitted correction, and method that must be rerun.

After evidence failure, Orchard compiles a successor frame from the unresolved claim and observation. It does not ask a model to rediscover the entire project or broadly debug unrelated code.

### Purpose-Driven Skills

A skill is a versioned, bounded capability attached to a graph transition. It is not merely optional prompt text.

```text
PURPOSE_LINKED_CLAIM
  -> REQUIRES_CAPABILITY
    -> REQUIRES_SKILL
      -> REQUIRED_INPUTS
      -> ALLOWED_TOOLS
      -> OUTPUT_CONTRACT
      -> EVIDENCE_OR_CANDIDATE_RESULT
```

A required skill records:

- stable skill identity and version;
- capability and purpose-linked claim identities;
- required authoritative inputs;
- allowed tools and mutation class;
- structured output contract;
- evidence or candidate-result type;
- deterministic validation rules; and
- escalation behavior when inputs or authority are insufficient.

Orchard selects required skills from admitted capability and policy relationships before model invocation. The model may reason inside a skill but cannot skip a required skill, grant it additional tools, reinterpret its purpose, or claim that it ran. Skill completion is recorded only after its output passes deterministic validation and any required evidence method executes.

Skills remain replaceable implementations. A project may satisfy the same capability through another admitted skill or deterministic adapter when the completion contract permits it. The purpose-linked claim and evidence requirement remain stable.

### Completion-State Discipline

Purpose-linked claims progress through distinct states:

```text
DEFINED
  -> PLANNED
    -> IMPLEMENTED_UNVERIFIED
      -> EVIDENCE_RUNNING
        -> EVIDENCE_FAILED or EVIDENCE_PASSED
          -> ACCEPTED
```

Only Orchard-owned transitions may advance these states. Model prose cannot do so.

A failed observation narrows subsequent work:

```text
failed claim
  -> failed evidence and diagnostic
    -> exact target revision
      -> responsible owner
        -> permitted repair
          -> verification method to rerun
```

Previously accepted claims remain visible and non-actionable unless dependency analysis invalidates them. This prevents broad repair prompts from reopening unrelated project areas and reduces repeated inference spent reconstructing known state.

### Admission and Validation

Before work begins, Orchard verifies that every active work item traces to a purpose-linked claim and that every required claim has either:

- admitted work and an evidence-producing method;
- accepted evidence at the current target state; or
- an explicit blocked, deferred, waived, or non-applicable disposition issued by the proper authority.

Before completion, Orchard verifies:

- forward coverage from every required purpose-linked claim to accepted evidence;
- reverse traceability from every accepted evidence record to its claim, method, target state, and contract;
- consistency of all evidence and judgments at the required release state;
- satisfaction of triggered security, operational, migration, compliance, and accessibility claims;
- absence of stale authority, unresolved blockers, or invalidated dependencies; and
- an explicit completion judgment from the configured acceptance authority.

Mandatory claims and evidence cannot be dropped to fit a model aperture. Orchard blocks or decomposes the work instead.

### Measurement

Orchard measures purpose-compiled delivery separately from raw model output quality. Relevant measures include:

- purpose-linked claim coverage;
- orphan work and orphan evidence count;
- evidence-boundary correctness;
- unsupported completion claims;
- required-skill invocation and validation rate;
- deterministic repair count;
- model retries per unresolved claim;
- tokens spent before and after evidence failure;
- accepted outcomes later reported as unmet or regressed; and
- elapsed time from claim definition to accepted evidence.

A reduction in generated code or passing tests is not itself success. The relevant result is whether Orchard reaches accepted purpose fulfillment with less drift, unsupported certainty, and repeated inference.

## Consequences

- Project completion becomes meaningful for different project types instead of inheriting one universal engineering checklist.
- Attention gains a stable root above task and code authority.
- Skills become purpose-driven, versioned workflow capabilities rather than optional prompt fragments.
- Code, tests, observations, evidence, and acceptance remain distinct and auditable.
- Failed evidence compiles into claim-focused repair rather than broad rediscovery.
- Project dashboards can distinguish terminal work from demonstrated outcomes and purpose fulfillment.
- Purpose revisions require impact analysis and may invalidate completion contracts, evidence applicability, and active work.
- Defining observable claims and evidence boundaries adds upfront governance cost, especially for ambiguous or exploratory projects.

## Boundaries

- Orchard does not infer or admit project purpose without user or delegated authority.
- A completion contract does not guarantee that its claims are sufficient; it records the admitted definition under which completion is judged.
- Human judgment remains necessary for subjective outcomes when no adequate automated observation exists.
- Skills do not become autonomous authorities and cannot modify purpose, policy, acceptance, or permissions.
- Existing task and workflow completion remain valid within their scope but do not imply project completion.
- Project completion does not imply indefinite correctness. New evidence, regressions, changed environments, or a successor purpose revision may create new governed work without rewriting historical acceptance.
- The first implementation may compile completion for one project type and a bounded set of claim/evidence kinds before generalizing.

## Alternatives Considered

### Define done as all tickets closed

Rejected because work-item state does not demonstrate that project outcomes were achieved.

### Define done as a green build

Rejected because build and test evidence may omit user, security, operational, migration, compliance, or accessibility outcomes.

### Let each model decide what done means

Rejected because completion is an authority and evidence judgment, not a generation result.

### Put purpose only in prompts and documentation

Rejected because visible prose does not create traceability, evidence gates, invalidation, or replayable completion authority.

### Make every project use the same completion checklist

Rejected because different project purposes require different claims, observation boundaries, and acceptance authorities.

### Let skills determine required outcomes

Rejected because skills are methods for advancing or evaluating claims. They are downstream of purpose and cannot define project success.
