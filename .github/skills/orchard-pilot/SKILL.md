---
name: orchard-pilot
description: 'Pilot, monitor, operate, or troubleshoot Orchard through its deterministic AI operator API. Use when asked to run an Orchard workflow, inspect what Orchard is doing, diagnose a blocked or running model, choose the next authorized action, resume self-hosting, or avoid costly raw-code investigation.'
argument-hint: '[preflight|status|atlas] [domain]'
user-invocable: true
---

# Orchard Pilot

Use Orchard as the workflow authority. Do not reconstruct its state machine from Kotlin code when the pilot API can answer the question.

## Governing Rule

Software executes known workflows. Models resolve bounded uncertainty. Orchard validates model output before changing authority.

The pilot reads compact authority state; Orchard owns truth, admission, evidence, retries, completion, and stop conditions.

## Procedure

1. Locate or start exactly one Orchard instance.

   First check the intended base URL. Do not infer service health from terminal names or process presence.

   For the normal local stack, prefer:

   ```bash
   ./run_orchard.sh
   ```

   This launcher checks or starts Ollama, verifies the recommended models, and then starts Orchard. Use `./run_orchard.sh --skip-ollama` only when LM Studio or a remote provider is already running and configured.

   For a headless or isolated backend, direct `:backend:jvmRun` is allowed, but it does **not** start Ollama, LM Studio, or any remote provider. Start and verify the configured provider separately before executing model-backed actions.

2. Run the deterministic preflight:

   ```bash
   python3 .github/skills/orchard-pilot/scripts/pilot.py preflight
   ```

   Preflight reads Orchard status, provider catalog, and provider inspection. Do not execute an action whose `costClass` starts with `MODEL` unless preflight reports `ready: true` and at least one configured provider is reachable.

3. Run the compact status probe:

   ```bash
   python3 .github/skills/orchard-pilot/scripts/pilot.py status
   ```

   Set `ORCHARD_BASE_URL` or pass `--base-url` when Orchard is not on `http://127.0.0.1:8085`.

4. Read only these status fields first:

   - `state`
   - `activeObjective`
   - `currentRun`
   - `currentOperation`
   - `model.latestPhase`
   - `evidence.missing`
   - `resources`
   - `authorizedActions`
   - `disallowedActions`

5. Follow the deterministic state:

   - `IDLE`: report that Orchard has no active run. Do not invent work.
   - `RUNNING`: inspect provider progress. Do not start a duplicate tick or model call.
   - `ACTION_REQUIRED`: choose only from `authorizedActions`.
   - `BLOCKED`: report the exact diagnostic and authority needed to continue.
   - `READY`: execute the lowest-cost authorized action when the user requested continued operation.
   - terminal state: report evidence and stop.

6. If the legal transition is unclear, query only the relevant atlas domain:

   ```bash
   python3 .github/skills/orchard-pilot/scripts/pilot.py atlas REPOSITORY_ANALYSIS_ATTEMPT
   ```

7. Before any mutation:

   - confirm the action appears in `authorizedActions`;
   - if its `costClass` starts with `MODEL`, confirm provider preflight passed immediately before execution;
   - use its exact HTTP method and path;
   - explain the authority or evidence basis briefly;
   - never execute an action listed in `disallowedActions`;
   - never derive a hidden mutation route from source code.

8. After one mutation, query status once again. Do not poll repeatedly. If the new state is `RUNNING`, report ownership and provider phase, then wait for a new user turn or an external completion notification.

## Provider Startup

### Ollama

Check the service, not the process list:

```bash
curl --silent --fail http://127.0.0.1:11434/api/tags
```

If unavailable, start `ollama serve` as a long-running background process, wait for `/api/tags`, and verify the configured model is installed. Do not assume direct Gradle startup launches Ollama.

### LM Studio or Remote Provider

Do not start Ollama. Ensure the external server is already running, start Orchard with `--skip-ollama` when using the normal launcher, and require `/api/model-providers/inspection` to report the configured endpoint as reachable.

### Unknown Provider

Start Orchard without executing model work, read `/api/model-providers`, inspect the configured protocol and endpoint, then follow the matching provider procedure. If reachability cannot be established, report provider unavailability and stop before inference.

## Cost Discipline

- Prefer the pilot status over `/api/workspace`, raw ledgers, terminal logs, or broad repository searches.
- Prefer the current atlas slice over reading service implementation.
- Never invoke another model merely to explain an active model call.
- Never execute model-backed work when provider preflight is unavailable or failed.
- Do not dump the complete atlas into chat unless explicitly requested.
- Do not repeat an unchanged broad analysis after deterministic rejection.
- Treat provider phases and prompt hashes as evidence, not prompt or response content.
- Use deterministic tools for Git, builds, tests, parsing, and state transitions. Invoke a model only at an admitted uncertainty boundary.

## Escalation Order

Use this order when the pilot response is insufficient:

1. `GET /api/pilot/status`
2. relevant domain from `GET /api/pilot/state-atlas`
3. exact endpoint referenced by the current authorized action
4. one focused raw authority projection
5. source-code inspection only when the map and projection demonstrably disagree or omit required state

If implementation behavior conflicts with the atlas, report an Orchard product defect. Do not silently reinterpret the transition.

## Failure Handling

- API unreachable: verify the configured base URL and whether Orchard is running. Do not start a second instance on an occupied port.
- Provider unreachable: start the configured provider or ask the user to start the external provider; do not consume retry authority.
- Direct `jvmRun`: treat provider startup as a separate mandatory prerequisite.
- `RUNNING` without provider phases: report missing telemetry as the blocker.
- Provider completed but operation blocked: use the deterministic rejection diagnostic, not provider duration, as the cause.
- Retry authorized: retry once through the exact advertised action; do not create additional retry authority.
- Recurrent equivalent failure: stop and surface stop authority rather than continuing inference.

## Output

Keep operator reports compact:

```text
Objective: <id/title/state>
Run: <id/work item/workflow state>
Operation: <type/state/diagnostic>
Model: <model/profile/latest phase/tokens>
Evidence missing: <kinds>
Next authorized action: <method path — reason>
Do not: <disallowed action — reason>
```
