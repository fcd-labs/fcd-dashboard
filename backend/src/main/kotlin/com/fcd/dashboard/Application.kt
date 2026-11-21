package com.fcd.dashboard

import io.ktor.http.CacheControl
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.jackson.JacksonConverter
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.cacheControl
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytesWriter
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.readRemaining
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.readByteArray
import org.slf4j.LoggerFactory
import java.nio.file.Path

/** Largest accepted log line. */
private const val MAX_BODY_BYTES = 64 * 1024
private val experimentPattern = Regex("^[A-Za-z0-9._-]{1,50}$")
private val namePattern = Regex("^[a-z0-9-]{1,100}$")

/** How long one live stream stays open; EventSource in the browser connects again by itself. */
private const val LIVE_STREAM_MILLIS = 30 * 60 * 1000L
private const val LIVE_HEARTBEAT_MILLIS = 15_000L

/** Collects log lines that arrive together into one update of the live view. */
private const val LIVE_COALESCE_MILLIS = 150L

fun main(args: Array<String>) {
    // The same image runs the simulator: java -jar app.jar simulate --scenario ...
    if (args.firstOrNull() == "simulate") return simulate(args.drop(1))
    val config = ShowcaseConfig.load(Path.of(env("FCD_CONFIG_DIR", "/config")))
    val store =
        PostgresEventStore(
            url = env("DB_URL"),
            user = env("DB_USER"),
            password = env("DB_PASSWORD"),
        )
    val commands = PostgresCommandStore(store.dataSource)
    embeddedServer(Netty, port = env("PORT", "8080").toInt()) {
        dashboard(store, env("DEFAULT_EXPERIMENT", "mvp0"), env("SHOWCASE"), config, commands = commands)
    }.start(wait = true)
}

private fun env(
    name: String,
    default: String? = null,
): String = System.getenv(name) ?: default ?: error("Environment variable $name is not set")

/** The Dashboard API, see contracts/dashboard-api.openapi.yaml and contracts/logline.openapi.yaml. */
fun Application.dashboard(
    store: EventStore,
    defaultExperiment: String = "mvp0",
    showcase: String = "",
    config: ShowcaseConfig = ShowcaseConfig.EMPTY,
    liveStreamMillis: Long = LIVE_STREAM_MILLIS,
    liveHeartbeatMillis: Long = LIVE_HEARTBEAT_MILLIS,
    commands: CommandStore = InMemoryCommandStore(),
) {
    val reader = LoglineReader()
    val views = Views(store, defaultExperiment, showcase)
    val hub = LiveHub()
    val live = LiveViews(store, views, config, commands)
    val kpis = Kpis(views, store, config)
    val report = Report(views, kpis)
    val log = LoggerFactory.getLogger("dashboard")
    if (config.showcase != null && showcase.isNotBlank() && config.showcase != showcase) {
        log.warn("The configuration is for showcase {}, this Dashboard is for showcase {}.", config.showcase, showcase)
    }

    install(ContentNegotiation) { register(ContentType.Application.Json, JacksonConverter(Json.mapper)) }
    install(StatusPages) {
        exception<InvalidLoglineException> { call, e -> call.respondProblem(HttpStatusCode.BadRequest, "Invalid request", e.message) }
        exception<Throwable> { call, e ->
            log.error("Unexpected error", e)
            call.respondProblem(HttpStatusCode.InternalServerError, "Internal error", null)
        }
    }

    routing {
        // Logline contract: receive one log line (idempotent on context.id).
        post("/api/v1/events") {
            val line = reader.read(call.receiveLimitedJson())
            // Every showcase has its own Dashboard and store: a log line of another showcase does not belong here.
            val lineShowcase = line.custom.path("showcase").asText()
            if (showcase.isNotBlank() && lineShowcase != showcase) {
                throw InvalidLoglineException("This Dashboard is for showcase $showcase, not for $lineShowcase.")
            }
            if (store.add(line)) hub.changed(line.experiment)
            call.respondText("", status = HttpStatusCode.Accepted)
        }
        // The log of an experiment as it was received (one log line per line), to keep it in the showcase repository.
        get("/api/v1/events") {
            val lines = store.all(views.experiment(call.experimentParam()))
            call.respondText(
                lines.joinToString("") { Json.mapper.writeValueAsString(it.payload) + "\n" },
                ContentType.parse("application/x-ndjson"),
            )
        }
        get("/api/v1/overview") {
            call.respond(views.overview(call.experimentParam()))
        }
        get("/api/v1/products/{product}/subproducts/{name}") {
            val product = call.parameters["product"].orEmpty()
            val name = call.parameters["name"].orEmpty()
            if (!namePattern.matches(product) ||
                !namePattern.matches(name)
            ) {
                throw InvalidLoglineException("Invalid product or sub-product.")
            }
            val view = views.subProduct(call.experimentParam(), product, name)
            if (view ==
                null
            ) {
                call.respondProblem(HttpStatusCode.NotFound, "Not found", "No log lines for this sub-product.")
            } else {
                call.respond(view)
            }
        }
        get("/api/v1/live/state") {
            call.respond(live.state(call.experimentParam()))
        }
        // The live view as Server-Sent Events: the state now, then the new state after every log line.
        get("/api/v1/live") {
            val experiment = call.experimentParam()
            if (call.request.headers[HttpHeaders.Accept]?.contains("text/event-stream") != true) {
                call.respondProblem(HttpStatusCode.NotAcceptable, "Not acceptable", "Use Accept: text/event-stream.")
                return@get
            }
            call.response.cacheControl(CacheControl.NoCache(null))
            // nginx passes every event on at once.
            call.response.header("X-Accel-Buffering", "no")
            call.respondBytesWriter(ContentType.Text.EventStream) {
                liveStream(hub, liveStreamMillis, liveHeartbeatMillis) { live.state(experiment) }
            }
        }
        get("/api/v1/kpis") {
            call.respond(kpis.kpis(call.experimentParam()))
        }
        get("/api/v1/keyresult") {
            call.respond(kpis.keyResult(call.experimentParam()))
        }
        // Status report as Markdown: to view in the Dashboard and to keep or share as a file.
        get("/api/v1/report") {
            val experiment = call.experimentParam()
            val download =
                when (call.request.queryParameters["download"]) {
                    null, "false" -> false
                    "true" -> true
                    else -> throw InvalidLoglineException("download must be true or false.")
                }
            val text = report.markdown(experiment)
            if (download) {
                // Only safe characters in a header value.
                val safe = views.experiment(experiment).replace(Regex("[^A-Za-z0-9._-]"), "_")
                val name = "statusrapport-${showcase.ifBlank { "showcase" }}-$safe-${java.time.LocalDate.now(java.time.ZoneOffset.UTC)}.md"
                call.response.headers.append("Content-Disposition", "attachment; filename=\"$name\"")
            }
            call.respondText(text, ContentType.parse("text/markdown; charset=utf-8"))
        }
        get("/api/v1/changelog") {
            call.respond(live.changelogs(call.experimentParam()))
        }
        get("/api/v1/commands") {
            call.respond(commands.recent())
        }
        // A button of the product owner. The Dashboard keeps it; the Pipeline agent carries it out.
        post("/api/v1/commands") {
            val request =
                try {
                    Json.mapper.readValue(call.receiveLimitedJson(), CommandRequest::class.java)
                } catch (e: com.fasterxml.jackson.core.JacksonException) {
                    throw InvalidLoglineException("The command is not valid JSON.")
                }
            val refusal = live.refusal(request)
            if (refusal != null) {
                call.respondProblem(HttpStatusCode.Conflict, "Not now", refusal)
                return@post
            }
            val command =
                Command(
                    id =
                        java.util.UUID
                            .randomUUID()
                            .toString(),
                    kind = request.kind,
                    run = request.run?.takeIf { request.kind == CommandKinds.APPROVE_RELEASE },
                    save = request.save?.takeIf { request.kind == CommandKinds.DEMO_RESET },
                    // No login in mvp0 (approved explain): the one who presses is the approver of the configuration.
                    requestedBy = config.controls.approver ?: "product owner",
                    requestedAt = java.time.Instant.now(),
                )
            commands.add(command)
            hub.changed(views.experiment(null))
            call.respond(HttpStatusCode.Accepted, command)
        }
        post("/api/v1/commands/claim") {
            val kinds =
                call.request.queryParameters["kind"]
                    ?.split(",")
                    ?.toSet()
                    ?.takeIf { it.isNotEmpty() && CommandKinds.ALL.containsAll(it) }
                    ?: throw InvalidLoglineException("kind must be one or more of ${CommandKinds.ALL.joinToString()}.")
            val run = call.request.queryParameters["run"]?.takeIf { it.length <= 200 }
            val command = commands.claim(kinds, run)
            if (command == null) {
                call.respondText("", status = HttpStatusCode.NoContent)
            } else {
                hub.changed(views.experiment(null))
                call.respond(command)
            }
        }
        post("/api/v1/commands/{id}/done") {
            val id =
                call.parameters["id"]?.takeIf { runCatching { java.util.UUID.fromString(it) }.isSuccess }
                    ?: throw InvalidLoglineException("Invalid id.")
            val body = Json.mapper.readTree(call.receiveLimitedJson())
            val outcome = body.path("outcome").asText()
            if (outcome !in CommandKinds.OUTCOMES) throw InvalidLoglineException("outcome must be done, failed or rejected.")
            val message =
                body
                    .path("message")
                    .asText("")
                    .take(1000)
                    .ifBlank { null }
            if (commands.finish(id, outcome, message)) {
                hub.changed(views.experiment(null))
                call.respondText("", status = HttpStatusCode.NoContent)
            } else {
                call.respondProblem(HttpStatusCode.NotFound, "Not found", "No open command with this id.")
            }
        }
        get("/api/v1/tests") {
            call.respond(views.tests(call.experimentParam()))
        }
        get("/api/v1/loglines") {
            val limit =
                call.request.queryParameters["limit"]?.let {
                    it.toIntOrNull()?.takeIf { n -> n in 1..1000 } ?: throw InvalidLoglineException("limit must be between 1 and 1000.")
                } ?: 200
            call.respond(views.loglines(call.experimentParam(), limit))
        }
    }
}

private suspend fun ByteWriteChannel.liveStream(
    hub: LiveHub,
    streamMillis: Long,
    heartbeatMillis: Long,
    state: () -> LiveState,
) {
    var id = 0L

    suspend fun send() {
        id++
        // The store is read with JDBC (blocking).
        val json = withContext(Dispatchers.IO) { Json.mapper.writeValueAsString(state()) }
        writeStringUtf8("event: live-state\nid: $id\ndata: $json\n\n")
        flush()
    }
    try {
        coroutineScope {
            val updates = Channel<Unit>(Channel.CONFLATED)
            val listener = launch { hub.changes.collect { updates.trySend(Unit) } }
            writeStringUtf8("retry: 3000\n\n")
            send()
            val end = System.currentTimeMillis() + streamMillis
            while (true) {
                val left = end - System.currentTimeMillis()
                if (left <= 0) break
                val update = withTimeoutOrNull(minOf(heartbeatMillis, left)) { updates.receive() }
                if (update == null) {
                    writeStringUtf8(": heartbeat\n\n")
                    flush()
                } else {
                    delay(LIVE_COALESCE_MILLIS)
                    updates.tryReceive()
                    send()
                }
            }
            listener.cancel()
        }
    } catch (e: java.io.IOException) {
        // The browser went away.
    } catch (e: io.ktor.utils.io.ClosedWriteChannelException) {
        // The browser went away.
    }
}

private fun ApplicationCall.experimentParam(): String? {
    val e = request.queryParameters["experiment"] ?: return null
    if (!experimentPattern.matches(e)) throw InvalidLoglineException("Invalid experiment.")
    return e
}

private suspend fun ApplicationCall.receiveLimitedJson(): String {
    if (!request.contentType().match(ContentType.Application.Json)) throw InvalidLoglineException("Content-Type must be application/json.")
    if ((request.contentLength() ?: 0) > MAX_BODY_BYTES) throw InvalidLoglineException("Request body is too large.")
    val bytes = receiveChannel().readRemaining(MAX_BODY_BYTES + 1L).readByteArray()
    if (bytes.size > MAX_BODY_BYTES) throw InvalidLoglineException("Request body is too large.")
    return bytes.decodeToString()
}

/** Error details according to RFC 9457, without internal details. */
private suspend fun ApplicationCall.respondProblem(
    status: HttpStatusCode,
    title: String,
    detail: String?,
) {
    val problem =
        buildMap {
            put("type", "about:blank")
            put("title", title)
            put("status", status.value)
            if (detail != null) put("detail", detail)
        }
    respondText(Json.mapper.writeValueAsString(problem), ContentType.parse("application/problem+json"), status)
}
