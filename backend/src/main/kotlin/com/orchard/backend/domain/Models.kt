package com.orchard.backend.domain

data class Document(
    val id: String, 
    val path: String, 
    val content: String,
    val metadata: Map<String, String> = emptyMap()
)

data class Chunk(
    val id: String,
    val documentId: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val embedding: FloatArray? = null
)
