package com.orchard.backend.agent

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

data class TestExecutionEvidence(
    val executedTests: Int,
    val failedTests: Int,
    val reportsHash: String,
    val verifiedForRevision: Boolean,
    val missingTestTasks: List<String> = emptyList(),
)

internal fun jvmTestEvidenceDiagnostic(kind: String, observation: VerificationObservation): String? {
    val tokens = observation.command.split(' ')
    if (tokens.first().substringAfterLast('/') !in JVM_BUILD_EXECUTABLES) return null
    val explicitlyTests = tokens.drop(1).any {
        !it.startsWith("-") && (it.substringAfterLast(':').endsWith("test", ignoreCase = true) || it == "integration-test")
    }
    if (kind !in setOf("TEST", "REGRESSION_TEST") && !explicitlyTests) return null
    val evidence = observation.testExecution
        ?: return "JVM test verification has no observed JUnit report evidence."
    if (evidence.executedTests <= 0) return "JVM test verification executed no tests; empty or entirely skipped suites are not passing test evidence."
    if (evidence.missingTestTasks.isNotEmpty()) return "No executed JUnit tests for requested tasks: ${evidence.missingTestTasks.joinToString()}."
    if (evidence.failedTests != 0) return "JUnit reports contain ${evidence.failedTests} failed or errored tests."
    if (!evidence.verifiedForRevision) return "JUnit reports are stale or unverified for this candidate; rerun the admitted tests rather than reusing unrelated reports."
    return null
}

internal class JvmTestEvidenceCollector {
    private data class VerifiedReports(val revision: String, val hashes: Set<String>)
    private val verified = ConcurrentHashMap<Path, VerifiedReports>()

    fun collect(root: Path, command: VerificationCommand, startedAtMillis: Long, revision: String?): TestExecutionEvidence? {
        val executable = command.executable.substringAfterLast('/')
        if (executable !in JVM_BUILD_EXECUTABLES) return null
        val gradle = executable in setOf("gradle", "gradlew")
        val tasks = command.arguments.filter { !it.startsWith("-") && it.substringAfterLast(':').endsWith("test", ignoreCase = true) }
        val reports = Files.walk(root).use { paths ->
            paths.filter { path ->
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return@filter false
                val relative = root.relativize(path).toString().replace('\\', '/')
                if (!path.fileName.toString().let { it.startsWith("TEST-") && it.endsWith(".xml") }) return@filter false
                if (gradle) {
                    val match = GRADLE_REPORT.matchEntire(relative) ?: return@filter false
                    tasks.isEmpty() || tasks.any { task ->
                        val module = task.trimStart(':').substringBeforeLast(':', "")
                        match.groupValues[2] == task.substringAfterLast(':') &&
                            (module.isEmpty() || match.groupValues[1] == module.replace(':', '/') + "/")
                    }
                } else {
                    val integration = command.arguments.any { it in setOf("integration-test", "verify", "install", "deploy") }
                    relative.contains("target/surefire-reports/") ||
                        integration && relative.contains("target/failsafe-reports/")
                }
            }.limit(1_025).toList().sorted()
        }
        require(reports.size <= 1_024) { "Too many JUnit reports to validate" }
        val prior = verified[root]?.takeIf { it.revision == revision }
        val verifiedHashes = mutableSetOf<String>()
        var executed = 0
        var failed = 0
        var allVerified = reports.isNotEmpty()
        val executedByTask = tasks.associateWith { 0 }.toMutableMap()
        val identities = reports.map { path ->
            val bytes = Files.newInputStream(path).use { it.readNBytes(4 * 1024 * 1024 + 1) }
            require(bytes.size <= 4 * 1024 * 1024) { "JUnit report is too large: ${root.relativize(path)}" }
            val identity = root.relativize(path).toString() + ":" + sha256Content(bytes.toString(Charsets.UTF_8))
            val fresh = Files.getLastModifiedTime(path).toMillis() >= startedAtMillis
            val trusted = fresh || identity in prior?.hashes.orEmpty()
            if (trusted) verifiedHashes += identity else allVerified = false
            val factory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                setFeature("http://xml.org/sax/features/external-general-entities", false)
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
                setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
                isXIncludeAware = false
                isExpandEntityReferences = false
            }
            val suite = factory.newDocumentBuilder().parse(bytes.inputStream()).documentElement
            require(suite.tagName == "testsuite") { "Unsupported JUnit report root: ${suite.tagName}" }
            val tests = suite.count("tests")
            val skipped = suite.count("skipped", optional = true)
            val failures = suite.count("failures", optional = true) + suite.count("errors", optional = true)
            require(tests == suite.getElementsByTagName("testcase").length &&
                skipped == suite.getElementsByTagName("skipped").length) { "JUnit totals do not match test cases" }
            require(skipped <= tests && failures <= tests - skipped) { "Inconsistent JUnit report totals" }
            executed += tests - skipped
            failed += failures
            if (gradle) {
                val match = requireNotNull(GRADLE_REPORT.matchEntire(root.relativize(path).toString().replace('\\', '/')))
                tasks.forEach { task ->
                    val module = task.trimStart(':').substringBeforeLast(':', "")
                    if (match.groupValues[2] == task.substringAfterLast(':') &&
                        (module.isEmpty() || match.groupValues[1] == module.replace(':', '/') + "/")) {
                        executedByTask[task] = executedByTask.getValue(task) + tests - skipped
                    }
                }
            }
            identity
        }
        if (revision != null) {
            verified[root] = VerifiedReports(revision, prior?.hashes.orEmpty() + verifiedHashes)
        }
        return TestExecutionEvidence(executed, failed, sha256Content(identities.joinToString("\n")), allVerified,
            if (gradle) executedByTask.filterValues { it == 0 }.keys.toList() else emptyList())
    }

    private fun Element.count(name: String, optional: Boolean = false): Int {
        val text = getAttribute(name)
        if (optional && text.isEmpty()) return 0
        return requireNotNull(text.toIntOrNull()?.takeIf { it >= 0 }) { "Invalid JUnit $name count" }
    }
}

private val JVM_BUILD_EXECUTABLES = setOf("gradle", "gradlew", "mvn", "mvnw")
private val GRADLE_REPORT = Regex("""(.*/)?build/test-results/([^/]+)/TEST-[^/]+\.xml""")
