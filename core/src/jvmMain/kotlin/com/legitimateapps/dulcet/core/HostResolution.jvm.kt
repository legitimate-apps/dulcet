package com.legitimateapps.dulcet.core

import java.net.InetAddress

internal actual suspend fun platformResolveHost(host: String): List<String> =
    InetAddress.getAllByName(host).map { it.hostAddress }.distinct()
