package com.orchard.backend.vector

import com.orchard.backend.domain.Chunk
import com.orchard.backend.domain.Document

interface VectorStore {
    fun upsertDocument(document: Document, chunks: List<Chunk>)
    fun deleteDocument(path: String)
    fun search(queryVector: FloatArray, topK: Int): List<Chunk>
}
