package com.fcd.dashboard

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A small history that can be checked by hand (environment lokaal, sub-product bestellen):
 *  09:00 deploy 0.1.0, commit 08:50  (lead time 10 min)
 *  10:00 deploy 0.2.0, commit 09:40  (lead time 20 min) -> followed by an emergency patch: a failed change
 *  10:30 deploy 0.2.1 (emergency), commit 10:25 (lead time 5 min) -> restore time 30 min
 * Plus a closed security gate in a werkplek run, and three orders of which one did not reach the bar.
 */
class KpiTest {
    private val day = "2026-11-26"

    private fun t(time: String) = Instant.parse("${day}T$time:00Z")

    private fun line(
        type: String,
        at: String,
        content: String,
        version: String = "0.1.0",
        commit: String = at,
        environment: String = "lokaal",
        releaseType: String = "planned",
        run: String = "run-$version-$environment",
        trigger: String = """{"kind":"human","name":"sim007"}""",
    ) = """{"context":{"version":"0.4.1","id":"${UUID.randomUUID()}","source":"test","type":"$type","timestamp":"${t(at)}"},
        "subject":{"id":"x","source":"sk-demo","content":$content},
        "customData":{"durationSeconds":60,"run":"$run","commit":{"sha":"abc","time":"${t(commit)}"},
        "trigger":$trigger,"product":"showkitchen",
        "subProduct":{"name":"bestellen","version":"$version","containers":{"bestellen-backend":"$version"}},
        "showcase":"demo","experiment":"mvp0","environment":"$environment","releaseType":"$releaseType"}}"""

    private fun deploy(
        at: String,
        version: String,
        commit: String,
        releaseType: String = "planned",
    ) = listOf(
        line(
            "dev.cdevents.pipelinerun.started.0.2.0",
            at,
            """{"pipelineName":"sk-demo","outcome":"running"}""",
            version,
            commit,
            releaseType = releaseType,
        ),
        line(
            "dev.cdevents.service.deployed.0.2.0",
            at,
            """{"environment":{"id":"lokaal"},"artifactId":"x"}""",
            version,
            commit,
            releaseType = releaseType,
        ),
        line(
            "dev.cdevents.pipelinerun.finished.0.2.0",
            at,
            """{"pipelineName":"sk-demo","outcome":"success"}""",
            version,
            commit,
            releaseType = releaseType,
        ),
    )

    private fun order(
        at: String,
        bar: Boolean,
    ) = line(
        "dev.cdeventsx.fcd-keyresult.measured.0.1.0",
        at,
        """{"keyResult":"k","orderId":"${UUID.randomUUID()}","tableNumber":12,"complete":$bar,
           "receivers":{"kitchen":{"arrived":true},"bar":{"arrived":$bar},"cash-register":{"arrived":true}}}""",
        run = "",
        trigger = """{"kind":"guest","name":"gast"}""",
    )

    private suspend fun ApplicationTestBuilder.load() {
        val lines =
            deploy("09:00", "0.1.0", "08:50") +
                deploy("10:00", "0.2.0", "09:40") +
                deploy("10:30", "0.2.1", "10:25", releaseType = "emergency") +
                line(
                    "dev.cdeventsx.fcd-qualitygate.evaluated.0.1.0",
                    "09:30",
                    """{"gate":"security","afterSteps":"6-7","outcome":"closed","reason":"image x: kritieke of hoge kwetsbaarheid","explain":""}""",
                    version = "0.2.0",
                    environment = "werkplek",
                ) +
                line(
                    "dev.cdeventsx.fcd-qualitygate.evaluated.0.1.0",
                    "09:59",
                    """{"gate":"security","afterSteps":"6-7","outcome":"open","reason":"","explain":""}""",
                    version = "0.2.0",
                ) +
                listOf(order("10:05", true), order("10:10", false), order("10:40", true)) +
                // An order of an end-to-end test: measured, but it does not count for the key result.
                order("10:41", false).replace("\"kind\":\"guest\",\"name\":\"gast\"", "\"kind\":\"test\",\"name\":\"end-to-end test\"")
        for (l in lines) {
            val r =
                client.post("/api/v1/events") {
                    contentType(ContentType.Application.Json)
                    setBody(l.replace("\n", " "))
                }
            assertEquals(HttpStatusCode.Accepted, r.status, r.bodyAsText())
        }
    }

    @Test
    fun `computes the DORA metrics from the log lines of the deployed environment`() =
        testApplication {
            application { dashboard(InMemoryEventStore()) }
            load()

            val kpis = Json.mapper.readTree(client.get("/api/v1/kpis").bodyAsText())
            val dora = kpis["products"].first { it["name"].asText() == "showkitchen" }["subProducts"][0]

            assertEquals("bestellen", dora["subProduct"].asText())
            assertEquals(3, dora["deployments"].asInt())
            assertEquals(10.0, dora["leadTimeMedianMinutes"].asDouble())
            assertEquals(1, dora["failedChanges"].asInt())
            assertEquals(1.0 / 3, dora["changeFailureRate"].asDouble(), 0.0001)
            assertEquals(30.0, dora["restoreMedianMinutes"].asDouble())
            assertEquals(3.0, dora["deploymentsPerDay"].asDouble())
        }

    @Test
    fun `counts how often each gate closes and why, and shows the trend per day`() =
        testApplication {
            application { dashboard(InMemoryEventStore()) }
            load()

            val kpis = Json.mapper.readTree(client.get("/api/v1/kpis").bodyAsText())
            val security = kpis["gates"].first { it["gate"].asText() == "security" }
            assertEquals(2, security["evaluated"].asInt())
            assertEquals(1, security["closed"].asInt())
            assertTrue(security["reasons"][0]["reason"].asText().contains("kwetsbaarheid"))

            val trend = kpis["trend"]
            assertEquals(1, trend.size())
            assertEquals(day, trend[0]["day"].asText())
            assertEquals(3, trend[0]["deployments"].asInt())
            assertEquals(1, trend[0]["gatesClosed"].asInt())
            assertEquals(66.7, trend[0]["keyResultPercent"].asDouble(), 0.1)
        }

    @Test
    fun `shows the key result of Bestellen - how many orders, how many complete, and which not`() =
        testApplication {
            application { dashboard(InMemoryEventStore()) }
            load()

            val kr = Json.mapper.readTree(client.get("/api/v1/keyresult").bodyAsText())
            assertEquals(3, kr["orders"].asInt())
            assertEquals(2, kr["complete"].asInt())
            assertEquals(66.7, kr["percent"].asDouble(), 0.1)
            assertEquals(1, kr["incomplete"].size())
            assertEquals(listOf("bar"), kr["incomplete"][0]["missing"].map { it.asText() })
            assertEquals(12, kr["incomplete"][0]["tableNumber"].asInt())
        }
}
