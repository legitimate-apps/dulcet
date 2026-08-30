package com.legitimateapps.dulcet.conformance

import com.legitimateapps.dulcet.core.ProviderItemId
import com.legitimateapps.dulcet.core.SearchPageRequest
import com.legitimateapps.dulcet.core.SearchPageResult
import com.legitimateapps.dulcet.core.SearchResultItem
import com.legitimateapps.dulcet.core.SearchResultType
import com.legitimateapps.dulcet.core.ServerSearch
import com.legitimateapps.dulcet.core.mergeSearchResults
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration.Companion.seconds

class SearchConformanceTest {
    @Test
    fun conf41LocalAndServerSearchMergeReplacesWithoutDuplicatingOrDropping() =
        runTest(timeout = 30.seconds) {
            val serverResults = when (val result = ServerSearch().search(request())) {
                is SearchPageResult.Loaded -> result.page.results
                is SearchPageResult.Failed -> fail(
                    "CONF-41 requires working search3 on the disposable reference server; " +
                        "production search failed with ${result.error}",
                )
            }
            val overlap = serverResults.firstOrNull { result ->
                result.type == SearchResultType.Track && result.title == HEALTH_PROBE_TITLE
            } ?: fail(
                "CONF-41 disposable search3 omitted the required $HEALTH_PROBE_TITLE track; " +
                    "observed=${serverResults.safeObservation()}",
            )
            val serverOnly = serverResults.firstOrNull { it.id != overlap.id }
                ?: fail(
                    "CONF-41 requires a distinct server-only result from disposable search3; " +
                        "observed=${serverResults.safeObservation()}",
                )
            val staleLocal = overlap.copy(title = STALE_LOCAL_TITLE, artworkKey = null)
            val localOnly = overlap.copy(
                id = ProviderItemId(CONFORMANCE_PROVIDER_ID, LOCAL_ONLY_OPAQUE_ID),
                title = LOCAL_ONLY_TITLE,
            )

            val merged = mergeSearchResults(
                localResults = listOf(staleLocal, localOnly),
                serverResults = listOf(overlap, serverOnly),
            )

            assertEquals(
                listOf(overlap.id, localOnly.id, serverOnly.id),
                merged.map(SearchResultItem::id),
                "CONF-41 must replace in place, retain local-only rows, and append server-only rows",
            )
            assertEquals(overlap, merged[0], "CONF-41 server data did not refresh the cached row")
            assertEquals(localOnly, merged[1], "CONF-41 dropped the local-only row")
            assertEquals(serverOnly, merged[2], "CONF-41 dropped the server-only row")
            assertEquals(
                merged.size,
                merged.map(SearchResultItem::id).toSet().size,
                "CONF-41 emitted a duplicate identity",
            )
            assertTrue(
                merged.none { it.title == STALE_LOCAL_TITLE },
                "CONF-41 retained stale local data instead of the matching server object",
            )
            println(
                "CONF-41 OBSERVED search3_results=${serverResults.size} " +
                    "overlap_id_length=${overlap.id.rawId.length} " +
                    "server_only_id_length=${serverOnly.id.rawId.length} " +
                    "opaque_local_id_preserved=${merged[1].id.rawId == LOCAL_ONLY_OPAQUE_ID} " +
                    "merged_ids_unique=true replacement_index=0 local_only_index=1 " +
                    "server_only_index=2",
            )
        }

    private fun request() = SearchPageRequest(
        providerInstanceId = CONFORMANCE_PROVIDER_ID,
        normalizedBaseUrl = disposableConformanceBaseUrl(),
        username = environmentOrNull("DULCET_CONFORMANCE_USERNAME") ?: ADMIN_USER,
        password = environmentOrNull("DULCET_CONFORMANCE_PASSWORD") ?: ADMIN_PASSWORD,
        allowLocalHttp = true,
        query = SEARCH_QUERY,
        artistCount = RESULT_LIMIT,
        artistOffset = 0,
        albumCount = RESULT_LIMIT,
        albumOffset = 0,
        trackCount = RESULT_LIMIT,
        trackOffset = 0,
    )

    private fun List<SearchResultItem>.safeObservation(): String =
        joinToString(prefix = "[", postfix = "]", limit = 10) { result ->
            "${result.type}:${result.title}"
        }

    private companion object {
        const val CONFORMANCE_PROVIDER_ID = "provider:disposable-conformance"
        const val ADMIN_USER = "dulcet-admin"
        const val ADMIN_PASSWORD = "dulcet-ci-canary-password"
        const val SEARCH_QUERY = "Dulcet"
        const val HEALTH_PROBE_TITLE = "Dulcet Health Probe"
        const val RESULT_LIMIT = 20
        const val STALE_LOCAL_TITLE = "Stale local CONF-41 title"
        const val LOCAL_ONLY_TITLE = "Local-only CONF-41 title"
        const val LOCAL_ONLY_OPAQUE_ID = "local:opaque/CONF-41:not-an-integer"
    }
}
