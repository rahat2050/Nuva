import java.io.ByteArrayOutputStream
import org.gradle.api.execution.TaskExecutionListener
import org.gradle.api.tasks.TaskState

// Root build file — plugin versions come from gradle/libs.versions.toml.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// ---------------------------------------------------------------------------
// CI diagnostics (GitHub Actions only) — no effect on local builds.
//
// The Actions log/artifact CDN is unreachable from some development
// environments, so build failures are re-published as CHECK-RUN ANNOTATIONS
// ("::error" workflow commands), which remain readable through the REST API.
// The finalizer also runs the JVM unit tests best-effort and reports their
// outcome (and the produced APK's sha256) the same way.
//
// NUVA_CI_NESTED guards the inner invocation so the hook never recurses.
// ---------------------------------------------------------------------------
if (System.getenv("GITHUB_ACTIONS") == "true" && System.getenv("NUVA_CI_NESTED") == null) {
    val taskFailures = mutableListOf<String>()

    gradle.addListener(object : TaskExecutionListener {
        override fun beforeExecute(task: org.gradle.api.Task) = Unit

        override fun afterExecute(task: org.gradle.api.Task, state: TaskState) {
            state.failure?.let { failure ->
                val detail = buildString {
                    append(failure.javaClass.simpleName)
                    failure.message?.let { append(": ").append(it.take(500)) }
                    failure.cause?.let { cause ->
                        append(" | caused by ").append(cause.javaClass.simpleName)
                        cause.message?.let { m -> append(": ").append(m.take(500)) }
                    }
                }
                taskFailures += "${task.path} → $detail"
            }
        }
    })

    val ciDiagnostics = tasks.register("ciDiagnostics") {
        doLast {
            val out = ByteArrayOutputStream()
            val err = ByteArrayOutputStream()
            val testRun = runCatching {
                exec {
                    commandLine(
                        "gradle", "--no-daemon", "--console=plain", "-q",
                        "-p", rootDir.absolutePath,
                        ":app:testDebugUnitTest",
                    )
                    environment("NUVA_CI_NESTED", "1")
                    standardOutput = out
                    errorOutput = err
                    isIgnoreExitValue = true
                }
            }

            val output = out.toString("UTF-8") + "\n" + err.toString("UTF-8")
            val compileErrors = output.lineSequence().filter { it.startsWith("e: ") }.distinct().toList()
            val testFailures = output.lineSequence().filter { it.contains(" FAILED") }.distinct().toList()

            fun announce(level: String, title: String, body: String) {
                if (body.isBlank()) return
                val clean = body.trim()
                    .replace("\r", "")
                    .replace("%", "%25")
                    .replace("\n", "%0A")
                    .take(3600)
                println("::$level title=$title::$clean")
            }

            if (compileErrors.isNotEmpty()) {
                announce("error", "compile-errors", compileErrors.joinToString("\n"))
            }
            if (testFailures.isNotEmpty()) {
                announce("error", "unit-test-failures", testFailures.joinToString("\n"))
            }
            taskFailures.forEachIndexed { index, failure ->
                announce("error", "task-failure-${index + 1}", failure)
            }

            val apk = rootDir.resolve("app/build/outputs/apk/debug/app-debug.apk")
            if (apk.isFile) {
                val digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(apk.readBytes())
                    .joinToString("") { "%02x".format(it) }
                announce("notice", "apk", "app-debug.apk built (${apk.length() / 1024} KB), sha256=$digest")
            }

            if (compileErrors.isEmpty() && testFailures.isEmpty() && taskFailures.isEmpty()) {
                val testOk = testRun.getOrNull()?.exitValue == 0
                announce(
                    if (testOk) "notice" else "warning",
                    "unit-tests",
                    if (testOk) "unit tests passed" else "nested unit-test run could not be verified — see the run log",
                )
            }
        }
    }

    subprojects {
        plugins.withId("com.android.application") {
            tasks.matching { it.name == "assembleDebug" }.configureEach {
                finalizedBy(ciDiagnostics)
            }
        }
    }
}
