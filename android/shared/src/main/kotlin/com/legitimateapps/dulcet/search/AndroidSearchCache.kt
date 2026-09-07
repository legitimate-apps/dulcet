package com.legitimateapps.dulcet.search

import android.content.Context
import com.legitimateapps.dulcet.core.*
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlin.time.Duration.Companion.nanoseconds

/** Account-scoped, bounded cache of ranked search pages. Query text is never a preference key. */
public class AndroidSearchCache(context: Context, private val providerId: String) : LocalSearchSource {
    private val preferences = context.getSharedPreferences("dulcet.search.$providerId", Context.MODE_PRIVATE)

    override suspend fun search(query: String): List<SearchResultItem> = withContext(Dispatchers.IO) {
        val encoded = preferences.getString(key(query), null) ?: return@withContext emptyList()
        // A corrupt disposable cache must not prevent a fresh server search.
        runCatching {
            val rows = JSONArray(encoded)
            List(rows.length()) { decode(rows.getJSONObject(it)) }
        }.getOrElse { emptyList() }
    }

    override suspend fun store(query: String, results: List<SearchResultItem>): Unit = withContext(Dispatchers.IO) {
        require(results.all { it.id.providerInstanceId == providerId })
        val pageKey = key(query)
        val editor = preferences.edit()
        if (!preferences.contains(pageKey) && preferences.all.size >= 32) editor.clear()
        editor.putString(pageKey, JSONArray(results.take(90).map(::encode)).toString()).apply()
    }

    private fun key(query: String): String = MessageDigest.getInstance("SHA-256")
        .digest(query.trim().lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

    private fun encode(row: SearchResultItem): JSONObject = JSONObject().apply {
        put("id", row.id.rawId); put("type", row.type.name); put("title", row.title)
        put("credits", JSONArray(row.credits.map { credit -> JSONObject().apply {
            put("role", credit.role.name); put("name", credit.name)
            put("provider", credit.id?.providerInstanceId); put("id", credit.id?.rawId)
        } }))
        put("album", row.albumTitle); put("year", row.year); put("duration", row.duration?.inWholeNanoseconds)
        put("disc", row.discNumber); put("track", row.trackNumber); put("container", row.sourceContainer?.name)
        put("media", row.mediaSourceId); put("artwork", row.artworkKey)
    }

    private fun decode(row: JSONObject): SearchResultItem = SearchResultItem(
        id = ProviderItemId(providerId, row.getString("id")),
        type = SearchResultType.valueOf(row.getString("type")), title = row.getString("title"),
        credits = row.getJSONArray("credits").let { credits -> List(credits.length()) {
            val credit = credits.getJSONObject(it)
            Credit(CreditRole.valueOf(credit.getString("role")), credit.getString("name"),
                credit.stringOrNull("id")?.let { id -> ProviderItemId(credit.getString("provider"), id) })
        } },
        albumTitle = row.stringOrNull("album"), year = row.intOrNull("year"),
        duration = if (row.has("duration")) row.getLong("duration").nanoseconds else null,
        discNumber = row.intOrNull("disc"), trackNumber = row.intOrNull("track"),
        sourceContainer = row.stringOrNull("container")?.let(AudioContainer::valueOf),
        mediaSourceId = row.stringOrNull("media"), artworkKey = row.stringOrNull("artwork"),
    )

    private fun JSONObject.stringOrNull(key: String): String? = if (has(key)) getString(key) else null
    private fun JSONObject.intOrNull(key: String): Int? = if (has(key)) getInt(key) else null
}
