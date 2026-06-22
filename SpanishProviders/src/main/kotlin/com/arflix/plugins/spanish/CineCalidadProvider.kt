package com.arflix.plugins.spanish

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class CineCalidadProvider : MainAPI() {
    override var mainUrl = "https://www.cinecalidad.ec"
    override var name = "CineCalidad"
    override val hasMainPage = true
    override var lang = "es"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Últimos Estrenos",
        "$mainUrl/genero-de-la-pelicula/accion/page/" to "Acción",
        "$mainUrl/genero-de-la-pelicula/comedia/page/" to "Comedia",
        "$mainUrl/ver-serie/page/" to "Series Recomendadas"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data + page
        val document = app.get(url).document
        
        val home = document.select("article.item[id^=post-]").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val href = a.attr("href")
        val img = this.selectFirst("div.poster img") ?: return null
        val title = img.attr("alt")
        val posterUrl = img.attr("data-src").ifEmpty { img.attr("src") }

        return if (href.contains("/ver-pelicula/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else if (href.contains("/ver-serie/")) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.item[id^=post-]").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title = document.selectFirst(".single_left h1")?.text() ?: ""
        val poster = document.selectFirst(".single_left table img")?.attr("data-src")
        val detailsTd = document.selectFirst(".single_left td[style*=justify]")
        val overview = detailsTd?.selectFirst("p:not(:has(span))")?.text()?.trim()

        val trailer = document.selectFirst("#playeroptionsul li.dooplay_player_option_trailer[data-option]")?.attr("data-option")

        if (url.contains("/ver-serie/")) {
            val seasons = document.select(".mark-1").mapNotNull { element ->
                val numerando = element.selectFirst(".numerando")?.text() ?: return@mapNotNull null
                val seasonMatch = Regex("S(\\d+)-E(\\d+)").find(numerando)
                if (seasonMatch != null) {
                    val (season, episode) = seasonMatch.destructured
                    val a = element.selectFirst(".episodiotitle a")
                    val epTitle = a?.text() ?: "Episodio $episode"
                    val epUrl = a?.attr("href") ?: ""
                    val epPoster = element.selectFirst("div.imagen img")?.attr("data-src")
                    
                    Episode(
                        data = epUrl,
                        name = epTitle,
                        season = season.toIntOrNull(),
                        episode = episode.toIntOrNull(),
                        posterUrl = epPoster
                    )
                } else {
                    null
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, seasons) {
                this.posterUrl = posterUrl
                this.plot = overview
                addTrailer(trailer)
            }
        } else {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = overview
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        document.select("#playeroptionsul li[data-option]").forEach { element ->
            if (!element.hasClass("dooplay_player_option_trailer") && !element.text().contains("trailer", true)) {
                val serverUrl = element.attr("data-option")
                val serverName = element.text()
                
                // Cloudstream will handle the extraction if it's a known embed (e.g. Fembed, Streamtape)
                // loadExtractor(serverUrl, serverName, subtitleCallback, callback)
                
                // For now, we yield raw links
                callback.invoke(
                    ExtractorLink(
                        name,
                        "$serverName [LAT]",
                        serverUrl,
                        mainUrl,
                        Qualities.Unknown.value,
                        isM3u8 = serverUrl.contains("m3u8")
                    )
                )
            }
        }
        return true
    }
}
