package com.arflix.plugins.spanish

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import android.util.Base64

class TioPlusProvider : MainAPI() {
    override var mainUrl = "https://tioplus.app"
    override var name = "TioPlus"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/peliculas/" to "Películas",
        "$mainUrl/series/" to "Series"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        
        val home = document.select("article.item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a.itemA") ?: return null
        val href = a.attr("href")
        val title = this.selectFirst("h2")?.text()?.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")?.trim() ?: ""
        val posterUrl = this.selectFirst("img")?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: this.selectFirst("img")?.attr("src")

        return if (href.contains("/pelicula/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/api/search/$query").document
        return document.select("article.item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst("h1")?.text()?.replace(Regex("\\s*\\(\\d{4}\\)\\s*$"), "")?.trim() ?: ""
        val overview = document.selectFirst(".home__slider_content p")?.text()?.trim()
        
        var poster = document.select("img[data-src]").firstOrNull { 
            it.attr("data-src").contains("tmdb") 
        }?.attr("data-src")
        
        if (poster == null) {
            val bgStyle = document.selectFirst(".bg")?.attr("style") ?: ""
            val match = Regex("""url\(['"]?([^'")\s]+)['"]?\)""").find(bgStyle)
            poster = match?.groupValues?.get(1)
        }

        val ratingText = document.select("span:contains(Rating:)").text()
        val rating = Regex("""[0-9.]+""").find(ratingText)?.value?.toIntOrNull()

        if (url.contains("/series/")) {
            val script = document.select("script").map { it.html() }
                .firstOrNull { it.contains("const seasonsJson") } ?: ""
                
            val seasonsDataMatch = Regex("""const\s+seasonsJson\s*=\s*(\{[\s\S]*?\})\s*;""").find(script)
            
            val seasons = mutableListOf<Episode>()
            if (seasonsDataMatch != null) {
                val jsonString = seasonsDataMatch.groupValues[1]
                try {
                    val jsonObject = AppUtils.tryParseJson<Map<String, List<Map<String, Any>>>>(jsonString)
                    jsonObject?.forEach { (seasonNumStr, episodesArray) ->
                        val seasonNum = seasonNumStr.toIntOrNull() ?: return@forEach
                        
                        episodesArray.forEachIndexed { i, epObj ->
                            val epNum = (epObj["episode"] as? Number)?.toInt() ?: (i + 1)
                            val epTitle = epObj["title"] as? String ?: "Episodio $epNum"
                            val imgPath = epObj["image"] as? String ?: ""
                            val epImg = if (imgPath.isNotBlank()) "https://image.tmdb.org/t/p/w342$imgPath" else null
                            
                            seasons.add(
                                Episode(
                                    data = "$url/season/$seasonNum/episode/$epNum",
                                    name = epTitle,
                                    season = seasonNum,
                                    episode = epNum,
                                    posterUrl = epImg
                                )
                            )
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, seasons) {
                this.posterUrl = poster
                this.plot = overview
                this.rating = rating
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = overview
                this.rating = rating
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // data here is url (from load) for movies or the constructed season url for episodes
        val document = app.get(data).document
        
        document.select("li[data-server]").forEachIndexed { index, el ->
            val dataServer = el.attr("data-server")
            val serverName = el.selectFirst("span")?.text()?.trim()?.takeIf { it.isNotBlank() } ?: "Servidor ${index + 1}"
            
            if (dataServer.isNotBlank()) {
                val playerUrl = "$mainUrl/player/${Base64.encodeToString(dataServer.toByteArray(), Base64.NO_WRAP)}"
                
                try {
                    val doc = app.get(playerUrl).document
                    val iframeSrc = doc.selectFirst("iframe")?.attr("src")
                    
                    if (iframeSrc != null) {
                        loadExtractor(iframeSrc, data, subtitleCallback, callback)
                    } else {
                        val script = doc.select("script").html()
                        val match = Regex("""window\.location\.href\s*=\s*['"]([^'"]+)['"]""").find(script)
                        if (match != null) {
                            loadExtractor(match.groupValues[1], data, subtitleCallback, callback)
                        } else {
                            loadExtractor(dataServer, data, subtitleCallback, callback)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return true
    }
}
