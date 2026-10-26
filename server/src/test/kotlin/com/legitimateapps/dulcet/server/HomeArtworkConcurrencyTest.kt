package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.*

/** The real home response must overlap cold extractions, retain content IDs, and recheck access. */
class HomeArtworkConcurrencyTest {
    @Test fun coldHomeUsesBoundedConcurrentOriginalsAndWarmHomeDoesNotExtractAgain() = testApplication {
        val root=Files.createTempDirectory("home-art-concurrency")
        val fake=root.resolve("ffmpeg")
        Files.writeString(fake,"""#!/bin/sh
            |base=${'$'}(CDPATH= cd -- "${'$'}(dirname -- "${'$'}0")" && pwd)
            |mkdir "${'$'}base/active-${'$'}${'$'}"
            |printf 'extract\n' >> "${'$'}base/extractions"
            |n=0
            |while [ "${'$'}n" -lt 100 ]; do
            |  set -- "${'$'}base"/active-*
            |  if [ "${'$'}#" -ge 2 ]; then printf '%s\n' "${'$'}#" >> "${'$'}base/overlap"; break; fi
            |  n=${'$'}((n+1)); sleep 0.01
            |done
            |sleep 0.1
            |cat "${'$'}base/image.png"
            |rmdir "${'$'}base/active-${'$'}${'$'}"
            |""".trimMargin())
        check(fake.toFile().setExecutable(true))
        ImageIO.write(BufferedImage(16,16,BufferedImage.TYPE_INT_RGB),"png",root.resolve("image.png").toFile())
        val music=Files.createDirectory(root.resolve("music"))
        val config=Config(musicFolder=music,dataFolder=Files.createDirectory(root.resolve("data")),ffmpeg=fake.toString(),ffprobe=Fixtures.ffprobe,port=0,bind="127.0.0.1",scanOnStart=false,lrclibUrl=null,
            discover=com.legitimateapps.dulcet.server.discover.DiscoverSettings(enabled=false))
        val ctx=ServerContext(config,Db.inMemory())
        val user=ctx.users.create("generated-user","generated-password",false)
        val token=ctx.apiAuth.bootstrapPersonal(user,setOf("catalog:read","user:read"))
        try {
            repeat(6) { index ->
                val file=music.resolve("$index.flac")
                Fixtures.track(file,tags=mapOf("album" to "Album $index","title" to "Track $index","artist" to "Artist"),picture=root.resolve("image.png"))
            }
            ctx.scanner.scan()
            assertEquals(6L,ctx.db.read { long("SELECT COUNT(*) FROM track WHERE has_embedded_art=1") },"All six cold sources must really have embedded art")
            application { dulcetModule(ctx) }
            suspend fun home()=client.get("/dulcet/v1/recommendations/home?limit=6") { bearerAuth(token) }
            val cold=home();assertEquals(HttpStatusCode.OK,cold.status)
            fun artwork(body:String)=Json.parseToJsonElement(body).jsonObject["shelves"]!!.jsonArray.flatMap { it.jsonObject["items"]!!.jsonArray }.mapNotNull { it.jsonObject["artwork"]?.jsonObject?.get("id")?.jsonPrimitive?.content }.toSet()
            val ids=artwork(cold.bodyAsText());assertEquals(1,ids.size,"Identical generated image bytes retain one content-addressed artwork ID")
            assertEquals(6,Files.readAllLines(root.resolve("extractions")).size,"Duplicate shelf appearances must not extract a source twice")
            val overlap=if(Files.exists(root.resolve("overlap")))Files.readAllLines(root.resolve("overlap")).map(String::toInt) else emptyList()
            assertTrue(overlap.isNotEmpty(),"Cold home must actually overlap distinct extractions; serial rendering caused the latency defect")
            assertTrue(overlap.all { it in 2..4 },"Home warm-up must never exceed four concurrent extractions: $overlap")
            val warm=home();assertEquals(ids,artwork(warm.bodyAsText()))
            assertEquals(6,Files.readAllLines(root.resolve("extractions")).size)
            ctx.db.write { execute("INSERT OR REPLACE INTO api_user_access(user_id,source_ids) VALUES(?,'[]')",user.id) }
            val hidden=home();assertTrue(Json.parseToJsonElement(hidden.bodyAsText()).jsonObject["shelves"]!!.jsonArray.all { it.jsonObject["items"]!!.jsonArray.isEmpty() })
            assertEquals(6,Files.readAllLines(root.resolve("extractions")).size,"Revocation must prevent cached artwork from leaking into recommendations")
        } finally { ctx.close();ctx.db.close();root.toFile().deleteRecursively() }
    }
}
