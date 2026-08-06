package com.xtreamtv.data.model

import com.google.gson.annotations.SerializedName

// Xtream panels are wildly inconsistent about types (ints as strings, empty
// strings for numbers), so numeric fields are nullable and parsed leniently —
// see XtreamApiClient's Gson configuration.

data class LoginResponse(
    @SerializedName("user_info") val userInfo: UserInfo?,
    @SerializedName("server_info") val serverInfo: ServerInfo?
)

data class UserInfo(
    @SerializedName("username") val username: String?,
    @SerializedName("auth") val auth: Int?,
    @SerializedName("status") val status: String?,
    @SerializedName("exp_date") val expDate: String?,
    @SerializedName("max_connections") val maxConnections: String?
)

data class ServerInfo(
    @SerializedName("url") val url: String?,
    @SerializedName("port") val port: String?,
    @SerializedName("server_protocol") val protocol: String?
)

data class Category(
    @SerializedName("category_id") val id: String?,
    @SerializedName("category_name") val name: String?
)

data class LiveStream(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("stream_id") val streamId: Int?,
    @SerializedName("stream_icon") val icon: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("epg_channel_id") val epgChannelId: String?
)

data class VodStream(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("stream_id") val streamId: Int?,
    @SerializedName("stream_icon") val icon: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("rating") val rating: String?
)

data class Series(
    @SerializedName("num") val num: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("series_id") val seriesId: Int?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("category_id") val categoryId: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("rating") val rating: String?
)

data class SeriesDetails(
    @SerializedName("name") val name: String?,
    @SerializedName("cover") val cover: String?,
    @SerializedName("plot") val plot: String?
)

data class Episode(
    @SerializedName("id") val id: String?,
    @SerializedName("episode_num") val episodeNum: Int?,
    @SerializedName("title") val title: String?,
    @SerializedName("container_extension") val containerExtension: String?,
    @SerializedName("season") val season: Int?,
    @SerializedName("info") val info: EpisodeInfo?
)

data class EpisodeInfo(
    @SerializedName("movie_image") val image: String?,
    @SerializedName("plot") val plot: String?,
    @SerializedName("duration") val duration: String?
)

/** Built manually by XtreamApiClient (the `episodes` shape varies per panel). */
data class SeriesInfo(
    val info: SeriesDetails?,
    val episodes: Map<String, List<Episode>>
)

/** One programme from get_short_epg (title arrives base64-encoded). */
data class EpgEntry(
    val title: String?,
    val start: String?,
    val end: String?
)
