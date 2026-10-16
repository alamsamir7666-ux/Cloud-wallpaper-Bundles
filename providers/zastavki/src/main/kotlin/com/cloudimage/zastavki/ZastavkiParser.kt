package com.cloudimage.zastavki

/**
 * Hand-rolled HTML mining for zastavki.com — the scraping half of this
 * provider, kept free of any parsing library on purpose.
 *
 * Extension packages carry ONLY their own classes: the build dexes the
 * module jar and nothing else, so a dependency like Jsoup would be
 * missing at load time (the host supplies the contract, nothing more).
 * Everything below is therefore stdlib string and regex work over the
 * site's server-rendered markup — the same technique every scraper in
 * this provider set uses.
 *
 * ## Where the shapes come from
 *
 * Anchors captured from the site's OWN live markup (October 2026 — the
 * site serves plain HTML to every client, no challenge anywhere):
 *
 * - every wallpaper cell on every listing — subcategory grids, the
 *   category pages, the homepage's "new images" block and the ranking
 *   route — is an anchor to a `/rus/…/wallpaper-{id}.htm` detail page
 *   wrapping an `<img>` whose `src` is a `/pictures/286x180/…` thumbnail
 *   and whose `alt` (or the anchor's `title`) carries the item title in
 *   the page's language: ENGLISH on `/eng/` listings, RUSSIAN on the
 *   ranking route. A cell can appear up to three times on one page
 *   (thumbnail, download fab, info link) — grid parsing dedupes by id;
 * - the image CDN serves derivable size families over the SAME path
 *   tail: `/pictures/286x180/{year}/{tail}_{sizeCode}.jpg` for grid
 *   thumbs and `/pictures/originals/{year}/{tail}_.jpg` for the TRUE
 *   original (the size code segment simply dropped) — the transform the
 *   detail page's own `og:image` states verbatim, and the originals are
 *   publicly served to every client with no referer check;
 * - the subcategory grid's pagination: numbered clean links
 *   `/rus/{cat}/{sub}/{N}/` whose deepest number is the last page —
 *   while the TOP-category pagination is 502-broken server-side, a site
 *   quirk this provider mirrors honestly by walking only page one of a
 *   flat category;
 * - the ranking route `?r=site/list&orderby=downloads|date&page=N` —
 *   the all-time most-downloaded walk and the latest walk, eleven items
 *   per page, paginating blind (no bar in the markup — the walk simply
 *   stops when a page comes back empty);
 * - the category page `/eng/{cat}/`: subcategory tiles as anchors to
 *   `/rus/{cat}/{sub}/` wrapping cover thumbnails whose `alt` is the
 *   subcategory's English name — and nothing but tiles distinguishes a
 *   subcategorized category from a FLAT one (flat pages show a
 *   wallpaper grid directly);
 * - the detail page `/rus/…/wallpaper-{id}.htm`: `og:image` +
 *   `og:image:width`/`og:image:height` stating the TRUE original's URL
 *   and pixel dimensions, and the uploader as the `user-added` anchor
 *   under the "Добавил" (added by) label.
 *
 * The parsers stay narrow on purpose: every one keys on those semantic
 * anchors rather than document order, so cosmetic redesigns degrade
 * parsing to "nothing found" instead of producing garbage. All functions
 * are pure and total: bad input yields empty lists and nulls, never
 * exceptions — callers decide what a miss means.
 */
internal object ZastavkiParser {
    /** One grid cell: everything a listing discloses about a wallpaper. */
    data class GridItem(
        /**
         * The wallpaper's site path — its detail href minus the `/rus/`
         * prefix and `.htm` suffix, e.g. `Nature/Mountains/wallpaper-179442`.
         * Doubles as the [com.cloudimage.provider.api.WallpaperProvider.details]
         * key: the path is the only way back to the page.
         */
        val sitePath: String,
        /** The grid thumbnail, absolutized. */
        val thumbUrl: String,
        /** The original image, derived by the CDN's own size-family transform. */
        val fullUrl: String,
        /** The item title — English wherever the site states one. */
        val title: String,
        /** The category path the detail href carries, e.g. `Nature/Mountains`. */
        val categoryPath: String,
    )

    /** One subcategory tile of a category page. */
    data class Tile(
        /** The subcategory's path under its category, e.g. `Nature/Mountains`. */
        val path: String,
        /** The subcategory's English name, as the tile's alt states it. */
        val name: String,
        /** The tile's cover thumbnail, absolutized. */
        val coverUrl: String,
    )

    /** The definitive record of a detail page. */
    data class DetailRecord(
        /** The TRUE original's URL, as `og:image` states it, absolutized. */
        val originalUrl: String,
        /** The original's pixel width, as `og:image:width` states it. */
        val width: Int?,
        /** The original's pixel height, as `og:image:height` states it. */
        val height: Int?,
        /** The uploader, the `user-added` anchor's span text. */
        val author: String?,
    )

    // ------------------------------------------------------------- patterns

    /**
     * One grid cell: an anchor to a wallpaper detail page wrapping the
     * thumbnail img. The href's category path is captured so grids can
     * be scoped to the listing that owns them (a page's info/download
     * links and any sidebar never leak foreign items in), and the img
     * tag is captured whole so its own attributes can be mined
     * separately.
     */
    private val GRID_CELL =
        Regex(
            """<a\b[^>]*href="(?:https?://[^/"]*)?/rus/([^"]*)/wallpaper-(\d+)\.htm"[^>]*>\s*(<img\b[^>]*>)""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** The `src` of any img tag — the grid thumbnail on this site. */
    private val IMG_SRC = Regex("""\ssrc="([^"]+)"""")

    /** The `alt` of any img tag — the item title in the page's language. */
    private val IMG_ALT = Regex("""\salt="([^"]*)"""")

    /** The `title` of an anchor — the same title the site states twice. */
    private val ANCHOR_TITLE = Regex("""\stitle="([^"]*)"""")

    /**
     * One subcategory tile: an anchor to the subcategory's own listing
     * wrapping its cover img. Only `/rus/` targets count — the tiles the
     * English pages render still link the canonical Russian paths.
     */
    private val SUBCATEGORY_TILE =
        Regex(
            """<a\b[^>]*href="(?:https?://[^/"]*)?/rus/([^"]+)/"[^>]*>\s*(<img\b[^>]*>)""",
            RegexOption.DOT_MATCHES_ALL,
        )

    /** The detail page's og:image, property-then-content attribute order. */
    private val OG_IMAGE =
        Regex("""<meta\b[^>]*property="og:image"[^>]*content="([^"]*)"""")

    /** Same, attribute order flipped. */
    private val OG_IMAGE_FLIPPED =
        Regex("""<meta\b[^>]*content="([^"]*)"[^>]*property="og:image"[^>]*>""")

    /** The og:image width declaration, either attribute order. */
    private val OG_WIDTH =
        Regex(
            """<meta\b[^>]*(?:property="og:image:width"[^>]*content="(\d+)"|content="(\d+)"[^>]*property="og:image:width")""",
        )

    /** The og:image height declaration, either attribute order. */
    private val OG_HEIGHT =
        Regex(
            """<meta\b[^>]*(?:property="og:image:height"[^>]*content="(\d+)"|content="(\d+)"[^>]*property="og:image:height")""",
        )

    /** The uploader's anchor — the span under the "Добавил" label. */
    private val USER_ADDED =
        Regex(
            """<a\b[^>]*class="[^"]*user-added[^"]*"[^>]*>\s*(?:<span\b[^>]*>)?\s*([^<]*?)\s*(?:</span>)?\s*</a>""",
        )

    /**
     * The thumbnail's size-family tail: `{anything}_{sizeCode}.jpg`.
     * The size code (32 for 286x180 thumbs) is what the original drops.
     */
    private val THUMB_SIZE_SUFFIX = Regex("""_(\d{1,3})\.jpe?g$""", RegexOption.IGNORE_CASE)

    /** A Cyrillic character — the tell of a Russian-language title. */
    private fun isCyrillic(text: String): Boolean = text.any { it.code in CYRILLIC_FIRST..CYRILLIC_LAST }

    private const val CYRILLIC_FIRST = 0x0400
    private const val CYRILLIC_LAST = 0x04FF

    /** The site's image host — thumb and original paths are site-rooted. */
    private const val IMAGE_HOST = "https://www.zastavki.com"

    // ---------------------------------------------------------------- grid

    /**
     * The wallpaper cells of any listing. [owningPath] scopes the scan
     * when known — a subcategory or flat-category grid only accepts
     * cells whose category path IS that listing's (the same pages carry
     * info/download links to the same wallpapers, and some render
     * sidebars of site-wide latest); the ranking route and homepage pass
     * `null` and accept every cell. Titles are the English ones wherever
     * the site states them: an English attr wins outright, a Cyrillic
     * attr is replaced by the title mined from the thumbnail's own URL
     * slug (`…{title}_{id}_{size}.jpg`), and a slug that states nothing
     * degrades to the wallpaper's id. The full URL is the thumbnail's
     * size-family sibling on `originals` — the exact transform the
     * detail page's `og:image` states, a real file served to anyone.
     */
    fun parseGrid(
        html: String,
        owningPath: String?,
    ): List<GridItem> {
        val byId = LinkedHashMap<String, GridItem>()
        for (match in GRID_CELL.findAll(html)) {
            val categoryPath = match.groupValues[1]
            val id = match.groupValues[2]
            if (owningPath != null && categoryPath != owningPath) continue
            val img = match.groupValues[3]
            val thumb = IMG_SRC.find(img)?.groupValues?.get(1) ?: continue
            if (!thumb.contains("286x180")) continue
            val alt =
                IMG_ALT
                    .find(img)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            val anchorTitle =
                ANCHOR_TITLE
                    .find(match.groupValues[0])
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            val stated = listOf(alt, anchorTitle).firstOrNull { it.isNotBlank() && !isCyrillic(it) }
            val title =
                stated
                    ?: slugTitle(thumb, categoryPath)?.takeIf { it.isNotBlank() }
                    ?: "Wallpaper $id"
            val full = originalOf(thumb) ?: continue
            byId[id] =
                GridItem(
                    sitePath = "$categoryPath/wallpaper-$id",
                    thumbUrl = absolutize(thumb),
                    fullUrl = absolutize(full),
                    title = title,
                    categoryPath = categoryPath,
                )
        }
        return byId.values.toList()
    }

    // ---------------------------------------------------------------- tiles

    /**
     * The subcategory tiles of a category page — the albums of one
     * category, in the site's own order. English pages state English
     * names in the tile's alt; a Cyrillic alt is replaced by the slug
     * mined from the cover's URL, underscores become spaces.
     */
    fun parseTiles(
        html: String,
        categorySlug: String,
    ): List<Tile> {
        val byPath = LinkedHashMap<String, Tile>()
        for (match in SUBCATEGORY_TILE.findAll(html)) {
            val path = match.groupValues[1]
            if (!path.startsWith("$categorySlug/")) continue
            val sub = path.removePrefix("$categorySlug/")
            if (sub.isBlank() || sub.contains('/')) continue
            val img = match.groupValues[2]
            val cover = IMG_SRC.find(img)?.groupValues?.get(1) ?: continue
            if (!cover.contains("286x180")) continue
            val alt =
                IMG_ALT
                    .find(img)
                    ?.groupValues
                    ?.get(1)
                    .orEmpty()
            val name =
                alt.takeIf { it.isNotBlank() && !isCyrillic(it) }
                    ?: sub.replace('_', ' ')
            byPath[path] =
                Tile(
                    path = path,
                    name = name,
                    coverUrl = absolutize(cover),
                )
        }
        return byPath.values.toList()
    }

    // ------------------------------------------------------------ pagination

    /**
     * The deepest page number a listing's own pagination bar advertises
     * — null when the markup states no numbered link at all (the
     * ranking route's blind walk).
     */
    fun parseMaxPage(
        html: String,
        listingPath: String,
    ): Int? {
        val link =
            Regex(
                """<a\b[^>]*href="(?:https?://[^/"]*)?/rus/${Regex.escape(listingPath)}/(\d+)(?:/|")"""",
            )
        return link
            .findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .maxOrNull()
    }

    // --------------------------------------------------------------- detail

    /**
     * The definitive record of a wallpaper page: the `og:image` original
     * with its true pixel dimensions, and the uploader. Null when the
     * page states no og:image — a redesigned or wrong page, which the
     * caller reports as the miss it is.
     */
    fun parseDetail(html: String): DetailRecord? {
        val image =
            OG_IMAGE.find(html)?.groupValues?.get(1)
                ?: OG_IMAGE_FLIPPED.find(html)?.groupValues?.get(1)
                ?: return null
        val width = OG_WIDTH.find(html)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
        val height = OG_HEIGHT.find(html)?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
        val author =
            USER_ADDED
                .find(html)
                ?.groupValues
                ?.get(1)
                ?.takeIf { it.isNotBlank() }
        return DetailRecord(
            originalUrl = absolutize(image),
            width = width?.toIntOrNull(),
            height = height?.toIntOrNull(),
            author = author,
        )
    }

    // ---------------------------------------------------------------- helpers

    /**
     * The thumbnail's original sibling: the trailing `_{sizeCode}`
     * dropped and the size segment swapped to `originals` — the exact
     * transform the detail page's own `og:image` states (`…
     * /pictures/286x180/2025/{tail}_179841_32.jpg` ->
     * `…/pictures/originals/2025/{tail}_179841_.jpg`). Null when the
     * thumbnail does not carry the expected shape.
     */
    private fun originalOf(thumbUrl: String): String? {
        val withoutSize =
            THUMB_SIZE_SUFFIX
                .replace(thumbUrl, "_.jpg")
                .takeIf { it != thumbUrl } ?: return null
        return withoutSize.replace("/pictures/286x180/", "/pictures/originals/")
    }

    /**
     * The English title mined from a thumbnail's URL slug: the file name
     * without its category prefix — `{cat}_` or `{cat}___{sub}_`, the
     * site has used both separators across its years, so both are tried
     * bounded by the href's OWN [categoryPath] — and without its
     * trailing `_{id}_{size}` pair, underscores become spaces. A title
     * that begins with its category's own name survives intact: only
     * one prefix instance is ever stripped.
     */
    private fun slugTitle(
        thumbUrl: String,
        categoryPath: String,
    ): String? {
        val base = thumbUrl.substringAfterLast('/').substringBeforeLast('.')
        val segments = categoryPath.split('/')
        val prefixes =
            buildList {
                if (segments.size == 2) {
                    add("${segments[0]}___${segments[1]}_")
                    add("${segments[0]}_${segments[1]}_")
                }
                add("${segments[0]}_")
            }
        var stripped = base
        for (prefix in prefixes) {
            if (stripped.startsWith(prefix)) {
                stripped = stripped.removePrefix(prefix)
                break
            }
        }
        val words = stripped.split('_')
        if (words.size < 3) return null
        val tail = words.takeLast(2)
        if (tail.any { it.toIntOrNull() == null }) return null
        return words
            .dropLast(2)
            .joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }

    /** Site-rooted image paths become absolute; already-absolute pass through. */
    private fun absolutize(url: String): String =
        when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$IMAGE_HOST$url"
            else -> "$IMAGE_HOST/$url"
        }
}
