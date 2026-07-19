# Models and Resources

Orchard routes model work by execution profile, capability, context capacity, provider policy, and observed evidence. A model does not choose its own role or provider.

## Supported Provider Protocols

| Protocol | Typical use | Base URL example |
| --- | --- | --- |
| `OLLAMA_NATIVE` | Local Ollama | `http://127.0.0.1:11434` |
| `OPENAI_COMPATIBLE` | LM Studio, compatible local servers, explicitly allowed remote services | `http://127.0.0.1:1234` |

Local endpoints must use a loopback host. Remote endpoints must be explicitly labeled remote and permitted by provider policy.

## Provider Policies

| Policy | Behavior |
| --- | --- |
| `LOCAL_ONLY` | Every enabled endpoint must be local. This is the default. |
| `LOCAL_PREFERRED` | Compatible local bindings are selected when available. |
| `CLOUD_ALLOWED` | Compatible local or remote bindings may be selected. |
| `CLOUD_ESCALATION_ONLY` | Local bindings are preferred; remote bindings are capability/context fallback. |

Cloud escalation is not an automatic retry after arbitrary local generation failure. Provider selection happens before execution from compatible bindings.

## Configure Ollama

On first launch, Orchard applies a complete `LOCAL_ONLY` catalog and all six workload assignments from the machine's memory before constructing the model runtime. An untouched legacy catalog is migrated automatically, while any customized catalog or profile aperture is preserved. Execution settings show the exact `ollama pull` commands and allow deliberate replacement afterward.

| Hardware tier | General, analysis, and audit | Coding | Notes |
| --- | --- | --- | --- |
| Classic PC or Apple silicon, 8-15 GiB | `qwen3:4b` | `qwen2.5-coder:3b` | Survival apertures for entry systems, including an 8 GiB MacBook Neo or Air. |
| Classic PC or Apple silicon, 16-31 GiB | `qwen3:8b` | `qwen2.5-coder:7b` | Balanced MacBook Air/Neo and ordinary-PC default. |
| Classic PC or Apple silicon, 32-63 GiB | `qwen3:14b` | `qwen2.5-coder:14b` | Role-specific capable default. |
| Classic PC or Apple silicon, 64-95 GiB | `qwen3-coder:30b` | `qwen3-coder:30b` | Full default stage apertures and long-context repository work. |
| Apple silicon, 96-127 GiB | `gpt-oss:120b` | `gpt-oss:120b` | Guarded context apertures retain operating-system headroom. |
| Apple silicon, 128+ GiB | `gpt-oss:120b` | `gpt-oss:120b` | Expanded analysis and audit context. |

Classic PC auto-detection deliberately uses total system memory and stops at the conservative 64 GiB preset because portable GPU/VRAM telemetry is not yet available. A PC with a dedicated accelerator can enter its exact model context and resource demand manually after applying the nearest safe preset.

Every generated catalog uses:

- endpoint `local-ollama`;
- strict JSON capability;
- deterministic temperature and seed settings; and
- `LOCAL_ONLY` policy.

Install and launch the recommended models with the setup and run scripts, or override the model set explicitly:

```bash
./setup_orchard.sh
ORCHARD_MODELS=model-a,model-b ./setup_orchard.sh
ORCHARD_MODELS=model-a,model-b ./run_orchard.sh
```

`ORCHARD_MEMORY_BYTES` and `ORCHARD_PLATFORM=APPLE_SILICON|CLASSIC_PC` are diagnostic overrides for scripted deployments. Normal desktop installations should rely on detection. After a script-level model override, make sure the durable bindings name the same models and declare enough context and resident memory for their assigned profiles.

## Configure LM Studio or Another Local Compatible Server

1. Start the server on a loopback address.
2. Launch Orchard with `./run_orchard.sh --skip-ollama`.
3. Open execution settings in the desktop.
4. Choose the OpenAI-compatible protocol.
5. Set locality to local and use the server's loopback base URL.
6. Add a binding with its exact model identifier, context capacity, strict JSON capability, and resource demand.
7. Inspect the endpoint before relying on it for work.

A local endpoint cannot declare credentials.

## Configure an Explicit Remote Endpoint

1. Put the secret in an environment variable available to the Orchard backend.
2. Reference it as `env:VARIABLE_NAME`; never enter the secret value into Orchard configuration.
3. Set endpoint locality to remote.
4. Select `CLOUD_ALLOWED` or `CLOUD_ESCALATION_ONLY` according to your intended policy.
5. Save and inspect the endpoint.

Credential references must match `env:[A-Z][A-Z0-9_]+`. Orchard rejects token-, key-, or secret-like values in model binding configuration and resolves the environment variable only at request time.

## Execution Profiles

Orchard currently separates reasoning by bounded profiles:

| Profile | Purpose | Input / output budget |
| --- | --- | --- |
| `bounded-conversation-conductor-v1` | Conversational intent and capability routing | 48K / 4K |
| `bounded-definition-reasoning-v1` | Work-definition proposals | 12K / 2K |
| `bounded-circuit-synthesis-v1` | Staged delivery circuit proposals | 12K / 3K |
| `bounded-coding-patch-v1` | Typed coding operations | 24K / 8K |
| `broad-repository-analysis-v1` | Repository plans, standards scans, resolution reasoning | 88K / 8K |
| `bounded-independent-audit-v1` | Architecture and quality audit | 64K / 4K |

A binding must expose strict JSON and enough total context for the selected profile. Per-profile input/output overrides are durable configuration, but incompatible reductions can prevent routing or validation.

Hardware presets resize these apertures when the default would exceed safe admission capacity. Startup installs the detected preset as one complete catalog/profile unit; applying a different preset later replaces all six assignments together. Selecting a workload stage in execution settings then permits deliberate per-stage customization.

## Machine Resource Policy

The machine resource controller admits model execution before inference. The configurable policy includes:

- capacity percentage, from 1 to 100;
- minimum free-memory reserve, default 1 GiB; and
- maximum concurrent model executions, default 1.

A request may be blocked when telemetry is unavailable, concurrency is exhausted, configured capacity is exceeded, or live CPU/memory cannot satisfy the binding's declared demand. A blocked request does not silently ignore the policy; retry after capacity changes or adjust the policy deliberately.

## Troubleshooting Provider Routing

- **No compatible model**: verify strict JSON capability and context capacity.
- **Endpoint inspection fails**: verify protocol, base URL, server readiness, and exact model name.
- **Remote endpoint rejected**: change provider policy from `LOCAL_ONLY` only when remote execution is intended.
- **Credential unavailable**: export the referenced environment variable before starting the backend.
- **Resource blocked**: inspect machine policy, declared resident memory/CPU, free memory, and active executions.
- **Launcher still checks Ollama**: use `--skip-ollama` or `ORCHARD_SKIP_OLLAMA=1`.

See [Troubleshooting and Data](troubleshooting.md) for service-level diagnostics.
