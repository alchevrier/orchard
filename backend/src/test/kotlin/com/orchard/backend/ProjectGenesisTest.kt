package com.orchard.backend

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.ArchitectureComponent
import com.orchard.backend.workspace.ArchitectureDecision
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.DESIGN_STATUS_ADMITTED
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.ExperienceContract
import com.orchard.backend.workspace.FileProjectGenesisStore
import com.orchard.backend.workspace.FileWorkspaceRepository
import com.orchard.backend.workspace.GENESIS_ADMISSION
import com.orchard.backend.workspace.GENESIS_ARCHITECTURE
import com.orchard.backend.workspace.GENESIS_BLUEPRINT
import com.orchard.backend.workspace.GENESIS_CLASSIFICATION
import com.orchard.backend.workspace.GENESIS_EXPERIENCE
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.PROJECT_GREENFIELD_LOCAL
import com.orchard.backend.workspace.PROJECT_ORGANIZATION_GOVERNED
import com.orchard.backend.workspace.ProjectGenesisStatus
import com.orchard.backend.workspace.ProjectGenesisSubmission
import com.orchard.backend.workspace.RepositoryBlueprint
import com.orchard.backend.workspace.WorkflowStartStatus
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.WorkspaceSnapshot
import com.orchard.backend.workspace.WorkspaceStore
import com.orchard.backend.agent.GenesisIntelligenceService
import com.orchard.backend.agent.GenesisProposalRequest
import com.orchard.backend.agent.GenesisProposalStatus
import com.orchard.backend.vector.ModelGeneration
import com.orchard.backend.vector.ModelProvider
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProjectGenesisTest {
    @Test
    fun modelProposalIsPhaseBoundPinnedAndNonAuthoritative() = kotlinx.coroutines.test.runTest {
        val workspace = WorkspaceStore()
        createHierarchy(workspace)
        val validOutput = """{
            "submission":{
                "classification":"GREENFIELD_LOCAL",
                "productIntent":"A guided local product.",
                "baseRevision":99,
                "baseHash":"forged"
            },
            "observations":["The user asked for local authority."],
            "unresolvedQuestions":[]
        }""".trimIndent()
        val service = GenesisIntelligenceService(workspace, FakeGenesisModel(validOutput))

        val result = service.propose(1, GenesisProposalRequest("I am making a private local product."))

        assertEquals(GenesisProposalStatus.CREATED, result.status)
        assertEquals(0, result.proposal?.baseRevision)
        assertEquals(null, result.proposal?.baseHash)
        assertEquals(0, workspace.snapshot(MESSAGE_READY).projectGenesis.single().revision?.revision ?: 0)

        val invalidOutput = """{
            "submission":{
                "blueprint":{"rootName":"wrong","toolchain":"gradle","modules":["app"],"verificationCommands":["test"]},
                "baseRevision":0
            }
        }""".trimIndent()
        val invalid = GenesisIntelligenceService(workspace, FakeGenesisModel(invalidOutput))
            .propose(1, GenesisProposalRequest("Skip directly to repository setup."))
        assertEquals(GenesisProposalStatus.INVALID_OUTPUT, invalid.status)
        assertEquals(GENESIS_CLASSIFICATION, workspace.snapshot(MESSAGE_READY).projectGenesis.single().phase)
    }

    @Test
    fun guidedGenesisRecoversAtExactAdmittedState() = withTempDirectory { directory ->
        val first = newWorkspace(directory)
        createHierarchy(first)

        val classified = advance(
            first,
            ProjectGenesisSubmission(
                classification = PROJECT_GREENFIELD_LOCAL,
                productIntent = "A calm local environment where product intent becomes governed software.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        assertEquals(
            ProjectGenesisStatus.STALE_REVISION,
            first.advanceProjectGenesis(
                1,
                ProjectGenesisSubmission(
                    classification = PROJECT_GREENFIELD_LOCAL,
                    productIntent = "A stale competing direction.",
                    baseRevision = 0,
                ),
            ).status,
        )
        val experienced = advance(first, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val architected = advance(first, architectureSubmission(experienced), GENESIS_BLUEPRINT)
        val blueprint = advance(first, blueprintSubmission(architected), GENESIS_ADMISSION)

        val admitted = first.admitProjectGenesis(1)
        assertEquals(ProjectGenesisStatus.ADMITTED, admitted.status)
        val ready = admitted.snapshot.projectGenesis.single()
        assertEquals(GENESIS_READY, ready.phase)
        assertEquals(100, ready.progress)
        assertTrue(ready.revision?.admitted == true)
        assertTrue(ready.revision?.decisions.orEmpty().all { it.status == DESIGN_STATUS_ADMITTED })

        val recovered = newWorkspace(directory).snapshot(MESSAGE_READY).projectGenesis.single()
        assertEquals(ready, recovered)
        assertEquals(5, FileProjectGenesisStore(directory).loadEvents().size)
        assertTrue(Files.readString(directory.resolve("project-genesis.jsonl")).contains("\"checksum\""))
        assertEquals(blueprint.revision?.hash, recovered.revision?.let { _ -> blueprint.revision?.hash })
    }

    @Test
    fun invalidOrderAndOrganizationalAdmissionFailClosed() {
        val workspace = WorkspaceStore(
            projectGenesisStore = com.orchard.backend.workspace.TransientProjectGenesisStore(),
            enforceProjectGenesis = true,
        )
        createHierarchy(workspace)

        assertEquals(
            ProjectGenesisStatus.INVALID_TRANSITION,
            workspace.advanceProjectGenesis(
                1,
                ProjectGenesisSubmission(
                    experience = experience(),
                    baseRevision = 0,
                ),
            ).status,
        )
        val classified = advance(
            workspace,
            ProjectGenesisSubmission(
                classification = PROJECT_ORGANIZATION_GOVERNED,
                productIntent = "Organization-governed delivery.",
                baseRevision = 0,
            ),
            GENESIS_EXPERIENCE,
        )
        val experienced = advance(workspace, experienceSubmission(classified), GENESIS_ARCHITECTURE)
        val architected = advance(workspace, architectureSubmission(experienced), GENESIS_BLUEPRINT)
        val blueprint = advance(workspace, blueprintSubmission(architected), GENESIS_ADMISSION)

        assertNotNull(blueprint.revision)
        assertEquals(
            ProjectGenesisStatus.ORGANIZATION_POLICY_REQUIRED,
            workspace.admitProjectGenesis(1).status,
        )
        assertEquals(GENESIS_ADMISSION, workspace.snapshot(MESSAGE_READY).projectGenesis.single().phase)
    }

    @Test
    fun directWorkflowApiCannotBypassGenesisAdmission() = testApplication {
        val workspace = WorkspaceStore(enforceProjectGenesis = true)
        createHierarchy(workspace)
        workspace.submitWorkDefinition(4, readyDefinition())
        application { workspaceApi(workspace) }

        val response = client.post("/api/work-items/4/runs")

        assertEquals(HttpStatusCode.Conflict, response.status)
        assertEquals(WorkflowStartStatus.PROJECT_GENESIS_NOT_ADMITTED, workspace.startWorkflow(4).status)
        assertTrue(workspace.snapshot(MESSAGE_READY).workflowRuns.isEmpty())
    }

    private fun newWorkspace(directory: Path) = WorkspaceStore(
        repository = FileWorkspaceRepository(directory),
        projectGenesisStore = FileProjectGenesisStore(directory),
        enforceProjectGenesis = true,
    )

    private fun createHierarchy(workspace: WorkspaceStore) {
        workspace.beginBatch()
        assertTrue(workspace.applyIntent(intent(ENTITY_PROJECT, "Orchard")))
        assertTrue(workspace.applyIntent(intent(ENTITY_EPIC, "Guided product genesis", projectId = 1)))
        assertTrue(workspace.applyIntent(intent(ENTITY_STORY, "Experience formation", projectId = 1, epicId = 2)))
        assertTrue(workspace.applyIntent(intent(ENTITY_TASK, "Build genesis circuit", projectId = 1, epicId = 2, storyId = 3)))
        workspace.commitBatch()
    }

    private fun advance(
        workspace: WorkspaceStore,
        submission: ProjectGenesisSubmission,
        expectedPhase: String,
    ): com.orchard.backend.workspace.ProjectGenesisView {
        assertEquals(ProjectGenesisStatus.RECORDED, workspace.advanceProjectGenesis(1, submission).status)
        return workspace.snapshot(MESSAGE_READY).projectGenesis.single().also {
            assertEquals(expectedPhase, it.phase)
        }
    }

    private fun experienceSubmission(current: com.orchard.backend.workspace.ProjectGenesisView) =
        ProjectGenesisSubmission(
            experience = experience(),
            baseRevision = requireNotNull(current.revision).revision,
            baseHash = current.revision.hash,
        )

    private fun experience() = ExperienceContract(
        audience = "Developers shaping local products",
        productPromise = "Conversation continuously resolves into visible, governed project state.",
        primaryJourney = listOf("Describe intent", "Resolve design", "Admit authority", "Observe delivery"),
        interactionPrinciples = listOf("Expose only the next valid decision", "Keep prior authority inspectable"),
        emotionalQualities = listOf("Calm", "Sophisticated", "Continuous"),
        mustNotFeelLike = listOf("A project-management dashboard", "An unrestricted coding chat"),
        accessibility = listOf("Honor reduced motion", "Maintain keyboard access"),
    )

    private fun architectureSubmission(current: com.orchard.backend.workspace.ProjectGenesisView) =
        ProjectGenesisSubmission(
            components = listOf(
                ArchitectureComponent(
                    "architect",
                    "Architect",
                    "Turns conversation into candidate governed transitions.",
                    requirementIds = listOf("GEN-1"),
                    repositoryPaths = listOf("backend/src/main/kotlin"),
                ),
                ArchitectureComponent(
                    "projection",
                    "State projection",
                    "Shows the current authority without becoming an alternate mutation path.",
                    dependsOn = listOf("architect"),
                    requirementIds = listOf("GEN-1"),
                    repositoryPaths = listOf("frontend/src/desktopMain/kotlin"),
                ),
            ),
            decisions = listOf(
                ArchitectureDecision(
                    "ADR-GEN-1",
                    "Use a guided state-driven circuit",
                    context = "Free navigation permits implementation before product design is authoritative.",
                    decision = "The backend determines the next legal genesis phase and the UI projects it.",
                    consequences = listOf("Users may inspect prior state but cannot jump forward."),
                    componentIds = listOf("architect", "projection"),
                    requirementIds = listOf("GEN-1"),
                )
            ),
            firstEpicId = 2,
            baseRevision = requireNotNull(current.revision).revision,
            baseHash = current.revision.hash,
        )

    private fun blueprintSubmission(current: com.orchard.backend.workspace.ProjectGenesisView) =
        ProjectGenesisSubmission(
            blueprint = RepositoryBlueprint(
                rootName = "orchard",
                toolchain = "gradle-kotlin",
                modules = listOf("backend", "frontend"),
                verificationCommands = listOf("./gradlew build --no-daemon"),
                policyPackIds = listOf("builtin-toolchains-v1"),
            ),
            baseRevision = requireNotNull(current.revision).revision,
            baseHash = current.revision.hash,
        )

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
        requestedOutcome = "The guided genesis circuit is enforced.",
        currentBehavior = "Implementation can start without genesis authority.",
        requiredBehavior = "Implementation starts only after genesis admission.",
        scope = listOf("Workflow admission"),
        nonGoals = listOf("Remote policy sources"),
        constraints = listOf("Preserve existing design governance"),
        acceptanceCriteria = listOf(AcceptanceCriterion("Bypass is rejected", "Run the genesis tests")),
    )

    private fun withTempDirectory(block: (Path) -> Unit) {
        val directory = createTempDirectory("orchard-genesis-test")
        try {
            block(directory)
        } finally {
            Files.walk(directory).use { paths ->
                paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
            }
        }
    }

    private class FakeGenesisModel(private val output: String) : ModelProvider {
        override suspend fun triage(prompt: String): String = error("unused")
        override suspend fun plan(
            prompt: String,
            actionType: Int,
            entityType: Int,
            workspace: WorkspaceStore,
        ): String = error("unused")

        override suspend fun executeWorkDefinition(
            prompt: String,
            maxOutputTokens: Int,
            contextWindowTokens: Int,
        ): ModelGeneration = ModelGeneration(output, prompt.length, output.length)
    }
}