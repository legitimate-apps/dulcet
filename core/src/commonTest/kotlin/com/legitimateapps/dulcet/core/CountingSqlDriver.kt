package com.legitimateapps.dulcet.core

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement

/** Counts the SQL a test causes, so a cost can be asserted as a number of statements. */
internal class CountingSqlDriver(private val delegate: SqlDriver) : SqlDriver by delegate {
    val statements = mutableListOf<String>()

    /** When set, a write whose SQL matches throws, as a full disk or a corrupt page would. */
    var failWrite: ((String) -> Boolean)? = null

    /** When set, a read whose SQL matches throws, as a corrupt page or a locked database would. */
    var failRead: ((String) -> Boolean)? = null

    /** Reads [failRead] refused: a test asserts this to prove its injected failure fired. */
    var failedReads = 0
        private set

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> {
        statements += sql
        if (failRead?.invoke(sql) == true) {
            failedReads += 1
            throw IllegalStateException("injected read failure")
        }
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        statements += sql
        if (failWrite?.invoke(sql) == true) throw IllegalStateException("injected write failure")
        return delegate.execute(identifier, sql, parameters, binders)
    }

    fun countSince(mark: Int): Int = statements.size - mark
}
