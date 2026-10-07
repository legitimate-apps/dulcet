package com.legitimateapps.dulcet.core

/**
 * Whether [failure], thrown by the HTTP client in place of an answer, proves the request never left
 * this device because no connection was made: the host did not resolve, the connection was refused,
 * or the device had no network. Anything else that ends without an answer — a connection lost or
 * reset once made — may have delivered the request, and the server may have applied it (§18.3, §18.6).
 * Maps only typed platform failures; exception messages are never a classification input.
 */
internal expect fun provesNeverConnected(failure: Throwable): Boolean
