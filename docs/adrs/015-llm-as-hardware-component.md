# ADR 015: The LLM as a Native Hardware Component (FSM over Ring Buffers)

## Status
Accepted

## Context
Traditional "Agentic" tools treat LLMs as remote cloud REST endpoints. This forces the application to deal with massive temporal uncertainty (network jitter, unpredictable latency, stateless JSON parsing overhead) and necessitates asynchronous, reactive programming architectures.

Because Orchard runs *locally* via the Autumn OS, we can completely invert this relationship.

## Decision
We treat the local LLM (Ollama daemon) not as a web service, but as a **computable hardware component** attached via a native ring buffer boundary.

1. **Statefulness via L1/L2 Pinning**: Instead of pushing the entire JSON chat history over the WAN for every single FSM phase transition (Triage -> Retrieval -> Planning), the local KV cache is maintained in VRAM. Autumn simply shifts index pointers targeting memory blocks over to the Ollama pipeline.
2. **Deterministic Polling over Callbacks**: Because the LLM sits locally, we do not require reactive async/await UI threads to handle network callbacks. The Autumn kernel natively polls the `ollamaClientGateway` egress ring buffer on a defined clock cycle.
3. **Synchronous FSM Paging**: The `IntentSynthesizerState` FSM can aggressively page the LLM. If the LLM generates an invalid struct (e.g. failing the Phase 3 schema check), the kernel can instantly push the error string offset into the ingress ring buffer, rerunning the generation cycle almost instantaneously without internet latency delays.

## Consequences
- The LLM ceases to be an external API and functionally acts as an Arithmetic Logic Unit (ALU) within the Orchard Kernel.
- Allows for extremely tight, multi-shot reasoning loops (Triage -> Context -> Plan -> Validate -> Fix) that would be prohibitively slow and expensive over cloud APIs.
- **Enables Small Model Dominance:** Because the execution path is bounded into deterministic FSM loops with immediate self-correction budgets, we do not need massive, slow 70B parameter models (like GPT-4o-size local models). We can run lightweight, quantized 7B-8B parameter models (e.g., LLaMA-3 8B, Qwen) which execute lightning-fast natively, relying on the Autumn compiler's rigid mechanical constraints to overcome the model's inherent probabilistic limitations.
- Justifies the entire zero-allocation `@Pipelined` architecture, as the data never has to be boxed onto the standard JVM heap to cross a WAN boundary.