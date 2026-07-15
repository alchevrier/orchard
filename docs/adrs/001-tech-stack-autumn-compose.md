# ADR 001: Technology Stack - Autumn and Compose Multiplatform

## Status
Superseded by [ADR 019](019-application-runtime-and-integration-stack.md)

## Context
Orchard is designed as a local-first agentic operating system for governed software workflows. It requires a high-performance, low-latency background server capable of running complex RAG pipelines, and a unified desktop/mobile UI that allows users to interact with their delivery pipelines locally and remotely.

## Decision
We will use:
1. **Backend**: [Autumn](https://github.com/autumn) framework using Kotlin. It provides zero-allocation pipelines, `@AutumnApplication` annotations, and high-speed routing suitable for local agent serving and memory compilation workloads.
2. **Frontend**: Compose Multiplatform. This ensures a native-feeling UI across Desktop and Mobile, allowing seamless cross-platform deployment while keeping the primary tech stack unified around Kotlin development.

## Consequences
- The development ecosystem will lean heavily on JVM/Kotlin toolchains.
- We have the capacity for extremely high-performance data processing pipelines on the local machine without web-backend overhead.
