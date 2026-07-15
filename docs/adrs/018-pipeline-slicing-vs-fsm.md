# ADR 018: Pipeline Slicing (Circuit Flow) vs Monolithic FSM

## Context
Initially, the `ArchitectAgentGateway` was modeled as a single monolithic FSM queue (`intentSynthesizerQueue`) containing a `when(phase)` switch statement. The idea was that as boundary devices (Network, Filesystem) responded, they would push the index back into the same monolithic queue to evaluate the next phase.

However, in a true strict Zero-Allocation / Dataflow architecture (like DPDK or Autumn), data flows in a single direction over distinct physical ring buffers (Boundary -> Handler -> Boundary). A monolithic state machine loop creates a conceptual bottleneck and violates the "Circuit-Based Programming" model. Once data is handed off to an egress boundary, the original loop does not pause or suspend; the flow is entirely gone from that component.

## Decision
We will eliminate the monolithic `intentSynthesizerQueue` and replace it with a continuous pipeline of bounded `@HotPath` event handlers that act as "wires" connecting the gateways.

The structural flow becomes:
1. `onArchitectChatReceived` -> dispatches to Ollama network boundary.
2. `onOllamaTriageResponse` -> traverses the JSON and dispatches to VectorWal boundary.
3. `onVectorWalResponse` -> formats context and dispatches to Ollama network boundary (Planning).
4. `onOllamaPlanningResponse` -> validates and loops back to Vector WAL (as Ticket saves).

## Consequences
- **Memory Footprint**: State cannot be held in a single suspended `struct`. State must either be encoded in the boundary payloads themselves (via offset pointers) or stored in an out-of-band correlation array indexed by a session hash.
- **Complexity**: It forces us to build actual state-tracking flyweight arrays independent of the queues, because the queue payloads act purely as signals/events.
- **Purity**: This maps 1:1 with hardware FPGA pathways and DPDK core routing. It makes the system purely reactive and deterministic.
