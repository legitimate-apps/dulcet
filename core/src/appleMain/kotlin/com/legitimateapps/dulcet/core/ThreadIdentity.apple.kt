package com.legitimateapps.dulcet.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toLong
import platform.posix.pthread_self

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentThreadIdentity(): Long = pthread_self().toLong()
