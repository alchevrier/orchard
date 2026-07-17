# ADR 004: Evidence-Producing Workflows and Governance

## Status
Accepted

## Context
AI agents are prone to hallucinating code completion or claiming a task is done without verifying it works. This is not solved by selecting a supposedly trustworthy model: local and hosted LLMs, deterministic tools, and humans can all produce incomplete, mistaken, or selectively supported outputs. Orchard’s philosophy is therefore "governed, evidence-producing workflows" rather than trust in the producer.

An execution agent can also produce a plausible benchmark, omit the workload that falsifies its claim, and then present its own output as proof. Raw measurements are not automatically evidence, and a benchmark author cannot grant authority to its own methodology.

The same authority problem applies to research. An agent can cite real sources that do not support its conclusion, repeat one underlying claim through several derivative sources, omit contrary evidence, or choose a methodology after seeing the results. Research quality must come from an enforceable, inspectable methodology and bounded evidence, not from the fluency or identity of the researcher.

## Decision
Integrate a mandatory Testing & Benchmarking phase (the Quality Center) for agent execution:
- A design is a candidate artifact until it passes design admission. Local Architect models may propose and revise designs, but cannot accept their own design or use it as downstream authority before admission.
- Design admission applies deterministic structural validation, independent read-only design inspection, and the configured design-approval policy. It checks problem and scope definition, assumptions, constraints, alternatives, architecture boundaries, failure modes, quality attributes, security and compliance impact, feasibility, internal consistency, conflict with governing ADRs, traceability, falsifiability, required observations, unresolved decisions, and named approval authority.
- Every requirement must either define an inspectable acceptance path or identify an explicit human judgment gate. Ambiguous, contradictory, infeasible, unfalsifiable, or policy-conflicting designs return structured findings for a new Architect attempt rather than being compiled into implementation gates.
- Only an admitted, versioned, content-hash-pinned design may produce an acceptance contract. The contract records exact requirement-to-criterion traceability so missing, weakened, or mistranslated design requirements cause contract rejection.
- Every work item has a versioned acceptance contract before coding begins. It defines required behavior, constraints, failure cases, quality thresholds, permitted exclusions, and the observations needed for acceptance. The coding agent cannot weaken or reinterpret that contract.
- Orchard assigns exact requirement authority by work-item level:
	- An **Epic** defines the exact system requirements, including system boundaries, externally observable behavior, end-to-end quality budgets, authority boundaries, failure behavior, and system acceptance criteria.
	- A **Story** defines the exact subsystem requirements that satisfy identified Epic requirements, including interfaces, allocated budgets and constraints, dependencies, failure behavior, and subsystem acceptance criteria.
	- A **Task** or **Bug** defines the exact implementation requirements that satisfy identified Story and Epic requirements, including the affected artifacts, required behavior or correction, implementation constraints, regressions that must be prevented, and implementation acceptance criteria.
- Each requirement has a stable identifier and every child requirement records the parent requirements it implements. Decomposition may make a parent requirement more specific but cannot omit, weaken, contradict, or silently redistribute it. Unallocated parent requirements, conflicting allocations, and child requirements without an admitted parent fail contract synthesis.
- A Task or Bug that reveals an incorrect or missing subsystem or system requirement cannot repair the requirement hierarchy implicitly. It triggers a new admitted Story or Epic revision before implementation proceeds, preserving the prior requirement and the reason for change.
- Coding agents produce **candidate artifacts**: implementation, tests, benchmark harnesses, workload definitions, documentation, README and release-note changes, environment capture, and reproduction commands. Agent-authored code, tests, documentation, coverage statements, benchmark summaries, and completion statements are claims until accepted.
- A separately governed, read-only inspector validates the candidate artifacts against the acceptance contract. It checks that production paths implement the required behavior and that tests can falsify them rather than merely execute lines.
- Test inspection includes requirement traceability, meaningful assertions, negative controls, production-path execution, independently governed coverage denominators and exclusions, and mutation or fault sensitivity where proportionate to risk.
- Documentation inspection checks every current-state claim against the pinned design, acceptance contract, implementation revision, and accepted evidence. It rejects planned behavior written as delivered, unsupported performance claims, stale commands, omitted limitations, and README summaries that exceed what was accepted.
- README files, generated reference pages, release notes, and dashboards are projections for communication, not authority. They must source-pin the accepted artifacts they summarize and cannot override the approved design or acceptance contract.
- Benchmark inspection checks that the workload covers the claim, the control is comparable, filtering is disclosed, units are coherent, transitions are observable, and raw inputs can be pinned by content hash.
- A deterministic evidence runner executes the inspector-approved plan against the pinned candidate revision in a clean sandbox. It independently produces raw observations, coverage data, logs, environment metadata, and content hashes. Documentation commands and code examples are executed or compiled where possible; links, generated API references, schemas, and version statements are checked for drift.
- A governed quality policy derives an accepted evidence artifact only from inspector-approved means and deterministic-runner observations. Neither the coding agent nor the inspector may self-certify completion.
- Evidence must preserve provenance linking the inspector, inspected harness revision, workload definition, environment, and immutable raw observations.
- The UI will include a dedicated Dashboard / Coverage Tab visualizing unit/integration test coverage, LOC covered.
- Performance evidence must include claim-relevant workloads, controls, transition-conditioned results where applicable, unfiltered maximums, and the raw observations from which percentiles were derived.
- Tickets cannot proceed from "Execution" to "Done" unless the defined criteria/evidence thresholds are met and verifiable by the system.

The quality workflow is an explicit feedback loop:

```text
candidate design
	-> design admission
	-> accepted design
	-> acceptance-contract synthesis and validation
	-> immutable coding attempt
	-> read-only inspection
	-> deterministic execution
	-> quality-policy decision
		-> accepted: ticket may complete
		-> rejected: structured findings start a new coding attempt
```

Each rejected attempt remains immutable and auditable. Findings identify the unmet acceptance criterion and link the observations that falsified the attempt. The coding agent may revise code and candidate tests in a new attempt, but not the acceptance contract, inspector findings, prior observations, or quality policy. Iteration continues until acceptance or a governed retry limit triggers human escalation; exhaustion is not completion.

Research uses the same authority model. Before collection or synthesis begins, Orchard admits a versioned research protocol defining:

- the exact question, scope, terminology, assumptions, decision context, and required output;
- permitted source classes, required primary sources, independence rules, jurisdiction or population, publication-date bounds, and source-quality thresholds;
- search locations, queries, sampling method, inclusion and exclusion criteria, stopping conditions, and treatment of inaccessible material;
- the claim-to-source schema, including direct support, derived inference, contradiction, uncertainty, and negative or missing evidence;
- required cross-checks, replications, calculations, domain reviewers, and human judgment gates; and
- freshness, citation, archival capture, content hashing, licensing, confidentiality, and reproducibility requirements.

Research agents produce candidate source collections, extraction records, calculations, claim graphs, contradiction records, and reports. A separately governed inspector verifies that cited material exists, is represented in context, supports the exact attributed claim, satisfies the admitted source rules, and is independent where corroboration is required. Multiple articles repeating one underlying source count as one evidentiary lineage, not independent confirmation. Cross-references must expose common authorship, common datasets, citations between sources, and other dependencies that could create false corroboration.

Where possible, a deterministic runner repeats searches, retrievals, transformations, and calculations against pinned inputs. Accepted research evidence preserves source versions, access times, exact passages or structured extracts where legally permitted, query and tool versions, exclusions, unresolved contradictions, and the derivation from observations to each conclusion. Conclusions cannot exceed the strength or scope of their accepted evidence and must distinguish observed fact, sourced claim, inference, hypothesis, and configured human judgment.

The research agent cannot change the protocol retrospectively, suppress conflicting observations, declare sources independent, assign final confidence, or accept its own synthesis. A methodology change creates a new protocol revision and attempt while preserving the earlier search record. Missing evidence, unresolved material contradiction, unavailable required sources, or a failed replication produces a bounded inconclusive result or escalation, never fabricated certainty.

Design admission does not create an infinite self-validation chain. Its rules derive from versioned organization policy, Orchard's non-bypassable integrity invariants, and configured human authority rather than from the candidate design being inspected. Changes to that design meta-policy are separately governed platform or organization decisions and apply prospectively through new policy revisions.

Governance is proportional to scope and risk. Design-first does not require a full architecture document for every change. A low-risk request such as “make the Create New button this shade of blue” may be represented by a compact admitted design containing the target component, exact design token or color value, affected interaction states, and applicable accessibility floor. Its contract may require only a clean build, exact token inspection, a focused visual observation, and contrast validation when mandated by policy.

```text
Intent: Create New uses action-primary-600
Scope: default, hover, focus, disabled states
Acceptance: computed style matches approved tokens; required contrast passes
Authority: automatic under delegated low-risk UI policy
```

If the request is inherently subjective, such as “use the blue that feels right,” the contract records a human visual-approval gate rather than inventing a false objective metric. Autopilot may prepare alternatives and observations, but only the configured authority resolves that judgment.

Risk classification selects the admission and evidence depth. Trivial, reversible, local presentation changes may use a fast path; shared design-system changes, authentication UI, legal disclosures, destructive controls, accessibility-sensitive interactions, or broad component rewrites trigger stronger profiles. The coding agent may propose a risk classification but cannot lower the effective class or select its own fast path. Classification comes from deterministic scope signals and configured policy, with escalation when uncertainty remains.

Profiles may attach versioned **domain assurance packs** that define what a usable design and acceptable evidence must contain for a particular system class. These packs are organization-configurable and compose with general security, quality, and compliance policy. They do not merely increase test count; they introduce domain-specific design obligations, metrics, failure models, inspectors, and approval roles.

For a low-latency trading application, design admission may require a top-to-bottom system design covering at least:

- **Latency contract:** externally meaningful start and end points, such as NIC ingress to normalized event, book update, strategy decision, risk decision, NIC egress, and exchange acknowledgement; per-stage budgets; p50, p99, p99.9, and unfiltered maximum objectives; timestamp source, synchronization, warm-up, load shape, transition workloads, and measurement error.
- **Market and network topology:** venues, sessions, multicast groups, sequence and gap handling, NICs, queues, kernel-bypass mode, VLANs, routes, firewall and egress policy, hardware timestamps, IRQ placement, core affinity, NUMA placement, and downstream network assumptions that are measured separately from application latency.
- **Execution and risk controls:** pre-trade limits, credit and position checks, duplicate-order protection, throttles, kill switch, cancel-on-disconnect, stale-market behavior, reject handling, fail-open or fail-closed decisions, and the authority allowed to override each control.
- **Identity and host access:** named human, service, deployment, monitoring, and break-glass accounts; least-privilege access matrix; Linux users and groups; sudo and capability grants; device and huge-page access; secret ownership and rotation; production deployment rights; audit logging; and segregation of duties.
- **Capacity and degradation:** expected and adversarial message rates, burst duration, queue depths, backpressure, memory and CPU budgets, feed gaps, exchange disconnects, partial venue failure, replay, restart, recovery-time objectives, and behavior when latency or risk budgets are exceeded.
- **Artifact and change integrity:** reproducible builds, pinned toolchains and dependencies, SBOM, binary provenance and signing, configuration authority, deployment topology, rollback, production evidence retention, and approval requirements for strategy, risk, network, and infrastructure changes.

An accepted trading design decomposes system objectives into source-pinned component contracts without pretending that application code controls external network or exchange latency. Downstream network measurements remain separate observations with their own environment and provenance, while the end-to-end contract states how those observations compose with application stages.

Changes to one component must be checked against the admitted system design. A parser optimization, account-permission change, NIC reconfiguration, or new dependency may use a focused implementation contract, but impact analysis determines whether it changes latency budgets, access boundaries, risk behavior, deployment assumptions, or previously accepted evidence. Material impact triggers revision and re-approval of the affected system-design sections rather than silently treating the change as local.

Acceptance policy is configurable through versioned profiles. Orchard resolves the effective contract before an attempt from four ordered sources:

```text
non-bypassable Orchard invariants
	+ organization, security, and regulatory mandates
	+ project acceptance profile
	+ work-item-specific criteria
	= immutable effective acceptance contract
```

Users may choose looser or stricter validation within the authority delegated to them. An exploratory profile may require compilation, focused tests, and basic documentation consistency; a production profile may add broad integration tests, mutation thresholds, performance regressions, dependency review, and rollback evidence; a hardened or regulated profile may additionally require threat models, static and dynamic security analysis, secret scanning, SBOM and license policy, vulnerability thresholds, segregation of duties, retention rules, and explicit human approvals.

Rigor is multidimensional rather than a single loose-to-strict scalar. Profiles independently govern at least:

- **Code reuse and duplication:** allow local duplication, prefer an existing internal component, or require reuse from an approved internal catalog unless a governed waiver demonstrates that reuse would violate the design or create unacceptable coupling.
- **External dependency provenance:** allow public open-source dependencies with recorded source and license metadata, require organization allowlisting and pinned digests, require a firm-owned security and license audit, require vendoring and reproducible builds, or prohibit unaudited external code entirely.
- **Supply-chain controls:** approved registries, lockfiles, signatures or attestations, SBOM generation, transitive-dependency review, vulnerability thresholds, update policy, and artifact retention.
- **Implementation assurance:** architecture-conformance checks, complexity and duplication thresholds, forbidden APIs, memory or allocation constraints, security analysis, and domain-specific correctness obligations.

A permissive profile may let the coding agent select a public library when it records provenance, license, version, and alternatives. A strict firm profile may require evidence that the internal component catalog was searched, mandate reuse or an approved exception, deny network access to unapproved registries, and accept an external package only when a separate firm authority has approved its exact source revision and transitive dependency closure. The coding agent cannot create or assert that approval.

Reuse is not intrinsically safer than duplication, and public open-source code is not intrinsically less safe than private code. Mandatory reuse can spread coupling or vulnerabilities, while mature public code may have stronger scrutiny than a new internal implementation. Orchard therefore enforces the configured decision and its rationale rather than treating either reuse or originality as universally correct.

Configurability cannot disable Orchard's core integrity guarantees: producer and approver separation, immutable attempt history, pinned provenance, deterministic execution records, disclosure of exclusions, and truthful representation of inspection status. Organization or jurisdictional policy may establish mandatory floors that project users can tighten but cannot weaken. Risk classification may raise the effective floor for a work item but cannot silently lower it.

Every effective contract records the selected profile versions, inherited controls, overrides, authority for each override, and conflict-resolution result. Changing any criterion after an attempt starts creates a new contract revision and a new attempt; it never retroactively changes the meaning of existing observations or evidence. The UI must show which controls are required, selected, waived, or unavailable and why.

Compliance profiles encode inspectable technical and procedural controls, not an automatic legal conclusion. Claims of legal or regulatory compliance require the configured approval authority and must identify the policy version, jurisdiction, evidence set, exceptions, and human sign-off on which the claim depends.

Orchard exposes the same governed workflow through different autonomy modes:

- **Autopilot:** Intended for users who want outcomes rather than workflow administration. A local LLM may propose the design and acceptance contract, create candidate artifacts, consume structured rejection findings, and iterate without user intervention. Deterministic policy may automatically accept only controls for which the user or organization has explicitly delegated automatic authority. Ambiguity, exhausted retries, unavailable checks, policy conflicts, and mandatory human gates escalate instead of being silently bypassed.
- **Auditable:** The workflow may iterate automatically, but every design, effective contract, attempt, inspection finding, command, observation, waiver, and decision is retained and inspectable. Users can require approval of the design or contract while allowing accepted low-risk implementation attempts to complete automatically.
- **Approval-gated:** Intended for CTO, security, legal, or regulated operation. Selected transitions require explicit authorized approval, potentially with multiple roles or segregation of duties. A ticket cannot enter execution, accept a waiver, change its contract, or become complete without the configured approval records.

Autonomy mode changes who must approve a transition, not whether validation occurs. A fully automated workflow still uses pinned designs, acceptance contracts, independent inspection, deterministic execution, provenance, and immutable findings. A strict workflow uses the same artifacts and state machine but adds non-bypassable approval predicates.

Local LLMs remain proposal and analysis components in every mode. They cannot issue approval records, mark their own inspection as accepted, change the active autonomy mode, or convert missing observations into success. Automatic acceptance is a deterministic policy action exercised under previously delegated authority, not an LLM decision.

The active autonomy mode, delegated authorities, required approver roles, quorum, and escalation behavior are versioned policy inputs to the effective acceptance contract. Changing them creates a new governed revision and cannot retroactively authorize an existing attempt.

Acceptance governance evolves with the organization. A founder may begin with an exploratory profile, accumulate visible quality or dependency debt, and later appoint a CTO or other authority to introduce stricter project gates and commission governed refactor or remediation work. A policy transition may:

- apply the new floor to all new attempts immediately;
- establish a no-regression ratchet so touched code cannot become worse than its accepted baseline;
- inspect the existing system and create source-pinned remediation tasks for duplication, architecture drift, unaudited dependencies, missing tests, documentation mismatch, or security findings;
- set milestones after which unresolved findings block release or deployment; or
- require explicit acceptance of residual risk by the newly configured authority.

Stricter policy does not retroactively convert historical observations into failures or claim that legacy code was previously accepted under the new standard. Existing artifacts retain the policy and contract under which they were accepted. Re-evaluation creates new observations, findings, and remediation contracts, preserving the historical record while allowing quality to ratchet upward.

Larger organizations compose policy by scope. Organization-wide integrity, security, legal, and supply-chain floors apply to every team. Business units, repositories, and teams may add stricter overlays or use delegated looser profiles where the organization floor permits. Work-item exceptions must identify their scope, authority, rationale, duration, affected artifacts, and compensating controls; they do not silently relax sibling teams or future work.

Policy conflict resolution is deterministic. Mandatory higher-scope controls prevail unless that authority explicitly delegates an override, and the effective contract records the winning rule. Temporary waivers expire and trigger re-inspection or escalation rather than becoming permanent undocumented policy.

## Consequences
- Workflow design must include explicit "Success Criteria" handling.
- Orchard requires a governed registry of candidate, rejected, superseded, and admitted design revisions with immutable findings and approvals.
- Acceptance-contract synthesis must fail closed when any admitted design requirement lacks a valid criterion or authorized human gate.
- Design and evidence ceremony must scale with risk so low-risk work remains economical without weakening mandatory floors.
- Fast-path eligibility and risk classification are governed decisions, not coding-agent assertions.
- Domain assurance packs must be versioned, organization-configurable policy data with explicit required design sections, metrics, inspectors, evidence sources, and approval roles.
- Research assurance packs must define admitted methodologies, source and independence rules, claim-level traceability, contradiction handling, reproducibility checks, confidence authority, and criteria for inconclusive outcomes.
- Component contracts must preserve traceability to affected system-level budgets and authority boundaries; local acceptance cannot override an admitted whole-system constraint.
- Agents need sandboxed execution environments or CI hooks to run Playwright, unit tests, and profilers locally to generate inspectable observations.
- Quality inspection is a distinct authority boundary, not another name for the coding step.
- A failed inspection invalidates the candidate evidence without erasing the raw observations or audit trail.
- Acceptance criteria must be established independently of the implementation attempt and changed only through a separately authorized contract revision.
- Inspector-owned challenge tests or test plans must live outside the coding agent's write authority.
- Retry budgets and escalation policy are required to prevent an agent from looping indefinitely or declaring success after exhaustion.
- Documentation rejection returns structured mismatches to a new candidate attempt just like code or test rejection; prose is never accepted merely because the underlying code passed.
- Acceptance-profile composition, delegation, mandatory floors, waivers, and conflicts must be deterministic and auditable.
- Policy presets must remain versioned data rather than hard-coded claims that Orchard universally satisfies a security standard or law.
- Internal-component catalogs, dependency allowlists, audit decisions, waivers, and approved artifact digests are governed authority; coding-agent searches and recommendations are candidate observations only.
- Automation may remove user interaction but never validation, provenance, or configured approval requirements.
- Approval records must be source-pinned to the exact design, contract, attempt, evidence set, policy revision, and approver authority they authorize.
- Policy migrations must preserve historical acceptance context while creating explicit baselines, remediation contracts, and no-regression rules for future work.
- Team-specific profiles and exceptions must inherit organization floors and remain scoped, versioned, attributable, and time-bounded.
