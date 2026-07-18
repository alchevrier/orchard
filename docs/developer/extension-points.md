# Extension Points

Extend Orchard through existing authority boundaries. Avoid adding a direct route-to-model-to-mutation path; it bypasses the product's central governance guarantees.

## Add a Model Provider Protocol

Current protocols are Ollama-native and OpenAI-compatible.

To add a protocol:

1. Extend the provider protocol model and its deterministic catalog validation.
2. Implement the provider adapter behind the existing model provider interface.
3. Define locality, credential-reference, timeout, structured-output, and endpoint-inspection behavior.
4. Wire it through `ModelProviderRegistry` in the backend composition root.
5. Add typed desktop configuration and inspection support.
6. Test local/remote policy rejection, unavailable credentials, malformed output, and resource lease release.
7. Update [Models and Resources](../user-guide/models-and-resources.md).

Persist only references such as `env:NAME`; never persist resolved secrets or include them in diagnostics.

## Add or Change an Execution Profile

Profiles define a bounded reasoning role, input/output budgets, required capabilities, and routing constraints.

When adding a profile:

1. give it a stable ID;
2. define defaults and compatible binding requirements;
3. compose a bounded prompt from admitted and hash-pinned context;
4. enforce context budget before inference;
5. validate the complete structured envelope after inference;
6. expose settings only where user adjustment is safe; and
7. add route/client/UI and failure-path tests.

Do not use one broad profile to erase distinctions between definition, coding, analysis, and independent audit.

## Add an Agent Prompt

Default prompts live in `backend/src/main/resources/default-system-prompts/`.

A prompt change is behavior. Keep it strict about:

- the model's role and prohibited authority;
- exact JSON shape;
- required identifier and hash echoing;
- evidence/citation requirements;
- complete item coverage;
- bounded output; and
- no prose outside the envelope.

The service must still reject invalid or semantically incomplete output. Prompt instructions are not a security or integrity boundary.

## Add a Toolchain Policy Pack

Toolchain policy packs live under `~/.orchard/policy-packs/toolchains/` and are loaded by `FileToolchainPolicyCatalog` through `LocalCodingWorkspaceGateway`.

A pack should define recognized repository/toolchain evidence and allowlisted verification behavior without enabling arbitrary shell execution. Preserve canonical path checks, working-directory containment, timeouts, and captured evidence hashes.

Add tests with a temporary repository for detection, allowed commands, rejected commands, and deterministic output.

## Add a Coding Operation

Coding output is a typed operation set, not an unrestricted script.

For a new operation:

1. extend the serialized operation model compatibly;
2. validate paths and operation-specific invariants before mutation;
3. constrain every target to the managed worktree;
4. make application deterministic;
5. include the result in canonical diff/evidence calculation; and
6. test traversal, symlink, stale-base, duplicate, and partial-application behavior.

Prefer structured parsers for structured formats. Do not add an operation merely to avoid modeling a safe existing edit.

## Add a Persistent Authority

Create a dedicated store and service when a concept has independent identity, admission, history, or recovery semantics.

Define:

- immutable record types and stable IDs;
- sequence/checksum envelope;
- source revision/hash pins;
- validation before append;
- replay invariants;
- duplicate/idempotent behavior;
- interrupted-operation reconciliation; and
- old-record compatibility.

Use [Persistence and Recovery](persistence.md) as the implementation checklist. Do not hide a new authority in a mutable field on an unrelated projection.

## Add a Workflow Stage

A stage belongs in the company circuit only when it adds distinct evidence or authority. Specify:

1. entry preconditions;
2. admitted inputs;
3. candidate outputs;
4. deterministic validation;
5. evidence produced;
6. who may accept it;
7. terminal and retry states; and
8. restart reconciliation.

Update circuit compilation, dispatch, workspace transitions, company projection, UI, tests, and the applicable ADR. Preserve the rule that coding cannot audit or approve itself.

## Add an Engineering Practice

Practices have stable IDs across standards revisions and scans. New baseline practices must define applicability, severity, requirement, required evidence, remediation guidance, and default enabled state.

Test that:

- the practice appears exactly once in complete scan output;
- non-applicable and unknown evidence are distinguished;
- citations are repository-bound and hash-pinned;
- revision changes do not rewrite historical scans; and
- remediation and follow-up evaluation retain the stable practice lineage.

## Add a Standards Scope or Overlay Operation

Scope and operation values are serialized authority, not display-only enums. A new scope must define deterministic applicability, precedence, specificity, identity validation, and leakage tests. A new operation must define how it composes with inherited practices and mandatory floors.

Update pure composition, append validation, effective-standard hashing, scan target selection, typed APIs, cockpit projection, and replay tests together. Never let a narrower scope weaken inherited mandatory policy implicitly.

## Extend Exception Authority

Keep proposal, admission, current effect, and revocation separate. Any new exception condition must be deterministic and included in scan/campaign authority identity when it can change conformance without changing Git HEAD.

Actor authentication, role authorization, delegation, quorum, and signatures belong in the identity authority planned for Milestone 10.2. Do not retrofit them as trusted string comparisons inside `StandardsPolicyService`.

## Add a Resolution Action Executor

Resolution admission creates successors for delivery actions. Exception requests seed candidate scoped exception proposals through idempotent reconciliation. Rescan and standard clarification remain durable decisions without specialized execution.

A future executor must consume an admitted decision and produce a separate execution record. It must not reinterpret proposal text as authority or mutate the predecessor campaign. Define idempotency across the external action and ledger append, then project execution status separately from decision admission.

Additional executors are sequenced after identity and verified policy authority on the [Roadmap](../../ROADMAP.md).

## Add a Frontend Projection

Use `DesktopNetworkClient` for typed transport and `OrchardCircuitBinder` for state loading and commands. Compose views should receive projected state and callbacks.

Include loading, empty, unavailable, blocked, stale, success, and diagnostic states. Keep state labels identical to backend enums where operators need to reason about authority. The backend remains responsible for validation even when the UI prevents an invalid gesture.

## Documentation and Decision Updates

An extension normally updates:

- a focused implementation test;
- [API Reference](api-reference.md) for route changes;
- the relevant user guide for operator-visible behavior;
- an ADR for a new or changed architectural boundary; and
- `ROADMAP.md` when a planned milestone is completed, split, deferred, or superseded.
