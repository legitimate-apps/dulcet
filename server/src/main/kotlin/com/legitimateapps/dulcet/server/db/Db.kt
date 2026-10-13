package com.legitimateapps.dulcet.server.db

import java.nio.file.Files
import java.nio.file.Path
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
class Db private constructor(private val connection: Connection) : AutoCloseable {
    private val lock = ReentrantLock()

    fun <T> read(block: Session.() -> T): T = lock.withLock { Session(connection).block() }

    fun <T> write(block: Session.() -> T): T = lock.withLock {
        val previous = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = Session(connection).block()
            connection.commit()
            result
        } catch (error: Throwable) {
            connection.rollback()
            throw error
        } finally {
            connection.autoCommit = previous
        }
    }

    override fun close() = lock.withLock { connection.close() }

    class Session(val connection: Connection) {
        fun execute(sql: String, vararg args: Any?): Int = prepare(sql, args).use { it.executeUpdate() }

        fun <T> query(sql: String, vararg args: Any?, map: (Row) -> T): List<T> =
            prepare(sql, args).use { statement ->
                statement.executeQuery().use { rows ->
                    val result = ArrayList<T>()
                    val row = Row(rows)
                    while (rows.next()) result.add(map(row))
                    result
                }
            }

        fun <T> single(sql: String, vararg args: Any?, map: (Row) -> T): T? = query(sql, *args, map = map).firstOrNull()

        fun long(sql: String, vararg args: Any?): Long = single(sql, *args) { it.long(1) } ?: 0L

        fun string(sql: String, vararg args: Any?): String? = single(sql, *args) { it.stringOrNull(1) }

        fun batch(sql: String, rows: List<List<Any?>>) {
            if (rows.isEmpty()) return
            connection.prepareStatement(sql).use { statement ->
                for (row in rows) {
                    bind(statement, row.toTypedArray())
                    statement.addBatch()
                }
                statement.executeBatch()
            }
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
            val db = Db(connection)
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
