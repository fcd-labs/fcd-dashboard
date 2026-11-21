package com.fcd.dashboard

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

data class StepConfig(
    val name: String,
    val title: String,
    val gate: String?,
    val testType: String?,
    /** container: the step runs per container of the sub-product (a row per container); else for the sub-product. */
    val level: String = "subProduct",
    /** Id of the approved explain when the step is shortened for the demo (Step.spec.shortened). */
    val shortened: String? = null,
    /** Not built yet (Pipeline.spec.backlog): grey on the Dashboard, never run. */
    val backlog: Boolean = false,
    /** For a backlog step: the step it comes after (Step.spec.after). */
    val after: String? = null,
) {
    val perContainer: Boolean get() = level == "container"
}

data class ExplanationConfig(
    val id: String,
    val rule: String?,
    val reason: String?,
    val until: String?,
)

data class StubConfig(
    val name: String,
    val title: String,
    val receiver: String,
    /** The name of the container in the code (docker compose), when it differs from the name in the configuration. */
    val service: String? = null,
)

/** Which buttons the Dashboard shows. */
data class Controls(
    val startButton: Boolean = false,
    val demoReset: Boolean = false,
    val approver: String? = null,
)

data class GateConfig(
    val name: String,
    val title: String,
    val afterSteps: List<String>,
    val beforeSteps: List<String>,
)

data class Presentation(
    val title: String? = null,
    val theme: String = "dark",
    val fontScale: Double = 1.5,
    val focusRunning: Boolean = true,
)

data class LiveSettings(
    /** The configuration of the showcase was read. */
    val configured: Boolean = false,
    /** Which sub-products, in this order; empty is all. */
    val subProducts: List<String> = emptyList(),
    /** Which runs (werkplek, lokaal); empty is all. */
    val environments: List<String> = emptyList(),
    val layout: String = "stacked",
    val keepFinishedSeconds: Int = 600,
    val presentation: Presentation = Presentation(),
)

data class SubProductConfig(
    val name: String,
    val title: String,
    val product: String?,
    val containers: List<String>,
)

/** One place on the line of the live view: a step or a gate. */
data class LaneEntry(
    val kind: String,
    val name: String,
    val title: String,
    /** A gate before a step (such as the human gate at the start and before releasing): that step. */
    val before: String? = null,
) {
    /** Unique on the line: a gate can stand before more than one step. */
    val key: String get() = if (before != null) "$name@$before" else name
}

/**
 * What the Dashboard reads from the configuration of its showcase (fcd-<showcase>/fcd/\*.fcd.yaml, FCD model):
 * the steps and gates in their order (kind Pipeline, Step, QualityGate), the sub-products, and what the live view shows
 * (kind Dashboard, spec.live). Read only; nothing showcase-specific in the code.
 */
class ShowcaseConfig(
    val showcase: String?,
    val steps: List<StepConfig>,
    val gates: List<GateConfig>,
    val subProducts: List<SubProductConfig>,
    val live: LiveSettings,
    val backlog: List<StepConfig> = emptyList(),
    val explanations: Map<String, ExplanationConfig> = emptyMap(),
    val controls: Controls = Controls(),
    val containerTitles: Map<String, String> = emptyMap(),
    /** Stubs by the receiver they stand in for (Container with spec.stub and spec.receiver). */
    val stubs: Map<String, StubConfig> = emptyMap(),
    val productTitles: Map<String, String> = emptyMap(),
) {
    fun productTitle(name: String): String = productTitles[name] ?: name

    fun receiverStub(receiver: String): StubConfig? = stubs[receiver]

    /** The row of a container: the steps of level container, with the backlog steps that come after one of them. */
    val containerLane: List<LaneEntry> by lazy {
        withBacklog(
            steps.filter { it.perContainer }.map { LaneEntry("step", it.name, it.title) },
        )
    }

    /** The row of the sub-product: the other steps and all gates, with the backlog steps in their place. */
    val subProductLane: List<LaneEntry> by lazy {
        withBacklog(lane.filterNot { it.kind == "step" && step(it.name)?.perContainer == true })
    }

    private fun withBacklog(entries: List<LaneEntry>): List<LaneEntry> {
        val out = entries.toMutableList()
        for (b in backlog) {
            val at = out.indexOfLast { it.kind == "step" && it.name == b.after }
            if (b.after != null && at < 0) continue
            val entry = LaneEntry("step", b.name, b.title)
            // After the step it follows and after the gates that close behind that step (not before the gate of the next step).
            var i = if (at < 0) out.size else at + 1
            // ... and after the backlog steps placed there before, so they keep the order of the configuration.
            while (i < out.size &&
                (
                    (out[i].kind == "gate" && out[i].before == null) ||
                        backlog.any { it.name == out[i].name && it.after == b.after }
                )
            ) {
                i++
            }
            out.add(i, entry)
        }
        return out
    }

    fun containerTitle(name: String): String = containerTitles[name] ?: name

    /** The steps and gates in order: gates before a step, the step, then the gates that close after it. */
    val lane: List<LaneEntry> by lazy {
        val order = steps.map { it.name }
        val placed = mutableSetOf<String>()
        val entries = mutableListOf<LaneEntry>()

        fun gate(g: GateConfig) {
            if (placed.add(g.name)) entries += LaneEntry("gate", g.name, g.title)
        }
        for (step in steps) {
            gates.filter { step.name in it.beforeSteps }.forEach {
                placed += it.name
                entries += LaneEntry("gate", it.name, it.title, before = step.name)
            }
            entries += LaneEntry("step", step.name, step.title)
            gates
                .filter { g -> g.afterSteps.isNotEmpty() && g.afterSteps.maxOf { order.indexOf(it) } == order.indexOf(step.name) }
                .forEach(::gate)
        }
        gates.forEach(::gate)
        entries
    }

    fun step(name: String): StepConfig? = steps.firstOrNull { it.name == name } ?: backlog.firstOrNull { it.name == name }

    fun subProductTitle(name: String): String = subProducts.firstOrNull { it.name == name }?.title ?: name

    companion object {
        private val log = LoggerFactory.getLogger(ShowcaseConfig::class.java)

        val EMPTY = ShowcaseConfig(null, emptyList(), emptyList(), emptyList(), LiveSettings())

        private fun JsonNode.optText(field: String): String? = path(field).asText("").ifBlank { null }

        /** Reads all \*.yaml files in [dir]. Without a directory or with an error: [EMPTY] (the live view still works). */
        fun load(dir: Path): ShowcaseConfig {
            if (!dir.isDirectory()) return EMPTY
            return try {
                val files =
                    Files.list(dir).use { s ->
                        s.filter { it.isRegularFile() && (it.name.endsWith(".yaml") || it.name.endsWith(".yml")) }.sorted().toList()
                    }
                parse(files.flatMap { readDocuments(Files.readString(it)) })
            } catch (e: Exception) {
                log.warn("The configuration of the showcase in {} could not be read: {}", dir, e.message)
                EMPTY
            }
        }

        fun parse(yaml: String): ShowcaseConfig =
            try {
                parse(readDocuments(yaml))
            } catch (e: Exception) {
                log.warn("The configuration of the showcase could not be read: {}", e.message)
                EMPTY
            }

        private fun readDocuments(yaml: String): List<JsonNode> {
            val factory = YAMLFactory()
            factory.createParser(yaml).use { parser ->
                return Json.mapper
                    .readValues(parser, JsonNode::class.java)
                    .readAll()
                    .filter { it.isObject }
            }
        }

        private fun parse(docs: List<JsonNode>): ShowcaseConfig {
            fun ofKind(kind: String) = docs.filter { it.path("kind").asText() == kind }

            fun JsonNode.name() = path("metadata").path("name").asText()

            fun JsonNode.title() = path("metadata").path("title").asText("").ifBlank { name() }

            fun JsonNode.strings() = if (isArray) map { it.asText() } else emptyList()

            val stepDocs = ofKind("Step").associateBy { it.name() }
            val pipeline = ofKind("Pipeline").firstOrNull()
            val order =
                pipeline
                    ?.path("spec")
                    ?.path("steps")
                    ?.strings()
                    .orEmpty()

            fun stepConfig(
                name: String,
                backlog: Boolean,
            ): StepConfig {
                val spec = stepDocs[name]?.path("spec")
                return StepConfig(
                    name = name,
                    title = stepDocs[name]?.title() ?: name,
                    gate = spec?.optText("gate"),
                    testType = spec?.optText("testType"),
                    level = spec?.optText("level") ?: "subProduct",
                    shortened = spec?.optText("shortened"),
                    backlog = backlog,
                    after = spec?.optText("after"),
                )
            }
            val steps = order.map { stepConfig(it, backlog = false) }
            val backlog =
                pipeline
                    ?.path("spec")
                    ?.path("backlog")
                    ?.strings()
                    .orEmpty()
                    .filterNot { it in order }
                    .map { stepConfig(it, backlog = true) }
            val explanations =
                ofKind("Explanation").associate {
                    val spec = it.path("spec")
                    it.name() to ExplanationConfig(it.name(), spec.optText("rule"), spec.optText("reason"), spec.optText("until"))
                }
            val humanGate = ofKind("QualityGate").firstOrNull { it.path("spec").has("approval") }
            val controls =
                Controls(
                    startButton = pipeline?.path("spec")?.optText("trigger") == "dashboard-button",
                    demoReset = pipeline?.path("spec")?.path("demo")?.isObject == true,
                    approver = humanGate?.path("spec")?.optText("approver"),
                )
            val gates =
                ofKind("QualityGate").map {
                    GateConfig(
                        name = it.name(),
                        title = it.title(),
                        afterSteps = it.path("spec").path("afterSteps").strings(),
                        beforeSteps = it.path("spec").path("beforeSteps").strings(),
                    )
                }
            val containers = ofKind("Container")
            val subProducts =
                ofKind("SubProduct").map { sp ->
                    SubProductConfig(
                        name = sp.name(),
                        title = sp.title(),
                        product =
                            sp
                                .path("spec")
                                .path("product")
                                .asText("")
                                .ifBlank { null },
                        containers = containers.filter { it.path("spec").path("subProduct").asText() == sp.name() }.map { it.name() },
                    )
                }
            val live = ofKind("Dashboard").firstOrNull()?.path("spec")?.path("live")
            val p = live?.path("presentation")
            val defaults = LiveSettings()
            val settings =
                LiveSettings(
                    configured = docs.isNotEmpty(),
                    subProducts = live?.path("subProducts")?.strings().orEmpty(),
                    environments = live?.path("environments")?.strings().orEmpty(),
                    layout = live?.path("layout")?.asText("")?.takeIf { it in setOf("stacked", "side-by-side") } ?: defaults.layout,
                    keepFinishedSeconds =
                        live?.path("keepFinishedSeconds")?.takeIf { it.isInt && it.asInt() >= 0 }?.asInt()
                            ?: defaults.keepFinishedSeconds,
                    presentation =
                        Presentation(
                            title = p?.path("title")?.asText("")?.ifBlank { null },
                            theme = p?.path("theme")?.asText("")?.takeIf { it in setOf("dark", "light") } ?: defaults.presentation.theme,
                            fontScale =
                                p
                                    ?.path("fontScale")
                                    ?.takeIf { it.isNumber }
                                    ?.asDouble()
                                    ?.coerceIn(0.5, 4.0)
                                    ?: defaults.presentation.fontScale,
                            focusRunning =
                                p?.path("focusRunning")?.takeIf { it.isBoolean }?.asBoolean() ?: defaults.presentation.focusRunning,
                        ),
                )
            val showcase =
                ofKind("Factory")
                    .firstOrNull()
                    ?.path("spec")
                    ?.path("showcase")
                    ?.asText("")
                    ?.ifBlank { null }
            val containerTitles = containers.associate { it.name() to it.title() }
            val stubs =
                containers
                    .filter { it.path("spec").path("stub").asBoolean() && it.path("spec").optText("receiver") != null }
                    .associate {
                        val receiver = it.path("spec").optText("receiver")!!
                        receiver to StubConfig(it.name(), it.title(), receiver, it.path("spec").optText("service"))
                    }
            val productTitles = ofKind("Product").associate { it.name() to it.title() }
            return ShowcaseConfig(
                showcase,
                steps,
                gates,
                subProducts,
                settings,
                backlog,
                explanations,
                controls,
                containerTitles,
                stubs,
                productTitles,
            )
        }
    }
}
