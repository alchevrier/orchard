# ADR 007: Local LLM Backend - Ollama

## Status
Accepted

## Context
Orchard requires a local vector generation mechanism to build the memory DB without sending code to the cloud. We considered LM Studio and Ollama. LM Studio provides a GUI and an OpenAI-compatible REST server. Ollama operates as a background daemon and is highly scriptable.

## Decision
We will standardize on **Ollama** as the default embedding and generation engine for Orchard.
- Embeddings will default to using the `nomic-embed-text` (or `mxbai-embed-large`) model natively through Ollama's `/api/embeddings` endpoint.
- Code generation agents in the "Coding Center" will communicate with Ollama's `/api/generate` or `/api/chat` endpoints.

## Consequences
- Requires the user to have Ollama installed and running on port 11434 prior to booting Orchard.
- Requires downloading the required models (`ollama pull nomic-embed-text`) during the initial Architect workspace setup.
- The `OllamaEmbedder` operates within the Autumn Cold Channel to prevent HTTP latency from stalling the primary pipeline rings.