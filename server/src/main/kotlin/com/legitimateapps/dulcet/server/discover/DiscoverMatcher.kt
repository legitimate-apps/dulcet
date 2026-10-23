package com.legitimateapps.dulcet.server.discover

import com.legitimateapps.dulcet.server.auth.AccountAccess
import com.legitimateapps.dulcet.server.db.Db
import java.text.Normalizer
import java.util.Locale

/** Pure local matching. Edition metadata is supplied by the service, outside any DB transaction. */
class DiscoverMatcher(private val db:Db,private val access:AccountAccess,private val rejected:(String,DiscoverItem,LibraryRef)->Boolean={_,_,_->false}) {
    fun normalizeName(value:String)=Normalizer.normalize(value,Normalizer.Form.NFKC).lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}\\p{M}]+")," ").trim().replace(Regex("\\s+")," ")
    private fun same(a:String?,b:String?)= !a.isNullOrBlank() && !b.isNullOrBlank() && normalizeName(a)==normalizeName(b)
    private val versionWords=setOf("live","remix","demo","acoustic","instrumental")
    private fun sameVersion(a:String,b:String):Boolean {
        fun versions(title:String)=normalizeName(title).split(' ').filter { it in versionWords }.toSet()
        return versions(a)==versions(b)
    }
    private fun identifier(value:String?)=mbidOrNull(value)
    private data class Track(val id:String,val albumId:String,val title:String,val artist:String,val album:String,val albumArtist:String,val recording:String?,val release:String?,val group:String?,val disc:Int?,val position:Int?)
    fun match(userId:String,item:DiscoverItem,editions:List<Edition>?=null):Availability {
        val target=item.externalIds[item.kind.registryKey]?.let(::identifier) ?: return Availability()
        if(item.kind==DiscoverKind.Artist) {
            val rows=db.read { query("SELECT a.id,a.name,a.mbz_artist_id FROM artist a WHERE ${access.catalogPredicate(userId,"artist","a")}") { Triple(it.string("id"),it.string("name"),it.stringOrNull("mbz_artist_id")) } }
            val matches=rows.mapNotNull { (id,name,mbid) ->
                val by=if(!mbid.isNullOrBlank()) if(identifier(mbid)==target) "mbid" else null else if(same(name,item.title)) "name" else null
                if(by==null || rejected(userId,item,LibraryRef(id,"artist"))) null else LibraryRef(id,"artist") to by
            }
            if(matches.isEmpty())return Availability()
            return Availability("held",matches.map { it.first },if(matches.any { it.second=="mbid" })"mbid" else "name")
        }
        val tracks=db.read { query("SELECT * FROM track t WHERE ${access.catalogPredicate(userId,"track","t")}") { t ->
            Track(t.string("id"),t.string("album_id"),t.string("title"),t.string("artist"),t.string("album"),t.string("album_artist"),t.stringOrNull("mbz_recording_id"),t.stringOrNull("mbz_album_id"),t.stringOrNull("mbz_release_group_id"),t.intOrNull("disc_no"),t.intOrNull("track_no"))
        } }
        if(item.kind==DiscoverKind.Song) {
            val matches=tracks.mapNotNull { t ->
                val by=if(!t.recording.isNullOrBlank()) if(identifier(t.recording)==target && sameVersion(t.title,item.title)) "mbid" else null else if(same(t.title,item.title) && same(t.artist,item.artist)) "name" else null
                if(by==null || rejected(userId,item,LibraryRef(t.id,"track")))null else LibraryRef(t.id,"track") to by
            }
            if(matches.isEmpty())return Availability()
            // Name-only ambiguities cannot choose a recording automatically.
            if(matches.size>1 && matches.none { it.second=="mbid" })return Availability("partlyHeld",matches.map { it.first },"name")
            return Availability("held",matches.map { it.first },if(matches.any { it.second=="mbid" })"mbid" else "name")
        }
        val editionRows=editions.orEmpty().filter { it.releaseGroupMbid==target && it.tracks.isNotEmpty() }
        data class Candidate(val tracks:List<Track>,val by:String)
        val candidates=tracks.groupBy { it.albumId }.values.mapNotNull { local ->
            val ref=LibraryRef(local.first().albumId,"album")
            if(rejected(userId,item,ref))return@mapNotNull null
            val groups=local.mapNotNull { it.group?.takeIf(String::isNotBlank) }.toSet()
            val releases=local.mapNotNull { it.release?.takeIf(String::isNotBlank) }.toSet()
            val by=when {
                groups.isNotEmpty()->if(groups.all { identifier(it)==target })"mbid" else null
                releases.isNotEmpty()->if(releases.all { release -> editionRows.any { it.releaseMbid==identifier(release) } })"mbid" else null
                local.all { same(it.album,item.title) && same(it.albumArtist,item.artist) }->"name"
                else->null
            } ?: return@mapNotNull null
            Candidate(local,by)
        }
        if(candidates.isEmpty())return Availability()
        if(editionRows.isEmpty())return Availability("partlyHeld",candidates.map { LibraryRef(it.tracks.first().albumId,"album") },if(candidates.any { it.by=="mbid" })"mbid" else "name")
        data class Proof(val candidate:Candidate,val edition:Edition,val matched:Int) { val complete get()=matched==edition.tracks.size }
        val proofs=candidates.flatMap { candidate -> editionRows.map { edition -> Proof(candidate,edition,assign(candidate.tracks,edition.tracks)) } }
        val best=proofs.sortedWith(compareByDescending<Proof> { it.complete }.thenByDescending { it.matched.toDouble()/it.edition.tracks.size }.thenBy { it.edition.releaseMbid }.thenBy { it.candidate.tracks.first().albumId }).first()
        val ambiguous=best.candidate.by=="name" && proofs.filter { it.complete && it.candidate.by=="name" }.map { it.candidate.tracks.first().albumId }.distinct().size>1
        val complete=best.complete && !ambiguous
        val refs=if(complete) proofs.filter { it.complete }.map { LibraryRef(it.candidate.tracks.first().albumId,"album") }.distinct() else listOf(LibraryRef(best.candidate.tracks.first().albumId,"album"))
        return Availability(if(complete)"held" else "partlyHeld",refs,best.candidate.by,best.matched,best.edition.tracks.size,best.edition.releaseMbid,if(ambiguous)"unknown" else "known")
    }
    private fun assign(local:List<Track>,slots:List<EditionTrack>):Int {
        val available=local.toMutableList();val filled=HashSet<Int>()
        fun take(index:Int,mbid:Boolean) {
            val slot=slots[index]
            var choices=available.filter { t ->
                if(mbid) !slot.recordingMbid.isNullOrBlank() && identifier(t.recording)==slot.recordingMbid && sameVersion(t.title,slot.title)
                else t.recording.isNullOrBlank() && same(t.title,slot.title) && same(t.artist,slot.artist)
            }
            val duplicate=slots.count { other -> if(mbid)other.recordingMbid==slot.recordingMbid else same(other.title,slot.title) && same(other.artist,slot.artist) }>1
            if(duplicate || choices.size>1) choices=choices.filter { (it.disc ?: 1)==slot.disc && it.position==slot.position }
            if(choices.size==1) { available.remove(choices.single());filled+=index }
        }
        slots.indices.forEach { take(it,true) }
        slots.indices.filter { it !in filled }.forEach { take(it,false) }
        return filled.size
    }
}
