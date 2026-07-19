# Getting Started

## Prerequisites

The supported setup scripts target Linux and macOS.

Required for every installation:

- Git on `PATH`.
- `curl` for launcher readiness checks.
- `zip` and `unzip` for SDKMAN.
- A Compose Desktop-compatible graphical environment.

`./setup_orchard.sh` preserves an existing JDK 21+ or installs the current stable Java through SDKMAN without modifying shell startup files. Set `ORCHARD_JAVA_CANDIDATE` to an exact SDKMAN Java identifier when a specific distribution or patch version is required.

For the default local model stack, install Ollama. Orchard selects and installs a model set from physical or unified memory; Ollama is optional when you intend to configure LM Studio or another OpenAI-compatible provider.

## Install the Default Local Stack

From the repository root:

```bash
./setup_orchard.sh
```

The script:

- installs or verifies Git, `curl`, `zip`, and `unzip`;
- installs JDK 21+ through SDKMAN when no compatible Java is already available;
- installs Linux desktop runtime libraries where supported;
- detects classic PC or Apple silicon memory capacity;
- installs Ollama and downloads the recommended general and coding models;
- downloads Gradle dependencies; and
- runs the complete build and test suite.

It uses Homebrew on macOS and `apt`, `dnf`, `pacman`, or `zypper` on Linux only for native prerequisites; Java remains SDKMAN-managed. The script is safe to rerun.

Inspect prerequisites without changing the machine:

```bash
./setup_orchard.sh --check
```

Install only the application toolchain when using a different provider:

```bash
./setup_orchard.sh --skip-ollama
```

## Launch Orchard

Start the default stack and desktop application:

```bash
./run_orchard.sh
```

The launcher reuses an existing Ollama or Orchard backend, starts missing services, waits for readiness, opens the desktop, and stops only processes it started.

When using LM Studio or a configured remote provider:

```bash
./run_orchard.sh --skip-ollama
```

Equivalent environment controls are:

```bash
ORCHARD_SKIP_OLLAMA=1 ./run_orchard.sh
ORCHARD_MODELS=qwen3:8b,qwen2.5-coder:7b ./run_orchard.sh
```

`ORCHARD_MODELS` changes the models installed or checked by the scripts. The legacy single-model `ORCHARD_MODEL` variable remains supported. Script overrides do not replace Orchard's durable provider catalog by themselves; select the corresponding preset or customize bindings in execution settings.

On first launch, Orchard applies the detected hardware preset before constructing the model runtime. An untouched legacy `phi3:mini` catalog is migrated the same way; customized catalogs or profile apertures are preserved. Open execution settings to inspect the local endpoint or independently customize any workload stage.

## First Project

On a fresh workspace:

1. Create a project in the guided desktop flow.
2. Select whether it is greenfield-local or existing-local.
3. For an existing product, bind its local Git repository.
4. Complete the experience contract.
5. Select the first Epic that proves the product experience.
6. Define architecture and the repository blueprint.
7. Review the complete genesis revision.
8. Select **Admit product genesis**.
9. Start the local company circuit from the READY phase.

Admission makes the exact experience, architecture, first Epic, and blueprint the implementation authority. A model-generated proposal remains editable candidate data until you apply it, and the resulting genesis revision remains non-executable until admission.

## Services

The backend process starts two loopback-only HTTP servers:

| Service | Address | Purpose |
| --- | --- | --- |
| Workspace API | `127.0.0.1:8085` | Workspace, company, delivery, models, standards, campaigns, and resources |
| Architect API | `127.0.0.1:8086` | Architect conversation |

The desktop client connects to both automatically.

## Next Steps

- Configure a non-default model in [Models and Resources](models-and-resources.md).
- Learn the complete lifecycle in [Product Workflow](product-workflow.md).
- If startup fails, use [Troubleshooting and Data](troubleshooting.md).
