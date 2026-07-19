# Conversation Conductor

The conversation subsystem is an orchestration authority above Orchard's existing domain services. It records chronology, objective revisions, exact command admission, dispatch intent, downstream correlation, bounded summaries, model provenance, and projected activity. It does not duplicate workspace, workflow, repository, audit, standards, or promotion authority.

## Modules

- `conversation/ConversationAuthority.kt` defines checksummed append-only records, replay validation, malformed-tail recovery, dependency-cycle rejection, and file locking.
- `conversation/ConversationConductor.kt` validates speech acts, compiles bounded context, orders commands per objective, enforces admission and dependencies at dispatch, reconciles interrupted commands, and projects workflow activity.
- `conversation/ConversationCapabilities.kt` contains the closed typed adapter registry.
- `conversation/ConversationRoutes.kt` exposes the loopback Ktor API.
- `workspace/RepositoryOnboardingService.kt` validates local Git roots and safely materializes admitted HTTP(S) remotes in managed storage.
- `frontend/.../DurableConversationWorkspace.kt` renders chronology, objective controls, exact admissions, and correlated activity.

## Capability Contract

A capability descriptor must declare:

- a stable uppercase ID and payload schema;
- read-only or mutation class;
- allowed objective states;
- the owning service;
- the admission rule;
- the projected result type; and
- an idempotency strategy.

Mutation adapters receive the immutable command proposal and must call the owning service. Persist `ConversationCommandReference` in downstream authority when that schema supports it. Otherwise, reconciliation must use an authority-specific unique key; never match titles, timestamps, or generated prose. The conductor appends `DISPATCHED` before invoking an adapter and `CORRELATED` only after resolving an exact downstream ID and hash.

Project genesis revisions and workflow runs encode optional command references compatibly when absent. `ADVANCE_PROJECT_GENESIS`, `ADMIT_PROJECT_GENESIS`, and `START_WORKFLOW` reconcile only that exact reference. Repository binding uses the unique project and canonical-path pair because binding authority is a single current mapping rather than an append-only command-bearing record.

`ONBOARD_REPOSITORY` creates a new project with its `ConversationCommandReference`, or selects one explicit existing project, before canonical binding. Recovery first adopts the exact command-bearing project and current binding. URL materialization derives a destination from canonical URL plus SHA-256 prefix, clones through a temporary directory, verifies `remote.origin.url`, and then moves into managed storage. Git prompts, submodules, and LFS smudging are disabled; no repository-owned executable is invoked.

Model onboarding remains split by authority:

- `INSPECT_MODEL_CONFIGURATION` reads provider catalog, endpoint health, compatibility, and profile overrides;
- `REGISTER_MODEL` upserts one validated endpoint and binding through `ModelProviderRegistry` and permits only environment credential references;
- `ASSIGN_MODEL_PROFILE` calls `DefinitionIntelligenceService.updateProfile` and reuses the current budget unless an admitted payload changes it.

Registration does not install runtimes or download models. Profile-assignment recovery compares the exact durable override. Model providers are exposed through the registry's live provider view, so new assignments become available to workers without rebuilding their services.

## Scheduling

The conductor uses one mutex per objective, never one mutex per conversation. Run-based production workers expose eligible run IDs and lock by run. Before analysis, coding, or audit dispatch, `dispatchableRunIds` removes runs belonging to paused or cancelled objectives, requires completed dependencies, and orders the remainder by objective priority. Legacy runs with no conversation correlation remain eligible.

Coding claims and repository-analysis plans allocate event IDs atomically in their durable stores. Coding permits one active claim per run. Company audits lock by run so the two roles stay ordered for one candidate while unrelated runs can proceed concurrently. Campaign proposals lock by case and allocate proposal IDs atomically.

Promotion remains serialized by company authority and delegates ancestry/revision validation to the repository binding store before recording the destination revision.

## Recovery and Compatibility

`conversations.jsonl` is replayed under `conversations.lock`. Optional fields are encoded compatibly when absent. A restart reconciles admitted commands whose latest execution is `DISPATCHED`: the adapter first searches exact downstream authority and invokes mutation only when no unique adoption exists.

Conversation model provenance is optional for compatibility with earlier records but mandatory for turns produced by `ModelConversationInterpreter`. Its hashes and budgets are validated as part of the activity record checksum.

## Validation

Run focused conversation tests with:

```bash
./gradlew :backend:jvmTest --tests 'com.orchard.backend.conversation.*' --no-daemon
```

Run coding and company circuit tests after worker scheduling changes, then finish with:

```bash
./gradlew build --no-daemon
```

ADR 044 remains proposed until the three live Orchard-on-Orchard changes complete through local promotion and their intervention, model, timing, failure, evidence, and bypass reports are recorded.
