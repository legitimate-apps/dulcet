package com.legitimateapps.dulcet.apiconformance

import com.fasterxml.jackson.databind.node.ObjectNode
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** The run's evidence: JSON, JUnit XML and the one-line summary. Every string passes through redaction. */
class Report(private val run: Run, val results: List<CheckResult>) {
    private val finishedAt: Instant = Instant.now()

    val passed get() = results.count { it.outcome == Outcome.PASS }
    val failed get() = results.count { it.outcome == Outcome.FAIL }
    val skipped get() = results.count { it.outcome == Outcome.SKIP }
    val manual get() = results.count { it.outcome == Outcome.MANUAL }
    /** A claim needs every selected check to have run and passed: no failure, skip or manual check. */
    val complete get() = failed == 0 && skipped == 0 && manual == 0
    private val profiles get() = run.config.profiles.sortedBy { it.ordinal }.joinToString(",") { it.name }

    fun summaryLine(): String =
        "DULCET-CONFORMANCE suite=$SUITE_VERSION profiles=$profiles passed=$passed failed=$failed skipped=$skipped manual=$manual complete=$complete"

    fun json(): ObjectNode {
        val root = json.createObjectNode()
        root.put("suite", "dulcet-api-conformance")
        root.put("suiteVersion", SUITE_VERSION)
        root.put("contractVersion", run.contract.version)
        root.put("contractSha256", run.contract.sha256)
        root.put("profiles", profiles)
        root.put("server", run.origin)
        run.discovery?.path("server")?.let { server ->
            root.put("instanceId", server.str("instanceId"))
            root.put("serverName", server.str("name"))
            root.put("serverVersion", server.str("version"))
        }
        root.put("startedAt", run.startedAt.toString())
        root.put("finishedAt", finishedAt.toString())
        root.put("oauth", run.config.oauthMode.name.lowercase())
        root.put("refreshWindowSeconds", run.config.refreshWindowSeconds)
        root.put("summary", summaryLine())
        val counts = root.putObject("counts")
        for (profile in run.config.profiles) {
            val mine = results.filter { it.profile == profile }
            counts.putObject(profile.name).apply {
                put("passed", mine.count { it.outcome == Outcome.PASS })
                put("failed", mine.count { it.outcome == Outcome.FAIL })
                put("skipped", mine.count { it.outcome == Outcome.SKIP })
                put("manual", mine.count { it.outcome == Outcome.MANUAL })
            }
        }
        val checks = root.putArray("checks")
        for (r in results) checks.addObject().apply {
            put("id", r.id)
            put("profile", r.profile.name)
            put("title", r.title)
            put("ref", r.ref)
            put("outcome", r.outcome.name)
            put("message", r.message)
            put("durationMs", r.durationMs)
        }
        return root
    }

    fun writeJson(path: Path) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(path, run.secrets.redact(json.writerWithDefaultPrettyPrinter().writeValueAsString(json())))
    }

    fun junitXml(): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        sb.append("<testsuites name=\"dulcet-api-conformance\" tests=\"${results.size}\" failures=\"$failed\" skipped=\"$skipped\">\n")
        for (profile in run.config.profiles.sortedBy { it.ordinal }) {
            val mine = results.filter { it.profile == profile }
            sb.append("  <testsuite name=\"Dulcet API ${profile.name}\" tests=\"${mine.size}\" failures=\"${mine.count { it.outcome == Outcome.FAIL }}\" skipped=\"${mine.count { it.outcome == Outcome.SKIP || it.outcome == Outcome.MANUAL }}\" errors=\"0\" timestamp=\"${run.startedAt}\">\n")
            sb.append("    <properties>\n")
            sb.append("      <property name=\"suiteVersion\" value=\"$SUITE_VERSION\"/>\n")
            sb.append("      <property name=\"contractSha256\" value=\"${run.contract.sha256}\"/>\n")
            sb.append("      <property name=\"server\" value=\"${xml(run.origin)}\"/>\n")
            run.discovery?.path("server")?.str("instanceId")?.let { sb.append("      <property name=\"instanceId\" value=\"${xml(it)}\"/>\n") }
            sb.append("    </properties>\n")
            for (r in mine) {
                sb.append("    <testcase classname=\"dulcet.api.${profile.name}\" name=\"${xml(r.id)} ${xml(r.title)}\" time=\"${r.durationMs / 1000.0}\">")
                when (r.outcome) {
                    Outcome.FAIL -> sb.append("<failure message=\"${xml(r.message)}\">${xml(r.ref)}</failure>")
                    Outcome.SKIP -> sb.append("<skipped message=\"${xml(r.message)}\"/>")
                    Outcome.MANUAL -> sb.append("<skipped message=\"MANUAL: ${xml(r.message)}\"/>")
                    Outcome.PASS -> {}
                }
                sb.append("</testcase>\n")
            }
            sb.append("  </testsuite>\n")
        }
        sb.append("</testsuites>\n")
        return run.secrets.redact(sb.toString())
    }

    fun writeJunit(path: Path) {
        path.toAbsolutePath().parent?.let { Files.createDirectories(it) }
        Files.writeString(path, junitXml())
    }

    private fun xml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        .filter { it == '\n' || it == '\t' || it >= ' ' }
}
