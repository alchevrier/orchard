# ADR 008: Workflow & Governance Data Structures

## Status
Accepted

## Context
Orchard requires a formal mechanism to enforce "governed, evidence-producing" workflows. Agents within the Coding Center cannot execute arbitrarily; they must be bound by rules defining *what* they are allowed to do, *which* agent profile handles the task, and *what evidence* must be produced to transition a ticket from Execution to Done.

## Decision
We define the **Workflow Intent** structure within the application memory. A Workflow is distinct from a basic Project/Ticket document:

1. **Topology Context**: Workflows define Finite State Machine (FSM) states that a ticket can transit through (e.g., `Design -> Code -> Quality Check -> Deploy`).
2. **Agent Assignment**: Workflow FSM nodes define the agent profile required (e.g., `role: typescript_specialist`, `role: infrastructure_engineer`).
3. **Success Criteria**: Defines mandatory conditions. (e.g., "Must achieve >85% unit test coverage," "Must provide a Playwright layout snapshot").

We model this within the Autumn `@Pipelined` architecture via a `WorkflowIntent` interface.

The first implemented workflow is the built-in **Default Delivery** workflow (`boundWorkflowIdHash = 1`). Its initial admission policy is structural and deterministic:

- A Project has no parent.
- An Epic must reference an existing Project.
- A Story must reference an existing Epic whose Project still exists.
- A Task or Bug must reference an existing Story whose Epic and Project still exist.
- If optional ancestor IDs are supplied, they must match the stored chain.
- No parent is inferred from the most recently created entity.

The local LLM may classify prose and propose a plan, but it cannot waive this policy or act as the workspace database. The workspace store is the current source of truth; persistent entity and relationship storage belongs beneath `~/.orchard/db`.

## Consequences
- Every Ticket created must bind to a specific Workflow ID hash.
- Invalid or missing hierarchy references are rejected before workspace mutation.
- The `CodingCenter` pipeline must reject tickets where the Success Criteria checks (simulated natively) are not passed.
- This creates a strict dependency: The Architect/Workflow Centers must define these structs before the Coding Center has permission to accept a ticket payload into its task queue.