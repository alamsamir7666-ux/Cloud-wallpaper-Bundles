package com.cloudimage.wallhere

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.HomeSection
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The v2.0.1 walk's suite, over a scripted fake of the plugin-facing
 * HTTP facade, with fixtures in the shapes of wallhere.com's LIVE
 * markup (the `current-item-photo` masonry cells with their `!s1`/
 * `!s2` CDN thumbnails and tag rows in the img's `title`, the desktop
 * `<ul class="pagination">` bar with its active marker and `?page=N`
 * links, the bar-less mobile dialect, the wallpaper page's
 * `contentURL` img, `get.wallhere.com` download anchor and profile
 * link) — all covered without a network, exactly like the suite this
 * repo's other providers ship.
 *
 * The center of the suite is the pair of live-probed facts the
 * v2.0.1 line exists to serve (October 2026):
 *
 * - the random surface is a STABLE, DEEP LISTING (14,295 pages by its
 *   own bar), NOT a per-load reshuffle — the bare-path re-draw the
 *   v2.0.0 fallback trusted served exactly one usable draw and then
 *   repeated the walk's memory, so every feed died at a couple dozen
 *   items. The fallback now WALKS the random listing's own pages by a
 *   per-listing cursor, deep as the listing goes;
 * - the `?q=` search lane is IGNORED by the site (a nonsense `q=`
 *   answers with the popular grid unchanged) — search now resolves
 *   queries through the baked tag catalogue and walks the REAL
 *   precision lane, `/en/tag/{id}`, and a query resolving to nothing
 *   answers empty instead of serving the popular feed as "results".
 *
 * A connection whose page parameter does not advance (the mobile
 * dialect, a cache-flattened replay) answers every continuation with
 * page one's items again — the lane the fallback keeps fed, deduped
 * by the per-listing id memory, so no duplicate ever reaches the
 * grid.
 */
class WallhereWallpaperProviderTest {
    private val provider = WallhereWallpaperProvider()

    /**
     * URL-routed responses, matched EXACTLY — the walk builds its URLs
     * deterministically, so an unmatched URL is a routing bug worth
     * failing loudly on (500).
     */
    private class FakeClient : ProviderHttpClient {
        val requests = mutableListOf<String>()
        val sentHeaders = mutableListOf<Map<String, String>>()
        var routes: Map<String, ProviderHttpResponse> = emptyMap()

        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            requests += url
            sentHeaders += headers
            return routes[url] ?: ProviderHttpResponse(500, emptyMap(), ByteArray(0))
        }
    }

    private fun ok(body: String): ProviderHttpResponse = ProviderHttpResponse(200, emptyMap(), body.toByteArray())

    private fun configureWith(routes: Map<String, ProviderHttpResponse>): FakeClient =
        FakeClient().apply {
            this.routes = routes
            provider.configure(this, ProviderSettings { null })
        }

    // ---------------------------------------------------------- fixtures

    /**
     * A grid cell in the site's own masonry shape: the
     * `current-item-photo` anchor (numeric id in the detail href, both
     * attribute orders seen live), the tag row in the img's `title`,
     * the 1x thumb in `src`, the 2x in `data-src`, the masonry box
     * proportions in `width`/`height` (never published as dimensions).
     */
    private fun cell(
        id: Int,
        tags: String = "nature,mountains,landscape",
    ): String =
        """
        <a href="/en/wallpaper/$id" class="current-item-photo">
                    <img width="240" height="340" alt="$tags" title="$tags" src="https://c.wallhere.com/photos/11/22/fixture_$id.jpg!s1" data-src="https://c.wallhere.com/photos/11/22/fixture_$id.jpg!s2" srcset="https://c.wallhere.com/photos/11/22/fixture_$id.jpg!s1 1x, https://c.wallhere.com/photos/11/22/fixture_$id.jpg!s2 2x"></a>
        """.trimIndent()

    /** [count] cells with sequential ids from [firstId] — a full page is 24. */
    private fun gridOf(
        count: Int,
        firstId: Int = 3000001,
        tags: String = "nature,mountains,landscape",
    ): String = (0 until count).joinToString("\n") { cell(firstId + it, tags) }

    /**
     * The desktop dialect's pagination bar, live shape: the active
     * marker on the CURRENT page, numbered `?page=N` links, ellipsis,
     * the deepest pages, `«`/`»` edge arrows degrading to
     * `javascript:void(0)` at the ends.
     */
    private fun bar(
        current: Int,
        max: Int,
    ): String {
        val numbered =
            (1..minOf(5, max)).joinToString("") { page ->
                val active = if (page == current) "active" else ""
                val href = if (page == 1) "/" else "/?page=$page"
                """<li class="$active"><a href="$href">$page</a></li>"""
            }
        return """
            <ul class="pagination pagination-lg"><li><a href="javascript:void(0)"><i class="fa fa-angle-left">«</i></a></li>$numbered<li class=""><a href="javascript:void(0)">...</a></li><li class=""><a href="/?page=${max - 1}}">${max - 1}</a></li><li class=""><a href="/?page=$max">$max</a></li><li><a href="javascript:void(0)" title="»"><i class="fa fa-angle-right">»</i></a></li></ul>
        """.trimIndent()
    }

    /** The bare listing URL of the popular order (page one, unfiltered). */
    private val popularUrl = "https://wallhere.com/en/wallpapers?order=popular"

    private fun popularPage(n: Int): String = "$popularUrl&page=$n"

    /** The random listing's page [n] — the fallback's deep lane. */
    private val randomUrl = "https://wallhere.com/en/random"

    private fun randomPage(n: Int): String =
        if (n == 1) randomUrl else "$randomUrl?page=$n"

    /** The Nature tag's grid (catalogue id 212) — the search precision lane. */
    private val natureTagUrl = "https://wallhere.com/en/tag/212"

    private fun natureTagPage(n: Int): String =
        if (n == 1) natureTagUrl else "$natureTagUrl?page=$n"

    // ------------------------------------------------- the desktop walk

    /**
     * The desktop dialect's happy walk: page one serves a full page
     * under a bar advertising seventy thousand pages, and every
     * continuation serves the NEXT page's fresh cells under a bar
     * that confirms the page requested — the walk follows the bar as
     * deep as the site goes, verifiably past the twenty-four-item
     * cliff the retired line died on.
     */
    @Test
    fun desktopWalkFollowsTheBar() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        popularUrl to ok("${gridOf(24, 3000001)}\n${bar(1, 70329)}"),
                        popularPage(2) to ok("${gridOf(24, 3000025)}\n${bar(2, 70329)}"),
                        popularPage(3) to ok("${gridOf(24, 3000049)}\n${bar(3, 70329)}"),
                    ),
                )

            val first = provider.popular(1, Filters.None).getOrThrow()
            assertEquals(24, first.wallpapers.size)
            assertEquals(2, first.nextPage)

            val second = provider.popular(2, Filters.None).getOrThrow()
            assertEquals(24, second.wallpapers.size)
            assertEquals(24, second.wallpapers.count { it.id.toInt() >= 3000025 })
            assertEquals(3, second.nextPage)

            val third = provider.popular(3, Filters.None).getOrThrow()
            assertEquals(24, third.wallpapers.size)
            assertEquals(24, third.wallpapers.count { it.id.toInt() >= 3000049 })
            assertEquals(4, third.nextPage)
            assertEquals(
                listOf(popularUrl, popularPage(2), popularPage(3)),
                client.requests,
            )
        }

    /**
     * The bar's LAST page: the active marker sits on the deepest
     * numbered link, and the walk ends there — a page one whose bar
     * admits only two pages offers a second, the short second page
     * offers nothing, and a STALE request beyond the end (the site
     * clamps it to the last page, whose bar confirms) is the honest
     * empty end, with no random fallback spent on it.
     */
    @Test
    fun shortListingEndsWhereTheBarSays() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        popularUrl to ok("${gridOf(24, 3000001)}\n${bar(1, 2)}"),
                        popularPage(2) to ok("${gridOf(10, 3000025)}\n${bar(2, 2)}"),
                        // A too-deep request: the site serves its LAST
                        // page again, bar confirming — the clamp shape.
                        popularPage(3) to ok("${gridOf(10, 3000025)}\n${bar(2, 2)}"),
                    ),
                )

            val first = provider.popular(1, Filters.None).getOrThrow()
            assertEquals(2, first.nextPage)

            val second = provider.popular(2, Filters.None).getOrThrow()
            assertEquals(10, second.wallpapers.size)
            assertNull(second.nextPage)

            val stale = provider.popular(3, Filters.None).getOrThrow()
            assertTrue("the clamped end answers an empty page", stale.wallpapers.isEmpty())
            assertNull(stale.nextPage)
            assertEquals(
                "the honest clamp end spends no random fetch",
                listOf(popularUrl, popularPage(2), popularPage(3)),
                client.requests,
            )
        }

    /**
     * The dead parameter's DESKTOP shape — the v1.1.1 field failure
     * verbatim: the continuation answers page ONE again, bar and all
     * (current=1, max=70329 — a live listing, not an end). The v1.x
     * line threw here, naming the mismatch; the walk reads the
     * all-repeat shape for what it is — the parameter did not take on
     * this connection — and continues on the random listing's first
     * un-walked page.
     */
    @Test
    fun deadParameterOnTheDesktopDialectRidesTheRandomLane() =
        runTest {
            val pageOne = "${gridOf(24, 3000001)}\n${bar(1, 70329)}"
            val client =
                configureWith(
                    mapOf(
                        popularUrl to ok(pageOne),
                        popularPage(2) to ok(pageOne),
                        randomPage(1) to ok(gridOf(24, 4000001)),
                    ),
                )

            provider.popular(1, Filters.None).getOrThrow()
            val second = provider.popular(2, Filters.None).getOrThrow()
            assertEquals(24, second.wallpapers.size)
            assertEquals(24, second.wallpapers.count { it.id.toInt() >= 4000001 })
            assertEquals(3, second.nextPage)
            assertEquals(
                listOf(popularUrl, popularPage(2), randomPage(1)),
                client.requests,
            )
        }

    // ------------------------------ THE regression: the dead-parameter lane

    /**
     * THE v2.0.0 FAILURE, the exact field shape this rewrite exists to
     * kill: a connection (the mobile dialect, a cache-flattened
     * replay) answering every `?page=N` continuation with page one's
     * items again — bar-less, so no bar governs. The v2.0.0 fallback
     * re-drew the BARE random path and trusted the reshuffle that
     * probing proved absent, so the SECOND draw repeated the walk's
     * memory and the walk died anyway. The v2.0.1 fallback WALKS the
     * random listing's own pages: each dead continuation draws the
     * NEXT page the listing's cursor has not served — deep as the
     * fourteen-thousand-page listing goes, deduped, never dead.
     */
    @Test
    fun deadParameterContinuesOnTheRandomListingPages() =
        runTest {
            val pageOneGrid = gridOf(24, 3000001)
            val client =
                configureWith(
                    mapOf(
                        // The mobile dialect: full 24-cell grid, NO bar.
                        popularUrl to ok(pageOneGrid),
                        // The dead parameter: every continuation answers page one.
                        popularPage(2) to ok(pageOneGrid),
                        popularPage(3) to ok(pageOneGrid),
                        // The random listing: STABLE pages, one per draw.
                        randomPage(1) to ok(gridOf(24, 4000001)),
                        randomPage(2) to ok(gridOf(24, 4000025)),
                    ),
                )

            val first = provider.popular(1, Filters.None).getOrThrow()
            assertEquals(24, first.wallpapers.size)
            // Bar-less full page: the walk advertises the next page itself.
            assertEquals(2, first.nextPage)

            val second = provider.popular(2, Filters.None).getOrThrow()
            assertTrue("the walk must survive the dead parameter", second.wallpapers.isNotEmpty())
            assertEquals(24, second.wallpapers.size)
            assertEquals(
                "the fallback serves only fresh cells",
                24,
                second.wallpapers.count { it.id.toInt() >= 4000001 },
            )
            assertEquals("the walk continues past twenty-four", 3, second.nextPage)
            assertEquals(
                "the dead probe precedes the first random page",
                listOf(popularUrl, popularPage(2), randomPage(1)),
                client.requests,
            )

            // Page three: the lane is still dead, and the fallback
            // walks DEEPER — the listing's second page, not a re-draw
            // of the first (the v2.0.0 cliff, on the lane that killed
            // it: the bare-path re-draw answered the same batch again).
            val third = provider.popular(3, Filters.None).getOrThrow()
            assertEquals(24, third.wallpapers.size)
            assertEquals(24, third.wallpapers.count { it.id.toInt() >= 4000025 })
            assertEquals(4, third.nextPage)
            assertEquals(
                listOf(popularUrl, popularPage(2), randomPage(1), popularPage(3), randomPage(2)),
                client.requests,
            )

            val served = (first.wallpapers + second.wallpapers + third.wallpapers).map { it.id }
            assertEquals("no duplicate ever reaches the grid", served.size, served.distinct().size)
        }

    /**
     * The fallback's skip-ahead: a random page that repeats the walk's
     * whole memory (an overlapping shuffle region, a boundary) is not
     * the end yet — the cursor skips forward, bounded, and the next
     * page feeds the walk.
     */
    @Test
    fun allRepeatRandomPagesAreSkippedNotFatal() =
        runTest {
            val pageOneGrid = gridOf(24, 3000001)
            val client =
                configureWith(
                    mapOf(
                        popularUrl to ok(pageOneGrid),
                        popularPage(2) to ok(pageOneGrid),
                        // Random pages one AND two repeat the memory…
                        randomPage(1) to ok(gridOf(24, 3000001)),
                        randomPage(2) to ok(gridOf(24, 3000001)),
                        // …page three finally serves fresh cells.
                        randomPage(3) to ok(gridOf(24, 4000001)),
                    ),
                )

            provider.popular(1, Filters.None).getOrThrow()
            val second = provider.popular(2, Filters.None).getOrThrow()
            assertEquals(24, second.wallpapers.size)
            assertEquals(24, second.wallpapers.count { it.id.toInt() >= 4000001 })
            assertEquals(
                "the lane skipped the repeat pages without dying",
                listOf(popularUrl, popularPage(2), randomPage(1), randomPage(2), randomPage(3)),
                client.requests,
            )
        }

    /**
     * The dead-parameter lane's own end: a random listing that repeats
     * the walk's whole memory page after page, past the skip-ahead's
     * bound, is the honest end — never an error, never a loop.
     */
    @Test
    fun exhaustedRandomListingEndsHonestly() =
        runTest {
            val pageOneGrid = gridOf(24, 3000001)
            val client =
                configureWith(
                    mapOf(
                        popularUrl to ok(pageOneGrid),
                        popularPage(2) to ok(pageOneGrid),
                        // Every random page repeats the walk's memory.
                        randomPage(1) to ok(gridOf(24, 3000001)),
                        randomPage(2) to ok(gridOf(24, 3000001)),
                        randomPage(3) to ok(gridOf(24, 3000001)),
                        randomPage(4) to ok(gridOf(24, 3000001)),
                    ),
                )

            provider.popular(1, Filters.None).getOrThrow()
            val second = provider.popular(2, Filters.None).getOrThrow()
            assertTrue(second.wallpapers.isEmpty())
            assertNull(second.nextPage)
            // One probe plus the cursor's page and three skips.
            assertEquals(6, client.requests.size)
        }

    /**
     * A PARTIAL overlap on a bar-less page — the site clamps a
     * too-deep request to its last page, half of which the walk
     * already served: only the fresh cells are served, and a short
     * bar-less page is the honest end (the mobile dialect's own
     * "nothing more" shape).
     */
    @Test
    fun partialOverlapServesOnlyFreshCells() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        popularUrl to ok(gridOf(24, 3000001)),
                        // Twelve cells the walk served, twelve fresh.
                        popularPage(2) to ok(gridOf(12, 3000001) + "\n" + gridOf(12, 3000025)),
                    ),
                )

            provider.popular(1, Filters.None).getOrThrow()
            val second = provider.popular(2, Filters.None).getOrThrow()
            assertEquals(12, second.wallpapers.size)
            assertEquals(12, second.wallpapers.count { it.id.toInt() >= 3000025 })
            assertNull("a short bar-less page is the last one", second.nextPage)
        }

    /**
     * A bar-less EMPTY continuation (the mobile dialect's ran-out
     * shape, after a full page one): the random lane decides — a
     * fresh page continues the walk, never an error like v1.1.1's and
     * never the silent stop of v1.1.0.
     */
    @Test
    fun barLessEmptyContinuationRidesTheRandomLane() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        popularUrl to ok(gridOf(24, 3000001)),
                        popularPage(2) to ok(""),
                        randomPage(1) to ok(gridOf(24, 4000001)),
                    ),
                )

            provider.popular(1, Filters.None).getOrThrow()
            val second = provider.popular(2, Filters.None).getOrThrow()
            assertEquals(24, second.wallpapers.size)
            assertEquals(3, second.nextPage)
        }

    // ----------------------------------------------------- search & feeds

    /**
     * Search resolves through the tag catalogue onto the REAL
     * precision lane — `/en/tag/{id}` — because the site's `?q=`
     * parameter is dead (probed live: a nonsense `q=` answers with
     * the popular grid unchanged). "nature" walks the Nature tag's
     * own grid, page by page with the same content-verified
     * continuation.
     */
    @Test
    fun searchRidesTheTagLane() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        natureTagPage(1) to ok("${gridOf(24, 5000001)}\n${bar(1, 6250)}"),
                        natureTagPage(2) to ok("${gridOf(24, 5000025)}\n${bar(2, 6250)}"),
                    ),
                )

            val first = provider.search("nature", 1, Filters.None).getOrThrow()
            assertEquals(24, first.wallpapers.size)
            assertEquals(2, first.nextPage)
            assertEquals(listOf(natureTagUrl), client.requests)

            val second = provider.search("nature", 2, Filters.None).getOrThrow()
            assertEquals(24, second.wallpapers.size)
            assertTrue(second.wallpapers.all { it.id.toInt() >= 5000025 })
        }

    /**
     * A multi-word query resolves through its content-bearing noun:
     * "indian women" names no whole tag, but "women" does — the
     * word-tier of the cascade keeps multi-word queries precise
     * instead of dead.
     */
    @Test
    fun multiWordSearchResolvesItsNouns() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/tag/72" to ok("${gridOf(24, 5000001)}\n${bar(1, 8000)}"),
                    ),
                )

            val page = provider.search("indian women", 1, Filters.None).getOrThrow()
            assertEquals(24, page.wallpapers.size)
            assertEquals(listOf("https://wallhere.com/en/tag/72"), client.requests)
        }

    /**
     * A query resolving to NO tag is the honest empty answer: the
     * site's `q=` lane would answer it with the popular grid (the
     * silent lie the v2.0.0 line told), and the tag lane answers
     * "nothing here matches" — with no fetch spent.
     */
    @Test
    fun unmatchedSearchAnswersEmpty() =
        runTest {
            val client = configureWith(emptyMap())

            val miss = provider.search("ghosts of mars", 1, Filters.None).getOrThrow()
            assertTrue(miss.wallpapers.isEmpty())
            assertNull(miss.nextPage)
            assertTrue("an unmatched query costs no fetch", client.requests.isEmpty())
        }

    /** A BLANK query rides the latest order, at the page the host asked for. */
    @Test
    fun blankQueryRidesTheLatestOrder() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpapers?order=latest&page=2" to ok("${gridOf(24, 3000025)}\n${bar(2, 70329)}"),
                    ),
                )

            val second = provider.search("", 2, Filters.None).getOrThrow()
            assertEquals(24, second.wallpapers.size)
            assertEquals(listOf("https://wallhere.com/en/wallpapers?order=latest&page=2"), client.requests)
        }

    /** `sorting=date` walks the latest order; a recognized category walks its own tag lane. */
    @Test
    fun hostVocabularyMapsOntoTheSiteLanes() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpapers?order=latest" to ok("${gridOf(24)}\n${bar(1, 70329)}"),
                        "https://wallhere.com/en/tag/150" to ok("${gridOf(24, 5000001)}\n${bar(1, 6645)}"),
                        "https://wallhere.com/en/tag/80" to ok("${gridOf(24, 6000001)}\n${bar(1, 10355)}"),
                    ),
                )

            val latest = provider.popular(1, Filters.of("sorting" to "date")).getOrThrow()
            assertEquals("https://wallhere.com/en/wallpapers?order=latest", client.requests.last())

            val anime = provider.popular(1, Filters.of("category" to "anime")).getOrThrow()
            assertEquals("https://wallhere.com/en/tag/150", client.requests.last())
            assertTrue(anime.wallpapers.all { it.id.toInt() >= 5000001 })

            val people = provider.popular(1, Filters.of("category" to "people")).getOrThrow()
            assertEquals("https://wallhere.com/en/tag/80", client.requests.last())
        }

    /**
     * `sorting=random` walks the random listing's OWN pages: the
     * listing is stable between loads (probed live — the bare path
     * answers the same batch minute over minute), so its depth, not a
     * reshuffle, is what feeds the walk — page one bare, every
     * continuation at `?page=N`, deduped by the id memory.
     */
    @Test
    fun randomSortingWalksItsOwnPages() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        randomPage(1) to ok(gridOf(24, 7000001)),
                        randomPage(2) to ok(gridOf(24, 7000025)),
                    ),
                )

            val first = provider.popular(1, Filters.of("sorting" to "random")).getOrThrow()
            assertEquals(24, first.wallpapers.size)
            assertEquals(2, first.nextPage)

            val second = provider.popular(2, Filters.of("sorting" to "random")).getOrThrow()
            assertEquals(24, second.wallpapers.size)
            assertEquals(24, second.wallpapers.count { it.id.toInt() >= 7000025 })
            assertEquals(3, second.nextPage)
            assertEquals(
                "the random feed rides the listing's own page parameter",
                listOf(randomPage(1), randomPage(2)),
                client.requests,
            )
        }

    /** The bare contract call: the random listing's first page, served as-is. */
    @Test
    fun randomServesOneBatch() =
        runTest {
            val client =
                configureWith(
                    mapOf(randomPage(1) to ok(gridOf(24, 7000001))),
                )

            val batch = provider.random().getOrThrow()
            assertEquals(24, batch.size)
            assertEquals(listOf(randomPage(1)), client.requests)
        }

    // ------------------------------------------------------ purity & identity

    /** SFW-only requests ride the site's own NSFW=off switch on every URL — listings and the random fallback alike. */
    @Test
    fun sfwOnlyRidesTheSiteSwitchEverywhere() =
        runTest {
            val sfwPopular = "$popularUrl&NSFW=off"
            val sfwRandomPage1 = "$randomUrl?NSFW=off"
            val pageOneGrid = gridOf(24, 3000001)
            val client =
                configureWith(
                    mapOf(
                        sfwPopular to ok(pageOneGrid),
                        "$sfwPopular&page=2" to ok(pageOneGrid),
                        sfwRandomPage1 to ok(gridOf(24, 4000001)),
                    ),
                )

            val first = provider.popular(1, Filters.of("purity" to "sfw")).getOrThrow()
            assertEquals(24, first.wallpapers.size)

            val second = provider.popular(2, Filters.of("purity" to "sfw")).getOrThrow()
            assertEquals(24, second.wallpapers.size)
            assertEquals(
                "the switch rides the dead-parameter probe AND the random page",
                listOf(sfwPopular, "$sfwPopular&page=2", sfwRandomPage1),
                client.requests,
            )
        }

    /** Sketchy-allowed and unspecified requests both stay bare — the site's own default. */
    @Test
    fun sketchyAllowedStaysBare() =
        runTest {
            val client =
                configureWith(
                    mapOf(popularUrl to ok("${gridOf(24)}\n${bar(1, 70329)}")),
                )

            provider.popular(1, Filters.of("purity" to "sfw", "purity" to "sketchy")).getOrThrow()
            provider.popular(1, Filters.None).getOrThrow()
            assertEquals(listOf(popularUrl, popularUrl), client.requests)
        }

    /** Every document fetch claims the desktop Chrome identity — the facade's sanctioned per-request override. */
    @Test
    fun everyFetchClaimsTheDesktopIdentity() =
        runTest {
            val pageOneGrid = gridOf(24, 3000001)
            val client =
                configureWith(
                    mapOf(
                        popularUrl to ok(pageOneGrid),
                        popularPage(2) to ok(pageOneGrid),
                        randomPage(1) to ok(gridOf(24, 4000001)),
                    ),
                )

            provider.popular(1, Filters.None).getOrThrow()
            provider.popular(2, Filters.None).getOrThrow()
            assertTrue(client.sentHeaders.all { it["User-Agent"]?.contains("Windows NT 10.0") == true })
            assertTrue(client.sentHeaders.all { !it.containsKey("Cookie") })
        }

    // ------------------------------------------------------- memory guards

    /**
     * Page one REPLACES its listing's memory: a refreshed feed is a
     * new walk, and a revisit serves page one's items again instead
     * of answering "everything already seen" with an empty grid.
     */
    @Test
    fun pageOneResetsTheWalkMemory() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        popularUrl to ok(gridOf(24, 3000001)),
                        popularPage(2) to ok(gridOf(24, 3000025)),
                    ),
                )

            val first = provider.popular(1, Filters.None).getOrThrow()
            assertEquals(24, first.wallpapers.size)

            provider.popular(2, Filters.None).getOrThrow()

            // The refreshed feed: page one again, full — not "all seen".
            val refresh = provider.popular(1, Filters.None).getOrThrow()
            assertEquals(24, refresh.wallpapers.size)
        }

    /** The Popular tab, a category lane and a search never compare notes — each walk dedupes against ITS OWN memory. */
    @Test
    fun listingsKeepIsolatedMemories() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        popularUrl to ok(gridOf(24, 3000001)),
                        natureTagUrl to ok(gridOf(24, 3000001)),
                    ),
                )

            provider.popular(1, Filters.None).getOrThrow()
            val search = provider.search("nature", 1, Filters.None).getOrThrow()
            assertEquals("the search lane serves the same items freely", 24, search.wallpapers.size)
        }

    /** The deep-pagination cap: no fetch at all past the hundredth page. */
    @Test
    fun deepPaginationIsCapped() =
        runTest {
            val client = configureWith(emptyMap())

            val beyond = provider.popular(101, Filters.None).getOrThrow()
            assertTrue(beyond.wallpapers.isEmpty())
            assertNull(beyond.nextPage)
            assertTrue(client.requests.isEmpty())
        }

    // ----------------------------------------------------------- failures

    /** An HTTP failure is the source failure it is — the host keeps its error surface. */
    @Test
    fun httpFailuresPropagate() =
        runTest {
            val client =
                configureWith(
                    mapOf(popularUrl to ProviderHttpResponse(502, emptyMap(), ByteArray(0))),
                )

            assertTrue(provider.popular(1, Filters.None).isFailure)
        }

    /** An UNRECOGNIZED wallpaper page is the source failure it is; the host keeps the grid item's values. */
    @Test
    fun unrecognizedDetailPageFails() =
        runTest {
            val client =
                configureWith(
                    mapOf("https://wallhere.com/en/wallpaper/1" to ok("<html>redesigned</html>")),
                )

            assertTrue(provider.details("1").isFailure)
        }

    // ------------------------------------------------------------- details

    /**
     * The wallpaper page's definitive record: the TRUE original on
     * `get.wallhere.com`, the resolution the meta description's tail
     * states, the uploader with her furniture stripped, and the
     * fuller tag row — classified per item.
     */
    @Test
    fun detailsReadTheDefinitiveRecord() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpaper/2298592" to
                            ok(
                                """
                                <html><head><meta name="description" content="Wallhere: beautiful 3840x2160 wallpaper download - Wallpaper Sunlight, forest, fog, trees, 3840x2160,2298592"></head><body>
                                <img itemprop="contentURL" width="1200" height="1697" title="sunlight,forest,fog,trees" alt="sunlight,forest,fog,trees" src="https://c.wallhere.com/photos/84/71/sunlight_forest_fog_trees-2298592.jpg!d">
                                <a class="current-page-photo" href="https://get.wallhere.com/photo/sunlight_forest_fog_trees-2298592.jpg">Download</a>
                                <a class="profile-link">lrxv / 220 Wallpapers</a>
                                </body></html>
                                """.trimIndent(),
                            ),
                    ),
                )

            val details = provider.details("2298592").getOrThrow()
            assertEquals("https://get.wallhere.com/photo/sunlight_forest_fog_trees-2298592.jpg", details.wallpaper.fullUrl)
            // The meta description's LAST pair is the original's, not the display's.
            assertEquals(3840, details.wallpaper.width)
            assertEquals(2160, details.wallpaper.height)
            assertEquals("3840x2160", details.resolution)
            assertEquals("lrxv", details.author)
            assertEquals("https://wallhere.com/en/wallpaper/2298592", details.sourceUrl)
            assertTrue(details.wallpaper.tags.contains("forest"))
            assertEquals(ContentRating.SFW, details.wallpaper.contentRating)
            // Blank on purpose: the grid item's thumbnail is the better thumb.
            assertEquals("", details.wallpaper.thumbUrl)
        }

    /** Without the download anchor, the display original stands in — a real file, honestly lower-resolution. */
    @Test
    fun detailsFallBackToTheDisplayOriginal() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://wallhere.com/en/wallpaper/598783" to
                            ok(
                                """
                                <html><head><meta name="description" content="Wallhere: mountain, snow, sunset, 2400x1600,598783"></head><body>
                                <img itemprop="contentURL" width="1200" height="800" title="mountain,snow,sunset" src="https://c.wallhere.com/photos/4b/90/mountain_snow_sunset-598783.jpg!d">
                                </body></html>
                                """.trimIndent(),
                            ),
                    ),
                )

            val details = provider.details("598783").getOrThrow()
            assertEquals("https://c.wallhere.com/photos/4b/90/mountain_snow_sunset-598783.jpg!d", details.wallpaper.fullUrl)
            assertEquals(2400, details.wallpaper.width)
        }

    // ---------------------------------------------------------- rating & tags

    /**
     * The per-WORD adult classification: compound rows ("panties
     * down", "Lingerie Football League") classify exactly like the
     * bare vocabulary, while innocent words ("grass", "bass") never
     * trip their shorter selves.
     */
    @Test
    fun adultRowsClassifyPerWord() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        popularUrl to
                            ok(
                                cell(1, "panties down,woman") + "\n" +
                                    cell(2, "Lingerie Football League,sport") + "\n" +
                                    cell(3, "grass,bass,landscape") + "\n" +
                                    cell(4, "nature,mountains"),
                            ),
                    ),
                )

            val page = provider.popular(1, Filters.None).getOrThrow()
            assertEquals(ContentRating.NSFW, page.wallpapers.first { it.id == "1" }.contentRating)
            assertEquals(ContentRating.NSFW, page.wallpapers.first { it.id == "2" }.contentRating)
            assertEquals(ContentRating.SFW, page.wallpapers.first { it.id == "3" }.contentRating)
            assertEquals(ContentRating.SFW, page.wallpapers.first { it.id == "4" }.contentRating)
        }

    /**
     * Suggestions answer offline: the catalogue's tiered matches first,
     * supplemented by harvested rows — and the SAME per-word adult
     * check gates both lanes, so the suggestion filter can never
     * offer the vocabulary the content filter would refuse.
     */
    @Test
    fun suggestionsComeFromCatalogueAndPoolWithheldPerWord() =
        runTest {
            val client =
                configureWith(
                    mapOf(popularUrl to ok(cell(1, "lingerie,woman,model") + "\n" + cell(2, "nature,forest"))),
                )

            provider.popular(1, Filters.None).getOrThrow()

            val nature = provider.suggestTags("nat").getOrThrow()
            assertTrue(nature.any { it.equals("nature", ignoreCase = true) })
            assertFalse("adult vocabulary is withheld from suggestions", provider.suggestTags("linger").getOrThrow().any { it.contains("linger", ignoreCase = true) })
            assertFalse(provider.suggestTags("pant").getOrThrow().any { it.contains("pant", ignoreCase = true) })
        }

    /** The catalogue answers immediately on a fresh instance — no network, no harvest needed. */
    @Test
    fun suggestionsAnswerOnAFreshInstance() =
        runTest {
            val client = configureWith(emptyMap())

            val anime = provider.suggestTags("ani").getOrThrow()
            assertTrue(anime.any { it.equals("anime", ignoreCase = true) })
            assertTrue(client.requests.isEmpty())
        }

    // ---------------------------------------------------------- plumbing

    /** The extension's declared shape: the v2 lineage, SKETCHY floor, every capability but albums. */
    @Test
    fun metaDeclaresTheV2Shape() {
        assertEquals("cloudimage.wallhere", provider.meta.id)
        assertEquals("2.0.1", provider.meta.versionName)
        assertEquals(ContentRating.SKETCHY, provider.meta.contentRating)
        assertEquals(
            setOf(Capability.POPULAR, Capability.LATEST, Capability.SEARCH, Capability.FILTERS, Capability.TAGS, Capability.RANDOM),
            provider.capabilities,
        )
    }

    /** Sections are cheap and offline: the ranked shelves plus tag-style query presets. */
    @Test
    fun sectionsAreOfflinePresets() =
        runTest {
            val sections = provider.sections()
            assertTrue(sections.any { it.id == HomeSection.DEFAULT_ID })
            assertTrue(sections.any { it.filters.isSelected("query", "nature") })
            assertTrue(sections.size >= 10)
        }
}
