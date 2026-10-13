package com.legitimateapps.dulcet.apiconformance

import com.fasterxml.jackson.databind.JsonNode
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A client the suite makes verified without a network, through the Testing override
 * `trustedClients` (README "Registered"): its metadata document is given inline.
 */
class TrustedClient(val clientId: String, val redirectUri: String, val name: String) {
    fun document(): Map<String, Any> = mapOf("client_id" to clientId, "client_name" to name, "redirect_uris" to listOf(redirectUri),
        "grant_types" to listOf("authorization_code", "refresh_token"), "response_types" to listOf("code"), "token_endpoint_auth_method" to "none")

    companion object {
        val SUITE = TrustedClient("https://conformance.dulcet.invalid/oauth/trusted-client.json",
            "http://127.0.0.1/dulcet-conformance/callback", "Dulcet conformance (trusted)")
    }
}

/** The result of one trip through the authorization endpoint. */
class Authorization(
    val code: String?,
    val error: String?,
    val iss: String?,
    val state: String?,
    val expectedState: String,
    val verifier: String,
    val redirectUri: String,
    val clientId: String,
)

/**
 * Drives the OAuth authorization code flow with PKCE (README §3) against the server's own metadata.
 *
 * `--oauth=interactive` prints the authorization URL and waits on a loopback redirect while a person
 * signs in to the disposable instance. `--oauth=auto` requests the URL itself and follows the
 * server's redirect, which works only when the server approves without a sign-in page (a test
 * configuration); a sign-in page makes the OAuth checks skip with that reason.
 */
class OAuthDriver(private val run: Run) {
    private val random = SecureRandom()

    fun requireEnabled(ctx: Ctx) {
        when (run.config.oauthMode) {
            OAuthMode.OFF -> ctx.skip("OAuth flow checks are off; run with --oauth=auto (the default: autoApprove signs in as user A) or --oauth=interactive")
            else -> {}
        }
    }

    fun metadata(ctx: Ctx): JsonNode {
        run.asMetadata?.let { return it }
        val ex = ctx.send("GET", "getAuthorizationServerMetadata", run.origin + "/.well-known/oauth-authorization-server")
        ctx.expectStatus(ex, 200, what = "authorization server metadata")
        return ctx.body(ex).also { run.asMetadata = it }
    }

    private fun endpoint(ctx: Ctx, name: String): String =
        metadata(ctx).str(name) ?: ctx.fail("authorization server metadata has no $name")

    private fun randomToken(bytes: Int): String {
        val b = ByteArray(bytes)
        random.nextBytes(b)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    fun s256(verifier: String): String = Base64.getUrlEncoder().withoutPadding()
        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(StandardCharsets.US_ASCII)))

    /** One authorization. [method] `plain` sends the verifier itself as the challenge, which the server must refuse. */
    fun authorize(ctx: Ctx, scope: String, method: String = "S256", purpose: String, client: TrustedClient? = null): Authorization {
        requireEnabled(ctx)
        if (client != null && run.config.oauthMode != OAuthMode.AUTO) ctx.manual("signing in as the suite's trusted client needs --oauth=auto (its redirect is not a listener)")
        if (run.config.oauthMode == OAuthMode.AUTO) ctx.ensureAutoApprove(client?.clientId ?: run.config.clientId)
        val clientId = client?.clientId ?: run.config.clientId
        val verifier = randomToken(48)
        val state = randomToken(12)
        val challenge = if (method == "plain") verifier else s256(verifier)
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val redirectUri = client?.redirectUri ?: "http://127.0.0.1:${server.address.port}/callback"
        val callback = CompletableFuture<String>()
        server.createContext("/callback") { exchange ->
            callback.complete(exchange.requestURI.rawQuery ?: "")
            val reply = "Dulcet conformance: authorization received. You can close this window.".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            exchange.sendResponseHeaders(200, reply.size.toLong())
            exchange.responseBody.use { it.write(reply) }
        }
        server.start()
        try {
            val url = endpoint(ctx, "authorization_endpoint") + "?" + Http.query(listOf(
                "response_type" to "code",
                "client_id" to clientId,
                "redirect_uri" to redirectUri,
                "scope" to scope,
                "state" to state,
                "code_challenge" to challenge,
                "code_challenge_method" to method,
            ))
            val query: String = when (run.config.oauthMode) {
                OAuthMode.INTERACTIVE -> {
                    run.config.out.println("\n  >> ${ctx.check.id}: sign in to the disposable instance and approve ($purpose):\n     $url\n")
                    try {
                        callback.get(run.config.oauthTimeoutSeconds.toLong(), TimeUnit.SECONDS)
                    } catch (e: java.util.concurrent.TimeoutException) {
                        ctx.fail("no authorization arrived within ${run.config.oauthTimeoutSeconds} s")
                    }
                }
                else -> followAutomatically(ctx, url, redirectUri)
                    ?: return Authorization(null, "http_400", null, null, state, verifier, redirectUri, clientId)
            }
            val params = parseQuery(query)
            params["code"]?.let { run.secrets.add(it, SecretKind.TOKEN) }
            return Authorization(params["code"], params["error"], params["iss"], params["state"], state, verifier, redirectUri, clientId)
        } finally {
            server.stop(0)
        }
    }

    /** Returns the redirect's query, or null when the authorization endpoint refused with 400. */
    private fun followAutomatically(ctx: Ctx, start: String, redirectUri: String): String? {
        var url = start
        repeat(5) {
            val ex = ctx.send("GET", if (url == start) "authorize" else null, url)
            when (ex.status) {
                302, 303 -> {
                    val location = ex.header("Location") ?: ctx.fail("authorization redirect without Location")
                    val target = URI(url).resolve(location).toString()
                    if (target.startsWith(redirectUri)) return URI(target).rawQuery ?: ""
                    val sameOrigin = URI(target).let { "${it.scheme}://${it.rawAuthority}" } == run.origin
                    if (!sameOrigin) ctx.fail("the authorization endpoint redirected to another origin, not to the client's redirect_uri")
                    url = target
                }
                400 -> return null
                200 -> ctx.fail("the authorization endpoint showed a page although autoApprove lists this client id (README Testing profile)")
                else -> ctx.fail("authorization endpoint answered ${ex.describe()}")
            }
        }
        ctx.fail("the authorization endpoint redirected more than 5 times")
    }

    fun token(ctx: Ctx, params: List<Pair<String, String?>>): Exchange {
        val ex = ctx.send("POST", "requestToken", endpoint(ctx, "token_endpoint"),
            body = Http.form(params), contentType = "application/x-www-form-urlencoded")
        ex.json?.let { body ->
            run.secrets.add(body.str("access_token"), SecretKind.TOKEN)
            run.secrets.add(body.str("refresh_token"), SecretKind.TOKEN)
        }
        return ex
    }

    fun exchangeCode(ctx: Ctx, auth: Authorization, verifier: String = auth.verifier, redirectUri: String = auth.redirectUri): Exchange =
        token(ctx, listOf(
            "grant_type" to "authorization_code",
            "client_id" to auth.clientId,
            "code" to (auth.code ?: ctx.fail("the authorization returned no code (error=${auth.error})")),
            "redirect_uri" to redirectUri,
            "code_verifier" to verifier,
        ))

    fun refresh(ctx: Ctx, refreshToken: String, clientId: String = run.config.clientId): Exchange = token(ctx, listOf(
        "grant_type" to "refresh_token",
        "client_id" to clientId,
        "refresh_token" to refreshToken,
    ))

    fun revoke(ctx: Ctx, token: String, hint: String, clientId: String = run.config.clientId): Exchange =
        ctx.send("POST", "revokeToken", endpoint(ctx, "revocation_endpoint"),
            body = Http.form(listOf("token" to token, "token_type_hint" to hint, "client_id" to clientId)),
            contentType = "application/x-www-form-urlencoded")

    /** Authorizes and exchanges in one step; fails the check if either step fails. */
    fun signIn(ctx: Ctx, scope: String, purpose: String, client: TrustedClient? = null): JsonNode {
        val auth = authorize(ctx, scope, purpose = purpose, client = client)
        ctx.ensure(auth.code != null) { "authorization failed: error=${auth.error}" }
        val ex = exchangeCode(ctx, auth)
        ctx.expectStatus(ex, 200, what = "exchanging the code")
        return ctx.body(ex)
    }

    companion object {
        fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate {
            val k = it.substringBefore('=')
            val v = it.substringAfter('=', "")
            URLDecoder.decode(k, StandardCharsets.UTF_8) to URLDecoder.decode(v, StandardCharsets.UTF_8)
        }
    }
}
