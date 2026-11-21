package com.fcd.dashboard

import com.fasterxml.jackson.databind.JsonNode
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.time.Instant

data class WaitingFor(
    val kind: String,
    val name: String,
    val action: String?,
)

data class LiveTests(
    val testType: String,
    val tests: Int,
    val passed: Int,
    val failed: Int,
    val skipped: Int,
    val expected: Int?,
    val finished: Boolean,
)

data class Shortened(
    val explain: String,
    val reason: String? = null,
    val until: String? = null,
)

data class LiveItem(
    val kind: String,
    val name: String,
    val title: String,
    val status: String,
    val container: String? = null,
    val shortened: Shortened? = null,
    val step: Int? = null,
    val startedAt: Instant? = null,
    val seconds: Double? = null,
    val reason: String? = null,
    val excerpt: String? = null,
    val explains: List<String> = emptyList(),
    val waitingFor: WaitingFor? = null,
    val tests: LiveTests? = null,
)

data class LiveFailure(
    val title: String,
    val reason: String?,
    val excerpt: String?,
    val failures: String?,
)

data class LiveRun(
    val product: String,
    val subProduct: String,
    val title: String,
    val run: String,
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
    val waitingFor: WaitingFor?,
    val failure: LiveFailure?,
    val tag: String?,
    val deployed: Boolean,
    val items: List<LiveItem>,
    val containers: Map<String, String> = emptyMap(),
    val containerRows: List<LiveContainerRow> = emptyList(),
    /** The gate before the first step (the human gate at the start), shown in the head of the run. */
    val startGate: LiveItem? = null,
)

data class LiveContainerRow(
    val container: String,
    val title: String,
    val version: String?,
    val items: List<LiveItem>,
)

data class Changelog(
    val product: String,
    val subProduct: String,
    val title: String,
    val version: String,
    val containers: Map<String, String>,
    val deployedAt: Instant,
    val text: String,
)

/** Where the demo is: after the reset a prepared change waits to be merged (start), or nothing waits (merged). */
data class DemoState(
    val phase: String,
    val unmerged: List<String>,
)

/** What runs now: the version of a sub-product deployed last in this experiment. */
data class RunningVersion(
    val product: String,
    val subProduct: String,
    val title: String,
    val version: String,
    val containers: Map<String, String>,
    val deployedAt: Instant,
    val stubs: List<StubView> = emptyList(),
    val productTitle: String = product,
)

/** A stub that runs along, with its version (from service.deployed alongside) and the receiver it stands in for. */
data class StubView(
    val name: String,
    val title: String,
    val receiver: String,
    val version: String?,
)

data class LiveState(
    val showcase: String,
    val experiment: String,
    val serverTime: Instant,
    val settings: LiveSettings,
    val runs: List<LiveRun>,
    val controls: Controls = Controls(),
    val commands: List<Command> = emptyList(),
    val demo: DemoState? = null,
    val running: List<RunningVersion> = emptyList(),
    val dashboardVersion: String = "dev",
)

/** Tells the open live streams that a new log line arrived (one backend per showcase: in memory is enough). */
class LiveHub {
    private val flow = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val changes: SharedFlow<String> = flow.asSharedFlow()

    fun changed(experiment: String) {
        flow.tryEmit(experiment)
    }
}

/**
 * The live view: per sub-product the current (or last) run, with its steps and gates in the order of the configuration
 * of the showcase. Without configuration: the order of the run itself (pipelinerun.started.steps, then the log lines).
 */
class LiveViews(
    private val store: EventStore,
    private val views: Views,
    private val config: ShowcaseConfig,
    private val commands: CommandStore = InMemoryCommandStore(),
    private val version: String = "dev",
) {
    /** Why a button cannot be pressed now, or null when it can. */
    fun refusal(request: CommandRequest): String? {
        val controls = config.controls
        val open = commands.recent(50).filter { it.status == "pending" || it.status == "claimed" }
        val running = state(null).runs.filter { it.outcome == "running" && it.environment == "lokaal" }
        return when (request.kind) {
            CommandKinds.START_RUN -> {
                when {
                    !controls.startButton -> "This showcase does not start the pipeline with a button (Pipeline.spec.trigger)."

                    open.any {
                        it.kind == CommandKinds.START_RUN || it.kind == CommandKinds.DEMO_RESET
                    } -> "The Pipeline is already busy with a command."

                    running.isNotEmpty() -> "A pipeline is running."

                    else -> null
                }
            }

            CommandKinds.APPROVE_RELEASE -> {
                when {
                    request.run.isNullOrBlank() -> "Which run? (run)"

                    running.none { it.run == request.run && it.waitingFor != null } -> "This run does not wait for an approval."

                    open.any {
                        it.kind == CommandKinds.APPROVE_RELEASE && it.run == request.run
                    } -> "Already approved; the Pipeline is on it."

                    else -> null
                }
            }

            CommandKinds.DEMO_MERGE -> {
                when {
                    !controls.demoReset -> "Demo mode is off (Pipeline.spec.demo)."
                    open.isNotEmpty() -> "The Pipeline is already busy with a command."
                    running.isNotEmpty() -> "A pipeline is running."
                    state(null).demo?.phase != "start" -> "Nothing waits to be merged."
                    else -> null
                }
            }

            CommandKinds.DEMO_RESET -> {
                when {
                    !controls.demoReset -> "Demo mode is off (Pipeline.spec.demo)."
                    open.isNotEmpty() -> "The Pipeline is already busy with a command."
                    running.isNotEmpty() -> "A pipeline is running; reset after it."
                    else -> null
                }
            }

            else -> {
                "Unknown command ${request.kind}."
            }
        }
    }

    /** Per sub-product the changelog of the version deployed last (log line service.deployed with a changelog). */
    fun changelogs(requested: String?): List<Changelog> =
        store
            .all(views.experiment(requested))
            .filter { it.shortType == "service.deployed" && it.content.path("changelog").isTextual }
            .groupBy { it.product to it.subProduct }
            .map { (_, l) ->
                val last = l.last()
                Changelog(
                    product = last.product,
                    subProduct = last.subProduct,
                    title = config.subProductTitle(last.subProduct),
                    version =
                        last.custom
                            .path("subProduct")
                            .path("version")
                            .asText(),
                    containers = last.containers(),
                    deployedAt = last.timestamp,
                    text = last.content.path("changelog").asText(),
                )
            }.sortedBy { it.subProduct }

    private fun Logline.containers(): Map<String, String> =
        custom
            .path("subProduct")
            .path("containers")
            .properties()
            .associate { (k, v) -> k to v.asText() }

    fun state(requested: String?): LiveState {
        val experiment = views.experiment(requested)
        val settings = config.live
        val lines =
            store
                .all(experiment)
                .filter { it.run.isNotBlank() && it.subProduct.isNotBlank() }
                .filter { settings.environments.isEmpty() || it.custom.path("environment").asText() in settings.environments }
                .filter { settings.subProducts.isEmpty() || it.subProduct in settings.subProducts }
        val runs =
            lines
                .groupBy { it.product to it.subProduct }
                .mapNotNull { (_, l) ->
                    // The run that started last (lines are in order of time, then of arrival: the last group wins a tie).
                    l
                        .groupBy { it.run }
                        .values
                        .lastOrNull()
                        ?.let { run(it) }
                }.sortedWith(
                    compareBy<LiveRun>(
                        { settings.subProducts.indexOf(it.subProduct).let { i -> if (i < 0) Int.MAX_VALUE else i } },
                        { it.product },
                        { it.subProduct },
                    ),
                )
        // Commands of this experiment, and the reset that started it; open commands always.
        val start = store.all(experiment).firstOrNull()?.timestamp
        val recent =
            commands.recent(10).filter {
                val since = if (it.kind == CommandKinds.DEMO_RESET) start?.minusSeconds(COMMAND_SLACK_SECONDS) else start
                it.status == "pending" || it.status == "claimed" || since == null || !it.requestedAt.isBefore(since)
            }
        val all = store.all(experiment)
        return LiveState(
            views.showcase,
            experiment,
            Instant.now(),
            settings,
            runs,
            config.controls,
            recent,
            demo(all),
            all
                .filter { it.shortType == "service.deployed" }
                .groupBy { it.product to it.subProduct }
                .map { (_, l) ->
                    val last = l.last()
                    val along = last.content.path("alongside")
                    RunningVersion(
                        last.product,
                        last.subProduct,
                        config.subProductTitle(last.subProduct),
                        last.custom
                            .path("subProduct")
                            .path("version")
                            .asText(),
                        last.containers(),
                        last.timestamp,
                        config.stubs.values.map { s ->
                            StubView(s.name, s.title, s.receiver, along.path(s.service ?: s.name).asText("").ifBlank { null })
                        },
                        config.productTitle(last.product),
                    )
                }.sortedBy { it.subProduct },
            version,
        )
    }

    /** From the log lines of the demo: the prepared changes of the last reset, minus those merged after it. */
    private fun demo(lines: List<Logline>): DemoState? {
        if (!config.controls.demoReset) return null
        val resetAt = lines.indexOfLast { it.shortType == "demo.reset" }
        if (resetAt < 0) return null

        fun key(n: com.fasterxml.jackson.databind.JsonNode) = "${n.path("repository").asText()} ${n.path("branch").asText()}"
        val waiting = lines[resetAt].content.path("branches").map(::key)
        val merged =
            lines
                .drop(resetAt + 1)
                .filter { it.shortType == "demo.merged" }
                .map { key(it.content) }
                .toSet()
        val unmerged = waiting.filterNot { it in merged }
        return DemoState(if (unmerged.isEmpty()) "merged" else "start", unmerged)
    }

    private class StepState(
        val name: String,
        var title: String,
        var step: Int? = null,
        var startedAt: Instant? = null,
        var finished: Boolean = false,
        var outcome: String? = null,
        var seconds: Double? = null,
        var reason: String? = null,
        var excerpt: String? = null,
        var tests: LiveTests? = null,
    )

    private class GateState(
        val name: String,
        var title: String,
        var started: Boolean = false,
        var outcome: String? = null,
        var reason: String? = null,
        var explains: List<String> = emptyList(),
        var waitingFor: WaitingFor? = null,
    )

    private fun run(lines: List<Logline>): LiveRun {
        val first = lines.first()
        // Keys: "step:<name>" (sub-product), "step:<name>@<container>" (per container), "gate:<name>".
        val steps = linkedMapOf<String, StepState>()
        val gates = linkedMapOf<String, GateState>()
        val order = mutableListOf<String>()
        val containers =
            config.subProducts
                .firstOrNull { it.name == first.subProduct }
                ?.containers
                ?.takeIf { it.isNotEmpty() }
                ?: lines
                    .last()
                    .containers()
                    .keys
                    .toList()
        val configured = config.lane.isNotEmpty()
        val containerLane = if (configured) config.containerLane else emptyList()
        val subProductLane = if (configured) config.subProductLane else config.lane
        // Every gate of the configuration has its state (on a row, or the start gate in the head); the order is the row.
        (if (configured) config.lane else emptyList()).filter { it.kind == "gate" }.forEach { gates[it.key] = GateState(it.name, it.title) }
        subProductLane.forEach {
            order += "${it.kind}:${it.key}"
            if (it.kind == "step") steps["step:${it.name}"] = StepState(it.name, it.title) else gates[it.key] = GateState(it.name, it.title)
        }
        for (c in containers) {
            containerLane.filter { it.kind == "step" }.forEach { steps["step:${it.name}@$c"] = StepState(it.name, it.title) }
        }
        if (!configured) {
            lines
                .firstOrNull { it.shortType == "pipelinerun.started" }
                ?.content
                ?.path("steps")
                ?.filter { it.isTextual }
                ?.forEach { n ->
                    if (steps.putIfAbsent("step:${n.asText()}", StepState(n.asText(), n.asText())) == null) order += "step:${n.asText()}"
                }
        }
        var lastStep: String? = null

        fun stepFor(
            name: String,
            container: String?,
        ): StepState {
            val key = if (container != null && config.step(name)?.perContainer == true) "step:$name@$container" else "step:$name"
            steps[key]?.let { return it }
            val s = StepState(name, name)
            steps[key] = s
            if (key.contains('@')) return s
            // An unknown step: after the step that started last, else at the end.
            val at = lastStep?.let { order.indexOf("step:$it") } ?: -1
            if (at >= 0) order.add(at + 1, key) else order += key
            return s
        }

        fun gateFor(
            name: String,
            before: String?,
        ): GateState {
            // A gate before a step has its own place per step; without beforeSteps (older Pipeline): the first undecided one.
            before?.let { b -> gates["$name@$b"]?.let { return it } }
            gates[name]?.let { return it }
            gates.entries.firstOrNull { (k, g) -> k.startsWith("$name@") && g.outcome == null }?.let { return it.value }
            val g = GateState(name, name)
            gates[name] = g
            val at = lastStep?.let { order.indexOf("step:$it") } ?: -1
            // After the last step and the gates already behind it.
            var i = if (at >= 0) at + 1 else order.size
            while (i < order.size && order[i].startsWith("gate:")) i++
            order.add(i, "gate:$name")
            return g
        }

        fun testStep(
            testType: String,
            container: String?,
        ): StepState? =
            config.steps.firstOrNull { it.testType == testType }?.let { stepFor(it.name, container) } ?: lastStep?.let { steps["step:$it"] }

        var finishedLine: Logline? = null
        var tag: String? = null
        var deployed = false
        for (line in lines) {
            val c = line.content
            val container = c.text("container")
            when (line.shortType) {
                "taskrun.started", "taskrun.finished" -> {
                    val s = stepFor(c.path("taskName").asText(), container)
                    c
                        .path("title")
                        .asText("")
                        .ifBlank { null }
                        ?.let { if (s.title == s.name) s.title = it }
                    if (c.path("step").isInt) s.step = c.path("step").asInt()
                    if (line.shortType == "taskrun.started") {
                        s.startedAt = line.timestamp
                    } else {
                        s.finished = true
                        s.outcome = c.path("outcome").asText()
                        s.seconds = line.custom.path("durationSeconds").asDouble()
                        s.reason = c.text("reason")
                        s.excerpt = c.text("excerpt")?.let(::oneLine)
                        if (s.startedAt == null) s.startedAt = line.timestamp
                    }
                    if (container == null || config.step(s.name)?.perContainer != true) lastStep = s.name
                }

                "testsuiterun.started", "testsuiterun.progressed", "testsuiterun.finished" -> {
                    val type = c.path("testType").asText()
                    val s = testStep(type, container) ?: continue
                    val before = s.tests
                    // The final count wins: a finished line carries the begin time, so it can come before the progress.
                    if (before?.finished == true && line.shortType != "testsuiterun.finished") continue
                    s.tests =
                        LiveTests(
                            testType = type,
                            tests = c.path("tests").asInt(before?.tests ?: 0),
                            passed = c.path("passed").asInt(before?.passed ?: 0),
                            failed = c.path("failed").asInt(before?.failed ?: 0),
                            skipped = c.path("skipped").asInt(before?.skipped ?: 0),
                            expected = c.path("expected").takeIf { it.isInt }?.asInt() ?: before?.expected,
                            finished = line.shortType == "testsuiterun.finished",
                        )
                    if (line.shortType == "testsuiterun.finished" && s.excerpt == null) s.excerpt = c.text("failures")?.let(::oneLine)
                }

                "qualitygate.started", "qualitygate.evaluated" -> {
                    val g = gateFor(c.path("gate").asText(), c.text("beforeSteps"))
                    if (line.shortType == "qualitygate.started") {
                        g.started = true
                        g.outcome = null
                        g.waitingFor =
                            c.path("waitingFor").takeIf { it.isObject }?.let {
                                WaitingFor(it.path("kind").asText("human"), it.path("name").asText(), it.text("action"))
                            }
                    } else {
                        g.outcome = c.path("outcome").asText()
                        g.reason = c.text("reason")
                        g.explains =
                            c
                                .path("explain")
                                .asText("")
                                .split(",")
                                .filter { it.isNotBlank() }
                        g.waitingFor = null
                    }
                }

                "pipelinerun.finished" -> {
                    finishedLine = line
                }

                "service.deployed" -> {
                    deployed = true
                }

                "artifact.published" -> {
                    tag = c.text("tag")
                }
            }
        }
        val running = finishedLine == null

        fun stepItem(
            s: StepState,
            container: String?,
        ): LiveItem {
            val cfg = config.step(s.name)
            val shortened =
                cfg?.shortened?.let { id ->
                    val e = config.explanations[id]
                    Shortened(id, e?.reason, e?.until)
                }
            return LiveItem(
                kind = "step",
                name = s.name,
                title = s.title,
                status =
                    when {
                        cfg?.backlog == true -> "backlog"
                        s.finished -> s.outcome ?: "succeeded"
                        s.startedAt != null && running -> "running"
                        running -> "waiting"
                        else -> "not-run"
                    },
                container = container,
                shortened = shortened,
                step = s.step,
                startedAt = s.startedAt,
                seconds = s.seconds,
                reason = s.reason ?: cfg?.takeIf { it.backlog }?.let { "nog niet gebouwd (backlog)" },
                excerpt = s.excerpt,
                tests = s.tests,
            )
        }

        fun gateItem(g: GateState) =
            LiveItem(
                kind = "gate",
                name = g.name,
                title = g.title,
                status =
                    when {
                        g.outcome != null -> g.outcome!!
                        g.waitingFor != null -> "waiting"
                        g.started && running -> "deciding"
                        running -> "pending"
                        else -> "not-run"
                    },
                reason = g.reason,
                explains = g.explains,
                waitingFor = g.waitingFor,
            )
        val raw =
            order.map { key ->
                val (kind, name) = key.split(":", limit = 2)
                if (kind == "step") stepItem(steps.getValue(key), null) else gateItem(gates.getValue(name))
            }
        // A step that found something its gate let through with an approved explain: not failed, but explained.
        val explainedGates = raw.filter { it.kind == "gate" && it.status == "open" && it.explains.isNotEmpty() }.map { it.name }.toSet()
        val items =
            raw.map {
                if (it.kind == "step" && it.status == "failed" && config.step(it.name)?.gate in explainedGates) {
                    it.copy(status = "explained", excerpt = null)
                } else {
                    it
                }
            }
        val versions = lines.last().containers()
        val containerRows =
            if (containerLane.isEmpty()) {
                emptyList()
            } else {
                containers.map { c ->
                    LiveContainerRow(
                        container = c,
                        title = config.containerTitle(c),
                        version = versions[c],
                        items =
                            containerLane.map {
                                if (it.kind ==
                                    "step"
                                ) {
                                    stepItem(steps.getValue("step:${it.name}@$c"), c)
                                } else {
                                    gateItem(gates.getValue(it.key))
                                }
                            },
                    )
                }
            }
        val all = containerRows.flatMap { it.items } + items
        val outcome = finishedLine?.content?.path("outcome")?.asText() ?: "running"
        val failedStep = all.firstOrNull { it.kind == "step" && it.status == "failed" }
        val closedGate = items.firstOrNull { it.kind == "gate" && it.status == "closed" }
        val c = first.custom
        return LiveRun(
            product = first.product,
            subProduct = first.subProduct,
            title = config.subProductTitle(first.subProduct),
            run = first.run,
            version =
                lines
                    .last()
                    .custom
                    .path("subProduct")
                    .path("version")
                    .asText(),
            environment = c.path("environment").asText(),
            triggerKind = c.path("trigger").path("kind").asText(),
            triggerName = c.path("trigger").path("name").asText(),
            releaseType = c.path("releaseType").asText("planned"),
            commit = c.path("commit").path("sha").asText(""),
            started = lines.firstOrNull { it.shortType == "pipelinerun.started" }?.timestamp ?: first.timestamp,
            finished = finishedLine?.timestamp,
            outcome = outcome,
            seconds = finishedLine?.custom?.path("durationSeconds")?.asDouble(),
            stuckAt = closedGate?.name?.let { "$it-gate" } ?: failedStep?.title?.takeIf { outcome == "failure" },
            waitingFor = items.firstOrNull { it.status == "waiting" && it.kind == "gate" }?.waitingFor,
            failure =
                failedStep?.let {
                    LiveFailure(
                        listOfNotNull(it.title, it.container?.let(config::containerTitle)).joinToString(" – "),
                        it.reason,
                        it.excerpt,
                        it.tests
                            ?.takeIf { t ->
                                t.failed > 0
                            }?.let { t -> "${t.failed} van ${t.tests}" },
                    )
                },
            tag = tag,
            deployed = deployed,
            items = items,
            containers = versions,
            containerRows = containerRows,
            startGate =
                config.startGates
                    .firstOrNull()
                    ?.let { gates[it.key] }
                    ?.let(::gateItem),
        )
    }

    private fun JsonNode.text(field: String): String? = path(field).asText("").ifBlank { null }

    companion object {
        const val MAX_EXCERPT = 300

        /** A reset is requested shortly before the first log line of the new experiment. */
        const val COMMAND_SLACK_SECONDS = 300L

        /** One line of output, at most [MAX_EXCERPT] characters. */
        fun oneLine(text: String): String {
            val line = text.replace(Regex("[\\r\\n\\t]+"), " ").trim()
            return if (line.length <= MAX_EXCERPT) line else line.take(MAX_EXCERPT - 1) + "…"
        }
    }
}
