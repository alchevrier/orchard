# ADR 043: Scoped Standards Overlays and Exception Authority

## Status

Accepted

## Context

Project standards and repository conformance established a stable engineering baseline, but one project-wide revision cannot express organization, module, or work-item requirements without duplication. Milestone 10.0 can also admit an `EXCEPTION_REQUEST`, while no authority can truthfully grant, scope, expire, revoke, or apply that request.

Letting a narrower policy silently replace inherited requirements would make governance weaker as it becomes more specific. Letting a model or resolution decision emit `EXCEPTION_ACTIVE` without separate authority would turn candidate text into a policy bypass. Orchard therefore needs deterministic composition and an exception lifecycle whose effect is independently verifiable from repository and policy evidence.

## Decision

Orchard stores scoped policy in a checksummed append-only ledger separate from immutable base standards. A scope is one of:

- organization;
- project;
- module; or
- work item.

Applicable overlays compose in that order. Nested module scopes apply from the least specific path to the most specific path. The latest immutable revision for an exact scope is authoritative and contains the complete cumulative adjustment set for that scope.

An adjustment may add a practice, tighten an inherited practice, or disable a practice. Adding cannot replace an inherited stable practice ID. Tightening may add requirements, evidence, remediation, required severity, or a mandatory floor. A narrower scope cannot disable a mandatory floor; composition records an explicit conflict and conformance fails closed.

The effective standard is a hash-pinned projection of the base standard, target scope, applicable overlay hashes, effective practices, and composition conflicts. Scans persist that projection so historical judgments retain their exact policy context.

Exceptions are independent from overlays. An exception proposal pins:

- project and exact scope;
- effective-standard hash;
- clean repository revision;
- affected stable practice IDs;
- rationale and compensating controls;
- content-hashed control evidence;
- requested activation and expiry; and
- optional campaign-resolution lineage.

A proposal is not active. Explicit admission records the exact proposal hash, grantor, activation, and expiry. Active evaluation additionally requires the proposal revision to remain an ancestor of current HEAD, its control evidence bytes to remain unchanged, its scope to apply, its effective-standard hash to match, its time window to be active, and no effective revocation.

The projected lifecycle is `PENDING`, `ACTIVE`, `EXPIRED`, `REVOKED`, `SUPERSEDED`, or `INVALIDATED`. `SUPERSEDED` means effective policy changed. `INVALIDATED` means admitted authority no longer passes repository ancestry or evidence validation. Historical proposal, admission, and revocation records remain immutable.

An active exception permits `EXCEPTION_ACTIVE` for only its declared practices and scope. It does not require that disposition when current evidence independently proves `CONFORMING`, and it does not alter unrelated practices. The deterministic host rejects `EXCEPTION_ACTIVE` without exact applied admission authority.

A conformance scan identity includes its effective-standard hash and sorted active exception admission hashes. A repository may therefore be rescanned at the same Git revision after admission, expiry, revocation, supersession, or invalidation changes policy authority. Campaign evaluations include the same policy authority in idempotency and terminal campaigns become eligible for reevaluation when that authority changes.

An admitted Milestone 10.0 `EXCEPTION_REQUEST` seeds exactly one candidate exception proposal pinned to the resolution case, admission, terminal scan citations, and requested control. Reconciliation is idempotent and never admits the resulting proposal automatically.

The cockpit projects effective practices, mandatory floors, overlay sources, conflicts, exception state, expiry, and resolution lineage. Mutation APIs remain backend-validated even when the desktop offers a narrower convenience workflow.

## Consequences

- Engineering requirements can vary by organizational and delivery scope without copying or mutating the base standard.
- Narrower policy can strengthen inherited controls but cannot silently remove mandatory floors.
- Every active exception is bounded by policy, scope, Git ancestry, time, immutable admission, and unchanged control evidence.
- Resolution requests become actionable candidates without collapsing request and grant authority.
- Expiry or revocation can remove campaign closure authority even when repository HEAD is unchanged.
- Historical scans explain exactly which effective policy and exception admissions governed their findings.
- Existing standards, scans, campaigns, and evaluations retain canonical hash compatibility because new fields are optional and omitted when absent.

## Boundaries

- Actor and grantor strings are attributable records, not authenticated identities. Roles, delegation, quorum, and signatures are deferred to Milestone 10.3.
- Organization overlays are local ledger authority, not verified external policy sources.
- The desktop currently provides project-focused authoring conveniences; typed APIs support organization, project, module, and work-item scope.
- Exception control evidence proves unchanged repository bytes, not the real-world effectiveness of a compensating process.
- `RESCAN` and `STANDARD_CLARIFICATION` resolution executors remain future work.
- Policy changes do not retroactively rewrite historical scans or campaign evaluations.

## Alternatives Considered

### Put scoped fields on base standard revisions

Rejected because organization and narrow delivery scope have independent identity and revision cadence. Mutating or cloning the base standard would obscure inheritance and historical policy context.

### Let narrower scopes replace inherited standards

Rejected because replacement permits accidental or malicious weakening. Explicit operations and mandatory floors make composition inspectable and deterministic.

### Treat an admitted exception request as an active exception

Rejected because choosing to consider an exception is not the same authority as granting one. Separate proposal and admission records preserve that distinction.

### Encode exceptions as disabled practices

Rejected because an exception is bounded permission under compensating controls, not deletion of the requirement. Disabling would erase the reason, expiry, evidence, and affected repository context.

### Identify scans only by Git revision and base standard

Rejected because active policy authority can change without a commit. That identity would incorrectly reuse stale evidence after admission, expiry, or revocation.
