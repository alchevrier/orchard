package com.orchard.backend.vector

interface DocumentClassifier {
    /**
     * Recommends metadata classifications for a given document based on its path and content.
     * Example outputs might include keys like: "scope", "topic", "project_id"
     */
    fun classify(path: String, content: String): Map<String, String>
}
