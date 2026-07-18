package com.orchard.backend.company

import com.orchard.backend.api.DocumentIntent
import com.orchard.backend.workspace.ACTION_CREATE
import com.orchard.backend.workspace.AcceptanceCriterion
import com.orchard.backend.workspace.CRITERION_AUTOMATED
import com.orchard.backend.workspace.DEFAULT_DELIVERY_WORKFLOW_ID
import com.orchard.backend.workspace.DESIGN_STATUS_ADMITTED
import com.orchard.backend.workspace.DesignCriterionSubmission
import com.orchard.backend.workspace.DesignGovernanceStatus
import com.orchard.backend.workspace.DesignSubmission
import com.orchard.backend.workspace.ENTITY_EPIC
import com.orchard.backend.workspace.ENTITY_PROJECT
import com.orchard.backend.workspace.ENTITY_STORY
import com.orchard.backend.workspace.ENTITY_TASK
import com.orchard.backend.workspace.GENESIS_READY
import com.orchard.backend.workspace.MESSAGE_READY
import com.orchard.backend.workspace.PROJECT_GREENFIELD_LOCAL
import com.orchard.backend.workspace.RepositoryBlueprint
import com.orchard.backend.workspace.RepositoryBindStatus
import com.orchard.backend.workspace.RequirementSubmission
import com.orchard.backend.workspace.StagedDeliveryPlanSubmission
import com.orchard.backend.workspace.StagedPlanNodeSubmission
import com.orchard.backend.workspace.StagedPlanStageSubmission
import com.orchard.backend.workspace.StagedPlanStatus
import com.orchard.backend.workspace.WorkDefinitionStatus
import com.orchard.backend.workspace.WorkDefinitionSubmission
import com.orchard.backend.workspace.WorkspaceEntity
import com.orchard.backend.workspace.WorkspaceStore
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable

enum class CompanyCircuitStatus {
    STARTED,
    ALREADY_STARTED,
    PROJECT_NOT_READY,
    REPOSITORY_REQUIRED,
    BLUEPRINT_UNSUPPORTED,
    AUTHORITY_REJECTED,
    STORAGE_UNAVAILABLE,
}

@Serializable
data class CompanyCircuitResult(
    val status: CompanyCircuitStatus,
    val project: CompanyProjectView? = null,
    val diagnostic: String = "",
)

class CompanyCircuitService(
    private val workspace: WorkspaceStore,
    private val company: CompanyControlService,
    private val localProjectsDirectory: Path,
) {
    @Synchronized
    fun start(projectId: Int): CompanyCircuitResult {
        val snapshot = workspace.snapshot(MESSAGE_READY)
        val genesis = snapshot.projectGenesis.singleOrNull {
            it.projectId == projectId && it.phase == GENESIS_READY && it.revision?.admitted == true
        }?.revision ?: return failure(CompanyCircuitStatus.PROJECT_NOT_READY, projectId, "Admit product genesis first.")
        val epicId = genesis.firstEpicId ?: return failure(
            CompanyCircuitStatus.PROJECT_NOT_READY,
            projectId,
            "The admitted genesis has no first epic.",
        )
        val repository = snapshot.repositories[projectId]
        if (repository?.available != true) {
            if (genesis.classification != PROJECT_GREENFIELD_LOCAL) return failure(
                CompanyCircuitStatus.REPOSITORY_REQUIRED,
                projectId,
                "Bind the existing local repository before starting the company circuit.",
            )
            val materialized = runCatching { materialize(projectId, requireNotNull(genesis.blueprint)) }.getOrElse {
                return failure(CompanyCircuitStatus.BLUEPRINT_UNSUPPORTED, projectId, it.message.orEmpty())
            }
            if (workspace.bindRepository(projectId, materialized.toString()).status != RepositoryBindStatus.BOUND) {
                return failure(CompanyCircuitStatus.STORAGE_UNAVAILABLE, projectId, "The materialized repository could not be bound.")
            }
        }
        val currentEntities = entities()
        val existingStory = currentEntities.singleOrNull { it.type == ENTITY_STORY && it.parentId == epicId }
        val existingTask = existingStory?.let { story ->
            currentEntities.singleOrNull { it.type == ENTITY_TASK && it.parentId == story.id }
        }
        if (existingTask != null && workspace.snapshot(MESSAGE_READY).stagedPlans.any { it.plan.scopeId == existingStory.id }) {
            company.compileRules(projectId)
            workspace.dispatchEligible()
            return CompanyCircuitResult(CompanyCircuitStatus.ALREADY_STARTED, company.projectView(projectId))
        }
        val story = existingStory ?: createEntity(
            DocumentIntent(
                actionTypeId = ACTION_CREATE,
                entityTypeId = ENTITY_STORY,
                boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID,
                projectId = projectId,
                epicId = epicId,
                title = "Prove the primary product journey",
                content = genesis.experience.primaryJourney.joinToString(" -> ").ifBlank { genesis.experience.productPromise },
            )
        ) ?: return authorityFailure(projectId, "The first delivery story could not be created.")
        val task = existingTask ?: createEntity(
            DocumentIntent(
                actionTypeId = ACTION_CREATE,
                entityTypeId = ENTITY_TASK,
                boundWorkflowId = DEFAULT_DELIVERY_WORKFLOW_ID,
                projectId = projectId,
                epicId = epicId,
                storyId = story.id,
                title = "Implement the first experience slice",
                content = genesis.experience.productPromise,
            )
        ) ?: return authorityFailure(projectId, "The first implementation task could not be created.")
        val verification = preferredVerification(requireNotNull(genesis.blueprint))
        val definition = workspace.snapshot(MESSAGE_READY).workDefinitions.singleOrNull { it.workItemId == task.id }
        if (definition == null) {
            val result = workspace.submitWorkDefinition(
                task.id,
                WorkDefinitionSubmission(
                    requestedOutcome = genesis.experience.productPromise,
                    currentBehavior = "The admitted product experience has no implemented vertical slice yet.",
                    requiredBehavior = genesis.experience.primaryJourney.joinToString(" -> ")
                        .ifBlank { genesis.experience.productPromise },
                    scope = genesis.components.flatMap { it.repositoryPaths }.distinct().ifEmpty { listOf("src") },
                    nonGoals = genesis.experience.mustNotFeelLike.ifEmpty { listOf("Implementing work outside the first admitted journey") },
                    constraints = genesis.decisions.map { it.decision }.ifEmpty { listOf("Preserve the admitted product genesis") },
                    acceptanceCriteria = listOf(AcceptanceCriterion(genesis.experience.productPromise, verification)),
                ),
            )
            if (result.status != WorkDefinitionStatus.RECORDED) return authorityFailure(
                projectId,
                "The first work definition was rejected with ${result.status}.",
            )
        }
        val designStatus = ensureDesignHierarchy(projectId, epicId, story.id, task.id, genesis, verification)
        if (designStatus != null) return authorityFailure(projectId, designStatus)
        val plans = workspace.snapshot(MESSAGE_READY).stagedPlans
        if (plans.none { it.plan.scopeId == epicId }) {
            val result = workspace.acceptStagedPlan(
                StagedDeliveryPlanSubmission(
                    scopeId = epicId,
                    title = "First product experience",
                    stages = listOf(
                        StagedPlanStageSubmission(
                            "experience",
                            "Experience proof",
                            "sequential-delivery-v1",
                            nodes = listOf(StagedPlanNodeSubmission("first-story", story.id)),
                        )
                    ),
                )
            )
            if (result.status != StagedPlanStatus.ACCEPTED) return authorityFailure(
                projectId,
                "The first epic circuit was rejected with ${result.status}.",
            )
        }
        if (workspace.snapshot(MESSAGE_READY).stagedPlans.none { it.plan.scopeId == story.id }) {
            val result = workspace.acceptStagedPlan(
                StagedDeliveryPlanSubmission(
                    scopeId = story.id,
                    title = "First vertical slice",
                    stages = listOf(
                        StagedPlanStageSubmission(
                            "implementation",
                            "Implementation",
                            "sequential-delivery-v1",
                            nodes = listOf(StagedPlanNodeSubmission("first-slice", task.id)),
                        )
                    ),
                )
            )
            if (result.status != StagedPlanStatus.ACCEPTED) return authorityFailure(
                projectId,
                "The first story circuit was rejected with ${result.status}.",
            )
        }
        if (company.compileRules(projectId).status != CompanyMutationStatus.RECORDED) {
            return failure(CompanyCircuitStatus.STORAGE_UNAVAILABLE, projectId, "Architecture rules could not be compiled.")
        }
        workspace.dispatchEligible()
        return CompanyCircuitResult(CompanyCircuitStatus.STARTED, company.projectView(projectId))
    }

    private fun ensureDesignHierarchy(
        projectId: Int,
        epicId: Int,
        storyId: Int,
        taskId: Int,
        genesis: com.orchard.backend.workspace.ProjectGenesisRevision,
        verification: String,
    ): String? {
        val snapshot = workspace.snapshot(MESSAGE_READY)
        if (snapshot.projectGovernance.none { it.activation.projectId == projectId }) {
            val activation = workspace.activateDesignGovernance(projectId)
            if (activation.status != DesignGovernanceStatus.RECORDED) return "Design governance activation failed with ${activation.status}."
        }
        val levels = listOf(
            DesignLevel(epicId, "PRODUCT_OUTCOME", emptyList(), "Deliver ${genesis.experience.productPromise}"),
            DesignLevel(storyId, "EXPERIENCE_SLICE", listOf("PRODUCT_OUTCOME"), genesis.experience.primaryJourney.joinToString(" -> ")),
            DesignLevel(taskId, "IMPLEMENTATION_SLICE", listOf("EXPERIENCE_SLICE"), genesis.experience.productPromise),
        )
        levels.forEach { level ->
            val current = workspace.snapshot(MESSAGE_READY).designRevisions.lastOrNull { it.design.workItemId == level.workItemId }
            if (current?.status == DESIGN_STATUS_ADMITTED) return@forEach
            val submission = DesignSubmission(
                workItemId = level.workItemId,
                title = "Governed ${level.requirementId.lowercase().replace('_', ' ')}",
                problem = level.statement.ifBlank { genesis.productIntent },
                scope = genesis.components.map { it.name }.ifEmpty { listOf("First product slice") },
                assumptions = genesis.experience.interactionPrinciples.ifEmpty { listOf("The admitted experience remains authoritative") },
                constraints = genesis.decisions.map { it.decision }.ifEmpty { listOf("Remain within admitted genesis") },
                alternatives = listOf("Defer implementation and return to product design"),
                architecture = genesis.components.map { "${it.name}: ${it.responsibility}" },
                failureModes = genesis.experience.mustNotFeelLike.ifEmpty { listOf("The primary journey is not demonstrable") },
                qualityAttributes = genesis.experience.emotionalQualities.ifEmpty { listOf("Coherent", "Inspectable") },
                securityImpact = "No additional security authority beyond the admitted genesis.",
                complianceImpact = "No additional compliance authority beyond the admitted genesis.",
                requirements = listOf(
                    RequirementSubmission(
                        requirementId = level.requirementId,
                        statement = level.statement.ifBlank { genesis.productIntent },
                        parentRequirementIds = level.parentRequirementIds,
                        criteria = listOf(
                            DesignCriterionSubmission(
                                description = "The governed slice satisfies ${level.requirementId}.",
                                verification = verification,
                                gate = CRITERION_AUTOMATED,
                            )
                        ),
                    )
                ),
            )
            val candidate = workspace.recordDesignCandidate(submission)
            val design = candidate.design ?: return "Design candidate ${level.requirementId} was rejected with ${candidate.status}."
            val admission = workspace.admitDesign(design.designId)
            if (admission.status != DesignGovernanceStatus.ADMITTED) {
                return "Design ${level.requirementId} was rejected: ${admission.decision?.findings?.joinToString { it.code }.orEmpty()}"
            }
        }
        return null
    }

    private fun createEntity(intent: DocumentIntent): WorkspaceEntity? {
        workspace.beginBatch()
        return try {
            if (!workspace.applyIntent(intent)) {
                workspace.rollbackBatch()
                null
            } else {
                val entity = workspace.entityAt(workspace.entityCount - 1)
                workspace.commitBatch()
                entity
            }
        } catch (error: Exception) {
            runCatching { workspace.rollbackBatch() }
            throw error
        }
    }

    private fun entities(): List<WorkspaceEntity> = List(workspace.entityCount, workspace::entityAt)

    private fun preferredVerification(blueprint: RepositoryBlueprint): String = blueprint.verificationCommands
        .firstOrNull { it.isNotBlank() }
        ?: when (blueprint.toolchain.trim().lowercase()) {
            "gradle", "kotlin", "java" -> "./gradlew test --no-daemon"
            "maven" -> "./mvnw test"
            "cargo", "rust" -> "cargo test"
            "meson" -> "meson test -C build"
            "cmake" -> "ctest --test-dir build"
            "node", "npm", "typescript", "javascript" -> "npm test"
            else -> throw IllegalArgumentException("The admitted blueprint toolchain is not supported locally.")
        }

    private fun materialize(projectId: Int, blueprint: RepositoryBlueprint): Path {
        val toolchain = blueprint.toolchain.trim().lowercase()
        val rootName = blueprint.rootName.trim().ifBlank { "project-$projectId" }
        require(rootName.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "The blueprint root name is invalid." }
        val root = localProjectsDirectory.resolve(rootName).toAbsolutePath().normalize()
        require(root.startsWith(localProjectsDirectory.toAbsolutePath().normalize())) { "The blueprint root escapes local projects." }
        Files.createDirectories(root)
        if (!Files.exists(root.resolve(".git"))) {
            writeScaffold(root, toolchain, blueprint)
            git(root, "init")
            git(root, "add", ".")
            git(root, "-c", "user.name=Orchard", "-c", "user.email=orchard@localhost", "commit", "-m", "Materialize admitted repository blueprint")
        }
        return root
    }

    private fun writeScaffold(root: Path, toolchain: String, blueprint: RepositoryBlueprint) {
        Files.writeString(root.resolve("README.md"), "# ${blueprint.rootName}\n\nMaterialized from admitted Orchard product genesis.\n")
        when (toolchain) {
            "gradle", "kotlin", "java" -> {
                Files.writeString(root.resolve(".gitignore"), ".gradle/\nbuild/\n**/build/\n")
                Files.writeString(root.resolve("settings.gradle.kts"), "rootProject.name = \"${blueprint.rootName}\"\n")
                Files.writeString(root.resolve("build.gradle.kts"), "plugins { base }\n\ntasks.register(\"test\") { dependsOn(\"check\") }\n")
                val launcher = generateSequence(Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()) {
                    it.parent
                }.map { directory -> directory.resolve("gradlew") }
                    .firstOrNull(Files::isRegularFile)
                    ?: throw IllegalArgumentException("Orchard's Gradle launcher is unavailable.")
                Files.copy(launcher, root.resolve("gradlew"))
                root.resolve("gradlew").toFile().setExecutable(true)
                val wrapper = launcher.parent.resolve("gradle/wrapper")
                if (Files.isDirectory(wrapper)) copyTree(wrapper, root.resolve("gradle/wrapper"))
            }
            "cargo", "rust" -> {
                Files.writeString(root.resolve(".gitignore"), "target/\n")
                Files.writeString(root.resolve("Cargo.toml"), "[package]\nname = \"${blueprint.rootName.replace('-', '_')}\"\nversion = \"0.1.0\"\nedition = \"2024\"\n")
            }
            "node", "npm", "typescript", "javascript" -> {
                Files.writeString(root.resolve(".gitignore"), "node_modules/\ncoverage/\ndist/\n")
                Files.writeString(
                    root.resolve("package.json"),
                    "{\"name\":\"${blueprint.rootName}\",\"version\":\"0.1.0\",\"scripts\":{\"build\":\"node --check index.js\",\"test\":\"node --test\"}}\n",
                )
            }
            else -> throw IllegalArgumentException("Greenfield materialization supports Gradle, Cargo, and Node blueprints.")
        }
        blueprint.modules.filter { it.isNotBlank() }.forEach { module ->
            val normalized = Path.of(module).normalize()
            require(!normalized.isAbsolute && normalized.none { it.toString() == ".." }) { "A blueprint module path is invalid." }
            Files.createDirectories(root.resolve(normalized))
            Files.writeString(root.resolve(normalized).resolve(".gitkeep"), "")
        }
    }

    private fun copyTree(source: Path, target: Path) {
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val destination = target.resolve(source.relativize(path).toString())
                if (Files.isDirectory(path)) Files.createDirectories(destination)
                else Files.copy(path, destination)
            }
        }
    }

    private fun git(root: Path, vararg arguments: String) {
        val process = ProcessBuilder(listOf("git", "-C", root.toString()) + arguments)
            .redirectErrorStream(true)
            .start()
        val completed = process.waitFor(15, TimeUnit.SECONDS)
        if (!completed) process.destroyForcibly()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        require(completed && process.exitValue() == 0) { "Git repository materialization failed: ${output.take(1_000)}" }
    }

    private fun authorityFailure(projectId: Int, diagnostic: String): CompanyCircuitResult =
        failure(CompanyCircuitStatus.AUTHORITY_REJECTED, projectId, diagnostic)

    private fun failure(status: CompanyCircuitStatus, projectId: Int, diagnostic: String): CompanyCircuitResult =
        CompanyCircuitResult(status, company.projectView(projectId), diagnostic)

    private data class DesignLevel(
        val workItemId: Int,
        val requirementId: String,
        val parentRequirementIds: List<String>,
        val statement: String,
    )
}