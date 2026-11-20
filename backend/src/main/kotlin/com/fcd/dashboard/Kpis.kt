package com.fcd.dashboard

import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

data class RestoreView(
    val failedVersion: String,
    val patchVersion: String,
    val minutes: Double,
    val at: Instant,
)

data class DoraView(
    val product: String,
    val subProduct: String,
    val deployments: Int,
    val deploymentsPerDay: Double,
    val leadTimeMedianMinutes: Double?,
    val failedChanges: Int,
    val changeFailureRate: Double?,
    val restoreMedianMinutes: Double?,
    val restores: List<RestoreView>,
    val pipelineSecondsAverage: Double?,
)

data class ProductKpis(
    val name: String,
    val dora: DoraView,
    val subProducts: List<DoraView>,
)

data class ReasonCount(
    val reason: String,
    val count: Int,
)

data class GateStats(
    val gate: String,
    val evaluated: Int,
    val closed: Int,
    val reasons: List<ReasonCount>,
)

data class TrendPoint(
    val day: String,
    val deployments: Int,
    val leadTimeMedianMinutes: Double?,
    val runs: Int,
    val runsFailed: Int,
    val gatesClosed: Int,
    val pipelineSecondsAverage: Double?,
    val keyResultPercent: Double?,
)

data class KpiView(
    val experiment: String,
    val products: List<ProductKpis>,
    val gates: List<GateStats>,
    val trend: List<TrendPoint>,
    val activeExplains: Int,
    val keyResultPercent: Double?,
)

data class IncompleteOrder(
    val orderId: String,
    val tableNumber: Int,
    val at: Instant,
    val missing: List<String>,
)

data class KeyResultView(
    val experiment: String,
    val keyResult: String,
    val orders: Int,
    val complete: Int,
    val percent: Double?,
    val incomplete: List<IncompleteOrder>,
)

/**
 * KPIs of the way of working, computed from the log lines of one experiment.
 * DORA counts only the deployed environment (lokaal):
 *  - lead time: from the commit to the deployment;
 *  - deployment frequency: deployments per day of the experiment;
 *  - change failure rate: share of deployments that were followed by an emergency patch;
 *  - time to restore: from the deployment of the failed version to the deployment of the patch.
 */
class Kpis(
    private val views: Views,
    private val store: EventStore,
) {
    fun kpis(requested: String?): KpiView {
        val experiment = views.experiment(requested)
        val lines = store.all(experiment)
        val days = days(lines)
        val bySub = lines.filter { it.product.isNotBlank() && it.subProduct.isNotBlank() }.groupBy { it.product to it.subProduct }
        val doras = bySub.map { (key, l) -> dora(key.first, key.second, l, days) }
        val products =
            doras.groupBy { it.product }.map { (product, subs) ->
                val all = bySub.filterKeys { it.first == product }.values.flatten()
                ProductKpis(product, dora(product, "", all, days), subs.sortedBy { it.subProduct })
            }
        val today =
            Instant
                .now()
                .atZone(ZoneOffset.UTC)
                .toLocalDate()
                .toString()
        return KpiView(
            experiment = experiment,
            products = products.sortedBy { it.name },
            gates = gateStats(lines),
            trend = trend(lines),
            activeExplains =
                lines
                    .filter { it.shortType == "qualitygate.evaluated" }
                    .flatMap { l -> l.content.path("explainDetails").map { it.path("id").asText() to it.path("until").asText("") } }
                    .filter { (_, until) -> until.isBlank() || until >= today }
                    .map { it.first }
                    .distinct()
                    .size,
            keyResultPercent = keyResult(lines).let { (orders, complete) -> percent(complete, orders) },
        )
    }

    fun keyResult(requested: String?): KeyResultView {
        val experiment = views.experiment(requested)
        val measured = keyResultLines(store.all(experiment))
        val incomplete =
            measured.filterNot { it.content.path("complete").asBoolean() }.map { l ->
                val receivers = l.content.path("receivers")
                IncompleteOrder(
                    orderId = l.content.path("orderId").asText(),
                    tableNumber = l.content.path("tableNumber").asInt(),
                    at = l.timestamp,
                    missing = receivers.properties().filterNot { it.value.path("arrived").asBoolean() }.map { it.key },
                )
            }
        return KeyResultView(
            experiment = experiment,
            keyResult =
                measured
                    .lastOrNull()
                    ?.content
                    ?.path("keyResult")
                    ?.asText()
                    ?: "Elke bestelling van de gast komt aan bij keuken, bar en kassa",
            orders = measured.size,
            complete = measured.size - incomplete.size,
            percent = percent(measured.size - incomplete.size, measured.size),
            incomplete = incomplete.asReversed(),
        )
    }

    private fun dora(
        product: String,
        subProduct: String,
        lines: List<Logline>,
        days: Long,
    ): DoraView {
        val deploys = deployments(lines)
        val failed = mutableListOf<Logline>()
        val restores = mutableListOf<RestoreView>()
        deploys.groupBy { it.subProduct }.values.forEach { d ->
            d.zipWithNext().forEach { (previous, next) ->
                if (next.custom.path("releaseType").asText() == "emergency") {
                    failed += previous
                    restores += RestoreView(version(previous), version(next), minutes(previous.timestamp, next.timestamp), next.timestamp)
                }
            }
        }
        val leadTimes =
            deploys.mapNotNull { d ->
                runCatching {
                    Instant.parse(
                        d.custom
                            .path("commit")
                            .path("time")
                            .asText(),
                    )
                }.getOrNull()?.let { minutes(it, d.timestamp) }
            }
        val runSeconds =
            lines
                .filter { it.shortType == "pipelinerun.finished" && it.custom.path("environment").asText() == "lokaal" }
                .map { it.custom.path("durationSeconds").asDouble() }
        return DoraView(
            product = product,
            subProduct = subProduct,
            deployments = deploys.size,
            deploymentsPerDay = round(deploys.size.toDouble() / days),
            leadTimeMedianMinutes = median(leadTimes),
            failedChanges = failed.size,
            changeFailureRate = if (deploys.isEmpty()) null else failed.size.toDouble() / deploys.size,
            restoreMedianMinutes = median(restores.map { it.minutes }),
            restores = restores,
            pipelineSecondsAverage = runSeconds.takeIf { it.isNotEmpty() }?.average()?.let { round(it) },
        )
    }

    private fun gateStats(lines: List<Logline>): List<GateStats> =
        lines
            .filter { it.shortType == "qualitygate.evaluated" }
            .groupBy { it.content.path("gate").asText() }
            .map { (gate, l) ->
                val closed = l.filter { it.content.path("outcome").asText() == "closed" }
                GateStats(
                    gate = gate,
                    evaluated = l.size,
                    closed = closed.size,
                    reasons =
                        closed
                            .flatMap { c ->
                                c.content
                                    .path("reason")
                                    .asText()
                                    .split("; ")
                                    .map { "${c.subProduct}: ${it.take(160)}" }
                            }.groupingBy { it }
                            .eachCount()
                            .map { ReasonCount(it.key, it.value) }
                            .sortedByDescending { it.count },
                )
            }.sortedBy { GATE_ORDER.indexOf(it.gate) }

    private fun trend(lines: List<Logline>): List<TrendPoint> =
        lines.groupBy { day(it.timestamp) }.toSortedMap().map { (day, l) ->
            val deploys = deployments(l)
            val runs = l.filter { it.shortType == "pipelinerun.finished" }
            val (orders, complete) = keyResult(l)
            TrendPoint(
                day = day,
                deployments = deploys.size,
                leadTimeMedianMinutes =
                    median(
                        deploys.mapNotNull { d ->
                            runCatching {
                                Instant.parse(
                                    d.custom
                                        .path("commit")
                                        .path("time")
                                        .asText(),
                                )
                            }.getOrNull()?.let { minutes(it, d.timestamp) }
                        },
                    ),
                runs = runs.size,
                runsFailed = runs.count { it.content.path("outcome").asText() == "failure" },
                gatesClosed = l.count { it.shortType == "qualitygate.evaluated" && it.content.path("outcome").asText() == "closed" },
                pipelineSecondsAverage =
                    runs
                        .map {
                            it.custom
                                .path(
                                    "durationSeconds",
                                ).asDouble()
                        }.takeIf { it.isNotEmpty() }
                        ?.average()
                        ?.let { round(it) },
                keyResultPercent = percent(complete, orders),
            )
        }

    private fun keyResultLines(lines: List<Logline>) =
        lines.filter { it.shortType == "keyresult.measured" && it.custom.path("environment").asText() == "lokaal" }

    private fun keyResult(lines: List<Logline>): Pair<Int, Int> {
        val measured = keyResultLines(lines)
        return measured.size to measured.count { it.content.path("complete").asBoolean() }
    }

    private fun deployments(lines: List<Logline>) =
        lines.filter { it.shortType == "service.deployed" && it.custom.path("environment").asText() == "lokaal" }

    private fun days(lines: List<Logline>): Long {
        if (lines.isEmpty()) return 1
        return ChronoUnit.DAYS.between(
            lines
                .first()
                .timestamp
                .atZone(ZoneOffset.UTC)
                .toLocalDate(),
            lines
                .last()
                .timestamp
                .atZone(ZoneOffset.UTC)
                .toLocalDate(),
        ) +
            1
    }

    companion object {
        val GATE_ORDER = listOf("code", "build", "security", "deploy", "human")

        private fun version(line: Logline) =
            line.custom
                .path("subProduct")
                .path("version")
                .asText()

        private fun day(t: Instant) = t.atZone(ZoneOffset.UTC).toLocalDate().toString()

        private fun minutes(
            from: Instant,
            to: Instant,
        ) = round(Duration.between(from, to).seconds / 60.0)

        private fun round(v: Double) = Math.round(v * 10) / 10.0

        private fun percent(
            part: Int,
            whole: Int,
        ): Double? = if (whole == 0) null else round(part * 100.0 / whole)

        fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val s = values.sorted()
            return if (s.size % 2 == 1) s[s.size / 2] else round((s[s.size / 2 - 1] + s[s.size / 2]) / 2)
        }
    }
}
