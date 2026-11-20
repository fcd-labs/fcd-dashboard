package com.fcd.dashboard

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Timestamp
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** The store of the Dashboard: all log lines, per experiment. Adding is idempotent on the log line id. */
interface EventStore {
    /** Returns false when a log line with this id was already there. */
    fun add(line: Logline): Boolean

    fun all(experiment: String): List<Logline>

    fun experiments(): List<String>

    /** The experiment of the most recent log line; test experiments (test-...) never count. */
    fun latestExperiment(): String?
}

const val TEST_EXPERIMENT_PREFIX = "test-"

/** Log lines in order of time, then in order of arrival (timestamps have a resolution of seconds). */
class InMemoryEventStore : EventStore {
    private val lines = ConcurrentHashMap<String, Pair<Long, Logline>>()
    private val sequence = AtomicLong()

    override fun add(line: Logline): Boolean = lines.putIfAbsent(line.id, sequence.incrementAndGet() to line) == null

    override fun all(experiment: String): List<Logline> =
        lines.values
            .filter { it.second.experiment == experiment }
            .sortedWith(compareBy({ it.second.timestamp }, { it.first }))
            .map { it.second }

    override fun experiments(): List<String> =
        lines.values
            .map { it.second.experiment }
            .distinct()
            .sorted()

    override fun latestExperiment(): String? =
        lines.values
            .filterNot { it.second.experiment.startsWith(TEST_EXPERIMENT_PREFIX) }
            .maxWithOrNull(compareBy({ it.second.timestamp }, { it.first }))
            ?.second
            ?.experiment
}

/** PostgreSQL: one table, the raw log line as JSONB next to the columns the Dashboard selects on. */
class PostgresEventStore(
    url: String,
    user: String,
    password: String,
) : EventStore {
    private val dataSource =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = url
                username = user
                this.password = password
                maximumPoolSize = 4
                initializationFailTimeout = 60_000
            },
        )
    private val reader = LoglineReader()

    init {
        dataSource.connection.use { c ->
            c.createStatement().use {
                it.execute(
                    """
                    CREATE TABLE IF NOT EXISTS logline (
                      seq         bigserial,
                      id          text PRIMARY KEY,
                      type        text NOT NULL,
                      ts          timestamptz NOT NULL,
                      product     text NOT NULL,
                      sub_product text NOT NULL,
                      experiment  text NOT NULL,
                      run         text NOT NULL,
                      received_at timestamptz NOT NULL DEFAULT now(),
                      payload     jsonb NOT NULL
                    )
                    """.trimIndent(),
                )
                it.execute("CREATE INDEX IF NOT EXISTS logline_experiment_ts ON logline (experiment, ts, seq)")
            }
        }
    }

    override fun add(line: Logline): Boolean =
        dataSource.connection.use { c ->
            c
                .prepareStatement(
                    "INSERT INTO logline (id, type, ts, product, sub_product, experiment, run, payload) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT (id) DO NOTHING",
                ).use { st ->
                    st.setString(1, line.id)
                    st.setString(2, line.type)
                    st.setTimestamp(3, Timestamp.from(line.timestamp))
                    st.setString(4, line.product)
                    st.setString(5, line.subProduct)
                    st.setString(6, line.experiment)
                    st.setString(7, line.run)
                    st.setString(8, Json.mapper.writeValueAsString(line.payload))
                    st.executeUpdate() == 1
                }
        }

    override fun all(experiment: String): List<Logline> =
        dataSource.connection.use { c ->
            c.prepareStatement("SELECT payload::text FROM logline WHERE experiment = ? ORDER BY ts, seq").use { st ->
                st.setString(1, experiment)
                st.executeQuery().use { rs ->
                    buildList { while (rs.next()) add(reader.read(rs.getString(1))) }
                }
            }
        }

    override fun experiments(): List<String> =
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st.executeQuery("SELECT DISTINCT experiment FROM logline ORDER BY experiment").use { rs ->
                    buildList { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    override fun latestExperiment(): String? =
        dataSource.connection.use { c ->
            c.createStatement().use { st ->
                st
                    .executeQuery(
                        "SELECT experiment FROM logline WHERE experiment NOT LIKE 'test-%' ORDER BY ts DESC, seq DESC LIMIT 1",
                    ).use { rs ->
                        if (rs.next()) rs.getString(1) else null
                    }
            }
        }
}
