package com.orchard.backend.conversation

import com.orchard.backend.company.AUDIT_CONFORMING
import com.orchard.backend.company.AuditJudgment
import com.orchard.backend.company.CompanyAcceptance
import com.orchard.backend.company.CompanyProjectView
import com.orchard.backend.company.LocalPromotion
import com.orchard.backend.company.ROLE_ARCHITECTURE_AUDITOR
import com.orchard.backend.agent.CandidatePullRequest
import com.orchard.backend.agent.CandidatePullRequestCorrection
import com.orchard.backend.agent.CandidatePullRequestEvidence
import com.orchard.backend.agent.CandidatePullRequestReviewFinding
import com.orchard.backend.agent.CANDIDATE_REVIEW_INTENT
import com.orchard.backend.agent.REVIEW_CORRECTION_CANDIDATE_REPAIR
import com.orchard.backend.agent.candidatePullRequestHash
import com.orchard.backend.agent.newCandidatePullRequestCorrection
import com.orchard.backend.agent.newCandidatePullRequestReview
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
import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ExperienceContract
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.PROJECT_GREENFIELD_LOCAL
import com.orchard.backend.workspace.ProjectGenesisStatus
import com.orchard.backend.workspace.ProjectGenesisSubmission
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ConversationConductorTest {
    @Test
    fun `Genesis context is current and scoped and invalid submissions never reach admission`() = runBlocking {
        val workspace = WorkspaceStore()
        workspace.beginBatch()
        for (title in listOf("Selected", "Unrelated")) {
            assertTrue(workspace.applyIntent(DocumentIntent(
                ACTION_CREATE, ENTITY_PROJECT, DEFAULT_DELIVERY_WORKFLOW_ID, title = title,
            )))
        }
        for (projectId in 1..2) {
            assertTrue(workspace.applyIntent(DocumentIntent(
                ACTION_CREATE, ENTITY_EPIC, DEFAULT_DELIVERY_WORKFLOW_ID, projectId = projectId, title = "Outcome $projectId",
            )))
        }
        workspace.commitBatch()
        assertEquals(ProjectGenesisStatus.RECORDED, workspace.advanceProjectGenesis(1, ProjectGenesisSubmission(
            classification = PROJECT_GREENFIELD_LOCAL, productIntent = "Deliver the content service and its tests.", baseRevision = 0,
        )).status)
        val revision = requireNotNull(workspace.snapshot(MESSAGE_READY).projectGenesis.first { it.projectId == 1 }.revision)
        val submission = ProjectGenesisSubmission(
            experience = ExperienceContract(
                audience = "Developers", productPromise = "Look up persisted content.",
                primaryJourney = listOf("Start the service", "Fetch content"),
                interactionPrinciples = listOf("Predictable HTTP"), emotionalQualities = listOf("Confident"),
                mustNotFeelLike = listOf("An untested stub"),
            ),
            baseRevision = revision.revision,
            baseHash = revision.hash,
        )
        val objectiveTurn = objectiveTurn("Deliver content lookup").let { turn ->
            turn.copy(objective = requireNotNull(turn.objective).copy(projectId = 1))
        }
        val interpreter = QueueInterpreter(
            objectiveTurn,
            commandTurn(CAPABILITY_ADVANCE_PROJECT_GENESIS).copy(
                payloadJson = """{"projectId":1,"submission":{"experience":{"primaryJourney":["Wrong phase payload"]}}}""",
            ),
            commandTurn(CAPABILITY_ADVANCE_PROJECT_GENESIS).copy(payloadJson = Json.encodeToString(
                AdvanceProjectGenesisCapabilityPayload(1, submission.copy(
                    experience = null, classification = PROJECT_GREENFIELD_LOCAL,
                )),
            )),
            commandTurn(CAPABILITY_ADVANCE_PROJECT_GENESIS).copy(payloadJson = Json.encodeToString(
                AdvanceProjectGenesisCapabilityPayload(1, submission.copy(baseRevision = 0, baseHash = null)),
            )),
            commandTurn(CAPABILITY_ADVANCE_PROJECT_GENESIS).copy(
                payloadJson = Json.encodeToString(AdvanceProjectGenesisCapabilityPayload(1, submission)),
            ),
        )
        val store = TransientConversationStore()
        val registry = defaultConversationCapabilities(workspace)
        val compiler = ConversationContextCompiler(workspace = workspace)
        val service = ConversationConductorService(store, interpreter, registry, compiler, now = { NOW })
        val conversationId = requireNotNull(service.create(CreateConversationRequest("Genesis")).projection).conversation.conversationId
        val initialResult = service.submitMessage(conversationId, SubmitConversationMessageRequest(
            "initial-goal", 1, "Deliver the content service and its tests.",
        ))
        assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED, initialResult.status, initialResult.diagnostic)
        val initial = requireNotNull(initialResult.projection)
        val objective = initial.objectives.single()
        val source = initial.messages.first()
        service.controlObjective(objective.objectiveId, ControlConversationObjectiveRequest(
            OBJECTIVE_CONTROL_ADMIT, source.messageId, source.hash,
        ))
        for ((index, diagnostic) in listOf("baseRevision", "INVALID_TRANSITION", "STALE_REVISION").withIndex()) {
            val result = service.submitMessage(conversationId, SubmitConversationMessageRequest(
                "invalid-$index", requireNotNull(service.projection(conversationId)).messages.size + 1L,
                "Continue the original requirement.", objective.objectiveId,
            ))
            assertEquals(ConversationOperationStatus.REJECTED, result.status)
            assertTrue(result.diagnostic.contains(diagnostic), result.diagnostic)
            assertTrue(requireNotNull(result.projection).commands.isEmpty())
            assertEquals(revision, workspace.snapshot(MESSAGE_READY).projectGenesis.first { it.projectId == 1 }.revision)
        }
        val proposed = service.submitMessage(conversationId, SubmitConversationMessageRequest(
            "valid-submission", requireNotNull(service.projection(conversationId)).messages.size + 1L,
            "Continue the original requirement.", objective.objectiveId,
        ))
        assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED, proposed.status)
        assertEquals(revision, interpreter.contexts.last().projectGenesis?.revision)
        assertEquals(listOf(ConversationContextEpic(3, "Outcome 1")), interpreter.contexts.last().projectEpics)
        assertEquals(revision, workspace.snapshot(MESSAGE_READY).projectGenesis.first { it.projectId == 1 }.revision)
        val command = requireNotNull(proposed.projection).commands.single().proposal
        assertEquals(ConversationOperationStatus.RECORDED,
            service.admitCommand(command.commandId, AdmitConversationCommandRequest(command.hash)).status)
        val refreshed = compiler.compile(store.events(), conversationId, objective.objectiveId, registry.descriptors())
        assertEquals("ARCHITECTURE", refreshed.projectGenesis?.phase)
        assertEquals(revision.revision + 1, refreshed.projectGenesis?.revision?.revision)
        assertTrue(refreshed.projectGenesis?.revision?.hash != revision.hash)
        assertNull(compiler.compile(store.events(), conversationId, null, registry.descriptors()).projectGenesis)
    }

    @Test
    fun `small onboarding turn admits actual context demand instead of full aperture`() = runBlocking {
        var admittedInputTokens = 0
        var executionContextTokens = 0
        var executionPrompt = ""
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
                executionPrompt = prompt
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
        assertTrue(executionPrompt.contains("The top-level keys are exactly speechAct, response, objective, capabilityId, payloadJson, commandId, and commandHash."))
        assertTrue(executionPrompt.contains("Never return a top-level payload key or a nested payload object."))
        assertTrue(executionPrompt.contains("A credential-free HTTP(S) Git URL is sufficient for ONBOARD_REPOSITORY"))
        assertTrue(executionPrompt.contains("A domain action does not require a separate objective"))
        assertTrue(executionPrompt.contains("A valid objective response has this exact outer shape"))
        assertTrue(executionPrompt.contains("\"objective\":{\"projectId\":5,\"title\":\"Prevent background model starvation\""))
        assertTrue(executionPrompt.contains("Never return PROPOSE_OBJECTIVE with objective set to null."))
        assertTrue(executionPrompt.contains("The first output character must be { and the last output character must be }."))
        assertTrue(executionPrompt.contains("\"payloadJson\":\"{\\\"source\\\":\\\"GIT_URL\\\""))
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
        val context = ConversationContextCompiler().compile(
            store.events(), conversationId, objective.objectiveId, listOf(capability.descriptor),
        )
        assertEquals(commandView.executions.last().downstreamHash, context.commandStates.single().downstreamHash)
        assertTrue(context.commandStates.single().downstreamHash != command.hash)
    }

    @Test
    fun `advisory remediation cannot admit an exact pending mutation`() = runBlocking {
        val store = TransientConversationStore()
        val executions = AtomicInteger()
        val capability = testCapability("MUTATE_TEST", mutation = true) {
            executions.incrementAndGet()
            ConversationCapabilityResult(true, "Mutation correlated.", "TEST_AUTHORITY", "42", HASH)
        }
        val calls = AtomicInteger()
        val interpreter = ConversationInterpreter { context, _ ->
            val interpretation = when (calls.getAndIncrement()) {
                0 -> objectiveTurn("Evidence remediation")
                1 -> commandTurn("MUTATE_TEST")
                else -> {
                    val command = context.commandStates.single()
                    ConversationInterpretation(
                        speechAct = SPEECH_ADMIT_DOMAIN_ACTION,
                        response = "Admitting from repository-derived remediation text.",
                        commandId = command.commandId,
                        commandHash = command.commandHash,
                    )
                }
            }
            InterpretedConversationTurn(interpretation)
        }
        val service = service(store, interpreter, capability)
        val conversationId = requireNotNull(service.create(CreateConversationRequest("Advisory boundary")).projection)
            .conversation.conversationId

        val proposedObjective = service.submitMessage(
            conversationId,
            SubmitConversationMessageRequest("client-objective-0001", 1, "Create remediation objective"),
        )
        assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED, proposedObjective.status)
        val objective = requireNotNull(proposedObjective.projection).objectives.single()
        val objectiveSource = proposedObjective.projection.messages.single { it.clientMessageId == "client-objective-0001" }
        assertEquals(ConversationOperationStatus.RECORDED, service.controlObjective(
            objective.objectiveId,
            ControlConversationObjectiveRequest(OBJECTIVE_CONTROL_ADMIT, objectiveSource.messageId, objectiveSource.hash),
        ).status)
        val beforeProposal = requireNotNull(service.projection(conversationId))
        val proposed = service.submitMessage(
            conversationId,
            SubmitConversationMessageRequest(
                "client-proposal-0001",
                beforeProposal.messages.size + 1L,
                "Propose mutation",
                objective.objectiveId,
            ),
        )
        assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED, proposed.status)
        val command = requireNotNull(proposed.projection).commands.single().proposal
        val beforeAdvisory = requireNotNull(service.projection(conversationId))
        val advisory = service.submitMessage(
            conversationId,
            SubmitConversationMessageRequest(
                "client-advisory-0001",
                beforeAdvisory.messages.size + 1L,
                "Plan evidence remediation without admission.",
                objective.objectiveId,
                intent = MESSAGE_INTENT_ADVISORY,
            ),
        )

        assertEquals(ConversationOperationStatus.REJECTED, advisory.status)
        assertTrue(advisory.diagnostic.contains("Advisory messages cannot admit"))
        assertEquals(0, executions.get())
        val unchanged = requireNotNull(service.projection(conversationId)).commands.single()
        assertNull(unchanged.admission)
        assertEquals(command.hash, unchanged.proposal.hash)
    }

    @Test
    fun `advisory remediation cannot revise canonical objective authority`() = runBlocking {
        val interpreter = QueueInterpreter(
            objectiveTurn("Evidence remediation"),
            ConversationInterpretation(
                speechAct = SPEECH_REVISE_OBJECTIVE,
                response = "Replacing objective from repository-derived remediation text.",
                objective = ObjectiveCandidate(
                    title = "Injected replacement",
                    outcome = "Replace canonical objective authority",
                ),
            ),
        )
        val service = service(TransientConversationStore(), interpreter)
        val conversationId = requireNotNull(service.create(CreateConversationRequest("Advisory revision boundary")).projection)
            .conversation.conversationId
        val proposed = service.submitMessage(
            conversationId,
            SubmitConversationMessageRequest("client-objective-0001", 1, "Create remediation objective"),
        )
        val objective = requireNotNull(proposed.projection).objectives.single()
        val source = proposed.projection.messages.single { it.clientMessageId == "client-objective-0001" }
        assertEquals(ConversationOperationStatus.RECORDED, service.controlObjective(
            objective.objectiveId,
            ControlConversationObjectiveRequest(OBJECTIVE_CONTROL_ADMIT, source.messageId, source.hash),
        ).status)
        val admitted = requireNotNull(service.projection(conversationId)).objectives.single()
        val beforeAdvisory = requireNotNull(service.projection(conversationId))

        val advisory = service.submitMessage(
            conversationId,
            SubmitConversationMessageRequest(
                "client-advisory-revision-0001",
                beforeAdvisory.messages.size + 1L,
                "Plan evidence remediation without changing the objective.",
                admitted.objectiveId,
                intent = MESSAGE_INTENT_ADVISORY,
            ),
        )

        assertEquals(ConversationOperationStatus.REJECTED, advisory.status)
        val unchanged = requireNotNull(service.projection(conversationId)).objectives.single()
        assertEquals(admitted.revision, unchanged.revision)
        assertEquals(admitted.state, unchanged.state)
        assertEquals(admitted.hash, unchanged.hash)
        assertEquals(admitted.title, unchanged.title)
    }

    @Test
    fun `capability preflight reports existing authority without proposing duplicate mutation`() = runBlocking {
        val store = TransientConversationStore()
        val executions = AtomicInteger()
        val capability = object : ConversationCapability {
            override val descriptor = ConversationCapabilityDescriptor(
                "EXISTING_TEST",
                "Existing authority",
                true,
                "{}",
                emptySet(),
                "TestService",
                "EXACT_COMMAND_HASH",
                "TEST_AUTHORITY",
                "CANONICAL_SOURCE",
            )

            override suspend fun preflight(
                payloadJson: String,
                objective: ConversationObjectiveRevision?,
            ) = ConversationCapabilityResult(true, "Already exists. Next: inspect it.", "TEST_AUTHORITY", "42", HASH)

            override suspend fun execute(
                payloadJson: String,
                objective: ConversationObjectiveRevision?,
                command: ConversationCommandProposal,
            ): ConversationCapabilityResult {
                executions.incrementAndGet()
                return ConversationCapabilityResult(true, "Unexpected execution.", "TEST_AUTHORITY", "42", HASH)
            }
        }
        val service = service(store, QueueInterpreter(commandTurn("EXISTING_TEST")), capability)
        val conversationId = requireNotNull(service.create(CreateConversationRequest("Existing authority")).projection)
            .conversation.conversationId

        val result = service.submitMessage(
            conversationId,
            SubmitConversationMessageRequest("client-existing-0001", 1, "Do it again"),
        )

        assertEquals(ConversationOperationStatus.RECORDED, result.status)
        assertEquals(0, executions.get())
        val projection = requireNotNull(result.projection)
        assertTrue(projection.commands.isEmpty())
        assertEquals("Already exists. Next: inspect it.", projection.messages.last().content)
        assertEquals("42", projection.activities.single().authorityId)
    }

    @Test
    fun `pending command rejection is durable and prevents later admission`() = runBlocking {
        val store = TransientConversationStore()
        val executions = AtomicInteger()
        val capability = object : ConversationCapability {
            override val descriptor = ConversationCapabilityDescriptor(
                "REJECT_TEST",
                "Reject test",
                true,
                "{}",
                emptySet(),
                "TestService",
                "EXACT_COMMAND_HASH",
                "TEST_AUTHORITY",
                "TEST_KEY",
            )

            override suspend fun execute(
                payloadJson: String,
                objective: ConversationObjectiveRevision?,
                command: ConversationCommandProposal,
            ): ConversationCapabilityResult {
                executions.incrementAndGet()
                return ConversationCapabilityResult(true, "Unexpected execution.", "TEST_AUTHORITY", "42", HASH)
            }
        }
        val service = service(store, QueueInterpreter(commandTurn("REJECT_TEST")), capability)
        val conversationId = requireNotNull(service.create(CreateConversationRequest("Reject proposal")).projection)
            .conversation.conversationId
        val proposed = service.submitMessage(
            conversationId,
            SubmitConversationMessageRequest("client-reject-0001", 1, "Propose it"),
        )
        val command = requireNotNull(proposed.projection).commands.single().proposal

        val rejected = service.rejectCommand(command.commandId, RejectConversationCommandRequest(command.hash))

        assertEquals(ConversationOperationStatus.RECORDED, rejected.status)
        assertEquals(COMMAND_REJECTED, requireNotNull(rejected.projection).commands.single().executions.last().state)
        assertEquals("Dismissed REJECT_TEST proposal ${command.commandId}.", rejected.projection.messages.last().content)
        assertEquals(ConversationOperationStatus.REJECTED, service.admitCommand(
            command.commandId,
            AdmitConversationCommandRequest(command.hash),
        ).status)
        assertEquals(0, executions.get())
        assertEquals(ConversationOperationStatus.ALREADY_RECORDED, service.rejectCommand(
            command.commandId,
            RejectConversationCommandRequest(command.hash),
        ).status)
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
    fun `canonical conversation title is included in bounded context`() = runBlocking {
        val store = TransientConversationStore()
        val contexts = mutableListOf<ConversationContextManifest>()
        val service = service(store, ConversationInterpreter { context, _ ->
            contexts += context
            InterpretedConversationTurn(ConversationInterpretation(SPEECH_DISCUSS, "Scoped reply."))
        })
        val title = "Project 42 ticket 7: Build the inbox"
        val conversationId = requireNotNull(service.create(CreateConversationRequest(title)).projection)
            .conversation.conversationId

        assertEquals(ConversationOperationStatus.RECORDED, service.submitMessage(
            conversationId,
            SubmitConversationMessageRequest("canonical-title-0001", 1, "What is blocking this ticket?"),
        ).status)

        assertEquals(title, contexts.single().conversationTitle)
    }

    @Test
    fun `explicit standalone onboarding isolates stale conversation history`() = runBlocking {
        val store = TransientConversationStore()
        val contexts = mutableListOf<ConversationContextManifest>()
        val interpreter = ConversationInterpreter { context, _ ->
            contexts += context
            InterpretedConversationTurn(ConversationInterpretation(SPEECH_DISCUSS, "Recorded."))
        }
        val onboardingCapability = testCapability(CAPABILITY_ONBOARD_REPOSITORY, mutation = true) {
            ConversationCapabilityResult(true, "Onboarded.", "REPOSITORY_ONBOARDING", "1", HASH)
        }
        val service = service(store, interpreter, onboardingCapability)
        val conversationId = requireNotNull(service.create(CreateConversationRequest("Polluted context")).projection)
            .conversation.conversationId

        repeat(13) { index ->
            val expectedSequence = requireNotNull(service.projection(conversationId)).messages.size + 1L
            assertEquals(ConversationOperationStatus.RECORDED, service.submitMessage(
                conversationId,
                SubmitConversationMessageRequest(
                    "client-pollution-${index.toString().padStart(4, '0')}",
                    expectedSequence,
                    "Failed onboarding attempt $index",
                ),
            ).status)
        }
        assertTrue(requireNotNull(service.projection(conversationId)).summaries.isNotEmpty())

        val expectedSequence = requireNotNull(service.projection(conversationId)).messages.size + 1L
        assertEquals(ConversationOperationStatus.RECORDED, service.submitMessage(
            conversationId,
            SubmitConversationMessageRequest(
                "client-isolated-onboarding-0001",
                expectedSequence,
                "Would you mind onboarding: https://github.com/alchevrier/autumn",
            ),
        ).status)

        assertEquals(listOf("Would you mind onboarding: https://github.com/alchevrier/autumn"), contexts.last().recentMessages.map { it.content })
        assertTrue(contexts.last().summaries.isEmpty())
        assertTrue(contexts.last().capabilities.any { it.id == CAPABILITY_ONBOARD_REPOSITORY })
    }

    @Test
    fun `invented and assistant supplied import sources are rejected before preflight`() = runBlocking {
        val location = "https://github.com/example/spring-boot-h2-content-lookup.git"
        for (request in listOf(
            "Create a Spring Boot H2 application from nothing with unit and integration tests.",
            "Import $location-other instead.",
            "Import \"$location-other\" instead.",
        )) {
            val store = TransientConversationStore()
            val baseCapability = testCapability(CAPABILITY_ONBOARD_REPOSITORY, true, emptySet()) {
                error("An unsupplied repository must not execute")
            }
            val capability = object : ConversationCapability by baseCapability {
                override suspend fun preflight(
                    payloadJson: String,
                    objective: ConversationObjectiveRevision?,
                ): ConversationCapabilityResult? = error("An unsupplied repository must not reach preflight")
            }
            val service = service(store, QueueInterpreter(
                ConversationInterpretation(SPEECH_DISCUSS, "Consider $location"),
                commandTurn(CAPABILITY_ONBOARD_REPOSITORY).copy(
                    payloadJson = Json.encodeToString(OnboardRepositoryCapabilityPayload("GIT_URL", location, "Example")),
                ),
            ), capability)
            val conversationId = requireNotNull(service.create(CreateConversationRequest("Greenfield intake")).projection)
                .conversation.conversationId
            service.submitMessage(conversationId, SubmitConversationMessageRequest("initial-request", 1, request))

            val result = service.submitMessage(
                conversationId,
                SubmitConversationMessageRequest("continue-request", 3, "Continue with the original request."),
            )

            assertEquals(ConversationOperationStatus.REJECTED, result.status)
            assertTrue(result.diagnostic.contains("GREENFIELD_LOCAL"))
            assertTrue(requireNotNull(result.projection).commands.isEmpty())
            assertEquals(result.diagnostic, result.projection.messages.last().content)
        }
    }

    @Test
    fun `previously supplied exact import sources still require admission`() = runBlocking {
        for ((sourceType, location) in listOf(
            "GIT_URL" to "https://example.com/acme/repository.git",
            "LOCAL_FOLDER" to "/tmp/a repository with spaces",
        )) {
            val store = TransientConversationStore()
            val executions = AtomicInteger()
            val capability = testCapability(CAPABILITY_ONBOARD_REPOSITORY, true, emptySet()) {
                executions.incrementAndGet()
                ConversationCapabilityResult(true, "Onboarded.", "REPOSITORY_ONBOARDING", "1", HASH)
            }
            val service = service(store, QueueInterpreter(
                ConversationInterpretation(SPEECH_DISCUSS, "Ready to propose the import."),
                commandTurn(CAPABILITY_ONBOARD_REPOSITORY).copy(
                    payloadJson = Json.encodeToString(OnboardRepositoryCapabilityPayload(sourceType, location, "Example")),
                ),
            ), capability)
            val conversationId = requireNotNull(service.create(CreateConversationRequest("Existing import")).projection)
                .conversation.conversationId
            service.submitMessage(conversationId, SubmitConversationMessageRequest("source-request", 1, "Import \"$location\"."))

            val proposed = service.submitMessage(
                conversationId,
                SubmitConversationMessageRequest("continue-request", 3, "Propose that import."),
            )

            assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED, proposed.status)
            assertEquals(0, executions.get())
            val command = requireNotNull(proposed.projection).commands.single().proposal
            val admitted = service.admitCommand(command.commandId, AdmitConversationCommandRequest(command.hash))
            assertEquals(ConversationOperationStatus.RECORDED, admitted.status)
            assertEquals(1, executions.get())
            assertEquals(COMMAND_CORRELATED, requireNotNull(admitted.projection).commands.single().executions.last().state)
        }
    }

    @Test
    fun `restored unsafe imports cannot be admitted or dispatched using later source messages`() = runBlocking {
        val location = "https://github.com/example/spring-boot-h2-content-lookup.git"
        for (alreadyAdmitted in listOf(false, true)) {
            val store = TransientConversationStore()
            val capability = testCapability(CAPABILITY_ONBOARD_REPOSITORY, true, emptySet()) {
                error("A restored unsupplied import must not execute")
            }
            val service = service(store, QueueInterpreter(
                ConversationInterpretation(SPEECH_DISCUSS, "Recorded."),
                ConversationInterpretation(SPEECH_DISCUSS, "Recorded later source."),
            ), capability)
            val initial = requireNotNull(service.create(CreateConversationRequest("Restored greenfield request")).projection)
            val conversationId = initial.conversation.conversationId
            val submitted = service.submitMessage(
                conversationId,
                SubmitConversationMessageRequest("original-request", 1, "Build a new product from nothing."),
            )
            val source = requireNotNull(submitted.projection).messages.first()
            val command = newConversationCommand(ConversationCommandProposal(
                commandId = 1,
                conversationId = conversationId,
                sourceMessageId = source.messageId,
                sourceMessageHash = source.hash,
                capabilityId = CAPABILITY_ONBOARD_REPOSITORY,
                payloadJson = Json.encodeToString(OnboardRepositoryCapabilityPayload("GIT_URL", location, "Example")),
                mutation = true,
                proposedAt = NOW,
                hash = "",
            ))
            store.appendCommand(command)
            if (alreadyAdmitted) {
                store.appendAdmission(newConversationAdmission(ConversationCommandAdmission(1, command.commandId, command.hash, "HUMAN", NOW, "")))
            }
            service.submitMessage(conversationId, SubmitConversationMessageRequest("later-source", 3, "Here is $location"))
            val restored = service(store, QueueInterpreter(), capability)

            val result = restored.admitCommand(command.commandId, AdmitConversationCommandRequest(command.hash))

            assertEquals(ConversationOperationStatus.REJECTED, result.status)
            assertTrue(result.diagnostic.contains("before this proposal"))
            val view = requireNotNull(result.projection).commands.single()
            assertEquals(alreadyAdmitted, view.admission != null)
            if (alreadyAdmitted) {
                assertEquals(COMMAND_FAILED, view.executions.single().state)
                assertEquals(result.diagnostic, view.executions.single().diagnostic)
            } else {
                assertTrue(view.executions.isEmpty())
            }
        }
    }

    @Test
    fun `new project payloads are validated before admission without creating workspace state`() = runBlocking {
        val workspace = WorkspaceStore()
        val store = TransientConversationStore()
        val payload = CreateWorkItemCapabilityPayload(
            entityTypeId = 1,
            title = "Spring Boot H2 Content Lookup",
            content = "Persist Hello World at ID 1 in H2; deliver HTTP lookup and unit and integration tests.",
        )
        val validPayload = Json.encodeToString(payload)
        val invalidPayload = validPayload.dropLast(1) + ",\"epicId\":null,\"storyId\":null}"
        val service = ConversationConductorService(
            store,
            QueueInterpreter(
                objectiveTurn("Build a new content service"),
                commandTurn(CAPABILITY_CREATE_WORK_ITEM).copy(payloadJson = invalidPayload),
                commandTurn(CAPABILITY_CREATE_WORK_ITEM).copy(payloadJson = validPayload),
            ),
            defaultConversationCapabilities(workspace),
            now = { NOW },
        )
        val conversationId = requireNotNull(service.create(CreateConversationRequest("From nothing")).projection)
            .conversation.conversationId
        val proposed = requireNotNull(service.submitMessage(
            conversationId, SubmitConversationMessageRequest("initial-goal", 1, payload.content),
        ).projection)
        val objective = proposed.objectives.single()
        val source = proposed.messages.first()
        service.controlObjective(
            objective.objectiveId,
            ControlConversationObjectiveRequest(OBJECTIVE_CONTROL_ADMIT, source.messageId, source.hash),
        )

        val rejected = service.submitMessage(
            conversationId, SubmitConversationMessageRequest("invalid-project", 3, "Create the project.", objective.objectiveId),
        )

        assertEquals(ConversationOperationStatus.REJECTED, rejected.status)
        assertTrue(rejected.diagnostic.contains("before admission"))
        assertTrue(requireNotNull(rejected.projection).commands.isEmpty())
        assertEquals(0, workspace.entityCount)

        val corrected = service.submitMessage(
            conversationId, SubmitConversationMessageRequest("valid-project", 5, "Propose the corrected project.", objective.objectiveId),
        )
        assertEquals(ConversationOperationStatus.ADMISSION_REQUIRED, corrected.status)
        assertEquals(0, workspace.entityCount)
        val command = requireNotNull(corrected.projection).commands.single().proposal
        val admitted = service.admitCommand(command.commandId, AdmitConversationCommandRequest(command.hash))
        assertEquals(ConversationOperationStatus.RECORDED, admitted.status)
        assertEquals(1, workspace.entityCount)
        assertEquals(payload.content, workspace.entityAt(0).content)
        assertTrue(workspace.snapshot(com.orchard.backend.workspace.MESSAGE_READY).repositories.isEmpty())
        assertEquals(1, service.projectWorkspaceActivity(workspace))
        val bound = requireNotNull(service.projection(conversationId)).objectives.single()
        assertEquals(workspace.entityAt(0).id, bound.projectId)
        assertEquals(OBJECTIVE_READY, bound.state)
        assertEquals(objective.outcome, bound.outcome)
        assertEquals(objective.constraints, bound.constraints)
        assertEquals(objective.sourceMessageHash, bound.sourceMessageHash)
        assertEquals("ORCHARD_PROJECTION", bound.actor)
        assertEquals(0, service.projectWorkspaceActivity(workspace))
        assertEquals(com.orchard.backend.workspace.ProjectArchiveStatus.ARCHIVED, workspace.archiveProject(requireNotNull(bound.projectId)).status)
        assertEquals(1, service.projectWorkspaceActivity(workspace))
        val archivedObjective = requireNotNull(service.projection(conversationId)).objectives.single()
        assertEquals(OBJECTIVE_CANCELLED, archivedObjective.state)
        assertEquals(objective.sourceMessageHash, archivedObjective.sourceMessageHash)
        assertEquals(0, service.projectWorkspaceActivity(workspace))
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

    @Test
    fun `candidate review and correction activities project once into their correlated conversation`() {
        val store = TransientConversationStore()
        val conversation = newConversation(Conversation(1, "Candidate review", "HUMAN", NOW, ""))
        val message = newConversationMessage(ConversationMessage(
            1, 1, 1, "client-candidate-0001", MESSAGE_ROLE_USER, "Deliver the change.", actor = "HUMAN", createdAt = NOW, hash = "",
        ))
        val objective = newConversationObjective(ConversationObjectiveRevision(
            1, 1, 1, 1, "Delivery", "Review one candidate.", state = OBJECTIVE_ACTIVE,
            sourceMessageId = message.messageId, sourceMessageHash = message.hash, actor = "HUMAN", createdAt = NOW, hash = "",
        ))
        val command = newConversationCommand(ConversationCommandProposal(
            1, 1, objective.objectiveId, message.messageId, message.hash, "START_WORKFLOW", "{\"workItemId\":4}", true, NOW, "",
        ))
        store.appendConversation(conversation)
        store.appendMessage(message)
        store.appendObjective(objective)
        store.appendCommand(command)
        store.appendAdmission(newConversationAdmission(ConversationCommandAdmission(1, 1, command.hash, "HUMAN", NOW, "")))
        store.appendExecution(newConversationExecution(ConversationCommandExecution(
            1, 1, command.hash, COMMAND_CORRELATED, "WORKFLOW_RUN", "42", HASH, recordedAt = NOW, hash = "",
        )))
        val pullRequestDraft = CandidatePullRequest(
            1, runId = 42, workPackageId = 3, workPackageHash = "a".repeat(64), baseRevision = "b".repeat(40),
            candidateRevision = "c".repeat(40), changedPaths = listOf("src/Main.kt"), implementationClaims = listOf("The change works."),
            checks = listOf("./gradlew test"), evidence = listOf(CandidatePullRequestEvidence("TEST", "./gradlew test", true, HASH, "Passed.")),
            deviations = emptyList(), createdAt = NOW, hash = "",
        )
        val pullRequest = pullRequestDraft.copy(hash = candidatePullRequestHash(pullRequestDraft))
        val finding = CandidatePullRequestReviewFinding(
            "Accepted behavior", "The candidate needs a repair.", "BLOCKER", REVIEW_CORRECTION_CANDIDATE_REPAIR, listOf(HASH),
        )
        val review = newCandidatePullRequestReview(1, pullRequest, CANDIDATE_REVIEW_INTENT, "intent-reviewer", listOf(finding))
        val correction = newCandidatePullRequestCorrection(1, review, pullRequest, REVIEW_CORRECTION_CANDIDATE_REPAIR, listOf(finding))
        val service = service(store, QueueInterpreter())

        assertEquals(2, service.projectCandidatePullRequestActivity(listOf(pullRequest), listOf(review), listOf(correction)))
        val activities = requireNotNull(service.projection(conversation.conversationId)).activities
        assertEquals(listOf("CANDIDATE_PR_REVIEW", "CANDIDATE_PR_CORRECTION"), activities.map { it.authorityType })
        assertEquals(listOf(review.hash, correction.hash), activities.map { it.authorityHash })
        assertEquals(0, service.projectCandidatePullRequestActivity(listOf(pullRequest), listOf(review), listOf(correction)))
        assertEquals(2, requireNotNull(service.projection(conversation.conversationId)).activities.size)
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
        allowedObjectiveStates: Set<String> = setOf(OBJECTIVE_READY, OBJECTIVE_ACTIVE),
        execute: suspend () -> ConversationCapabilityResult,
    ) = object : ConversationCapability {
        override val descriptor = ConversationCapabilityDescriptor(
            id,
            "Test capability",
            mutation,
            "{}",
            allowedObjectiveStates,
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
        val contexts = mutableListOf<ConversationContextManifest>()

        override suspend fun interpret(
            context: ConversationContextManifest,
            message: ConversationMessage,
        ): InterpretedConversationTurn {
            calls.incrementAndGet()
            contexts += context
            return InterpretedConversationTurn(pending.removeFirst())
        }
    }

    private companion object {
        const val NOW = "2026-06-21T00:00:00Z"
        const val HASH = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val GIT_HASH = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}