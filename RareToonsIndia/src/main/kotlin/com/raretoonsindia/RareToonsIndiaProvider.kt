package com.raretoonsindia

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element

class RareToonsIndiaProvider : MainAPI() {
    override var mainUrl = "https://www.rareanimes.fans"
    override var name = "RareToonsIndia"
    override var lang = "hi"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.Cartoon)

    // The site's home page is only a landing page, so the main page uses category archives.
    // Pagination format: /category/<slug>/page/<n>/
    override val mainPage = mainPageOf(
        "$mainUrl/category/animes/page/" to "Animes",
        "$mainUrl/category/popular-shows/page/" to "Popular Shows",
        "$mainUrl/category/hindi-dub/page/" to "Hindi Dub",
        "$mainUrl/category/movies/page/" to "Movies",
        "$mainUrl/category/completed/page/" to "Completed",
        "$mainUrl/category/tamil/page/" to "Tamil",
        "$mainUrl/category/telugu/page/" to "Telugu",
    )

    // One row of buttons on the site, e.g. "Hindi – [WatchMultiQuality] [HubCloud] [WatchNow]"
    data class LinkInfo(val lang: String, val label: String, val url: String)

    private val episodeRegex = Regex("""^Episode\s*(\d+)\s*[–\-:]?\s*(.*)$""", RegexOption.IGNORE_CASE)
    private val langRegex = Regex("""^(Hindi Sub|Hindi|Tamil|Telugu|Bengali|Malayalam|English|Kannada|Marathi)\b""", RegexOption.IGNORE_CASE)

    // ---------- Listing ----------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page/").document
        val items = document.select("article").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    private fun cleanTitle(raw: String): String =
        raw.replace(Regex("""(?i)\s*[–\-]?\s*(episodes?\s*)?(hindi|tamil|telugu|dubbed|download|watch)\b.*$"""), "")
            .trim()
            .ifBlank { raw.trim() }

    private fun Element.toSearchResult(): SearchResponse? {
        // The title link points at a post URL, not at /category/ or /tag/
        val a = select("a[href]").firstOrNull {
            val h = it.attr("href")
            h.startsWith(mainUrl) && !h.contains("/category/") && !h.contains("/tag/") &&
                !h.contains("#") && it.text().isNotBlank()
        } ?: return null
        val href = fixUrl(a.attr("href"))
        val rawTitle = selectFirst("h1, h2, h3, .entry-title, .title")?.text()?.ifBlank { null } ?: a.text()
        val title = cleanTitle(rawTitle)

        val img = selectFirst("img")
        val poster = img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("data-lazy-src")?.ifBlank { null }
            ?: img?.attr("src")

        val isMovie = rawTitle.contains("movie", true) || href.contains("-movie-")
        return newAnimeSearchResponse(title, href, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = poster?.let { fixUrl(it) }
            addDubStatus(DubStatus.Dubbed)
        }
    }

    // ---------- Details ----------

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: document.selectFirst("h1")?.text() ?: return null
        val title = cleanTitle(rawTitle)
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")

        val plot = document.select("p").firstOrNull { it.text().contains("Synopsis", true) }
            ?.text()?.substringAfter(":")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")
        val year = document.select("p").firstOrNull { it.text().contains("Year:") }
            ?.text()?.let { Regex("""(19|20)\d{2}""").find(it)?.value?.toIntOrNull() }
        val tags = document.select("a[rel=tag], a[rel='category tag']").map { it.text() }.distinct()

        // Walk through the post's <p> tags in order and group button rows under their "Episode NN – Title" line.
        class Ep(val number: Int, val title: String, val links: MutableList<LinkInfo> = mutableListOf())
        val eps = mutableListOf<Ep>()
        val looseLinks = mutableListOf<LinkInfo>() // links that appear before any "Episode" line (movies)

        document.select("article p").forEach { p ->
            val text = p.text().trim()
            val anchors = p.select("a[href*=codedew], a[href*=zipper]")
            val epMatch = episodeRegex.find(text)

            if (anchors.isEmpty() && epMatch != null) {
                eps.add(Ep(epMatch.groupValues[1].toInt(), epMatch.groupValues[2].trim()))
            } else if (anchors.isNotEmpty()) {
                // Row such as "Hindi – [..] [..]" or "Episode 01 – Untouched (Multi Audio) – [..] [..]"
                val lang = when {
                    epMatch != null && text.contains("Multi Audio", true) -> "Multi Audio"
                    else -> langRegex.find(text)?.value ?: "Hindi"
                }
                val infos = anchors.map { LinkInfo(lang, it.text().trim('[', ']', ' '), it.attr("href")) }
                if (epMatch != null && eps.none { it.number == epMatch.groupValues[1].toInt() }) {
                    eps.add(Ep(epMatch.groupValues[1].toInt(), epMatch.groupValues[2].trim()))
                }
                if (eps.isEmpty()) looseLinks.addAll(infos) else eps.last().links.addAll(infos)
            }
        }

        val isMovie = eps.isEmpty() || rawTitle.contains("movie", true)
        if (isMovie && (looseLinks.isNotEmpty() || eps.any { it.links.isNotEmpty() })) {
            val all = looseLinks + eps.flatMap { it.links }
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, all.toJson()) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = tags
            }
        }

        val episodes = eps.filter { it.links.isNotEmpty() }.map { ep ->
            newEpisode(ep.links.toJson()) {
                this.name = ep.title.ifBlank { "Episode ${ep.number}" }
                this.episode = ep.number
            }
        }
        val subOnly = eps.isNotEmpty() && eps.all { e -> e.links.all { it.lang.contains("Sub", true) } }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            addEpisodes(if (subOnly) DubStatus.Subbed else DubStatus.Dubbed, episodes)
        }
    }

    // ---------- Links ----------

    private val mediaRegex = Regex("""\.(m3u8|mp4|mkv)""", RegexOption.IGNORE_CASE)

    // MultQuality first (the site spells it "WatchMultQuality"), then WatchNow, then the rest
    private fun priority(label: String): Int = when {
        label.contains("mult", true) -> 0
        label.contains("watch", true) -> 1
        else -> 2
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val links = try { parseJson<List<LinkInfo>>(data) } catch (_: Exception) { return false }
        // Mega and "DL" buttons are download-only, so only streamable buttons are tried
        val usable = links
            .map { it.copy(url = fixUrl(it.url)) }
            .filterNot { it.label.contains("mega", true) || it.label.startsWith("DL", true) }
            .sortedBy { priority(it.label) }

        var found = false

        // Pass 1: open each button in a WebView (like a real browser: zipper hops -> player page -> iframe)
        for (link in usable) {
            if (found) break
            try {
                if (link.label.contains("hub", true)) {
                    // HubCloud needs an extractor registered for that host
                    val resolved = app.get(link.url, referer = "$mainUrl/").url
                    loadExtractor(resolved, "$mainUrl/", subtitleCallback) {
                        found = true
                        callback(it)
                    }
                } else {
                    if (sniffPlayer(link, callback)) {
                        found = true
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        // Pass 2 (last resort): old method - follow the redirect, then open the embed directly
        if (!found) {
            for (link in usable.filter { it.label.contains("mult", true) }) {
                try {
                    val resolved = app.get(link.url, referer = "$mainUrl/").url
                    val id = Regex("""[?&]url=([^&#]+)""").find(resolved)?.groupValues?.get(1)
                    if (resolved.contains("/multiquality/") && id != null) {
                        val embed = "https://argon.razorshell.space/embed/$id"
                        if (extractFromEmbed(embed, "https://codedew.com/", link, callback)) {
                            found = true
                            break
                        }
                    }
                } catch (_: Exception) { continue }
            }
        }
        return found
    }

    /** Opens the button link in a WebView and catches the real .m3u8/.mp4/.mkv request (also from iframes). */
    private suspend fun sniffPlayer(link: LinkInfo, callback: (ExtractorLink) -> Unit): Boolean {
        val res = app.get(
            link.url,
            referer = "$mainUrl/",
            interceptor = WebViewResolver(mediaRegex, timeout = 25_000L)
        )
        val src = res.url
        if (!mediaRegex.containsMatchIn(src)) return false

        val playerReferer =
            if (link.label.contains("mult", true)) "https://argon.razorshell.space/" else "https://codedew.com/"
        val sourceName = "$name ${link.lang} - ${link.label}"
        if (src.contains(".m3u8")) {
            M3u8Helper.generateM3u8(sourceName, src, playerReferer).forEach(callback)
        } else {
            callback(
                newExtractorLink(sourceName, sourceName, src, ExtractorLinkType.VIDEO) {
                    this.referer = playerReferer
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }

    /** Opens the embed player directly in a WebView and sniffs the real .m3u8 request. */
    private suspend fun extractFromEmbed(
        embedUrl: String,
        referer: String,
        link: LinkInfo,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val res = app.get(
            embedUrl,
            referer = referer,
            interceptor = WebViewResolver(mediaRegex, timeout = 25_000L)
        )
        val src = res.url
        if (!mediaRegex.containsMatchIn(src)) return false

        val sourceName = "$name ${link.lang} - ${link.label}"
        if (src.contains(".m3u8")) {
            M3u8Helper.generateM3u8(sourceName, src, embedUrl).forEach(callback)
        } else {
            callback(
                newExtractorLink(sourceName, sourceName, src, ExtractorLinkType.VIDEO) {
                    this.referer = embedUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }
}
