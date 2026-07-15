# ADR 017: Zero-Allocation JSON Traversal

## Context
When Ollama responds to the `ArchitectAgentGateway` with intent classifications or planning constraints, the output is a raw string/byte buffer payload formatted as JSON.

Because Orchard relies on the Autumn Framework, any process residing on a `@HotPath` or interacting with a ring buffer must avoid generating Garbage Collection overhead (no `String` allocations, no `ObjectMapper`, no `Gson` tree models). Standard Kotlin libraries (`kotlinx.serialization` or `Gson`) allocate thousands of strings to build traversable Node trees, which violates the strict FSM cycle budget.

## Decision
We will implement a custom `ZeroAllocaJsonTraverser.kt`.
This object will natively traverse `ByteBuffer` references (the raw HTTP response from Ollama) and extract numeric values directly into primitive arrays (or integers).

For example, when Triage finishes and returns `{"classificationTypeIds": [101, 103]}`, the C-style cursor loop will scan the buffer for the memory offset of `"classificationTypeIds"`, iterate forward pushing the digits into an accumulator, and inject `101` and `103` into a pre-allocated `IntArray`.

Zero strings are instantiated.

## Consequences
- **Rigidity**: If the LLM generates slightly malformed JSON (e.g. dropping a quote or throwing in markdown blocks), the primitive cursor could scan past the bounds.
- **Safety**: The traverser requires hard length bounds to prevent `IndexOutOfBoundsException` issues in the buffer.
- **Enforcement**: This validates the prior decision to strip markdown delimiters (````json`) from the LLM prompt instructions, as standardizing the payload prefix makes primitive string matching trivial.

## Alignment with Local OS Paradigm
By dodging traditional JVM deserialization overhead, the FSM intent translation happens in nanoseconds at a near O(N) scan complexity, ensuring that the background OS loop never bogs down the primary hardware threads or starves the UI thread IPC.
