package com.fcd.dashboard

import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readUTF8Line
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The live view, fed by the simulator (no waiting) with a small test configuration. */
class LiveTest {
    private val configDir: Path = Path.of(javaClass.getResource("/config")!!.toURI())
    private val config = ShowcaseConfig.load(configDir)

    private fun simulate(
        store: EventStore,
        scenario: String,
        vararg extra: String,
        hook: (Logline) -> Unit = {},
    ) {
        val reader = LoglineReader()
        val options =
            SimulatorOptions.parse(
                listOf("--scenario", scenario, "--config", configDir.toString(), "--experiment", "sim") + extra,
            )
        Simulator(options, sender = { body -> reader.read(body).also { store.add(it) }.also(hook) }, sleeper = {}).run()
    }

    private fun live(store: EventStore) = LiveViews(store, Views(store, "mvp0", "demo"), config).state("sim")

    @Test
    fun `reads the steps, gates and live settings from the configuration`() {
        assertEquals("demo", config.showcase)
        assertEquals(
            listOf(
                "step:lint",
                "step:unit-tests",
                "gate:code",
                "step:build",
                "gate:build",
                "step:deploy",
                "gate:deploy",
                "gate:human",
                "step:release",
            ),
            config.lane.map { "${it.kind}:${it.name}" },
        )
        assertEquals("Codegate", config.lane[2].title)
        assertEquals(listOf("orders"), config.live.subProducts)
        assertEquals("side-by-side", config.live.layout)
        assertEquals(120, config.live.keepFinishedSeconds)
        assertEquals(Presentation("Test live", "light", 2.0, false), config.live.presentation)
        assertEquals(listOf("orders-backend"), config.subProducts.single().containers)
    }

    @Test
    fun `without configuration the live view still works with defaults`() {
        assertEquals(ShowcaseConfig.EMPTY, ShowcaseConfig.load(Path.of("/does/not/exist")))
        val broken = ShowcaseConfig.parse("kind: [")
        assertTrue(broken.steps.isEmpty())
    }

    @Test
    fun `a successful run shows every step and gate in order, deployed and released`() {
        val store = InMemoryEventStore()
        simulate(store, "gelukt")
        val run = live(store).runs.single()

        assertEquals("orders", run.subProduct)
        assertEquals("Bestellingen", run.title)
        assertEquals("success", run.outcome)
        assertEquals(config.lane.map { it.name }, run.items.map { it.name })
        assertTrue(run.items.filter { it.kind == "step" }.all { it.status == "succeeded" }, run.items.toString())
        assertTrue(run.items.filter { it.kind == "gate" }.all { it.status == "open" }, run.items.toString())
        val unit = run.items.first { it.name == "unit-tests" }.tests!!
        assertEquals(LiveTests("unit", 31, 31, 0, 0, 31, true), unit)
        assertEquals("v0.1.0", run.tag)
        assertTrue(run.deployed)
        assertNull(run.failure)
    }

    @Test
    fun `while running a step is busy, later steps wait, and the test counters go up`() {
        val store = InMemoryEventStore()
        val seen = mutableListOf<LiveRun>()
        simulate(store, "gelukt") { line ->
            if (line.shortType == "testsuiterun.progressed") seen += live(store).runs.single()
        }
        val first = seen.first()
        assertEquals("running", first.outcome)
        assertEquals("succeeded", first.items.first { it.name == "lint" }.status)
        assertEquals("running", first.items.first { it.name == "unit-tests" }.status)
        assertNotNull(first.items.first { it.name == "unit-tests" }.startedAt)
        assertEquals("waiting", first.items.first { it.name == "build" }.status)
        assertEquals("pending", first.items.first { it.name == "code" }.status)
        val counts =
            seen.map {
                it.items
                    .first { i -> i.name == "unit-tests" }
                    .tests!!
                    .passed
            }
        assertEquals(counts.sorted(), counts)
        assertTrue(counts.first() < counts.last())
    }

    @Test
    fun `a closed gate shows the failed step with a line of output, and the rest is not run`() {
        val store = InMemoryEventStore()
        simulate(store, "codegate-dicht")
        val run = live(store).runs.single()

        assertEquals("failure", run.outcome)
        assertEquals("code-gate", run.stuckAt)
        val unit = run.items.first { it.name == "unit-tests" }
        assertEquals("failed", unit.status)
        assertTrue(unit.excerpt!!.contains("expected status 400"))
        assertEquals(LiveTests("unit", 31, 30, 1, 0, 31, true), unit.tests)
        val gate = run.items.first { it.name == "code" }
        assertEquals("closed", gate.status)
        assertTrue(gate.reason!!.isNotBlank())
        assertEquals("Unittests", run.failure!!.title)
        assertEquals("1 van 31", run.failure!!.failures)
        assertEquals("not-run", run.items.first { it.name == "build" }.status)
        assertEquals("not-run", run.items.first { it.name == "human" }.status)
    }

    @Test
    fun `on the workstation the run ends waiting for the human gate`() {
        val store = InMemoryEventStore()
        simulate(store, "wacht-op-mens", "--approver", "Pat")
        val run = live(store).runs.single()

        assertEquals("werkplek", run.environment)
        assertEquals("waiting", run.items.first { it.name == "human" }.status)
        assertEquals(WaitingFor("human", "Pat", "merge op main met een hogere versie"), run.waitingFor)
        assertEquals("skipped", run.items.first { it.name == "deploy" }.status)
        assertEquals("not-run", run.items.first { it.name == "release" }.status)
    }

    @Test
    fun `an emergency patch has a patch version and is released as emergency`() {
        val store = InMemoryEventStore()
        simulate(store, "noodpatch", "--version", "0.4.0")
        val run = live(store).runs.single()
        assertEquals("emergency", run.releaseType)
        assertEquals("0.4.1", run.version)
        assertEquals("v0.4.1", run.tag)
    }

    @Test
    fun `only the configured sub-products and the latest run of each are shown`() {
        val store = InMemoryEventStore()
        simulate(store, "codegate-dicht")
        simulate(store, "gelukt", "--sub-products", "other")
        Thread.sleep(1100) // timestamps have seconds
        simulate(store, "gelukt")
        val runs = live(store).runs
        assertEquals(listOf("orders"), runs.map { it.subProduct })
        assertEquals("success", runs.single().outcome)
    }

    @Test
    fun `without configuration the order comes from the run itself`() {
        val store = InMemoryEventStore()
        simulate(store, "codegate-dicht")
        val state = LiveViews(store, Views(store, "mvp0", "demo"), ShowcaseConfig.EMPTY).state("sim")
        val run = state.runs.single()
        assertEquals(false, state.settings.configured)
        // Steps from pipelinerun.started.steps, the gate after the last step that ran.
        assertEquals(listOf("lint", "unit-tests", "code", "build", "deploy", "release"), run.items.map { it.name })
        assertEquals("closed", run.items.first { it.name == "code" }.status)
    }

    @Test
    fun `replays a real run from a log`() {
        val file = Path.of(javaClass.getResource("/run-success.jsonl")!!.toURI()).toString()
        val store = InMemoryEventStore()
        simulate(store, "replay", "--file", file, "--sub-products", "bestellen")
        val state = LiveViews(store, Views(store, "mvp0", "demo"), ShowcaseConfig.EMPTY).state("sim")
        val run = state.runs.single()
        assertEquals("bestellen", run.subProduct)
        assertEquals("success", run.outcome)
        assertTrue(run.run.contains("-replay-"))
        assertEquals(10, run.items.count { it.kind == "step" })
    }

    @Test
    fun `the final test count wins, also when it carries the begin time`() {
        val store = InMemoryEventStore()
        val reader = LoglineReader()

        fun send(
            type: String,
            at: String,
            content: String,
        ) = store.add(
            reader.read(
                """{"context":{"version":"0.4.1","id":"${java.util.UUID.randomUUID()}","source":"t","type":"$type","timestamp":"$at"},
                "subject":{"id":"x","content":$content},"customData":{"durationSeconds":0,"run":"r1","trigger":{"kind":"human","name":"t"},
                "product":"shop","subProduct":{"name":"orders","version":"1.0.0"},"showcase":"demo","experiment":"sim","environment":"lokaal"}}""",
            ),
        )
        send("dev.cdevents.taskrun.started.0.2.0", "2026-11-26T09:00:00Z", """{"taskName":"unit-tests","step":2}""")
        send("dev.cdevents.testsuiterun.started.0.2.0", "2026-11-26T09:00:00Z", """{"testType":"unit","expected":31}""")
        send(
            "dev.cdeventsx.fcd-testsuiterun.progressed.0.1.0",
            "2026-11-26T09:00:05Z",
            """{"testType":"unit","tests":25,"passed":25,"failed":0}""",
        )
        // Sent last, with the time the test suite began (contract: the time the activity began).
        send(
            "dev.cdevents.testsuiterun.finished.0.2.0",
            "2026-11-26T09:00:00Z",
            """{"testType":"unit","tests":31,"passed":30,"failed":1}""",
        )

        val tests =
            live(store)
                .runs
                .single()
                .items
                .first { it.name == "unit-tests" }
                .tests
        assertEquals(LiveTests("unit", 31, 30, 1, 0, 31, true), tests)
    }

    @Test
    fun `excerpts are one short line`() {
        val long = "a\nb\r\n" + "x".repeat(400)
        val line = LiveViews.oneLine(long)
        assertEquals(LiveViews.MAX_EXCERPT, line.length)
        assertTrue(!line.contains('\n'))
    }

    @Test
    fun `the live state is available as JSON and as a stream that pushes every new log line`() =
        testApplication {
            val store = InMemoryEventStore()
            application { dashboard(store, showcase = "demo", config = config, liveStreamMillis = 3000, liveHeartbeatMillis = 500) }
            simulate(store, "codegate-dicht")

            val json = client.get("/api/v1/live/state?experiment=sim")
            assertEquals(HttpStatusCode.OK, json.status)
            assertTrue(json.bodyAsText().contains("\"stuckAt\":\"code-gate\""))

            assertEquals(HttpStatusCode.NotAcceptable, client.get("/api/v1/live?experiment=sim").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/live/state?experiment=no%20way").status)

            // A new run arrives while the stream is open.
            val other = InMemoryEventStore()
            simulate(other, "gelukt")
            val newLines = other.all("sim").map { Json.mapper.writeValueAsString(it.payload) }

            val events = mutableListOf<String>()
            var heartbeat = false
            client
                .prepareGet("/api/v1/live?experiment=sim") { header(HttpHeaders.Accept, "text/event-stream") }
                .execute { response ->
                    assertEquals(HttpStatusCode.OK, response.status)
                    assertEquals("text/event-stream", response.headers[HttpHeaders.ContentType]?.substringBefore(';'))
                    val channel = response.bodyAsChannel()
                    var sent = false
                    while (true) {
                        val l = channel.readUTF8Line() ?: break
                        if (l.startsWith("data: ")) events += l.removePrefix("data: ")
                        if (l.startsWith(": heartbeat")) heartbeat = true
                        if (events.size == 1 && !sent) {
                            sent = true
                            for (body in newLines) {
                                client.post("/api/v1/events") {
                                    contentType(ContentType.Application.Json)
                                    setBody(body)
                                }
                            }
                        }
                    }
                }
            assertTrue(events.size >= 2, "events: ${events.size}")
            val first = Json.mapper.readTree(events.first())
            val last = Json.mapper.readTree(events.last())
            assertEquals(
                "failure",
                first
                    .path("runs")
                    .path(0)
                    .path("outcome")
                    .asText(),
            )
            assertEquals(
                "success",
                last
                    .path("runs")
                    .path(0)
                    .path("outcome")
                    .asText(),
            )
            assertTrue(heartbeat)
        }
}
