package com.fcd.dashboard

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

data class StepView(
    val step: Int,
    val name: String,
    val title: String,
    val outcome: String,
    val reason: String?,
    val seconds: Double?,
)

data class GateView(
    val gate: String,
    val afterSteps: String,
    val outcome: String,
    val reason: String?,
    val explains: List<String>,
)

data class TestView(
    val subProduct: String,
    val testType: String,
    val tests: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val seconds: Double,
    val failures: String?,
    val run: String,
    val at: Instant,
)

data class RunView(
    val run: String,
    val product: String,
    val subProduct: String,
    val version: String,
    val environment: String,
    val triggerKind: String,
    val triggerName: String,
    val releaseType: String,
    val commit: String,
    val started: Instant,
    val finished: Instant?,
    val outcome: String,
    val seconds: Double?,
    val stuckAt: String?,
    val warnings: String?,
    val steps: List<StepView>,
    val gates: List<GateView>,
    val tests: List<TestView>,
)

data class DeployedView(
    val version: String,
    val containers: Map<String, String>,
    val environment: String,
    val at: Instant,
)

data class ReleaseView(
    val tag: String,
    val releaseType: String,
    val at: Instant,
)

data class ExplainView(
    val id: String,
    val gate: String,
    val rule: String?,
    val reason: String?,
    val approver: String?,
    val until: String?,
    val lastUsed: Instant,
)

data class SubProductView(
    val product: String,
    val name: String,
    val status: String,
    val lastRun: RunView?,
    val deployed: DeployedView?,
    val releases: List<ReleaseView>,
    val explains: List<ExplainView>,
    val runs: List<RunSummary>,
)

data class RunSummary(
    val run: String,
    val version: String,
    val environment: String,
    val started: Instant,
    val outcome: String,
    val stuckAt: String?,
    val triggerName: String,
)

data class TestTotals(
    val testType: String,
    val tests: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val seconds: Double,
)

data class ProductView(
    val name: String,
    val status: String,
    val subProducts: List<SubProductView>,
    val tests: List<TestTotals>,
)

data class Overview(
    val showcase: String,
    val experiment: String,
    val experiments: List<String>,
    val generatedAt: Instant,
    val products: List<ProductView>,
)

data class LoglineRow(
    val id: String,
    val time: Instant,
    val seconds: Double,
    val triggerKind: String,
    val triggerName: String,
    val product: String,
    val subProduct: String,
    val version: String,
    val environment: String,
    val type: String,
    val action: String,
    val outcome: String,
    val reason: String?,
    val run: String,
)

/** Builds what the Dashboard shows from the log lines of one experiment. */
class Views(
    private val store: EventStore,
    private val defaultExperiment: String,
    /** The showcase of this Dashboard (from its environment); every showcase has its own Dashboard and store. */
    val showcase: String = "",
) {
    fun experiment(requested: String?): String = requested?.takeIf { it.isNotBlank() } ?: store.latestExperiment() ?: defaultExperiment

    fun overview(requested: String?): Overview {
        val experiment = experiment(requested)
        val lines = store.all(experiment)
        val products =
            lines
                .filter { it.product.isNotBlank() }
                .groupBy { it.product }
                .map { (product, productLines) ->
                    val subs = productLines.groupBy { it.subProduct }.map { (name, l) -> subProduct(product, name, l) }.sortedBy { it.name }
                    ProductView(product, worst(subs.map { it.status }), subs, totals(subs.flatMap { it.lastRun?.tests.orEmpty() }))
                }.sortedBy { it.name }
        val experiments = store.experiments().filterNot { it.startsWith(TEST_EXPERIMENT_PREFIX) } + experiment
        return Overview(showcase, experiment, experiments.distinct().sorted(), Instant.now(), products)
    }

    fun subProduct(
        requested: String?,
        product: String,
        name: String,
    ): SubProductView? {
        val lines = store.all(experiment(requested)).filter { it.product == product && it.subProduct == name }
        return if (lines.isEmpty()) null else subProduct(product, name, lines)
    }

    fun tests(requested: String?): List<TestView> =
        store
            .all(experiment(requested))
            .filter { it.shortType == "testsuiterun.finished" }
            .groupBy { it.subProduct to it.content.path("testType").asText() }
            .map { (_, l) -> test(l.last()) }
            .sortedWith(compareBy({ it.subProduct }, { TEST_ORDER.indexOf(it.testType) }))

    fun loglines(
        requested: String?,
        limit: Int,
    ): List<LoglineRow> =
        store
            .all(experiment(requested))
            .asReversed()
            .take(limit)
            .map { row(it) }

    private fun subProduct(
        product: String,
        name: String,
        lines: List<Logline>,
    ): SubProductView {
        val runs =
            lines
                .filter { it.run.isNotBlank() }
                .groupBy { it.run }
                .map { (id, l) -> run(id, l) }
                .sortedBy { it.started }
        val last = runs.lastOrNull()
        val deployed =
            lines.lastOrNull { it.shortType == "service.deployed" && it.custom.path("environment").asText() == "lokaal" }?.let {
                DeployedView(version(it), containers(it), "lokaal", it.timestamp)
            }
        val releases =
            lines.filter { it.shortType == "artifact.published" }.map {
                ReleaseView(it.content.path("tag").asText(), it.content.path("releaseType").asText("planned"), it.timestamp)
            }
        val status =
            when (last?.outcome) {
                null -> "unknown"
                "running" -> "running"
                "success" -> "green"
                else -> "red"
            }
        return SubProductView(
            product = product,
            name = name,
            status = status,
            lastRun = last,
            deployed = deployed,
            releases = releases.asReversed(),
            explains = explains(lines),
            runs =
                runs.asReversed().take(20).map {
                    RunSummary(it.run, it.version, it.environment, it.started, it.outcome, it.stuckAt, it.triggerName)
                },
        )
    }

    private fun run(
        id: String,
        lines: List<Logline>,
    ): RunView {
        val first = lines.first()
        val started = lines.firstOrNull { it.shortType == "pipelinerun.started" }?.timestamp ?: first.timestamp
        val finishedLine = lines.lastOrNull { it.shortType == "pipelinerun.finished" }
        val steps =
            lines
                .filter { it.shortType == "taskrun.started" || it.shortType == "taskrun.finished" }
                .groupBy { it.content.path("step").asInt() }
                .map { (step, l) ->
                    val end = l.lastOrNull { it.shortType == "taskrun.finished" }
                    val c = (end ?: l.last()).content
                    StepView(
                        step = step,
                        name = c.path("taskName").asText(),
                        title = c.path("title").asText(),
                        outcome = end?.content?.path("outcome")?.asText() ?: "running",
                        reason = c.path("reason").asText("").ifBlank { null },
                        seconds = end?.custom?.path("durationSeconds")?.asDouble(),
                    )
                }.sortedBy { it.step }
        val gates =
            lines.filter { it.shortType == "qualitygate.evaluated" }.map {
                val c = it.content
                GateView(
                    gate = c.path("gate").asText(),
                    afterSteps = c.path("afterSteps").asText(),
                    outcome = c.path("outcome").asText(),
                    reason = c.path("reason").asText("").ifBlank { null },
                    explains =
                        c
                            .path("explain")
                            .asText("")
                            .split(",")
                            .filter { s -> s.isNotBlank() },
                )
            }
        val outcome = finishedLine?.content?.path("outcome")?.asText() ?: "running"
        val stuckAt =
            gates.firstOrNull { it.outcome == "closed" }?.let { "${it.gate}-gate" }
                // Without a closed gate the run stopped at the last failed step (an earlier one can be covered by an explain).
                ?: steps.lastOrNull { it.outcome == "failed" }?.title?.takeIf { outcome == "failure" }
        val c = first.custom
        return RunView(
            run = id,
            product = first.product,
            subProduct = first.subProduct,
            version = version(first),
            environment = c.path("environment").asText(),
            triggerKind = c.path("trigger").path("kind").asText(),
            triggerName = c.path("trigger").path("name").asText(),
            releaseType = c.path("releaseType").asText("planned"),
            commit = c.path("commit").path("sha").asText(""),
            started = started,
            finished = finishedLine?.timestamp,
            outcome = outcome,
            seconds = finishedLine?.custom?.path("durationSeconds")?.asDouble(),
            stuckAt = stuckAt,
            warnings =
                finishedLine
                    ?.content
                    ?.path("warnings")
                    ?.asText("")
                    ?.ifBlank { null },
            steps = steps,
            gates = gates,
            tests = lines.filter { it.shortType == "testsuiterun.finished" }.map { test(it) },
        )
    }

    private fun explains(lines: List<Logline>): List<ExplainView> =
        lines
            .filter { it.shortType == "qualitygate.evaluated" }
            .flatMap { line ->
                val details = line.content.path("explainDetails")
                val ids =
                    line.content
                        .path("explain")
                        .asText("")
                        .split(",")
                        .filter { it.isNotBlank() }
                ids.map { id ->
                    val d: JsonNode? = details.firstOrNull { it.path("id").asText() == id }
                    ExplainView(
                        id = id,
                        gate = line.content.path("gate").asText(),
                        rule = d?.path("rule")?.asText(),
                        reason = d?.path("reason")?.asText(),
                        approver = d?.path("approver")?.asText(),
                        until = d?.path("until")?.asText(),
                        lastUsed = line.timestamp,
                    )
                }
            }.groupBy { it.id }
            .map { (_, l) -> l.last() }

    private fun test(line: Logline): TestView {
        val c = line.content
        return TestView(
            subProduct = line.subProduct,
            testType = c.path("testType").asText(),
            tests = c.path("tests").asInt(),
            passed = c.path("passed").asInt(),
            failed = c.path("failed").asInt(),
            skipped = c.path("skipped").asInt(),
            seconds = c.path("seconds").asDouble(),
            failures = c.path("failures").asText("").ifBlank { null },
            run = line.run,
            at = line.timestamp,
        )
    }

    private fun row(line: Logline): LoglineRow {
        val c = line.content
        val (action, outcome) =
            when (line.shortType) {
                "taskrun.started", "taskrun.finished" -> {
                    "stap ${c.path("step").asText()} ${c.path("title").asText()}" to
                        c.path("outcome").asText()
                }

                "qualitygate.evaluated" -> {
                    "${c.path("gate").asText()}-gate" to c.path("outcome").asText()
                }

                "qualitygate.started" -> {
                    "${c.path("gate").asText()}-gate" to if (c.path("waitingFor").isObject) "waiting" else "running"
                }

                "testsuiterun.started" -> {
                    "tests ${c.path("testType").asText()}" to "running"
                }

                "testsuiterun.progressed" -> {
                    "tests ${c.path("testType").asText()} (${c.path("passed").asInt()}/${c.path("tests").asInt()})" to "running"
                }

                "testsuiterun.finished" -> {
                    "tests ${c.path("testType").asText()} (${c.path("passed").asInt()}/${c.path("tests").asInt()})" to
                        c.path("outcome").asText()
                }

                "pipelinerun.started", "pipelinerun.finished" -> {
                    "run" to c.path("outcome").asText()
                }

                "service.deployed" -> {
                    "uitgerold" to "succeeded"
                }

                "artifact.published" -> {
                    "release ${c.path("tag").asText()}" to "succeeded"
                }

                "keyresult.measured" -> {
                    "key result bestelling" to if (c.path("complete").asBoolean()) "complete" else "incomplete"
                }

                else -> {
                    line.shortType to ""
                }
            }
        return LoglineRow(
            id = line.id,
            time = line.timestamp,
            seconds = line.custom.path("durationSeconds").asDouble(),
            triggerKind =
                line.custom
                    .path("trigger")
                    .path("kind")
                    .asText(),
            triggerName =
                line.custom
                    .path("trigger")
                    .path("name")
                    .asText(),
            product = line.product,
            subProduct = line.subProduct,
            version = version(line),
            environment = line.custom.path("environment").asText(),
            type = line.shortType,
            action = action,
            outcome = outcome,
            reason = c.path("reason").asText("").ifBlank { c.path("errors").asText("").ifBlank { null } },
            run = line.run,
        )
    }

    companion object {
        val TEST_ORDER = listOf("unit", "contract", "integration", "end-to-end")

        fun totals(tests: List<TestView>): List<TestTotals> =
            tests
                .groupBy { it.testType }
                .map { (type, l) ->
                    TestTotals(
                        type,
                        l.sumOf { it.tests },
                        l.sumOf { it.passed },
                        l.sumOf { it.failed },
                        l.sumOf { it.skipped },
                        l.sumOf { it.seconds },
                    )
                }.sortedBy { TEST_ORDER.indexOf(it.testType) }

        fun worst(statuses: List<String>): String =
            when {
                "red" in statuses -> "red"
                "running" in statuses -> "running"
                "green" in statuses -> "green"
                else -> "unknown"
            }

        private fun version(line: Logline) =
            line.custom
                .path("subProduct")
                .path("version")
                .asText()

        private fun containers(line: Logline): Map<String, String> =
            line.custom
                .path("subProduct")
                .path("containers")
                .properties()
                .associate { it.key to it.value.asText() }
    }
}
