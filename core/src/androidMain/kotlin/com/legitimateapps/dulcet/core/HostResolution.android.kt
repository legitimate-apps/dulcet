package com.legitimateapps.dulcet.core

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext
import java.net.InetAddress

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
private val resolverDispatcher by lazy { newFixedThreadPoolContext(2, "host-resolution") }

internal actual fun hostResolutionDispatcher(): CoroutineDispatcher = resolverDispatcher

internal actual suspend fun platformResolveHost(host: String): List<String> =
    boundedHostResolution { InetAddress.getAllByName(host).map { checkNotNull(it.hostAddress) }.distinct() }
