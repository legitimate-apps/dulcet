package com.legitimateapps.dulcet.server

import com.legitimateapps.dulcet.server.api.Catalog
import com.legitimateapps.dulcet.server.api.Handler
import com.legitimateapps.dulcet.server.api.RestApi
import com.legitimateapps.dulcet.server.api.browseHandlers
import com.legitimateapps.dulcet.server.api.launchBackground
import com.legitimateapps.dulcet.server.api.mediaHandlers
import com.legitimateapps.dulcet.server.api.userDataHandlers
import com.legitimateapps.dulcet.server.auth.PasswordVault
import com.legitimateapps.dulcet.server.auth.Users
import com.legitimateapps.dulcet.server.db.Db
import com.legitimateapps.dulcet.server.importer.ImportOptions
import com.legitimateapps.dulcet.server.importer.NavidromeImporter
import com.legitimateapps.dulcet.server.lyrics.LrcLibProvider
import com.legitimateapps.dulcet.server.lyrics.LyricsService
import com.legitimateapps.dulcet.server.media.Artwork
import com.legitimateapps.dulcet.server.media.TranscodeParams
import com.legitimateapps.dulcet.server.media.Transcoder
import com.legitimateapps.dulcet.server.scan.Probe
import com.legitimateapps.dulcet.server.scan.Scanner
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import kotlin.system.exitProcess

const val SERVER_VERSION = "0.1.0"
const val USER_AGENT = "DulcetServer/$SERVER_VERSION (+https://github.com/legitimate-apps/dulcet)"

/** Settings from the environment, overridable by `--name=value` arguments. */
data class Config(
    val musicFolder: Path,
    val dataFolder: Path,
    val port: Int,
    val bind: String,
    val ffmpeg: String,
    val ffprobe: String,
    val lrclibUrl: String?,
    val musicFolderName: String = "Music",
    val scanOnStart: Boolean = true,
    val watchSeconds: Long = 0,
) {
    companion object {
        fun load(args: Map<String, String>, env: Map<String, String> = System.getenv()): Config {
            fun value(name: String, default: String? = null): String? =
                args[name] ?: env["DULCET_" + name.uppercase().replace('-', '_')] ?: default
            val lrclibEnabled = value("lrclib", "on") != "off"
            return Config(
                musicFolder = Paths.get(value("music", "./music")!!).toAbsolutePath().normalize(),
                dataFolder = Paths.get(value("data", "./data")!!).toAbsolutePath().normalize(),
                port = value("port", "4533")!!.toInt(),
                bind = value("bind", "0.0.0.0")!!,
                ffmpeg = value("ffmpeg", "ffmpeg")!!,
                ffprobe = value("ffprobe", "ffprobe")!!,
                lrclibUrl = if (lrclibEnabled) value("lrclib-url", "https://lrclib.net") else null,
                musicFolderName = value("music-folder-name", "Music")!!,
                scanOnStart = value("scan-on-start", "true") != "false",
                watchSeconds = value("rescan-interval", "0")!!.toLong(),
            )
        }
    }
}

/** Everything a handler needs, built once per process (and once per test). */
class ServerContext(val config: Config, val db: Db, lyricsProvider: com.legitimateapps.dulcet.server.lyrics.LyricsProvider? = null) {
    private val key: ByteArray = PasswordVault.keyFromDataFolder(config.dataFolder)
    val users = Users(db, PasswordVault(key))
    val catalog = Catalog(db)
    val scanner = Scanner(db, config.musicFolder, Probe(config.ffprobe))
    val artwork = Artwork(db, config.musicFolder, config.dataFolder.resolve("cache"), config.ffmpeg)
    val transcoder = Transcoder(config.ffmpeg)
    val transcodeParams = TranscodeParams(java.security.MessageDigest.getInstance("SHA-256").digest(key + "transcodeParams".toByteArray()))
    val lyrics = LyricsService(db, config.musicFolder, lyricsProvider ?: config.lrclibUrl?.let { LrcLibProvider(it, USER_AGENT) })

    /** Advertised by getOpenSubsonicExtensions. */
    val extensions: Map<String, List<Int>> = linkedMapOf(
        "formPost" to listOf(1),
        "songLyrics" to listOf(1, 2),
        "transcodeOffset" to listOf(1),
        "transcoding" to listOf(1),
        "dulcetLibrary" to listOf(1),
    )

    fun lastScanMillis(): Long? = db.read { string("SELECT value FROM meta WHERE key = 'last_scan'") }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    fun requestScan() = launchBackground { scanner.scan() }

    fun handlers(): Map<String, Handler> = browseHandlers(this) + userDataHandlers(this) + mediaHandlers(this)

    fun restApi() = RestApi(users, handlers(), open = setOf("getOpenSubsonicExtensions"))
}

fun Application.dulcetModule(ctx: ServerContext) {
    val api = ctx.restApi()
    routing {
        get("/") { call.respondText("Dulcet Server $SERVER_VERSION\n", ContentType.Text.Plain) }
        api.install(this)
    }
}

private val log = LoggerFactory.getLogger("dulcet-server")

fun main(argv: Array<String>) {
    val positional = argv.filterNot { it.startsWith("--") }
    val options = argv.filter { it.startsWith("--") }.associate { option ->
        val body = option.removePrefix("--")
        if ('=' in body) body.substringBefore('=') to body.substringAfter('=') else body to "true"
    }
    val config = Config.load(options)
    Files.createDirectories(config.dataFolder)
    val db = Db.open(config.dataFolder.resolve("dulcet.db"))
    val ctx = ServerContext(config, db)
    when (positional.firstOrNull() ?: "serve") {
        "serve" -> serve(ctx)
        "scan" -> {
            val result = ctx.scanner.scan()
            println("scan: $result")
        }
        "user" -> userCommand(ctx, positional.drop(1), options)
        "import" -> importCommand(ctx, positional.drop(1), options)
        else -> {
            System.err.println("usage: dulcet-server [serve|scan|user add <name> <password> [--admin]|import navidrome --url=.. --user=.. --password=.. --into=<user> [--dry-run]] [--music=DIR --data=DIR --port=N]")
            exitProcess(2)
        }
    }
}

private fun serve(ctx: ServerContext) {
    val env = System.getenv()
    val adminUser = env["DULCET_ADMIN_USER"]
    val adminPassword = env["DULCET_ADMIN_PASSWORD"]
    if (adminUser != null && adminPassword != null && ctx.users.find(adminUser) == null) {
        ctx.users.create(adminUser, adminPassword, isAdmin = true)
        log.info("created admin user from DULCET_ADMIN_USER")
    }
    if (ctx.users.count() == 0L) log.warn("no users yet: run `dulcet-server user add <name> <password> --admin` or set DULCET_ADMIN_USER and DULCET_ADMIN_PASSWORD")
    if (ctx.config.scanOnStart) ctx.requestScan()
    if (ctx.config.watchSeconds > 0) {
        Thread({
            while (true) {
                Thread.sleep(ctx.config.watchSeconds * 1000)
                runCatching { ctx.scanner.scan(force = false) }.onFailure { log.warn("periodic scan failed: {}", it.toString()) }
            }
        }, "rescan").apply { isDaemon = true }.start()
    }
    log.info("Dulcet Server {} on {}:{}, music {}", SERVER_VERSION, ctx.config.bind, ctx.config.port, ctx.config.musicFolder.fileName)
    embeddedServer(CIO, port = ctx.config.port, host = ctx.config.bind) { dulcetModule(ctx) }.start(wait = true)
}

private fun userCommand(ctx: ServerContext, args: List<String>, options: Map<String, String>) {
    when (args.firstOrNull()) {
        "add" -> {
            val name = args.getOrNull(1) ?: error("user add <name> <password>")
            val password = args.getOrNull(2) ?: error("user add <name> <password>")
            if (ctx.users.find(name) != null) ctx.users.setPassword(name, password) else ctx.users.create(name, password, options["admin"] == "true")
            println("user $name ready")
        }
        "list" -> ctx.users.all().forEach { println("${it.name}${if (it.isAdmin) " (admin)" else ""}") }
        else -> error("user add <name> <password> [--admin] | user list")
    }
}

private fun importCommand(ctx: ServerContext, args: List<String>, options: Map<String, String>) {
    require(args.firstOrNull() == "navidrome") { "only `import navidrome` exists so far" }
    val into = ctx.users.find(options["into"] ?: error("--into=<dulcet user>")) ?: error("no such Dulcet user")
    val report = NavidromeImporter(ctx).run(
        ImportOptions(
            url = options["url"] ?: error("--url"),
            username = options["user"] ?: error("--user"),
            password = options["password"] ?: System.getenv("DULCET_IMPORT_PASSWORD") ?: error("--password or DULCET_IMPORT_PASSWORD"),
            intoUserId = into.id,
            dryRun = options["dry-run"] == "true",
        ),
    )
    println(report.summary())
}
