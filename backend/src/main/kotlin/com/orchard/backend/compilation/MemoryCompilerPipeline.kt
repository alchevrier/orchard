package com.orchard.backend.compilation

import com.orchard.backend.domain.Chunk
import com.orchard.backend.domain.Document
import com.orchard.backend.vector.Embedder
import com.orchard.backend.vector.SemanticChunker
import com.orchard.backend.vector.VectorStore
import com.orchard.backend.vector.DocumentClassifier
import java.util.UUID

/**
 * Executes in a background context (Cold Channel), allowing 
 * heavy LLM inference and DB writes without blocking Autumn's lock-free hot paths.
 * 
 * In a real implementation, SemanticChunker, Embedder, etc., would be accessed 
 * purely functionally or via object instances defined here statically since
 * Autumn does not have an IoC container.
 */
class MemoryCompilerPipeline(
    private val classifier: DocumentClassifier,
    private val chunker: SemanticChunker,
    private val embedder: Embedder,
    private val vectorStore: VectorStore
) {

    // This method is called from the cold channel consumption loop
    fun executeCompilation(path: String, content: String) {
        val documentId = UUID.randomUUID().toString()
        val metadata = classifier.classify(path, content)
        val document = Document(id = documentId, path = path, content = content, metadata = metadata)
        
        val stringChunks = chunker.chunk(content)
        val embeddings = embedder.embedBatch(stringChunks)
        
        val sequence = stringChunks.zip(embeddings).map { (text, vector) ->
            Chunk(
                id = UUID.randomUUID().toString(),
                documentId = documentId,
                content = text,
                metadata = metadata,
                embedding = vector
            )
        }
        
        vectorStore.upsertDocument(document, sequence)
        println("Compiled memory for path: $path on Cold Thread")
    }

    fun executeRemoval(path: String) {
        vectorStore.deleteDocument(path)
        println("Removed memory for path: $path on Cold Thread")
    }
}
