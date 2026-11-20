package com.fcd.dashboard

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The status report: the state at one moment, per product and sub-product, as Markdown.
 * With date, showcase, experiment and versions, so reports can be compared (also as a diff in git).
 */
class Report(
    private val views: Views,
    private val kpis: Kpis,
) {
    fun markdown(
        requested: String?,
        now: Instant = Instant.now(),
    ): String {
        val overview = views.overview(requested)
        val k = kpis.kpis(overview.experiment)
        val kr = kpis.keyResult(overview.experiment)
        val tests = views.tests(overview.experiment)
        val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC).format(now)

        return buildString {
            appendLine("# Statusrapport showcase Demo")
            appendLine()
            appendLine("| | |")
            appendLine("|---|---|")
            appendLine("| Datum | $time |")
            appendLine("| Showcase | demo |")
            appendLine("| Experiment | ${overview.experiment} |")
            for (p in overview.products) {
                for (s in p.subProducts) {
                    val containers =
                        s.deployed
                            ?.containers
                            ?.entries
                            ?.joinToString(", ") { "${it.key} ${it.value}" }
                    appendLine(
                        "| ${p.name} / ${s.name} | uitgerold: ${s.deployed?.version ?: "nog niet"}${containers?.let { " ($it)" } ?: ""} |",
                    )
                }
            }
            appendLine()

            appendLine("## Key result van Bestellen")
            appendLine()
            appendLine("${kr.keyResult}: **${pct(kr.percent)}** (${kr.complete} van ${kr.orders} bestellingen volledig aangekomen).")
            if (kr.incomplete.isNotEmpty()) {
                appendLine()
                appendLine("Niet volledig aangekomen:")
                kr.incomplete
                    .take(
                        10,
                    ).forEach { appendLine("- tafel ${it.tableNumber}, ${it.at}: niet bij ${it.missing.joinToString(", ")}") }
            }
            appendLine()

            appendLine("## Pipelines en quality gates")
            appendLine()
            for (p in overview.products) {
                appendLine("### ${p.name}: ${status(p.status)}")
                appendLine()
                appendLine("| Deelproduct | Status | Laatste run | Gates | Vast bij |")
                appendLine("|---|---|---|---|---|")
                for (s in p.subProducts) {
                    val r = s.lastRun
                    val gates =
                        r?.gates?.joinToString(" ") { "${GATES[it.gate] ?: it.gate} ${if (it.outcome == "open") "open" else "**dicht**"}" }
                            ?: ""
                    val run = r?.let { "${it.version}, ${it.environment}, ${it.started}" } ?: ""
                    appendLine("| ${s.name} | ${status(s.status)} | $run | $gates | ${r?.stuckAt ?: ""} |")
                }
                appendLine()
            }
            val explains = overview.products.flatMap { p -> p.subProducts.flatMap { s -> s.explains.map { s.name to it } } }
            if (explains.isNotEmpty()) {
                appendLine("Goedgekeurde afwijkingen:")
                explains.forEach { (sub, x) ->
                    appendLine(
                        "- ${x.id} ($sub, ${GATES[x.gate] ?: x.gate})${x.until?.let {
                            ", tot $it"
                        } ?: ""}${x.approver?.let { ", goedgekeurd door $it" } ?: ""}",
                    )
                }
                appendLine()
            }

            appendLine("## KPI's (DORA)")
            appendLine()
            appendLine(
                "| Product / deelproduct | Uitrols | Per dag | Doorlooptijd (mediaan) | Mislukte wijzigingen | Hersteltijd (mediaan) | Runduur |",
            )
            appendLine("|---|---|---|---|---|---|---|")
            for (p in k.products) {
                appendLine(doraRow("**${p.name}**", p.dora))
                p.subProducts.forEach { appendLine(doraRow(it.subProduct, it)) }
            }
            appendLine()
            appendLine(
                "Quality gates dicht: " + k.gates.joinToString(", ") { "${GATES[it.gate] ?: it.gate} ${it.closed} van ${it.evaluated}" } +
                    ".",
            )
            appendLine("Lopende afwijkingen: ${k.activeExplains}.")
            appendLine()

            appendLine("## Testresultaten")
            appendLine()
            appendLine("| Deelproduct | Soort | Tests | Geslaagd | Mislukt | Overgeslagen | Duur (s) |")
            appendLine("|---|---|---|---|---|---|---|")
            tests.forEach {
                appendLine(
                    "| ${it.subProduct} | ${it.testType} | ${it.tests} | ${it.passed} | ${it.failed} | ${it.skipped} | ${it.seconds} |",
                )
            }
            for (p in overview.products) {
                p.tests.forEach {
                    appendLine(
                        "| **${p.name}** | ${it.testType} | ${it.tests} | ${it.passed} | ${it.failed} | ${it.skipped} | ${round(
                            it.seconds,
                        )} |",
                    )
                }
            }
            appendLine()
            appendLine("_Gemaakt door het Dashboard van fcd-demo uit de logregels van experiment ${overview.experiment}._")
        }
    }

    private fun doraRow(
        name: String,
        d: DoraView,
    ) = "| $name | ${d.deployments} | ${d.deploymentsPerDay} | ${min(d.leadTimeMedianMinutes)} | " +
        "${d.changeFailureRate?.let { pct(it * 100) } ?: "–"} (${d.failedChanges}) | ${min(d.restoreMedianMinutes)} | " +
        "${d.pipelineSecondsAverage?.let { "$it s" } ?: "–"} |"

    companion object {
        val GATES =
            mapOf(
                "code" to "codegate",
                "build" to "bouwgate",
                "security" to "securitygate",
                "deploy" to "uitrolgate",
                "human" to "mensgate",
            )

        private fun status(s: String) = mapOf("green" to "groen", "red" to "rood", "running" to "bezig").getOrDefault(s, "onbekend")

        private fun round(v: Double) = Math.round(v * 10) / 10.0

        private fun pct(v: Double?) = v?.let { "${round(it)}%" } ?: "–"

        private fun min(v: Double?) = v?.let { "$it min" } ?: "–"
    }
}
