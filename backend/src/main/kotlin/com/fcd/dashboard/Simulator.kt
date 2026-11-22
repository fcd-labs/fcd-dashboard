package com.fcd.dashboard

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.system.exitProcess

/**
 * The simulator: sends example log lines (contract 0.4.0) to a Dashboard, with real time between them, so the live view
 * works and can be shown without the real Pipeline. Generic: the steps, gates and sub-products come from the
 * configuration of the showcase. Its log lines go to their own experiment (default "test-sim": like the end-to-end tests never the default view of the Dashboard).
 *
 *   java -jar app.jar simulate --scenario gelukt [--speed 2] [--url http://dashboard-backend:8080] [--config /config]
 */
fun simulate(args: List<String>) {
    val options =
        try {
            SimulatorOptions.parse(args)
        } catch (e: IllegalArgumentException) {
            System.err.println("simulator: ${e.message}\n\n${SimulatorOptions.USAGE}")
            exitProcess(2)
        }
    if (options.help) {
        println(SimulatorOptions.USAGE)
        return
    }
    try {
        Simulator(options).run()
    } catch (e: SimulatorException) {
        System.err.println("simulator: ${e.message}")
        exitProcess(1)
    }
}

class SimulatorException(
    message: String,
) : RuntimeException(message)

data class SimulatorOptions(
    val scenario: String = "gelukt",
    val speed: Double = 1.0,
    val url: String = System.getenv("DASHBOARD_URL") ?: "http://dashboard-backend:8080",
    val config: String = System.getenv("FCD_CONFIG_DIR") ?: "/config",
    val experiment: String = "test-sim",
    val showcase: String? = System.getenv("SHOWCASE"),
    val subProducts: List<String> = emptyList(),
    val version: String = "0.1.0",
    val triggerName: String = "Simulator",
    val approver: String = "de product owner",
    val file: String? = null,
    val run: String? = null,
    val help: Boolean = false,
) {
    companion object {
        val SCENARIOS = listOf("gelukt", "codegate-dicht", "securitygate-dicht", "noodpatch", "wacht-op-mens", "parallel", "replay")

        val USAGE =
            """
            Gebruik: java -jar app.jar simulate [opties]
              --scenario NAAM      ${SCENARIOS.joinToString(", ")} (standaard gelukt)
              --speed N            sneller (2 = twee keer zo snel; standaard 1)
              --url URL            het Dashboard (standaard http://dashboard-backend:8080)
              --config MAP         de configuratie van de showcase (standaard /config)
              --experiment NAAM    eigen experiment voor de simulatie (standaard test-sim)
              --showcase NAAM      standaard uit de configuratie
              --sub-products A,B   standaard de deelproducten uit de configuratie
              --version X.Y.Z      versie van het deelproduct (standaard 0.1.0)
              --trigger-name NAAM  wie de run start (standaard Simulator)
              --approver NAAM      wie de goedkeuring geeft (standaard de product owner)
              --file LOG.jsonl     bij replay: een log van een showcase
              --run ID             bij replay: welke run (standaard de laatste)
            """.trimIndent()

        fun parse(args: List<String>): SimulatorOptions {
            var o = SimulatorOptions()
            val it = args.iterator()

            fun value(name: String): String =
                if (it.hasNext()) it.next() else throw IllegalArgumentException("$name heeft een waarde nodig")
            while (it.hasNext()) {
                when (val a = it.next()) {
                    "--scenario" -> {
                        o = o.copy(scenario = value(a))
                    }

                    "--speed" -> {
                        o =
                            o.copy(
                                speed =
                                    value(a).toDoubleOrNull()?.takeIf { s -> s in 0.1..100.0 }
                                        ?: throw IllegalArgumentException("--speed: een getal van 0.1 tot 100"),
                            )
                    }

                    "--url" -> {
                        o = o.copy(url = value(a).trimEnd('/'))
                    }

                    "--config" -> {
                        o = o.copy(config = value(a))
                    }

                    "--experiment" -> {
                        o = o.copy(experiment = value(a))
                    }

                    "--showcase" -> {
                        o = o.copy(showcase = value(a))
                    }

                    "--sub-products" -> {
                        o = o.copy(subProducts = value(a).split(",").map { s -> s.trim() }.filter { s -> s.isNotEmpty() })
                    }

                    "--version" -> {
                        o = o.copy(version = value(a))
                    }

                    "--trigger-name" -> {
                        o = o.copy(triggerName = value(a))
                    }

                    "--approver" -> {
                        o = o.copy(approver = value(a))
                    }

                    "--file" -> {
                        o = o.copy(file = value(a))
                    }

                    "--run" -> {
                        o = o.copy(run = value(a))
                    }

                    "-h", "--help" -> {
                        o = o.copy(help = true)
                    }

                    else -> {
                        throw IllegalArgumentException("onbekende optie $a")
                    }
                }
            }
            if (o.scenario !in SCENARIOS) throw IllegalArgumentException("onbekend scenario ${o.scenario}")
            if (!Regex(
                    "^[A-Za-z0-9._-]{1,50}$",
                ).matches(o.experiment)
            ) {
                throw IllegalArgumentException("--experiment: letters, cijfers, . _ -")
            }
            if (!Regex("^[0-9]+\\.[0-9]+\\.[0-9]+$").matches(o.version)) throw IllegalArgumentException("--version: X.Y.Z")
            if (o.scenario == "replay" && o.file == null) throw IllegalArgumentException("replay heeft --file nodig")
            return o
        }
    }
}

/** Plays one scenario: the log lines of one or more pipeline runs, in real time (divided by the speed). */
class Simulator(
    private val o: SimulatorOptions,
    private val sender: (String) -> Unit = HttpSender(o.url)::send,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    private val config = ShowcaseConfig.load(Path.of(o.config))
    private val source = "fcd-dashboard-simulator/" + (Simulator::class.java.`package`?.implementationVersion ?: "dev")

    fun run() {
        if (o.scenario == "replay") return replay()
        if (config.steps.isEmpty()) {
            throw SimulatorException(
                "geen stappen in de configuratie (${o.config}): mount fcd-<showcase>/fcd op --config",
            )
        }
        val showcase = o.showcase ?: config.showcase ?: throw SimulatorException("geen showcase: geef --showcase")
        val subs =
            o.subProducts.ifEmpty { config.live.subProducts }.ifEmpty { config.subProducts.map { it.name } }.ifEmpty {
                throw SimulatorException("geen deelproducten in de configuratie: geef --sub-products")
            }
        println("Simulatie ${o.scenario} voor showcase $showcase, experiment ${o.experiment}, snelheid ${o.speed}x")
        when (o.scenario) {
            "parallel" -> {
                // Two sub-products at the same time; with one in the configuration, a second example sub-product.
                val second = subs.getOrNull(1) ?: "${subs[0]}-sim"
                val a = thread { Run(showcase, subs[0], "gelukt").play() }
                sleep(2.0)
                val b = thread { Run(showcase, second, "codegate-dicht").play() }
                a.join()
                b.join()
            }

            else -> {
                Run(showcase, subs[0], o.scenario).play()
            }
        }
        println("Klaar. Bekijk het Dashboard (Live) met ?experiment=${o.experiment}")
    }

    private fun sleep(seconds: Double) = sleeper((seconds * 1000 / o.speed).toLong())

    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.SECONDS)

    private inner class Run(
        val showcase: String,
        val subProduct: String,
        val scenario: String,
    ) {
        val sub = config.subProducts.firstOrNull { it.name == subProduct }
        val product = sub?.product ?: "product"
        val emergency = scenario == "noodpatch"
        val version = if (emergency) o.version.split(".").let { (a, b, c) -> "$a.$b.${c.toInt() + 1}" } else o.version
        val environment = if (scenario == "wacht-op-mens") "werkplek" else "lokaal"
        val commit = (1..7).map { "0123456789abcdef".random() }.joinToString("")
        val runId = "$subProduct-sim-${DateTimeFormatter.ofPattern(
            "yyyyMMdd-HHmmss",
        ).withZone(ZoneOffset.UTC).format(Instant.now())}-$commit"
        val commitTime = now().minusSeconds(120)
        val failGate =
            when (scenario) {
                "codegate-dicht" -> "code"
                "securitygate-dicht" -> "security"
                else -> null
            }
        val failStep: String? =
            failGate?.let { g ->
                val inGate = config.steps.filter { it.gate == g }
                (if (g == "code") inGate.firstOrNull { it.testType == "unit" } else null)?.name
                    ?: inGate.firstOrNull()?.name
                    ?: config.gates
                        .firstOrNull { it.name == g }
                        ?.afterSteps
                        ?.lastOrNull()
            }
        val t0 = now()

        fun play() {
            println("[$subProduct] run $runId ($scenario, $environment)")
            emit(
                "dev.cdevents.pipelinerun.started.0.2.0",
                runId,
                mapOf(
                    "pipelineName" to subProduct,
                    "outcome" to "running",
                    "steps" to config.steps.map { it.name },
                ),
            )
            var n = 0
            for (entry in config.lane) {
                if (entry.kind == "gate") {
                    if (!gate(entry)) return
                    continue
                }
                n++
                step(n, config.step(entry.name)!!)
            }
            finish("success", "")
        }

        /** Returns false when the run stops here. */
        fun gate(entry: LaneEntry): Boolean {
            val g = config.gates.first { it.name == entry.name }
            val human = g.beforeSteps.isNotEmpty()
            val content = mutableMapOf<String, Any>("gate" to g.name)
            if (human) content["beforeSteps"] = g.beforeSteps.joinToString(",") else content["afterSteps"] = g.afterSteps.joinToString(",")
            if (human && environment == "werkplek") {
                content["waitingFor"] = mapOf("kind" to "human", "name" to o.approver, "action" to "merge op main met een hogere versie")
                emit("dev.cdeventsx.fcd-qualitygate.started.0.1.0", "${g.name}-gate", content)
                println("[$subProduct] ${g.title}: wacht op ${o.approver}")
                finish("success", "")
                return false
            }
            emit("dev.cdeventsx.fcd-qualitygate.started.0.1.0", "${g.name}-gate", content)
            sleep(if (human) 2.0 else 0.6)
            val closed = g.name == failGate
            val reason =
                when {
                    closed && g.name == "security" -> "kritieke of hoge kwetsbaarheid: example-lib 1.2.0 (gesimuleerd)"
                    closed -> "unittests niet groen: 1 van 31 mislukt (gesimuleerd)"
                    human -> "goedgekeurd door ${o.approver}: merge op main met versie $version"
                    else -> ""
                }
            emit(
                "dev.cdeventsx.fcd-qualitygate.evaluated.0.1.0",
                "${g.name}-gate",
                content.filterKeys { it != "waitingFor" } +
                    mapOf(
                        "outcome" to if (closed) "closed" else "open",
                        "reason" to reason,
                        "explain" to "",
                        "explainDetails" to emptyList<Any>(),
                    ),
            )
            println("[$subProduct] ${g.title}: ${if (closed) "dicht" else "open"}")
            if (closed) {
                finish("failure", "${g.name}-gate dicht: $reason")
                return false
            }
            return true
        }

        fun step(
            n: Int,
            s: StepConfig,
        ) {
            val started = now()
            val base = mapOf("taskName" to s.name, "step" to n, "title" to s.title, "pipelineRun" to mapOf("id" to runId))
            emit("dev.cdevents.taskrun.started.0.2.0", s.name, base + ("outcome" to "running"), at = started)
            println("[$subProduct] [$n/${config.steps.size}] ${s.title}")
            val fails = s.name == failStep
            var reason = ""
            var excerpt: String? = null
            if (s.testType != null) {
                val total = TEST_COUNTS[s.testType] ?: 10
                val failed = if (fails) 1 else 0
                tests(s.testType, total, failed)
                if (fails) reason = "1 van $total tests mislukt"
                if (fails) excerpt = "OrderServiceTest.rejectsEmptyOrder: expected status 400 but was 500 (gesimuleerd)"
            } else {
                sleep(2.0 + (n * 7 % 5))
                if (fails) {
                    reason = "kritieke of hoge kwetsbaarheid gevonden"
                    excerpt = "CVE-2026-0001 (HIGH) in example-lib 1.2.0, opgelost in 1.2.3 (gesimuleerd)"
                }
            }
            // On the workstation (before a push) nothing is deployed or released.
            val skipped = environment == "werkplek" && s.gate in setOf("deploy", "human")
            val outcome =
                when {
                    fails -> "failed"
                    skipped -> "skipped"
                    else -> "succeeded"
                }
            if (skipped) reason = "alleen in de lokale omgeving"
            val content = base + mapOf("outcome" to outcome, "reason" to reason) + (excerpt?.let { mapOf("excerpt" to it) } ?: emptyMap())
            emit(
                "dev.cdevents.taskrun.finished.0.2.0",
                s.name,
                content,
                durationSeconds = Duration.between(started, now()).seconds.toDouble(),
                at = started,
            )
            if (s.name == "deploy" && !skipped) {
                emit(
                    "dev.cdevents.service.deployed.0.2.0",
                    subProduct,
                    mapOf(
                        "environment" to mapOf("id" to environment),
                        "artifactId" to "pkg:git/$subProduct@$commit",
                    ),
                )
            }
            if (s.name == "release" && !skipped) {
                emit(
                    "dev.cdevents.artifact.published.0.2.0",
                    subProduct,
                    mapOf(
                        "tag" to "v$version",
                        "releaseType" to if (emergency) "emergency" else "planned",
                    ),
                )
            }
        }

        fun tests(
            testType: String,
            total: Int,
            failed: Int,
        ) {
            val suite = mapOf("id" to "$runId-$testType", "name" to testType)
            val started = now()
            emit(
                "dev.cdevents.testsuiterun.started.0.2.0",
                testType,
                mapOf(
                    "testSuite" to suite,
                    "testType" to testType,
                    "expected" to total,
                ),
                at = started,
            )
            val ticks = 6
            for (i in 1..ticks) {
                sleep(1.0)
                val done = total * i / ticks
                val f = if (i == ticks) failed else 0
                val content =
                    mapOf(
                        "testSuite" to suite,
                        "testType" to testType,
                        "tests" to done,
                        "passed" to done - f,
                        "failed" to f,
                        "skipped" to 0,
                    )
                if (i < ticks) emit("dev.cdeventsx.fcd-testsuiterun.progressed.0.1.0", testType, content)
            }
            emit(
                "dev.cdevents.testsuiterun.finished.0.2.0",
                testType,
                mapOf(
                    "testSuite" to suite,
                    "testType" to testType,
                    "outcome" to if (failed > 0) "fail" else "pass",
                    "tests" to total,
                    "passed" to total - failed,
                    "failed" to failed,
                    "skipped" to 0,
                    "seconds" to Duration.between(started, now()).seconds.toDouble(),
                    "failures" to if (failed > 0) "OrderServiceTest.rejectsEmptyOrder" else "",
                ),
                at = started,
            )
        }

        fun finish(
            outcome: String,
            errors: String,
        ) {
            emit(
                "dev.cdevents.pipelinerun.finished.0.2.0",
                runId,
                mapOf("pipelineName" to subProduct, "outcome" to outcome, "errors" to errors, "warnings" to ""),
                durationSeconds = Duration.between(t0, now()).seconds.toDouble(),
                at = t0,
            )
            println("[$subProduct] run ${if (outcome == "success") "gelukt" else "mislukt"}${if (errors.isNotEmpty()) ": $errors" else ""}")
        }

        fun emit(
            type: String,
            subjectId: String,
            content: Map<String, Any?>,
            durationSeconds: Double = 0.0,
            at: Instant = now(),
        ) {
            val line =
                mapOf(
                    "context" to
                        mapOf(
                            "version" to "0.4.1",
                            "id" to UUID.randomUUID().toString(),
                            "source" to source,
                            "type" to type,
                            "timestamp" to at.toString(),
                        ),
                    "subject" to mapOf("id" to subjectId, "source" to subProduct, "content" to content),
                    "customData" to
                        mapOf(
                            "durationSeconds" to durationSeconds,
                            "run" to runId,
                            "commit" to mapOf("sha" to commit, "time" to commitTime.toString()),
                            "trigger" to mapOf("kind" to "human", "name" to o.triggerName),
                            "product" to product,
                            "subProduct" to
                                mapOf(
                                    "name" to subProduct,
                                    "version" to version,
                                    "containers" to (sub?.containers ?: emptyList()).associateWith { version },
                                ),
                            "showcase" to showcase,
                            "experiment" to o.experiment,
                            "environment" to environment,
                            "releaseType" to if (emergency) "emergency" else "planned",
                        ),
                )
            sender(Json.mapper.writeValueAsString(line))
        }
    }

    /** Plays a real run from a log (one log line per line) again, with new ids, now, in the experiment of the simulation. */
    private fun replay() {
        val lines =
            Files
                .readAllLines(Path.of(o.file!!))
                .filter { it.isNotBlank() }
                .map { Json.mapper.readTree(it) }
        val runId =
            o.run ?: lines
                .lastOrNull { it.path("context").path("type").asText() == "dev.cdevents.pipelinerun.started.0.2.0" }
                ?.path("customData")
                ?.path("run")
                ?.asText()
                ?: throw SimulatorException("geen run gevonden in ${o.file}")
        val run = lines.filter { it.path("customData").path("run").asText() == runId }
        if (run.isEmpty()) throw SimulatorException("run $runId staat niet in ${o.file}")
        val newRun = "$runId-replay-${Random.nextInt(1000, 9999)}"
        val t0 =
            Instant.parse(
                run
                    .first()
                    .path("context")
                    .path("timestamp")
                    .asText(),
            )
        val start = now()
        println("Replay van run $runId (${run.size} logregels) als $newRun, experiment ${o.experiment}, snelheid ${o.speed}x")
        var previous = t0
        for (node in run) {
            val line = node.deepCopy<JsonNode>() as ObjectNode
            val ts = Instant.parse(line.path("context").path("timestamp").asText())
            if (ts.isAfter(previous)) sleep(Duration.between(previous, ts).toMillis() / 1000.0)
            if (ts.isAfter(previous)) previous = ts
            val shifted = start.plusMillis((Duration.between(t0, ts).toMillis() / o.speed).toLong())
            (
                line.path(
                    "context",
                ) as ObjectNode
            ).put(
                "id",
                UUID.randomUUID().toString(),
            ).put("source", source)
                .put("timestamp", shifted.truncatedTo(ChronoUnit.SECONDS).toString())
            val custom = line.path("customData") as ObjectNode
            custom.put("run", newRun).put("experiment", o.experiment)
            o.showcase?.let { custom.put("showcase", it) }
            val content = line.path("subject").path("content")
            if (content is ObjectNode && content.path("pipelineRun").isObject) (content.path("pipelineRun") as ObjectNode).put("id", newRun)
            if (line.path("subject").path("id").asText() == runId) (line.path("subject") as ObjectNode).put("id", newRun)
            sender(Json.mapper.writeValueAsString(line))
        }
        println("Klaar. Bekijk het Dashboard (Live) met ?experiment=${o.experiment}")
    }

    companion object {
        val TEST_COUNTS = mapOf("unit" to 31, "integration" to 25, "end-to-end" to 7)
    }
}

/** Sends a log line to the Dashboard (logline contract); waits up to a minute for a Dashboard that is still starting. */
class HttpSender(
    url: String,
) {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build()
    private val endpoint = URI.create("$url/api/v1/events")
    private var reached = false

    fun send(body: String) {
        val request =
            HttpRequest
                .newBuilder(endpoint)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
        val deadline = System.currentTimeMillis() + if (reached) 5_000 else 60_000
        while (true) {
            try {
                val response = client.send(request, HttpResponse.BodyHandlers.ofString())
                if (response.statusCode() !=
                    202
                ) {
                    throw SimulatorException(
                        "het Dashboard weigert de logregel (${response.statusCode()}): ${response.body().take(300)}",
                    )
                }
                reached = true
                return
            } catch (e: java.io.IOException) {
                if (System.currentTimeMillis() >
                    deadline
                ) {
                    throw SimulatorException("het Dashboard op $endpoint is niet bereikbaar: ${e.message}")
                }
                Thread.sleep(1000)
            }
        }
    }
}
