package com.legitimateapps.dulcet.server.discover

import org.slf4j.LoggerFactory
import java.net.URI

/** Aggregate process-local diagnostics. No URL, path, query, body or exception message is retained. */
class DiscoverDiagnostics(private val emit: (String) -> Unit = { LoggerFactory.getLogger("dulcet-discover-upstream").info(it) }) {
    data class Counts(
        val origin: String, val attempts: Long, val requests: Long, val statusClasses: List<Long>, val durationMillis: Long,
        val timeouts: Long, val backoffApplied: Long, val backoffRefused: Long, val waitingCap: Long,
        val parseFailures: Long, val staleServes: Long, val refreshFailures: Long, val refreshTimeouts: Long,
    )
    private class MutableCounts(val label: String) {
        var attempts=0L; var requests=0L; val statuses=LongArray(6); var duration=0L
        var timeouts=0L; var backoffApplied=0L; var backoffRefused=0L; var waitingCap=0L
        var parseFailures=0L; var staleServes=0L; var refreshFailures=0L; var refreshTimeouts=0L
        fun snapshot()=Counts(label,attempts,requests,statuses.toList(),duration,timeouts,backoffApplied,backoffRefused,waitingCap,parseFailures,staleServes,refreshFailures,refreshTimeouts)
    }
    private val origins=linkedMapOf<UpstreamOrigin,MutableCounts>()
    private val overflow=MutableCounts("other-origins")
    // Redirect hosts are not a cardinality-controlled input. Keep at most 64 separate origins.
    private fun counts(uri: URI): MutableCounts {
        val origin=UpstreamOrigin.of(uri)
        origins[origin]?.let { return it }
        if(origins.size>=64)return overflow
        val known=when(origin.host) {
            "musicbrainz.org" -> "musicbrainz"
            "coverartarchive.org" -> "cover-art-archive"
            "api.listenbrainz.org" -> "listenbrainz"
            "labs.api.listenbrainz.org" -> "listenbrainz-labs"
            else -> "origin-${origins.size+1}"
        }
        // Include an opaque suffix for non-default ports/schemes so distinct origins stay distinct.
        val label=if(known.startsWith("origin-") || origin.scheme=="https" && origin.port==443)known else "$known-${origins.size+1}"
        return MutableCounts(label).also { origins[origin]=it }
    }
    @Synchronized fun snapshot(): List<Counts> = origins.values.map { it.snapshot() }+listOfNotNull(overflow.takeIf { it.attempts+it.staleServes+it.parseFailures+it.refreshFailures>0 }?.snapshot())
    @Synchronized internal fun http(uri: URI, durationNanos: Long, status: Int?, outcome: UpstreamFailure?, appliedBackoff: Boolean, sent: Boolean) {
        val c=counts(uri); c.attempts++; if(sent)c.requests++; c.duration+=(durationNanos.coerceAtLeast(0)/1_000_000)
        status?.div(100)?.takeIf { it in 1..5 }?.let { c.statuses[it]++ }
        if(appliedBackoff)c.backoffApplied++
        when(outcome) {
            UpstreamFailure.Timeout -> c.timeouts++
            UpstreamFailure.Backoff -> c.backoffRefused++
            UpstreamFailure.WaitingCap -> c.waitingCap++
            else -> Unit
        }
        line(c,"http",outcome?.wire ?: "response",status?.let { "${it/100}xx" } ?: "none",durationNanos.coerceAtLeast(0)/1_000_000)
    }
    @Synchronized internal fun parseFailure(baseUrl: String) {
        val c=counts(URI(baseUrl)); c.parseFailures++; line(c,"decode","parse_failure","none",0)
    }
    @Synchronized internal fun refreshFailure(baseUrl: String, reason: UpstreamFailure, durationNanos: Long) {
        val c=counts(URI(baseUrl)); c.refreshFailures++
        if(reason==UpstreamFailure.Timeout || reason==UpstreamFailure.DnsTimeout)c.refreshTimeouts++
        line(c,"refresh_failure",reason.wire,"none",durationNanos.coerceAtLeast(0)/1_000_000)
    }
    @Synchronized internal fun stale(baseUrl: String, reason: UpstreamFailure) {
        val c=counts(URI(baseUrl)); c.staleServes++; line(c,"stale_serve",reason.wire,"none",0)
    }
    private fun line(c: MutableCounts,event: String,outcome: String,status: String,duration: Long) {
        emit("discover_upstream origin=${c.label} event=$event outcome=$outcome status=$status duration_ms=$duration attempts=${c.attempts} requests=${c.requests} status_1xx=${c.statuses[1]} status_2xx=${c.statuses[2]} status_3xx=${c.statuses[3]} status_4xx=${c.statuses[4]} status_5xx=${c.statuses[5]} duration_total_ms=${c.duration} timeouts=${c.timeouts} backoff_applied=${c.backoffApplied} backoff_refused=${c.backoffRefused} waiting_cap=${c.waitingCap} parse_failures=${c.parseFailures} stale_serves=${c.staleServes} refresh_failures=${c.refreshFailures} refresh_timeouts=${c.refreshTimeouts}")
    }
    companion object { val shared=DiscoverDiagnostics() }
}

enum class UpstreamFailure(val wire: String) {
    Cancelled("cancelled"), Unavailable("unavailable"), HttpStatus("http_status"), Timeout("timeout"), Transport("transport"),
    DnsTimeout("dns_timeout"), DnsTransport("dns_transport"), CacheWrite("cache_write"), Backoff("backoff"), WaitingCap("waiting_cap"), Body("invalid_body"), Parse("parse_failure"), Closed("closed"),
}
