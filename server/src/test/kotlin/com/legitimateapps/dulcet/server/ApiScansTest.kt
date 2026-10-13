package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.scan.ScanResult
import com.legitimateapps.dulcet.server.v1.iso
import com.legitimateapps.dulcet.server.v1.j
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.*

class ApiScansTest {
    private suspend fun HttpResponse.obj()=Json.parseToJsonElement(bodyAsText()).jsonObject
    private fun config(root:Path,probe:String=Fixtures.ffprobe)=Config(
        musicFolder=root.resolve("music").also(Files::createDirectories),
        dataFolder=root.resolve("data").also(Files::createDirectories),
        port=0,bind="127.0.0.1",ffmpeg=Fixtures.ffmpeg,ffprobe=probe,lrclibUrl=null,scanOnStart=false,
    )
    private fun quote(value:String)="'"+value.replace("'","'\\''")+"'"

    @Test fun `API joins a blocked legacy scan using its actual identity and completion`()=testApplication {
        val root=Files.createTempDirectory("api-scan-join")
        val entered=root.resolve("probe-entered")
        val release=root.resolve("probe-release")
        val wrapper=root.resolve("blocked-ffprobe")
        Files.writeString(wrapper,"""
            #!/bin/sh
            touch ${quote(entered.toString())}
            while [ ! -f ${quote(release.toString())} ]; do sleep 0.01; done
            exec ${quote(Fixtures.ffprobe)} "${'$'}@"
        """.trimIndent()+"\n")
        assertTrue(wrapper.toFile().setExecutable(true))
        val ctx=ServerContext(config(root,wrapper.toString()),Db.inMemory())
        Fixtures.track(ctx.config.musicFolder.resolve("song.flac"))
        val user=ctx.users.create("scan-admin","password",true)
        val token=ctx.apiAuth.bootstrapPersonal(user,setOf("admin"))
        application { dulcetModule(ctx) }
        val executor=Executors.newSingleThreadExecutor()
        val legacy=executor.submit<ScanResult?> { ctx.scanner.scan() }
        try {
            withTimeout(5000) { while(!Files.exists(entered)) delay(10) }
            val running=assertNotNull(ctx.scanner.currentRun())
            assertTrue(ctx.scanner.isScanning)
            assertFalse(running.completion.isDone)
            val response=client.post("/dulcet/v1/admin/scans") {
                bearerAuth(token);contentType(ContentType.Application.Json);setBody("{\"kind\":\"quick\"}")
            }
            assertEquals(HttpStatusCode.Accepted,response.status)
            val scan=response.obj()
            assertEquals(running.id,scan["id"]!!.jsonPrimitive.content)
            assertEquals("running",scan["state"]!!.jsonPrimitive.content)
            assertEquals("full",scan["kind"]!!.jsonPrimitive.content,"the joined scan retains its original kind")
            assertEquals(iso(running.startedAt),scan["startedAt"]!!.jsonPrimitive.content)
            val again=client.post("/dulcet/v1/admin/scans") { bearerAuth(token);header("Idempotency-Key",java.util.UUID.randomUUID().toString()) }
            assertEquals(HttpStatusCode.Accepted,again.status)
            assertEquals(running.id,again.obj()["id"]!!.jsonPrimitive.content)
            assertEquals(1L,ctx.db.read { long("SELECT COUNT(*) FROM api_scan") })
            assertFalse(running.completion.isDone)
            Files.writeString(release,"continue")
            val result=withContext(Dispatchers.IO) { assertNotNull(legacy.get(10,TimeUnit.SECONDS)) }
            assertEquals(1,result.added,"joining must not request a second legacy rescan")
            val completed=client.get("/dulcet/v1/admin/scans/${running.id}") { bearerAuth(token) }.obj()
            assertEquals("completed",completed["state"]!!.jsonPrimitive.content)
            assertEquals(1,completed["counts"]!!.jsonObject["added"]!!.jsonPrimitive.int)
            assertNotEquals(JsonNull,completed["finishedAt"])
            assertNull(ctx.scanner.currentRun())
        } finally {
            Files.writeString(release,"continue")
            executor.shutdown()
            withContext(Dispatchers.IO) { assertTrue(executor.awaitTermination(10,TimeUnit.SECONDS)) }
            ctx.db.close()
        }
    }

    @Test fun `scanner transaction failure is recorded and another scan can complete`()=testApplication {
        val ctx=ServerContext(config(Files.createTempDirectory("api-scan-failure")),Db.inMemory())
        Fixtures.track(ctx.config.musicFolder.resolve("song.flac"))
        val user=ctx.users.create("scan-admin","password",true)
        val token=ctx.apiAuth.bootstrapPersonal(user,setOf("admin"))
        ctx.db.write { execute("CREATE TRIGGER reject_scan BEFORE INSERT ON track BEGIN SELECT RAISE(ABORT,'test scan failure'); END") }
        application { dulcetModule(ctx) }
        try {
            val first=client.post("/dulcet/v1/admin/scans") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Accepted,first.status)
            val failedId=first.obj()["id"]!!.jsonPrimitive.content
            val failed=withTimeout(5000) {
                var scan=client.get("/dulcet/v1/admin/scans/$failedId") { bearerAuth(token) }.obj()
                while(scan["state"]!!.jsonPrimitive.content in setOf("queued","running")) {
                    delay(10)
                    scan=client.get("/dulcet/v1/admin/scans/$failedId") { bearerAuth(token) }.obj()
                }
                scan
            }
            assertEquals("failed",failed["state"]!!.jsonPrimitive.content)
            assertEquals("Scan failed; catalog retained",failed["error"]!!.jsonPrimitive.content)
            assertNotEquals(JsonNull,failed["finishedAt"])
            assertFalse(ctx.scanner.isScanning)
            assertEquals(0L,ctx.db.read { long("SELECT COUNT(*) FROM track") },"the failed transaction leaves no partial catalog")
            ctx.db.write { execute("DROP TRIGGER reject_scan") }
            val second=client.post("/dulcet/v1/admin/scans") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Accepted,second.status)
            val nextId=second.obj()["id"]!!.jsonPrimitive.content
            assertNotEquals(failedId,nextId)
            val completed=withTimeout(5000) {
                var scan=client.get("/dulcet/v1/admin/scans/$nextId") { bearerAuth(token) }.obj()
                while(scan["state"]!!.jsonPrimitive.content in setOf("queued","running")) {
                    delay(10)
                    scan=client.get("/dulcet/v1/admin/scans/$nextId") { bearerAuth(token) }.obj()
                }
                scan
            }
            assertEquals("completed",completed["state"]!!.jsonPrimitive.content)
            assertEquals(1,completed["counts"]!!.jsonObject["added"]!!.jsonPrimitive.int)
        } finally { ctx.db.close() }
    }

    @Test fun `restart terminates interrupted persisted jobs and allows a new scan`()=testApplication {
        val root=Files.createTempDirectory("api-scan-restart")
        val config=config(root)
        val file=config.dataFolder.resolve("library.db")
        var userId=""
        Db.open(file).use { db ->
            val first=ServerContext(config,db)
            userId=first.users.create("scan-admin","password",true).id
            db.write {
                execute("INSERT INTO api_scan VALUES('queued',1,?)",j("id" to "queued","kind" to "quick","state" to "queued","startedAt" to null,"finishedAt" to null).toString())
                execute("INSERT INTO api_scan VALUES('running',2,?)",j("id" to "running","kind" to "full","state" to "running","startedAt" to iso(2),"finishedAt" to null).toString())
            }
        }
        val ctx=ServerContext(config,Db.open(file))
        val token=ctx.apiAuth.bootstrapPersonal(assertNotNull(ctx.users.byId(userId)),setOf("admin"))
        application { dulcetModule(ctx) }
        try {
            val recovered=client.get("/dulcet/v1/admin/scans") { bearerAuth(token) }.obj()["items"]!!.jsonArray
            assertEquals(2,recovered.size)
            recovered.forEach { raw ->
                val scan=raw.jsonObject
                assertEquals("failed",scan["state"]!!.jsonPrimitive.content)
                assertNotEquals(JsonNull,scan["finishedAt"])
                assertTrue(scan["error"]!!.jsonPrimitive.content.contains("restart"))
            }
            val response=client.post("/dulcet/v1/admin/scans") { bearerAuth(token) }
            assertEquals(HttpStatusCode.Accepted,response.status)
            val id=response.obj()["id"]!!.jsonPrimitive.content
            assertTrue(id !in setOf("queued","running"))
            withTimeout(5000) {
                while(true) {
                    val scan=client.get("/dulcet/v1/admin/scans/$id") { bearerAuth(token) }.obj()
                    if(scan["state"]!!.jsonPrimitive.content=="completed") break
                    assertEquals("running",scan["state"]!!.jsonPrimitive.content)
                    delay(10)
                }
            }
        } finally { ctx.db.close() }
    }
}
