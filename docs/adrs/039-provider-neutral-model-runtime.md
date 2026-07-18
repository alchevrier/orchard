# ADR 039: Provider-Neutral Model Runtime

## Status

Accepted

## Context

Orchard's model roles and execution profiles were already independent of a specific model name, but production constructed one hard-coded Ollama client. That prevented users from assigning the broad analysis role and bounded coding role to different local models, using an OpenAI-compatible local server such as LM Studio, or explicitly allowing a cloud endpoint.

Provider flexibility must not weaken Orchard's authority boundaries. Endpoint configuration is replaceable operational state, model output remains untrusted candidate data, local-only policy must be enforced by the backend, and credentials must never enter Orchard persistence, model provenance, logs, or cockpit responses.

## Decision

Orchard resolves model execution through:

```text
role -> execution profile -> model binding -> provider adapter -> endpoint
```

A checksummed, atomically replaced provider catalog stores:

- endpoint identity, protocol, base URL, locality, and enabled state;
- model binding, context capacity, strict-JSON capability, resource demand, and non-secret generation configuration; and
- one server-enforced provider policy.

The initial protocols are native Ollama and OpenAI-compatible REST. OpenAI-compatible endpoints cover local servers such as LM Studio and remote APIs that implement chat completions and model discovery.

The registry exposes a live provider view to all long-running services. Catalog replacement builds adapters before persistence, atomically saves the catalog, publishes the new provider set, and then closes old clients. Architect and genesis calls use the registry as a single-provider compatibility facade while role-aware services resolve across all current bindings.

### Locality Policy

`LOCAL_ONLY` rejects enabled remote endpoints during catalog admission.

`LOCAL_PREFERRED` and `CLOUD_ESCALATION_ONLY` exclude remote bindings whenever a compatible local binding exists for the requested execution profile. A remote binding becomes eligible only when no local binding satisfies the role's context and capability contract.

`CLOUD_ALLOWED` permits local and remote bindings to participate in evidence-based model resolution.

Policy filtering occurs before quality and latency evidence ranking, so historical remote performance cannot override a user's locality constraint.

### Credential Boundary

The catalog accepts only credential references of the form `env:NAME`. It never accepts or stores credential values. The environment resolver reads the referenced variable only while constructing an outbound request and adds it as a bearer token. Local endpoints cannot declare credentials.

### HTTP and Cockpit Surface

The workspace API exposes catalog retrieval, atomic replacement, and endpoint inspection. Inspection uses Ollama model tags or the OpenAI-compatible models endpoint and returns bounded reachability diagnostics plus discovered model names.

The execution settings dialog configures the active endpoint and binding, including policy, protocol, locality, URL, model, context capacity, local resource demand, and optional environment reference. Endpoint inspection is explicit and independent from saving, so temporary endpoint downtime cannot prevent configuration.

## Consequences

- A default install still boots local-first with Ollama on `127.0.0.1:11434` and `phi3:mini`.
- Users can switch to LM Studio or another compatible local server without code changes.
- Broad analysis and bounded coding can use different bindings while preserving existing execution-profile admission.
- Cloud use is explicit, policy constrained, and does not place API keys in Orchard authority.
- Provider changes become visible to running role-aware services without restarting Orchard.
- Protocol adapters remain responsible only for transport; deterministic validation remains the authority for generated plans, definitions, and patches.

## Boundaries

- The initial cockpit edits one active endpoint and binding while the backend schema and APIs support multiple entries.
- OpenAI-compatible behavior is limited to chat completions, JSON object response mode, bearer authentication, token usage, and model discovery.
- Provider-specific OAuth, streaming, tool calling, image input, pricing, and rate-limit scheduling are outside this milestone.
- `CLOUD_ESCALATION_ONLY` currently means capability/context fallback, not retrying a remote model after a local generation or schema failure.
- Credential rotation and secret-manager integrations remain external to Orchard; environment variables are the only credential resolver.

## Alternatives Considered

### Keep one hard-coded Ollama client

Rejected because model role specialization and user-controlled hardware deployment require runtime binding configuration.

### Store API keys in the provider catalog

Rejected because the catalog is durable authority, appears in diagnostics, and may be backed up or inspected. Orchard needs references to external secret authority, not secret custody.

### Let the frontend enforce local-only behavior

Rejected because alternate clients and direct HTTP calls could bypass it. Locality policy must constrain backend admission and model resolution.

### Implement one adapter per vendor

Rejected for the initial runtime because Ollama native and OpenAI-compatible REST cover the required local and cloud deployment shapes with a small auditable surface. Vendor-specific adapters can be added when their semantics genuinely differ.
