# ADR 004: Evidence-Producing Workflows and Governance

## Status
Accepted

## Context
AI agents are prone to hallucinating code completion or claiming a task is done without verifying it works. Orchard’s philosophy is "governed, evidence-producing workflows" rather than simple code generation.

## Decision
Integrate a mandatory Testing & Benchmarking phase (the Quality Center) for agent execution:
- Agents must output quantifiable evidence of success for completed tickets.
- The UI will include a dedicated Dashboard / Coverage Tab visualizing unit/integration test coverage, LOC covered.
- Performance must be tracked with benchmarks showing percentiles and hard performance numbers.
- Tickets cannot proceed from "Execution" to "Done" unless the defined criteria/evidence thresholds are met and verifiable by the system.

## Consequences
- Workflow design must include explicit "Success Criteria" handling.
- Agents need sandboxed execution environments or CI hooks to run Playwright, unit tests, and profilers locally to generate the required evidence.
