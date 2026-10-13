package com.legitimateapps.dulcet.server.v1

import com.legitimateapps.dulcet.server.ServerContext
import com.legitimateapps.dulcet.server.auth.PasswordVault
import kotlinx.serialization.json.*
import java.util.Base64
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Authenticated cursors never trust client-supplied sort keys or snapshot boundaries. */
class CursorCodec(private val ctx: ServerContext) {
    private val key=PasswordVault.keyFromDataFolder(ctx.config.dataFolder)
    private val encoder=Base64.getUrlEncoder().withoutPadding()
    private fun sign(bytes:ByteArray)=Mac.getInstance("HmacSHA256").run { init(SecretKeySpec(key,"HmacSHA256")); doFinal(bytes) }
    fun encode(value:JsonObject):String {
        val payload=encoder.encodeToString(JsonObject(value+j("instance" to ctx.journal.instanceId,"visibility" to ctx.journal.visibilityEpoch())).toString().toByteArray())
        return "c_$payload.${encoder.encodeToString(sign(payload.toByteArray()))}"
    }
    fun decode(cursor:String):JsonObject {
        try {
            val parts=cursor.removePrefix("c_").split('.')
            if(!cursor.startsWith("c_") || parts.size!=2) throw ApiProblem(400,"invalid-request","Invalid cursor")
            val value=Json.parseToJsonElement(Base64.getUrlDecoder().decode(parts[0]).decodeToString()).jsonObject
            if(value["instance"]?.jsonPrimitive?.content!=ctx.journal.instanceId) throw ApiProblem(410,"cursor-expired")
            if(!MessageDigest.isEqual(sign(parts[0].toByteArray()),Base64.getUrlDecoder().decode(parts[1]))) throw ApiProblem(400,"invalid-request","Invalid cursor")
            if(value["visibility"]?.jsonPrimitive?.longOrNull!=ctx.journal.visibilityEpoch()) throw ApiProblem(410,"cursor-expired")
            return value
        } catch(e:ApiProblem) { throw e } catch(_:Exception) { throw ApiProblem(400,"invalid-request","Invalid cursor") }
    }
}
