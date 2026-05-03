package eu.kanade.tachiyomi.animeextension.id.nekopoi

import androidx.preference.ListPreference
import androidx.preference.PreferenceScreen
import aniyomi.lib.cloudflareinterceptor.CloudflareInterceptor
import aniyomi.lib.doodextractor.DoodExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.utils.getPreferencesLazy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

class NekoPoi :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val name = "NekoPoi"
    override val baseUrl = "https://nekopoi.care"
    override val lang = "id"
    override val supportsLatest = true

    private val preferences by getPreferencesLazy()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    override val client by lazy {
        network.client.newBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor(CloudflareInterceptor(network.client))
            .build()
    }

    override fun headersBuilder() = super.headersBuilder()
        .add("Referer", "$baseUrl/")
        .add("Origin", baseUrl)
        .add("Accept", "application/json, text/html, */*;q=0.8")
        .add("Accept-Language", "id-ID,id;q=0.9,en-US;q=0.8")

    private val wpApi = "$baseUrl/wp-json/wp/v2"

    // URL internal agar daftar utama menampilkan judul anime/seri, bukan post episode.
    private val seriesPrefix = "/__series__/"

    private fun hentaiArchiveUrl(page: Int): String = if (page <= 1) "$baseUrl/hentai/" else "$baseUrl/hentai/page/$page/"

    // ══ POPULAR ══════════════════════════════════════════════════════════

    // Popular sengaja mengambil halaman /hentai/ karena halaman ini adalah archive anime,
    // bukan wp-json/posts yang berisi post episode.
    override fun popularAnimeRequest(page: Int) = GET(hentaiArchiveUrl(page), headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseHentaiArchivePage(response)

    // ══ LATEST ═══════════════════════════════════════════════════════════

    // Latest: pakai halaman /hentai/ dengan orderby=modified — sama seperti popular tapi sorted by update
    // Latest = halaman home nekopoi.care/ dengan selector a.nk-series-link
    private fun homeUrl(page: Int): String = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"

    override fun latestUpdatesRequest(page: Int) = GET(homeUrl(page), headers)

    override fun latestUpdatesParse(response: Response) = parseHomePage(response)

    private fun parseHomePage(response: Response): AnimesPage {
        val doc = response.asJsoup()

        val animes = doc.select("a.nk-series-link").mapNotNull { item ->
            val href = normalizeSeriesUrl(item.attr("abs:href"))
                .takeIf { it.startsWith(baseUrl) } ?: return@mapNotNull null

            // Semua nk-series-link di home mengarah ke /hentai/slug/ (series page)
            // Tidak perlu filter episode karena link ini bukan episode post

            // Title dan thumbnail ada di attribute original-title (HTML encoded)
            val tooltipHtml = item.attr("original-title")
                .replace("&quot;", "\"")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&amp;", "&")

            val tooltipDoc = org.jsoup.Jsoup.parse(tooltipHtml)

            val title = tooltipDoc.select("h2").text().trim()
                .removeNekoPoiSuffix()
                .ifBlank { item.text().trim().removeNekoPoiSuffix() }
                .ifBlank { href.trimEnd('/').substringAfterLast('/').replace('-', ' ') }

            if (title.isBlank()) return@mapNotNull null

            val thumbnail = tooltipDoc.select("img").attr("src")
                .ifEmpty { tooltipDoc.select("img").attr("abs:src") }
                .ifEmpty { null }

            SAnime.create().apply {
                this.title = title
                thumbnail_url = thumbnail
                setUrlWithoutDomain(href)
            }
        }.distinctBy { it.url.trimEnd('/') }

        val hasNextPage = doc.select("a.next.page-numbers, .pagination a.next").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    // ══ SEARCH ═══════════════════════════════════════════════════════════

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        // Search HTML situs menampilkan halaman seri/anime dengan poster asli.
        // WP posts API hanya berisi post episode, jadi thumbnail-nya sering berupa potongan video.
        if (query.isNotBlank()) {
            return GET(searchHtmlUrl(page, query), headers)
        }

        val fl = if (filters.isEmpty()) getFilterList() else filters

        // Genre filter: gunakan URL path /genres/{slug}/page/{n}/
        val genreSlug = (fl.find { it is GenreFilter } as? GenreFilter)?.toGenreSlug()
        if (!genreSlug.isNullOrEmpty()) {
            val url = if (page == 1) {
                "$baseUrl/genres/$genreSlug/"
            } else {
                "$baseUrl/genres/$genreSlug/page/$page/"
            }
            return GET(url, headers)
        }

        // Kategori via HTML /category/{slug}/ — lebih reliable dari WP API
        val typeFilter = fl.find { it is TypeFilter } as? TypeFilter
        val catId = typeFilter?.toCatId() ?: ""
        val orderFilter = fl.find { it is OrderFilter } as? OrderFilter
        val order = orderFilter?.toUriPart() ?: "date"

        val catSlug = when (catId) {
            "2" -> "hentai"
            "81" -> "3d-hentai"
            "4" -> "jav"
            "682" -> "2d-animation"
            "1" -> "jav-cosplay"
            "597" -> "jav-subtitle-indonesia"
            else -> ""
        }

        val orderQuery = if (order == "modified") "?orderby=modified" else ""

        val url = if (catSlug.isNotEmpty()) {
            if (page <= 1) {
                "$baseUrl/category/$catSlug/$orderQuery"
            } else {
                "$baseUrl/category/$catSlug/page/$page/$orderQuery"
            }
        } else {
            // Tidak ada filter kategori: pakai hentai archive
            if (page <= 1) {
                "$baseUrl/hentai/$orderQuery"
            } else {
                "$baseUrl/hentai/page/$page/$orderQuery"
            }
        }
        return GET(url, headers)
    }

    override fun searchAnimeParse(response: Response): AnimesPage {
        val path = response.request.url.encodedPath
        return when {
            path.contains("/wp-json/") -> parseWpSeriesAnimePage(response)
            else -> parseHentaiArchivePage(response)
            // Semua HTML page (/genres/, /category/, /hentai/, /search/) → parseHentaiArchivePage
        }
    }

    private fun searchHtmlUrl(page: Int, query: String): String {
        // nekopoi pakai format /search/{query}/ bukan ?s=query
        val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "%20")
        return if (page <= 1) {
            "$baseUrl/search/$encoded/"
        } else {
            "$baseUrl/search/$encoded/page/$page/"
        }
    }

    private fun searchPostsApiUrl(page: Int, query: String, perPage: Int = 50): String = "$wpApi/posts".toHttpUrl().newBuilder()
        .addQueryParameter("per_page", perPage.toString())
        .addQueryParameter("page", page.toString())
        .addQueryParameter("_embed", "1")
        .addQueryParameter("search", query)
        .addQueryParameter("orderby", "date")
        .addQueryParameter("order", "desc")
        .build()
        .toString()

    private fun parseWpSearchAsSeriesPage(response: Response, query: String): AnimesPage {
        val posts = parseWpPosts(response.body.string())
            .filter { it.matchesSearchQuery(query) }

        val animes = posts.groupAsSeriesAnime(useRealSeriesUrl = true)
            .distinctBy { it.title.seriesKey() }

        // Search fallback dibuat 1 halaman saja agar tidak memuat duplikat/latest terus-menerus.
        return AnimesPage(animes, false)
    }

    private fun resolveSeriesAnimeFromTitle(title: String): SAnime? {
        val slug = title.toSeriesSlug().takeIf { it.isNotBlank() } ?: return null
        val href = "$baseUrl/hentai/$slug/"
        return runCatching {
            client.newCall(GET(href, headers)).execute().use { res ->
                if (!res.isSuccessful) return@runCatching null
                val doc = res.asJsoup()
                if (doc.select(".nk-series-detail, .nk-series-info").isEmpty()) return@runCatching null
                parseHtmlDetails(doc).apply { setUrlWithoutDomain(href) }
            }
        }.getOrNull()
    }

    // ══ ANIME DETAIL ══════════════════════════════════════════════════════

    override fun animeDetailsRequest(anime: SAnime): Request = if (anime.url.startsWith(seriesPrefix)) {
        GET(seriesPostsApiUrl(seriesQueryFromUrl(anime.url), perPage = 30), headers)
    } else {
        GET("$baseUrl${anime.url}", headers)
    }

    override fun animeDetailsParse(response: Response): SAnime {
        val body = response.body.string()
        return if (body.trimStart().startsWith("[")) {
            parseSeriesDetails(body, response.request.url.queryParameter("search").orEmpty())
        } else {
            parseHtmlDetails(Jsoup.parse(body, response.request.url.toString()))
        }
    }

    private fun parseSeriesDetails(body: String, query: String): SAnime {
        val posts = parseWpPosts(body)
            .filter { it.matchesSeries(query) }
            .ifEmpty { parseWpPosts(body) }
        val first = posts.firstOrNull()

        return SAnime.create().apply {
            title = query.ifBlank { first?.title?.toSeriesTitle().orEmpty() }.ifBlank { first?.title.orEmpty() }
            thumbnail_url = first?.thumbnail
            description = buildString {
                first?.excerpt?.takeIf { it.isNotBlank() }?.let { appendLine(it) }
                posts.firstOrNull()?.title?.takeIf { it.isNotBlank() }?.let {
                    if (isNotBlank()) appendLine()
                    appendLine("Episode terbaru: $it")
                }
                if (posts.isNotEmpty()) appendLine("Jumlah episode ditemukan: ${posts.size}")
            }.trim()
            genre = posts.flatMap { it.terms }.distinct().joinToString()
            status = SAnime.COMPLETED
        }
    }

    private fun parseHtmlDetails(doc: Document): SAnime {
        val meta = parseSeriesMeta(doc)
        val producer = meta["produser"].orEmpty()
        val synopsis = doc.select(".nk-series-synopsis > p")
            .joinToString("\n\n") { it.text().trim() }
            .ifBlank { doc.select("div.sinopsis p, div.synopsis p").joinToString("\n\n") { it.text().trim() } }
            .ifBlank { doc.select("meta[name=description], meta[property=og:description]").firstOrNull()?.attr("content").orEmpty() }

        return SAnime.create().apply {
            title = doc.select("meta[property=og:title]").attr("content")
                .ifEmpty { doc.select("h1.entry-title, h1.post-title, header.entry-header h1, .nk-series-info h2").text() }
                .replace("\u2013 NekoPoi", "").trim()
            thumbnail_url = doc.select("meta[property=og:image]").attr("content")
                .ifEmpty { extractBackgroundImage(doc.selectFirst(".nk-series-poster")?.attr("style").orEmpty()).orEmpty() }
                .ifEmpty { doc.select("img.wp-post-image, div.entry-content img").attr("abs:src") }
            author = producer
            description = synopsis.trim()
            genre = doc.select(".nk-series-meta-list li:contains(Genre) a, a[rel=tag], a[href*='/tag/'], a[href*='/genres/'], a[href*='/genre/'], div.genres a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()
                .joinToString()
                .ifBlank { meta["genre"].orEmpty() }
            status = when (meta["status"]?.lowercase()) {
                "ongoing" -> SAnime.ONGOING
                "completed", "complete", "tamat" -> SAnime.COMPLETED
                else -> if (doc.text().contains("Ongoing", ignoreCase = true)) SAnime.ONGOING else SAnime.COMPLETED
            }
        }
    }

    private fun parseSeriesMeta(doc: Document): Map<String, String> {
        return doc.select(".nk-series-meta-list li").mapNotNull { li ->
            val labelRaw = li.selectFirst("b")?.text()?.replace(":", "")?.trim()
                ?: return@mapNotNull null
            val label = labelRaw.lowercase()
            val value = li.text()
                .replace(Regex("^\\s*${Regex.escape(labelRaw)}\\s*:??\\s*", RegexOption.IGNORE_CASE), "")
                .trim()
            label to value
        }.toMap()
    }

    private fun parseSearchHtmlPage(response: Response, query: String = ""): AnimesPage {
        val doc = response.asJsoup()
        val q = query.normalizeSearchKey()
        val animes = doc.select("a.nk-search-item").mapNotNull { item ->
            val href = normalizeSeriesUrl(item.attr("abs:href")).takeIf { it.startsWith(baseUrl) }
                ?: return@mapNotNull null
            if (!isSeriesDetailUrl(href)) return@mapNotNull null

            val titleText = item.selectFirst(".nk-search-info h2")?.text()?.trim().orEmpty()
            val title = titleText.ifBlank { item.attr("title").trim() }
                .ifBlank { href.substringBeforeLast("/").substringAfterLast("/").replace('-', ' ').trim() }
                .removeNekoPoiSuffix()
            if (title.isBlank()) return@mapNotNull null

            val slug = href.substringBeforeLast("/").substringAfterLast("/").normalizeSearchKey()
            val titleKey = title.normalizeSearchKey()
            if (q.isNotBlank() && q !in titleKey && q !in slug) return@mapNotNull null

            SAnime.create().apply {
                this.title = title
                thumbnail_url = extractBackgroundImage(
                    item.selectFirst(".nk-search-thumb")?.attr("style").orEmpty(),
                )
                description = item.selectFirst(".nk-search-desc")?.text()?.trim().orEmpty()
                genre = item.selectFirst(".nk-search-genres")?.text()?.trim().orEmpty()
                setUrlWithoutDomain(href)
            }
        }.distinctBy { it.title.seriesKey() }

        // Search HTML sering mengulang item di page berikutnya. Matikan paging untuk search.
        return AnimesPage(animes, false)
    }

    private fun normalizeSeriesUrl(url: String): String = url.substringBefore('#').substringBefore('?').trimEnd('/') + "/"

    private fun String.normalizeSearchKey(): String = lowercase()
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")

    private fun String.toSeriesSlug(): String = toSeriesTitle()
        .lowercase()
        .replace("&", " and ")
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

    private fun isSeriesDetailUrl(url: String): Boolean {
        val httpUrl = url.toHttpUrlOrNull() ?: return false
        val segments = httpUrl.pathSegments.filter { it.isNotBlank() }
        return segments.size == 2 && segments[0] == "hentai"
    }

    private fun parseHentaiArchivePage(response: Response): AnimesPage {
        val doc = response.asJsoup()

        // Parse semua hasil terlebih dahulu
        val allItems = doc.select("a.nk-search-item").mapNotNull { item ->
            val href = normalizeSeriesUrl(item.attr("abs:href"))
                .takeIf { it.startsWith(baseUrl) } ?: return@mapNotNull null
            val titleText = item.selectFirst(".nk-search-info h2")?.text()?.trim().orEmpty()
            val title = titleText.removeNekoPoiSuffix()
                .ifBlank { item.attr("title").trim() }
                .ifBlank { href.substringBeforeLast("/").substringAfterLast("/").replace('-', ' ').trim() }
            if (title.isBlank()) return@mapNotNull null

            SAnime.create().apply {
                this.title = title
                thumbnail_url = extractBackgroundImage(
                    item.selectFirst(".nk-search-thumb")?.attr("style").orEmpty(),
                )
                description = item.selectFirst(".nk-search-desc")?.text()?.trim().orEmpty()
                genre = item.selectFirst(".nk-search-genres")?.text()?.trim().orEmpty()
                setUrlWithoutDomain(href)
            }
        }

        // Pisahkan halaman seri (/hentai/slug/) dan post episode/standalone
        val seriesPages = allItems.filter { isSeriesDetailUrl("$baseUrl${it.url}") }
        val episodePosts = allItems.filter { !isSeriesDetailUrl("$baseUrl${it.url}") }

        // Kata kunci yang menandai post adalah episode (bukan konten standalone)
        val episodeKeywords = listOf(
            "episode-",
            "-episode-",
            "subtitle-indonesia",
            "sub-indo",
            "-ep-",
            "-eps-",
        )

        // Slug dari tiap halaman seri yang ada di halaman ini
        val seriesSlugs = seriesPages.map { anime ->
            anime.url.trimEnd('/').substringAfterLast('/')
        }.filter { it.isNotBlank() }.toSet()

        // Post standalone: bukan episode keyword DAN tidak ada seri yang cocok
        val standaloneOnly = episodePosts.filter { post ->
            val postSlug = post.url.trimEnd('/').substringAfterLast('/')

            // Filter ketat: jika slug mengandung kata episode → pasti episode, skip
            val isEpisode = episodeKeywords.any { postSlug.contains(it) }
            if (isEpisode) return@filter false

            // Jika ada halaman seri yang slug-nya adalah awalan dari post ini → skip
            seriesSlugs.none { seriesSlug ->
                seriesSlug.isNotBlank() && postSlug.startsWith(seriesSlug)
            }
        }

        // Gabungkan: halaman seri dulu, lalu post standalone
        val animes = (seriesPages + standaloneOnly)
            .distinctBy { it.url.trimEnd('/') }

        val hasNextPage = doc.select("a.next.page-numbers, .pagination a.next").isNotEmpty()
        return AnimesPage(animes, hasNextPage)
    }

    private fun extractBackgroundImage(style: String): String? {
        val raw = Regex("""url\(['\"]?([^'\")]+)['\"]?\)""")
            .find(style)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?: return null

        return when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$baseUrl$raw"
            else -> raw
        }
    }

    private fun parseWpSeriesAnimePage(response: Response): AnimesPage {
        val posts = parseWpPosts(response.body.string())
        val animes = posts.groupAsSeriesAnime()
        return AnimesPage(animes, posts.size >= 100)
    }

    // ══ EPISODE LIST ══════════════════════════════════════════════════════

    override fun episodeListRequest(anime: SAnime): Request = if (anime.url.startsWith(seriesPrefix)) {
        GET(seriesPostsApiUrl(seriesQueryFromUrl(anime.url), perPage = 100), headers)
    } else {
        GET("$baseUrl${anime.url}", headers)
    }

    override fun episodeListParse(response: Response): List<SEpisode> {
        val body = response.body.string()
        return if (body.trimStart().startsWith("[")) {
            parseSeriesEpisodes(body, response.request.url.queryParameter("search").orEmpty())
        } else {
            parseHtmlEpisodes(Jsoup.parse(body, response.request.url.toString()), response.request.url.toString())
        }
    }

    private fun parseSeriesEpisodes(body: String, query: String): List<SEpisode> {
        val posts = parseWpPosts(body)
            .filter { it.matchesSeries(query) }
            .ifEmpty { parseWpPosts(body) }

        return posts.mapIndexed { idx, post ->
            SEpisode.create().apply {
                name = post.title.ifEmpty { "Episode ${idx + 1}" }
                setUrlWithoutDomain(post.link)
                episode_number = epNumberFrom(post.title, idx)
                date_upload = System.currentTimeMillis()
            }
        }
            .distinctBy { it.url }
            .sortedWith(compareByDescending<SEpisode> { it.episode_number }.thenBy { it.name })
    }

    private fun parseHtmlEpisodes(doc: Document, pageUrl: String): List<SEpisode> {
        val episodes = mutableListOf<SEpisode>()

        val epLinks = doc.select(
            "a.nk-episode-card, " +
                ".nk-episode-grid a[href], " +
                "div.entry-content a[href*='episode'], " +
                "div.entry-content a[href*='subtitle-indonesia'], " +
                "ul.episode-list li a, div.eps-list a, div.daftarepisode a",
        ).filter { el ->
            val href = el.attr("abs:href")
            href.startsWith(baseUrl) && !href.contains("#") &&
                !href.contains("/tag/") && !href.contains("/genre/") && !href.contains("/genres/") &&
                !href.contains("/page/") && !href.contains("/hentai/")
        }.distinctBy { it.attr("abs:href") }

        if (epLinks.isNotEmpty()) {
            epLinks.forEachIndexed { idx, el ->
                val titleText = el.selectFirst(".nk-episode-card-title")?.text()?.trim().orEmpty()
                val badgeText = el.selectFirst(".nk-episode-badge")?.text()?.trim().orEmpty()
                val dateText = el.selectFirst(".nk-episode-card-date")?.ownText()?.trim()
                    ?: el.selectFirst(".nk-episode-card-date")?.text()?.replace("calendar-alt", "")?.trim()
                val t = titleText.ifBlank { el.text().trim() }.ifBlank { badgeText }
                episodes += SEpisode.create().apply {
                    name = t.ifEmpty { "Episode ${idx + 1}" }
                    setUrlWithoutDomain(el.attr("abs:href"))
                    episode_number = epNumberFrom("$t $badgeText", idx)
                    date_upload = parseIndonesianDate(dateText) ?: System.currentTimeMillis()
                }
            }
        } else {
            val hasPlayer = doc.select("iframe[src], iframe[data-src], video source, div#player")
                .isNotEmpty()
            if (hasPlayer) {
                val t = doc.select("h1.entry-title").text()
                    .replace(" \u2013 NekoPoi", "").trim()
                episodes += SEpisode.create().apply {
                    name = t.ifEmpty { "Episode 1" }
                    setUrlWithoutDomain(pageUrl.replace(baseUrl, ""))
                    episode_number = epNumberFrom(t, 0)
                    date_upload = System.currentTimeMillis()
                }
            } else {
                runCatching {
                    val kw = doc.select("h1.entry-title").text()
                        .split(" ").take(3).joinToString("+")
                    val res = client.newCall(
                        GET("$wpApi/posts?search=$kw&per_page=20&_embed=1", headers),
                    ).execute()
                    parseWpPosts(res.body.string())
                        .filter {
                            it.link.contains("episode", ignoreCase = true) ||
                                it.link.contains("subtitle-indonesia", ignoreCase = true)
                        }
                        .forEachIndexed { idx, post ->
                            episodes += SEpisode.create().apply {
                                name = post.title.ifEmpty { "Episode ${idx + 1}" }
                                setUrlWithoutDomain(post.link)
                                episode_number = epNumberFrom(post.title, idx)
                                date_upload = System.currentTimeMillis()
                            }
                        }
                }
            }
        }

        return episodes.distinctBy { it.url }.sortedBy { it.episode_number }.reversed()
            .ifEmpty {
                listOf(
                    SEpisode.create().apply {
                        name = "Episode 1"
                        setUrlWithoutDomain(pageUrl.replace(baseUrl, ""))
                        episode_number = 1f
                    },
                )
            }
    }

    private fun parseIndonesianDate(text: String?): Long? {
        val clean = text?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            SimpleDateFormat("dd MMMM yyyy", Locale("id", "ID")).parse(clean)?.time
        }.getOrNull()
    }

    private fun epNumberFrom(title: String, fallback: Int): Float {
        listOf(
            Regex("""[Ee]pisode\s*(\d+(\.\d+)?)"""),
            Regex("""[Ee]p\.?\s*(\d+(\.\d+)?)"""),
            Regex("""\b(\d{1,3})\b"""),
        ).forEach { p ->
            p.find(title)?.groupValues?.get(1)?.toFloatOrNull()?.let { return it }
        }
        return (fallback + 1).toFloat()
    }

    // ══ VIDEO LIST ════════════════════════════════════════════════════════

    override fun videoListRequest(episode: SEpisode) = GET("$baseUrl${episode.url}", headers)

    override fun videoListParse(response: Response): List<Video> {
        val doc = response.asJsoup()
        val prefQ = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT)!!
        val videos = mutableListOf<Video>()

        // Kumpulkan semua iframe src yang valid (kecuali chat widget)
        val skipDomains = listOf("chatango.com", "disqus.com", "facebook.com", "twitter.com")
        val iframeSrcs = doc.select("iframe").mapNotNull { el ->
            el.attr("src").ifEmpty { el.attr("data-src") }.trim()
                .takeIf { src -> src.startsWith("http") && skipDomains.none { src.contains(it) } }
        }

        for (src in iframeSrcs) {
            runCatching {
                when {
                    // Server 3: StreamPoi → HLS m3u8
                    src.contains("streampoi") -> videos += extractStreamPoi(src)
                    // Server 1 & 2: PlayMogo → MP4 / HLS
                    src.contains("playmogo") -> videos += extractPlayMogo(src, response.request.url.toString())
                }
            }
        }

        return videos.distinctBy { it.url }.sortedByDescending { qualityScore(it.quality, prefQ) }
    }

    // ══ PLAYMOGO EXTRACTOR (Server 1 & 2) ════════════════════════════════
    // URL pola: https://playmogo.com/e/{id}
    // Fetch halaman embed → cari video URL di HTML / packed JS

    private val pmHeaders by lazy {
        // PlayMogo cek referrer - harus dari halaman nekopoi.care
        headersBuilder()
            .set("Referer", "$baseUrl/")
            .set("Origin", baseUrl)
            .build()
    }

    private fun pmHeadersWithEpisodeRef(episodeUrl: String) = headersBuilder()
        .set("Referer", episodeUrl)
        .set("Origin", baseUrl)
        .build()

    private fun extractPlayMogo(embedUrl: String, episodeUrl: String = ""): List<Video> {
        // PlayMogo adalah reskin DoodStream
        // CDN: ticdn.net / doodcdn.io, player: VideoJS dengan branding DoodStream

        // ── Strategi 1: DoodExtractor (primary) ──────────────────────
        val doodVideos = runCatching {
            DoodExtractor(client).videosFromUrl(embedUrl, quality = "PlayMogo")
        }.getOrDefault(emptyList())
        if (doodVideos.isNotEmpty()) return doodVideos

        // ── Strategi 2: Manual pass_md5 flow ─────────────────────────
        val refHeaders = if (episodeUrl.isNotBlank()) pmHeadersWithEpisodeRef(episodeUrl) else pmHeaders
        val rawHtml = runCatching {
            client.newCall(GET(embedUrl, refHeaders)).execute().body.string()
        }.getOrNull() ?: return emptyList()

        val videos = mutableListOf<Video>()

        runCatching {
            val embedHost = embedUrl.toHttpUrl().run { "$scheme://$host" }
            val passMd5Path = Regex("""['"]([^'"]*pass_md5[^'"]*)['"]""")
                .find(rawHtml)?.groupValues?.get(1)
                ?: Regex("""/pass_md5/[^'"&\s]+""").find(rawHtml)?.value

            if (passMd5Path != null) {
                val passUrl = if (passMd5Path.startsWith("http")) {
                    passMd5Path
                } else {
                    "$embedHost$passMd5Path"
                }
                val passHeaders = headersBuilder()
                    .set("Referer", embedUrl)
                    .set("Origin", embedHost)
                    .build()
                val baseVideoUrl = client.newCall(GET(passUrl, passHeaders))
                    .execute().body.string().trim()
                if (baseVideoUrl.startsWith("http")) {
                    val token = Regex("""token=([^&'"<>\s]+)""").find(rawHtml)
                        ?.groupValues?.get(1).orEmpty()
                    val expiry = Regex("""expiry=([^&'"<>\s]+)""").find(rawHtml)
                        ?.groupValues?.get(1).orEmpty()
                    val rand = buildString { repeat(10) { append(('a'..'z').random()) } }
                    val finalUrl = "${baseVideoUrl}$rand?token=$token&expiry=$expiry"
                    videos += Video(finalUrl, "PlayMogo", finalUrl, passHeaders)
                }
            }
        }

        // ── Strategi 3: Scan HTML / packed JS ────────────────────────
        if (videos.isEmpty()) {
            val unpacked = unpackFromHtml(rawHtml)
            val searchIn = unpacked ?: rawHtml
            listOf(
                Regex(""""(?:file|src)"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)""""),
                Regex(""""(?:file|src)"\s*:\s*"(https?://[^"]+\.mp4[^"]*)""""),
                Regex("""(https?://[^\s"'<>\\]+ticdn\.net[^\s"'<>\\]+)"""),
                Regex("""(https?://[^\s"'<>\\]+doodcdn[^\s"'<>\\]+\.mp4[^\s"'<>\\]*)"""),
            ).forEach { pattern ->
                pattern.findAll(searchIn).forEach { m ->
                    val url = m.groupValues[1].replace("\\/", "/")
                    if (!url.contains(".vtt") && url.startsWith("http")) {
                        val q = extractQualityFromUrl(url) ?: "PlayMogo"
                        videos += Video(url, "PlayMogo - $q", url, refHeaders)
                    }
                }
            }
        }

        return videos.distinctBy { it.url }
    }

    private fun extractQualityFromUrl(url: String): String? = when {
        url.contains("1080") -> "1080p"
        url.contains("720") -> "720p"
        url.contains("480") -> "480p"
        url.contains("360") -> "360p"
        url.contains("m3u8") -> "HLS"
        url.contains("mp4") -> "MP4"
        else -> null
    }

    // ══ STREAMPOI EXTRACTOR (Server 3) ════════════════════════════════════

    private val spHeaders by lazy {
        headersBuilder()
            .set("Referer", "https://streampoi.com/")
            .set("Origin", "https://streampoi.com")
            .build()
    }

    private fun extractStreamPoi(embedUrl: String): List<Video> {
        val rawHtml = runCatching {
            client.newCall(GET(embedUrl, spHeaders)).execute().body.string()
        }.getOrNull() ?: return emptyList()

        // ── Coba unpack packed JS ──────────────────────────────────────
        val unpacked = unpackFromHtml(rawHtml)
        if (unpacked != null) {
            val videos = findM3u8InString(unpacked)
            if (videos.isNotEmpty()) return videos
        }

        // ── Fallback: scan raw HTML langsung ───────────────────────────
        return findM3u8InString(rawHtml)
    }

    // ── Cari dan unpack eval(function(p,a,c,k,e,d)) dari HTML ─────────
    //
    // Pendekatan: iterasi script tags, temukan yang mengandung eval packed,
    // lalu ekstrak komponen dengan string parsing (bukan regex kompleks)
    private fun unpackFromHtml(html: String): String? {
        // Pecah per script tag dan cari yang mengandung eval packed
        val evalMarker = "eval(function(p,a,c,k,e,"
        var searchFrom = 0

        while (true) {
            val evalIdx = html.indexOf(evalMarker, searchFrom)
            if (evalIdx < 0) break
            searchFrom = evalIdx + 1

            runCatching {
                // Temukan }(' yang menandai awal encoded string
                val bodyEnd = html.indexOf("}('", evalIdx)
                if (bodyEnd < 0) return@runCatching

                val encodedStart = bodyEnd + 3 // lewati }('

                // Ekstrak encoded string dengan menghitung quote yang tidak di-escape
                var i = encodedStart
                val sb = StringBuilder()
                while (i < html.length) {
                    val ch = html[i]
                    if (ch == '\'' && (i == 0 || html[i - 1] != '\\')) break
                    sb.append(ch)
                    i++
                }
                val encoded = sb.toString()
                if (encoded.isEmpty()) return@runCatching

                // Setelah encoded string: ,'RADIX','COUNT','KEYS'
                val afterEncoded = html.substring(i + 1) // skip penutup '

                // Ekstrak radix (angka pertama)
                val radixMatch = Regex("""^\s*,\s*(\d+)""").find(afterEncoded)
                    ?: return@runCatching
                val radix = radixMatch.groupValues[1].toIntOrNull() ?: return@runCatching

                // Ekstrak keys string (antara dua tanda kutip setelah count)
                val afterRadix = afterEncoded.substring(radixMatch.value.length)
                val keysMatch = Regex("""^\s*,\s*\d+\s*,\s*'([^']*)'""").find(afterRadix)
                    ?: return@runCatching
                val keys = keysMatch.groupValues[1].split("|")

                // Unpack
                val result = unpackJs(encoded, radix, keys)

                // Validasi: hasil unpack harus mengandung URL atau jwplayer
                if (result.contains("http") || result.contains("jwplayer")) {
                    return result
                }
            }
        }
        return null
    }

    // ── JS Unpacker: p,a,c,k,e,d algorithm ───────────────────────────
    //
    // Setiap token angka (dalam basis radix) dalam encoded string
    // diganti dengan keys[parseInt(token, radix)] jika tidak kosong
    private fun unpackJs(encoded: String, radix: Int, keys: List<String>): String {
        val lookup = HashMap<String, String>(keys.size * 2)
        keys.forEachIndexed { idx, key ->
            if (key.isNotEmpty()) {
                lookup[idx.toString(radix)] = key
            }
        }
        // Ganti token \b WORD \b dengan lookup value
        return Regex("""\b(\w+)\b""").replace(encoded) { mr ->
            lookup[mr.value] ?: mr.value
        }
    }

    // ── Cari semua URL m3u8 dalam string ─────────────────────────────
    private fun findM3u8InString(source: String): List<Video> {
        val videos = mutableListOf<Video>()

        // Pola 1: "file":"URL.m3u8"
        Regex(""""file"\s*:\s*"(https?://[^"]+\.m3u8[^"]*)"""")
            .findAll(source).forEach { m ->
                val url = m.groupValues[1].replace("\\/", "/")
                if (!url.contains(".vtt")) {
                    videos += Video(url, "StreamPoi HLS", url, spHeaders)
                }
            }

        // Pola 2: 'URL.m3u8'
        if (videos.isEmpty()) {
            Regex("""'(https?://[^']+\.m3u8[^']*)'""")
                .findAll(source).forEach { m ->
                    val url = m.groupValues[1]
                    if (!url.contains(".vtt")) {
                        videos += Video(url, "StreamPoi HLS", url, spHeaders)
                    }
                }
        }

        // Pola 3: URL mentah streamruby
        if (videos.isEmpty()) {
            Regex("""(https?://[^\s"'<>\\]+streamruby[^\s"'<>\\]+\.m3u8[^\s"'<>\\]*)""")
                .findAll(source).forEach { m ->
                    val url = m.groupValues[1].replace("\\/", "/")
                    videos += Video(url, "StreamPoi HLS", url, spHeaders)
                }
        }

        return videos.distinctBy { it.url }
    }

    private fun qualityScore(quality: String, pref: String): Int {
        val q = quality.lowercase()
        val p = pref.lowercase()
        return when {
            q.contains(p) -> 100
            q.contains("1080") -> 40
            q.contains("720") -> 30
            q.contains("480") -> 20
            q.contains("360") -> 10
            else -> 0
        }
    }

    // ══ WP POST → SERIES HELPERS ════════════════════════════════════════

    private data class WpPost(
        val title: String,
        val link: String,
        val thumbnail: String?,
        val excerpt: String,
        val terms: List<String>,
    )

    private fun postsApiUrl(
        page: Int,
        perPage: Int,
        orderBy: String,
    ): String = "$wpApi/posts".toHttpUrl().newBuilder()
        .addQueryParameter("per_page", perPage.toString())
        .addQueryParameter("page", page.toString())
        .addQueryParameter("_embed", "1")
        .addQueryParameter("orderby", orderBy)
        .addQueryParameter("order", "desc")
        .build()
        .toString()

    private fun seriesPostsApiUrl(query: String, perPage: Int): String = "$wpApi/posts".toHttpUrl().newBuilder()
        .addQueryParameter("per_page", perPage.toString())
        .addQueryParameter("page", "1")
        .addQueryParameter("_embed", "1")
        .addQueryParameter("search", query)
        .addQueryParameter("orderby", "date")
        .addQueryParameter("order", "desc")
        .build()
        .toString()

    private fun parseWpPosts(body: String): List<WpPost> = runCatching {
        json.parseToJsonElement(body).jsonArray.map { it.toWpPost() }
    }.getOrDefault(emptyList())

    private fun JsonElement.toWpPost(): WpPost {
        val obj = jsonObject
        val rawTitle = obj["title"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content.orEmpty()
        val rawExcerpt = obj["excerpt"]?.jsonObject?.get("rendered")?.jsonPrimitive?.content.orEmpty()
        val link = obj["link"]?.jsonPrimitive?.content.orEmpty()
        val thumb = obj["_embedded"]?.jsonObject
            ?.get("wp:featuredmedia")?.jsonArray?.firstOrNull()?.jsonObject
            ?.let { m ->
                m["media_details"]?.jsonObject?.get("sizes")?.jsonObject
                    ?.let { s -> (s["medium_large"] ?: s["medium"] ?: s["thumbnail"]) }
                    ?.jsonObject?.get("source_url")?.jsonPrimitive?.content
                    ?: m["source_url"]?.jsonPrimitive?.content
            }
        val terms = obj["_embedded"]?.jsonObject
            ?.get("wp:term")?.jsonArray
            ?.flatMap { group ->
                group.jsonArray.mapNotNull { term ->
                    term.jsonObject["name"]?.jsonPrimitive?.content?.cleanHtml()?.takeIf { it.isNotBlank() }
                }
            }
            .orEmpty()

        return WpPost(
            title = rawTitle.cleanHtml().removeNekoPoiSuffix(),
            link = link,
            thumbnail = thumb,
            excerpt = rawExcerpt.cleanHtml(),
            terms = terms,
        )
    }

    private fun List<WpPost>.groupAsSeriesAnime(useRealSeriesUrl: Boolean = false): List<SAnime> {
        val grouped = linkedMapOf<String, MutableList<WpPost>>()
        forEach { post ->
            val key = post.title.toSeriesTitle().seriesKey()
            if (key.isNotBlank()) grouped.getOrPut(key) { mutableListOf() } += post
        }

        return grouped.values.mapNotNull { group ->
            val first = group.firstOrNull() ?: return@mapNotNull null
            val seriesTitle = first.title.toSeriesTitle().ifBlank { first.title }
            SAnime.create().apply {
                title = seriesTitle
                thumbnail_url = first.thumbnail
                description = first.excerpt
                val realUrl = "$baseUrl/hentai/${seriesTitle.toSeriesSlug()}/"
                setUrlWithoutDomain(if (useRealSeriesUrl) realUrl else seriesUrl(seriesTitle))
            }
        }.distinctBy { it.url }
    }

    private fun WpPost.matchesSearchQuery(query: String): Boolean {
        val q = query.normalizeSearchKey()
        if (q.isBlank()) return true

        val words = q.split(" ").filter { it.length >= 2 }
        if (words.isEmpty()) return false

        val haystack = listOf(title, title.toSeriesTitle(), link.substringBeforeLast("/").substringAfterLast("/"))
            .joinToString(" ")
            .normalizeSearchKey()

        return words.all { it in haystack }
    }

    private fun WpPost.matchesSeries(query: String): Boolean {
        val q = query.seriesKey()
        if (q.isBlank()) return true
        val titleKey = title.seriesKey()
        val postSeriesKey = title.toSeriesTitle().seriesKey()
        return postSeriesKey == q || titleKey.contains(q) || q.contains(postSeriesKey)
    }

    private fun seriesUrl(title: String): String = seriesPrefix + URLEncoder.encode(title, Charsets.UTF_8.name())

    private fun seriesQueryFromUrl(url: String): String = URLDecoder.decode(url.removePrefix(seriesPrefix), Charsets.UTF_8.name())

    private fun String.cleanHtml(): String = Jsoup.parse(this).text().trim()

    private fun String.removeNekoPoiSuffix(): String = replace(Regex("""\s*[–|-]\s*NekoPoi.*$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""\s*\|\s*NekoPoi.*$""", RegexOption.IGNORE_CASE), "")
        .trim()

    private fun String.toSeriesTitle(): String {
        var t = cleanHtml().removeNekoPoiSuffix()

        // Hilangkan label rilis dari judul episode, misalnya:
        // [UNCENSORED] Judul Anime Episode 1 -> Judul Anime
        t = t
            .replace(Regex("""(?i)^\s*\[[^\]]*(?:uncensored|censored|sub|dub|raw)[^\]]*]\s*"""), "")
            .replace(Regex("""(?i)^\s*(?:uncensored|censored)\s*[-–:]?\s*"""), "")
            .trim()

        // Hilangkan penanda episode di judul post agar daftar utama menjadi daftar anime/seri.
        val patterns = listOf(
            Regex("""(?i)\b(?:episode|eps?|ep)\s*\d+(?:\.\d+)?\b.*$"""),
            Regex("""(?i)\b(?:batch|complete)\b.*$"""),
            Regex("""(?i)\b(?:subtitle\s*indonesia|sub\s*indo|sub\s*id)\b.*$"""),
            Regex("""(?i)\s*[-–]\s*\d{1,3}\s*$"""),
            Regex("""(?i)\s*\[\s*\d{1,3}\s*]\s*$"""),
        )
        patterns.forEach { t = t.replace(it, "").trim() }

        return t.ifBlank { cleanHtml().removeNekoPoiSuffix() }
    }

    private fun String.seriesKey(): String = toSeriesTitle()
        .lowercase()
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()

    // ══ FILTERS ══════════════════════════════════════════════════════════

    override fun getFilterList() = AnimeFilterList(
        AnimeFilter.Header("Filter tidak bisa dikombinasikan dengan Search"),
        AnimeFilter.Separator(),
        TypeFilter(),
        GenreFilter(),
        AnimeFilter.Separator(),
        OrderFilter(),
    )

    class TypeFilter :
        UriPartFilter(
            "Tipe / Kategori",
            arrayOf(
                Pair("Semua", ""),
                Pair("Hentai", "2"), // 2751 posts
                Pair("3D Hentai", "81"), // 2055 posts
                Pair("JAV", "4"), // 1711 posts
                Pair("2D Animation", "682"), // 1577 posts
                Pair("JAV Cosplay", "1"), // 289 posts
                Pair("JAV Subtitle Indonesia", "597"), // 61 posts
            ),
        ) {
        fun toCatId() = vals[state].second
    }

    // Genre asli dari /genres/{slug}/ — custom taxonomy nekopoi.care
    class GenreFilter :
        UriPartFilter(
            "Genre",
            arrayOf(
                Pair("<Pilih Genre>", ""),
                Pair("Action", "action"),
                Pair("Ahegao", "ahegao"),
                Pair("Anal", "anal"),
                Pair("Armpit", "armpit"),
                Pair("BDSM", "bdsm"),
                Pair("Big Oppai", "big-oppai"),
                Pair("Blackmail", "blackmail"),
                Pair("Blonde", "blonde"),
                Pair("Blowjob", "blowjob"),
                Pair("Bondage", "bondage"),
                Pair("Cheating", "cheating"),
                Pair("Comedy", "comedy"),
                Pair("Creampie", "creampie"),
                Pair("Dark Skin", "dark-skin"),
                Pair("DILF", "dilf"),
                Pair("Elf", "elf"),
                Pair("Exhibitionist", "exhibitionist"),
                Pair("Fellatio", "fellatio"),
                Pair("Female Monster", "female-monster"),
                Pair("Femdom", "femdom"),
                Pair("Footjob", "footjob"),
                Pair("Forced", "forced"),
                Pair("Furry", "furry"),
                Pair("Futanari", "futanari"),
                Pair("Gangbang", "gangbang"),
                Pair("Gore", "gore"),
                Pair("Gyaru", "gyaru"),
                Pair("Handjob", "handjob"),
                Pair("Harem", "harem"),
                Pair("Horror", "horror"),
                Pair("Housewife", "housewife"),
                Pair("Humiliation", "humiliation"),
                Pair("Hypnotize", "hypnotize"),
                Pair("Incest", "incest"),
                Pair("Intercrural", "intercrural"),
                Pair("JAV", "jav"),
                Pair("Lactation", "lactation"),
                Pair("Loli", "loli"),
                Pair("Maid", "maid"),
                Pair("Male Monster", "male-monster"),
                Pair("Masturbation", "masturbation"),
                Pair("Megane", "megane"),
                Pair("MILF", "milf"),
                Pair("Mind Control", "mind-control"),
                Pair("Monster", "monster"),
                Pair("Netorare", "netorare"),
                Pair("Nurse", "nurse"),
                Pair("Old Man", "old-man"),
                Pair("Onee-san", "onee-san"),
                Pair("Oral", "oral"),
                Pair("Paizuri", "paizuri"),
                Pair("Pantyhose", "pantyhose"),
                Pair("Pregnant", "pregnant"),
                Pair("Prostitution", "prostitution"),
                Pair("Rape", "rape"),
                Pair("Romance", "romance"),
                Pair("Saimin", "saimin"),
                Pair("Schoolgirl", "schoolgirl"),
                Pair("Semi-Hentai", "semi-hentai"),
                Pair("Sex Toys", "sex-toys"),
                Pair("Shibari", "shibari"),
                Pair("Shota", "shota"),
                Pair("Stocking", "stocking"),
                Pair("Succubus", "succubus"),
                Pair("Supranatural", "supranatural"),
                Pair("Swimsuit", "swimsuit"),
                Pair("Tentacles", "tentacles"),
                Pair("Threesome", "threesome"),
                Pair("Tsundere", "tsundere"),
                Pair("Ugly Bastard", "ugly-bastard"),
                Pair("Uncensored", "uncensored"),
                Pair("Vanilla", "vanilla"),
                Pair("Virgin", "virgin"),
                Pair("Yaoi", "yaoi"),
                Pair("Yuri", "yuri"),
            ),
        ) {
        fun toGenreSlug() = vals[state].second
    }

    class OrderFilter :
        UriPartFilter(
            "Urutkan",
            arrayOf(Pair("Terbaru", "date"), Pair("Terpopuler", "modified"), Pair("A-Z", "title")),
        )

    open class UriPartFilter(
        displayName: String,
        protected val vals: Array<Pair<String, String>>,
    ) : AnimeFilter.Select<String>(displayName, vals.map { it.first }.toTypedArray()) {
        fun toUriPart() = vals[state].second
    }

    // ══ PREFERENCES ══════════════════════════════════════════════════════

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        ListPreference(screen.context).apply {
            key = PREF_QUALITY_KEY
            title = "Kualitas Video Default"
            entries = arrayOf("360p", "480p", "720p (HD)", "1080p (FHD)")
            entryValues = arrayOf("360p", "480p", "720p", "1080p")
            setDefaultValue(PREF_QUALITY_DEFAULT)
            summary = "%s"
            setOnPreferenceChangeListener { _, v ->
                preferences.edit().putString(PREF_QUALITY_KEY, v as String).apply()
                true
            }
        }.also(screen::addPreference)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
    }
}
