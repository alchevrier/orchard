package com.orchard.backend.attention

import com.orchard.backend.agent.CodingRepositoryContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

@Serializable
enum class ContextQualityStatus { PASS, FAIL, UNKNOWN, NOT_ATTEMPTED }

@Serializable
data class ContextTransformation(
    val stage: String,
    val sourceBytes: Int,
    val sourcePaths: List<String>,
    val fits: Boolean,
    val adequate: Boolean,
    val diagnostic: String? = null,
)

@Serializable
data class ContextEvidenceReport(
    val reference: String,
    val path: String,
    val sourceHash: String,
    val excerptHash: String,
    val excerptBytes: Int,
    val originalLineRanges: List<String>,
    val role: String,
    val inclusionReason: String,
    val supportedScopes: List<String>,
    val required: Boolean,
    val declarationHashes: Set<String>,
)

@Serializable
data class ContextQualityReport(
    val formatVersion: Int = 1,
    val policy: String = "attention-context-quality-v1",
    val stage: String,
    val repositoryRevision: String,
    val frameHash: String,
    val validity: ContextQualityStatus,
    val relevance: ContextQualityStatus,
    val adequacy: ContextQualityStatus,
    val capacity: ContextQualityStatus,
    val downstream: ContextQualityStatus = ContextQualityStatus.NOT_ATTEMPTED,
    val accounting: com.orchard.backend.vector.ModelTokenAccounting?,
    val inputAllowanceTokens: Int,
    val evidence: List<ContextEvidenceReport>,
    val missingPaths: List<String>,
    val deferredPaths: List<String>,
    val unresolvedScopeIds: List<String>,
    val diagnostics: List<String>,
    val transformations: List<ContextTransformation>,
    val references: CanonicalAttentionProjection?,
)

fun compileContextQualityReport(
    frame: JsonObject,
    context: CodingRepositoryContext,
    requiredPaths: Set<String>,
    accounting: com.orchard.backend.vector.ModelTokenAccounting?,
    inputAllowanceTokens: Int,
    requiredAnchors: Map<String, Set<String>> = emptyMap(),
    transformations: List<ContextTransformation> = emptyList(),
): ContextQualityReport {
    val correlations = (frame["correlations"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    fun paths(correlation: JsonObject): Set<String> = listOf("evidencePaths", "ownerPaths").flatMap {
        (correlation[it] as? JsonArray).orEmpty().map { value -> value.jsonPrimitive.content }
    }.toSet() + (correlation["evidence"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.get("path")?.jsonPrimitive?.content }
    val available = context.files.mapTo(hashSetOf()) { it.path }
    val missing = (requiredPaths - available).sorted()
    val anchors = com.orchard.backend.agent.repositoryEvidenceAnchors(context, available)
    val diagnostics = listOfNotNull(
        if (context.files.isEmpty()) "SOURCE_EVIDENCE_EMPTY: no source was supplied." else null,
        missing.takeIf { it.isNotEmpty() }?.let { "SOURCE_EVIDENCE_MISSING: ${it.joinToString()}." },
        com.orchard.backend.agent.repositoryContextAdequacyDiagnostic(context),
        com.orchard.backend.agent.repositoryEvidenceRetentionDiagnostic(requiredAnchors, context),
    )
    val projection = runCatching { canonicalAttentionProjection(frame, context) }
    val orphanPaths = available - (correlations.flatMap { paths(it) }.toSet() + requiredPaths)
    val evidence = context.files.mapIndexed { index, file ->
        val supporters = correlations.filter { file.path in paths(it) }
        val scopes = supporters.mapNotNull { (it["scope"] as? JsonObject)?.get("id")?.jsonPrimitive?.content }
        ContextEvidenceReport(
            "source-${index + 1}", file.path, file.contentHash,
            com.orchard.backend.workspace.stagedPlanHash(file.content), file.content.encodeToByteArray().size,
            Regex("(?m)^// Orchard source lines (\\d+-\\d+)$").findAll(file.content).map { it.groupValues[1] }.toList().ifEmpty {
                if (com.orchard.backend.workspace.stagedPlanHash(file.content) == file.contentHash) listOf("1-${file.content.count { it == '\n' } + 1}") else emptyList()
            },
            supporters.mapNotNull { it["disposition"]?.jsonPrimitive?.content }.distinct().joinToString("|").ifBlank { "ACCEPTED_PREREQUISITE" },
            if (scopes.isEmpty()) "ACCEPTED_REQUIRED_PATH" else "ATTENTION_CORRELATION", scopes,
            file.path in requiredPaths, anchors[file.path].orEmpty(),
        )
    }
    return ContextQualityReport(
        stage = frame["workflowStepId"]?.jsonPrimitive?.content.orEmpty(),
        repositoryRevision = frame["repositoryRevision"]?.jsonPrimitive?.content.orEmpty(),
        frameHash = frame["hash"]?.jsonPrimitive?.content.orEmpty(),
        validity = if (projection.isSuccess && context.files.all { it.contentHash.matches(Regex("[0-9a-f]{64}")) }) ContextQualityStatus.PASS else ContextQualityStatus.FAIL,
        relevance = if (orphanPaths.isEmpty()) ContextQualityStatus.PASS else ContextQualityStatus.FAIL,
        adequacy = if (diagnostics.isEmpty()) ContextQualityStatus.PASS else ContextQualityStatus.FAIL,
        capacity = when {
            accounting == null || !accounting.capacityEstablished -> ContextQualityStatus.UNKNOWN
            accounting.totalTokens > inputAllowanceTokens -> ContextQualityStatus.FAIL
            else -> ContextQualityStatus.PASS
        },
        accounting = accounting, inputAllowanceTokens = inputAllowanceTokens, evidence = evidence, missingPaths = missing,
        deferredPaths = correlations.flatMap { (it["deferredPaths"] as? JsonArray).orEmpty().map { value -> value.jsonPrimitive.content } }.distinct(),
        unresolvedScopeIds = correlations.filter { it["unresolved"] == JsonPrimitive(true) }.mapNotNull { (it["scope"] as? JsonObject)?.get("id")?.jsonPrimitive?.content },
        diagnostics = diagnostics + orphanPaths.map { "SOURCE_EVIDENCE_UNRELATED: $it." } + listOfNotNull(projection.exceptionOrNull()?.message),
        transformations = transformations, references = projection.getOrNull(),
    )
}

fun contextQualityReportHash(report: ContextQualityReport): String = com.orchard.backend.workspace.stagedPlanHash(Json.encodeToString(report))

fun envelopeContextQualityReport(
    envelope: JsonObject,
    accounting: com.orchard.backend.vector.ModelTokenAccounting,
    inputAllowanceTokens: Int,
    downstream: ContextQualityStatus,
): ContextQualityReport? {
    val attention = envelope["attention"] as? JsonObject ?: return null
    val context = Json.decodeFromJsonElement(CodingRepositoryContext.serializer(), envelope.getValue("repositoryContext"))
    val projection = Json.decodeFromJsonElement(CanonicalAttentionProjection.serializer(), attention)
    val frame = resolveCanonicalAttention(projection, context)
    return compileContextQualityReport(frame, context, context.files.mapTo(hashSetOf()) { it.path }, accounting, inputAllowanceTokens,
        transformations = listOf(ContextTransformation("final-provider-serialization", context.files.sumOf { it.content.encodeToByteArray().size }, context.files.map { it.path },
            accounting.capacityEstablished && accounting.totalTokens <= inputAllowanceTokens, com.orchard.backend.agent.repositoryContextAdequacyDiagnostic(context) == null)))
        .copy(downstream = downstream)
}

@Serializable
data class CanonicalAttentionProjection(
    val formatVersion: Int = 1,
    val frame: JsonObject,
    val sources: Map<String, JsonObject>,
    val authorities: Map<String, JsonObject>,
    val hashes: Map<String, String>,
)

fun canonicalAttentionProjection(frame: JsonObject, context: CodingRepositoryContext): CanonicalAttentionProjection {
    require(context.files.map { it.path }.distinct().size == context.files.size) { "Ambiguous source identities" }
    val sourceIds = linkedMapOf<String, String>()
    val sources = linkedMapOf<String, JsonObject>()
    context.files.forEachIndexed { index, file ->
        val reference = "source-${index + 1}"
        sourceIds[file.path] = reference
        sources[reference] = JsonObject(mapOf("fileIndex" to JsonPrimitive(index)))
    }
    fun sourceReference(path: String): String = sourceIds.getOrPut(path) {
        val reference = "deferred-${sources.size + 1}"
        sources[reference] = JsonObject(mapOf("path" to JsonPrimitive(path)))
        reference
    }
    val authorities = linkedMapOf<String, JsonObject>()
    val authorityIds = linkedMapOf<JsonObject, String>()
    val hashes = linkedMapOf<String, String>()
    val pathFields = setOf("path", "ownerPaths", "ownershipPaths", "coordinatePaths", "evidencePaths", "deferredPaths")
    fun project(element: JsonElement, field: String = ""): JsonElement = when (element) {
        is JsonObject -> {
            if (setOf("kind", "id", "sourceHash", "text").all { it in element }) {
                val reference = authorityIds.getOrPut(element) {
                    val identifier = "authority-${authorityIds.size + 1}"
                    authorities[identifier] = JsonObject(element.mapValues { (key, value) -> project(value, key) })
                    identifier
                }
                JsonObject(mapOf("authorityRef" to JsonPrimitive(reference)))
            } else {
                val path = (element["path"] as? JsonPrimitive)?.contentOrNull
                val citationHash = (element["contentHash"] as? JsonPrimitive)?.contentOrNull
                val source = context.files.firstOrNull { it.path == path }
                require(source == null || citationHash == null || citationHash == source.contentHash) { "Stale citation source hash: $path" }
                JsonObject(element.mapValues { (key, value) -> project(value, key) })
            }
        }
        is JsonArray -> JsonArray(element.map { project(it, field) })
        is JsonPrimitive -> when {
            element.isString && field in pathFields -> JsonPrimitive(sourceReference(element.content))
            element.isString && (field.endsWith("Hash") || field == "hash") && element.content.isNotEmpty() -> {
                val sourceIndex = context.files.indexOfFirst { it.contentHash == element.content }
                if (sourceIndex >= 0) JsonObject(mapOf("sourceHashRef" to JsonPrimitive("source-${sourceIndex + 1}")))
                else {
                    val reference = hashes.entries.firstOrNull { it.value == element.content }?.key ?: "hash-${hashes.size + 1}"
                    hashes[reference] = element.content
                    JsonObject(mapOf("hashRef" to JsonPrimitive(reference)))
                }
            }
            else -> element
        }
    }
    val projected = project(frame) as JsonObject
    val result = CanonicalAttentionProjection(frame = projected, sources = sources, authorities = authorities, hashes = hashes)
    require(resolveCanonicalAttention(result, context) == frame) { "Canonical projection changed pinned frame authority" }
    return result
}

fun resolveCanonicalAttention(projection: CanonicalAttentionProjection, context: CodingRepositoryContext): JsonObject {
    require(projection.formatVersion == 1) { "Unsupported canonical projection" }
    val visiting = mutableSetOf<String>()
    fun sourcePath(reference: String): String {
        val source = requireNotNull(projection.sources[reference]) { "Missing source reference: $reference" }
        val index = (source["fileIndex"] as? JsonPrimitive)?.intOrNull
        require(source.size == 1) { "Ambiguous source reference: $reference" }
        return if (index != null) requireNotNull(context.files.getOrNull(index)) { "Source reference index out of bounds" }.path
        else requireNotNull((source["path"] as? JsonPrimitive)?.contentOrNull) { "Invalid deferred reference" }
    }
    val pathFields = setOf("path", "ownerPaths", "ownershipPaths", "coordinatePaths", "evidencePaths", "deferredPaths")
    fun resolve(element: JsonElement, field: String = ""): JsonElement = when (element) {
        is JsonObject -> when {
            element.size == 1 && "authorityRef" in element -> {
                val reference = element.getValue("authorityRef").jsonPrimitive.content
                require(visiting.add(reference)) { "Cyclic authority reference" }
                val resolved = resolve(requireNotNull(projection.authorities[reference]) { "Missing authority reference: $reference" })
                visiting.remove(reference)
                resolved
            }
            element.size == 1 && "hashRef" in element -> JsonPrimitive(requireNotNull(projection.hashes[element.getValue("hashRef").jsonPrimitive.content]) { "Missing authority hash" })
            element.size == 1 && "sourceHashRef" in element -> {
                val path = sourcePath(element.getValue("sourceHashRef").jsonPrimitive.content)
                JsonPrimitive(requireNotNull(context.files.singleOrNull { it.path == path }) { "Source hash has no supplied evidence" }.contentHash)
            }
            else -> JsonObject(element.mapValues { (key, value) -> resolve(value, key) })
        }
        is JsonArray -> JsonArray(element.map { resolve(it, field) })
        is JsonPrimitive -> if (element.isString && field in pathFields) JsonPrimitive(sourcePath(element.content)) else element
    }
    return resolve(projection.frame) as JsonObject
}