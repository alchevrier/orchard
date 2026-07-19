package com.orchard.backend.vector

import kotlinx.serialization.Serializable

const val MODEL_SETUP_PLATFORM_APPLE_SILICON = "APPLE_SILICON"
const val MODEL_SETUP_PLATFORM_CLASSIC_PC = "CLASSIC_PC"

@Serializable
data class ModelSetupRecommendationRequest(
    val platform: String,
    val usableMemoryBytes: Long,
)

@Serializable
data class ModelSetupPreset(
    val presetId: String,
    val displayName: String,
    val platform: String,
    val minimumMemoryBytes: Long,
    val maximumMemoryBytes: Long? = null,
    val summary: String,
    val catalog: ModelProviderCatalog,
    val profileOverrides: List<ModelProfileOverride>,
    val requiredModels: List<String>,
    val setupCommands: List<String>,
)

@Serializable
data class ModelSetupRecommendations(
    val detectedPlatform: String,
    val detectedMemoryBytes: Long,
    val recommendedPresetId: String,
    val presets: List<ModelSetupPreset>,
)

@Serializable
data class ModelSetupApplication(
    val preset: ModelSetupPreset,
    val profiles: List<ModelProfileConfiguration>,
)

enum class ModelSetupBootstrapStatus {
    APPLIED,
    PRESERVED_CUSTOM_CONFIGURATION,
}

fun bootstrapDetectedLocalModelSetup(
    catalogStore: ModelProviderCatalogStore,
    profileSettingsStore: ModelProfileSettingsStore,
    platform: String,
    usableMemoryBytes: Long,
): ModelSetupBootstrapStatus {
    val recommendations = LocalModelSetupRecommendations.forMachine(platform, usableMemoryBytes)
    val preset = LocalModelSetupRecommendations.resolve(recommendations.recommendedPresetId)
    val currentCatalog = catalogStore.load()
    val currentOverrides = profileSettingsStore.load()
    val legacyCatalog = defaultLocalModelProviderCatalog()
    val resumablePresetWrite = currentOverrides == preset.profileOverrides
    if (currentCatalog != legacyCatalog || (currentOverrides.isNotEmpty() && !resumablePresetWrite)) {
        return ModelSetupBootstrapStatus.PRESERVED_CUSTOM_CONFIGURATION
    }

    profileSettingsStore.save(preset.profileOverrides)
    catalogStore.save(preset.catalog)
    return ModelSetupBootstrapStatus.APPLIED
}

object LocalModelSetupRecommendations {
    private val LOCAL_OLLAMA_ENDPOINT = ModelEndpointDefinition(
        endpointId = "local-ollama",
        displayName = "Local Ollama",
        protocol = PROVIDER_PROTOCOL_OLLAMA_NATIVE,
        baseUrl = "http://127.0.0.1:11434",
        locality = PROVIDER_LOCALITY_LOCAL,
    )

    fun forMachine(platform: String, usableMemoryBytes: Long): ModelSetupRecommendations {
        require(platform in SUPPORTED_PLATFORMS) { "Unsupported model setup platform $platform" }
        require(usableMemoryBytes > 0) { "Usable memory must be positive" }
        val presets = presets(platform)
        val recommended = presets.lastOrNull { usableMemoryBytes >= it.minimumMemoryBytes } ?: presets.first()
        return ModelSetupRecommendations(platform, usableMemoryBytes, recommended.presetId, presets)
    }

    fun resolve(presetId: String): ModelSetupPreset = allPresets()
        .singleOrNull { it.presetId == presetId }
        ?: throw IllegalArgumentException("Unknown model setup preset $presetId")

    fun detectPlatform(osName: String = System.getProperty("os.name"), architecture: String = System.getProperty("os.arch")): String =
        if (osName.contains("mac", ignoreCase = true) && architecture.lowercase() in setOf("aarch64", "arm64")) {
            MODEL_SETUP_PLATFORM_APPLE_SILICON
        } else {
            MODEL_SETUP_PLATFORM_CLASSIC_PC
        }

    private fun presets(platform: String): List<ModelSetupPreset> = when (platform) {
        MODEL_SETUP_PLATFORM_APPLE_SILICON -> appleSiliconPresets
        MODEL_SETUP_PLATFORM_CLASSIC_PC -> classicPcPresets
        else -> error("Unsupported model setup platform $platform")
    }

    private fun allPresets(): List<ModelSetupPreset> = classicPcPresets + appleSiliconPresets

    private val classicPcPresets = listOf(
        preset(
            presetId = "classic-pc-8gb",
            displayName = "Classic PC · 8 GB safe start",
            platform = MODEL_SETUP_PLATFORM_CLASSIC_PC,
            minimumMemoryGiB = 8,
            maximumMemoryGiB = 15,
            summary = "CPU-safe defaults for a PC without declared accelerator memory.",
            generalModel = model("qwen3:4b", 3_000, 32_768, 2),
            codingModel = model("qwen2.5-coder:3b", 2_200, 32_768, 2),
            aperture = Aperture(8_192, 1_536, 6_144, 1_536, 6_144, 2_048, 8_192, 2_048, 8_192, 2_048, 8_192, 2_048),
        ),
        preset(
            presetId = "classic-pc-16gb",
            displayName = "Classic PC · 16 GB balanced",
            platform = MODEL_SETUP_PLATFORM_CLASSIC_PC,
            minimumMemoryGiB = 16,
            maximumMemoryGiB = 31,
            summary = "Balanced local reasoning and coding on system memory or an 8 GB-class accelerator.",
            generalModel = model("qwen3:8b", 5_200, 40_960, 4),
            codingModel = model("qwen2.5-coder:7b", 4_700, 32_768, 4),
            aperture = Aperture(16_384, 2_048, 10_240, 2_048, 10_240, 3_072, 16_384, 4_096, 18_432, 3_072, 16_384, 3_072),
        ),
        preset(
            presetId = "classic-pc-32gb",
            displayName = "Classic PC · 32 GB capable",
            platform = MODEL_SETUP_PLATFORM_CLASSIC_PC,
            minimumMemoryGiB = 32,
            maximumMemoryGiB = 63,
            summary = "Larger local models for a workstation with 32 GB of usable model memory.",
            generalModel = model("qwen3:14b", 9_500, 40_960, 6),
            codingModel = model("qwen2.5-coder:14b", 9_500, 32_768, 6),
            aperture = Aperture(28_672, 3_072, 12_288, 2_048, 12_288, 3_072, 24_576, 6_144, 28_672, 4_096, 28_672, 3_072),
        ),
        preset(
            presetId = "classic-pc-64gb",
            displayName = "Classic PC · 64 GB performance",
            platform = MODEL_SETUP_PLATFORM_CLASSIC_PC,
            minimumMemoryGiB = 64,
            summary = "High-context coding and repository analysis for a 48 GB-class accelerator or CPU workstation.",
            generalModel = model("qwen3-coder:30b", 19_000, 131_072, 8),
            aperture = Aperture(48_000, 4_000, 12_000, 2_000, 12_000, 3_000, 24_000, 8_000, 88_000, 8_000, 64_000, 4_000),
        ),
    )

    private val appleSiliconPresets = listOf(
        preset(
            presetId = "apple-silicon-8gb",
            displayName = "MacBook Neo / Air · 8 GB",
            platform = MODEL_SETUP_PLATFORM_APPLE_SILICON,
            minimumMemoryGiB = 8,
            maximumMemoryGiB = 15,
            summary = "A conservative first-run route for entry Apple silicon with unified memory.",
            generalModel = model("qwen3:4b", 3_000, 32_768, 2),
            codingModel = model("qwen2.5-coder:3b", 2_200, 32_768, 2),
            aperture = Aperture(8_192, 1_536, 6_144, 1_536, 6_144, 2_048, 8_192, 2_048, 8_192, 2_048, 8_192, 2_048),
        ),
        preset(
            presetId = "apple-silicon-16gb",
            displayName = "MacBook Neo / Air · 16–24 GB",
            platform = MODEL_SETUP_PLATFORM_APPLE_SILICON,
            minimumMemoryGiB = 16,
            maximumMemoryGiB = 31,
            summary = "Balanced defaults for fanless and entry Apple silicon systems.",
            generalModel = model("qwen3:8b", 5_200, 40_960, 4),
            codingModel = model("qwen2.5-coder:7b", 4_700, 32_768, 4),
            aperture = Aperture(16_384, 2_048, 10_240, 2_048, 10_240, 3_072, 16_384, 4_096, 18_432, 3_072, 16_384, 3_072),
        ),
        preset(
            presetId = "apple-silicon-32gb",
            displayName = "Apple silicon · 32 GB",
            platform = MODEL_SETUP_PLATFORM_APPLE_SILICON,
            minimumMemoryGiB = 32,
            maximumMemoryGiB = 63,
            summary = "Capable role-specific local models with room for the operating system and tools.",
            generalModel = model("qwen3:14b", 9_500, 40_960, 6),
            codingModel = model("qwen2.5-coder:14b", 9_500, 32_768, 6),
            aperture = Aperture(28_672, 3_072, 12_288, 2_048, 12_288, 3_072, 24_576, 6_144, 28_672, 4_096, 28_672, 3_072),
        ),
        preset(
            presetId = "apple-silicon-64gb",
            displayName = "Apple silicon · 64 GB",
            platform = MODEL_SETUP_PLATFORM_APPLE_SILICON,
            minimumMemoryGiB = 64,
            maximumMemoryGiB = 95,
            summary = "A single strong coding model with Orchard's full default stage apertures.",
            generalModel = model("qwen3-coder:30b", 19_000, 131_072, 8),
            aperture = Aperture(48_000, 4_000, 12_000, 2_000, 12_000, 3_000, 24_000, 8_000, 88_000, 8_000, 64_000, 4_000),
        ),
        preset(
            presetId = "apple-silicon-96gb",
            displayName = "Apple silicon · 96 GB",
            platform = MODEL_SETUP_PLATFORM_APPLE_SILICON,
            minimumMemoryGiB = 96,
            maximumMemoryGiB = 127,
            summary = "A larger reasoning model with guarded context apertures for 96 GB unified memory.",
            generalModel = model("gpt-oss:120b", 65_000, 131_072, 10),
            aperture = Aperture(48_000, 4_000, 12_000, 2_000, 12_000, 3_000, 24_000, 8_000, 52_000, 8_000, 52_000, 4_000),
        ),
        preset(
            presetId = "apple-silicon-128gb",
            displayName = "Apple silicon · 128 GB",
            platform = MODEL_SETUP_PLATFORM_APPLE_SILICON,
            minimumMemoryGiB = 128,
            summary = "The larger reasoning model with expanded analysis and audit context on 128 GB unified memory.",
            generalModel = model("gpt-oss:120b", 65_000, 131_072, 12),
            aperture = Aperture(64_000, 4_000, 16_000, 2_000, 16_000, 3_000, 32_000, 8_000, 88_000, 8_000, 80_000, 4_000),
        ),
    )

    private fun preset(
        presetId: String,
        displayName: String,
        platform: String,
        minimumMemoryGiB: Long,
        maximumMemoryGiB: Long? = null,
        summary: String,
        generalModel: RecommendedModel,
        codingModel: RecommendedModel = generalModel,
        aperture: Aperture,
    ): ModelSetupPreset {
        val models = listOf(generalModel, codingModel).distinctBy { it.name }
        val bindings = models.map { recommended ->
            CatalogModelBinding(
                bindingId = bindingId(recommended.name),
                endpointId = LOCAL_OLLAMA_ENDPOINT.endpointId,
                model = recommended.name,
                contextWindowTokens = recommended.contextWindowTokens,
                residentMemoryBytes = recommended.residentMemoryMiB * MEBIBYTE,
                cpuUnits = recommended.cpuUnits,
                configuration = mapOf("temperature" to "0", "seed" to "42"),
            )
        }
        val generalBindingId = bindingId(generalModel.name)
        val codingBindingId = bindingId(codingModel.name)
        val overrides = listOf(
            override(DefaultModelExecutionProfiles.boundedConversationConductor, aperture.conductorInput, aperture.conductorOutput, generalBindingId),
            override(DefaultModelExecutionProfiles.boundedDefinitionReasoning, aperture.definitionInput, aperture.definitionOutput, generalBindingId),
            override(DefaultModelExecutionProfiles.boundedCircuitSynthesis, aperture.circuitInput, aperture.circuitOutput, generalBindingId),
            override(DefaultModelExecutionProfiles.boundedCodingPatch, aperture.codingInput, aperture.codingOutput, codingBindingId),
            override(DefaultModelExecutionProfiles.broadRepositoryAnalysis, aperture.analysisInput, aperture.analysisOutput, generalBindingId),
            override(DefaultModelExecutionProfiles.boundedIndependentAudit, aperture.auditInput, aperture.auditOutput, generalBindingId),
        )
        return ModelSetupPreset(
            presetId = presetId,
            displayName = displayName,
            platform = platform,
            minimumMemoryBytes = minimumMemoryGiB * GIBIBYTE,
            maximumMemoryBytes = maximumMemoryGiB?.times(GIBIBYTE),
            summary = summary,
            catalog = ModelProviderCatalog(endpoints = listOf(LOCAL_OLLAMA_ENDPOINT), bindings = bindings),
            profileOverrides = overrides,
            requiredModels = models.map { it.name },
            setupCommands = listOf("ollama serve") + models.map { "ollama pull ${it.name}" },
        )
    }

    private fun override(profile: ModelExecutionProfile, input: Int, output: Int, bindingId: String) = ModelProfileOverride(
        profileId = profile.id,
        inputBudgetTokens = input,
        outputBudgetTokens = output,
        preferredBindingId = bindingId,
    )

    private fun bindingId(model: String): String = "ollama:${model.replace(':', '-')}:json:t0:s42"

    private fun model(name: String, residentMemoryMiB: Long, contextWindowTokens: Int, cpuUnits: Int) =
        RecommendedModel(name, residentMemoryMiB, contextWindowTokens, cpuUnits)

    private data class RecommendedModel(
        val name: String,
        val residentMemoryMiB: Long,
        val contextWindowTokens: Int,
        val cpuUnits: Int,
    )

    private data class Aperture(
        val conductorInput: Int,
        val conductorOutput: Int,
        val definitionInput: Int,
        val definitionOutput: Int,
        val circuitInput: Int,
        val circuitOutput: Int,
        val codingInput: Int,
        val codingOutput: Int,
        val analysisInput: Int,
        val analysisOutput: Int,
        val auditInput: Int,
        val auditOutput: Int,
    )

    private val SUPPORTED_PLATFORMS = setOf(MODEL_SETUP_PLATFORM_APPLE_SILICON, MODEL_SETUP_PLATFORM_CLASSIC_PC)
    private const val MEBIBYTE = 1_048_576L
    private const val GIBIBYTE = 1_073_741_824L
}