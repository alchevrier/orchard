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

The default catalog uses:

- endpoint `local-ollama`;
- model `phi3:mini`;
- strict JSON capability;
- deterministic temperature and seed settings; and
- `LOCAL_ONLY` policy.

Install and launch the default model with the setup and run scripts, or choose another installed Ollama model:

```bash
ORCHARD_MODEL=my-model ./setup_orchard.sh
ORCHARD_MODEL=my-model ./run_orchard.sh
```

Then make sure the durable model binding in Orchard's execution settings names the same model and declares enough context for its assigned profile.

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
| `bounded-definition-reasoning-v1` | Work-definition proposals | 12K / 2K |
| `bounded-circuit-synthesis-v1` | Staged delivery circuit proposals | 12K / 3K |
| `bounded-coding-patch-v1` | Typed coding operations | 24K / 8K |
| `broad-repository-analysis-v1` | Repository plans, standards scans, resolution reasoning | 88K / 8K |
| `bounded-independent-audit-v1` | Architecture and quality audit | 64K / 4K |

A binding must expose strict JSON and enough total context for the selected profile. Per-profile input/output overrides are durable configuration, but incompatible reductions can prevent routing or validation.

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
