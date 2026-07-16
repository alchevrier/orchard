package com.orchard.backend

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.FileRepositoryBindingStore
import com.orchard.backend.workspace.FileWorkflowMemoryStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.FileWorkDefinitionStore
import com.orchard.backend.workspace.FileDefinitionCollaborationStore
import com.orchard.backend.workspace.FileModelExperienceStore
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.WorkflowStartStatus
import com.orchard.backend.workspace.WorkEpisode
import com.orchard.backend.workspace.AttemptSubmission
import com.orchard.backend.workspace.EvidenceSubmission
import com.orchard.backend.workspace.RUN_STATE_DONE
import com.orchard.backend.workspace.RUN_STATE_EVIDENCE_BLOCKED
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.WorkspaceEntity
import com.orchard.backend.workspace.WorkspaceRepository
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.DEFINITION_READY
import com.orchard.backend.workspace.COLLABORATOR_LOCAL_LLM
import com.orchard.backend.workspace.DefinitionExecutionProvenance
import com.orchard.backend.workspace.DefinitionProposalContent
import com.orchard.backend.workspace.DefinitionCollaborationEvent
import com.orchard.backend.workspace.DefinitionFeedback
import com.orchard.backend.workspace.newDefinitionProposal
import com.orchard.backend.workspace.ModelExecutionObservationDraft
import com.orchard.backend.workspace.ModelExecutionObservation
import com.orchard.backend.workspace.ModelExperienceEvent
import com.orchard.backend.vector.DefaultModelExecutionProfiles
import com.orchard.backend.vector.ModelBindingProfile
import com.orchard.backend.vector.MODEL_CAPABILITY_STRICT_JSON
import com.orchard.backend.vector.modelBindingFingerprint
import com.orchard.backend.vector.FileModelProfileSettingsStore
import com.orchard.backend.vector.ModelProfileOverride
import com.orchard.backend.resource.FileMachineUsagePolicyStore
import com.orchard.backend.resource.MachineUsagePolicy
import com.orchard.backend.workspace.FileStagedDeliveryPlanStore
import com.orchard.backend.workspace.StagedDeliveryPlanSubmission
import com.orchard.backend.workspace.StagedPlanNodeSubmission
import com.orchard.backend.workspace.StagedPlanStageSubmission
import com.orchard.backend.workspace.StagedPlanStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class WorkspaceRepositoryTest {
    @Test
    fun stagedDeliveryCircuitRecoversAfterRestart() = withTempDirectory { directory ->
        val first = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            stagedPlanStore = FileStagedDeliveryPlanStore(directory),
        )
        first.beginBatch()
        assertTrue(first.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(first.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(first.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(first.applyIntent(intent(ENTITY_TASK, "Contract", projectId = 1, epicId = 2, storyId = 3)))
        assertTrue(first.applyIntent(intent(ENTITY_TASK, "Implementation", projectId = 1, epicId = 2, storyId = 3)))
        first.commitBatch()
        val accepted = first.acceptStagedPlan(
            StagedDeliveryPlanSubmission(
                3,
                "Recovered circuit",
                listOf(
                    StagedPlanStageSubmission(
                        "design", "Design", "contract-design-v1",
                        nodes = listOf(StagedPlanNodeSubmission("contract", 4)),
                    ),
                    StagedPlanStageSubmission(
                        "build", "Build", "parallel-implementation-v1",
                        nodes = listOf(StagedPlanNodeSubmission("implementation", 5, dependsOn = listOf("contract"))),
                    ),
                ),
            )
        )
        val original = accepted.snapshot.stagedPlans.single().plan

        val recovered = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            stagedPlanStore = FileStagedDeliveryPlanStore(directory),
        ).snapshot(MESSAGE_READY).stagedPlans.single().plan

        assertEquals(StagedPlanStatus.ACCEPTED, accepted.status)
        assertEquals(original.hash, recovered.hash)
        assertEquals(listOf("1a", "2a"), recovered.stages.flatMap { it.nodes }.map { it.label })
        assertTrue(Files.readString(directory.resolve("staged-delivery-plans.jsonl")).contains("\"checksum\""))
    }

    @Test
    fun stagedDeliveryCircuitRecoversHistoricalRevisionAfterHierarchyGrowth() = withTempDirectory { directory ->
        val repository = FileWorkspaceRepository(directory)
        val store = FileStagedDeliveryPlanStore(directory)
        val first = WorkspaceStore(repository = repository, stagedPlanStore = store)
        first.beginBatch()
        assertTrue(first.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(first.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(first.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(first.applyIntent(intent(ENTITY_TASK, "Contract", projectId = 1, epicId = 2, storyId = 3)))
        assertTrue(first.applyIntent(intent(ENTITY_TASK, "Implementation", projectId = 1, epicId = 2, storyId = 3)))
        first.commitBatch()
        val revision1 = first.acceptStagedPlan(
            StagedDeliveryPlanSubmission(
                3,
                "Initial circuit",
                listOf(
                    StagedPlanStageSubmission(
                        "design", "Design", "contract-design-v1",
                        nodes = listOf(StagedPlanNodeSubmission("contract", 4)),
                    ),
                    StagedPlanStageSubmission(
                        "build", "Build", "parallel-implementation-v1",
                        nodes = listOf(StagedPlanNodeSubmission("implementation", 5, dependsOn = listOf("contract"))),
                    ),
                ),
            )
        ).snapshot.stagedPlans.single().plan

        first.beginBatch()
        assertTrue(first.applyIntent(intent(ENTITY_TASK, "Integration", projectId = 1, epicId = 2, storyId = 3)))
        first.commitBatch()
        val staleRecovery = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            stagedPlanStore = FileStagedDeliveryPlanStore(directory),
        ).snapshot(MESSAGE_READY).stagedPlans.single()
        assertEquals(1, staleRecovery.plan.revision)
        assertTrue(staleRecovery.nodes.all { it.blockedReason.contains("stale") })
        val revision2 = first.acceptStagedPlan(
            StagedDeliveryPlanSubmission(
                3,
                "Expanded circuit",
                listOf(
                    StagedPlanStageSubmission(
                        "design", "Design", "contract-design-v1",
                        nodes = listOf(StagedPlanNodeSubmission("contract", 4)),
                    ),
                    StagedPlanStageSubmission(
                        "build", "Build", "parallel-implementation-v1",
                        nodes = listOf(StagedPlanNodeSubmission("implementation", 5, dependsOn = listOf("contract"))),
                    ),
                    StagedPlanStageSubmission(
                        "join", "Join", "integration-v1",
                        nodes = listOf(StagedPlanNodeSubmission("integration", 6, dependsOn = listOf("implementation"))),
                    ),
                ),
                baseRevision = revision1.revision,
                baseHash = revision1.hash,
            )
        )
        assertEquals(StagedPlanStatus.ACCEPTED, revision2.status)

        val recovered = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            stagedPlanStore = FileStagedDeliveryPlanStore(directory),
        ).snapshot(MESSAGE_READY).stagedPlans

        assertEquals(1, recovered.size)
        assertEquals(2, recovered.single().plan.revision)
        assertEquals(listOf(4, 5, 6), recovered.single().plan.stages.flatMap { it.nodes }.map { it.workItemId })
    }

    @Test
    fun legacyModelExperienceWithoutResourceEvidenceKeepsItsChecksum() = withTempDirectory { directory ->
        val execution = ModelExecutionObservation(
            executionId = 1,
            profile = DefaultModelExecutionProfiles.boundedDefinitionReasoning,
            binding = ModelBindingProfile(
                "legacy",
                "test",
                "model",
                14_000,
                setOf(MODEL_CAPABILITY_STRICT_JSON),
            ),
            workflowStepId = "DEFINE_TASK",
            workItemId = 4,
            envelopeHash = "a".repeat(64),
            promptHash = "b".repeat(64),
            outputHash = "c".repeat(64),
            inputTokens = 1_000,
            outputTokens = 200,
            latencyMillis = 500,
            schemaValid = true,
            recordedAt = "2026-07-15T00:00:00Z",
        )
        val json = Json { encodeDefaults = true }
        val payload = json.encodeToString(ModelExperienceEvent(1, execution = execution))
        assertTrue("resourceAdmission" !in payload)
        val checksum = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray())
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        Files.writeString(
            directory.resolve("model-experience.jsonl"),
            """{"version":1,"value":$payload,"checksum":"$checksum"}
""",
        )

        val recovered = FileModelExperienceStore(directory).loadEvents().single().execution

        assertEquals(1, recovered?.executionId)
        assertEquals(null, recovered?.resourceAdmission)
    }

    @Test
    fun machineUsagePolicyRecoversUserDelegatedCapacity() = withTempDirectory { directory ->
        FileMachineUsagePolicyStore(directory).save(MachineUsagePolicy(20, 2_147_483_648, 3))

        val recovered = FileMachineUsagePolicyStore(directory).load()

        assertEquals(20, recovered.capacityPercent)
        assertEquals(2_147_483_648, recovered.minimumFreeMemoryBytes)
        assertEquals(3, recovered.maxConcurrentModelExecutions)
        assertTrue(Files.readString(directory.resolve("machine-usage-policy.json")).contains("\"checksum\""))
    }

    @Test
    fun modelProfileSettingsRecoverUserApertureAndPreferredBinding() = withTempDirectory { directory ->
        val first = FileModelProfileSettingsStore(directory)
        first.save(
            listOf(
                ModelProfileOverride(
                    "bounded-definition-reasoning-v1",
                    6_000,
                    1_000,
                    "ollama:phi3:mini",
                )
            )
        )

        val recovered = FileModelProfileSettingsStore(directory).load().single()

        assertEquals(6_000, recovered.inputBudgetTokens)
        assertEquals(1_000, recovered.outputBudgetTokens)
        assertEquals("ollama:phi3:mini", recovered.preferredBindingId)
        assertTrue(Files.readString(directory.resolve("model-profile-settings.json")).contains("\"checksum\""))
    }

    @Test
    fun modelProfilesDoNotMergeChangedBindingConfiguration() {
        val workspace = WorkspaceStore()
        listOf("42", "43").forEachIndexed { index, seed ->
            workspace.recordModelExecution(
                ModelExecutionObservationDraft(
                    DefaultModelExecutionProfiles.boundedDefinitionReasoning,
                    ModelBindingProfile(
                        "stable-name",
                        "test",
                        "same-model",
                        14_000,
                        setOf(MODEL_CAPABILITY_STRICT_JSON),
                        configuration = mapOf("seed" to seed),
                    ),
                    "DEFINE_TASK",
                    1,
                    ("${index + 1}".repeat(64)).take(64),
                    ("${index + 3}".repeat(64)).take(64),
                    ("${index + 5}".repeat(64)).take(64),
                    100,
                    20,
                    1,
                    true,
                )
            )
        }

        assertEquals(2, workspace.modelProfiles().size)
        assertEquals(setOf("42", "43"), workspace.modelProfiles().map { it.binding.configuration.getValue("seed") }.toSet())
    }

    @Test
    fun modelProfilesDoNotMergeDifferentEffectiveApertures() {
        val workspace = WorkspaceStore()
        listOf(6_000 to 1_000, 12_000 to 2_000).forEachIndexed { index, (input, output) ->
            workspace.recordModelExecution(
                ModelExecutionObservationDraft(
                    DefaultModelExecutionProfiles.boundedDefinitionReasoning.copy(
                        inputBudgetTokens = input,
                        outputBudgetTokens = output,
                    ),
                    ModelBindingProfile("binding", "test", "model", 20_000, setOf(MODEL_CAPABILITY_STRICT_JSON)),
                    "DEFINE_TASK",
                    1,
                    ("${index + 1}".repeat(64)).take(64),
                    ("${index + 3}".repeat(64)).take(64),
                    ("${index + 5}".repeat(64)).take(64),
                    100,
                    20,
                    1,
                    true,
                )
            )
        }

        assertEquals(setOf(6_000 to 1_000, 12_000 to 2_000), workspace.modelProfiles().map {
            it.inputBudgetTokens to it.outputBudgetTokens
        }.toSet())
    }

    @Test
    fun modelExperienceInteriorCorruptionFailsClosedWithoutDeletingLaterEvidence() = withTempDirectory { directory ->
        val store = FileModelExperienceStore(directory)
        val workspace = WorkspaceStore(modelExperienceStore = store)
        repeat(2) { index ->
            workspace.recordModelExecution(
                ModelExecutionObservationDraft(
                    DefaultModelExecutionProfiles.boundedDefinitionReasoning,
                    ModelBindingProfile("binding", "test", "model", 14_000, setOf(MODEL_CAPABILITY_STRICT_JSON)),
                    "DEFINE_TASK",
                    1,
                    ("${index + 1}".repeat(64)).take(64),
                    ("${index + 3}".repeat(64)).take(64),
                    ("${index + 5}".repeat(64)).take(64),
                    100,
                    20,
                    1,
                    true,
                )
            )
        }
        val path = directory.resolve("model-experience.jsonl")
        val original = Files.readAllLines(path)
        Files.write(path, listOf("{\"corrupt\":true}", original[1]))

        assertFailsWith<IllegalStateException> { store.loadEvents() }
        assertEquals(2, Files.readAllLines(path).size)
        Files.list(directory).use { paths ->
            assertTrue(paths.noneMatch { it.fileName.toString().startsWith("model-experience.corrupt-") })
        }
    }

    @Test
    fun modelProfileRebuildsFromExecutionAndSatisfactionAfterRestart() = withTempDirectory { directory ->
        val first = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            definitionStore = FileWorkDefinitionStore(directory),
            collaborationStore = FileDefinitionCollaborationStore(directory),
            modelExperienceStore = FileModelExperienceStore(directory),
        )
        first.beginBatch()
        assertTrue(first.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(first.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(first.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(first.applyIntent(intent(ENTITY_TASK, "Task", projectId = 1, epicId = 2, storyId = 3)))
        first.commitBatch()
        val execution = first.recordModelExecution(
            ModelExecutionObservationDraft(
                profile = DefaultModelExecutionProfiles.boundedDefinitionReasoning,
                binding = ModelBindingProfile(
                    "ollama:phi3:mini",
                    "ollama",
                    "phi3:mini",
                    131_072,
                    setOf(MODEL_CAPABILITY_STRICT_JSON),
                ),
                workflowStepId = "DEFINE_TASK",
                workItemId = 4,
                envelopeHash = "a".repeat(64),
                promptHash = "b".repeat(64),
                outputHash = "c".repeat(64),
                inputTokens = 900,
                outputTokens = 200,
                latencyMillis = 750,
                schemaValid = true,
            )
        )!!
        val proposal = first.recordDefinitionProposal(
            4,
            COLLABORATOR_LOCAL_LLM,
            DefinitionProposalContent(readyDefinition(), listOf("Observed behavior."), emptyList()),
            DefinitionExecutionProvenance(
                executor = "profile:bounded-definition-reasoning-v1",
                model = "phi3:mini",
                executionProfileId = DefaultModelExecutionProfiles.boundedDefinitionReasoning.id,
                bindingFingerprint = modelBindingFingerprint(execution.binding),
                promptVersion = 1,
                promptHash = execution.promptHash,
                contextHash = execution.envelopeHash,
                outputHash = requireNotNull(execution.outputHash),
                executionId = execution.executionId,
            ),
        ).proposal!!
        first.recordDefinitionFeedback(proposal.proposalId, "Keep compatibility.")
        first.acceptDefinitionProposal(
            proposal.proposalId,
            proposal.content.definition.copy(constraints = listOf("Keep compatibility")),
        )
        Files.writeString(directory.resolve("model-experience.jsonl"), "{\"truncated\"", StandardOpenOption.APPEND)

        val recovered = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            definitionStore = FileWorkDefinitionStore(directory),
            collaborationStore = FileDefinitionCollaborationStore(directory),
            modelExperienceStore = FileModelExperienceStore(directory),
        ).snapshot(0)

        val profile = recovered.modelProfiles.single()
        assertEquals(1, profile.sampleCount)
        assertEquals(1.0, profile.schemaValidityRate)
        assertEquals(1, profile.revisionRequestedCount)
        assertEquals(1, profile.acceptedAfterEditCount)
        assertEquals(1.0, profile.averageHumanRevisionFields)
        assertEquals(execution.executionId, recovered.definitionProposals.first().proposal.provenance?.executionId)
        assertEquals(1, Files.readAllLines(directory.resolve("model-experience.jsonl")).size)
        Files.list(directory).use { paths ->
            assertTrue(paths.anyMatch { it.fileName.toString().startsWith("model-experience.corrupt-") })
        }
    }

    @Test
    fun authorityJournalsQuarantineTruncatedTailsAndKeepAcceptedDefinition() = withTempDirectory { directory ->
        val store = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            definitionStore = FileWorkDefinitionStore(directory),
            collaborationStore = FileDefinitionCollaborationStore(directory),
        )
        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(store.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(store.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(store.applyIntent(intent(ENTITY_TASK, "Task", projectId = 1, epicId = 2, storyId = 3)))
        store.commitBatch()
        assertEquals(DEFINITION_READY, store.submitWorkDefinition(4, readyDefinition()).snapshot.workDefinitions.single().assessment.status)
        Files.writeString(directory.resolve("definition-collaboration.jsonl"), "{\"truncated\"", StandardOpenOption.APPEND)
        Files.writeString(directory.resolve("work-definitions.jsonl"), "{\"truncated\"", StandardOpenOption.APPEND)

        val recovered = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            definitionStore = FileWorkDefinitionStore(directory),
            collaborationStore = FileDefinitionCollaborationStore(directory),
        ).snapshot(0)

        assertEquals(1, recovered.definitionProposals.size)
        assertEquals(1, recovered.workDefinitions.size)
        assertEquals(recovered.definitionProposals.single().proposal.proposalId, recovered.workDefinitions.single().sourceProposal?.proposalId)
        assertEquals(1, Files.readAllLines(directory.resolve("definition-collaboration.jsonl")).size)
        assertEquals(1, Files.readAllLines(directory.resolve("work-definitions.jsonl")).size)
        Files.list(directory).use { paths ->
            val names = paths.map { it.fileName.toString() }.toList()
            assertTrue(names.any { it.startsWith("definition-collaboration.corrupt-") })
            assertTrue(names.any { it.startsWith("work-definitions.corrupt-") })
        }
    }

    @Test
    fun collaborationStoreRejectsLocalLlmFeedback() = withTempDirectory { directory ->
        val store = FileDefinitionCollaborationStore(directory)
        val proposal = newDefinitionProposal(
            proposalId = 1,
            workItemId = 1,
            revision = 1,
            parentProposalId = null,
            actor = "HUMAN",
            content = DefinitionProposalContent(readyDefinition(), emptyList(), emptyList()),
            provenance = null,
        )
        store.appendEvent(DefinitionCollaborationEvent(1, proposal = proposal))

        assertFailsWith<IllegalArgumentException> {
            store.appendEvent(
                DefinitionCollaborationEvent(
                    2,
                    feedback = DefinitionFeedback(2, proposal.proposalId, COLLABORATOR_LOCAL_LLM, "Unauthorized feedback."),
                )
            )
        }
    }

    @Test
    fun collaborativeDefinitionRecoversProposalFeedbackAndAcceptedSource() = withTempDirectory { directory ->
        val first = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            definitionStore = FileWorkDefinitionStore(directory),
            collaborationStore = FileDefinitionCollaborationStore(directory),
            modelExperienceStore = FileModelExperienceStore(directory),
        )
        first.beginBatch()
        assertTrue(first.applyIntent(intent(ENTITY_PROJECT, "Project")))
        assertTrue(first.applyIntent(intent(ENTITY_EPIC, "Epic", projectId = 1)))
        assertTrue(first.applyIntent(intent(ENTITY_STORY, "Story", projectId = 1, epicId = 2)))
        assertTrue(first.applyIntent(intent(ENTITY_TASK, "Task", projectId = 1, epicId = 2, storyId = 3)))
        first.commitBatch()
        val binding = ModelBindingProfile(
            "ollama:phi3:test",
            "ollama",
            "phi3:mini",
            8_000,
            setOf(MODEL_CAPABILITY_STRICT_JSON),
        )
        val execution = first.recordModelExecution(
            ModelExecutionObservationDraft(
                DefaultModelExecutionProfiles.boundedDefinitionReasoning,
                binding,
                "DEFINE_TASK",
                4,
                "b".repeat(64),
                "a".repeat(64),
                "c".repeat(64),
                100,
                50,
                1,
                true,
            )
        )!!
        val proposal = first.recordDefinitionProposal(
            4,
            COLLABORATOR_LOCAL_LLM,
            DefinitionProposalContent(
                readyDefinition(),
                observations = listOf("The current capability is absent."),
                assumptions = listOf("The existing boundary remains stable."),
            ),
            DefinitionExecutionProvenance(
                executor = "profile:bounded-definition-reasoning-v1",
                model = "phi3:mini",
                executionProfileId = DefaultModelExecutionProfiles.boundedDefinitionReasoning.id,
                bindingFingerprint = modelBindingFingerprint(binding),
                promptVersion = 1,
                promptHash = execution.promptHash,
                contextHash = execution.envelopeHash,
                outputHash = requireNotNull(execution.outputHash),
                executionId = execution.executionId,
            ),
        ).proposal!!
        first.recordDefinitionFeedback(proposal.proposalId, "Preserve the public contract.")
        first.acceptDefinitionProposal(proposal.proposalId)

        val recovered = WorkspaceStore(
            repository = FileWorkspaceRepository(directory),
            definitionStore = FileWorkDefinitionStore(directory),
            collaborationStore = FileDefinitionCollaborationStore(directory),
            modelExperienceStore = FileModelExperienceStore(directory),
        ).snapshot(0)

        assertEquals(1, recovered.definitionProposals.size)
        assertEquals("Preserve the public contract.", recovered.definitionProposals.single().feedback.single().content)
        assertEquals(proposal.hash, recovered.definitionProposals.single().proposal.hash)
        assertEquals(proposal.proposalId, recovered.workDefinitions.single().sourceProposal?.proposalId)
        assertEquals(recovered.workDefinitions.single().definitionId, recovered.definitionProposals.single().acceptedDefinitionId)
    }

    @Test
    fun recoversHierarchyAndContinuesMonotonicIdsAfterRestart() = withTempDirectory { directory ->
        val firstStore = WorkspaceStore(FileWorkspaceRepository(directory))
        firstStore.beginBatch()
        assertTrue(firstStore.applyIntent(intent(ENTITY_PROJECT, "Orchard")))
        assertTrue(firstStore.applyIntent(intent(ENTITY_EPIC, "Persistence", projectId = 1)))
        assertTrue(firstStore.applyIntent(intent(ENTITY_STORY, "Recover state", projectId = 1, epicId = 2)))
        assertTrue(firstStore.applyIntent(intent(ENTITY_TASK, "Replay journal", projectId = 1, epicId = 2, storyId = 3)))
        firstStore.commitBatch()

        val recoveredStore = WorkspaceStore(FileWorkspaceRepository(directory))
        assertEquals(4, recoveredStore.entityCount)
        assertEquals(listOf(1, 2, 3, 4), (0 until 4).map { recoveredStore.entityAt(it).id })
        assertEquals(listOf(0, 1, 2, 3), (0 until 4).map { recoveredStore.entityAt(it).parentId })

        recoveredStore.beginBatch()
        assertTrue(recoveredStore.applyIntent(intent(ENTITY_TASK, "Verify IDs", projectId = 1, epicId = 2, storyId = 3)))
        recoveredStore.commitBatch()

        val restartedAgain = WorkspaceStore(FileWorkspaceRepository(directory))
        assertEquals(5, restartedAgain.entityCount)
        assertEquals(5, restartedAgain.entityAt(4).id)
    }

    @Test
    fun writesMultiEntityBatchAsOneJournalTransaction() = withTempDirectory { directory ->
        val store = WorkspaceStore(FileWorkspaceRepository(directory))
        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_PROJECT, "Atlas")))
        assertTrue(store.applyIntent(intent(ENTITY_EPIC, "General", projectId = 1)))
        store.commitBatch()

        val lines = Files.readAllLines(directory.resolve("workspace.journal.jsonl"))
        assertEquals(1, lines.size)
        assertTrue("\"sequence\":1" in lines.single())
        assertTrue("\"Atlas\"" in lines.single())
        assertTrue("\"General\"" in lines.single())
    }

    @Test
    fun compactsToAtomicSnapshotAndRecoversWithoutJournal() = withTempDirectory { directory ->
        val store = WorkspaceStore(FileWorkspaceRepository(directory, compactAfterTransactions = 1))
        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_PROJECT, "Compacted")))
        store.commitBatch()

        assertTrue(Files.exists(directory.resolve("workspace.snapshot.json")))
        assertEquals(0L, Files.size(directory.resolve("workspace.journal.jsonl")))

        val recovered = WorkspaceStore(FileWorkspaceRepository(directory, compactAfterTransactions = 1))
        assertEquals(1, recovered.entityCount)
        assertEquals("Compacted", recovered.entityAt(0).title)
    }

    @Test
    fun quarantinesTruncatedJournalTailAndKeepsValidPrefix() = withTempDirectory { directory ->
        val store = WorkspaceStore(FileWorkspaceRepository(directory))
        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_PROJECT, "Durable")))
        store.commitBatch()
        Files.writeString(
            directory.resolve("workspace.journal.jsonl"),
            "{\"truncated\"",
            StandardOpenOption.APPEND,
        )

        val recovered = WorkspaceStore(FileWorkspaceRepository(directory))

        assertEquals(1, recovered.entityCount)
        assertEquals("Durable", recovered.entityAt(0).title)
        assertEquals(1, Files.readAllLines(directory.resolve("workspace.journal.jsonl")).size)
        Files.list(directory).use { paths ->
            assertTrue(paths.anyMatch { it.fileName.toString().startsWith("workspace.journal.corrupt-") })
        }
    }

    @Test
    fun failedDurableCommitCanBeRolledBackInMemory() {
        val repository = object : WorkspaceRepository {
            override fun load(): List<WorkspaceEntity> = emptyList()
            override fun commit(newEntities: List<WorkspaceEntity>, allEntities: List<WorkspaceEntity>) {
                error("disk full")
            }
        }
        val store = WorkspaceStore(repository)
        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_PROJECT, "Not durable")))

        assertFailsWith<IllegalStateException> { store.commitBatch() }
        store.rollbackBatch()

        assertEquals(0, store.entityCount)
    }

    @Test
    fun repositoryBindingRecoversCanonicalRootAndLiveMetadata() = withTempDirectory { directory ->
        val workspaceDirectory = directory.resolve("workspace")
        val repositoryDirectory = directory.resolve("bound-repository")
        val nestedDirectory = repositoryDirectory.resolve("src/main")
        Files.createDirectories(nestedDirectory)
        Files.writeString(repositoryDirectory.resolve("settings.gradle.kts"), "rootProject.name = \"bound\"\n")
        runCommand(repositoryDirectory, "git", "init", "--initial-branch=main")

        val firstStore = WorkspaceStore(
            FileWorkspaceRepository(workspaceDirectory),
            FileRepositoryBindingStore(workspaceDirectory),
        )
        firstStore.beginBatch()
        assertTrue(firstStore.applyIntent(intent(ENTITY_PROJECT, "Bound")))
        firstStore.commitBatch()

        val result = firstStore.bindRepository(1, nestedDirectory.toString())

        assertEquals(RepositoryBindStatus.BOUND, result.status)
        assertEquals(repositoryDirectory.toRealPath().toString(), result.snapshot.repositories.getValue(1).path)

        val recoveredStore = WorkspaceStore(
            FileWorkspaceRepository(workspaceDirectory),
            FileRepositoryBindingStore(workspaceDirectory),
        )
        val repository = recoveredStore.snapshot(0).repositories.getValue(1)
        assertEquals(repositoryDirectory.toRealPath().toString(), repository.path)
        assertEquals("main", repository.branch)
        assertEquals("Gradle", repository.buildSystem)
        assertTrue(repository.available)
        assertTrue(repository.dirty)

        Files.walk(repositoryDirectory).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
        }
        val unavailable = recoveredStore.snapshot(0).repositories.getValue(1)
        assertEquals(repository.path, unavailable.path)
        assertTrue(!unavailable.available)
    }

    @Test
    fun workflowRunRecoversPinnedContextContractAndPastFixes() = withTempDirectory { directory ->
        val workspaceDirectory = directory.resolve("workspace")
        val repositoryDirectory = directory.resolve("bound-repository")
        Files.createDirectories(repositoryDirectory)
        Files.writeString(repositoryDirectory.resolve("settings.gradle.kts"), "rootProject.name = \"bound\"\n")
        runCommand(repositoryDirectory, "git", "init", "--initial-branch=main")
        runCommand(repositoryDirectory, "git", "config", "user.email", "orchard@example.test")
        runCommand(repositoryDirectory, "git", "config", "user.name", "Orchard Test")
        runCommand(repositoryDirectory, "git", "add", "settings.gradle.kts")
        runCommand(repositoryDirectory, "git", "commit", "-m", "initial")
        val pinnedRevision = commandOutput(repositoryDirectory, "git", "rev-parse", "HEAD")

        val workflowMemory = FileWorkflowMemoryStore(workspaceDirectory)
        val definitionStore = FileWorkDefinitionStore(workspaceDirectory)
        val collaborationStore = FileDefinitionCollaborationStore(workspaceDirectory)
        workflowMemory.appendEpisode(
            WorkEpisode(
                episodeId = 1,
                projectId = 1,
                workItemType = ENTITY_TASK,
                workflowId = "default-delivery-task",
                title = "Fix Gradle JDK target failure",
                problem = "Gradle compilation failed because the JDK target was unsupported.",
                failedApproaches = listOf("Changing source compatibility alone did not change the Kotlin target."),
                resolution = "Configured the Kotlin JVM target consistently with the supported JDK.",
                evidenceSummary = "The complete Gradle build passed.",
                sourceRevision = pinnedRevision,
            )
        )
        workflowMemory.appendEpisode(
            WorkEpisode(
                episodeId = 2,
                projectId = 99,
                workItemType = ENTITY_TASK,
                workflowId = "default-delivery-task",
                title = "Fix Gradle JDK target failure",
                problem = "A different project had the same words.",
                failedApproaches = emptyList(),
                resolution = "Out of scope.",
                evidenceSummary = "None.",
                sourceRevision = pinnedRevision,
            )
        )
        val store = WorkspaceStore(
            FileWorkspaceRepository(workspaceDirectory),
            FileRepositoryBindingStore(workspaceDirectory),
            workflowMemory,
            definitionStore,
            collaborationStore,
        )
        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_PROJECT, "Orchard")))
        assertTrue(store.applyIntent(intent(ENTITY_EPIC, "Runtime", projectId = 1)))
        assertTrue(store.applyIntent(intent(ENTITY_STORY, "Build compatibility", projectId = 1, epicId = 2)))
        assertTrue(store.applyIntent(intent(ENTITY_TASK, "Fix Gradle JDK target", projectId = 1, epicId = 2, storyId = 3)))
        store.commitBatch()
        assertEquals(RepositoryBindStatus.BOUND, store.bindRepository(1, repositoryDirectory.toString()).status)
        assertEquals(DEFINITION_READY, store.submitWorkDefinition(4, readyDefinition()).snapshot.workDefinitions.single().assessment.status)

        val started = store.startWorkflow(4)

        assertEquals(WorkflowStartStatus.CREATED, started.status)
        val run = started.snapshot.workflowRuns.single()
        assertEquals(pinnedRevision, run.context.repository.commitHash)
        assertEquals(listOf(1L), run.context.recalledEpisodes.map { it.episodeId })
        assertEquals(4, run.workflow.evidenceContract.requirements.size)
        assertTrue(run.workflow.evidenceContract.requirements.any {
            it.kind == "ACCEPTANCE" && "./gradlew build" in it.description
        })
        assertEquals(started.snapshot.workDefinitions.single().hash, run.workDefinition?.hash)
        assertTrue("status=1" in started.snapshot.resources.getValue("entity-4").action)

        Files.writeString(repositoryDirectory.resolve("README.md"), "later revision\n")
        runCommand(repositoryDirectory, "git", "add", "README.md")
        runCommand(repositoryDirectory, "git", "commit", "-m", "later")
        val completedRevision = commandOutput(repositoryDirectory, "git", "rev-parse", "HEAD")

        assertEquals(
            com.orchard.backend.workspace.WorkflowMutationStatus.RECORDED,
            store.recordAttempt(
                1,
                AttemptSubmission(
                    description = "Change Java source compatibility only",
                    outcome = "Kotlin still targeted the unsupported bytecode level.",
                    diagnosticHash = "d".repeat(64),
                    successful = false,
                ),
            ).status,
        )
        store.submitEvidence(
            1,
            EvidenceSubmission(
                kind = "SOURCE_DIFF",
                revision = completedRevision,
                command = "",
                exitCode = 0,
                outputHash = "a".repeat(64),
                summary = "Aligned Kotlin and Java target configuration.",
                producer = "test-fixture",
            ),
        )
        val blocked = store.submitEvidence(
            1,
            EvidenceSubmission(
                kind = "BUILD",
                revision = completedRevision,
                command = "./gradlew build",
                exitCode = 1,
                outputHash = "b".repeat(64),
                summary = "Build still failed before the corrected retry.",
                producer = "test-fixture",
            ),
        )
        assertEquals(RUN_STATE_EVIDENCE_BLOCKED, blocked.snapshot.workflowRuns.single().state)
        store.submitEvidence(
            1,
            EvidenceSubmission(
                kind = "BUILD",
                revision = completedRevision,
                command = "./gradlew build",
                exitCode = 0,
                outputHash = "c".repeat(64),
                summary = "Complete build passed.",
                producer = "test-fixture",
            ),
        )
        store.submitEvidence(
            1,
            EvidenceSubmission(
                kind = "TEST",
                revision = completedRevision,
                command = "./gradlew test",
                exitCode = 0,
                outputHash = "e".repeat(64),
                summary = "Relevant tests passed.",
                producer = "test-fixture",
            ),
        )
        val completed = store.submitEvidence(
            1,
            EvidenceSubmission(
                kind = "ACCEPTANCE",
                revision = completedRevision,
                command = "./gradlew build",
                exitCode = 0,
                outputHash = "f".repeat(64),
                summary = "The work-definition acceptance criterion passed.",
                producer = "test-fixture",
            ),
        )
        assertEquals(RUN_STATE_DONE, completed.snapshot.workflowRuns.single().state)
        assertTrue("status=3" in completed.snapshot.resources.getValue("entity-4").action)

        store.beginBatch()
        assertTrue(store.applyIntent(intent(ENTITY_TASK, "Fix Gradle JDK target again", projectId = 1, epicId = 2, storyId = 3)))
        store.commitBatch()
        store.submitWorkDefinition(5, readyDefinition())
        val recalled = store.startWorkflow(5).snapshot.workflowRuns.first { it.context.workItemId == 5 }
        assertTrue(recalled.context.recalledEpisodes.any { recall ->
            recall.failedApproaches.any { "Kotlin still targeted" in it }
        })

        val recovered = WorkspaceStore(
            FileWorkspaceRepository(workspaceDirectory),
            FileRepositoryBindingStore(workspaceDirectory),
            FileWorkflowMemoryStore(workspaceDirectory),
            FileWorkDefinitionStore(workspaceDirectory),
            FileDefinitionCollaborationStore(workspaceDirectory),
        ).snapshot(0)
        assertEquals(2, recovered.workflowRuns.size)
        assertEquals(2, recovered.workDefinitions.size)
        assertEquals(RUN_STATE_DONE, recovered.workflowRuns.first { it.runId == 1L }.state)
        assertEquals(pinnedRevision, recovered.workflowRuns.first { it.runId == 1L }.context.repository.commitHash)
        assertTrue("status=3" in recovered.resources.getValue("entity-4").action)
        assertTrue(recovered.workflowRuns.first { it.runId == 2L }.context.recalledEpisodes.any { recall ->
            recall.failedApproaches.any { "Kotlin still targeted" in it }
        })
        assertEquals(
            recovered.workDefinitions.first { it.workItemId == 5 }.hash,
            recovered.workflowRuns.first { it.runId == 2L }.workDefinition?.hash,
        )
    }

    private fun intent(
        type: Int,
        title: String,
        projectId: Int = 0,
        epicId: Int = 0,
        storyId: Int = 0,
    ) = DocumentIntent(
        actionTypeId = ACTION_CREATE,
        entityTypeId = type,
        boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID,
        projectId = projectId,
        epicId = epicId,
        storyId = storyId,
        title = title,
    )

    private fun readyDefinition() = WorkDefinitionSubmission(
        requestedOutcome = "Build succeeds on the supported JDK",
        currentBehavior = "Gradle targets an unsupported bytecode version",
        requiredBehavior = "Kotlin and Java compile to the supported target",
        scope = listOf("Gradle JVM target configuration"),
        nonGoals = listOf("Changing application behavior"),
        constraints = listOf("Keep the current JDK toolchain"),
        acceptanceCriteria = listOf(
            AcceptanceCriterion("The complete build succeeds", "Run ./gradlew build")
        ),
    )

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("orchard-workspace-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private fun runCommand(directory: Path, vararg command: String) {
        val process = ProcessBuilder(command.toList()).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "Command ${command.joinToString(" ")} failed: $output" }
    }

    private fun commandOutput(directory: Path, vararg command: String): String {
        val process = ProcessBuilder(command.toList()).directory(directory.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText().trim() }
        check(process.waitFor() == 0) { "Command ${command.joinToString(" ")} failed: $output" }
        return output
    }
}