package com.orchard.backend.agent

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class ToolchainPolicyPack(
    val schemaVersion: Int,
    val packId: String,
    val packVersion: Int,
    val profiles: List<ToolchainProfile>,
)

@Serializable
data class ToolchainProfile(
    val id: String,
    val priority: Int = 0,
    val allFiles: List<String> = emptyList(),
    val anyFiles: List<String> = emptyList(),
    val commands: Map<String, VerificationCommand>,
)

@Serializable
data class VerificationCommand(
    val executable: String,
    val arguments: List<String> = emptyList(),
) {
    fun canonical(): String = (listOf(executable) + arguments).joinToString(" ")
}

@Serializable
data class ResolvedToolchainPolicy(
    val packId: String,
    val packVersion: Int,
    val profileId: String,
    val policyHash: String,
    val commands: Map<String, VerificationCommand>,
)

interface ToolchainPolicyCatalog {
    fun resolve(repository: Path): ResolvedToolchainPolicy?
}

class FileToolchainPolicyCatalog(
    private val externalDirectory: Path? = null,
    private val builtInPack: ToolchainPolicyPack = loadBuiltInToolchainPolicyPack(),
    private val json: Json = strictToolchainJson,
) : ToolchainPolicyCatalog {
    override fun resolve(repository: Path): ResolvedToolchainPolicy? {
        val builtIn = validatedPack(builtInPack)
        val external = loadExternalPacks()
        val packs = listOf(builtIn) + external
        require(packs.map { it.packId }.distinct().size == packs.size) { "Toolchain policy pack IDs must be unique" }
        val externalIds = external.mapTo(hashSetOf()) { it.packId }
        val candidates = packs.flatMap { pack ->
            pack.profiles.filter { profile -> matches(repository, profile) }.map { profile ->
                ToolchainCandidate(pack, profile, external = pack.packId in externalIds)
            }
        }.sortedWith(
            compareByDescending<ToolchainCandidate> { it.profile.priority }
                .thenByDescending { it.external }
                .thenBy { it.pack.packId }
                .thenBy { it.profile.id }
        )
        val selected = candidates.firstOrNull() ?: return null
        val pack = selected.pack
        val profile = selected.profile
        return ResolvedToolchainPolicy(
            packId = pack.packId,
            packVersion = pack.packVersion,
            profileId = profile.id,
            policyHash = toolchainPolicyHash(pack, profile),
            commands = profile.commands.mapKeys { it.key.uppercase() },
        )
    }

    private fun loadExternalPacks(): List<ToolchainPolicyPack> {
        val directory = externalDirectory ?: return emptyList()
        if (!Files.isDirectory(directory)) return emptyList()
        val entries = Files.list(directory).use { paths ->
            paths.limit(MAX_POLICY_DIRECTORY_ENTRIES + 1L).toList()
        }
        require(entries.size <= MAX_POLICY_DIRECTORY_ENTRIES) { "Toolchain policy directory has too many entries" }
        val packPaths = entries.filter { path ->
            Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && path.fileName.toString().endsWith(".json")
        }.sorted()
        require(packPaths.size <= MAX_EXTERNAL_PACKS) { "Too many external toolchain policy packs" }
        return packPaths.map { path ->
            val bytes = Files.newInputStream(path).use { it.readNBytes(MAX_POLICY_PACK_BYTES + 1) }
            require(bytes.size <= MAX_POLICY_PACK_BYTES) { "Toolchain policy pack at $path is too large" }
            val pack = runCatching { json.decodeFromString<ToolchainPolicyPack>(bytes.toString(Charsets.UTF_8)) }
                .getOrElse { throw IllegalStateException("Cannot decode toolchain policy pack at $path", it) }
            validatedPack(pack)
        }
    }

    private fun matches(repository: Path, profile: ToolchainProfile): Boolean =
        profile.allFiles.all { relative -> regularPolicyDetector(repository, relative) } &&
            (profile.anyFiles.isEmpty() || profile.anyFiles.any { relative -> regularPolicyDetector(repository, relative) })

    private fun regularPolicyDetector(repository: Path, relative: String): Boolean {
        val path = repository.resolve(relative).normalize()
        return path.startsWith(repository) && Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
    }
}

private data class ToolchainCandidate(
    val pack: ToolchainPolicyPack,
    val profile: ToolchainProfile,
    val external: Boolean,
)

internal fun validatedPack(pack: ToolchainPolicyPack): ToolchainPolicyPack {
    require(pack.schemaVersion == TOOLCHAIN_POLICY_SCHEMA_VERSION) {
        "Unsupported toolchain policy schema ${pack.schemaVersion}"
    }
    require(pack.packId.matches(POLICY_ID) && pack.packVersion > 0) { "Toolchain policy pack identity is invalid" }
    require(pack.profiles.isNotEmpty() && pack.profiles.size <= MAX_TOOLCHAIN_PROFILES) {
        "Toolchain policy profile count is invalid"
    }
    require(pack.profiles.map { it.id }.distinct().size == pack.profiles.size) {
        "Toolchain profile IDs must be unique within a pack"
    }
    pack.profiles.forEach { profile ->
        require(profile.id.matches(POLICY_ID) && profile.priority in MIN_PROFILE_PRIORITY..MAX_PROFILE_PRIORITY) {
            "Toolchain profile identity or priority is invalid"
        }
        require(profile.allFiles.isNotEmpty() || profile.anyFiles.isNotEmpty()) {
            "Toolchain profile ${profile.id} requires a detector"
        }
        require(profile.allFiles.size + profile.anyFiles.size <= MAX_DETECTORS) {
            "Toolchain profile ${profile.id} has too many detectors"
        }
        (profile.allFiles + profile.anyFiles).forEach(::validateDetector)
        require(profile.commands.isNotEmpty() && profile.commands.size <= MAX_COMMANDS) {
            "Toolchain profile ${profile.id} command count is invalid"
        }
        profile.commands.forEach { (kind, command) ->
            require(kind.matches(EVIDENCE_KIND) && kind == kind.uppercase()) { "Toolchain evidence kind $kind is invalid" }
            validateCommand(command)
        }
    }
    return pack
}

private fun validateDetector(detector: String) {
    require(detector.isNotBlank() && detector.length <= MAX_DETECTOR_LENGTH) { "Toolchain detector is invalid" }
    val path = Path.of(detector)
    require(!path.isAbsolute && path.none { it.toString() == ".." }) { "Toolchain detector must be repository-relative" }
    require(path.firstOrNull()?.toString() !in FORBIDDEN_POLICY_ROOTS) { "Toolchain detector targets reserved metadata" }
}

internal fun validateCommand(command: VerificationCommand) {
    require(command.executable.matches(EXECUTABLE)) { "Toolchain executable ${command.executable} is invalid" }
    require(command.arguments.size <= MAX_COMMAND_ARGUMENTS) { "Toolchain command has too many arguments" }
    command.arguments.forEach { argument ->
        require(argument.isNotBlank() && argument.length <= MAX_ARGUMENT_LENGTH && argument.none { it == '\u0000' || it == '\n' || it == '\r' }) {
            "Toolchain command argument is invalid"
        }
    }
    require(command.canonical().length <= MAX_CANONICAL_COMMAND_LENGTH) { "Toolchain command is too long" }
}

internal fun toolchainPolicyHash(pack: ToolchainPolicyPack, profile: ToolchainProfile): String = sha256(
    strictToolchainJson.encodeToString(
        ToolchainPolicyHashMaterial(
            schemaVersion = pack.schemaVersion,
            packId = pack.packId,
            packVersion = pack.packVersion,
            profile = profile.copy(commands = profile.commands.toSortedMap()),
        )
    )
)

@Serializable
private data class ToolchainPolicyHashMaterial(
    val schemaVersion: Int,
    val packId: String,
    val packVersion: Int,
    val profile: ToolchainProfile,
)

internal fun loadBuiltInToolchainPolicyPack(): ToolchainPolicyPack {
    val stream = requireNotNull(
        ToolchainPolicyPack::class.java.getResourceAsStream("/default-policy-packs/toolchains-v1.json")
    ) { "Missing built-in toolchain policy pack" }
    val pack = stream.bufferedReader(Charsets.UTF_8).use { strictToolchainJson.decodeFromString<ToolchainPolicyPack>(it.readText()) }
    return validatedPack(pack)
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> (byte.toInt() and 0xff).toString(16).padStart(2, '0') }

private val strictToolchainJson = Json { encodeDefaults = true }
private const val TOOLCHAIN_POLICY_SCHEMA_VERSION = 1
private const val MAX_TOOLCHAIN_PROFILES = 128
private const val MAX_POLICY_DIRECTORY_ENTRIES = 256
private const val MAX_EXTERNAL_PACKS = 64
private const val MAX_POLICY_PACK_BYTES = 256 * 1024
private const val MAX_DETECTORS = 16
private const val MAX_COMMANDS = 16
private const val MAX_DETECTOR_LENGTH = 256
private const val MAX_COMMAND_ARGUMENTS = 64
private const val MAX_ARGUMENT_LENGTH = 512
private const val MAX_CANONICAL_COMMAND_LENGTH = 4_096
private const val MIN_PROFILE_PRIORITY = -10_000
private const val MAX_PROFILE_PRIORITY = 10_000
private val POLICY_ID = Regex("[a-z0-9][a-z0-9._-]{0,127}")
private val EVIDENCE_KIND = Regex("[A-Z][A-Z0-9:_-]{0,127}")
private val EXECUTABLE = Regex(
    "(?:[A-Za-z0-9][A-Za-z0-9._+-]{0,127}|" +
        "\\./[A-Za-z0-9][A-Za-z0-9._+-]{0,127}(?:/[A-Za-z0-9][A-Za-z0-9._+-]{0,127}){0,7})"
)
private val FORBIDDEN_POLICY_ROOTS = setOf(".git", ".orchard")
