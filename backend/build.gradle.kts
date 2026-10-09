import java.security.MessageDigest

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    @OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)
    jvm {
        mainRun {
            mainClass.set("com.orchard.backend.OrchardApplicationKt")
        }
    }
    sourceSets {
        val jvmMain by getting {
            kotlin.srcDir("src/main/kotlin")
            resources.srcDir("src/main/resources")
            dependencies {
                implementation("io.ktor:ktor-server-core:3.1.3")
                implementation("io.ktor:ktor-server-netty:3.1.3")
                implementation("io.ktor:ktor-server-content-negotiation:3.1.3")
                implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.3")
                implementation("io.ktor:ktor-client-core:3.1.3")
                implementation("io.ktor:ktor-client-cio:3.1.3")
                implementation("io.ktor:ktor-client-content-negotiation:3.1.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1")
                implementation("com.knuddels:jtokkit:1.1.0")
                implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.2.20")
                runtimeOnly("ch.qos.logback:logback-classic:1.5.18")
            }
        }
        val jvmTest by getting {
            kotlin.srcDir("src/test/kotlin")
            dependencies {
                implementation(kotlin("test"))
                implementation("io.ktor:ktor-server-test-host:3.1.3")
                implementation("io.ktor:ktor-client-mock:3.1.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
            }
        }
    }
}

fun adrCompletionOutcome(criterion: Map<*, *>, evidence: Map<String, Boolean>): Map<String, Any?> {
    val tests = (criterion["tests"] as List<*>).map { it as String }
    val integrationTests = (criterion["integrationTests"] as? List<*>)?.map { it as String }.orEmpty()
    val partialTests = (criterion["partialTests"] as? List<*>)?.map { it as String }.orEmpty()
    require((tests + integrationTests + partialTests).all { '#' in it } && tests.distinct().size == tests.size &&
        integrationTests.distinct().size == integrationTests.size && integrationTests.none { it in partialTests }) { "Invalid test evidence identifiers" }
    val requiredTests = (tests + integrationTests).distinct()
    val missing = requiredTests.filter { it !in evidence }
    val failing = requiredTests.filter { evidence[it] == false }
    return mapOf(
        "id" to criterion["id"], "requirement" to criterion["requirement"], "tests" to tests,
        "integrationTests" to integrationTests,
        "integrationStatus" to if (integrationTests.isNotEmpty() && integrationTests.all { evidence[it] == true }) "PASS" else "BLOCKED",
        "status" to if (tests.isNotEmpty() && integrationTests.isNotEmpty() && missing.isEmpty() && failing.isEmpty()) "PASS" else "BLOCKED",
        "reason" to when {
            tests.isEmpty() -> "No complete acceptance proof registered"
            integrationTests.isEmpty() -> "No integrated runtime acceptance proof registered; isolated implementation is void for completion"
            missing.isNotEmpty() -> "Required tests did not execute"
            failing.isNotEmpty() -> "Required tests failed or were skipped"
            else -> "All required acceptance and integration tests passed"
        },
        "missingTests" to missing, "failingOrSkippedTests" to failing,
        "partialTestResults" to partialTests.associateWith { evidence[it] },
    )
}

tasks.register("adrCompletionGateSelfTest") {
    group = "verification"
    description = "Verifies that ADR completion requires executed, passing acceptance evidence."
    doLast {
        fun status(tests: List<String>, evidence: Map<String, Boolean>, partialTests: List<String> = emptyList(), integrationTests: List<String> = emptyList()) =
            adrCompletionOutcome(mapOf("tests" to tests, "partialTests" to partialTests, "integrationTests" to integrationTests), evidence)["status"]
        check(status(emptyList(), emptyMap()) == "BLOCKED")
        check(status(listOf("suite#test"), emptyMap()) == "BLOCKED")
        check(status(listOf("suite#test"), mapOf("suite#test" to false)) == "BLOCKED")
        check(status(listOf("suite#test"), mapOf("suite#test" to true)) == "BLOCKED")
        check(status(listOf("suite#test"), mapOf("suite#test" to true), integrationTests = listOf("suite#integration")) == "BLOCKED")
        check(status(listOf("suite#test"), mapOf("suite#test" to true, "suite#integration" to false), integrationTests = listOf("suite#integration")) == "BLOCKED")
        check(status(listOf("suite#test"), mapOf("suite#test" to true, "suite#integration" to true), integrationTests = listOf("suite#integration")) == "PASS")
        check(status(emptyList(), mapOf("suite#integration" to true), integrationTests = listOf("suite#integration")) == "BLOCKED")
        check(status(listOf("suite#test", "suite#missing"), mapOf("suite#test" to true)) == "BLOCKED")
        check(status(emptyList(), mapOf("suite#partial" to true), listOf("suite#partial")) == "BLOCKED")
        check(runCatching { status(listOf("suite#test", "suite#test"), emptyMap()) }.isFailure)
        check(runCatching { status(listOf("not-a-test-identifier"), emptyMap()) }.isFailure)
        check(runCatching { status(listOf("suite#test"), emptyMap(), listOf("suite#integration"), listOf("suite#integration")) }.isFailure)
        logger.lifecycle("ADR completion gate self-tests passed.")
    }
}

tasks.named("check") { dependsOn("adrCompletionGateSelfTest") }

val adr056Document = rootProject.file("docs/adrs/056-quality-governed-model-context-compilation.md")
val adr056Accepted = Regex("(?m)^## Status\\s*\\n\\s*Accepted\\s*$").containsMatchIn(adr056Document.readText())
if (adr056Accepted) tasks.named("check") { dependsOn("adr056CompletionGate") }

val adrCompletionRequested = gradle.startParameter.taskNames.any {
    it.substringAfterLast(':') in setOf("adrCompletionGate", "adr056CompletionGate")
} || adr056Accepted
gradle.taskGraph.whenReady {
    if (adrCompletionRequested) {
        val report = layout.buildDirectory.file("reports/adr-completion/056.json").get().asFile
        report.parentFile.mkdirs()
        report.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf(
            "schemaVersion" to 1, "adr" to "056", "complete" to false,
            "gateRunState" to "NOT_COMPLETED", "reason" to "This invocation has not established complete integrated acceptance evidence",
        ))) + "\n")
        val explicitGateRequest = gradle.startParameter.taskNames.any {
            it.substringAfterLast(':') in setOf("adrCompletionGate", "adr056CompletionGate")
        }
        check(!explicitGateRequest || hasTask(tasks.named("adr056CompletionGate").get())) {
            "ADR completion gate cannot be excluded from its own verification command"
        }
    }
}
tasks.named<org.gradle.api.tasks.testing.Test>("jvmTest") {
    if (adrCompletionRequested) {
        ignoreFailures = true
        doFirst {
            check(filter.includePatterns.isEmpty() && filter.excludePatterns.isEmpty() &&
                gradle.startParameter.taskRequests.none { "--tests" in it.args }) {
                "ADR completion requires the full backend suite; test filters are not allowed"
            }
        }
    }
}

tasks.register("adrCompletionGate") {
    group = "verification"
    description = "Runs the complete ADR 056 acceptance gate, not just ordinary regression tests."
    dependsOn("adr056CompletionGate")
}

tasks.register("adr056CompletionGate") {
    group = "verification"
    description = "Checks every ADR 056 completion criterion against fresh backend test evidence."
    dependsOn("jvmTest", "adrCompletionGateSelfTest")
    doLast {
        check(tasks.named("jvmTest").get().state.executed) { "ADR completion requires jvmTest; excluding it is not allowed" }
        val manifestFile = rootProject.file("docs/adrs/056-completion-gate.json")
        val manifest = groovy.json.JsonSlurper().parse(manifestFile) as Map<*, *>
        require(manifest["schemaVersion"] == 1 && manifest["adr"] == "056") { "Unsupported ADR completion manifest" }
        require(manifest["acceptancePolicy"] == "integrated-runtime-proof-v1") { "ADR completion requires integrated runtime acceptance evidence" }
        val criteria = manifest["criteria"] as List<*>
        val expectedIds = setOf(
            "attention-before-retrieval", "final-request-traceability", "binding-and-provider-accounting",
            "reserve-and-aperture-authority", "metadata-floor", "missing-required-evidence",
            "headers-are-not-behavior", "dependency-evidence-after-compaction", "evidence-only-authority",
            "typed-stage-contract", "canonical-pinned-references", "complete-coordinate-coverage",
            "independent-quality-report", "historical-replay-and-failed-report",
        )
        val ids = criteria.map { (it as Map<*, *>)["id"] as String }
        require(ids.size == expectedIds.size && ids.toSet() == expectedIds) { "ADR 056 requires all 14 distinct criteria" }
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        val evidence = mutableMapOf<String, Boolean>()
        layout.buildDirectory.dir("test-results/jvmTest").get().asFile.listFiles()
            .orEmpty().filter { it.name.endsWith(".xml") }.forEach { result ->
                val cases = factory.newDocumentBuilder().parse(result).getElementsByTagName("testcase")
                for (index in 0 until cases.length) {
                    val case = cases.item(index) as org.w3c.dom.Element
                    val key = case.getAttribute("classname") + "#" + case.getAttribute("name").removeSuffix("[jvm]")
                    val passed = listOf("failure", "error", "skipped").all { case.getElementsByTagName(it).length == 0 }
                    evidence[key] = evidence.getOrDefault(key, true) && passed
                }
            }
        val outcomes = criteria.map { adrCompletionOutcome(it as Map<*, *>, evidence) }
        val blocked = outcomes.filter { it["status"] != "PASS" }
        val regressionFailures = evidence.filterValues { !it }.keys.sorted()
        val complete = blocked.isEmpty() && regressionFailures.isEmpty() && evidence.isNotEmpty()
        val digest = MessageDigest.getInstance("SHA-256")
        val snapshotFiles = fileTree("src").files + setOf(buildFile, rootProject.buildFile, manifestFile, rootProject.file(manifest["document"] as String))
        snapshotFiles.sortedBy { it.relativeTo(rootProject.projectDir).invariantSeparatorsPath }.forEach {
            digest.update(it.relativeTo(rootProject.projectDir).invariantSeparatorsPath.toByteArray())
            digest.update(0.toByte())
            digest.update(it.readBytes())
            digest.update(0.toByte())
        }
        val sourceSnapshotHash = digest.digest().joinToString("") { "%02x".format(it) }
        val report = layout.buildDirectory.file("reports/adr-completion/056.json").get().asFile
        report.parentFile.mkdirs()
        report.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(mapOf(
            "schemaVersion" to 1, "adr" to "056", "acceptancePolicy" to manifest["acceptancePolicy"], "complete" to complete, "sourceSnapshotHash" to sourceSnapshotHash,
            "gateRunState" to if (complete) "PASSED" else "BLOCKED",
            "executedTestCount" to evidence.size, "regressionFailures" to regressionFailures, "criteria" to outcomes,
        ))) + "\n")
        blocked.forEach { logger.error("ADR 056 BLOCKED: ${it["id"]}: ${it["requirement"]}") }
        check(complete) { "ADR 056 is incomplete: ${blocked.size}/14 criteria blocked, ${regressionFailures.size} failed or skipped tests. Report: $report" }
        logger.lifecycle("ADR 056 completion gate passed: 14/14 criteria.")
    }
}
