package com.cloudimage.zastavki

import com.cloudimage.provider.api.Album
import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.Category
import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.Page
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderMeta
import com.cloudimage.provider.api.ProviderSettings
import com.cloudimage.provider.api.Wallpaper
import com.cloudimage.provider.api.WallpaperDetails
import com.cloudimage.provider.api.WallpaperProvider

/**
 * Zastavki.com as a Cloudimage provider package — a keyless scraper
 * over the site's server-rendered pages, the same way every scraping
 * source in this set works.
 *
 * ## Site model
 *
 * Zastavki is a category-first wallpaper catalogue, browsed exactly one
 * way: thirty-six top categories (Nature, Animals, Auto, Girls, …),
 * most of them split into subcategory shelves (Nature → Mountains,
 * Flowers, Waterfalls, …), each shelf a wallpaper grid paginating deep
 * — the album paradigm this host's v1.1.0 contract models. The site's
 * OWN search endpoint answers every query with "nothing found" (broken
 * for anonymous clients — verified from a real browser), so this
 * provider is honestly browse-only: the album UI is the whole
 * experience, exactly as on the site itself.
 *
 * ## The image URL families — and the original
 *
 * The site's own image host serves derivable size families over one
 * path tail: `/pictures/286x180/{year}/{tail}_{code}.jpg` for grid
 * thumbs and `/pictures/originals/{year}/{tail}_.jpg` for the TRUE
 * original — the code segment simply dropped, the exact transform the
 * detail page's own `og:image` states, publicly served to every client
 * with no referer check (verified: a 6000x4000, 6.9 MB original). A
 * grid cell's thumbnail therefore discloses a working original, and
 * [details] upgrades the record with the page's authoritative
 * `og:image` URL, true pixel dimensions and uploader.
 *
 * ## The ranked feeds
 *
 * The homepage's own "new images" block rides a ranking route the
 * navigation exposes: `?r=site/list&orderby=downloads|date&page=N` —
 * the all-time most-downloaded walk and the latest walk, eleven items
 * per page, paginating blind (the route states no bar; the walk simply
 * stops when a page comes back empty). The route renders
 * Russian-language titles, so those feeds mine the English title from
 * the thumbnail's own URL slug — the site stores one English slug per
 * wallpaper, whatever language the page renders.
 *
 * ## The site's own broken parts — mirrored honestly
 *
 * Two server-side quirks bound what this provider serves, exactly as
 * they bound the site's own users: the TOP-category pagination is
 * 502-dead beyond page one (a flat category serves its first page of
 * eleven and no more — the walk stops at the first failed page), and
 * the `orderby=rating` lane 502s outright (Popular rides downloads).
 * Subcategory grids paginate without limit; this provider caps the
 * album walk at [MAX_ALBUM_PAGES] fetched pages of shelf, a politeness
 * bound the site's own shelf-crawling users never approach.
 *
 * ## Content rating
 *
 * The catalogue mixes glamour shelves (Girls, Celebrities) into an
 * otherwise tame site with no server-side purity toggle, so the
 * provider declares SKETCHY and classifies every item by its own
 * category path — an item from a glamour shelf is SKETCHY, everything
 * else SFW, and the host's rating switch filters precisely.
 *
 * ## Politeness
 *
 * The site's robots.txt keeps generic crawlers off its query-string
 * routes; every fetch this provider makes happens on an explicit user
 * action — a feed page, an album open, a detail view — one request per
 * page, with deep-walk caps. No challenge machinery is ever needed:
 * the site serves plain HTML to every client, datacenter or phone.
 */
class ZastavkiWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "Zastavki",
            versionName = "1.0.0",
            author = "Cloudimage",
            description = "Free wallpapers from zastavki.com - scraped, keyless.",
            // Glamour shelves mix into the catalogue with no anonymous
            // purity toggle; SKETCHY is the honest floor, and every item
            // is classified by its own category path so the host's
            // rating switch filters precisely.
            contentRating = ContentRating.SKETCHY,
        )

    override val capabilities: Set<Capability> =
        setOf(Capability.POPULAR, Capability.LATEST, Capability.ALBUMS)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    /**
     * The default feed — the all-time most-downloaded walk, the closest
     * thing to "popular" the site states. `sorting=date` rides the
     * latest walk instead. Category filters are the album UI's business
     * on this source; the flat feed ignores them, documented.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val path =
                if (filters.isSelected("sorting", "date")) LATEST_PATH else POPULAR_PATH
            rankingPage(path, page)
        }

    /**
     * The site's own search endpoint answers every anonymous query with
     * "nothing found" — verified from a real browser — so the honest
     * answer is an empty page, never an error: the host renders "no
     * results" and the album UI (this provider's real search surface)
     * carries the finding duty.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> = Result.success(Page(emptyList(), nextPage = null))

    /**
     * The home shelves: the two ranked walks. Cheap and offline, as the
     * contract asks — the album UI swaps these for [homeAlbums], but a
     * flat host still gets a sane home.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = HomeSection.DEFAULT_ID, title = "Popular"),
            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
        )

    /**
     * The definitive record for an id: the wallpaper page states the
     * TRUE original's URL and pixel dimensions (`og:image` with its
     * width/height pair) and the uploader (the `user-added` anchor).
     * The thumb stays BLANK on purpose — the grid item's own thumbnail
     * is better than anything this page states, and the host keeps it.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val response = get("$BASE_URL/rus/$id.htm")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val record =
                ZastavkiParser.parseDetail(response.bodyText)
                    ?: error("unrecognized wallpaper page for '$id'")
            WallpaperDetails(
                wallpaper =
                    Wallpaper(
                        id = id,
                        providerId = ID,
                        // Blank on purpose: the grid item's thumbnail is
                        // the better thumb, and the host keeps it.
                        thumbUrl = "",
                        fullUrl = record.originalUrl,
                        title = null,
                        width = record.width,
                        height = record.height,
                        contentRating = ratingFor(categoryPathOf(id)),
                    ),
                author = record.author,
                resolution =
                    if (record.width != null && record.height != null) {
                        "${record.width}x${record.height}"
                    } else {
                        null
                    },
                sourceUrl = "$BASE_URL/rus/$id.htm",
            )
        }

    /** No random surface exists on this site — the honest empty answer. */
    override suspend fun random(): Result<List<Wallpaper>> = Result.success(emptyList())

    // ------------------------------------------------------------ album UI

    /**
     * The category sidebar — the site's own thirty-six top categories,
     * baked in (the contract blesses exactly this: cheap, offline,
     * refreshed implicitly when a category page is fetched). Names are
     * the English ones the site's own English navigation states.
     */
    override suspend fun categories(): List<Category> = CATEGORIES

    /**
     * The Home tab — a curated shelf of the site's own subcategory
     * albums (the ones its navigation marks "hot" and the deep shelves
     * the catalogue is known for), covers harvested from the live tile
     * markup, baked in so the tab renders before the first network
     * byte. [albums] fetches the live truth for every category.
     */
    override suspend fun homeAlbums(): Result<List<Album>> = Result.success(HOME_ALBUMS)

    /**
     * Every album of one category, as the category's own page states
     * it: the subcategory tiles, in the site's own order. A FLAT
     * category (no tiles) synthesizes its single "all wallpapers"
     * album — the category page itself, whose grid the walk reads.
     */
    override suspend fun albums(categoryId: String): Result<List<Album>> =
        runCatching {
            val category =
                CATEGORIES.firstOrNull { it.id == categoryId }
                    ?: return@runCatching emptyList()
            val response = get("$BASE_URL/eng/${category.id}/")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val tiles = ZastavkiParser.parseTiles(response.bodyText, category.id)
            if (tiles.isNotEmpty()) {
                tiles.map { tile ->
                    Album(
                        id = tile.path,
                        providerId = ID,
                        title = tile.name,
                        coverUrl = tile.coverUrl,
                    )
                }
            } else {
                val grid = ZastavkiParser.parseGrid(response.bodyText, owningPath = category.id)
                val cover = grid.firstOrNull()?.thumbUrl.orEmpty()
                listOf(
                    Album(
                        id = category.id,
                        providerId = ID,
                        title = "${category.name} wallpapers",
                        coverUrl = cover,
                    ),
                )
            }
        }

    /**
     * The wallpapers of one album — a subcategory shelf or a flat
     * category's page-one grid — walked up to [MAX_ALBUM_PAGES] fetched
     * pages in the site's own newest-first order. The walk stops at the
     * first page the site refuses (flat categories 502 beyond page one
     * — the server's own limit, mirrored honestly) or serves empty.
     */
    override suspend fun albumWallpapers(albumId: String): Result<List<Wallpaper>> =
        runCatching {
            val collected = mutableListOf<Wallpaper>()
            for (pageNumber in 1..MAX_ALBUM_PAGES) {
                val page =
                    listingPage(albumId, pageNumber, stopOnRefusal = true) ?: break
                if (page.wallpapers.isEmpty()) break
                collected += page.wallpapers
            }
            collected
        }

    /**
     * Album search rides the site's search, which answers nothing —
     * the honest empty list, exactly like [search].
     */
    override suspend fun searchAlbums(query: String): Result<List<Album>> = Result.success(emptyList())

    // ---------------------------------------------------------------- feed

    /**
     * One page of a ranked walk: fetch, parse every cell, dedupe. The
     * route states no pagination bar, so the walk is blind — a page
     * that comes back empty is the end, and any non-empty page promises
     * the next one up to the [MAX_RANKING_PAGES] cap.
     */
    private suspend fun rankingPage(
        path: String,
        page: Int,
    ): Page {
        if (page < 1 || page > MAX_RANKING_PAGES) return Page(emptyList(), nextPage = null)
        val response = get("$BASE_URL$path&page=$page")
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val items = ZastavkiParser.parseGrid(response.bodyText, owningPath = null)
        val nextPage =
            if (items.isNotEmpty() && page < MAX_RANKING_PAGES) page + 1 else null
        return Page(items.map(::gridWallpaper), nextPage = nextPage)
    }

    /**
     * One page of an album listing — `null` when the site refuses the
     * page (the flat-category 502 — [stopOnRefusal]) or serves
     * nothing. The subcategory bar's deepest number bounds
     * `nextPage` when the walk wants it; the album walk ignores
     * pagination signals and simply collects pages.
     */
    private suspend fun listingPage(
        path: String,
        page: Int,
        stopOnRefusal: Boolean,
    ): Page? {
        val paged = if (page == 1) "$BASE_URL/eng/$path/" else "$BASE_URL/eng/$path/$page/"
        val response = get(paged)
        if (!response.isSuccessful) {
            if (stopOnRefusal) return null
            throw httpError(response.statusCode)
        }
        val items = ZastavkiParser.parseGrid(response.bodyText, owningPath = path)
        val bar = ZastavkiParser.parseMaxPage(response.bodyText, path)
        val nextPage =
            if (bar != null && bar > page && page < MAX_RANKING_PAGES) page + 1 else null
        return Page(items.map(::gridWallpaper), nextPage = nextPage)
    }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper: the 286x180 thumbnail as
     * [Wallpaper.thumbUrl] and the original — the thumbnail's
     * size-family sibling on `originals`, a real publicly served file —
     * as [Wallpaper.fullUrl]. Dimensions are deliberately absent from
     * listings (the site states none there); [details] is where the
     * TRUE resolution arrives.
     */
    private fun gridWallpaper(item: ZastavkiParser.GridItem): Wallpaper =
        Wallpaper(
            id = item.sitePath,
            providerId = ID,
            thumbUrl = item.thumbUrl,
            fullUrl = item.fullUrl,
            title = item.title,
            contentRating = ratingFor(item.categoryPath),
        )

    /**
     * An item's rating, from its own category path: a glamour shelf
     * (Girls, Celebrities) is SKETCHY, everything else SFW. The host's
     * rating switch does the filtering; this classification is what
     * makes it honest for a source with no server-side purity toggle.
     */
    private fun ratingFor(categoryPath: String): ContentRating =
        when (categoryPath.substringBefore('/')) {
            "Girls", "Celebrities" -> ContentRating.SKETCHY
            else -> ContentRating.SFW
        }

    /** The category path an id (`{cat}[/{sub}]/wallpaper-{n}`) carries. */
    private fun categoryPathOf(id: String): String = id.substringBefore("/wallpaper-")

    private fun httpError(statusCode: Int): IllegalStateException =
        IllegalStateException("zastavki.com refused the exchange (HTTP $statusCode)")

    private suspend fun get(url: String) =
        httpClient?.get(url)
            ?: error("provider used before configure()")

    /** The site's own User-Agent-facing image host prefix is absolute everywhere. */
    private companion object {
        const val ID = "cloudimage.zastavki"
        const val BASE_URL = "https://www.zastavki.com"

        /** The all-time most-downloaded walk — the popular feed. */
        const val POPULAR_PATH = "/?r=site%2Flist&orderby=downloads"

        /** The newest-first walk — the latest feed. */
        const val LATEST_PATH = "/?r=site%2Flist&orderby=date"

        /** Deep-walk caps — politeness bounds on user-triggered fetches. */
        const val MAX_RANKING_PAGES = 100
        const val MAX_ALBUM_PAGES = 4

        /**
         * The site's thirty-six top categories, as its own English
         * navigation states them (October 2026) — the album sidebar.
         */
        val CATEGORIES: List<Category> =
            listOf(
                Category("Nature", "Nature"),
                Category("Animals", "Animals"),
                Category("Auto", "Auto"),
                Category("Girls", "Girls"),
                Category("Space", "Space"),
                Category("Anime", "Anime"),
                Category("Fantasy", "Fantasy"),
                Category("Games", "Games"),
                Category("Movies", "Movies"),
                Category("Cartoons", "Cartoons"),
                Category("Drawn_wallpapers", "Drawn wallpapers"),
                Category("Celebrities", "Celebrities"),
                Category("Men", "Men"),
                Category("People", "People"),
                Category("Love", "Love"),
                Category("Holidays", "Holidays"),
                Category("Cities", "Cities"),
                Category("Aviation", "Aviation"),
                Category("Ships", "Ships"),
                Category("Motocycles", "Motorcycles"),
                Category("Sport", "Sport"),
                Category("Music", "Music"),
                Category("Food", "Food"),
                Category("Computers", "Computers"),
                Category("Interior", "Interior"),
                Category("World", "World"),
                Category("Brands", "Brands"),
                Category("Photography", "Photography"),
                Category("Tattoos", "Tattoos"),
                Category("Zodiac_signs", "Zodiac signs"),
                Category("Funny_wallpapers", "Funny wallpapers"),
                Category("Creative_Wallpaper", "Creative Wallpaper"),
                Category("Finance_Wallpapers", "Finance Wallpapers"),
                Category("Backgrounds", "Backgrounds"),
                Category("Motivation", "Motivation"),
                Category("Widescreen", "Widescreen"),
            )

        /**
         * The Home tab's curated shelf — subcategory albums the site
         * itself features (its navigation's "hot" marks) plus the deep
         * shelves the catalogue is known for, covers harvested from the
         * live tile markup (October 2026).
         */
        val HOME_ALBUMS: List<Album> =
            listOf(
                Album("Nature/Mountains", ID, "Mountains", "$BASE_URL/pictures/286x180/2012/Nature_Mountains__000646_32.jpg"),
                Album("Nature/Flowers", ID, "Flowers", "$BASE_URL/pictures/286x180/2012/Nature_Flowers__000615_32.jpg"),
                Album(
                    "Nature/Waterfalls",
                    ID,
                    "Waterfalls",
                    "$BASE_URL/pictures/286x180/2012/Nature_Waterfalls_Niagara_Falls_USA_Canada_021785_32.jpg",
                ),
                Album("Animals/Dogs", ID, "Dogs", "$BASE_URL/pictures/286x180/2013/Animals___Dogs_Dog_in_knitted_clothes_047175_32.jpg"),
                Album(
                    "Animals/Cats",
                    ID,
                    "Cats",
                    "$BASE_URL/pictures/286x180/2013/Animals___Cats_Funny_cat_on_an_orange_background_046828_32.jpg",
                ),
                Album("Animals/Horses", ID, "Horses", "$BASE_URL/pictures/286x180/2012/Animals_Horses__000767_32.jpg"),
                Album("Auto/Tesla", ID, "Tesla", "$BASE_URL/pictures/286x180/2017_Tesla_electric_car_in_the_snow_111478_32.jpg"),
                Album("Auto/BMW", ID, "BMW", "$BASE_URL/pictures/286x180/2012/Auto_BMW_7_series_BMW_7_series_000162_32.jpg"),
                Album("Games/World_of_Tanks", ID, "World of Tanks", "$BASE_URL/pictures/286x180/2013/_World_of_Tanks_girls_045871_32.jpg"),
                Album(
                    "Drawn_wallpapers/Painted_girls",
                    ID,
                    "Painted girls",
                    "$BASE_URL/pictures/286x180/2012/Drawn_wallpapers_Painted_girls_Retro_girl_010952_32.jpg",
                ),
                Album(
                    "Computers/Apple",
                    ID,
                    "Apple",
                    "$BASE_URL/pictures/286x180/2014/Computers___Apple_Backlit_keyboard_from_Apple_089918_32.jpg",
                ),
                Album(
                    "People/Children",
                    ID,
                    "Children",
                    "$BASE_URL/pictures/286x180/2014/People___Children_The_girl_at_the_festival_084451_32.jpg",
                ),
            )
    }
}
