package com.legitimateapps.dulcet.core

/**
 * What a platform search facade remembers so that a failure it states itself answers the text in
 * the field (§16.15). Kept on the reader's thread.
 *
 * The query last typed is kept whether or not the facade has a search session, so a keystroke made
 * while setup is failing is neither lost nor answered under an older query. A failure the facade
 * states (the reader's own failure, or a closed reader) names that query, and keeps the rows already
 * shown only when they answer it: an error never replaces content, and never draws one query's rows
 * under another's text.
 */
internal class SearchFacadeMemory<R> {
    /** The query last typed, or null when none has been. */
    var typed: String? = null
        private set

    private var shownQuery: String? = null
    private var shownRows: List<R> = emptyList()

    fun type(text: String) {
        typed = text
    }

    /** Records what was last published. */
    fun shown(query: String, rows: List<R>) {
        shownQuery = query
        shownRows = rows
    }

    /** The query a failure publication names: the one typed, else the one last shown, else empty. */
    val failureQuery: String get() = typed ?: shownQuery ?: ""

    /** The rows a failure publication keeps: those shown, only when they answer [failureQuery]. */
    val failureRows: List<R> get() = if (shownQuery == failureQuery) shownRows else emptyList()
}
