package com.orchard.backend.conversation

import com.orchard.backend.agent.DefinitionIntelligenceService
import com.orchard.backend.vector.CatalogModelBinding
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelEndpointDefinition
import com.orchard.backend.vector.ModelProviderRegistry
import com.orchard.backend.vector.PROVIDER_LOCALITY_LOCAL
import com.orchard.backend.vector.PROVIDER_PROTOCOL_OPENAI_COMPATIBLE
import com.orchard.backend.vector.TransientModelProfileSettingsStore
import com.orchard.backend.vector.TransientModelProviderCatalogStore
import com.orchard.backend.workspace.WorkspaceStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConversationModelConfigurationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun `conductor registers model and assigns it to coding profile`() = runBlocking {
        val workspace = WorkspaceStore()
        val registry = ModelProviderRegistry(TransientModelProviderCatalogStore())
        val settings = TransientModelProfileSettingsStore()
        val intelligence = DefinitionIntelligenceService(workspace, registry.providers(), settings)
        val capabilities = defaultConversationCapabilities(
            workspace = workspace,
            definitionIntelligence = intelligence,
            modelProviderRegistry = registry,
        )
        val endpoint = ModelEndpointDefinition(
            "local-coder",
            "Local coder",
            PROVIDER_PROTOCOL_OPENAI_COMPATIBLE,
            "http://127.0.0.1:1234",
            PROVIDER_LOCALITY_LOCAL,
        )
        val binding = CatalogModelBinding(
            "local-coder:qwen",
            endpoint.endpointId,
            "qwen-coder",
            131_072,
            residentMemoryBytes = 8_000_000_000,
        )
        val registerCommand = command(
            1,
            CAPABILITY_REGISTER_MODEL,
            json.encodeToString(RegisterModelCapabilityPayload(endpoint, binding)),
        )
        val register = assertNotNull(capabilities.resolve(CAPABILITY_REGISTER_MODEL))

        val registered = register.execute(registerCommand.payloadJson, null, registerCommand)

        assertTrue(registered.success)
        val registeredBinding = registry.catalog().bindings.single { it.bindingId == binding.bindingId }
        assertEquals(binding, registeredBinding.copy(conversationCommand = null))
        assertEquals(registerCommand.commandId, registeredBinding.conversationCommand?.commandId)
        assertNotNull(register.reconcile(registerCommand, null))

        val profileId = DefaultModelExecutionProfiles.boundedCodingPatch.id
        val assignCommand = command(
            2,
            CAPABILITY_ASSIGN_MODEL_PROFILE,
            json.encodeToString(AssignModelProfileCapabilityPayload(profileId, binding.bindingId)),
        )
        val assign = assertNotNull(capabilities.resolve(CAPABILITY_ASSIGN_MODEL_PROFILE))

        val assigned = assign.execute(assignCommand.payloadJson, null, assignCommand)

        assertTrue(assigned.success)
        assertEquals(binding.bindingId, intelligence.profileConfigurations()
            .single { it.defaultProfile.id == profileId }.override?.preferredBindingId)
        assertEquals(assignCommand.commandId, intelligence.profileConfigurations()
            .single { it.defaultProfile.id == profileId }.override?.conversationCommand?.commandId)
        assertNotNull(assign.reconcile(assignCommand, null))
        registry.close()
    }

    @Test
    fun `conductor rejects incompatible model profile assignment`() = runBlocking {
        val workspace = WorkspaceStore()
        val registry = ModelProviderRegistry(TransientModelProviderCatalogStore())
        val intelligence = DefinitionIntelligenceService(
            workspace,
            registry.providers(),
            TransientModelProfileSettingsStore(),
        )
        val capability = assertNotNull(defaultConversationCapabilities(
            workspace = workspace,
            definitionIntelligence = intelligence,
            modelProviderRegistry = registry,
        ).resolve(CAPABILITY_ASSIGN_MODEL_PROFILE))
        val profileId = DefaultModelExecutionProfiles.broadRepositoryAnalysis.id
        val command = command(
            3,
            CAPABILITY_ASSIGN_MODEL_PROFILE,
            json.encodeToString(AssignModelProfileCapabilityPayload(
                profileId,
                registry.catalog().bindings.single().bindingId,
                inputBudgetTokens = 200_000,
                outputBudgetTokens = 8_000,
            )),
        )

        val result = capability.execute(command.payloadJson, null, command)

        assertTrue(!result.success)
        assertTrue(intelligence.profileConfigurations().single { it.defaultProfile.id == profileId }.override == null)
        registry.close()
    }

    @Test
    fun `older model commands cannot replace newer admitted configuration`() = runBlocking {
        val workspace = WorkspaceStore()
        val registry = ModelProviderRegistry(TransientModelProviderCatalogStore())
        val intelligence = DefinitionIntelligenceService(
            workspace,
            registry.providers(),
            TransientModelProfileSettingsStore(),
        )
        val capabilities = defaultConversationCapabilities(
            workspace = workspace,
            definitionIntelligence = intelligence,
            modelProviderRegistry = registry,
        )
        val profileId = DefaultModelExecutionProfiles.boundedCodingPatch.id
        val bindingId = registry.catalog().bindings.single().bindingId
        val older = command(10, CAPABILITY_ASSIGN_MODEL_PROFILE, json.encodeToString(
            AssignModelProfileCapabilityPayload(profileId, bindingId, 24_000, 8_000),
        ))
        val newer = command(11, CAPABILITY_ASSIGN_MODEL_PROFILE, json.encodeToString(
            AssignModelProfileCapabilityPayload(profileId, bindingId, 32_000, 8_000),
        ))
        val assign = assertNotNull(capabilities.resolve(CAPABILITY_ASSIGN_MODEL_PROFILE))

        assertTrue(assign.execute(older.payloadJson, null, older).success)
        assertTrue(assign.execute(newer.payloadJson, null, newer).success)
        val stale = assign.execute(older.payloadJson, null, older)

        assertTrue(!stale.success)
        val current = intelligence.profileConfigurations().single { it.defaultProfile.id == profileId }.override
        assertEquals(32_000, current?.inputBudgetTokens)
        assertEquals(newer.commandId, current?.conversationCommand?.commandId)
        registry.close()
    }

    private fun command(id: Long, capabilityId: String, payload: String) = newConversationCommand(
        ConversationCommandProposal(
            id,
            1,
            null,
            1,
            "d".repeat(64),
            capabilityId,
            payload,
            true,
            "2026-07-19T00:00:00Z",
            "",
        )
    )
}