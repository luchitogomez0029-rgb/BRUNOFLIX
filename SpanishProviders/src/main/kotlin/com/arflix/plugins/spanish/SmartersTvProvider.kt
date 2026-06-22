package com.arflix.plugins.spanish

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities

class SmartersTvProvider : MainAPI() {
    override var mainUrl = "http://smarterstv99.dyndns.tv:25461"
    override var name = "Smarters TV (Xtream)"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Live)

    private val apiBase = "$mainUrl/player_api.php?username=caco&password=caco"

    // Optional: caching logic could be added, but Cloudstream calls getMainPage dynamically
    override val mainPage = mainPageOf(
        "" to "Todos Los Canales"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // Fetch Categories
        val categoriesRes = app.get("$apiBase&action=get_live_categories").text
        val categoriesJson = tryParseJson<List<Map<String, String>>>(categoriesRes) ?: emptyList()
        val catMap = categoriesJson.mapNotNull { 
            val id = it["category_id"]
            var name = it["category_name"]?.trim() ?: return@mapNotNull null
            if (name.contains("---")) return@mapNotNull null
            if (name.uppercase().startsWith("TV |")) {
                name = name.substring(name.indexOf("|") + 1).trim()
            }
            name = name.lowercase().split(" ").joinToString(" ") { word ->
                word.replaceFirstChar { char -> if (char.isLowerCase()) char.titlecase() else char.toString() }
            }
            if (id.isNullOrEmpty() || name.isEmpty()) null else id to name
        }.toMap()

        // Fetch Streams
        val streamsRes = app.get("$apiBase&action=get_live_streams").text
        val streamsJson = tryParseJson<List<Map<String, Any>>>(streamsRes) ?: emptyList()

        val groupedMap = mutableMapOf<String, MutableList<SearchResponse>>()
        val allStreams = mutableListOf<SearchResponse>()

        for (stream in streamsJson) {
            val catId = stream["category_id"]?.toString() ?: ""
            val streamId = stream["stream_id"]?.toString() ?: ""
            val streamName = stream["name"]?.toString()?.trim() ?: ""
            val streamIcon = stream["stream_icon"]?.toString() ?: ""

            if (streamId.isNotEmpty() && streamName.isNotEmpty()) {
                val groupName = catMap[catId] ?: "Otros"
                val upperGroup = groupName.uppercase()
                val upperName = streamName.uppercase()
                if (upperGroup.contains("XXX") || upperGroup.contains("18+") || upperGroup.contains("ADULT") || upperGroup.contains("PORN") ||
                    upperName.contains("XXX") || upperName.contains("18+") || upperName.contains("ADULT") || upperName.contains("PORN")) {
                    continue
                }

                val show = newLiveSearchResponse(streamName, streamId) {
                    this.posterUrl = streamIcon
                }

                if (!groupedMap.containsKey(groupName)) {
                    groupedMap[groupName] = mutableListOf()
                }
                groupedMap[groupName]?.add(show)
                allStreams.add(show)
            }
        }

        val homePages = mutableListOf<HomePageList>()
        homePages.add(HomePageList("Todos Los Canales", allStreams))

        val spanishCats = groupedMap.filter { it.key.uppercase().contains("ESPAÑOL") || it.key.uppercase().contains("LATINO") }
        val otherCats = groupedMap.filterNot { it.key.uppercase().contains("ESPAÑOL") || it.key.uppercase().contains("LATINO") }

        spanishCats.forEach { homePages.add(HomePageList(it.key, it.value)) }
        otherCats.forEach { homePages.add(HomePageList(it.key, it.value)) }

        return newHomePageResponse(homePages)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val streamsRes = app.get("$apiBase&action=get_live_streams").text
        val streamsJson = tryParseJson<List<Map<String, Any>>>(streamsRes) ?: emptyList()

        return streamsJson.mapNotNull { stream ->
            val streamId = stream["stream_id"]?.toString() ?: ""
            val streamName = stream["name"]?.toString()?.trim() ?: ""
            val streamIcon = stream["stream_icon"]?.toString() ?: ""
            if (streamId.isNotEmpty() && streamName.contains(query, ignoreCase = true)) {
                newLiveSearchResponse(streamName, streamId) {
                    this.posterUrl = streamIcon
                }
            } else null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        // url is streamId
        val streamUrlHls = "$mainUrl/live/caco/caco/$url.m3u8"
        return LiveStreamLoadResponse(
            name = name,
            url = url,
            posterUrl = null,
            dataUrl = streamUrlHls,
            plot = "En vivo"
        )
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // data is streamId
        val streamUrlHls = "$mainUrl/live/caco/caco/$data.m3u8"
        val streamUrlTs = "$mainUrl/live/caco/caco/$data.ts"

        callback.invoke(
            ExtractorLink(
                name,
                "Directo (HLS)",
                streamUrlHls,
                "",
                Qualities.Unknown.value,
                isM3u8 = true
            )
        )
        callback.invoke(
            ExtractorLink(
                name,
                "Directo (TS)",
                streamUrlTs,
                "",
                Qualities.Unknown.value,
                isM3u8 = false
            )
        )
        return true
    }
}
