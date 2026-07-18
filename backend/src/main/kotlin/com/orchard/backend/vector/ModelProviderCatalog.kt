package com.orchard.backend.vector

import java.net.URI
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

const val PROVIDER_PROTOCOL_OLLAMA_NATIVE = "OLLAMA_NATIVE"
const val PROVIDER_PROTOCOL_OPENAI_COMPATIBLE = "OPENAI_COMPATIBLE"
const val PROVIDER_LOCALITY_LOCAL = "LOCAL"
const val PROVIDER_LOCALITY_REMOTE = "REMOTE"
const val PROVIDER_POLICY_LOCAL_ONLY = "LOCAL_ONLY"
const val PROVIDER_POLICY_LOCAL_PREFERRED = "LOCAL_PREFERRED"
const val PROVIDER_POLICY_CLOUD_ALLOWED = "CLOUD_ALLOWED"
const val PROVIDER_POLICY_CLOUD_ESCALATION_ONLY = "CLOUD_ESCALATION_ONLY"

@Serializable
data class ModelEndpointDefinition(
    val endpointId: String,
    val displayName: String,
    val protocol: String,
    val baseUrl: String,
    val locality: String,
    val credentialReference: String? = null,
    val enabled: Boolean = true,
)

@Serializable
data class CatalogModelBinding(
    val bindingId: String,
    val endpointId: String,
    val model: String,
    val contextWindowTokens: Int,
    val capabilities: Set<String> = setOf(MODEL_CAPABILITY_STRICT_JSON),
    val modelDigest: String? = null,
    val residentMemoryBytes: Long = 0,
    val cpuUnits: Int = 1,
    val configuration: Map<String, String> = emptyMap(),
)

@Serializable
data class ModelProviderCatalog(
    val policy: String = PROVIDER_POLICY_LOCAL_ONLY,
    val endpoints: List<ModelEndpointDefinition>,
    val bindings: List<CatalogModelBinding>,
)

interface ModelProviderCatalogStore {
    fun load(): ModelProviderCatalog
    fun save(catalog: ModelProviderCatalog)
}

class TransientModelProviderCatalogStore(initial: ModelProviderCatalog = defaultLocalModelProviderCatalog()) : ModelProviderCatalogStore {
    private var catalog = initial.also(::validateModelProviderCatalog)

    @Synchronized
    override fun load(): ModelProviderCatalog = catalog

    @Synchronized
    override fun save(catalog: ModelProviderCatalog) {
        validateModelProviderCatalog(catalog)
        this.catalog = catalog
    }
}

class FileModelProviderCatalogStore(private val directory: Path) : ModelProviderCatalogStore {
    private val path = directory.resolve("model-provider-catalog.json")
    private val json = Json { encodeDefaults = true; prettyPrint = true }
    private val compactJson = Json { encodeDefaults = true }

    @Synchronized
    override fun load(): ModelProviderCatalog {
        if (!Files.exists(path)) {
            val catalog = defaultLocalModelProviderCatalog()
            save(catalog)
            return catalog
        }
        val envelope = runCatching { json.decodeFromString<ModelProviderCatalogEnvelope>(Files.readString(path)) }
            .getOrElse { throw IllegalStateException("Cannot decode model provider catalog at $path", it) }
        require(envelope.version == FORMAT_VERSION) { "Unsupported model provider catalog version ${envelope.version}" }
        require(envelope.checksum == checksum(compactJson.encodeToString(envelope.catalog))) {
            "Model provider catalog checksum mismatch at $path"
        }
        validateModelProviderCatalog(envelope.catalog)
        return envelope.catalog
    }

    @Synchronized
    override fun save(catalog: ModelProviderCatalog) {
        validateModelProviderCatalog(catalog)
        Files.createDirectories(directory)
        val envelope = ModelProviderCatalogEnvelope(
            catalog = catalog,
            checksum = checksum(compactJson.encodeToString(catalog)),
        )
        val temporary = Files.createTempFile(directory, "${path.fileName}.", ".tmp")
        FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val bytes = ByteBuffer.wrap((json.encodeToString(envelope) + "\n").toByteArray())
            while (bytes.hasRemaining()) channel.write(bytes)
            channel.force(true)
        }
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) }
    }

    private fun checksum(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

    private companion object {
        const val FORMAT_VERSION = 1
    }
}

fun defaultLocalModelProviderCatalog(): ModelProviderCatalog = ModelProviderCatalog(
    endpoints = listOf(
        ModelEndpointDefinition(
            endpointId = "local-ollama",
            displayName = "Local Ollama",
            protocol = PROVIDER_PROTOCOL_OLLAMA_NATIVE,
            baseUrl = "http://127.0.0.1:11434",
            locality = PROVIDER_LOCALITY_LOCAL,
        )
    ),
    bindings = listOf(
        CatalogModelBinding(
            bindingId = "ollama:phi3-mini:json:t0:s42",
            endpointId = "local-ollama",
            model = "phi3:mini",
            contextWindowTokens = 131_072,
            residentMemoryBytes = 2_400_000_000L,
            configuration = mapOf("temperature" to "0", "seed" to "42"),
        )
    ),
)

fun validateModelProviderCatalog(catalog: ModelProviderCatalog) {
    require(catalog.policy in POLICIES) { "Model provider policy is invalid" }
    require(catalog.endpoints.isNotEmpty() && catalog.bindings.isNotEmpty()) { "Model provider catalog cannot be empty" }
    require(catalog.endpoints.map { it.endpointId }.distinct().size == catalog.endpoints.size) { "Endpoint IDs must be unique" }
    require(catalog.bindings.map { it.bindingId }.distinct().size == catalog.bindings.size) { "Binding IDs must be unique" }
    catalog.endpoints.forEach { endpoint ->
        require(endpoint.endpointId.matches(ID) && endpoint.displayName.isNotBlank()) { "Endpoint identity is invalid" }
        require(endpoint.protocol in PROTOCOLS && endpoint.locality in LOCALITIES) { "Endpoint protocol or locality is invalid" }
        val uri = runCatching { URI(endpoint.baseUrl) }.getOrNull()
        require(uri != null && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Endpoint URL is invalid"
        }
        if (endpoint.locality == PROVIDER_LOCALITY_LOCAL) require(uri.host in LOOPBACK_HOSTS) {
            "Local endpoints must use a loopback host"
        }
        require(endpoint.credentialReference == null || endpoint.credentialReference.matches(CREDENTIAL_REFERENCE)) {
            "Only environment credential references are supported"
        }
        require(endpoint.credentialReference == null || endpoint.locality == PROVIDER_LOCALITY_REMOTE) {
            "Local endpoints cannot declare credentials"
        }
    }
    val endpoints = catalog.endpoints.associateBy { it.endpointId }
    catalog.bindings.forEach { binding ->
        require(binding.bindingId.matches(ID) && binding.endpointId in endpoints && binding.model.isNotBlank()) { "Model binding identity is invalid" }
        require(binding.contextWindowTokens in 2_048..1_000_000 && binding.capabilities.contains(MODEL_CAPABILITY_STRICT_JSON)) {
            "Model binding capabilities or context are invalid"
        }
        require(binding.residentMemoryBytes >= 0 && binding.cpuUnits in 1..256) { "Model binding resource demand is invalid" }
        require(binding.configuration.keys.none { it.contains("key", true) || it.contains("secret", true) || it.contains("token", true) }) {
            "Model binding configuration cannot contain secrets"
        }
    }
    if (catalog.policy == PROVIDER_POLICY_LOCAL_ONLY) require(catalog.endpoints.filter { it.enabled }.all { it.locality == PROVIDER_LOCALITY_LOCAL }) {
        "Local-only policy cannot enable remote endpoints"
    }
}

@Serializable
private data class ModelProviderCatalogEnvelope(
    val version: Int = 1,
    val catalog: ModelProviderCatalog,
    val checksum: String,
)

private val PROTOCOLS = setOf(PROVIDER_PROTOCOL_OLLAMA_NATIVE, PROVIDER_PROTOCOL_OPENAI_COMPATIBLE)
private val LOCALITIES = setOf(PROVIDER_LOCALITY_LOCAL, PROVIDER_LOCALITY_REMOTE)
private val POLICIES = setOf(
    PROVIDER_POLICY_LOCAL_ONLY,
    PROVIDER_POLICY_LOCAL_PREFERRED,
    PROVIDER_POLICY_CLOUD_ALLOWED,
    PROVIDER_POLICY_CLOUD_ESCALATION_ONLY,
)
private val LOOPBACK_HOSTS = setOf("127.0.0.1", "localhost", "::1")
private val ID = Regex("[A-Za-z0-9][A-Za-z0-9._:-]{1,127}")
private val CREDENTIAL_REFERENCE = Regex("env:[A-Z][A-Z0-9_]{1,127}")