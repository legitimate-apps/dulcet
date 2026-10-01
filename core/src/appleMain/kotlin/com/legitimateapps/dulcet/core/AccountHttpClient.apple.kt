package com.legitimateapps.dulcet.core

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.ChallengeHandler
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.engine.darwin.KtorNSURLSessionDelegate
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UnsafeNumber
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSLock
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLAuthenticationMethodServerTrust
import platform.Foundation.NSURLComponents
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLErrorNetworkConnectionLost
import platform.Foundation.NSURLQueryItem
import platform.Foundation.NSURLRequest
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionAuthChallengePerformDefaultHandling
import platform.Foundation.NSURLSessionAuthChallengeCancelAuthenticationChallenge
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionDataDelegateProtocol
import platform.Foundation.NSURLSessionDataTask
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSData
import platform.Foundation.valueForHTTPHeaderField
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
internal actual fun createAccountHttpClient(
    transport: AccountClientTransport,
    configure: HttpClientConfig<*>.() -> Unit,
): HttpClient = HttpClient(Darwin) {
    engine {
        // A session of our own, so its delegate can sit in front of Ktor's: see
        // EstimatedLengthCompletingDelegate. Ktor still owns the session's lifetime and invalidates
        // it when the client closes; it ignores configureSession and handleChallenge for a
        // preconfigured session, so both are applied here instead.
        val configuration = NSURLSessionConfiguration.defaultSessionConfiguration().apply {
            transport.diagnostics?.write(
                "account.phase event darwin-session-defaults requestSeconds=$timeoutIntervalForRequest " +
                    "resourceSeconds=$timeoutIntervalForResource",
            )
            setHTTPCookieStorage(null)
            setURLCredentialStorage(null)
            if (transport is AccountClientTransport.ForwardProxy) {
                val proxy = transport.proxy
                setConnectionProxyDictionary(
                    mapOf(
                        "HTTPEnable" to 1,
                        "HTTPProxy" to proxy.host,
                        "HTTPPort" to proxy.port,
                        "HTTPSEnable" to 1,
                        "HTTPSProxy" to proxy.host,
                        "HTTPSPort" to proxy.port,
                    ),
                )
            }
        }
        val challengeHandler: ChallengeHandler = { _, _, challenge, completionHandler ->
            transport.diagnostics?.write("account.phase event darwin-challenge-enter")
            if (challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust) {
                completionHandler(
                    NSURLSessionAuthChallengePerformDefaultHandling,
                    challenge.proposedCredential,
                )
            } else {
                transport.challengeTracker.markUnsupported()
                completionHandler(NSURLSessionAuthChallengeCancelAuthenticationChallenge, null)
            }
            transport.diagnostics?.write("account.phase event darwin-challenge-completed")
        }
        val ktorDelegate = KtorNSURLSessionDelegate(challengeHandler)
        val session = NSURLSession.sessionWithConfiguration(
            configuration,
            EstimatedLengthCompletingDelegate(
                ktorDelegate,
                transport.estimatedBodyEndObserver,
                transport.bodyForwardedObserver,
            ),
            delegateQueue = null,
        )
        usePreconfiguredSession(session, ktorDelegate)
    }
    configure()
}

/**
 * Forwards every callback Ktor's delegate handles, and rewrites one completion.
 *
 * Navidrome's `estimateContentLength=true` Content-Length is a guess, and when the transcoder's
 * output outgrows it the server stops at the last write that fit and closes the connection, so the
 * body ends short of its declaration (spec §12.5, CONF-17). NSURLSession reports that as
 * NSURLErrorNetworkConnectionLost, after handing the delegate a prefix of what arrived -- all of
 * it, or only its first read when the rest came in with the end of the stream (both OBSERVED on
 * macOS, DarwinEstimatedLengthBodyTest). Ktor's Darwin engine answers any completion error by
 * cancelling the response body channel, which discards whatever the reader has not consumed yet,
 * so the reader then kept a race-dependent part of that prefix, and when it had consumed nothing
 * the load failed as Unreachable -- the likeliest reading of CONF-92's one Unreachable on the tvOS
 * simulator in apple-ci, which did not reproduce locally.
 *
 * For a request that asked for an estimate, carries no Range header, and was answered 2xx after at
 * least one body byte had been forwarded to Ktor, that particular completion is the end of the
 * representation, so Ktor is told the task succeeded and ends the body channel normally with every
 * byte it was handed. Every other completion, including the same error on a ranged or exact request,
 * on a non-2xx answer, or after nothing was forwarded, reaches Ktor unchanged.
 *
 * The byte clause counts what this delegate forwarded, not the task's `countOfBytesReceived`: the
 * session can have received bytes it never handed over, and an end with nothing forwarded would
 * become an empty success -- an unexpected payload to the playback validator instead of the
 * connection failure it is, which is what the transcode budget and the circuit breaker must see.
 * The count is kept per task under a lock and dropped at the task's completion. Nothing here throws
 * across the Objective-C callback: the observers' failures are dropped.
 *
 * What neither count can rescue: when the end of the stream arrives close behind body bytes the
 * session has read but not yet handed over, the session discards them with the -1005 -- the tail of
 * the body, or on a loaded host every byte of a short one, so the completion arrives with nothing
 * received and nothing forwarded and stays a failure (OBSERVED on macOS under CPU load; spec §12.5,
 * §28 2026-10-01, "The Darwin -1005 flake"). Those bytes never reach `countOfBytesReceived` either:
 * over 500 loopback completions the received and forwarded counts were equal every time, so the
 * forwarded count is chosen because an empty success is impossible by construction, not because a
 * sample has told the two apart.
 */
@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private class EstimatedLengthCompletingDelegate(
    private val ktor: KtorNSURLSessionDelegate,
    private val estimatedBodyEndObserver: ((forwardedBytes: Long) -> Unit)?,
    private val bodyForwardedObserver: ((forwardedSoFar: Long) -> Unit)?,
) : NSObject(), NSURLSessionDataDelegateProtocol {
    private val lock = NSLock()
    private val forwardedBytes = HashMap<ULong, Long>()

    override fun URLSession(session: NSURLSession, dataTask: NSURLSessionDataTask, didReceiveData: NSData) {
        ktor.URLSession(session, dataTask, didReceiveData)
        val task = dataTask.taskIdentifier.toULong()
        val length = didReceiveData.length.toLong()
        val forwarded = locked { ((forwardedBytes[task] ?: 0L) + length).also { forwardedBytes[task] = it } }
        try {
            bodyForwardedObserver?.invoke(forwarded)
        } catch (_: Throwable) {
            // An observer must never take the session down with it.
        }
    }

    override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
        val identifier = task.taskIdentifier.toULong()
        val forwarded = locked { forwardedBytes.remove(identifier) } ?: 0L
        val endsTheBody = didCompleteWithError != null && task.endedAnEstimatedBody(didCompleteWithError, forwarded)
        if (endsTheBody) {
            try {
                estimatedBodyEndObserver?.invoke(forwarded)
            } catch (_: Throwable) {
                // An observer must never take the session down with it.
            }
        }
        ktor.URLSession(session, task, if (endsTheBody) null else didCompleteWithError)
    }

    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        willPerformHTTPRedirection: NSHTTPURLResponse,
        newRequest: NSURLRequest,
        completionHandler: (NSURLRequest?) -> Unit,
    ) {
        ktor.URLSession(session, task, willPerformHTTPRedirection, newRequest, completionHandler)
    }

    override fun URLSession(
        session: NSURLSession,
        task: NSURLSessionTask,
        didReceiveChallenge: NSURLAuthenticationChallenge,
        completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
    ) {
        ktor.URLSession(session, task, didReceiveChallenge, completionHandler)
    }
}

@OptIn(ExperimentalForeignApi::class, UnsafeNumber::class)
private fun NSURLSessionTask.endedAnEstimatedBody(error: NSError, forwardedBytes: Long): Boolean {
    if (error.domain != NSURLErrorDomain || error.code != NSURLErrorNetworkConnectionLost) return false
    val request = originalRequest ?: return false
    val status = (response as? NSHTTPURLResponse)?.statusCode?.toInt() ?: return false
    return isEstimatedBodyEnd(
        statusCode = status,
        hasRangeHeader = request.valueForHTTPHeaderField("Range") != null,
        asksForEstimate = request.URL?.let { NSURLComponents(it, resolvingAgainstBaseURL = false) }
            ?.queryItems
            ?.any { (it as? NSURLQueryItem)?.let { item -> item.name == "estimateContentLength" && item.value == "true" } == true }
            == true,
        bodyBytesForwarded = forwardedBytes,
    )
}

/** The pure decision [EstimatedLengthCompletingDelegate] applies to a connection-lost completion. */
internal fun isEstimatedBodyEnd(
    statusCode: Int,
    hasRangeHeader: Boolean,
    asksForEstimate: Boolean,
    bodyBytesForwarded: Long,
): Boolean = asksForEstimate && !hasRangeHeader && statusCode in 200..299 && bodyBytesForwarded > 0

/**
 * Explicit forward-proxy connector used by the hosted Darwin wire conformance control.
 *
 * Production account setup continues to use the system URL-session proxy configuration. This seam
 * selects one deterministic loopback proxy without adding proxy credentials or changing challenge
 * handling, so the test exercises the same fail-closed delegate as production.
 */
public class DarwinForwardProxyAccountConnector(
    proxyHost: String,
    proxyPort: Int,
    saltSource: SaltSource? = null,
    logSink: LogSink? = null,
) {
    private val connector: AccountConnector

    init {
        require(proxyHost.isNotBlank() && proxyHost.none(Char::isWhitespace)) {
            "proxyHost must be a nonblank host without whitespace"
        }
        require(proxyPort in 1..65535) { "proxyPort must be in 1..65535" }
        connector = AccountConnector(
            forwardProxy = AccountForwardProxy(proxyHost, proxyPort),
            saltSource = saltSource,
            logSink = logSink,
        )
    }

    public suspend fun connect(request: AccountConnectionRequest): AccountConnectionResult =
        connector.connect(request)
}
