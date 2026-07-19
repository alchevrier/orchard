package com.orchard.backend.vector

import com.orchard.backend.agent.DefinitionIntelligenceService
import com.orchard.backend.resource.MachineCapacityMonitor
import com.orchard.backend.resource.MachineCapacitySnapshot
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.TransientMachineUsagePolicyStore
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspaceApi
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

class ModelSetupRecommendationsTest {
    @Test
    fun `apple silicon recommendation follows unified memory tier`() {
        val expected = mapOf(
            8L to "apple-silicon-8gb",
            16L to "apple-silicon-16gb",
            32L to "apple-silicon-32gb",
            64L to "apple-silicon-64gb",
            96L to "apple-silicon-96gb",
            128L to "apple-silicon-128gb",
        )

        expected.forEach { (memoryGiB, presetId) ->
            val recommendations = LocalModelSetupRecommendations.forMachine(
                MODEL_SETUP_PLATFORM_APPLE_SILICON,
                memoryGiB * GIBIBYTE,
            )
            assertEquals(presetId, recommendations.recommendedPresetId)
        }
    }

    @Test
    fun `classic PC recommendation remains conservative without accelerator telemetry`() {
        assertEquals(
            "classic-pc-8gb",
            LocalModelSetupRecommendations.forMachine(MODEL_SETUP_PLATFORM_CLASSIC_PC, 12 * GIBIBYTE).recommendedPresetId,
        )
        assertEquals(
            "classic-pc-32gb",
            LocalModelSetupRecommendations.forMachine(MODEL_SETUP_PLATFORM_CLASSIC_PC, 48 * GIBIBYTE).recommendedPresetId,
        )
        assertEquals(
            "classic-pc-64gb",
            LocalModelSetupRecommendations.forMachine(MODEL_SETUP_PLATFORM_CLASSIC_PC, 128 * GIBIBYTE).recommendedPresetId,
        )
    }

    @Test
    fun `every preset provides compatible bindings for all stages within its memory floor`() {
        val presetIds = listOf(
            "classic-pc-8gb",
            "classic-pc-16gb",
            "classic-pc-32gb",
            "classic-pc-64gb",
            "apple-silicon-8gb",
            "apple-silicon-16gb",
            "apple-silicon-32gb",
            "apple-silicon-64gb",
            "apple-silicon-96gb",
            "apple-silicon-128gb",
        )

        presetIds.forEach { presetId ->
            val preset = LocalModelSetupRecommendations.resolve(presetId)
            val bindings = preset.catalog.bindings.associateBy { it.bindingId }
            assertEquals(DefaultModelExecutionProfiles.all().map { it.id }.toSet(), preset.profileOverrides.map { it.profileId }.toSet())
            assertEquals(preset.requiredModels.toSet(), preset.catalog.bindings.map { it.model }.toSet())
            assertEquals(listOf("ollama serve") + preset.requiredModels.map { "ollama pull $it" }, preset.setupCommands)
            validateModelProviderCatalog(preset.catalog)

            preset.profileOverrides.forEach { override ->
                val binding = assertNotNull(bindings[override.preferredBindingId])
                val requiredTokens = override.inputBudgetTokens + override.outputBudgetTokens
                assertTrue(binding.contextWindowTokens >= requiredTokens, "$presetId exceeds ${binding.model} context")
                val demand = binding.residentMemoryBytes + requiredTokens.toLong() * KV_CACHE_BYTES_PER_TOKEN
                assertTrue(
                    demand + GIBIBYTE <= preset.minimumMemoryBytes,
                    "$presetId cannot admit ${override.profileId} while retaining 1 GiB",
                )
            }
        }
    }

    @Test
    fun `platform detection distinguishes apple silicon from classic systems`() {
        assertEquals(MODEL_SETUP_PLATFORM_APPLE_SILICON, LocalModelSetupRecommendations.detectPlatform("Mac OS X", "aarch64"))
        assertEquals(MODEL_SETUP_PLATFORM_CLASSIC_PC, LocalModelSetupRecommendations.detectPlatform("Mac OS X", "x86_64"))
        assertEquals(MODEL_SETUP_PLATFORM_CLASSIC_PC, LocalModelSetupRecommendations.detectPlatform("Linux", "aarch64"))
    }

    @Test
    fun `startup replaces only untouched legacy model configuration`() {
        val catalogStore = TransientModelProviderCatalogStore()
        val profileStore = TransientModelProfileSettingsStore()

        val status = bootstrapDetectedLocalModelSetup(
            catalogStore,
            profileStore,
            MODEL_SETUP_PLATFORM_APPLE_SILICON,
            32 * GIBIBYTE,
        )

        assertEquals(ModelSetupBootstrapStatus.APPLIED, status)
        assertEquals(setOf("qwen3:14b", "qwen2.5-coder:14b"), catalogStore.load().bindings.map { it.model }.toSet())
        assertEquals(6, profileStore.load().size)
    }

    @Test
    fun `startup migrates persisted legacy catalog and profiles together`() {
        val directory = createTempDirectory("orchard-model-bootstrap-")
        val catalogStore = FileModelProviderCatalogStore(directory)
        val profileStore = FileModelProfileSettingsStore(directory)
        assertEquals("phi3:mini", catalogStore.load().bindings.single().model)

        val status = bootstrapDetectedLocalModelSetup(
            catalogStore,
            profileStore,
            MODEL_SETUP_PLATFORM_APPLE_SILICON,
            64 * GIBIBYTE,
        )

        assertEquals(ModelSetupBootstrapStatus.APPLIED, status)
        assertEquals("qwen3-coder:30b", FileModelProviderCatalogStore(directory).load().bindings.single().model)
        val recoveredProfiles = FileModelProfileSettingsStore(directory).load()
        assertEquals(6, recoveredProfiles.size)
        assertTrue(recoveredProfiles.all { it.preferredBindingId == "ollama:qwen3-coder-30b:json:t0:s42" })
    }

    @Test
    fun `startup completes interrupted preset write but preserves customization`() {
        val preset = LocalModelSetupRecommendations.resolve("apple-silicon-32gb")
        val interruptedCatalogStore = TransientModelProviderCatalogStore()
        val interruptedProfileStore = TransientModelProfileSettingsStore().also { it.save(preset.profileOverrides) }

        assertEquals(
            ModelSetupBootstrapStatus.APPLIED,
            bootstrapDetectedLocalModelSetup(
                interruptedCatalogStore,
                interruptedProfileStore,
                MODEL_SETUP_PLATFORM_APPLE_SILICON,
                32 * GIBIBYTE,
            ),
        )
        assertEquals(preset.catalog, interruptedCatalogStore.load())

        val customCatalog = defaultLocalModelProviderCatalog().copy(
            bindings = defaultLocalModelProviderCatalog().bindings.map { it.copy(model = "my-model") },
        )
        val customCatalogStore = TransientModelProviderCatalogStore(customCatalog)
        val customProfileStore = TransientModelProfileSettingsStore()
        assertEquals(
            ModelSetupBootstrapStatus.PRESERVED_CUSTOM_CONFIGURATION,
            bootstrapDetectedLocalModelSetup(
                customCatalogStore,
                customProfileStore,
                MODEL_SETUP_PLATFORM_APPLE_SILICON,
                32 * GIBIBYTE,
            ),
        )
        assertEquals(customCatalog, customCatalogStore.load())
        assertTrue(customProfileStore.load().isEmpty())
    }

    @Test
    fun `setup API recommends for machine capacity and applies every stage in one request`() = testApplication {
        val workspace = WorkspaceStore()
        val registry = ModelProviderRegistry(TransientModelProviderCatalogStore())
        val settings = TransientModelProfileSettingsStore()
        val resources = MachineResourceController(
            TransientMachineUsagePolicyStore(),
            object : MachineCapacityMonitor {
                override fun snapshot() = MachineCapacitySnapshot(32 * GIBIBYTE, 30 * GIBIBYTE, 10, 0.0)
            },
        )
        val intelligence = DefinitionIntelligenceService(workspace, registry.providers(), settings, resources)
        application { workspaceApi(workspace, definitionIntelligence = intelligence, modelProviderRegistry = registry) }

        val recommendations = client.get("/api/model-setup/recommendations")
        assertEquals(HttpStatusCode.OK, recommendations.status)
        val decodedRecommendations = Json.decodeFromString<ModelSetupRecommendations>(recommendations.bodyAsText())
        val expectedPlatform = LocalModelSetupRecommendations.detectPlatform()
        assertEquals(
            expectedPlatform,
            decodedRecommendations.detectedPlatform,
        )
        val expectedPresetPrefix = if (expectedPlatform == MODEL_SETUP_PLATFORM_APPLE_SILICON) "apple-silicon" else "classic-pc"
        assertEquals("$expectedPresetPrefix-32gb", decodedRecommendations.recommendedPresetId)

        val applied = client.post("/api/model-setup/presets/apple-silicon-32gb/apply")
        assertEquals(HttpStatusCode.OK, applied.status)
        val result = Json.decodeFromString<ModelSetupApplication>(applied.bodyAsText())
        assertEquals("apple-silicon-32gb", result.preset.presetId)
        assertEquals(setOf("qwen3:14b", "qwen2.5-coder:14b"), registry.catalog().bindings.map { it.model }.toSet())
        assertEquals(6, result.profiles.size)
        assertTrue(result.profiles.all { it.override?.preferredBindingId in result.preset.catalog.bindings.map { binding -> binding.bindingId } })
        registry.close()
    }

    private companion object {
        const val GIBIBYTE = 1_073_741_824L
        const val KV_CACHE_BYTES_PER_TOKEN = 393_216L
    }
}