package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlin.time.ComparableTimeMark
import kotlin.time.TimeSource
import kotlin.time.Duration.Companion.milliseconds

/** Shared per account, so changing query or surface cannot bypass server backoff. */
internal class DiscoverBackoff(private val wall: () -> Long, private val monotonic: TimeSource.WithComparableMarks = TimeSource.Monotonic) {
    private var until: ComparableTimeMark? = null
    private var failure: DiscoverFailure? = null
    fun note(error: DiscoverFailure) {
        if (error.code != "rate-limited") return
        val delay=(error.retryAtMillis?.let { (it - wall()).coerceAtLeast(0) } ?: 60000).milliseconds
        val deadline=monotonic.markNow()+delay
        if (until == null || deadline > until!!) {
            until=deadline
            failure=error.copy(retryAtMillis=error.retryAtMillis ?: (wall()+60000))
        }
    }
    fun allowed(): Boolean = until?.let { monotonic.markNow() >= it } ?: true
    fun error(): DiscoverFailure? = if (allowed()) null else failure
}

/** One screen's query lifetime. Account admission and durable state belong to its parent. */
internal class DiscoverSearch(
    private val scope: CoroutineScope,
    private val transport: DiscoverTransport,
    private val store: DiscoverStore,
    private val backoff: DiscoverBackoff,
    private val online: () -> Boolean,
    private val active: () -> Boolean = {true},
    private val publish: (String,List<DiscoverGroup>) -> Unit = {_,_ ->},
) {
    private var query=""
    private var generation=0L
    private var closed=false
    private var job: Job?=null
    private val more=mutableMapOf<DiscoverKind,Job>()
    private var groups=emptyList<DiscoverGroup>()

    fun setQuery(query: String) {
        if (closed || !active()) return
        val next=query.trim()
        if (next == this.query && groups.isNotEmpty() && groups.none {it.error != null}) return
        val changed=next != this.query
        this.query=next
        val ticket=++generation
        job?.cancel();more.values.forEach {it.cancel()};more.clear()
        if (changed || groups.isEmpty()) groups=DiscoverKind.entries.map { kind -> try { DiscoverGroup(kind.wire,cached(kind,next),false,null) }
            catch (_: Exception) { DiscoverGroup(kind.wire,null,false,DiscoverFailure("storage",null,true)) } }
        if (next.isEmpty()) { groups=emptyList();emit();return }
        if (discoverCodePoints(next) !in 1..500) {
            groups=DiscoverKind.entries.map { DiscoverGroup(it.wire,null,false,DiscoverFailure("invalid-response",null,false)) };emit();return
        }
        emit()
        job=scope.launch {
            delay(350)
            for (kind in DiscoverKind.entries) {
                if (!current(ticket)) return@launch
                read(kind,null,ticket)
            }
        }
    }
    fun loadMore(kind: String) {
        if (closed || !active()) return
        val type=DiscoverKind.entries.firstOrNull {it.wire==kind} ?: return
        val cursor=group(type).page?.nextCursor ?: return
        if (more[type]?.isActive == true || group(type).loading) return
        val ticket=generation
        more[type]=scope.launch {read(type,cursor,ticket)}
    }
    fun revalidate() {val text=query;query="";setQuery(text)}
    fun close() { closed=true;generation++;job?.cancel();more.values.forEach {it.cancel()};more.clear() }
    private fun current(ticket: Long)=!closed && active() && ticket==generation
    private fun emit() { if (!closed && active()) publish(query,groups.toList()) }
    private fun group(kind: DiscoverKind)=groups.first {it.kind==kind.wire}
    private fun update(kind: DiscoverKind, value: DiscoverGroup) { groups=groups.map {if(it.kind==kind.wire)value else it};emit() }
    private fun key(kind: DiscoverKind,query: String)="search:${kind.wire}:$query"
    private fun cached(kind: DiscoverKind,query: String): DiscoverPage? = if(query.isEmpty()) null else
        store.read(key(kind,query))?.let { runCatching {Json.decodeFromString<DiscoverPage>(it.decodeToString()).asDeviceCached()}.getOrNull() }
    private suspend fun read(kind: DiscoverKind,cursor: String?,ticket: Long) {
        try { readChecked(kind,cursor,ticket) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            if(current(ticket)) update(kind,group(kind).copy(loading=false,error=DiscoverFailure("storage",null,true)))
        }
    }
    private suspend fun readChecked(kind: DiscoverKind,cursor: String?,ticket: Long) {
        if (!current(ticket)) return
        val error=if(!online()) DiscoverFailure("offline",null,true) else backoff.error()
        if(error!=null) { update(kind,group(kind).copy(loading=false,error=error));return }
        val issued=store.nextIssue()
        val requestedQuery=query
        update(kind,group(kind).copy(loading=true,error=null))
        if (!current(ticket)) return
        var append=cursor!=null
        var result=transport.search(kind,requestedQuery,cursor)
        if (result is DiscoverResult.Failed) backoff.note(result.error)
        if (!current(ticket)) return
        if (result is DiscoverResult.Failed && result.error.code=="cursor-expired" && cursor!=null) {
            append=false
            result=transport.search(kind,requestedQuery,null)
            if(result is DiscoverResult.Failed) backoff.note(result.error)
        }
        if (!current(ticket)) return
        when(result) {
            is DiscoverResult.Value -> {
                val page=result.value
                val previous=group(kind).page
                val merged=if(append && previous!=null) page.copy(items=(previous.items+page.items).distinctBy {it.id}) else page
                store.putRead(key(kind,requestedQuery),Json.encodeToString(merged).encodeToByteArray(),false,issued)
                update(kind,DiscoverGroup(kind.wire,merged,false,null))
            }
            is DiscoverResult.Failed -> update(kind,group(kind).copy(loading=false,error=result.error))
        }
    }
}
internal fun DiscoverPage.asDeviceCached(): DiscoverPage = copy(age=age.copy(fromDeviceCache=true))
internal fun discoverCodePoints(value: String): Int {
    var count=0;var index=0
    while(index<value.length) {
        val char=value[index++]
        if(char.isHighSurrogate() && index<value.length && value[index].isLowSurrogate()) index++
        count++
    }
    return count
}
