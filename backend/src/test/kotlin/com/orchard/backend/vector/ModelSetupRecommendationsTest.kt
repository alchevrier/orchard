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
        assertEquals(
            "classic-pc-32gb",
            Json.decodeFromString<ModelSetupRecommendations>(recommendations.bodyAsText()).recommendedPresetId,
        )

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