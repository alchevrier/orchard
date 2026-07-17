# ADR 037: Declarative Toolchain Policy Packs

## Status

Accepted

## Context

ADR 036 introduced an autonomous coding worker with a compiled registry for Gradle, Maven, Cargo, Meson, CMake, and Node verification. The registry mixed three concerns inside the trusted Kotlin binary:

- repository toolchain detection;
- evidence-kind to command selection; and
- process-safety enforcement.

Every new ecosystem therefore required an Orchard source change, rebuild, and release even when the extension only needed to declare a detector and bounded command arguments. The same pattern exists in other Orchard catalogs, including stage workflows, delivery evidence, model execution profiles, prompts, and classification vocabularies.

Moving all behavior into configuration would be unsafe. Repository confinement, process invocation, authority separation, evidence admission, and absolute resource bounds are security and correctness invariants. They must not become optional policy supplied by a community pack.

## Decision

Orchard separates declarative toolchain policy from compiled execution enforcement.

A toolchain policy pack is strict JSON with:

- schema version;
- stable pack ID and positive pack version;
- one or more stable profiles;
- deterministic profile priority;
- `allFiles` and `anyFiles` repository-relative detectors; and
- evidence-kind to typed executable-and-argument commands.

The production catalog combines Orchard's built-in default pack with local external packs found beneath:

```text
~/.orchard/policy-packs/toolchains/*.json
```

The directory is read on every policy resolution. A pack added or changed on disk can therefore govern a future worker claim without restarting or rebuilding Orchard.

Pack publishers should write a sibling temporary file, force its content, and atomically rename it to the final `.json` path. If Orchard observes a transient read or decode failure during publication, the worker records `DEFERRED` and retries after its cooldown. A successfully read catalog with no matching profile records terminal `BLOCKED` for that workflow run.

### Example

```json
{
  "schemaVersion": 1,
  "packId": "community.bazel",
  "packVersion": 1,
  "profiles": [
    {
      "id": "bazel-workspace",
      "priority": 500,
      "anyFiles": ["MODULE.bazel", "WORKSPACE.bazel"],
      "commands": {
        "BUILD": {
          "executable": "bazel",
          "arguments": ["build", "//..."]
        },
        "TEST": {
          "executable": "bazel",
          "arguments": ["test", "//..."]
        }
      }
    }
  ]
}
```

Pack and profile IDs are globally stable authority names. Matching profiles are ordered by descending priority, external-before-built-in source, then pack ID and profile ID. Duplicate pack IDs fail closed. This permits an explicitly installed local pack to take precedence over a built-in default at equal priority without replacing or mutating the built-in pack.

### Admission

Before a pack participates in resolution, Orchard validates:

- exact supported schema version;
- bounded policy-directory entries, pack-file count, and pack-file bytes before decoding;
- bounded pack, profile, detector, command, argument, and string counts;
- stable ID syntax and unique profile IDs;
- repository-relative detector paths without traversal or reserved metadata roots;
- uppercase evidence kinds;
- shell-free executable names or bounded repository-relative executable paths; and
- typed argument vectors without null or line terminators.

Unknown JSON fields, malformed files, duplicate identities, and invalid declarations fail resolution. Symbolic-link pack files are ignored. Detector files and repository-local executables are accepted only as regular non-symbolic-link files.

A local external pack is user-installed executable policy. It is not downloaded or activated automatically. Future Git policy sources require explicit source allowlisting, revision pinning, and signature policy before they can enter this local directory through Orchard.

### Immutable Execution Binding

The worker resolves one toolchain policy before model inference. Its durable claim pins:

- pack ID;
- pack version;
- profile ID; and
- SHA-256 policy hash.

The hash covers schema version, pack identity, profile priority, detectors, and sorted typed commands. A pack edit can affect a later claim but cannot alter the command authority already selected for an in-flight or historical execution.

Commands admitted directly by an acceptance contract still take precedence. Version 1 accepts only canonical whitespace-separated command strings without quoting, escaping, or shell operators, parses them into typed arguments, and preserves the exact admitted string in evidence. Otherwise the pinned toolchain profile supplies the command for the required evidence kind. `REGRESSION_TEST` and Work Definition `ACCEPTANCE` use the profile's `TEST` command unless an exact admitted verification is present.

If no valid profile matches a dispatched repository, the worker records a terminal `BLOCKED` execution for that run rather than retrying indefinitely. A later workflow run can use a newly installed policy pack.

### Compiled Enforcement Boundary

Policy packs may select detectors and command arguments. They cannot change:

- reserved worktree confinement;
- clean-worktree admission;
- symbolic-link and metadata protections;
- `ProcessBuilder` argument-vector execution;
- reduced environment construction;
- command timeout and output bounds;
- process-tree termination;
- source-diff canonicalization;
- evidence-contract membership;
- exact automated-criterion verification;
- human-judgment authority;
- completion computation; or
- replay validation.

These remain compiled Kotlin invariants.

## Consequences

- Community support for a new build ecosystem can be distributed as data rather than an Orchard binary change.
- Future worker claims can adopt a newly installed pack immediately; historical claims retain exact policy identity.
- The hard-coded build-system command branch and executable allowlist disappear from the coding gateway.
- Commands are structured data rather than shell text, reducing parsing and injection surface.
- Orchard's trusted core becomes an interpreter and verifier for a bounded extension vocabulary.
- The pack pattern provides a concrete foundation for later stage-workflow, delivery-workflow, model-profile, prompt-registry, and classifier packs without requiring one unrestricted plugin API.

## Boundaries

- Toolchain packs are the only runtime-extensible policy type in this decision.
- Local installation is the trust decision. Orchard does not yet fetch, update, sign, rank, or publish community packs.
- Pack removal or corruption can block future worker attempts but does not invalidate historical claim records.
- Worker replay accepts the legacy pre-ADR-037 claim hash and completed records that predate toolchain authority fields. New successful claims always pin policy identity.
- Global executables are resolved through Orchard's reduced `PATH`; repository-local executables must be executable regular files inside the reserved worktree.
- Typed argv prevents shell expansion but does not provide an OS-level filesystem or network sandbox. Installed packs and repository build scripts remain trusted local execution policy.
- Verification output is continuously drained into a fixed-size prefix; excess bytes are discarded and marked truncated without growing Orchard memory or an intermediate log file.
- Command timeouts and environment additions are not pack-configurable in version 1. They remain compiled safety policy.
- Packs are resolved per repository, not yet selected explicitly per Project through the UI.

## Alternatives Considered

### Keep adding toolchains to a Kotlin `when` block

Rejected because every declarative ecosystem addition would require rebuilding Orchard and expanding its trusted code surface.

### Load executable JVM plugins

Rejected because plugins would receive broad process authority and make confinement, compatibility, and provenance substantially harder to enforce.

### Store commands as shell strings

Rejected because quoting and shell expansion would become part of policy interpretation. Commands remain typed executable-and-argument vectors.

### Make all worker safety limits configurable

Rejected because community extensibility must not permit a pack to remove the boundaries that make execution governable.

### Resolve the latest pack again before each command

Rejected because a pack edit during an execution could change build and test authority between evidence gates. One resolved policy is pinned for the complete worker attempt.
