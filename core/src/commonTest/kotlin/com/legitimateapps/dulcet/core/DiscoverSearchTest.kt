@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
package com.legitimateapps.dulcet.core

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

class DiscoverSearchTest {
    @Test fun cacheFailureIsTypedAndNeverBecomesEmptySuccess() = runTest {
        val driver=createTestDriver()
        val store=DiscoverStore(DulcetDatabaseStore.open(driver).database,"A")
        val wire=DiscoverTestTransport();var groups=emptyList<DiscoverGroup>()
        val search=DiscoverSearch(this,wire,store,DiscoverBackoff({1000},testScheduler.timeSource),{true},publish={_,g ->groups=g})
        driver.close()
        search.setQuery("q");advanceUntilIdle()
        assertTrue(groups.isNotEmpty())
        assertTrue(groups.all {it.error?.code=="storage"})
        assertTrue(wire.searches.isEmpty())
        search.close()
    }

    @Test fun debounces350msAndPublishesArtistsBeforeAlbumsBeforeSongs() = discoverStoreTest { env ->
        val wire=DiscoverTestTransport()
        val release=DiscoverKind.entries.associateWith { CompletableDeferred<Unit>() }
        wire.onSearch={kind,q,_ -> release.getValue(kind).await(); DiscoverResult.Value(sampleDiscoverPage(q))}
        val publications=mutableListOf<List<DiscoverGroup>>()
        val search=DiscoverSearch(this,wire,env.store(),DiscoverBackoff({env.now},testScheduler.timeSource),{true},publish={_,groups -> publications += groups})
        search.setQuery("  A & B  ")
        advanceTimeBy(349); assertTrue(wire.searches.isEmpty())
        advanceTimeBy(1); runCurrent()
        assertEquals(listOf(DiscoverKind.Artist),wire.searches.map {it.first})
        release.getValue(DiscoverKind.Artist).complete(Unit);runCurrent()
        assertEquals("A & B",publications.last()[0].page?.items?.single()?.id)
        assertEquals(listOf(DiscoverKind.Artist,DiscoverKind.Album),wire.searches.map {it.first})
        release.getValue(DiscoverKind.Album).complete(Unit);runCurrent()
        assertNotNull(publications.last()[1].page)
        release.getValue(DiscoverKind.Song).complete(Unit);advanceUntilIdle()
        assertEquals(DiscoverKind.entries,wire.searches.map {it.first})
        search.close()
    }
    @Test fun lateOldQueryCannotReplaceNewResults() = discoverStoreTest { env ->
        val release=CompletableDeferred<Unit>(); val wire=DiscoverTestTransport()
        wire.onSearch={_,q,_ -> if(q=="old") withContext(NonCancellable){release.await()};DiscoverResult.Value(sampleDiscoverPage(q))}
        var query="";var groups=emptyList<DiscoverGroup>()
        val search=DiscoverSearch(this,wire,env.store(),DiscoverBackoff({env.now},testScheduler.timeSource),{true},publish={q,g ->query=q;groups=g})
        search.setQuery("old");advanceTimeBy(350);runCurrent()
        search.setQuery("new");advanceTimeBy(350);runCurrent()
        release.complete(Unit);advanceUntilIdle()
        assertEquals("new",query)
        assertTrue(groups.all {it.page?.items?.single()?.id == "new"})
        search.close()
    }
    @Test fun rateLimitKeepsLastGoodAndHonorsDeadline() = discoverStoreTest { env ->
        val wire=DiscoverTestTransport().apply {onSearch={_,q,c -> if(c!=null) DiscoverResult.Failed(DiscoverFailure("rate-limited",env.now+120000,true)) else DiscoverResult.Value(sampleDiscoverPage(q,"more"))}}
        val backoff=DiscoverBackoff({env.now},testScheduler.timeSource)
        var groups=emptyList<DiscoverGroup>()
        val search=DiscoverSearch(this,wire,env.store(),backoff,{true},publish={_,g ->groups=g})
        search.setQuery("q");advanceUntilIdle();search.loadMore("artist");runCurrent()
        assertEquals("q",groups[0].page?.items?.single()?.id)
        assertEquals("rate-limited",groups[0].error?.code)
        val sends=wire.searches.size;env.now=-100000
        search.setQuery("next");advanceTimeBy(350);runCurrent()
        assertEquals(sends,wire.searches.size)
        assertFalse(backoff.allowed())
        advanceTimeBy(120000);runCurrent();assertTrue(backoff.allowed())
        search.setQuery("next");advanceUntilIdle();assertTrue(wire.searches.size>sends)
        search.close()
    }
    @Test fun staleAndOfflineNeverMasqueradeAsFreshOrEmpty() = discoverStoreTest { env ->
        val wire=DiscoverTestTransport().apply {onSearch={_,q,_ ->DiscoverResult.Value(sampleDiscoverPage(q,stale=true))}}
        var online=true;var groups=emptyList<DiscoverGroup>()
        fun client()=DiscoverSearch(this,wire,env.store(),DiscoverBackoff({env.now},testScheduler.timeSource),{online},publish={_,g ->groups=g})
        val first=client();first.setQuery("q");advanceUntilIdle();first.close()
        online=false;val count=wire.searches.size;env.now=0
        val second=client();second.setQuery("q");advanceUntilIdle()
        assertEquals(count,wire.searches.size)
        assertTrue(groups.all {it.page?.age?.fromDeviceCache == true && it.page?.age?.stale == true})
        assertEquals(120L,groups[0].page?.age?.sourceAgeSeconds)
        assertEquals("offline",groups[0].error?.code)
        second.close()
    }
    @Test fun oneGroupFailureDoesNotEraseOtherGroups() = discoverStoreTest { env ->
        val wire=DiscoverTestTransport().apply {onSearch={kind,q,_ ->if(kind==DiscoverKind.Album) DiscoverResult.Failed(DiscoverFailure("upstream-unavailable",null,true)) else DiscoverResult.Value(sampleDiscoverPage(q))}}
        var groups=emptyList<DiscoverGroup>()
        val search=DiscoverSearch(this,wire,env.store(),DiscoverBackoff({env.now},testScheduler.timeSource),{true},publish={_,g ->groups=g})
        search.setQuery("q");advanceUntilIdle()
        assertNotNull(groups[0].page);assertNull(groups[1].page);assertNotNull(groups[2].page)
        assertEquals("upstream-unavailable",groups[1].error?.code);search.close()
    }
    @Test fun expiredCursorReplacesOnlyThatGroup() = discoverStoreTest { env ->
        val wire=DiscoverTestTransport()
        var first=0
        wire.onSearch={kind,q,c -> if(c!=null) DiscoverResult.Failed(DiscoverFailure("cursor-expired",null,true)) else DiscoverResult.Value(sampleDiscoverPage(if(kind==DiscoverKind.Artist) "artist-${++first}" else q,"more"))}
        var groups=emptyList<DiscoverGroup>()
        val search=DiscoverSearch(this,wire,env.store(),DiscoverBackoff({env.now},testScheduler.timeSource),{true},publish={_,g ->groups=g})
        search.setQuery("q");advanceUntilIdle();search.loadMore("artist");advanceUntilIdle()
        assertEquals(listOf("artist-2"),groups[0].page?.items?.map {it.id})
        assertEquals("q",groups[1].page?.items?.single()?.id);search.close()
    }
    @Test fun emptyIdenticalUnicodeBoundariesAndCloseDuringDebounce() = discoverStoreTest { env ->
        val wire=DiscoverTestTransport()
        val search=DiscoverSearch(this,wire,env.store(),DiscoverBackoff({env.now},testScheduler.timeSource),{true})
        search.setQuery(" ");advanceUntilIdle();assertTrue(wire.searches.isEmpty())
        val query="😀".repeat(500)
        search.setQuery(query);advanceUntilIdle();assertEquals(3,wire.searches.size)
        search.setQuery(query);advanceUntilIdle();assertEquals(3,wire.searches.size)
        search.setQuery(query+"😀");advanceUntilIdle();assertEquals(3,wire.searches.size)
        search.setQuery("later");search.close();advanceUntilIdle();assertEquals(3,wire.searches.size)
    }
}
