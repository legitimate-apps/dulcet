package com.legitimateapps.dulcet.core

/**
 * Whether a platform transport reported its terminal declared-length mismatch only after the
 * response block had already consumed and assembled the complete estimated body.
 */
internal expect fun isPlatformEstimatedLengthCompletion(failure: Throwable): Boolean
