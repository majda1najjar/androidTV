package com.xtreamtv.data

import android.util.Base64
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonParser
import com.google.gson.TypeAdapter
import com.google.gson.reflect.TypeToken
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import com.xtreamtv.data.model.Category
import com.xtreamtv.data.model.EpgEntry
import com.xtreamtv.data.model.Episode
import com.xtreamtv.data.model.LiveStream
import com.xtreamtv.data.model.LoginResponse
import com.xtreamtv.data.model.Series
import com.xtreamtv.data.model.SeriesDetails
import com.xtreamtv.data.model.SeriesInfo
import com.xtreamtv.data.model.VodStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Client for the standard Xtream Codes player API.
 *
 * Endpoints:
 *   player_api.php?username=U&password=P                      -> auth / profile
 *   player_api.php?...&action=get_live_categories             -> live categories
 *   player_api.php?...&action=get_live_streams                -> live channels
 *   player_api.php?...&action=get_vod_streams                 -> movies
 *   player_api.php?...&action=get_series                      -> series list
 *   player_api.php?...&action=get_series_info&series_id=N     -> seasons/episodes
 */
class XtreamApiClient(private val session: SessionManager) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    // Lenient Integer adapter: panels return numbers as "12", "", or null.
    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(Int::class.javaObjectType, LenientIntAdapter().nullSafe())
        .create()

    // ---- Auth ----

    suspend fun authenticate(serverUrl: String, username: String, password: String): LoginResponse {
        val body = fetch(playerApiUrl(normalizeBaseUrl(serverUrl), username, password, null))
        val response = runCatching { gson.fromJson(body, LoginResponse::class.java) }.getOrNull()
            ?: throw IOException("Server did not return a valid Xtream response")
        return response
    }

    // ---- Catalog ----

    suspend fun getLiveCategories(): List<Category> = fetchList("get_live_categories")

    suspend fun getVodCategories(): List<Category> = fetchList("get_vod_categories")

    suspend fun getSeriesCategories(): List<Category> = fetchList("get_series_categories")

    /** All live channels, or just one category's when [categoryId] is given. */
    suspend fun getLiveStreams(categoryId: String? = null): List<LiveStream> =
        fetchList<LiveStream>(
            "get_live_streams",
            if (categoryId != null) mapOf("category_id" to categoryId) else emptyMap()
        ).filter { it.streamId != null }

    suspend fun getVodStreams(): List<VodStream> =
        fetchList<VodStream>("get_vod_streams").filter { it.streamId != null }

    suspend fun getSeries(): List<Series> =
        fetchList<Series>("get_series").filter { it.seriesId != null }

    suspend fun getSeriesInfo(seriesId: Int): SeriesInfo {
        val url = playerApiUrl(requireBaseUrl(), requireUser(), requirePass(), "get_series_info")
            .newBuilder()
            .addQueryParameter("series_id", seriesId.toString())
            .build()
        val body = fetch(url)
        val root = JsonParser.parseString(body).takeIf { it.isJsonObject }?.asJsonObject
            ?: throw IOException("Unexpected series_info response")

        val details = root.get("info")?.takeIf { it.isJsonObject }
            ?.let { runCatching { gson.fromJson(it, SeriesDetails::class.java) }.getOrNull() }

        // "episodes" is an object keyed by season on most panels, but some
        // return an array of per-season arrays — handle both.
        val episodes = LinkedHashMap<String, List<Episode>>()
        val episodesElement = root.get("episodes")
        when {
            episodesElement == null || episodesElement.isJsonNull -> Unit
            episodesElement.isJsonObject -> {
                for ((season, listElement) in episodesElement.asJsonObject.entrySet()) {
                    if (listElement.isJsonArray) episodes[season] = parseEpisodes(listElement.asJsonArray)
                }
            }
            episodesElement.isJsonArray -> {
                episodesElement.asJsonArray.forEachIndexed { index, listElement ->
                    if (listElement.isJsonArray) {
                        episodes[(index + 1).toString()] = parseEpisodes(listElement.asJsonArray)
                    }
                }
            }
        }
        return SeriesInfo(details, episodes)
    }

    /** Now/next programme info for a live channel; empty when the panel has no EPG. */
    suspend fun getShortEpg(streamId: Int, limit: Int = 2): List<EpgEntry> {
        val url = playerApiUrl(requireBaseUrl(), requireUser(), requirePass(), "get_short_epg")
            .newBuilder()
            .addQueryParameter("stream_id", streamId.toString())
            .addQueryParameter("limit", limit.toString())
            .build()
        val body = fetch(url)
        val root = runCatching { JsonParser.parseString(body) }.getOrNull() ?: return emptyList()
        val listings = root.takeIf { it.isJsonObject }?.asJsonObject
            ?.get("epg_listings")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return listings.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            fun str(key: String): String? =
                obj.get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotBlank() }
            EpgEntry(decodeEpgTitle(str("title")), str("start"), str("end"))
        }
    }

    // Titles are usually base64, but some panels send plain text — and short
    // plain words ("News", "Golf") are themselves valid base64 that decodes to
    // garbage. Prefer the decoded form only when it looks like real text.
    private fun decodeEpgTitle(raw: String?): String? {
        if (raw.isNullOrBlank()) return raw
        val decoded = runCatching {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull() ?: runCatching {
            String(Base64.decode(raw, Base64.URL_SAFE), Charsets.UTF_8).trim()
        }.getOrNull()
        val looksLikeText = !decoded.isNullOrBlank() &&
            decoded.none { it == '�' || (it.isISOControl() && it != '\n') }
        return if (looksLikeText) decoded else raw
    }

    // ---- Playback URLs ----

    fun liveStreamUrl(streamId: Int): String = playbackUrl("live", "$streamId.ts")

    fun vodStreamUrl(vod: VodStream): String {
        val ext = vod.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
        return playbackUrl("movie", "${vod.streamId}.$ext")
    }

    fun seriesEpisodeUrl(episode: Episode): String {
        val ext = episode.containerExtension?.takeIf { it.isNotBlank() } ?: "mp4"
        return playbackUrl("series", "${episode.id}.$ext")
    }

    // ---- Internals ----

    // Credentials go in as path segments so reserved characters ('/', '?', '#',
    // spaces) get percent-encoded — raw interpolation would malform the URL for
    // such passwords even though login (query-encoded) succeeds.
    private fun playbackUrl(kind: String, fileName: String): String {
        val base = requireBaseUrl().toHttpUrlOrNull()
            ?: throw IOException("Invalid server URL")
        return base.newBuilder()
            .addPathSegment(kind)
            .addPathSegment(requireUser())
            .addPathSegment(requirePass())
            .addPathSegment(fileName)
            .build()
            .toString()
    }

    private fun parseEpisodes(array: JsonArray): List<Episode> =
        array.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject
                ?: return@mapNotNull null
            // Panels emit "info": [] (PHP empty-array quirk) for episodes with
            // no metadata; Gson would reject the whole episode over it.
            if (obj.has("info") && !obj.get("info").isJsonObject) {
                obj.remove("info")
            }
            runCatching { gson.fromJson(obj, Episode::class.java) }.getOrNull()
        }.filter { !it.id.isNullOrBlank() }

    private suspend inline fun <reified T> fetchList(
        action: String,
        params: Map<String, String> = emptyMap()
    ): List<T> {
        val url = playerApiUrl(requireBaseUrl(), requireUser(), requirePass(), action)
            .newBuilder()
            .apply { params.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
        val body = fetch(url)
        val element = runCatching { JsonParser.parseString(body) }.getOrNull()
            ?: throw IOException("Server returned malformed JSON")
        // Panels answer list requests with an object on failure (expired
        // account, server error). Swallowing that as an empty catalog would
        // cache the emptiness — throw so callers can offer a retry. A bare {}
        // stays an empty list (some panels emit it for genuinely empty sets).
        if (!element.isJsonArray) {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject
            if (obj != null && obj.entrySet().isEmpty()) return emptyList()
            val authRejected = obj?.get("user_info")?.takeIf { it.isJsonObject }
                ?.asJsonObject?.get("auth")
                ?.let { runCatching { it.asInt }.getOrNull() } == 0
            throw IOException(
                if (authRejected) "Provider rejected the login (subscription expired?)"
                else "Unexpected response from provider"
            )
        }
        val type = TypeToken.getParameterized(List::class.java, T::class.java).type
        return gson.fromJson(element, type)
    }

    private suspend fun fetch(url: HttpUrl): String = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Server returned HTTP ${response.code}")
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Server returned an empty response")
        }
    }

    private fun playerApiUrl(base: String, user: String, pass: String, action: String?): HttpUrl {
        val builder = "$base/player_api.php".toHttpUrlOrNull()?.newBuilder()
            ?: throw IOException("Invalid server URL")
        builder.addQueryParameter("username", user)
        builder.addQueryParameter("password", pass)
        if (action != null) builder.addQueryParameter("action", action)
        return builder.build()
    }

    private fun requireBaseUrl(): String =
        session.serverUrl?.takeIf { it.isNotBlank() }?.let { normalizeBaseUrl(it) }
            ?: throw IOException("No saved session")

    private fun requireUser(): String =
        session.username?.takeIf { it.isNotBlank() } ?: throw IOException("No saved session")

    private fun requirePass(): String =
        session.password?.takeIf { it.isNotBlank() } ?: throw IOException("No saved session")

    private class LenientIntAdapter : TypeAdapter<Int>() {
        override fun write(out: JsonWriter, value: Int?) {
            if (value == null) out.nullValue() else out.value(value)
        }

        override fun read(reader: JsonReader): Int? = when (reader.peek()) {
            JsonToken.NUMBER -> reader.nextDouble().toInt()
            JsonToken.STRING -> reader.nextString().trim().toDoubleOrNull()?.toInt()
            JsonToken.BOOLEAN -> if (reader.nextBoolean()) 1 else 0
            else -> {
                reader.skipValue()
                null
            }
        }
    }

    companion object {
        private const val USER_AGENT = "XtreamTV/1.0"

        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim()
            // TV keyboards love to capitalize the scheme; compare case-insensitively.
            val url = when {
                trimmed.startsWith("http://", ignoreCase = true) ->
                    "http://" + trimmed.substring(7)
                trimmed.startsWith("https://", ignoreCase = true) ->
                    "https://" + trimmed.substring(8)
                else -> "http://$trimmed"
            }
            // Users often paste the full player_api.php / get.php URL from their
            // provider email — keep only scheme://host[:port].
            val parsed = url.toHttpUrlOrNull() ?: return url.trimEnd('/')
            // HttpUrl.host strips the brackets from IPv6 literals; restore them.
            val host = if (':' in parsed.host) "[${parsed.host}]" else parsed.host
            return buildString {
                append(parsed.scheme).append("://").append(host)
                if (parsed.port != HttpUrl.defaultPort(parsed.scheme)) {
                    append(':').append(parsed.port)
                }
            }
        }
    }
}
