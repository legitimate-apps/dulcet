package com.legitimateapps.dulcet.server.wishlist

import com.legitimateapps.dulcet.server.api.markLibraryChanged
import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.discover.*
import com.legitimateapps.dulcet.server.v1.*
import java.security.MessageDigest
import java.util.UUID

class WishlistService(private val ctx:ServerContext,private val clock:()->Long=System::currentTimeMillis) {
    val store=WishlistStore(ctx.db,ctx.journal)
    private val db get()=ctx.db
    private fun uuid(value:String)=try { canonicalMbid(value) } catch(_:Exception) { throw ApiProblem(400,"invalid-request","operationId must be a UUID") }
    private fun fingerprint(value:Any):String {
        val framed=when(value) {
            is AddWish->j("kind" to value.kind.wire,"ids" to value.externalIds,"title" to value.snapshot.title,"artist" to value.snapshot.artist,"year" to value.snapshot.year,"artwork" to value.snapshot.artworkId,"source" to value.source.type,"sourceId" to value.source.id,"actionTime" to value.actionTime)
            is RemoveWish->j("wish" to value.wishId,"addOperation" to value.addOperationId,"actionTime" to value.actionTime)
            else->json(value)
        }
        return MessageDigest.getInstance("SHA-256").digest(framed.toString().toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun text(value:String?,max:Int,required:Boolean=false) {
        if((required && value.isNullOrBlank()) || (value!=null && value.codePointCount(0,value.length)>max)) throw ApiProblem(400,"invalid-request","Invalid text length")
    }
    private fun canonical(input:AddWish):AddWish {
        val ids=input.externalIds.mapValues { (key,value) -> if(key.startsWith("musicbrainz")) try { canonicalMbid(value) } catch(_:Exception) { throw ApiProblem(400,"invalid-request","Invalid MusicBrainz id") } else value }
        if(ids[input.kind.registryKey]==null || ids.size>16 || ids.any { it.key.length>100 || it.value.length>500 })throw ApiProblem(400,"invalid-request","Canonical target id required")
        text(input.snapshot.title,500,true);text(input.snapshot.artist,500);text(input.source.id,500)
        if(input.source.type !in setOf("search","shelf","artist","album","playlist","api"))throw ApiProblem(400,"invalid-request","Invalid wish source")
        input.snapshot.artworkId?.let { if(!it.startsWith("da_") || it.length>100 || db.read { long("SELECT count(*) FROM sqlite_master WHERE name='discover_identity'") }==0L || db.read { long("SELECT count(*) FROM discover_identity WHERE artwork_id=?",it) }==0L)throw ApiProblem(400,"invalid-request","Unknown artwork id") }
        if(input.actionTime!=null && input.actionTime<0)throw ApiProblem(400,"invalid-request","Invalid action time")
        return input.copy(externalIds=ids.toSortedMap(),operationId=uuid(input.operationId))
    }
    private data class Receipt(val type:String,val fingerprint:String,val wish:String?,val kind:String?,val mbid:String?,val time:Long,val removed:Boolean)
    private fun Db.Session.receipt(user:String,op:String)=single("SELECT * FROM wishlist_operation WHERE user_id=? AND operation_id=?",user,op) { Receipt(it.string("type"),it.string("fingerprint"),it.stringOrNull("wish_id"),it.stringOrNull("kind"),it.stringOrNull("mbid"),it.long("action_time"),it.bool("result_removed")) }
    private fun Db.Session.replay(user:String,op:String,type:String,fp:String):WishMutation? {
        val prior=receipt(user,op) ?: return null
        if(prior.type!=type || prior.fingerprint!=fp)throw ApiProblem(409,"operation-conflict")
        val wish=prior.wish?.let { store.read(this,user,it) }
        return WishMutation(false,wish,wish==null && prior.removed || (prior.type=="add" && prior.wish!=null && wish==null))
    }
    private fun Db.Session.save(user:String,op:String,type:String,fp:String,wish:String?,kind:String?,mbid:String?,time:Long,addOp:String?,removed:Boolean) {
        execute("INSERT INTO wishlist_operation(user_id,operation_id,type,fingerprint,wish_id,kind,mbid,action_time,add_operation_id,result_removed) VALUES(?,?,?,?,?,?,?,?,?,?)",user,op,type,fp,wish,kind,mbid,time,addOp,removed)
    }
    private fun Db.Session.targetClock(user:String,kind:String,mbid:String)=single("SELECT action_time,removed FROM wishlist_target_clock WHERE user_id=? AND kind=? AND mbid=?",user,kind,mbid) { it.long("action_time") to it.bool("removed") }
    private fun Db.Session.advance(user:String,kind:String,mbid:String,time:Long,removed:Boolean) {
        execute("INSERT INTO wishlist_target_clock VALUES(?,?,?,?,?) ON CONFLICT(user_id,kind,mbid) DO UPDATE SET action_time=excluded.action_time,removed=excluded.removed",user,kind,mbid,time,removed)
    }
    fun add(userId:String,input:AddWish):WishMutation {
        if(!ctx.config.discover.wishlistEnabled)throw ApiProblem(404,"not-found")
        val a=canonical(input);val fp=fingerprint(a);val target=a.externalIds.getValue(a.kind.registryKey)
        return db.write {
            replay(userId,a.operationId,"add",fp)?.let { return@write it }
            val time=a.actionTime ?: clock();val previous=targetClock(userId,a.kind.wire,target)
            val existing=store.target(this,userId,a.kind,target)
            val cancellation=single("SELECT action_time FROM wishlist_operation WHERE user_id=? AND type='remove' AND add_operation_id=?",userId,a.operationId) { it.long("action_time") }
            if(cancellation!=null) {
                if(existing==null && (previous==null || cancellation>=previous.first))advance(userId,a.kind.wire,target,cancellation,true)
                save(userId,a.operationId,"add",fp,null,a.kind.wire,target,time,null,true)
                return@write WishMutation(false,null,true)
            }
            if(previous!=null && (time<previous.first || (time==previous.first && previous.second))) {
                save(userId,a.operationId,"add",fp,existing?.id,a.kind.wire,target,time,null,existing==null)
                return@write WishMutation(false,existing,existing==null)
            }
            advance(userId,a.kind.wire,target,time,false)
            if(existing!=null) {
                save(userId,a.operationId,"add",fp,existing.id,a.kind.wire,target,time,null,false)
                return@write WishMutation(false,existing,false)
            }
            val id="wi_"+UUID.randomUUID();val now=clock()
            execute("""INSERT INTO wishlist_wish(id,user_id,kind,canonical_mbid,external_ids,title,artist,year,artwork_id,source_type,source_id,added,state,updated) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,'wanted',?)""",id,userId,a.kind.wire,target,json(a.externalIds).toString(),a.snapshot.title,a.snapshot.artist,a.snapshot.year,a.snapshot.artworkId,a.source.type,a.source.id,time,now)
            save(userId,a.operationId,"add",fp,id,a.kind.wire,target,time,null,false)
            WishMutation(true,store.read(this,userId,id),false)
        }
    }
    fun remove(userId:String,input:RemoveWish):WishMutation {
        if(!ctx.config.discover.wishlistEnabled)throw ApiProblem(404,"not-found")
        if((input.wishId==null)==(input.addOperationId==null) || (input.actionTime!=null && input.actionTime<0))throw ApiProblem(400,"invalid-request","Supply wishId or addOperationId")
        val r=input.copy(operationId=uuid(input.operationId),addOperationId=input.addOperationId?.let(::uuid));val fp=fingerprint(r)
        return db.write {
            replay(userId,r.operationId,"remove",fp)?.let { return@write it }
            val time=r.actionTime ?: clock()
            val addReceipt=r.addOperationId?.let { receipt(userId,it) }
            if(addReceipt!=null && addReceipt.type!="add")throw ApiProblem(409,"operation-conflict")
            val wishId=r.wishId ?: addReceipt?.wish
            val wish=wishId?.let { store.read(this,userId,it) }
            val old=if(wish==null && wishId!=null)single("SELECT kind,mbid FROM wishlist_operation WHERE user_id=? AND type='add' AND wish_id=? LIMIT 1",userId,wishId) { it.string("kind") to it.string("mbid") } else null
            if(wishId!=null && wish==null && old==null)throw ApiProblem(404,"not-found")
            val kind=wish?.kind?.wire ?: old?.first ?: addReceipt?.kind
            val mbid=wish?.externalIds?.get(wish.kind.registryKey) ?: old?.second ?: addReceipt?.mbid
            if(wish==null) {
                save(userId,r.operationId,"remove",fp,wishId,kind,mbid,time,r.addOperationId,true)
                return@write WishMutation(false,null,true)
            }
            val previous=targetClock(userId,kind!!,mbid!!)
            if(previous!=null && time<previous.first) {
                save(userId,r.operationId,"remove",fp,wishId,kind,mbid,time,r.addOperationId,false)
                return@write WishMutation(false,wish,false)
            }
            execute("DELETE FROM wishlist_wish WHERE user_id=? AND id=?",userId,wish.id)
            advance(userId,kind,mbid,time,true)
            save(userId,r.operationId,"remove",fp,wish.id,kind,mbid,time,r.addOperationId,true)
            WishMutation(true,null,true)
        }
    }
    fun get(userId:String,wishId:String):Wish?=db.read { store.read(this,userId,wishId) }?.let(::present)
    fun list(userId:String,filter:WishFilter=WishFilter()):List<Wish> {
        if(filter.state!=null && filter.state !in setOf("wanted","requested","searching","unavailable","fulfilled"))throw ApiProblem(400,"invalid-request")
        return db.read { query("SELECT * FROM wishlist_wish WHERE user_id=? ORDER BY added DESC,id",userId,map=store::row) }.filter {
            (filter.kind==null || it.kind==filter.kind) && (filter.state==null || it.state==filter.state) && (filter.includeFulfilledHistory || it.state!="fulfilled" || (it.fulfilledAt ?: 0)>clock()-30*DiscoverCache.DAY)
        }.map(::present)
    }
    fun wasRemoved(userId:String,id:String)=db.read { long("SELECT count(*) FROM wishlist_operation WHERE user_id=? AND type='add' AND wish_id=?",userId,id)>0 && store.read(this,userId,id)==null }
    fun present(wish:Wish):Wish {
        val refs=wish.availability.libraryItems.filter { ctx.access.canReadCatalog(wish.userId,it.type,it.id) }
        val linked=wish.libraryItem?.takeIf { ctx.access.canReadCatalog(wish.userId,it.type,it.id) }
        val availability=if(refs.size==wish.availability.libraryItems.size && (wish.libraryItem==null || linked!=null))wish.availability
            else Availability(if(refs.isEmpty())"notHeld" else "partlyHeld",refs,if(refs.isEmpty())null else wish.availability.matchedBy)
        return wish.copy(libraryItem=linked,availability=availability)
    }
    fun setState(actor:Principal,wishId:String,state:String,note:String?,expectedRevision:String?):Wish {
        if(!ctx.config.discover.wishlistEnabled)throw ApiProblem(404,"not-found")
        requireScopes(actor,"admin")
        text(note,1000)
        return db.write {
            if(!ctx.apiAuth.isActive(actor) || "admin" !in ctx.apiAuth.currentScopes(actor.tokenId).orEmpty() || long("SELECT is_admin FROM user WHERE id=?",actor.user.id)!=1L)throw ApiProblem(403,"permission-denied")
            if(state !in setOf("wanted","requested","searching","unavailable"))throw ApiProblem(422,"invalid-state")
            val wish=single("SELECT * FROM wishlist_wish WHERE id=?",wishId,map=store::row) ?: throw ApiProblem(404,"not-found")
            if(wish.state=="fulfilled")throw ApiProblem(409,"wish-fulfilled")
            if(expectedRevision!=null && expectedRevision!=wish.revision)throw ApiProblem(412,"precondition-failed")
            if(wish.state!=state || wish.note!=note) {
                val now=clock()
                execute("UPDATE wishlist_wish SET state=?,note=?,note_at=?,updated=? WHERE id=?",state,note,if(note==null)null else now,now,wishId)
            }
            store.read(this,wish.userId,wishId)!!
        }
    }
    fun isRejected(userId:String,target:ExternalTarget,libraryItem:LibraryRef):Boolean {
        val mbid=target.externalIds[target.kind.registryKey]?.let { runCatching { canonicalMbid(it) }.getOrNull() } ?: return false
        return db.read { long("SELECT count(*) FROM wishlist_rejected_match WHERE user_id=? AND kind=? AND mbid=? AND library_type=? AND library_id=?",userId,target.kind.wire,mbid,libraryItem.type,libraryItem.id)>0 }
    }
    fun rejectMatch(userId:String,target:ExternalTarget,libraryItem:LibraryRef,wishId:String?,operationId:String):WishMutation {
        if(!ctx.config.discover.enabled && !ctx.config.discover.wishlistEnabled)throw ApiProblem(404,"not-found")
        val mbid=target.externalIds[target.kind.registryKey]?.let { runCatching { canonicalMbid(it) }.getOrNull() } ?: throw ApiProblem(400,"invalid-request")
        val op=uuid(operationId)
        val fp=fingerprint(j("kind" to target.kind.wire,"mbid" to mbid,"libraryType" to libraryItem.type,"libraryId" to libraryItem.id,"wishId" to wishId))
        return db.write {
            replay(userId,op,"reject",fp)?.let { return@write it }
            val wish=if(wishId!=null)store.read(this,userId,wishId) ?: throw ApiProblem(404,"not-found") else store.target(this,userId,target.kind,mbid)
            if(wish!=null && (wish.kind!=target.kind || wish.externalIds[wish.kind.registryKey]!=mbid))throw ApiProblem(404,"not-found")
            if(libraryItem.type!=target.kind.catalogType)throw ApiProblem(404,"not-found")
            val linked=wish?.state=="fulfilled" && wish.libraryItem==libraryItem
            val table=target.kind.catalogType
            val exists=long("SELECT count(*) FROM $table WHERE id=?",libraryItem.id)>0
            if(!ctx.access.canReadCatalog(userId,table,libraryItem.id) && !(linked && !exists))throw ApiProblem(404,"not-found")
            val now=clock()
            val inserted=execute("INSERT OR IGNORE INTO wishlist_rejected_match VALUES(?,?,?,?,?,?)",userId,target.kind.wire,mbid,libraryItem.type,libraryItem.id,now)>0
            val reopen=linked && ctx.config.discover.wishlistEnabled
            if(reopen)execute("UPDATE wishlist_wish SET state='wanted',note=NULL,note_at=NULL,fulfilled=NULL,library_id=NULL,library_type=NULL,availability='{}',updated=? WHERE id=?",now,wish!!.id)
            save(userId,op,"reject",fp,wish?.id,target.kind.wire,mbid,now,null,wishId!=null && wish==null)
            WishMutation(inserted || reopen,wish?.let { store.read(this,userId,it.id) },false)
        }
    }

    internal fun updateAvailability(userId:String,wishId:String,expectedRevision:String,expectedScanStamp:String,availability:Availability):Boolean = db.write {
        val wish=store.read(this,userId,wishId) ?: return@write false
        if(wish.state=="fulfilled" || wish.revision!=expectedRevision || string("SELECT value FROM meta WHERE key='discover_scan_stamp'")!=expectedScanStamp)return@write false
        if(wish.availability!=availability)execute("UPDATE wishlist_wish SET availability=?,updated=? WHERE id=?",encodeAvailability(availability),clock(),wishId)
        true
    }
    internal fun fulfil(userId:String,wishId:String,libraryItem:LibraryRef,expectedWishRevision:String,expectedScanStamp:String,availability:Availability):Boolean = db.write {
        if(!ctx.config.discover.wishlistEnabled)return@write false
        val wish=store.read(this,userId,wishId) ?: return@write false
        if(wish.state=="fulfilled" || wish.revision!=expectedWishRevision || string("SELECT value FROM meta WHERE key='discover_scan_stamp'")!=expectedScanStamp)return@write false
        if(availability.status!="held" || libraryItem !in availability.libraryItems || libraryItem.type!=wish.kind.catalogType || !ctx.access.canReadCatalog(userId,libraryItem.type,libraryItem.id) || isRejected(userId,ExternalTarget(wish.kind,wish.externalIds),libraryItem))return@write false
        val now=clock();val type=if(libraryItem.type=="track")"song" else libraryItem.type
        val inserted=execute("INSERT OR IGNORE INTO library_item(user_id,item_type,item_id,added) VALUES(?,?,?,?)",userId,type,libraryItem.id,now)>0
        if(inserted) {
            markLibraryChanged(userId,now)
            execute("INSERT INTO api_library_receipt(user_id,item_id,at) SELECT ?,?,CAST(value AS INTEGER) FROM meta WHERE key=? ON CONFLICT(user_id,item_id) DO UPDATE SET at=excluded.at",userId,libraryItem.id,"library_changed:$userId")
        }
        execute("UPDATE wishlist_wish SET state='fulfilled',fulfilled=?,library_id=?,library_type=?,availability=?,updated=? WHERE id=?",now,libraryItem.id,libraryItem.type,encodeAvailability(availability),now,wishId)
        true
    }

}
