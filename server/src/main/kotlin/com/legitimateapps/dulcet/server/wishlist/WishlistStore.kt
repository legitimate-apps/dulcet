package com.legitimateapps.dulcet.server.wishlist

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.v1.*
import kotlinx.serialization.json.*

class WishlistStore(val db:Db,private val journal:Journal) {
    init { db.write {
        execute("""CREATE TABLE IF NOT EXISTS wishlist_wish(id TEXT PRIMARY KEY,user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,kind TEXT NOT NULL,canonical_mbid TEXT NOT NULL,external_ids TEXT NOT NULL,title TEXT NOT NULL,artist TEXT,year INTEGER,artwork_id TEXT,source_type TEXT NOT NULL,source_id TEXT,added INTEGER NOT NULL,state TEXT NOT NULL,updated INTEGER NOT NULL,note TEXT,note_at INTEGER,fulfilled INTEGER,library_id TEXT,library_type TEXT,availability TEXT NOT NULL DEFAULT '{}',UNIQUE(user_id,kind,canonical_mbid))""")
        execute("""CREATE TABLE IF NOT EXISTS wishlist_operation(user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,operation_id TEXT NOT NULL,type TEXT NOT NULL,fingerprint TEXT NOT NULL,wish_id TEXT,kind TEXT,mbid TEXT,action_time INTEGER NOT NULL,add_operation_id TEXT,result_removed INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(user_id,operation_id))""")
        execute("CREATE INDEX IF NOT EXISTS wishlist_operation_cancel ON wishlist_operation(user_id,add_operation_id)")
        execute("""CREATE TABLE IF NOT EXISTS wishlist_target_clock(user_id TEXT NOT NULL REFERENCES user(id) ON DELETE CASCADE,kind TEXT NOT NULL,mbid TEXT NOT NULL,action_time INTEGER NOT NULL,removed INTEGER NOT NULL,PRIMARY KEY(user_id,kind,mbid))""")
        for((action,ref,op) in listOf(Triple("INSERT","NEW","upsert"),Triple("UPDATE","NEW","upsert"),Triple("DELETE","OLD","delete"))) {
            execute("CREATE TRIGGER IF NOT EXISTS wishlist_journal_${action.lowercase()} AFTER $action ON wishlist_wish BEGIN INSERT INTO api_journal(type,id,user_id,op,at,reason) VALUES('wish',$ref.id,$ref.user_id,'$op',CAST((julianday('now')-2440587.5)*86400000 AS INTEGER),${if(op=="delete") "'deleted'" else "NULL"}); END")
        }
    } }
    internal fun read(s:Db.Session,user:String,id:String):Wish?=s.single("SELECT * FROM wishlist_wish WHERE user_id=? AND id=?",user,id, map=::row)
    internal fun target(s:Db.Session,user:String,kind:DiscoverKind,mbid:String):Wish?=s.single("SELECT * FROM wishlist_wish WHERE user_id=? AND kind=? AND canonical_mbid=?",user,kind.wire,mbid,map=::row)
    internal fun row(r:Db.Row):Wish {
        val ids=Json.parseToJsonElement(r.string("external_ids")).jsonObject.mapValues { it.value.jsonPrimitive.content }
        val availability=decodeAvailability(r.string("availability"))
        return Wish(r.string("id"),r.string("user_id"),DiscoverKind.parse(r.string("kind")),ids,WishSnapshot(r.string("title"),r.stringOrNull("artist"),r.intOrNull("year"),r.stringOrNull("artwork_id")),WishSource(r.string("source_type"),r.stringOrNull("source_id")),r.long("added"),r.string("state"),r.long("updated"),r.stringOrNull("note"),r.longOrNull("note_at"),r.longOrNull("fulfilled"),r.stringOrNull("library_id")?.let { LibraryRef(it,r.string("library_type")) },availability,journal.revision("wish",r.string("id"),r.string("user_id")))
    }
}
internal fun encodeAvailability(a:Availability)=j("status" to a.status,"libraryItems" to a.libraryItems.map { j("id" to it.id,"type" to it.type) },"matchedBy" to a.matchedBy,"matchedTrackCount" to a.matchedTrackCount,"totalTrackCount" to a.totalTrackCount,"editionReleaseId" to a.editionReleaseId,"completeness" to a.completeness).toString()
internal fun decodeAvailability(text:String):Availability {
    val a=Json.parseToJsonElement(text).jsonObject
    return Availability(a["status"]?.jsonPrimitive?.contentOrNull ?: "notHeld",(a["libraryItems"] as? JsonArray).orEmpty().map { LibraryRef(it.jsonObject["id"]!!.jsonPrimitive.content,it.jsonObject["type"]!!.jsonPrimitive.content) },a["matchedBy"]?.jsonPrimitive?.contentOrNull,a["matchedTrackCount"]?.jsonPrimitive?.intOrNull,a["totalTrackCount"]?.jsonPrimitive?.intOrNull,a["editionReleaseId"]?.jsonPrimitive?.contentOrNull,a["completeness"]?.jsonPrimitive?.contentOrNull ?: "unknown")
}
