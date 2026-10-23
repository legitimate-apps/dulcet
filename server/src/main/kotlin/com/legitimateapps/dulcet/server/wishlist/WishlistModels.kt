package com.legitimateapps.dulcet.server.wishlist

import com.legitimateapps.dulcet.server.discover.*

data class WishSnapshot(val title:String,val artist:String?=null,val year:Int?=null,val artworkId:String?=null)
data class WishSource(val type:String,val id:String?=null)
data class ExternalTarget(val kind:DiscoverKind,val externalIds:ExternalIds)
data class AddWish(val kind:DiscoverKind,val externalIds:ExternalIds,val snapshot:WishSnapshot,val source:WishSource,val operationId:String,val actionTime:Long?=null)
data class RemoveWish(val wishId:String?,val addOperationId:String?,val operationId:String,val actionTime:Long?=null)
data class WishMutation(val applied:Boolean,val wish:Wish?,val removed:Boolean)
data class WishFilter(val kind:DiscoverKind?=null,val state:String?=null,val includeFulfilledHistory:Boolean=false)
data class Wish(val id:String,val userId:String,val kind:DiscoverKind,val externalIds:ExternalIds,val snapshot:WishSnapshot,val source:WishSource,val addedAt:Long,val state:String,val updatedAt:Long,val note:String?,val noteAt:Long?,val fulfilledAt:Long?,val libraryItem:LibraryRef?,val availability:Availability,val revision:String)
