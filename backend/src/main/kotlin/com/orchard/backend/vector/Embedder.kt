package com.orchard.backend.vector

interface Embedder {
    fun embed(text: String): FloatArray
    fun embedBatch(texts: List<String>): List<FloatArray>
}
