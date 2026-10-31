package com.legitimateapps.dulcet.core

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Content-free observation seam for native no-loss proofs. No observer is installed by apps. */
@OptIn(ExperimentalAtomicApi::class)
internal object OwnershipBindingProbe {
    private val observer = AtomicReference<((String) -> Unit)?>(null)
    fun observe(callback: ((String) -> Unit)?) { observer.store(callback) }
    fun record(event: String) { observer.load()?.invoke(event) }
}
