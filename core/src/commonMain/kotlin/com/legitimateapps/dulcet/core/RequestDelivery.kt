package com.legitimateapps.dulcet.core

/**
 * Whether [failure], thrown by the HTTP client in place of an answer, proves the request never left
 * this device because no connection was made: the host did not resolve, or the connection was
 * refused or had no route. Anything else that ends without an answer — a connection lost or reset
 * once made — may have delivered the request, and the server may have applied it (§18.3, §18.6).
 * Maps only typed platform failures; exception messages are never a classification input.
 *
 * It judges the request's last hop. A redirect answered by an earlier hop and then followed to a host
 * that refuses reads as never connected; that holds because a redirect is an answer in place of the
 * request, and no Subsonic server applies a write and then redirects it.
 */
internal expect fun provesNeverConnected(failure: Throwable): Boolean
