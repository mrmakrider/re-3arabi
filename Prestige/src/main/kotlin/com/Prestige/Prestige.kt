package com.prestige

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.utils.ExtractorLink

class Prestige : MainAPI() {
    override var mainUrl = "https://hp.brstej.com"
    override var name = "برستيج"
    override var lang = "ar"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "$mainUrl/home46" to "الرئيسية",
        "$mainUrl/category.php?cat=movies2-2224" to "افلام",
        "$mainUrl/category818.php?cat=prss7-2025" to "مسلسلات برستيج",
        "$mainUrl/category.php?cat=arab8-2025" to "مسلسلات عربية",
        "$mainUrl/category.php?cat=eg8-2025" to "مسلسلات مصرية",
        "$mainUrl/category.php?cat=syy5-2025" to "مسلسلات شامية",
        "$mainUrl/category.php?cat=5a7-2024" to "مسلسلات خليجية",
        "$mainUrl/new-videos.php" to "جديد الحلقات",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1 && !request.data.contains("home46")) {
            "${request.data}&page=$page"
        } else {
            request.data
        }
        
        val document = app.get(url).document
        val items = document.select("a.ellipsis, .video-block a, .movie-block a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val title = element.attr("title").ifBlank { element.text() }
            if (title.isBlank() || href.isBlank()) return@mapNotNull null
            
            val posterUrl = element.selectFirst("img")?.let { img ->
                fixUrlNull(img.attr("data-src").ifBlank { img.attr("src") })
            }
            
            val isMovie = href.contains("movies") || !href.contains("watch.php")
            newMovieSearchResponse(title, href, if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Prestige uses a search form that posts to the same page
        val searchUrl = "$mainUrl/search.php?s=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        
        return document.select("a.ellipsis, .video-block a, .search-result a").mapNotNull { element ->
            val href = fixUrl(element.attr("href"))
            val title = element.attr("title").ifBlank { element.text() }
            if (title.isBlank() || href.isBlank()) return@mapNotNull null
            
            val posterUrl = element.selectFirst("img")?.let { img ->
                fixUrlNull(img.attr("data-src").ifBlank { img.attr("src") })
            }
            
            newMovieSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        // Extract title from the page
        val title = document.selectFirst("h1, .video-title, .watch-title")?.text()?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore(" – ")?.trim()
            ?: "Unknown"
        
        // Extract poster
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst(".poster img, .thumbnail img")?.let { img ->
                fixUrlNull(img.attr("data-src").ifBlank { img.attr("src") })
            }
        
        // Extract description
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".description, .story, .plot")?.text()
        
        // Check if this is a series page with multiple episodes
        val episodes = mutableListOf<Episode>()
        
        // Look for episode links on series overview pages
        document.select(".episodes-list a, .season-episodes a, a[href*='watch.php']").forEach { epLink ->
            val epHref = fixUrl(epLink.attr("href"))
            val epTitle = epLink.text().trim()
            if (epHref.contains("watch.php") && epTitle.isNotBlank()) {
                val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epHref) {
                    name = epTitle
                    episode = epNum
                })
            }
        }
        
        // If url is already a watch.php page (single episode/movie)
        return if (episodes.isEmpty() && url.contains("watch.php")) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            // Treat as movie if no episodes found
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.i(name, "loadLinks for: $data")
        
        // If data is a watch.php URL, we need to navigate to play.php
        val playUrl = if (data.contains("watch.php")) {
            data.replace("watch.php", "play.php")
        } else if (data.contains("play.php")) {
            data
        } else {
            // Try to find the play link from the page
            val doc = app.get(data).document
            doc.selectFirst("a[href*='play.php'], .play-btn, .watch-btn")?.attr("href")?.let { fixUrl(it) } ?: data
        }
        
        Log.d(name, "Play URL: $playUrl")
        
        val playDoc = app.get(playUrl, referer = data).document
        
        // Extract video sources from server buttons with data-embed-url
        playDoc.select(".watchButton, .server-btn, [data-embed-url]").forEach { serverBtn ->
            val embedUrl = serverBtn.attr("data-embed-url").ifBlank {
                serverBtn.attr("data-url")
            }.ifBlank {
                serverBtn.attr("href")
            }
            
            if (embedUrl.isNotBlank()) {
                val serverName = serverBtn.text().trim().ifBlank { "Server" }
                Log.d(name, "Found server: $serverName -> $embedUrl")
                
                try {
                    loadExtractor(embedUrl, playUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e(name, "Error loading extractor for $embedUrl", e)
                }
            }
        }
        
        // Also check for iframes directly on the page
        playDoc.select("iframe[src]").forEach { iframe ->
            val iframeSrc = fixUrl(iframe.attr("src"))
            if (iframeSrc.isNotBlank() && !iframeSrc.contains("google") && !iframeSrc.contains("facebook")) {
                Log.d(name, "Found iframe: $iframeSrc")
                try {
                    loadExtractor(iframeSrc, playUrl, subtitleCallback, callback)
                } catch (e: Exception) {
                    Log.e(name, "Error loading iframe extractor for $iframeSrc", e)
                }
            }
        }
        
        // Fallback: Check for inline player JavaScript that might contain video URLs
        playDoc.select("script").forEach { script ->
            val scriptText = script.html()
            
            // Look for m3u8 URLs
            Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['""]""").findAll(scriptText).forEach { match ->
                val m3u8Url = match.groupValues[1]
                Log.d(name, "Found m3u8: $m3u8Url")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - HLS",
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = playUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            
            // Look for mp4 URLs
            Regex("""['"](https?://[^'"]*\.mp4[^'"]*)['""]""").findAll(scriptText).forEach { match ->
                val mp4Url = match.groupValues[1]
                Log.d(name, "Found mp4: $mp4Url")
                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name - MP4",
                        url = mp4Url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = playUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
        
        return true
    }
}
