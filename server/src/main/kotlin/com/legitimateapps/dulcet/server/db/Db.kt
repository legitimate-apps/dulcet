package com.legitimateapps.dulcet.server.db

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Types
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One SQLite database file, opened once. SQLite serialises writers anyway, so the server holds a
 * single connection behind a lock: simple, and fast enough for a personal library. Every query goes
 * through [read] or [write]; a transaction is a [write] block.
 */
class Db private constructor(private val connection: Connection, val file: Path? = null) : AutoCloseable {
    private val lock = ReentrantLock()

    /** Internal, opt-in work accounting. Never receives bound parameters or result rows. */
    internal interface WorkObserver {
        fun statement(sql: String)
        fun transaction(write: Boolean)
        fun statementFinished(sql: String, nanos: Long) {}
        fun transactionFinished(write: Boolean, nanos: Long) {}
    }
    @Volatile internal var workObserver: WorkObserver? = null

    /** Coordinate operations that must span several transactions, such as a restore. */
    fun <T> exclusive(block: () -> T): T = lock.withLock(block)

    /** SQLite's online backup includes committed WAL pages without closing existing references. */
    fun checkpoint(destination: Path) = lock.withLock {
        check(connection.autoCommit) { "Cannot checkpoint during a transaction" }
        val target = destination.toAbsolutePath().normalize()
        require(file != target && (file == null || !Files.exists(target) || !Files.isSameFile(file, target))) { "Checkpoint must use a different file" }
        Files.createDirectories(target.parent)
        val temporary = Files.createTempFile(target.parent, ".checkpoint-", ".db")
        try {
            val sqlite = connection as org.sqlite.SQLiteConnection
            val result = sqlite.database.backup("main", temporary.toString(), null)
            if (result != org.sqlite.SQLiteErrorCode.SQLITE_OK.code) throw org.sqlite.core.DB.newSQLException(result, "Checkpoint failed")
            FileChannel.open(temporary, StandardOpenOption.WRITE).use { it.force(true) }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            FileChannel.open(target.parent, StandardOpenOption.READ).use { it.force(true) }
        } finally { Files.deleteIfExists(temporary) }
    }

    /** Replace main through SQLite's backup API while keeping this connection and Db object. */
    fun restore(source: Path) = lock.withLock {
        check(connection.autoCommit) { "Cannot restore during a transaction" }
        val backup = source.toAbsolutePath().normalize()
        require(Files.isRegularFile(backup) && (file == null || !Files.isSameFile(file, backup))) { "Restore requires a separate database file" }
        val config = org.sqlite.SQLiteConfig().apply { setReadOnly(true) }
        DriverManager.getConnection("jdbc:sqlite:$backup", config.toProperties()).use { candidate ->
            candidate.createStatement().use { statement ->
                statement.executeQuery("PRAGMA integrity_check").use { rows ->
                    require(rows.next() && rows.getString(1) == "ok" && !rows.next()) { "Invalid checkpoint" }
                }
            }
        }
        val result = (connection as org.sqlite.SQLiteConnection).database.restore("main", backup.toString(), null)
        if (result != org.sqlite.SQLiteErrorCode.SQLITE_OK.code) throw org.sqlite.core.DB.newSQLException(result, "Restore failed")
    }

    private val beforeCommit = ArrayList<(Session) -> Unit>()
    fun beforeCommit(listener: (Session) -> Unit) = lock.withLock { beforeCommit.add(listener); Unit }

    private val afterCommit = ArrayList<(Session) -> Unit>()
    /** Observers run after commit with autoCommit restored; observer failure cannot undo committed data. */
    fun afterCommit(listener: (Session) -> Unit) = lock.withLock { afterCommit.add(listener); Unit }

    /** Nested reads share the enclosing snapshot; every outer read is one SQLite transaction. */
    fun <T> read(block: Session.() -> T): T = lock.withLock {
        if (!connection.autoCommit) Session(connection, workObserver).block() else transaction(false, block)
    }

    fun <T> write(block: Session.() -> T): T = lock.withLock {
        if (!connection.autoCommit) {
            val savepoint = connection.setSavepoint()
            try {
                val result = Session(connection, workObserver).block()
                connection.releaseSavepoint(savepoint)
                return@withLock result
            } catch (error: Throwable) {
                connection.rollback(savepoint)
                connection.releaseSavepoint(savepoint)
                throw error
            }
        }
        transaction(true, block)
    }

    private fun <T> transaction(notify: Boolean, block: Session.() -> T): T {
        val observer = workObserver
        val started = if (observer == null) 0L else System.nanoTime()
        observer?.transaction(notify)
        connection.autoCommit = false
        try {
            val session = Session(connection, workObserver)
            val initialChanges = session.long("SELECT total_changes()")
            val result = session.block()
            // Some read helpers reconcile stored metadata. Those writes must commit through the
            // journal hook too; a pure read must not trigger retention or alter the timeline.
            if (notify || session.long("SELECT total_changes()") != initialChanges) beforeCommit.forEach { it(session) }
            connection.commit()
            connection.autoCommit = true
            if (notify || session.long("SELECT total_changes()") != initialChanges) afterCommit.forEach { listener -> runCatching { listener(session) } }
            return result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = true
            observer?.transactionFinished(notify, System.nanoTime() - started)
        }
    }

    override fun close() = lock.withLock { connection.close() }

    class Session internal constructor(val connection: Connection, private val observer: WorkObserver? = null) {
        private inline fun <T> observed(sql: String, block: () -> T): T {
            val started = if (observer == null) 0L else System.nanoTime()
            observer?.statement(sql)
            try { return block() } finally { observer?.statementFinished(sql, System.nanoTime() - started) }
        }
        fun execute(sql: String, vararg args: Any?): Int = observed(sql) { prepare(sql, args).use { it.executeUpdate() } }

        fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> =
            observed(sql) { prepare(sql, args).use { statement ->
                statement.executeQuery().use { rows ->
                    val result = ArrayList<T>()
                    val row = Row(rows)
                    while (rows.next()) result.add(map(row))
                    result
                }
            } }

        fun <T> single(sql: String, vararg args: Any?, map: (Row) -> T): T? = query(sql, *args, map = map).firstOrNull()

        fun long(sql: String, vararg args: Any?): Long = single(sql, *args) { it.long(1) } ?: 0L

        fun string(sql: String, vararg args: Any?): String? = single(sql, *args) { it.stringOrNull(1) }

        fun batch(sql: String, rows: List<List<Any?>>) {
            if (rows.isEmpty()) return
            observed(sql) { connection.prepareStatement(sql).use { statement ->
                for (row in rows) {
                    bind(statement, row.toTypedArray())
                    statement.addBatch()
                }
                statement.executeBatch()
            } }
        }

        private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement {
            val statement = connection.prepareStatement(sql)
            bind(statement, args)
            return statement
        }

        private fun bind(statement: PreparedStatement, args: Array<out Any?>) {
            args.forEachIndexed { index, value ->
                val position = index + 1
                when (value) {
                    null -> statement.setNull(position, Types.NULL)
                    is String -> statement.setString(position, value)
                    is Int -> statement.setInt(position, value)
                    is Long -> statement.setLong(position, value)
                    is Double -> statement.setDouble(position, value)
                    is Float -> statement.setDouble(position, value.toDouble())
                    is Boolean -> statement.setInt(position, if (value) 1 else 0)
                    is ByteArray -> statement.setBytes(position, value)
                    else -> error("unsupported SQL parameter type ${value::class}")
                }
            }
        }
    }

    /** A cursor view by column name or 1-based index; values are read while the row is current. */
    class Row(private val rows: ResultSet) {
        fun string(name: String): String = rows.getString(name) ?: ""
        fun stringOrNull(name: String): String? = rows.getString(name)
        fun stringOrNull(index: Int): String? = rows.getString(index)
        fun long(name: String): Long = rows.getLong(name)
        fun long(index: Int): Long = rows.getLong(index)
        fun longOrNull(name: String): Long? = rows.getLong(name).takeUnless { rows.wasNull() }
        fun int(name: String): Int = rows.getInt(name)
        fun intOrNull(name: String): Int? = rows.getInt(name).takeUnless { rows.wasNull() }
        fun double(name: String): Double = rows.getDouble(name)
        fun doubleOrNull(name: String): Double? = rows.getDouble(name).takeUnless { rows.wasNull() }
        fun bool(name: String): Boolean = rows.getInt(name) != 0
        fun bytes(name: String): ByteArray? = rows.getBytes(name)
    }

    companion object {
        fun open(file: Path): Db {
            file.parent?.let(Files::createDirectories)
            val connection = DriverManager.getConnection("jdbc:sqlite:${file.toAbsolutePath()}")
            connection.createStatement().use { statement ->
                statement.execute("PRAGMA journal_mode=WAL")
                statement.execute("PRAGMA foreign_keys=ON")
                statement.execute("PRAGMA synchronous=NORMAL")
                statement.execute("PRAGMA busy_timeout=5000")
            }
            val db = Db(connection, file.toAbsolutePath().normalize())
            Schema.migrate(db)
            return db
        }

        fun inMemory(): Db {
            val connection = DriverManager.getConnection("jdbc:sqlite::memory:")
            connection.createStatement().use { it.execute("PRAGMA foreign_keys=ON") }
            val db = Db(connection)
            Schema.migrate(db)
            return db
        }
    }
}
