package com.legitimateapps.dulcet.search.conformance

import com.legitimateapps.dulcet.core.*
import com.legitimateapps.dulcet.search.AndroidSearchCache
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.test.*
import kotlin.time.Duration.Companion.seconds

@RunWith(RobolectricTestRunner::class)
class AndroidSearchCacheTest {
    @Test fun persistedPagesPreserveMetadataRankingAndAccountIsolation() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val row = SearchResultItem(ProviderItemId("account-a", "track:opaque/not-a-number"),
            SearchResultType.Track, "Echo", listOf(Credit(CreditRole.Artist, "Artist",
                ProviderItemId("account-a", "artist:opaque"))), "Album", 2026, 123.seconds,
            2, 3, AudioContainer.Mp3, "media:opaque", "art:opaque")
        val rows = listOf(row, row.copy(id = ProviderItemId("account-a", "second")))
        AndroidSearchCache(context, "account-a").store(" Echo ", rows)
        assertEquals(rows, AndroidSearchCache(context, "account-a").search("echo"))
        assertEquals(emptyList(), AndroidSearchCache(context, "account-b").search("echo"))
        assertEquals(emptyList(), AndroidSearchCache(context, "account-a").search("different"))
        assertFailsWith<IllegalArgumentException> { AndroidSearchCache(context, "account-b").store("echo", rows) }
        context.getSharedPreferences("dulcet.search.account-a", 0).edit().clear().commit()
        Unit
    }
}
