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
    override var name = "Mak Prestige"
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
        
        // Select full item containers (li elements that contain both thumbnail and title)
        val items = document.select("ul.pm-ul-browse-videos li, li.col-xs-6, li.col-md-3").mapNotNull { item ->
            // Get the title link
            val link = item.selectFirst("h3 a.ellipsis, h3 a, a.ellipsis") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val title = link.attr("title").ifBlank { link.text() }
            if (title.isBlank() || href.isBlank()) return@mapNotNull null
            
            // Get thumbnail from the pm-video-thumb container (uses src, not data-src)
            val posterUrl = item.selectFirst("div.pm-video-thumb img, div.thumbnail img, img")?.let { img ->
                fixUrlNull(img.attr("src"))
            }
            
            val isMovie = href.contains("movies") || href.contains("movie")
            newMovieSearchResponse(title, href, if (isMovie) TvType.Movie else TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Prestige uses keywords parameter for search
        val searchUrl = "$mainUrl/search.php?keywords=${query.replace(" ", "+")}"
        val document = app.get(searchUrl).document
        
        // Select full item containers like in getMainPage
        return document.select("ul.pm-ul-browse-videos li, li.col-xs-6, li.col-md-3, .search-results li").mapNotNull { item ->
            val link = item.selectFirst("h3 a.ellipsis, h3 a, a.ellipsis") ?: return@mapNotNull null
            val href = fixUrl(link.attr("href"))
            val title = link.attr("title").ifBlank { link.text() }
            if (title.isBlank() || href.isBlank()) return@mapNotNull null
            
            val posterUrl = item.selectFirst("div.pm-video-thumb img, div.thumbnail img, img")?.let { img ->
                fixUrlNull(img.attr("src"))
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
        
        // Extract poster - try og:image first, then look in page
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.pm-video-thumb img, .poster img, .thumbnail img, img.img-responsive")?.let { img ->
                fixUrlNull(img.attr("src"))
            }
        
        // Extract description
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")
            ?: document.selectFirst(".description, .story, .plot")?.text()
        
        // Check if this is a series page with multiple episodes
        val episodes = mutableListOf<Episode>()
        
        // Look for episode links - try different selectors for series pages
        document.select("ul.pm-ul-browse-videos li a.ellipsis, .episodes-list a, a[href*='watch.php'], a[href*='play.php']").forEach { epLink ->
            val epHref = fixUrl(epLink.attr("href"))
            val epTitle = epLink.attr("title").ifBlank { epLink.text().trim() }
            if ((epHref.contains("watch.php") || epHref.contains("play.php")) && epTitle.isNotBlank()) {
                val epNum = Regex("""(\d+)""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epHref) {
                    name = epTitle
                    episode = epNum
                })
            }
        }
        
        // Also check for series page format (view-serie.php or series.php)
        if (url.contains("serie") || url.contains("series")) {
            document.select("li div.thumbnail a.ellipsis, li h3 a").forEach { epLink ->
                val epHref = fixUrl(epLink.attr("href"))
                val epTitle = epLink.attr("title").ifBlank { epLink.text().trim() }
                if (epHref.isNotBlank() && epTitle.isNotBlank() && !episodes.any { it.data == epHref }) {
                    val epNum = Regex("""الحلقة\s*(\d+)|حلقة\s*(\d+)|(\d+)""").find(epTitle)?.let { m ->
                        m.groupValues.drop(1).firstOrNull { it.isNotBlank() }?.toIntOrNull()
                    }
                    episodes.add(newEpisode(epHref) {
                        name = epTitle
                        episode = epNum
                    })
                }
            }
        }
        
        // If url is already a watch.php or play.php page (single episode/movie)
        return if (episodes.isEmpty() && (url.contains("watch.php") || url.contains("play.php"))) {
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
        
        // Determine the correct play URL
        val playUrl = when {
            data.contains("play.php") -> data
            data.contains("watch.php") -> data.replace("watch.php", "play.php")
            else -> {
                // Try to find the play link from the page
                val doc = app.get(data).document
                doc.selectFirst("a[href*='play.php'], a[href*='watch.php'], .play-btn, .watch-btn")?.attr("href")?.let { fixUrl(it) } ?: data
            }
        }
        
        Log.d(name, "Play URL: $playUrl")
        
        val playDoc = app.get(playUrl, referer = data).document
        var foundLinks = false
        
        // Extract video sources from server buttons with data-embed-url
        playDoc.select("button.watchButton[data-embed-url], button[data-embed-url], .server-btn[data-embed-url]").forEach { serverBtn ->
            val embedUrl = serverBtn.attr("data-embed-url")
            
            if (embedUrl.isNotBlank()) {
                val serverName = serverBtn.text().trim().ifBlank { "Server" }
                Log.d(name, "Found server: $serverName -> $embedUrl")
                
                try {
                    loadExtractor(embedUrl, playUrl, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    Log.e(name, "Error loading extractor for $embedUrl", e)
                }
            }
        }
        
        // Also check for iframes directly on the page
        playDoc.select("iframe[src]").forEach { iframe ->
            val iframeSrc = iframe.attr("src").let { src ->
                if (src.startsWith("//")) "https:$src" else fixUrl(src)
            }
            if (iframeSrc.isNotBlank() && !iframeSrc.contains("google") && !iframeSrc.contains("facebook")) {
                Log.d(name, "Found iframe: $iframeSrc")
                try {
                    loadExtractor(iframeSrc, playUrl, subtitleCallback, callback)
                    foundLinks = true
                } catch (e: Exception) {
                    Log.e(name, "Error loading iframe extractor for $iframeSrc", e)
                }
            }
        }
        
        // Fallback: Check for inline player JavaScript that might contain video URLs
        playDoc.select("script").forEach { script ->
            val scriptText = script.html()
            
            // Look for m3u8 URLs
            Regex("""['"](https?://[^'"]*\.m3u8[^'"]*)['"]""").findAll(scriptText).forEach { match ->
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
                foundLinks = true
            }
            
            // Look for mp4 URLs
            Regex("""['"](https?://[^'"]*\.mp4[^'"]*)['"]""").findAll(scriptText).forEach { match ->
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
                foundLinks = true
            }
        }
        
        return foundLinks
    }
}
