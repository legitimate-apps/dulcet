package com.legitimateapps.dulcet.core

@Suppress("DEPRECATION") // Thread.getId: threadId() needs Java 19; the id is only compared.
internal actual fun currentThreadIdentity(): Long = Thread.currentThread().id
