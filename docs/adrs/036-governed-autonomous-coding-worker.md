# ADR 036: Governed Autonomous Coding Worker

## Status

Accepted

Supersedes ADR 016.

## Context

Durable Circuit Dispatch creates an immutable governed workflow run and reserves a Git worktree at the exact repository revision authorized by the accepted circuit. Requirement Authority and Contract-Compiled Acceptance Gates pin the implementation requirements and define the only evidence that can complete that run.

Orchard still lacked an execution plane. Dispatch stopped after creating the run and worktree, so an external actor had to interpret the context, edit files, commit a candidate, execute verification, and submit evidence.

A coding model cannot receive authority to complete the workflow directly. Its output is an untrusted candidate action. Repository mutation, process execution, evidence admission, human judgment, integration, and publication remain separate authorities.

## Decision

Orchard runs a local autonomous coding worker for circuit-dispatched Task and Bug workflow runs.

The worker consumes the immutable `WorkflowRunView`, including:

- Project, Epic, Story, and Task or Bug context;
- exact repository base revision and isolated workspace reservation;
- accepted Work Definition;
- acceptance contract and compiled evidence gates;
- circuit dispatch identity; and
- recalled completed-work episodes.

The worker never reads a raw ticket as independent execution authority.

### Durable Execution Authority

Every model invocation begins with an append-only `CodingWorkerClaim`. The claim pins:

- monotonically assigned execution ID;
- workflow run ID and bounded attempt number;
- immutable context-manifest hash;
- reserved worktree path;
- selected model-binding fingerprint; and
- claim time and content hash.

Exactly one coding execution may remain active in the journal. A terminal result records the model execution ID, decoded proposal hash, changed paths, candidate revision, diagnostic, and outcome. Replay validates event order, payload exclusivity, hashes, attempt sequence, claim/result identity, and terminal requirements. A malformed final JSONL suffix is quarantined; interior corruption fails closed.

Journal validation and append occur under one cross-process file lock. On restart, a claim without a terminal result is recorded as interrupted before new work is admitted.

### Model Authority

Coding uses the versioned `bounded-coding-patch-v1` model execution profile. The invocation envelope contains the immutable run and a bounded selection of tracked UTF-8 repository files. Mandatory context overflow fails before inference.

The model may propose only strict JSON `WRITE` and `DELETE` operations. Each write contains complete UTF-8 file content. The model cannot propose commands, approvals, workflow transitions, commits, merges, pushes, or evidence.

Every inference attempt is retained in model-execution memory with profile, binding, envelope, prompt, output, token, latency, schema-validity, and resource-admission evidence.

### Repository Mutation

An Orchard-owned gateway applies decoded proposals only in the reserved Git worktree. It requires:

- an absolute, existing Git worktree;
- a clean index and worktree before mutation;
- normalized relative paths beneath the worktree;
- no traversal through symbolic links;
- no `.git` or `.orchard` target;
- bounded path, file, operation, and byte counts; and
- regular-file targets for replacement or deletion.

Writes use temporary files, forced file data, atomic replacement where available, and parent-directory synchronization. Orchard runs `git diff --check`, stages only validated proposal paths, and creates a local candidate commit with a deterministic worker identity. Application failure restores original file bytes and records a failed execution.

The worker revalidates run state, context hash, circuit identity, and workspace reservation immediately before mutation. Cancellation or authority change during inference therefore prevents a stale candidate write.

### Verification and Evidence

Verification commands come from the pinned evidence contract or a repository toolchain policy resolved under ADR 037. Model output never selects a command. Commands execute as bounded argument vectors without shell expansion. Execution uses the exact worktree, a reduced environment, timeout, process-tree termination, and bounded output capture.

The worker submits observations only through `WorkspaceStore.submitEvidence`. That existing gate engine remains the sole completion authority. Automated criteria require their exact admitted command. Human criteria are skipped by the worker and still require immutable human judgment.

New source-diff evidence uses a canonical hash computed from the pinned base revision, canonical target revision, and binary Git diff. Replay re-proves that hash when the evidence record marks its output as canonical. Older evidence remains replay-compatible.

A failed verification records evidence and a failed worker execution. Later attempts append new claims, proposals, commits, and evidence without erasing prior failures. The bounded repair budget is three actionable failures by default. Transient resource denial is persisted as `DEFERRED` with a retry time and does not consume that budget. A completed coding execution does not rerun merely because the workflow is awaiting human gates.

### Scheduling and Inspection

A cancellable backend coroutine ticks the worker after durable dispatch. The service mutex serializes automatic and manual ticks. One tick handles at most one claim.

The backend exposes:

- `GET /api/coding-worker/executions`
- `POST /api/coding-worker/tick`

These operations inspect or trigger worker execution; they do not grant additional repository or workflow authority.

## Consequences

- Accepted circuits can now produce local candidate commits without an external coding process.
- Model output remains proposal data rather than repository, process, evidence, or completion authority.
- Every candidate is traceable from requirement contract through run, claim, model execution, proposal, commit, verification, and gate evidence.
- Failed approaches become durable training and routing material instead of disappearing in transient logs.
- Human acceptance criteria remain outside autonomous worker authority.
- The worker can repair a failed candidate with later immutable attempts on the same isolated branch.

## Boundaries

- Candidate branches are not merged, rebased, pushed, published, cleaned up, or conflict-resolved automatically.
- The first worker uses full-file replacement operations rather than semantic patches or language-server edits.
- Verification has command, environment, time, and output bounds but no OS-level filesystem or network sandbox. Repository build scripts and local actors able to mutate a reserved worktree are trusted accordingly. Bubblewrap or an equivalent policy-controlled runner is required before processing untrusted repositories.
- Toolchain policy defaults are used only when a gate has no exact admitted command. A required executable gate with no resolvable command fails closed.
- The production Ollama binding remains `phi3:mini`; the coding profile is separately routable but no larger code-specialized binding is installed by this decision.
- Context selection is bounded lexical ranking over tracked text files. Source-bound RAG and language-aware symbol retrieval remain future derived context improvements.
- The scheduler is single-backend authority. The file lock prevents conflicting journal appends across processes, but distributed leasing and remote workers are not implemented.
- No dedicated Compose coding-worker view is included. The typed API exposes execution history for subsequent UX work.

## Alternatives Considered

### Let the model invoke shell and Git directly

Rejected because model output would become repository and process authority, making command provenance, bounds, and evidence separation unenforceable.

### Mark the ticket complete after a successful build

Rejected because completion belongs to the compiled acceptance contract. Build success cannot satisfy regression, acceptance, automated criterion, or human judgment gates by implication.

### Reuse workflow attempt prose as the worker claim

Rejected because a free-form attempt record cannot pin model binding, context, workspace, proposal, event order, or exactly-once execution authority.

### Mutate the bound project repository directly

Rejected because durable dispatch already reserves isolated branches and worktrees. Direct mutation would bypass circuit ownership and contaminate user or parallel-agent changes.

### Require human approval before every file write

Rejected for this autonomous milestone. The stronger boundary is isolated candidate commits plus unchanged workflow completion authority. Projects that require pre-write approval need a future policy gate before worker claim admission.
