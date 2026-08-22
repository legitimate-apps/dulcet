package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/** Synchronous cancellation handle exported to Apple platform shells. */
public interface AppleAccountConnectOperation {
    /** Best-effort, idempotent cancellation of the in-flight account negotiation. */
    public fun cancel()
}

/** Flat completion payload; exactly one of [account] and [error] is non-null. */
public class AppleAccountConnectOutcome internal constructor(
    public val account: ConnectedAccount?,
    public val error: DomainError?,
)

/**
 * Objective-C-interoperable account-connect facade.
 *
 * It returns the operation handle synchronously, delivers exactly one completion on the Apple main
 * dispatcher, and never lets a Kotlin exception cross the framework boundary.
 */
public class AppleAccountConnectionClient(
    private val connector: AccountConnector = AccountConnector(),
) {
    private val scope: CoroutineScope = MainScope()

    public fun startConnect(
        request: AccountConnectionRequest,
        completion: (AppleAccountConnectOutcome) -> Unit,
    ): AppleAccountConnectOperation {
        val operation = AppleAccountConnectOperationImpl(
            scope = scope,
            connector = connector,
            request = request,
            completion = completion,
        )
        operation.start()
        return operation
    }
}

private class AppleAccountConnectOperationImpl(
    private val scope: CoroutineScope,
    private val connector: AccountConnector,
    private val request: AccountConnectionRequest,
    private val completion: (AppleAccountConnectOutcome) -> Unit,
) : AppleAccountConnectOperation {
    private var delivered = false
    private val job: Job = scope.launch(start = CoroutineStart.LAZY) {
        val result = try {
            connector.connect(request)
        } catch (_: CancellationException) {
            AccountConnectionResult.Failed(DomainError.Transport.Cancelled)
        } catch (failure: Throwable) {
            AccountConnectionResult.Failed(mapAccountConnectionFailure(failure))
        }
        deliver(result.toAppleOutcome())
    }.also { operationJob ->
        operationJob.invokeOnCompletion { failure ->
            if (failure is CancellationException) {
                scope.launch {
                    deliver(
                        AccountConnectionResult.Failed(DomainError.Transport.Cancelled)
                            .toAppleOutcome(),
                    )
                }
            }
        }
    }

    fun start() {
        // Schedule rather than entering the lazy coroutine inline: the caller receives this handle
        // before even an input-validation completion can be delivered.
        scope.launch { job.start() }
    }

    override fun cancel() {
        job.cancel()
    }

    private fun deliver(outcome: AppleAccountConnectOutcome) {
        if (delivered) return
        delivered = true
        completion(outcome)
    }
}

private fun AccountConnectionResult.toAppleOutcome(): AppleAccountConnectOutcome = when (this) {
    is AccountConnectionResult.Connected -> AppleAccountConnectOutcome(account = account, error = null)
    is AccountConnectionResult.Failed -> AppleAccountConnectOutcome(account = null, error = error)
}
