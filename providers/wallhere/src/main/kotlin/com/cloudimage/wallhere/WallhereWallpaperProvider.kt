package com.cloudimage.wallhere

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.Page
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderMeta
import com.cloudimage.provider.api.ProviderSettings
import com.cloudimage.provider.api.Wallpaper
import com.cloudimage.provider.api.WallpaperDetails
import com.cloudimage.provider.api.WallpaperProvider
import java.net.URLEncoder

/**
 * WallHere (https://wallhere.com) as a Cloudimage provider package — a
 * keyless scraper over the site's server-rendered pages, the same way
 * every scraping source in this set works. This is the v2.0.0 rewrite:
 * the walk that cannot die at twenty-four.
 *
 * ## The problem this rewrite exists to solve
 *
 * Every listing on this site is a flat twenty-four-item page, and the
 * app's endless scroll walks `?page=N`. The retired v1.x line trusted
 * ONE continuation dialect — the desktop markup's pagination bar — and
 * died in the field exactly there: the site serves MOBILE agents a
 * different dialect (the same 24-cell grid, no bar, infinite-scroll
 * wiring instead), and on a phone the app's fetch lanes carry mobile
 * or Android-branded agents. Four revisions of bar heuristics (v1.1.1
 * through v1.1.4) could not make one dialect's furniture govern the
 * other's pages, and every feed and search stopped at exactly
 * twenty-four items — the failure this rewrite treats as the design
 * center, not an edge case.
 *
 * ## The v2.0.0 walk — verify, then adapt, never die
 *
 * Every continuation is verified BY CONTENT, never by trust: the walk
 * keeps the wallpaper ids it already served for the listing it is
 * walking, and a fetched page's FRESH cells — ids the walk has not
 * served — are the only proof the page parameter advanced. That single
 * rule is dialect-neutral, so the walk works on the desktop bar
 * dialect and the bar-less mobile dialect alike:
 *
 * - a page with fresh cells is served, deduped, and the walk continues;
 * - a page whose every cell repeats the walk's memory did not
 *   advance — the mobile dialect ignoring the parameter, or a
 *   cache-flattened replay — and instead of erroring out (the v1.x
 *   behavior) or stopping in silence, the walk CONTINUES ON THE
 *   RANDOM SURFACE: `/en/random` reshuffles its batch on every load —
 *   an endless source of fresh cells on every connection and every
 *   dialect, needing no page parameter at all — so the user's scroll
 *   keeps being fed real, fresh wallpapers;
 * - a page the site itself clamps past the listing's end (the bar
 *   marks a SHALLOWER page than requested, or advertises none so
 *   deep) is the honest end — nothing more exists to serve.
 *
 * The pagination bar, when present, still governs the "more exists"
 * signal exactly as before; the random fallback is the second layer
 * that makes the walk unkillable, and the per-listing id memory is
 * the third — duplicates can never reach keyed grids on any path.
 *
 * ## Site model
 *
 * WallHere is a tag-first, multi-language wallpaper catalogue. One
 * flat masonry grid renders EVERY listing — twenty-four items per
 * page — and every listing speaks the same query vocabulary the
 * site's own filter panel offers:
 *
 * - `/en/wallpapers` — `order=popular|latest`, `q=` keyword search
 *   (the top form's own target), `page=N` (the popular order runs
 *   seventy-thousand pages deep), plus the panel's filters:
 *   `NSFW=off` (the site's purity switch — its own nav links it),
 *   `direction=horizontal|vertical`, `color=RRGGBB`,
 *   `min_width`/`min_height`;
 * - `/en/random` — the random surface: a 343-thousand-wallpaper
 *   listing that reshuffles on every load — the v2.0.0 fallback lane
 *   and the `sorting=random` feed's own endless source;
 * - `/en/wallpaper/{id}` — the detail page every grid cell links.
 *
 * The grid's `current-item-photo` anchors disclose each wallpaper's
 * numeric id, its tag row (the img's own `title` attribute) and its
 * CDN thumbnail; the CDN's derivable suffix families turn any
 * thumbnail into its display original by suffix swap (`!s1`/`!s2` →
 * `!d`), a real publicly served ~1200px file.
 *
 * ## Purity — the honest server-side switch
 *
 * The provider appends the site's own `NSFW=off` switch to every
 * listing/search/random URL whenever the host asks for SFW-only
 * content (purity = sfw, no sketchy), and omits it whenever sketchy
 * is allowed or purity is unspecified. The per-item tag-row
 * classification stays as the second layer — the site's switch is
 * trusted, but each item is still labelled by its own vocabulary so
 * the host's rating switch filters whatever slips a server-side
 * filter.
 *
 * ## Content rating
 *
 * The site mixes adult-tagged wallpapers into its DEFAULT feeds, so
 * the provider declares SKETCHY and classifies every item by the
 * site's OWN tag vocabulary, per WORD of each tag: an item whose tag
 * row carries an adult word is NSFW, and the host's rating switch
 * filters it exactly as it does Wallhaven's purity labels. Everything
 * else is SFW. The SAME per-word check gates search-bar suggestions —
 * the content filter and the suggestion filter cannot disagree about
 * the same vocabulary.
 *
 * ## Cloudflare
 *
 * The zone Cloudflare-challenges datacenter addresses on every
 * document path (interactive Turnstile), while the image CDN serves
 * every client unchallenged. The host's HTTP facade owns that problem
 * (per-host clearances, the WebView document lane, the in-app browser
 * escape hatch) — this plugin parses whatever comes back. Its
 * requests are bare of cookies but not of identity: every document
 * fetch claims a DESKTOP Chrome agent through the facade's sanctioned
 * per-request User-Agent override, so an unchallenged plain request
 * comes back in the desktop dialect with the pagination bar. A replay
 * riding a WebView-earned clearance still travels under the
 * clearance's own (mobile) agent — clearance binding wins, per the
 * facade contract — and THAT is exactly the lane the content-verified
 * walk with its random fallback was built to survive: the mobile
 * dialect's bar-less pages parse with the same grid regexes, and a
 * dead page parameter degrades to the reshuffling random surface
 * instead of a dead feed.
 *
 * ## Contract mapping
 *
 * - [popular] rides the host vocabulary: a recognized `category`
 *   walks that category's own search lane (anime, people), `sorting=
 *   date` walks the latest order, `sorting=random` rides the random
 *   surface's own reshuffle mode (every page a fresh shuffle, the
 *   id memory keeping the sequence duplicate-free), and everything
 *   else lands on the popular order;
 * - [search] rides the site's own `/en/wallpapers?q=` search — one
 *   request per page, as deep as the site goes, with the random
 *   fallback keeping the scroll alive on dead-parameter connections;
 *   a BLANK query rides the latest order at the page the host asked
 *   for;
 * - [sections] offers the ranked shelves plus tag-style `query`
 *   presets whose values the host routes through [search];
 * - [details] fetches `/en/wallpaper/{id}` and reads the definitive
 *   record: the true original's URL and resolution, the display
 *   fallback, the uploader and the page's fuller tag row;
 * - [suggestTags] answers offline from a small baked seed of the
 *   site's own most-popular tags (harvested from its live tag index,
 *   adult vocabulary withheld) plus tags harvested from grids already
 *   seen — never a third-party service;
 * - [random] serves the site's own random batch as-is — a real
 *   random feed, advertised with [Capability.RANDOM].
 *
 * ## Politeness
 *
 * The site's robots.txt excludes only its login and signup paths;
 * every path used here — the listings, the search, the random page,
 * the wallpaper pages — is left open. Fetches happen on explicit user
 * actions, one request per page (two on the fallback lane, the dead
 * probe plus the random draw), with a deep-pagination cap.
 */
class WallhereWallpaperProvider : WallpaperProvider {
    private var httpClient: ProviderHttpClient? = null

    override val meta =
        ProviderMeta(
            id = ID,
            name = "WallHere",
            versionName = "2.0.0",
            author = "Cloudimage",
            description = "Free HD wallpapers from wallhere.com - keyless scraper with endless pagination on every surface.",
            // The site mixes adult-tagged content into its default feeds;
            // SKETCHY is the honest floor, every item is classified by its
            // own tag row, and SFW-only requests additionally ride the
            // site's server-side NSFW=off switch.
            contentRating = ContentRating.SKETCHY,
        )

    override val capabilities: Set<Capability> =
        setOf(Capability.POPULAR, Capability.LATEST, Capability.SEARCH, Capability.FILTERS, Capability.TAGS, Capability.RANDOM)

    override fun configure(
        client: ProviderHttpClient,
        settings: ProviderSettings,
    ) {
        httpClient = client
    }

    /**
     * The default feed, ridden through the host vocabulary: a
     * recognized `category` walks that category's own search lane
     * (the precise grids), `sorting=date` walks the latest order,
     * `sorting=random` rides the random surface's reshuffle mode
     * (every page a fresh shuffle, the id memory keeping the
     * sequence duplicate-free), and everything else lands on the
     * popular order — the feed the browse tab shows first. SFW-only
     * requests carry the site's own `NSFW=off` switch on every URL.
     */
    override suspend fun popular(
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val sfwOnly = wantsSfwOnly(filters)
            val category = filters.valuesFor("category").firstNotNullOfOrNull(::categoryLane)
            when {
                category != null -> listingPage(category, page, sfwOnly)
                filters.isSelected("sorting", "date") -> listingPage(LATEST_PATH, page, sfwOnly)
                filters.isSelected("sorting", "random") -> listingPage(RANDOM_PATH, page, sfwOnly, reshuffle = true)
                else -> listingPage(POPULAR_PATH, page, sfwOnly)
            }
        }

    /**
     * Search rides the site's own top form (`/en/wallpapers?q=`),
     * walked page by page with the content-verified continuation —
     * fresh cells serve, a dead page parameter degrades to the random
     * surface, and the site clamping past its last result page is the
     * honest end. A blank query (the contract's escape hatch) lands
     * on the latest order at the page the host asked for.
     */
    override suspend fun search(
        query: String,
        page: Int,
        filters: Filters,
    ): Result<Page> =
        runCatching {
            val sfwOnly = wantsSfwOnly(filters)
            val trimmed = query.trim()
            if (trimmed.isBlank()) {
                listingPage(LATEST_PATH, page, sfwOnly)
            } else {
                listingPage(searchPath(trimmed), page, sfwOnly)
            }
        }

    /**
     * Suggestions answer offline, immediately: the baked seed of the
     * site's own most-popular tags first, supplemented by tag rows
     * this instance has already seen in fetched grids — adult
     * vocabulary withheld per WORD of the name in both lanes.
     */
    override suspend fun suggestTags(query: String): Result<List<String>> =
        runCatching {
            if (query.isBlank()) {
                emptyList()
            } else {
                val seed =
                    SEED_TAGS.filter { it.contains(query, ignoreCase = true) }
                val seen =
                    synchronized(lock) { tagPool.toList() }
                        .filter { it.contains(query, ignoreCase = true) }
                (seed + seen)
                    .filterNot(::isAdultTag)
                    .distinct()
                    .take(TAG_SUGGESTION_LIMIT)
            }
        }

    /**
     * The home shelves: the two ranked feeds first (Popular is the
     * default row the host's merged home picks), then tag-style
     * `query` presets whose values the host routes through [search] —
     * every one of them walked with the same endless continuation.
     */
    override suspend fun sections(): List<HomeSection> =
        listOf(
            HomeSection(id = HomeSection.DEFAULT_ID, title = "Popular"),
            HomeSection(id = "latest", title = "Latest", filters = Filters.of("sorting" to "date")),
            HomeSection(id = "nature", title = "Nature", filters = Filters.of("query" to "nature")),
            HomeSection(id = "landscape", title = "Landscape", filters = Filters.of("query" to "landscape")),
            HomeSection(id = "anime", title = "Anime", filters = Filters.of("query" to "anime")),
            HomeSection(id = "abstract", title = "Abstract", filters = Filters.of("query" to "abstract")),
            HomeSection(id = "cars", title = "Cars", filters = Filters.of("query" to "car")),
            HomeSection(id = "games", title = "Games", filters = Filters.of("query" to "video games")),
            HomeSection(id = "space", title = "Space", filters = Filters.of("query" to "space")),
            HomeSection(id = "animals", title = "Animals", filters = Filters.of("query" to "animals")),
            HomeSection(id = "city", title = "City", filters = Filters.of("query" to "city")),
            HomeSection(id = "night", title = "Night", filters = Filters.of("query" to "night")),
            HomeSection(id = "mountains", title = "Mountains", filters = Filters.of("query" to "mountains")),
            HomeSection(id = "sunset", title = "Sunset", filters = Filters.of("query" to "sunset")),
            HomeSection(id = "sea", title = "Sea", filters = Filters.of("query" to "sea")),
            HomeSection(id = "forest", title = "Forest", filters = Filters.of("query" to "forest")),
            HomeSection(id = "snow", title = "Snow", filters = Filters.of("query" to "snow")),
        )

    /**
     * The definitive record for an id: the wallpaper page states the
     * TRUE original's URL (the `get.wallhere.com` download anchor) and
     * its resolution (the meta description's tail), the display
     * image's own dimensions when the description omits them, the
     * uploader, and a fuller tag row than any grid cell. The thumb
     * stays BLANK on purpose — the grid item's own thumbnail is
     * better than anything this page states, and the host keeps it.
     * When the download anchor is missing, the display original stands
     * in as the file URL: still a real, servable file, honestly
     * lower-resolution. A page that states neither image anchor is
     * the source failure it is; the host keeps the grid item's values.
     */
    override suspend fun details(id: String): Result<WallpaperDetails> =
        runCatching {
            val response = get("$BASE_URL/en/wallpaper/${encode(id)}")
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            val record =
                WallhereParser.parseDetail(response.bodyText)
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
                        tags = record.tags.take(MAX_TAGS),
                        contentRating = ratingFor(record.tags),
                    ),
                author = record.author,
                resolution =
                    if (record.width != null && record.height != null) "${record.width}x${record.height}" else null,
                sourceUrl = "$BASE_URL/en/wallpaper/$id",
            )
        }

    /**
     * The site's own random page: one fresh batch of twenty-four,
     * served as-is (per-item classified — the bare contract call
     * carries no filters to map onto the site's purity switch). The
     * page reshuffles on every load — there is no page 2 of a batch.
     */
    override suspend fun random(): Result<List<Wallpaper>> =
        runCatching {
            val response = get(listingUrl(RANDOM_PATH, page = 1, sfwOnly = false))
            if (!response.isSuccessful) {
                throw httpError(response.statusCode)
            }
            WallhereParser.parseGrid(response.bodyText).map(::gridWallpaper)
        }

    // ---------------------------------------------------------------- feed

    /**
     * THE WALK — any listing page, content-verified.
     *
     * Fetch, parse the grid, read the pagination bar, and decide by
     * what the CONTENT says, never by trust in the page parameter:
     *
     * - page one is whatever it is — even empty is an honest "no
     *   results" for a fresh search — and its id set REPLACES the
     *   listing's memory (a refreshed feed starts a new walk);
     * - a continuation with FRESH cells (ids this walk has not served)
     *   served them, remembers them, and continues while the bar
     *   advertises more — or, bar-less (the mobile dialect), while
     *   the page came up FULL, the site's fixed twenty-four;
     * - a continuation with NO fresh cells is either the site clamping
     *   past the listing's true end (the bar marks a SHALLOWER page
     *   than requested, or advertises none so deep — the honest end)
     *   or the page parameter DEAD on this connection (the mobile
     *   dialect, a cache-flattened replay) — and the dead case
     *   CONTINUES ON THE RANDOM SURFACE instead of dying: the walk
     *   this listing's users scroll keeps being fed fresh wallpapers
     *   from a surface that needs no page parameter at all. The
     *   v1.x line errored or stopped silently here — the twenty-four
     *   item truncation this rewrite exists to kill.
     *
     * [reshuffle] is the random surface's own feed mode: every page
     * beyond the first is a fresh reshuffled draw of the bare path,
     * the id memory keeping the sequence duplicate-free without any
     * page parameter at all.
     */
    private suspend fun listingPage(
        path: String,
        page: Int,
        sfwOnly: Boolean,
        reshuffle: Boolean = false,
    ): Page {
        if (page < 1 || page > MAX_PAGES) return Page(emptyList(), nextPage = null)
        if (reshuffle && page > 1) {
            return randomContinuation(listingKey(path, sfwOnly), page, sfwOnly)
        }

        val response = get(listingUrl(path, page, sfwOnly))
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val items = WallhereParser.parseGrid(response.bodyText)
        val bar = WallhereParser.parsePagination(response.bodyText)
        val key = listingKey(path, sfwOnly)
        val seen = seenIdsFor(key)

        if (page == 1) {
            rememberSeenIds(key, items.map { it.id }.toSet())
            rememberTagsFrom(items)
            return Page(items.map(::gridWallpaper), nextPage = continueFrom(page, bar, items.size))
        }

        val fresh = items.filter { it.id !in seen }
        if (fresh.isNotEmpty()) {
            rememberSeenIds(key, seen + fresh.map { it.id })
            rememberTagsFrom(items)
            return Page(fresh.map(::gridWallpaper), nextPage = continueFrom(page, bar, fresh.size))
        }

        // No fresh cells. The bar, when present, names the honest end:
        // the site clamping a too-deep request to its LAST page (the
        // bar's CURRENT sits on its own deepest link — the one shape a
        // legitimate past-the-end answer carries). Everything else —
        // a shallower page's bar, a bar with no current marker, no bar
        // at all — is the page parameter dead on this connection, and
        // the random surface keeps the walk alive.
        val clampedPastEnd =
            bar.present &&
                (
                    (bar.currentPage != null && bar.currentPage == bar.maxPage && bar.currentPage < page) ||
                        (bar.currentPage == null && bar.maxPage in 1 until page)
                )
        if (clampedPastEnd) {
            return Page(emptyList(), nextPage = null)
        }
        return randomContinuation(key, page, sfwOnly)
    }

    /**
     * The walk's fallback lane: a fresh draw from the random surface —
     * the bare path reshuffles its batch on every load, on every
     * connection, in every dialect — deduped against the listing's
     * served-id memory so nothing repeats. A draw that repeats
     * everything the walk already served (a walk at the cap of a very
     * narrow pool) is the honest end; anything else keeps the scroll
     * fed, which is the entire point.
     */
    private suspend fun randomContinuation(
        key: String,
        page: Int,
        sfwOnly: Boolean,
    ): Page {
        val response = get(listingUrl(RANDOM_PATH, page = 1, sfwOnly = sfwOnly))
        if (!response.isSuccessful) {
            throw httpError(response.statusCode)
        }
        val seen = seenIdsFor(key)
        val fresh =
            WallhereParser.parseGrid(response.bodyText)
                .filter { it.id !in seen }
        if (fresh.isEmpty()) {
            return Page(emptyList(), nextPage = null)
        }
        rememberSeenIds(key, seen + fresh.map { it.id })
        rememberTagsFrom(fresh)
        return Page(
            fresh.map(::gridWallpaper),
            nextPage = if (page >= MAX_PAGES) null else page + 1,
        )
    }

    /**
     * The "more exists" signal for a page that just served
     * [freshCount] fresh cells: the bar governs where present (the
     * desktop dialect's own advertisement), a bar-less FULL page
     * advertises the next page itself (the mobile dialect — the site
     * pads every page of a continuing listing to its fixed size), and
     * the walk's deep-pagination cap bounds everything.
     */
    private fun continueFrom(
        page: Int,
        bar: WallhereParser.Pagination,
        freshCount: Int,
    ): Int? =
        when {
            page >= MAX_PAGES -> null
            bar.present -> if (bar.maxPage > page) page + 1 else null
            freshCount >= FULL_PAGE -> page + 1
            else -> null
        }

    // ------------------------------------------------------------- mapping

    /**
     * A grid cell to a wallpaper: the 1x thumbnail as
     * [Wallpaper.thumbUrl] and the display original (the thumbnail's
     * suffix swapped to `!d`, a real publicly served file) as
     * [Wallpaper.fullUrl]. Dimensions are deliberately absent: the
     * listing's `width`/`height` attributes are the masonry BOX
     * proportions, true of no wallpaper's pixels — a value that wrong
     * is worse than no value, and the app renders null dimensions as
     * "—". [details] is where the TRUE resolution arrives. The item's
     * rating is classified from its own tag row so the host's switch
     * filters the adult content this site mixes into its feeds.
     */
    private fun gridWallpaper(item: WallhereParser.GridItem): Wallpaper =
        Wallpaper(
            id = item.id,
            providerId = ID,
            thumbUrl = item.thumbUrl,
            fullUrl = item.displayUrl,
            title = null,
            tags = item.tags.take(MAX_TAGS),
            contentRating = ratingFor(item.tags),
        )

    /**
     * An item's rating, from the site's own vocabulary: NSFW when the
     * tag row carries an adult WORD — checked per word, so multi-word
     * rows ("panties down", "Lingerie Football League") classify
     * exactly like the bare vocabulary while innocent words ("grass",
     * "bass") never trip their shorter selves. The host's rating
     * switch does the filtering; this classification is what makes it
     * honest for a source whose server-side switch is only asked on
     * SFW-only requests.
     */
    private fun ratingFor(tags: List<String>): ContentRating =
        if (tags.any(::isAdultTag)) {
            ContentRating.NSFW
        } else {
            ContentRating.SFW
        }

    /** Harvests seen tags for [suggestTags]; a bonus, never a dependency. */
    private fun rememberTagsFrom(items: List<WallhereParser.GridItem>) {
        if (items.isEmpty()) return
        synchronized(lock) {
            items.flatMap { it.tags }.forEach { tag ->
                if (tagPool.size < TAG_POOL_LIMIT) tagPool.add(tag)
            }
        }
    }

    /**
     * The wallpaper ids a listing's walk has already served — the
     * content-verification memory, keyed by listing (path plus purity)
     * so the Popular tab, a category lane and a search never compare
     * notes. Page one REPLACES its listing's set: a refreshed feed is
     * a new walk, and a revisit must not answer "everything already
     * seen" with an empty grid. Access-ordered so the most recently
     * walked listing survives the [SEEN_LISTINGS_LIMIT] eviction; each
     * set is capped at the deepest legal walk's worth of ids.
     */
    private val seenIdsByListing = LinkedHashMap<String, LinkedHashSet<String>>(16, 0.75f, true)

    private fun seenIdsFor(listingKey: String): Set<String> =
        synchronized(lock) { seenIdsByListing[listingKey]?.toSet() ?: emptySet() }

    private fun rememberSeenIds(
        listingKey: String,
        ids: Set<String>,
    ) {
        synchronized(lock) {
            val trimmed =
                if (ids.size > SEEN_IDS_LIMIT) {
                    ids.drop(ids.size - SEEN_IDS_LIMIT)
                } else {
                    ids.toList()
                }
            seenIdsByListing[listingKey] = trimmed.toCollection(LinkedHashSet())
            // Access-ordered: the first key is the least recently walked
            // listing — the one an eviction should forget.
            while (seenIdsByListing.size > SEEN_LISTINGS_LIMIT) {
                seenIdsByListing.keys.firstOrNull()?.let { seenIdsByListing.remove(it) }
            }
        }
    }

    /**
     * True when a tag carries an adult word — checked per WORD
     * (spaces, hyphens and underscores all split), so multi-word rows
     * ("panties down", "Lingerie Football League") are caught exactly
     * like the bare vocabulary while innocent words ("grass", "bass")
     * never trip their shorter selves. THE SAME CHECK gates per-item
     * ratings and search-bar suggestions — the content filter and the
     * suggestion filter must agree about the same vocabulary.
     */
    private fun isAdultTag(tag: String): Boolean =
        tag.lowercase().split(' ', '-', '_').any { it in WallhereParser.ADULT_TAG_WORDS }

    // ------------------------------------------------------------- plumbing

    /**
     * True when the host asked for SFW-only content: the purity
     * selection carries `sfw` and nothing else. Sketchy-allowed and
     * unspecified requests both stay bare — the site's own default.
     */
    private fun wantsSfwOnly(filters: Filters): Boolean {
        val purity = filters.valuesFor("purity")
        return purity.isNotEmpty() && purity.none { it.equals("sketchy", ignoreCase = true) }
    }

    /**
     * The search-lane path of a host `category` value, when the site's
     * own vocabulary expresses one: `anime` rides the site's
     * anime-search grid, `people` the portrait grid — the honest
     * nearest expression of a value the site files no single category
     * under. `general` needs no lane: it is the default feed.
     */
    private fun categoryLane(category: String): String? =
        when (category.lowercase()) {
            "anime" -> searchPath("anime")
            "people" -> searchPath("portrait")
            else -> null
        }

    private fun listingKey(
        path: String,
        sfwOnly: Boolean,
    ): String = "$path|$sfwOnly"

    /**
     * The full listing URL: the path as given, the site's own
     * `NSFW=off` switch prepended to the query when the host asks for
     * SFW-only content, and `page=N` appended for deep pages — the
     * dialect the site's own bar links and nav links use.
     */
    private fun listingUrl(
        path: String,
        page: Int,
        sfwOnly: Boolean,
    ): String {
        val params = mutableListOf<String>()
        if (sfwOnly) params += "NSFW=off"
        if (page > 1) params += "page=$page"
        if (params.isEmpty()) return "$BASE_URL$path"
        val joiner = if (path.contains('?')) "&" else "?"
        return "$BASE_URL$path$joiner${params.joinToString("&")}"
    }

    /** The search listing path for [query], the site's own form encoding. */
    private fun searchPath(query: String): String = "/en/wallpapers?q=${encode(query)}"

    private suspend fun get(url: String): ProviderHttpResponse =
        httpClient?.get(url, DOCUMENT_HEADERS)
            ?: error("configure() was not called")

    private fun httpError(statusCode: Int): IllegalStateException = IllegalStateException("wallhere answered HTTP $statusCode")

    /** Form-style encoding: spaces as `+`, exactly how the site's own form submits. */
    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val ID = "cloudimage.wallhere"
        const val BASE_URL = "https://wallhere.com"

        /** The ranked feeds; the random surface behind the fallback lane. */
        const val POPULAR_PATH = "/en/wallpapers?order=popular"
        const val LATEST_PATH = "/en/wallpapers?order=latest"
        const val RANDOM_PATH = "/en/random"

        /** Deep-pagination cap: a hundred pages, twenty-four items each. */
        const val MAX_PAGES = 100

        /** The site's fixed page size — a full page of fresh cells means more exists. */
        const val FULL_PAGE = 24

        /**
         * The desktop Chrome identity every document fetch claims. The
         * site serves its listings to mobile agents in the bar-less
         * dialect, and the host's phone lanes are mobile or
         * Android-branded — so the provider asks for the desktop markup
         * explicitly, through the facade's sanctioned per-request
         * User-Agent override (the header rides on top of the host's
         * own; a WebView-earned clearance still binds its own agent on
         * replays, per the facade contract — and the content-verified
         * walk survives exactly that lane).
         */
        const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

        /** The headers every document fetch rides: the desktop identity alone. */
        val DOCUMENT_HEADERS = mapOf("User-Agent" to DESKTOP_USER_AGENT)

        /**
         * The suggestion seed — the site's own most-popular tags as its
         * live tag index states them (October 2026), adult vocabulary
         * and bare-year noise withheld at bake time. The harvested pool
         * supplements this from real grids; both lanes ride the same
         * per-word adult filter at run time.
         */
        val SEED_TAGS =
            listOf(
                "nature",
                "landscape",
                "anime",
                "anime girls",
                "artwork",
                "digital art",
                "video games",
                "sky",
                "sea",
                "water",
                "trees",
                "sunset",
                "mountains",
                "clouds",
                "animals",
                "forest",
                "city",
                "night",
                "car",
                "portrait",
                "brunette",
                "blonde",
                "long hair",
                "blue eyes",
                "Tokyo",
                "Japan",
                "Asian",
                "fantasy art",
                "simple background",
                "monochrome",
                "tattoo",
                "women",
                "model",
                "looking at viewer",
            )

        /** How many listings' seen-id sets the content-verification memory keeps. */
        const val SEEN_LISTINGS_LIMIT = 16

        /** Per listing: every id of the deepest legal walk, plus slack. */
        const val SEEN_IDS_LIMIT = MAX_PAGES * FULL_PAGE + FULL_PAGE

        /** Tag caps and the suggestion list size. */
        const val MAX_TAGS = 6
        const val TAG_POOL_LIMIT = 200
        const val TAG_SUGGESTION_LIMIT = 8

        /** One lock over the shared memory. */
        val lock = Any()
    }

    private val tagPool = LinkedHashSet<String>()
}
