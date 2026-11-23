package com.fcd.dashboard

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The demo: rows per container, backlog and shortened steps, the human gate twice, the buttons and the changelog. */
class DemoTest {
    private val config =
        ShowcaseConfig.parse(
            """
            kind: Factory
            metadata: {name: f}
            spec: {showcase: demo}
            ---
            kind: Pipeline
            metadata: {name: pipeline}
            spec:
              trigger: dashboard-button
              demo: {startTag: demo-start}
              steps: [lint, unit-tests, build, e2e, release]
              backlog: [coverage, dast, perf]
            ---
            kind: Step
            metadata: {name: lint, title: Statisch}
            spec: {gate: code, level: container}
            ---
            kind: Step
            metadata: {name: unit-tests, title: Unit}
            spec: {gate: code, level: container, testType: unit}
            ---
            kind: Step
            metadata: {name: coverage, title: Dekking}
            spec: {after: unit-tests, level: container}
            ---
            kind: Step
            metadata: {name: build, title: Bouwen}
            spec: {gate: build}
            ---
            kind: Step
            metadata: {name: e2e, title: E2E}
            spec: {gate: deploy, testType: end-to-end, shortened: EXP-9}
            ---
            kind: Step
            metadata: {name: dast, title: DAST}
            spec: {after: e2e}
            ---
            kind: Step
            metadata: {name: perf, title: Perf}
            spec: {after: e2e}
            ---
            kind: Step
            metadata: {name: release, title: Releasen}
            ---
            kind: QualityGate
            metadata: {name: code, title: Codegate}
            spec: {afterSteps: [lint, unit-tests]}
            ---
            kind: QualityGate
            metadata: {name: human, title: Goedkeuring}
            spec: {beforeSteps: [lint, release], approval: dashboard-button, approver: sim007}
            ---
            kind: Explanation
            metadata: {name: EXP-9}
            spec: {rule: kernpad, reason: "Alleen het kernpad in de demo", until: 2026-12-31}
            ---
            kind: SubProduct
            metadata: {name: orders, title: Bestellen}
            spec: {product: shop}
            ---
            kind: Container
            metadata: {name: orders-frontend, title: frontend}
            spec: {subProduct: orders}
            ---
            kind: Container
            metadata: {name: orders-backend, title: backend}
            spec: {subProduct: orders}
            ---
            kind: Container
            metadata: {name: stub-keuken, title: keuken}
            spec: {stub: true, receiver: kitchen, service: stub-kitchen}
            """.trimIndent(),
        )

    private val run = "shop-20261125-100000-abc1234"
    private var second = 0L

    private fun line(
        type: String,
        content: String,
        runId: String = run,
    ): String =
        """
        {"context":{"version":"0.4.1","id":"${UUID.randomUUID()}","source":"fcd-pipeline/0.7.0","type":"$type",
          "timestamp":"${Instant.parse("2026-11-25T10:00:00Z").plusSeconds(second++)}"},
         "subject":{"id":"x","source":"shop","content":$content},
         "customData":{"durationSeconds":1,"run":"$runId","trigger":{"kind":"human","name":"sim007"},"product":"shop",
          "subProduct":{"name":"orders","version":"1.0.1","containers":{"orders-frontend":"1.0.0","orders-backend":"1.0.1"}},
          "showcase":"demo","experiment":"sim","environment":"lokaal","releaseType":"planned"}}
        """.trimIndent()

    private fun task(
        phase: String,
        name: String,
        container: String? = null,
        outcome: String = "succeeded",
    ) = line(
        "dev.cdevents.taskrun.$phase.0.2.0",
        """{"taskName":"$name","outcome":"${if (phase == "started") "running" else outcome}"${container?.let {
            ",\"container\":\"$it\""
        } ?: ""}}""",
    )

    private fun gate(
        phase: String,
        name: String,
        before: String? = null,
        waiting: Boolean = false,
    ) = line(
        "dev.cdeventsx.fcd-qualitygate.${if (phase == "started") "started" else "evaluated"}.0.1.0",
        buildString {
            append("""{"gate":"$name"""")
            before?.let { append(""","beforeSteps":"$it"""") }
            if (waiting) append(""","waitingFor":{"kind":"human","name":"sim007","action":"release goedkeuren"}""")
            if (phase != "started") append(""","outcome":"open","reason":"ok","explain":""""")
            append("}")
        },
    )

    private fun store(vararg lines: String): EventStore {
        val reader = LoglineReader()
        val store = InMemoryEventStore()
        lines.forEach { store.add(reader.read(it)) }
        return store
    }

    private fun waitingRun() =
        arrayOf(
            line("dev.cdevents.pipelinerun.started.0.2.0", """{"pipelineName":"shop","outcome":"running"}"""),
            gate("started", "human", "lint"),
            gate("evaluated", "human", "lint"),
            task("started", "lint", "orders-frontend"),
            task("finished", "lint", "orders-frontend"),
            task("started", "lint", "orders-backend"),
            task("finished", "lint", "orders-backend", "failed"),
            gate("started", "human", "release", waiting = true),
        )

    @Test
    fun `a row per container with its own steps and backlog, the sub-product row with the human gate twice`() {
        val live = LiveViews(store(*waitingRun()), Views(InMemoryEventStore(), "sim", "demo"), config)
        val r = live.state("sim").runs.single()

        assertEquals(listOf("orders-frontend", "orders-backend"), r.containerRows.map { it.container })
        val frontend = r.containerRows[0]
        assertEquals("frontend", frontend.title)
        assertEquals("1.0.0", frontend.version)
        // The code gate closes after the steps per container only: on the rows, before building.
        assertEquals(listOf("lint", "unit-tests", "coverage", "code"), frontend.items.map { it.name })
        assertEquals(listOf("succeeded", "waiting", "backlog", "pending"), frontend.items.map { it.status })
        assertEquals("failed", r.containerRows[1].items[0].status)

        // The human gate at the start is in the head of the run; the row of the sub-product begins after building.
        assertEquals("open", r.startGate?.status)
        assertEquals(
            listOf("step:build", "step:e2e", "step:dast", "step:perf", "gate:human", "step:release"),
            r.items.map { "${it.kind}:${it.name}" },
        )
        assertEquals("waiting", r.items[4].status)
        assertEquals("sim007", r.waitingFor?.name)
        assertEquals("backlog", r.items[2].status)
        assertEquals("EXP-9", r.items[1].shortened?.explain)
        assertEquals("Alleen het kernpad in de demo", r.items[1].shortened?.reason)
        assertEquals("Statisch – backend", r.failure?.title)
        assertEquals(mapOf("orders-frontend" to "1.0.0", "orders-backend" to "1.0.1"), r.containers)
    }

    @Test
    fun `a container that did not change is already green, not just skipped`() {
        val lines =
            arrayOf(
                line("dev.cdevents.pipelinerun.started.0.2.0", """{"pipelineName":"shop","outcome":"running"}"""),
                task("started", "lint", "orders-frontend"),
                line(
                    "dev.cdevents.taskrun.finished.0.2.0",
                    """{"taskName":"lint","outcome":"skipped","reason":"orders-frontend 1.0.0 ongewijzigd (draait al)","container":"orders-frontend","unchanged":true}""",
                ),
            )
        val r = LiveViews(store(*lines), Views(InMemoryEventStore(), "sim", "demo"), config).state("sim").runs.single()
        val lint = r.containerRows[0].items[0]
        assertEquals("unchanged", lint.status)
        assertEquals("orders-frontend 1.0.0 ongewijzigd (draait al)", lint.reason)
    }

    @Test
    fun `a step that its gate let through with an approved explain is explained, not failed`() {
        val lines =
            arrayOf(
                line("dev.cdevents.pipelinerun.started.0.2.0", """{"pipelineName":"shop","outcome":"running"}"""),
                task("started", "build"),
                task("finished", "build", outcome = "failed"),
                line(
                    "dev.cdeventsx.fcd-qualitygate.evaluated.0.1.0",
                    """{"gate":"build","outcome":"open","reason":"afwijking EXP-1: versheid","explain":"EXP-1"}""",
                ),
            )
        val withBuildGate =
            ShowcaseConfig.parse(
                """
                kind: Pipeline
                metadata: {name: p}
                spec: {steps: [build]}
                ---
                kind: Step
                metadata: {name: build}
                spec: {gate: build}
                ---
                kind: QualityGate
                metadata: {name: build}
                spec: {afterSteps: [build]}
                """.trimIndent(),
            )
        val r = LiveViews(store(*lines), Views(InMemoryEventStore(), "sim", "demo"), withBuildGate).state("sim").runs.single()
        assertEquals(listOf("explained", "open"), r.items.map { it.status })
        assertNull(r.failure)
    }

    @Test
    fun `after the reset the patch waits to be merged, after the merge nothing waits, and the version that runs`() {
        val reset =
            line(
                "dev.cdeventsx.fcd-demo.reset.0.1.0",
                """{"startTag":"demo-start","saved":false,"branches":[{"repository":"shop","branch":"patch/1.0.1"}]}""",
                runId = "",
            )
        val deployed =
            line(
                "dev.cdevents.service.deployed.0.2.0",
                """{"environment":{"id":"lokaal"},"artifactId":"x","alongside":{"stub-kitchen":"1.0.0"}}""",
                runId = "",
            )
        val merged = line("dev.cdeventsx.fcd-demo.merged.0.1.0", """{"repository":"shop","branch":"patch/1.0.1"}""", runId = "")
        val views = Views(InMemoryEventStore(), "sim", "demo")

        val atStart = LiveViews(store(reset, deployed), views, config).state("sim")
        assertEquals(DemoState("start", listOf("shop patch/1.0.1")), atStart.demo)
        assertNull(LiveViews(store(reset, deployed), views, config).refusal(CommandRequest(CommandKinds.DEMO_MERGE)))
        val r = atStart.running.single()
        assertEquals("Bestellen" to "1.0.1", r.title to r.version)
        assertEquals("1.0.0", r.containers["orders-frontend"])
        assertEquals(listOf(StubView("stub-keuken", "keuken", "kitchen", "1.0.0")), r.stubs)

        val after = LiveViews(store(reset, deployed, merged), views, config)
        assertEquals(DemoState("merged", emptyList()), after.state("sim").demo)
        assertEquals("Nothing waits to be merged.", after.refusal(CommandRequest(CommandKinds.DEMO_MERGE)))
    }

    @Test
    fun `commands - only when they make sense, claimed once, by the agent`() {
        val commands = InMemoryCommandStore()
        val live = LiveViews(store(*waitingRun()), Views(InMemoryEventStore(), "sim", "demo"), config, commands)

        assertEquals("A pipeline is running.", live.refusal(CommandRequest(CommandKinds.START_RUN)))
        // Demo opnieuw always works: the Pipeline stops the run.
        assertNull(live.refusal(CommandRequest(CommandKinds.DEMO_RESET)))
        assertEquals("This run does not wait for an approval.", live.refusal(CommandRequest(CommandKinds.APPROVE_RELEASE, run = "other")))
        assertNull(live.refusal(CommandRequest(CommandKinds.APPROVE_RELEASE, run = run)))

        commands.add(Command(UUID.randomUUID().toString(), CommandKinds.APPROVE_RELEASE, run, null, "sim007", Instant.now()))
        assertEquals("Already approved; the Pipeline is on it.", live.refusal(CommandRequest(CommandKinds.APPROVE_RELEASE, run = run)))
        assertNull(live.refusal(CommandRequest(CommandKinds.DEMO_RESET)))
        assertNull(commands.claim(setOf(CommandKinds.START_RUN, CommandKinds.DEMO_RESET), null))
        assertNull(commands.claim(setOf(CommandKinds.APPROVE_RELEASE), "other"))
        val claimed = assertNotNull(commands.claim(setOf(CommandKinds.APPROVE_RELEASE), run))
        assertEquals("claimed", claimed.status)
        assertNull(commands.claim(setOf(CommandKinds.APPROVE_RELEASE), run))
        assertTrue(commands.finish(claimed.id, "done", "release 1.0.1 goedgekeurd"))
        assertEquals("done", commands.recent().single().status)
        commands.add(Command(UUID.randomUUID().toString(), CommandKinds.DEMO_RESET, null, null, "sim007", Instant.now()))
        assertEquals("Demo opnieuw is already on its way.", live.refusal(CommandRequest(CommandKinds.DEMO_RESET)))
    }

    @Test
    fun `the buttons over HTTP - kept with the approver, claimed by the agent, the changelog of the deployed version`() =
        testApplication {
            val events = InMemoryEventStore()
            application { dashboard(events, "sim", "demo", config) }
            assertEquals(HttpStatusCode.NoContent, client.post("/api/v1/commands/claim?kind=start-run").status)
            val created =
                client.post("/api/v1/commands") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"kind":"start-run"}""")
                }
            assertEquals(HttpStatusCode.Accepted, created.status)
            assertTrue(created.bodyAsText().contains("\"requestedBy\":\"sim007\""))
            val again =
                client.post("/api/v1/commands") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"kind":"start-run"}""")
                }
            assertEquals(HttpStatusCode.Conflict, again.status)
            val claimed = client.post("/api/v1/commands/claim?kind=start-run,demo-reset").bodyAsText()
            val id =
                Json.mapper
                    .readTree(claimed)
                    .path("id")
                    .asText()
            val done =
                client.post("/api/v1/commands/$id/done") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"outcome":"done","message":"bestellen: gelukt"}""")
                }
            assertEquals(HttpStatusCode.NoContent, done.status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/api/v1/commands/claim?kind=rm-rf").status)

            client.post("/api/v1/events") {
                contentType(ContentType.Application.Json)
                setBody(
                    line(
                        "dev.cdevents.service.deployed.0.2.0",
                        """{"environment":{"id":"lokaal"},"artifactId":"pkg:git/shop@abc","changelog":"## [1.0.1]\n- bar"}""",
                    ),
                )
            }
            val changelog = client.get("/api/v1/changelog?experiment=sim").bodyAsText()
            assertTrue(changelog.contains("\"version\":\"1.0.1\""), changelog)
            assertTrue(changelog.contains("## [1.0.1]"), changelog)
        }
}
