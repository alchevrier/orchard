package com.orchard.backend.conversation

import com.orchard.backend.company.AUDIT_CONFORMING
import com.orchard.backend.company.AuditJudgment
import com.orchard.backend.company.CompanyAcceptance
import com.orchard.backend.company.CompanyProjectView
import com.orchard.backend.company.LocalPromotion
import com.orchard.backend.company.ROLE_ARCHITECTURE_AUDITOR
import com.orchard.backend.resource.MachineCapacityMonitor
import com.orchard.backend.resource.MachineCapacitySnapshot
import com.orchard.backend.resource.MachineResourceController
import com.orchard.backend.resource.MachineUsagePolicy
import com.orchard.backend.resource.ModelResourceDemand
import com.orchard.backend.resource.TransientMachineUsagePolicyStore
import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.ModelExecutionProfile
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProvider
import com.orchard.backend.workspace.WorkspaceStore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ConversationConductorTest {
    @Test
    fun `small onboarding turn admits actual context demand instead of full aperture`() = runBlocking {
        var admittedInputTokens = 0
        var executionContextTokens = 0
        val provider = object : ModelProvider {
            override suspend fun triage(prompt: String): String = error("unused")
            override suspend fun plan(prompt: String, actionType: Int, entityType: Int, workspace: WorkspaceStore): String = error("unused")
            override fun bindingProfile() = ModelBindingProfile(
                "local-model",
                "ollama-local",
                "qwen3-coder:30b",
                131_072,
                setOf(MODEL_CAPABILITY_STRICT_JSON),
            )
            override fun resourceDemand(profile: ModelExecutionProfile) = ModelResourceDemand(52_000, 1)
            override fun resourceDemand(profile: ModelExecutionProfile, inputTokens: Int): ModelResourceDemand {
                admittedInputTokens = inputTokens
                return ModelResourceDemand(inputTokens.toLong() + profile.outputBudgetTokens, 1)
            }
            override suspend fun executeConversation(prompt: String, maxOutputTokens: Int, contextWindowTokens: Int): ModelGeneration {
                executionContextTokens = contextWindowTokens
                return ModelGeneration("{\"speechAct\":\"INFORMATION\",\"response\":\"Ready.\"}", admittedInputTokens, 8)
            }
        }
        val resources = MachineResourceController(
            TransientMachineUsagePolicyStore(MachineUsagePolicy(100, 0, 1)),
            object : MachineCapacityMonitor {
                override fun snapshot() = MachineCapacitySnapshot(30_000, 30_000, 8, 0.0)
            },
        )
        val interpreter = ModelConversationInterpreter(listOf(provider), resources)
        val context = ConversationContextManifest(1, recentMessages = emptyList(), objectives = emptyList(), commandStates = emptyList(), summaries = emptyList(), capabilities = emptyList())
        val message = ConversationMessage(1, 1, 1, "client-onboarding-0001", MESSAGE_ROLE_USER, "Onboard https://example.com/acme/repository.git", actor = "HUMAN", createdAt = NOW, hash = HASH)

        val result = interpreter.interpret(context, message)

        assertEquals("Ready.", result.interpretation.response)
        assertTrue(admittedInputTokens in 1 until 26_000)
        assertEquals(admittedInputTokens + 4_000, executionContextTokens)
        assertEquals("ADMITTED", result.resourceDecision)
    }

    @Test
    fun `default registry exposes typed project setup authority`() {
        val descriptors = defaultConversationCapabilities(WorkspaceStore()).descriptors().associateBy { it.id }
        val setupCapabilities = setOf(
            CAPABILITY_CREATE_WORK_ITEM,
            CAPABILITY_BIND_REPOSITORY,
            CAPABILITY_INSPECT_PROJECT_GENESIS,
            CAPABILITY_ADVANCE_PROJECT_GENESIS,
            CAPABILITY_ADMIT_PROJECT_GENESIS,
        )

        assertTrue(descriptors.keys.containsAll(setupCapabilities))
        assertTrue(setupCapabilities.map(descriptors::getValue).all {
            it.owningService.isNotBlank() && it.resultType.isNotBlank() && it.idempotencyStrategy.isNotBlank()
        })
        assertEquals("NONE", descriptors.getValue(CAPABILITY_INSPECT_PROJECT_GENESIS).admissionRule)
        assertEquals("EXACT_COMMAND_HASH", descriptors.getValue(CAPABILITY_ADMIT_PROJECT_GENESIS).admissionRule)
    }

    @Test
    fun `configured registry exposes admitted onboarding and model authority`() {
        val workspace = WorkspaceStore()
        val modelRegistry = com.orchard.backend.vector.ModelProviderRegistry(
            com.orchard.backend.vector.TransientModelProviderCatalogStore(),
        )
        val descriptors = defaultConversationCapabilities(
            workspace = workspace,
            definitionIntelligence = com.orchard.backend.agent.DefinitionIntelligenceService(
                workspace,
                modelRegistry.providers(),
                com.orchard.backend.vector.TransientModelProfileSettingsStore(),
            ),
            repositoryOnboarding = com.orchard.backend.workspace.RepositoryOnboardingService(
                workspace,
                kotlin.io.path.createTempDirectory("orchard-capability-onboarding-"),
            ),
            modelProviderRegistry = modelRegistry,
        ).descriptors().associateBy { it.id }

        assertEquals("EXACT_COMMAND_HASH", descriptors.getValue(CAPABILITY_ONBOARD_REPOSITORY).admissionRule)
        assertTrue(descriptors.getValue(CAPABILITY_ONBOARD_REPOSITORY).allowedObjectiveStates.isEmpty())
        assertEquals("NONE", descriptors.getValue(CAPABILITY_INSPECT_MODEL_CONFIGURATION).admissionRule)
        assertEquals("EXACT_COMMAND_HASH", descriptors.getValue(CAPABILITY_REGISTER_MODEL).admissionRule)
        assertTrue(descriptors.getValue(CAPABILITY_ASSIGN_MODEL_PROFILE).allowedObjectiveStates.isEmpty())
        modelRegistry.close()
    }

    @Test
    fun `message retry is idempotent and mutation requires exact admission`() = runBlocking {
        val store = TransientConversationStore()
        val executions = AtomicInteger()
        val capability = testCapability("MUTATE_TEST", mutation = true) {
            executions.incrementAndGet()
            ConversationCapabilityResult(true, "Mutation correlated.", "TEST_AUTHORITY", "42", HASH)
        }
        val interpreter = QueueInterpreter(
            objectiveTurn("First objective"),
            commandTurn("MUTATE_TEST"),
        )
        val service = service(store, interpreter, capability)
        val conversationId = requireNotNull(service.create(CreateConversationRequest("Test conversation")).projection)
            .conversation.conversationId

        val objectiveRequest = SubmitConversationMessageRequest("client-objective-0001", 1, "Create an objective")
        val proposed = service.submitMessage(conversationId, objectiveRequest)
        assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED, proposed.status)
        val duplicate = service.submitMessage(conversationId, objectiveRequest)
        assertEquals(ConversationOperationStatus.ALREADY_RECORDED, duplicate.status)
        assertEquals(1, interpreter.calls.get())

        val projection = requireNotNull(service.projection(conversationId))
        val objective = projection.objectives.single()
        val source = projection.messages.single { it.role == MESSAGE_ROLE_USER }
        assertEquals(OBJECTIVE_AWAITING_ADMISSION, objective.state)
        assertEquals(ConversationOperationStatus.RECORDED, service.controlObjective(
            objective.objectiveId,
            ControlConversationObjectiveRequest(OBJECTIVE_CONTROL_ADMIT, source.messageId, source.hash),
        ).status)

        val commandProposal = service.submitMessage(
            conversationId,
            SubmitConversationMessageRequest("client-command-0001", 3, "Run the mutation", objective.objectiveId),
        )
        assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED, commandProposal.status)
        assertEquals(0, executions.get())
        val command = requireNotNull(service.projection(conversationId)).commands.single().proposal
        assertEquals(ConversationOperationStatus.REJECTED, service.admitCommand(
            command.commandId,
            AdmitConversationCommandRequest("0".repeat(64)),
        ).status)
        assertEquals(0, executions.get())

        val admitted = service.admitCommand(command.commandId, AdmitConversationCommandRequest(command.hash))
        assertEquals(ConversationOperationStatus.RECORDED, admitted.status)
        assertEquals(1, executions.get())
        val commandView = requireNotNull(admitted.projection).commands.single()
        assertNotNull(commandView.admission)
        assertEquals(COMMAND_CORRELATED, commandView.executions.last().state)
        assertEquals("42", commandView.executions.last().downstreamId)
    }

    @Test
    fun `independent objective commands execute concurrently`() = runBlocking {
        val store = TransientConversationStore()
        val active = AtomicInteger()
        val bothStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val capability = testCapability("PARALLEL_TEST", mutation = true) {
            if (active.incrementAndGet() == 2) bothStarted.complete(Unit)
            withTimeout(2_000) { bothStarted.await() }
            release.await()
            active.decrementAndGet()
            ConversationCapabilityResult(true, "Parallel mutation correlated.", "TEST_AUTHORITY", "parallel", HASH)
        }
        val interpreter = QueueInterpreter(
            objectiveTurn("Objective one"),
            objectiveTurn("Objective two"),
            commandTurn("PARALLEL_TEST"),
            commandTurn("PARALLEL_TEST"),
        )
        val service = service(store, interpreter, capability)
        val conversationId = requireNotNull(service.create(CreateConversationRequest("Parallel conversation")).projection)
            .conversation.conversationId
        service.submitMessage(conversationId, SubmitConversationMessageRequest("client-objective-0001", 1, "First objective"))
        service.submitMessage(conversationId, SubmitConversationMessageRequest("client-objective-0002", 3, "Second objective"))
        val initial = requireNotNull(service.projection(conversationId))
        initial.objectives.forEach { objective ->
            val source = initial.messages.single { it.messageId == objective.sourceMessageId }
            assertEquals(ConversationOperationStatus.RECORDED, service.controlObjective(
                objective.objectiveId,
                ControlConversationObjectiveRequest(OBJECTIVE_CONTROL_ADMIT, source.messageId, source.hash),
            ).status)
        }
        val objectives = requireNotNull(service.projection(conversationId)).objectives
        service.submitMessage(conversationId, SubmitConversationMessageRequest(
            "client-command-0001", 5, "Run first", objectives[0].objectiveId,
        ))
        service.submitMessage(conversationId, SubmitConversationMessageRequest(
            "client-command-0002", 7, "Run second", objectives[1].objectiveId,
        ))
        val commands = requireNotNull(service.projection(conversationId)).commands.map { it.proposal }

        coroutineScope {
            val first = async { service.admitCommand(commands[0].commandId, AdmitConversationCommandRequest(commands[0].hash)) }
            val second = async { service.admitCommand(commands[1].commandId, AdmitConversationCommandRequest(commands[1].hash)) }
            withTimeout(2_000) { bothStarted.await() }
            assertEquals(2, active.get())
            release.complete(Unit)
            assertEquals(ConversationOperationStatus.RECORDED, first.await().status)
            assertEquals(ConversationOperationStatus.RECORDED, second.await().status)
        }
        assertTrue(requireNotNull(service.projection(conversationId)).commands.all {
            it.executions.last().state == COMMAND_CORRELATED
        })
    }

    @Test
    fun `restart adopts dispatched command without repeating mutation`() = runBlocking {
        val store = TransientConversationStore()
        val conversation = newConversation(Conversation(1, "Recovery", "HUMAN", NOW, ""))
        val message = newConversationMessage(ConversationMessage(
            1, 1, 1, "client-recovery-0001", MESSAGE_ROLE_USER, "Recover exact command", actor = "HUMAN", createdAt = NOW, hash = "",
        ))
        val objective = newConversationObjective(ConversationObjectiveRevision(
            1, 1, 1, title = "Recovery", outcome = "Adopt downstream authority", state = OBJECTIVE_READY,
            sourceMessageId = message.messageId, sourceMessageHash = message.hash, actor = "HUMAN", createdAt = NOW, hash = "",
        ))
        val command = newConversationCommand(ConversationCommandProposal(
            1, 1, objective.objectiveId, message.messageId, message.hash, "RECOVER_TEST", "{}", true, NOW, "",
        ))
        store.appendConversation(conversation)
        store.appendMessage(message)
        store.appendObjective(objective)
        store.appendCommand(command)
        store.appendAdmission(newConversationAdmission(ConversationCommandAdmission(1, 1, command.hash, "HUMAN", NOW, "")))
        store.appendExecution(newConversationExecution(ConversationCommandExecution(
            1, 1, command.hash, COMMAND_ADMITTED, recordedAt = NOW, hash = "",
        )))
        store.appendExecution(newConversationExecution(ConversationCommandExecution(
            2, 1, command.hash, COMMAND_DISPATCHED, recordedAt = NOW, hash = "",
        )))
        val repeated = AtomicInteger()
        val capability = object : ConversationCapability {
            override val descriptor = ConversationCapabilityDescriptor(
                "RECOVER_TEST", "Recovery", true, "{}", setOf(OBJECTIVE_READY),
                "TestService", "EXACT_COMMAND_HASH", "TEST_AUTHORITY", "COMMAND_REFERENCE",
            )

            override suspend fun reconcile(
                command: ConversationCommandProposal,
                objective: ConversationObjectiveRevision?,
            ) = ConversationCapabilityResult(true, "Adopted exact authority.", "TEST_AUTHORITY", "99", HASH)

            override suspend fun execute(
                payloadJson: String,
                objective: ConversationObjectiveRevision?,
                command: ConversationCommandProposal,
            ): ConversationCapabilityResult {
                repeated.incrementAndGet()
                return ConversationCapabilityResult(true, "Repeated.", "TEST_AUTHORITY", "100", HASH)
            }
        }
        val service = service(store, QueueInterpreter(), capability)

        assertEquals(1, service.reconcilePending())

        assertEquals(0, repeated.get())
        val execution = requireNotNull(service.projection(1)).commands.single().executions.last()
        assertEquals(COMMAND_CORRELATED, execution.state)
        assertEquals("99", execution.downstreamId)
    }

    @Test
    fun `long transcript persists source linked summary and bounds active context`() = runBlocking {
        val store = TransientConversationStore()
        val contexts = mutableListOf<ConversationContextManifest>()
        val interpreter = ConversationInterpreter { context, _ ->
            contexts += context
            InterpretedConversationTurn(ConversationInterpretation(SPEECH_DISCUSS, "Bounded reply."))
        }
        val service = service(store, interpreter)
        val conversationId = requireNotNull(service.create(CreateConversationRequest("Long context")).projection)
            .conversation.conversationId

        repeat(13) { index ->
            val expectedSequence = requireNotNull(service.projection(conversationId)).messages.size + 1L
            assertEquals(ConversationOperationStatus.RECORDED, service.submitMessage(
                conversationId,
                SubmitConversationMessageRequest("client-context-${index.toString().padStart(4, '0')}", expectedSequence, "Message $index"),
            ).status)
        }

        val projection = requireNotNull(service.projection(conversationId))
        assertEquals(26, projection.messages.size)
        assertEquals(24, projection.summaries.single().sourceMessageIds.size)
        assertTrue(contexts.last().recentMessages.size <= 24)
        assertEquals(projection.summaries.single().hash, contexts.last().summaries.single().hash)
    }

    @Test
    fun `dispatch revalidates dependencies and paused objectives gate correlated runs`() = runBlocking {
        val store = TransientConversationStore()
        val executions = AtomicInteger()
        val capability = testCapability("RUN_TEST", mutation = true) {
            executions.incrementAndGet()
            ConversationCapabilityResult(true, "Run correlated.", "WORKFLOW_RUN", "42", HASH)
        }
        val service = service(
            store,
            QueueInterpreter(objectiveTurn("Dependency"), objectiveTurn("Dependent"), commandTurn("RUN_TEST")),
            capability,
        )
        val conversationId = requireNotNull(service.create(CreateConversationRequest("Dispatch controls")).projection)
            .conversation.conversationId
        service.submitMessage(conversationId, SubmitConversationMessageRequest("client-dependency-0001", 1, "Dependency"))
        service.submitMessage(conversationId, SubmitConversationMessageRequest("client-dependent-0001", 3, "Dependent"))
        val proposed = requireNotNull(service.projection(conversationId))
        proposed.objectives.forEach { objective ->
            val source = proposed.messages.single { it.messageId == objective.sourceMessageId }
            assertEquals(ConversationOperationStatus.RECORDED, service.controlObjective(
                objective.objectiveId,
                ControlConversationObjectiveRequest(OBJECTIVE_CONTROL_ADMIT, source.messageId, source.hash),
            ).status)
        }
        val dependency = proposed.objectives[0]
        val dependent = proposed.objectives[1]
        val dependentSource = proposed.messages.single { it.messageId == dependent.sourceMessageId }
        assertEquals(ConversationOperationStatus.RECORDED, service.controlObjective(
            dependent.objectiveId,
            ControlConversationObjectiveRequest(
                OBJECTIVE_CONTROL_SET_DEPENDENCIES,
                dependentSource.messageId,
                dependentSource.hash,
                dependencyObjectiveIds = listOf(dependency.objectiveId),
            ),
        ).status)
        assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED, service.submitMessage(
            conversationId,
            SubmitConversationMessageRequest("client-dependent-run-0001", 5, "Run dependent", dependent.objectiveId),
        ).status)
        val command = requireNotNull(service.projection(conversationId)).commands.single().proposal
        assertEquals(ConversationOperationStatus.REJECTED, service.admitCommand(
            command.commandId,
            AdmitConversationCommandRequest(command.hash),
        ).status)
        assertEquals(0, executions.get())

        val dependencySource = proposed.messages.single { it.messageId == dependency.sourceMessageId }
        assertEquals(ConversationOperationStatus.RECORDED, service.controlObjective(
            dependency.objectiveId,
            ControlConversationObjectiveRequest(OBJECTIVE_CONTROL_COMPLETE, dependencySource.messageId, dependencySource.hash),
        ).status)
        assertEquals(ConversationOperationStatus.RECORDED, service.admitCommand(
            command.commandId,
            AdmitConversationCommandRequest(command.hash),
        ).status)
        assertEquals(listOf(42L, 99L), service.dispatchableRunIds(listOf(42L, 99L)))
        assertEquals(ConversationOperationStatus.RECORDED, service.controlObjective(
            dependent.objectiveId,
            ControlConversationObjectiveRequest(OBJECTIVE_CONTROL_PAUSE, dependentSource.messageId, dependentSource.hash),
        ).status)
        assertEquals(listOf(99L), service.dispatchableRunIds(listOf(42L, 99L)))
    }

    @Test
    fun `company authority projects audit acceptance and terminal promotion exactly once`() {
        val store = TransientConversationStore()
        val conversation = newConversation(Conversation(1, "Company projection", "HUMAN", NOW, ""))
        val message = newConversationMessage(ConversationMessage(
            1, 1, 1, "client-company-0001", MESSAGE_ROLE_USER, "Deliver the change.",
            actor = "HUMAN", createdAt = NOW, hash = "",
        ))
        val objective = newConversationObjective(ConversationObjectiveRevision(
            1, 1, 1, 1, "Delivery", "Promote one revision.", state = OBJECTIVE_ACTIVE,
            sourceMessageId = message.messageId, sourceMessageHash = message.hash,
            actor = "HUMAN", createdAt = NOW, hash = "",
        ))
        val command = newConversationCommand(ConversationCommandProposal(
            1, 1, objective.objectiveId, message.messageId, message.hash, "START_WORKFLOW", "{\"workItemId\":4}",
            true, NOW, "",
        ))
        store.appendConversation(conversation)
        store.appendMessage(message)
        store.appendObjective(objective)
        store.appendCommand(command)
        store.appendAdmission(newConversationAdmission(ConversationCommandAdmission(1, 1, command.hash, "HUMAN", NOW, "")))
        store.appendExecution(newConversationExecution(ConversationCommandExecution(
            1, 1, command.hash, COMMAND_CORRELATED, "WORKFLOW_RUN", "42", HASH, recordedAt = NOW, hash = "",
        )))
        val service = service(store, QueueInterpreter())
        val audit = AuditJudgment(
            1, 1, 42, 1, ROLE_ARCHITECTURE_AUDITOR, GIT_HASH, HASH, HASH, HASH,
            emptyList(), AUDIT_CONFORMING, "Conforming.", NOW, HASH,
        )
        val acceptance = CompanyAcceptance(2, 1, 42, GIT_HASH, HASH, HASH, listOf(1), "ORCHARD", NOW, HASH)
        val promotion = LocalPromotion(3, 1, 42, 2, GIT_HASH, GIT_HASH, GIT_HASH, NOW, HASH)
        val project = CompanyProjectView(
            1, "OBSERVATION", "HEALTHY", audits = listOf(audit), acceptances = listOf(acceptance), promotions = listOf(promotion),
        )

        assertEquals(4, service.projectCompanyActivity(listOf(project)))
        val projection = requireNotNull(service.projection(1))
        assertEquals(OBJECTIVE_COMPLETED, projection.objectives.single().state)
        assertEquals(listOf("COMPANY_AUDIT", "COMPANY_ACCEPTANCE", "LOCAL_PROMOTION"), projection.activities.map { it.authorityType })
        assertEquals(0, service.projectCompanyActivity(listOf(project)))
    }

    private fun service(
        store: ConversationStore,
        interpreter: ConversationInterpreter,
        vararg capabilities: ConversationCapability,
    ) = ConversationConductorService(
        store,
        interpreter,
        ConversationCapabilityRegistry(capabilities.toList()),
        now = { NOW },
    )

    private fun testCapability(
        id: String,
        mutation: Boolean,
        execute: suspend () -> ConversationCapabilityResult,
    ) = object : ConversationCapability {
        override val descriptor = ConversationCapabilityDescriptor(
            id,
            "Test capability",
            mutation,
            "{}",
            setOf(OBJECTIVE_READY, OBJECTIVE_ACTIVE),
            "TestService",
            if (mutation) "EXACT_COMMAND_HASH" else "NONE",
            "TEST_AUTHORITY",
            "TEST_KEY",
        )

        override suspend fun execute(
            payloadJson: String,
            objective: ConversationObjectiveRevision?,
            command: ConversationCommandProposal,
        ): ConversationCapabilityResult = execute()
    }

    private fun objectiveTurn(title: String) = ConversationInterpretation(
        SPEECH_PROPOSE_OBJECTIVE,
        "$title awaits admission.",
        ObjectiveCandidate(title = title, outcome = "Complete $title"),
    )

    private fun commandTurn(capabilityId: String) = ConversationInterpretation(
        SPEECH_PROPOSE_DOMAIN_ACTION,
        "The command awaits admission.",
        capabilityId = capabilityId,
        payloadJson = "{}",
    )

    private class QueueInterpreter(vararg turns: ConversationInterpretation) : ConversationInterpreter {
        private val pending = ArrayDeque(turns.toList())
        val calls = AtomicInteger()

        override suspend fun interpret(
            context: ConversationContextManifest,
            message: ConversationMessage,
        ): InterpretedConversationTurn {
            calls.incrementAndGet()
            return InterpretedConversationTurn(pending.removeFirst())
        }
    }

    private companion object {
        const val NOW = "2026-06-21T00:00:00Z"
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val GIT_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}