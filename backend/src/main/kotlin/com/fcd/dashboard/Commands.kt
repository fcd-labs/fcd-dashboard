package com.fcd.dashboard

import com.zaxxer.hikari.HikariDataSource
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * A button of the product owner: start the pipeline, approve the release, reset the demo.
 * The Dashboard only keeps it; the Pipeline agent claims it and carries it out (contract dashboard-api).
 */
data class Command(
    val id: String,
    val kind: String,
    val run: String? = null,
    val save: Boolean? = null,
    val requestedBy: String,
    val requestedAt: Instant,
    val status: String = "pending",
    val message: String? = null,
)

data class CommandRequest(
    val kind: String = "",
    val run: String? = null,
    val save: Boolean? = null,
)

object CommandKinds {
    const val START_RUN = "start-run"
    const val APPROVE_RELEASE = "approve-release"
    const val DEMO_RESET = "demo-reset"
    val ALL = setOf(START_RUN, APPROVE_RELEASE, DEMO_RESET)
    val OUTCOMES = setOf("done", "failed", "rejected")
}

interface CommandStore {
    fun add(command: Command)

    /** The oldest pending command of one of these kinds (and of this run, if given), now claimed; null if none. */
    fun claim(
        kinds: Set<String>,
        run: String?,
    ): Command?

    /** Returns false when there is no such claimed or pending command. */
    fun finish(
        id: String,
        outcome: String,
        message: String?,
    ): Boolean

    /** Newest first. */
    fun recent(limit: Int = 20): List<Command>
}

class InMemoryCommandStore : CommandStore {
    private val commands = ConcurrentHashMap<String, Command>()

    override fun add(command: Command) {
        commands[command.id] = command
    }

    @Synchronized
    override fun claim(
        kinds: Set<String>,
        run: String?,
    ): Command? {
        val c =
            commands.values
                .filter { it.status == "pending" && it.kind in kinds && (run == null || it.run == run) }
                .minByOrNull { it.requestedAt } ?: return null
        return c.copy(status = "claimed").also { commands[c.id] = it }
    }

    @Synchronized
    override fun finish(
        id: String,
        outcome: String,
        message: String?,
    ): Boolean {
        val c = commands[id]?.takeIf { it.status == "pending" || it.status == "claimed" } ?: return false
        commands[id] = c.copy(status = outcome, message = message)
        return true
    }

    override fun recent(limit: Int): List<Command> = commands.values.sortedByDescending { it.requestedAt }.take(limit)
}

/** PostgreSQL: one table next to the log lines. Claiming is atomic (FOR UPDATE SKIP LOCKED). */
class PostgresCommandStore(
    private val dataSource: HikariDataSource,
) : CommandStore {
    init {
        dataSource.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    """
                    CREATE TABLE IF NOT EXISTS command (
                      id           uuid PRIMARY KEY,
                      kind         text NOT NULL,
                      run          text,
                      save         boolean,
                      requested_by text NOT NULL,
                      requested_at timestamptz NOT NULL,
                      status       text NOT NULL,
                      message      text
                    )
                    """.trimIndent(),
                )
                it.execute("CREATE INDEX IF NOT EXISTS command_pending ON command (status, requested_at)")
            }
        }
    }

    override fun add(command: Command) {
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    "INSERT INTO command (id, kind, run, save, requested_by, requested_at, status, message) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                ).use { st ->
                    st.setObject(1, UUID.fromString(command.id))
                    st.setString(2, command.kind)
                    st.setString(3, command.run)
                    st.setObject(4, command.save)
                    st.setString(5, command.requestedBy)
                    st.setTimestamp(6, Timestamp.from(command.requestedAt))
                    st.setString(7, command.status)
                    st.setString(8, command.message)
                    st.executeUpdate()
                }
        }
    }

    override fun claim(
        kinds: Set<String>,
        run: String?,
    ): Command? =
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    """
                    UPDATE command SET status = 'claimed'
                    WHERE id = (
                      SELECT id FROM command
                      WHERE status = 'pending' AND kind = ANY (?) AND (?::text IS NULL OR run = ?)
                      ORDER BY requested_at LIMIT 1 FOR UPDATE SKIP LOCKED
                    )
                    RETURNING id, kind, run, save, requested_by, requested_at, status, message
                    """.trimIndent(),
                ).use { st ->
                    st.setArray(1, c.createArrayOf("text", kinds.toTypedArray()))
                    st.setString(2, run)
                    st.setString(3, run)
                    st.executeQuery().use { rs -> if (rs.next()) rs.command() else null }
                }
        }

    override fun finish(
        id: String,
        outcome: String,
        message: String?,
    ): Boolean =
        dataSource.connection.use { c ->
            c.prepareStatement("UPDATE command SET status = ?, message = ? WHERE id = ? AND status IN ('pending', 'claimed')").use { st ->
                st.setString(1, outcome)
                st.setString(2, message)
                st.setObject(3, UUID.fromString(id))
                st.executeUpdate() == 1
            }
        }

    override fun recent(limit: Int): List<Command> =
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    "SELECT id, kind, run, save, requested_by, requested_at, status, message FROM command ORDER BY requested_at DESC LIMIT ?",
                ).use { st ->
                    st.setInt(1, limit)
                    st.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.command()) } }
                }
        }

    private fun ResultSet.command() =
        Command(
            id = getString("id"),
            kind = getString("kind"),
            run = getString("run"),
            save = getObject("save") as Boolean?,
            requestedBy = getString("requested_by"),
            requestedAt = getTimestamp("requested_at").toInstant(),
            status = getString("status"),
            message = getString("message"),
        )
}
