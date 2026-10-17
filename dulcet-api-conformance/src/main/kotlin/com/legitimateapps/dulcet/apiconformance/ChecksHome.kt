package com.legitimateapps.dulcet.apiconformance

/** Optional Home profile: a server claiming it must advertise and implement version 1. */
fun homeChecks(): List<Check> = listOf(
    Check("DA-HOME-1", Profile.Home, "advertised bounded per-user shelves contain visible, unique catalog resources", "README Listen Now; openapi getHomeRecommendations") {
        requireFixture()
        val caps = body(api("GET","getCapabilities","/capabilities"))
        ensure(caps.path("capabilities").path("dulcet.home").path("versions").any { it.asInt()==1 }) { "Home profile requires dulcet.home v1" }
        val ex = api("GET","getHomeRecommendations","/recommendations/home",query=listOf("limit" to "2"))
        expectStatus(ex,200)
        val shelves = body(ex).path("shelves")
        ensure(shelves.isArray && shelves.size() <= 50) { "home must return a bounded shelves array" }
        ensure(shelves.ids().toSet().size == shelves.size()) { "duplicate shelf ids" }
        var count = 0
        for(shelf in shelves) {
            ensure(!shelf.str("id").isNullOrBlank() && !shelf.str("title").isNullOrBlank() && !shelf.str("reason").isNullOrBlank()) { "shelf lacks identity, heading or reason" }
            val items = shelf.path("items")
            ensure(items.isArray && items.size()<=2 && items.ids().toSet().size==items.size()) { "shelf exceeds limit or contains duplicates" }
            for(item in items) {
                val type=item.str("type")
                ensure(type in setOf("album","track","playlist")) { "unknown home item type $type" }
                val path=when(type) { "album" -> "/albums/"; "playlist" -> "/playlists/"; else -> "/tracks/" }
                val operation=when(type) { "album" -> "getAlbum"; "playlist" -> "getPlaylist"; else -> "getTrack" }
                expectStatus(api("GET",operation,path+id(item.str("id") ?: fail("home item has no id"))),200)
                count++
            }
        }
        ensure(count>0) { "home returned no items for the seeded catalog" }
        expectProblem(api("GET","getHomeRecommendations","/recommendations/home", token=null),401,"unauthenticated")
        expectProblem(api("GET","getHomeRecommendations","/recommendations/home",query=listOf("limit" to "51")),400,"invalid-request")
    },
)
