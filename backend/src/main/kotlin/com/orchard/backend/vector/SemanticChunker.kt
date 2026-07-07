package com.orchard.backend.vector

interface SemanticChunker {
    fun chunk(content: String): List<String>
}
