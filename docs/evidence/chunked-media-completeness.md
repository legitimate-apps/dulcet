# Chunked media completeness

A chunked HTTP/1.1 response completes only after its zero-size chunk and trailer terminator.
Socket EOF between two chunks is truncation, just as EOF inside a chunk is. Media bytes can be
played progressively, but the terminal error must reach the player and a partial file must never
be promoted to the download or stream cache.

## Inventory and socket measurements

The baseline is commit `134b9300`. Fixtures bind to loopback, send a valid audio prefix, and
close with FIN after either a complete nonzero chunk or part of its declared payload. The
complete control also sends `0\r\n\r\n` and checks the exact returned or stored bytes.

| Path | Production source | Baseline: boundary cut | Mid-chunk cut | Complete control | Regression test |
| --- | --- | --- | --- | --- | --- |
| JVM core media load | `AuthenticatedEndpoint.kt`, `AccountHttpClient.jvm.kt` | Accepted | Rejected | Exact bytes | `PlaybackColdTranscodeTruncationTest` |
| Android core media load | `AuthenticatedEndpoint.kt`, `AccountHttpClient.android.kt` | Accepted | Rejected | Exact bytes | `AndroidChunkedMediaTest` core cases |
| Darwin core media load | `AuthenticatedEndpoint.kt`, `AccountHttpClient.apple.kt` | Accepted | Rejected | Exact bytes | `DarwinChunkedBodyTest`, `StrictMediaFramingTest` |
| Apple streaming resource and stream cache | `DulcetPlaybackResourceLoading.swift`, `DulcetOfflineLibrary.swift` | Accepted; actual cache promotion reproduced by loader mutant | Rejected | Exact bytes | `PlaybackResourceLoadingTests` chunked cases and controller cache case |
| Apple download delegate and promotion | `DulcetCoreDownloads.swift` | Accepted by URLSession | Rejected | Exact bytes | `DulcetAppleDownloadIntegrationTest` chunked case |
| Android player DataSource | `AndroidPlaybackDataSource.kt` | Rejected | Rejected | Exact bytes | `AndroidChunkedMediaTest` player cases |
| Android stream cache | `AndroidStreamingCache.kt` | Rejected, no promotion | Rejected, no promotion | Exact stored bytes | `AndroidChunkedMediaTest` cache cases |
| Android explicit download | `AndroidDownloads.kt` | Rejected, no promotion | Rejected, no promotion | Exact stored bytes | `AndroidChunkedMediaTest` download cases |

Core tests cover `/rest/stream`, `/rest/download` and the shared framing transport used by
`getTranscodeStream`. Explicit original-file downloads use `stream?format=raw`. Android
measurements run under Robolectric with the host JDK's `HttpURLConnection`; they do not claim
physical Android network-stack or decoder evidence. Darwin baseline measurements run on macOS.
Apple shells share the resource and download implementations. An equivalent-baseline controller
mutant that bypasses the new replacement guard promoted the boundary-cut file and exposed an
offline asset; complete and mid-chunk controls passed. A separate loader mutant that disables strict
framing also promoted the boundary-cut response through the actual durable stream-cache writer.
Restoring the guards rejects both deliveries.

No Dulcet API media resolver or shipping media source exists at this baseline. The Swift resource
fixtures also exercise API-shaped media URLs with authorization headers, so the transport does
not depend on a `/rest` pathname. That is transport evidence, not a claim that a Dulcet API player
has shipped.

## Where completeness is enforced

Core media GETs use a separate client for `stream`, `download` and `getTranscodeStream`, retaining
that client across redirects. JVM and Android use OkHttp's strict HTTP framing. Nonmedia account
traffic retains CIO and its existing status, authentication and mutation retry behavior. An
immediate `503` retry header cannot cause a state-changing GET or form POST to be replayed by the
new media engine.

The Darwin core media session uses a raw Foundation socket-stream URL protocol, retaining system
TLS certificate and hostname validation. The Swift resource session uses a Network framework URL
protocol. Both request HTTP/1.1, decode framing incrementally, and require the zero chunk plus
complete trailers. Neither rewrites a lost connection into success. Errors contain static codes,
not request URLs, query credentials or authorization headers. Same-origin redirects retain the
existing policy; credential handling remains in the account and resource loaders.

Background URLSession downloads cannot supply this framing proof. A successful OS delivery with
chunked or undeclared length is discarded and replaced by a strict foreground transfer after
current server identity and ownership are authorized. Only the replacement response and its bytes
may be secured and promoted. Its receipt persists a framing proof for recovery. Previously
secured unknown-length receipts without that proof are interrupted for retry. Identity rejection,
account rebind and disconnect cancel replacement sessions.

The raw Apple transports fail closed when an explicit or system forward proxy is configured,
rather than silently bypassing it. Reverse proxies serving the requested origin do not require
special handling. Supporting raw media through a system forward proxy remains a compatibility
limitation. Unknown-length background deliveries require a second transfer and foreground
execution for that replacement. Previously completed library files are not audited or repaired.

## Estimated-body removal

No production caller selected the estimated-body Ktor reader or requested `estimateContentLength`.
The selector, permissive reader, platform completion predicates, Darwin completion rewrite and
its observer-only test seams were removed together. Every declared response length remains exact.
The separate `PlaybackContentLength.Estimated` domain type and its existing policy tests remain;
this change does not reinterpret that type or make a truncated body complete.
