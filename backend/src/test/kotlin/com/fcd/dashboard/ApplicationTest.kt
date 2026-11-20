package com.fcd.dashboard

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Tests with real log lines of two pipeline runs of sk-demo (test resources). */
class ApplicationTest {
    private fun fixture(name: String): List<String> =
        javaClass
            .getResource("/$name")!!
            .readText()
            .lines()
            .filter { it.isNotBlank() }

    private suspend fun ApplicationTestBuilder.send(body: String): HttpResponse =
        client.post("/api/v1/events") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun ApplicationTestBuilder.setUp(store: EventStore = InMemoryEventStore()) {
        application { dashboard(store) }
    }

    @Test
    fun `receives a log line once, also when it is sent twice`() =
        testApplication {
            val store = InMemoryEventStore()
            setUp(store)
            val line = fixture("run-success.jsonl").first()

            assertEquals(HttpStatusCode.Accepted, send(line).status)
            assertEquals(HttpStatusCode.Accepted, send(line).status)

            assertEquals(1, store.all("mvp0").size)
        }

    @Test
    fun `rejects log lines that do not conform to the contract`() =
        testApplication {
            setUp()
            val valid = fixture("run-success.jsonl").first()
            val invalid =
                mapOf(
                    "not JSON" to "{",
                    "null" to "null",
                    "no customData" to """{"context": {}, "subject": {}}""",
                    "unknown type" to valid.replace("dev.cdevents.pipelinerun.started.0.2.0", "dev.cdevents.other.0.1.0"),
                    "unknown environment" to valid.replace("\"environment\":\"lokaal\"", "\"environment\":\"productie\""),
                    "timestamp not a date" to valid.replace(Regex("\"timestamp\":\"[^\"]*\""), "\"timestamp\":\"gisteren\""),
                    "commit time not a date" to valid.replace(Regex("\"time\":\"[^\"]*\""), "\"time\":\"gisteren\""),
                    "NUL character" to valid.replace("\"pipelineName\":\"", "\"pipelineName\":\"a\\u0000"),
                )

            for ((case, body) in invalid) {
                val response = send(body)
                assertEquals(HttpStatusCode.BadRequest, response.status, case)
                assertEquals("application/problem+json", response.headers["Content-Type"]?.substringBefore(';'), case)
                assertFalse(response.bodyAsText().contains("Exception"), "$case reveals internals")
            }
        }

    @Test
    fun `shows the status per product and sub-product with steps, gates, tests and deployed version`() =
        testApplication {
            setUp()
            fixture("run-success.jsonl").forEach { send(it) }

            val overview = Json.mapper.readTree(client.get("/api/v1/overview").bodyAsText())

            assertEquals("mvp0", overview["experiment"].asText())
            val product = overview["products"][0]
            assertEquals("showkitchen", product["name"].asText())
            assertEquals("green", product["status"].asText())
            val bestellen = product["subProducts"][0]
            assertEquals("bestellen", bestellen["name"].asText())
            assertEquals("0.1.0", bestellen["deployed"]["version"].asText())
            assertEquals("0.1.0", bestellen["deployed"]["containers"]["bestellen-frontend"].asText())
            assertEquals("v0.1.0", bestellen["releases"][0]["tag"].asText())
            val run = bestellen["lastRun"]
            assertEquals("success", run["outcome"].asText())
            assertEquals(10, run["steps"].size())
            assertEquals(listOf("code", "build", "security", "deploy", "human"), run["gates"].map { it["gate"].asText() })
            assertTrue(run["gates"].all { it["outcome"].asText() == "open" })
            assertEquals("EXP-001", bestellen["explains"][0]["id"].asText())
            assertEquals(45, product["tests"].first { it["testType"].asText() == "unit" }["passed"].asInt())
        }

    @Test
    fun `shows where a sub-product is stuck when a gate is closed`() =
        testApplication {
            setUp()
            fixture("run-code-gate-closed.jsonl").forEach { send(it) }

            val bestellen = Json.mapper.readTree(client.get("/api/v1/products/showkitchen/subproducts/bestellen").bodyAsText())

            assertEquals("red", bestellen["status"].asText())
            assertEquals("code-gate", bestellen["lastRun"]["stuckAt"].asText())
            assertTrue(bestellen["lastRun"]["gates"][0]["reason"].asText().contains("lint"))
        }

    @Test
    fun `lists test results and log lines, newest first`() =
        testApplication {
            setUp()
            fixture("run-success.jsonl").forEach { send(it) }

            val tests = Json.mapper.readTree(client.get("/api/v1/tests").bodyAsText())
            assertEquals(listOf("unit", "integration", "end-to-end"), tests.map { it["testType"].asText() })

            val lines = Json.mapper.readTree(client.get("/api/v1/loglines?limit=5").bodyAsText())
            assertEquals(5, lines.size())
            assertEquals("run", lines[0]["action"].asText())
            assertTrue(lines[0]["time"].asText() >= lines[4]["time"].asText())
        }

    @Test
    fun `rejects an invalid experiment or sub-product name`() =
        testApplication {
            setUp()
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/overview?experiment=../x").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/loglines?limit=1001").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/loglines?limit=x").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/report?download=AAA").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/api/v1/products/Show%20Kitchen/subproducts/x").status)
            assertEquals(HttpStatusCode.NotFound, client.get("/api/v1/products/showkitchen/subproducts/bestellen").status)
        }

    @Test
    fun `a test experiment is never the default and not in the list`() =
        testApplication {
            setUp()
            fixture("run-success.jsonl").forEach { send(it) }
            send(fixture("run-code-gate-closed.jsonl").first().replace("\"experiment\":\"mvp0\"", "\"experiment\":\"test-e2e\""))

            val overview = Json.mapper.readTree(client.get("/api/v1/overview").bodyAsText())
            assertEquals("mvp0", overview["experiment"].asText())
            assertEquals(listOf("mvp0"), overview["experiments"].map { it.asText() })
            val test = Json.mapper.readTree(client.get("/api/v1/overview?experiment=test-e2e").bodyAsText())
            assertEquals("test-e2e", test["experiment"].asText())
        }

    @Test
    fun `makes a status report in Markdown with date, showcase, experiment, versions, gates, KPIs, tests and key result`() =
        testApplication {
            setUp()
            fixture("run-success.jsonl").forEach { send(it) }

            val response = client.get("/api/v1/report")
            val text = response.bodyAsText()

            assertEquals("text/markdown", response.headers["Content-Type"]?.substringBefore(';'))
            assertTrue(text.startsWith("# Statusrapport showcase Demo"))
            assertTrue(text.contains("| Showcase | demo |"))
            assertTrue(text.contains("| Experiment | mvp0 |"))
            assertTrue(text.contains("| showkitchen / bestellen | uitgerold: 0.1.0 (bestellen-frontend 0.1.0, bestellen-backend 0.1.0) |"))
            assertTrue(text.contains("## Key result van Bestellen"))
            assertTrue(text.contains("securitygate open"))
            assertTrue(text.contains("EXP-001 (bestellen, securitygate)"))
            assertTrue(text.contains("## KPI's (DORA)"))
            assertTrue(text.contains("| bestellen | unit | 45 | 45 | 0 | 0 |"))
            val download = client.get("/api/v1/report?download=true")
            assertTrue(download.headers["Content-Disposition"]!!.contains("statusrapport-demo-mvp0-"))
        }

    @Test
    fun `an experiment name with other characters never ends up unescaped in the download header`() =
        testApplication {
            setUp()
            send(fixture("run-success.jsonl").first().replace("\"experiment\":\"mvp0\"", "\"experiment\":\"mvp 0\\u00e9\""))

            val response = client.get("/api/v1/report?download=true")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.headers["Content-Disposition"]!!.contains("filename=\"statusrapport-demo-mvp_0_-"))
        }
}
