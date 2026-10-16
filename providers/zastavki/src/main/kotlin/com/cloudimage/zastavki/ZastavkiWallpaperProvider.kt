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
 * Zastavki is a category-first wallpaper catalogue, browsed two ways:
 * thirty-six top categories (Nature, Animals, Auto, Girls, …), most of
 * them split into subcategory shelves (Nature → Mountains, Flowers,
 * Waterfalls, …), each shelf a wallpaper grid paginating deep — the
 * album paradigm this host's v1.1.0 contract models — and a tag index
 * that turns out to be the site's only WORKING search surface.
 *
 * ## The tag surface — search without the site's search
 *
 * The site's own search endpoint answers every anonymous query with
 * "nothing found" (broken for years, verified from a real browser) —
 * but `/rus/tag-list/` publishes a full tag index, and every tag slug
 * states a real listing page `/eng/tag/{slug}/`: a wallpaper grid,
 * eleven items per page, paginating blind exactly like the ranking
 * routes. [ZastavkiTags] bakes that catalogue (1,809 English slugs,
 * junk-filtered, live-checked), and everything search-shaped rides it:
 * [search] walks the best-matching tag's grid, [searchAlbums] offers
 * the top matches as albums the user can open as shelves,
 * [suggestTags] autocompletes the search bar offline. A query that
 * names no tag but names a CATEGORY ("nature", "girls") falls back to
 * that category's own page-one grid; anything else is the honest empty
 * page, never an error.
 *
 * ## The image URL families — the mobile markup first
 *
 * Every user-agent carrying "Android" earns the site's MOBILE markup,
 * and this provider deliberately claims that identity on every fetch:
 * the mobile grids state their thumbnails in the 600x382 preview
 * family — twice the desktop variant's 286x180, the size the site
 * itself serves its own phone visitors — as REAL, markup-stated URLs.
 * Subcategory shelves and the flat category grids carry the year
 * segment on recent uploads (the `originals/{year}/` sibling
 * derives); the ranking routes and the TAG surfaces glue the batch
 * year onto every file name (nothing derives — the 600x382 thumb
 * itself stands in as the full URL until `details` upgrades it). The
 * desktop markup's derivable-originals story is demonstrably WRONG
 * for legacy items (live-measured: the `originals/{year}/` sibling of
 * a 2012-batch thumb 404s while the true original lives elsewhere),
 * so the honest full URL is always a real served file, which
 * [details] then upgrades with the page's authoritative `og:image`
 * original, true pixel dimensions and uploader. The parser tolerates
 * BOTH markups (any `/pictures/{W}x{H}/` family), so a host that
 * overrides the agent back still parses.
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
 * Three server-side quirks bound what this provider serves, exactly as
 * they bound the site's own users: the TOP-category pagination is
 * 502-dead beyond page one (a flat category serves its first page of
 * eleven and no more — the category-backed search lane inherits the
 * same limit), the `orderby=rating` lane 502s outright (Popular rides
 * downloads), and unknown `orderby` values answer 200 with an EMPTY
 * shell (a trap, not a lane). Subcategory grids paginate without
 * limit; this provider caps the album walk at [MAX_ALBUM_PAGES]
 * fetched pages of shelf, a politeness bound the site's own
 * shelf-crawling users never approach.
 *
 * ## Content rating
 *
 * The catalogue mixes glamour shelves (Girls, Celebrities) into an
 * otherwise tame site with no server-side purity toggle, so the
 * provider declares SKETCHY and classifies every item by its own
 * category path — an item from a glamour shelf is SKETCHY, everything
 * else SFW, and the host's rating switch filters precisely. Tag grids
 * mix categories freely, so the classification rides each item's own
 * path there too.
 *
 * ## Politeness
 *
 * The site's robots.txt keeps generic crawlers off its query-string
 * routes; every fetch this provider makes happens on an explicit user
 * action — a feed page, an album open, a search, a detail view — one
 * request per page, with deep-walk caps. No challenge machinery is
 * ever needed: the site serves plain HTML to every client, datacenter
 * or phone.
 */
class ZastavkiWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "Zastavki",
            versionName = "1.1.0",
            author = "Cloudimage",
            description = "Free wallpapers from zastavki.com - keyless scraper with tag search.",
            // Glamour shelves mix into the catalogue with no anonymous
            // purity toggle; SKETCHY is the honest floor, and every item
            // is classified by its own category path so the host's
            // rating switch filters precisely.
            contentRating = ContentRating.SKETCHY,
        )

    override val capabilities: Set<Capability> =
        setOf(
            Capability.POPULAR,
            Capability.LATEST,
            Capability.ALBUMS,
            Capability.SEARCH,
            Capability.TAGS,
        )

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
     * Real search over the tag surface: the best-matching tag's own
     * grid, walked blind (a page beyond the tag's last answers 404 —
     * the walk's end, not an error) up to [MAX_SEARCH_PAGES]. A query
     * naming no tag but naming a category rides that category's
     * page-one grid — the top-category pagination is 502-dead beyond
     * page one server-side, so the category lane states no next page,
     * the site's own limit mirrored honestly. Anything else is the
     * honest empty page: the host renders "no results", never an error.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            if (page < 1 || page > MAX_SEARCH_PAGES) {
                return@runCatching Page(emptyList(), nextPage = null)
            }
            val tag = ZastavkiTags.bestMatch(query)
            if (tag != null) {
                return@runCatching tagSearchPage(tag, page)
            }
            categorySearchPage(query, page) ?: Page(emptyList(), nextPage = null)
        }

    /**
     * Search-bar autocomplete from the baked catalogue — offline, no
     * request fired, exactly the contract's "suggestions come from the
     * source the user is already searching" gate.
     */
    override suspend fun suggestTags(query: String): Result<List<String>> =
        Result.success(
            ZastavkiTags
                .topMatches(query, TAG_SUGGESTIONS)
                .map(ZastavkiTags::labelOf),
        )

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
     * the catalogue is known for) plus a row of showcase TAG albums
     * (Sky, Night, Ocean, …) that make the tag surface discoverable,
     * covers harvested from the live tag grids, baked in so the tab
     * renders before the first network byte. [albums] fetches the live
     * truth for every category.
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
     * The wallpapers of one album — a subcategory shelf, a flat
     * category's page-one grid, or a TAG album (`tag/{slug}`, the
     * search surface's shelf form) whose items come from every
     * category at once and are therefore read UNSCOPED. Every walk
     * runs up to [MAX_ALBUM_PAGES] fetched pages in the site's own
     * newest-first order, and stops at the first page the site refuses
     * (flat categories 502 beyond page one — the server's own limit,
     * mirrored honestly) or serves empty.
     */
    override suspend fun albumWallpapers(albumId: String): Result<List<Wallpaper>> =
        runCatching {
            // A tag's grid mixes every category: no owning path scopes it.
            val owningPath = if (albumId.startsWith("tag/")) null else albumId
            val collected = mutableListOf<Wallpaper>()
            for (pageNumber in 1..MAX_ALBUM_PAGES) {
                val page =
                    listingPage(albumId, pageNumber, stopOnRefusal = true, owningPath = owningPath)
                        ?: break
                if (page.wallpapers.isEmpty()) break
                collected += page.wallpapers
            }
            collected
        }

    /**
     * Album search over the baked tag catalogue: the top matches as
     * albums the user opens as shelves (`tag/{slug}` ids). Showcase
     * tags carry their harvested cover; the rest render the album
     * card's own placeholder until opened.
     */
    override suspend fun searchAlbums(query: String): Result<List<Album>> =
        Result.success(
            ZastavkiTags
                .topMatches(query, TAG_ALBUM_RESULTS)
                .map { slug ->
                    Album(
                        id = "tag/$slug",
                        providerId = ID,
                        title = ZastavkiTags.labelOf(slug),
                        coverUrl = SHOWCASE_TAG_COVERS[slug].orEmpty(),
                    )
                },
        )

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
     * One page of a tag's grid — the search surface's walk. Blind
     * pagination like the ranking routes: the site states no bar, a
     * 404 past the tag's last page is the walk's end (an empty page,
     * never an error), and any non-empty page promises the next up to
     * the [MAX_SEARCH_PAGES] cap.
     */
    private suspend fun tagSearchPage(
        slug: String,
        page: Int,
    ): Page {
        val path = if (page == 1) "tag/$slug/" else "tag/$slug/$page/"
        val response = get("$BASE_URL/eng/$path")
        if (!response.isSuccessful) {
            return Page(emptyList(), nextPage = null)
        }
        val items = ZastavkiParser.parseGrid(response.bodyText, owningPath = null)
        val nextPage =
            if (items.isNotEmpty() && page < MAX_SEARCH_PAGES) page + 1 else null
        return Page(items.map(::gridWallpaper), nextPage = nextPage)
    }

    /**
     * The category-name fallback lane: a query that names no tag but
     * names a category ("nature", "girls") rides that category's own
     * page-one grid. Null when the query names no category either. The
     * top-category pagination is 502-dead beyond page one server-side,
     * so this lane states no next page — the site's own limit, mirrored
     * honestly.
     */
    private suspend fun categorySearchPage(
        query: String,
        page: Int,
    ): Page? {
        val wanted = query.trim().lowercase()
        val category =
            CATEGORIES.firstOrNull { it.id.lowercase() == wanted || it.name.lowercase() == wanted }
                ?: return null
        if (page > 1) return Page(emptyList(), nextPage = null)
        val response = get("$BASE_URL/eng/${category.id}/")
        if (!response.isSuccessful) {
            // A flat category's own 502-or-worse refusal: empty, not an
            // error — the query simply has nothing more to say.
            return Page(emptyList(), nextPage = null)
        }
        val items = ZastavkiParser.parseGrid(response.bodyText, owningPath = category.id)
        return Page(items.map(::gridWallpaper), nextPage = null)
    }

    /**
     * One page of an album listing — `null` when the site refuses the
     * page (the flat-category 502 — [stopOnRefusal]) or serves
     * nothing. A tag album passes [owningPath] `null` (its grid mixes
     * every category); a shelf passes its own path so site-wide
     * furniture stays out. The subcategory bar's deepest number bounds
     * `nextPage` when the walk wants it; the album walk ignores
     * pagination signals and simply collects pages.
     */
    private suspend fun listingPage(
        path: String,
        page: Int,
        stopOnRefusal: Boolean,
        owningPath: String?,
    ): Page? {
        val paged = if (page == 1) "$BASE_URL/eng/$path/" else "$BASE_URL/eng/$path/$page/"
        val response = get(paged)
        if (!response.isSuccessful) {
            if (stopOnRefusal) return null
            throw httpError(response.statusCode)
        }
        val items = ZastavkiParser.parseGrid(response.bodyText, owningPath = owningPath)
        val bar = ZastavkiParser.parseMaxPage(response.bodyText, path)
        val nextPage =
            if (bar != null && bar > page && page < MAX_RANKING_PAGES) page + 1 else null
        return Page(items.map(::gridWallpaper), nextPage = nextPage)
    }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper: the markup-stated preview thumbnail
     * (the 600x382 family under the mobile identity this provider
     * rides) as [Wallpaper.thumbUrl], and as [Wallpaper.fullUrl] the
     * derivable `originals/{year}/` sibling where the thumb carries
     * the year segment (shelves, flat category grids), the thumb
     * itself otherwise (ranking and tag surfaces glue the batch year
     * onto every name) — a real served 600x382 file either way, which
     * [details] upgrades to the TRUE original. Dimensions are
     * deliberately absent from listings (the site states none there);
     * [details] is where the TRUE resolution arrives.
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

    /**
     * Every fetch rides an ANDROID-Chrome identity — the sanctioned
     * per-request override. Not cosmetic, and deliberately the OPPOSITE
     * of a desktop claim: user-agents carrying "Android" earn the
     * site's MOBILE markup, whose grids state their thumbnails in the
     * real 600x382 preview family (twice the desktop 286x180) as
     * markup-stated URLs — shelves and flat category grids with the
     * year segment on recent uploads (originals derivable), the
     * ranking routes and tag surfaces with the year glued onto every
     * name (the thumb itself stands in as the full URL). The desktop
     * markup's derivable originals are demonstrably WRONG for legacy
     * items (their `originals/{year}/` sibling 404s — live-measured),
     * while the mobile fallback is a real file for every item, and
     * [details] upgrades to the page's authoritative `og:image`
     * original regardless. The parser accepts BOTH markups, so a host
     * overriding the agent back still parses.
     */
    private suspend fun get(url: String) =
        httpClient?.get(url, mapOf("User-Agent" to USER_AGENT))
            ?: error("provider used before configure()")

    /** The site's own User-Agent-facing image host prefix is absolute everywhere. */
    private companion object {
        const val ID = "cloudimage.zastavki"
        const val BASE_URL = "https://www.zastavki.com"

        /**
         * The Android-Chrome identity every fetch rides — see [get].
         */
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

        /** The all-time most-downloaded walk — the popular feed. */
        const val POPULAR_PATH = "/?r=site%2Flist&orderby=downloads"

        /** The newest-first walk — the latest feed. */
        const val LATEST_PATH = "/?r=site%2Flist&orderby=date"

        /** Deep-walk caps — politeness bounds on user-triggered fetches. */
        const val MAX_RANKING_PAGES = 100
        const val MAX_ALBUM_PAGES = 4

        /** The blind search walk's page cap (eleven results per page). */
        const val MAX_SEARCH_PAGES = 30

        /** How many tag suggestions and tag albums a query surfaces. */
        const val TAG_SUGGESTIONS = 8
        const val TAG_ALBUM_RESULTS = 8

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
         * live tile markup (October 2026), and a row of showcase TAG
         * albums whose covers were harvested from the live tag grids —
         * the tag surface made discoverable on the Home tab itself.
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
                Album(
                    "tag/Sky",
                    ID,
                    "Sky",
                    "$BASE_URL/pictures/600x382/2014Cities_A_city_of_skyscrapers_083208_33.jpg",
                ),
                Album(
                    "tag/Night",
                    ID,
                    "Night",
                    "$BASE_URL/pictures/600x382/2015Drawn_wallpapers_Black_cat_moon_at_night_097970_33.jpg",
                ),
                Album(
                    "tag/Ocean",
                    ID,
                    "Ocean",
                    "$BASE_URL/pictures/600x382/2014Nature___Beach_____The_lagoon_beach_083308_33.jpg",
                ),
                Album(
                    "tag/Snow",
                    ID,
                    "Snow",
                    "$BASE_URL/pictures/600x382/2014Animals___Wild_cats_____Snow_leopard_is_sitting_in_the_snow_083447_33.jpg",
                ),
                Album(
                    "tag/Galaxy",
                    ID,
                    "Galaxy",
                    "$BASE_URL/pictures/600x382/2016Space_The_galaxy_in_the_form_of_a_sombrero_102800_33.jpg",
                ),
                Album(
                    "tag/Dubai",
                    ID,
                    "Dubai",
                    "$BASE_URL/pictures/600x382/2014Cities_____Skyscrapers_in_Dubai_085454_33.jpg",
                ),
                Album(
                    "tag/Pokemon",
                    ID,
                    "Pokemon",
                    "$BASE_URL/pictures/600x382/2013Fantasy__039078_33.jpg",
                ),
                Album(
                    "tag/Coffee",
                    ID,
                    "Coffee",
                    "$BASE_URL/pictures/600x382/2014Food___Drinks_Coffee_machine_083779_33.jpg",
                ),
            )

        /**
         * The showcase tags' harvested covers — the tag albums the Home
         * tab carries, reused wherever a tag surfaces without its own
         * cover (album search results).
         */
        val SHOWCASE_TAG_COVERS: Map<String, String> =
            HOME_ALBUMS
                .filter { it.id.startsWith("tag/") }
                .associate { it.id.removePrefix("tag/") to it.coverUrl }
    }
}
