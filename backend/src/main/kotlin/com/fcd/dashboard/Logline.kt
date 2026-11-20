package com.fcd.dashboard

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinFeature
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaValidatorsConfig
import com.networknt.schema.SpecVersion
import java.time.Instant

object Json {
    val mapper: ObjectMapper =
        JsonMapper
            .builder()
            .addModule(kotlinModule { enable(KotlinFeature.NewStrictNullChecks) })
            .addModule(JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build()
}

class InvalidLoglineException(
    message: String,
) : RuntimeException(message)

/** One received log line: the raw CDEvent plus the fields the Dashboard selects on. */
data class Logline(
    val id: String,
    val type: String,
    val timestamp: Instant,
    val product: String,
    val subProduct: String,
    val experiment: String,
    val run: String,
    val payload: JsonNode,
) {
    val content: JsonNode get() = payload.path("subject").path("content")
    val custom: JsonNode get() = payload.path("customData")
    val shortType: String get() = SHORT_TYPES[type] ?: type

    companion object {
        val SHORT_TYPES =
            mapOf(
                "dev.cdevents.pipelinerun.started.0.2.0" to "pipelinerun.started",
                "dev.cdevents.pipelinerun.finished.0.2.0" to "pipelinerun.finished",
                "dev.cdevents.taskrun.started.0.2.0" to "taskrun.started",
                "dev.cdevents.taskrun.finished.0.2.0" to "taskrun.finished",
                "dev.cdevents.testsuiterun.finished.0.2.0" to "testsuiterun.finished",
                "dev.cdevents.service.deployed.0.2.0" to "service.deployed",
                "dev.cdevents.artifact.published.0.2.0" to "artifact.published",
                "dev.cdeventsx.fcd-qualitygate.evaluated.0.1.0" to "qualitygate.evaluated",
                "dev.cdeventsx.fcd-keyresult.measured.0.1.0" to "keyresult.measured",
            )
    }
}

/** Checks a log line against the contract (contracts/logline.schema.json) and reads it. */
class LoglineReader {
    private val schema: JsonSchema =
        JsonSchemaFactory
            .getInstance(SpecVersion.VersionFlag.V202012)
            .getSchema(
                javaClass.getResourceAsStream("/logline.schema.json") ?: error("logline.schema.json is missing"),
                // Formats such as date-time are part of the contract: check them, not only annotate.
                SchemaValidatorsConfig.builder().formatAssertionsEnabled(true).build(),
            )

    fun read(body: String): Logline {
        val node =
            try {
                Json.mapper.readTree(body)
            } catch (e: com.fasterxml.jackson.core.JacksonException) {
                throw InvalidLoglineException("The log line is not valid JSON.")
            } ?: throw InvalidLoglineException("The log line is empty.")
        // PostgreSQL (jsonb) does not store the NUL character; a log line never needs it.
        if (body.contains("\\u0000") || body.contains('\u0000')) throw InvalidLoglineException("The log line contains a NUL character.")
        val errors = schema.validate(node)
        if (errors.isNotEmpty()) {
            throw InvalidLoglineException("The log line does not conform to the contract: " + errors.take(3).joinToString { it.message })
        }
        val context = node.path("context")
        val custom = node.path("customData")
        val timestamp =
            try {
                Instant.parse(context.path("timestamp").asText())
            } catch (e: java.time.format.DateTimeParseException) {
                throw InvalidLoglineException("context.timestamp is not a date-time.")
            }
        return Logline(
            id = context.path("id").asText(),
            type = context.path("type").asText(),
            timestamp = timestamp,
            product = custom.path("product").asText(),
            subProduct = custom.path("subProduct").path("name").asText(),
            experiment = custom.path("experiment").asText(),
            run = custom.path("run").asText(""),
            payload = node,
        )
    }
}
