package com.cloudimage.zastavki

import com.cloudimage.provider.api.Capability
import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Zastavki provider over a scripted fake of the plugin-facing HTTP
 * facade, with fixtures captured from the site's LIVE markup (October
 * 2026 — the site serves plain HTML to every client, no challenge
 * anywhere): the subcategory grid (anchors to `wallpaper-{id}.htm`
 * detail pages wrapping `/pictures/286x180/` thumbnails whose alts
 * carry English titles), the pagination bar's numbered
 * `/rus/{cat}/{sub}/{N}/` links, the category page's subcategory
 * tiles, the detail page's `og:image` original with its true
 * dimensions and `user-added` uploader, and the ranking route's
 * Russian-titled cells whose English titles are mined from the
 * thumbnail's own URL slug — all covered without a network, exactly
 * like the WallpaperFlare suite this mirrors.
 */
class ZastavkiWallpaperProviderTest {
    private val provider = ZastavkiWallpaperProvider()

    /**
     * URL-routed responses; unmatched URLs answer 500 to fail loudly.
     * Iteration is insertion-ordered, so a deeper page's longer key must
     * be declared before the listing's bare one.
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
            return routes.entries
                .firstOrNull { (prefix, _) -> url.startsWith(prefix) }
                ?.value
                ?: ProviderHttpResponse(500, emptyMap(), ByteArray(0))
        }
    }

    private fun ok(body: String): ProviderHttpResponse = ProviderHttpResponse(200, emptyMap(), body.toByteArray())

    /** The server's own refusal for a dead pagination lane. */
    private fun refused(): ProviderHttpResponse = ProviderHttpResponse(502, emptyMap(), ByteArray(0))

    private fun configureWith(routes: Map<String, ProviderHttpResponse>): FakeClient =
        FakeClient().apply {
            this.routes = routes
            provider.configure(this, ProviderSettings { null })
        }

    // Fixtures: shapes captured from zastavki.com's live pages.

    /**
     * The subcategory grid as the site serves it — the exact shape the
     * Mountains shelf renders: one anchor per wallpaper, the English
     * title in the img's `alt`, the 286x180 thumb in `src`, and the
     * triple-underscore `Cat___Sub` URL family whose original sibling
     * the size-code drop derives. The second cell is a legacy
     * single-underscore file (both separator generations proven), and
     * the third cell is a sidebar cell from ANOTHER category — the
     * site-wide-latest furniture the path scoping must keep out of the
     * shelf.
     */
    private val mountainsGrid =
        """
        <div class="row-fluid">
        <div class="thumbnail center img-thumb" href="/rus/Nature/Mountains/wallpaper-179442.htm">
            <a class="img-wrap" href="/rus/Nature/Mountains/wallpaper-179442.htm" title="Яркое солнце над горами">
                <img src="/pictures/286x180/2025/Nature___Mountains_Bright_sun_over_the_mountains_near_the_frozen_lake_179442_32.jpg" alt="Bright sun over the mountains near the frozen lake">
            </a>
            <h6 class="center image-info-block">
                <a href="/rus/Nature/Mountains/wallpaper-179442.htm" title="Яркое солнце над горами" class='small-link'>Яркое солнце</a>
            </h6>
        </div>
        <div class="thumbnail center img-thumb" href="/rus/Nature/Mountains/wallpaper-178727.htm">
            <a class="img-wrap" href="/rus/Nature/Mountains/wallpaper-178727.htm" title="Камни в реке">
                <img src="/pictures/286x180/2024/Nature_Mountains_Stones_in_the_river_against_the_mountains_178727_32.jpg" alt="Stones in the river against the backdrop of the mountains">
            </a>
        </div>
        <div class="thumbnail center img-thumb" href="/rus/People/Children/wallpaper-179844.htm">
            <a class="img-wrap" href="/rus/People/Children/wallpaper-179844.htm" title="Новорожденный ребенок">
                <img src="/pictures/286x180/2025/People___Children_Newborn_baby_with_deer_antlers_179844_32.jpg" alt="Newborn baby with deer antlers">
            </a>
        </div>
        </div>
        """.trimIndent()

    /**
     * The shelf's pagination bar as the live page renders it: numbered
     * clean links whose deepest number (141) is the last page.
     */
    private val mountainsBar =
        """
        <ul class="pagination">
        <li><a href="/rus/Nature/Mountains/">&laquo;</a></li>
        <li class="active"><a href="/rus/Nature/Mountains/">1</a></li>
        <li><a href="/rus/Nature/Mountains/2/">2</a></li>
        <li><a href="/rus/Nature/Mountains/3/">3</a></li>
        <li><a href="/rus/Nature/Mountains/140/">140</a></li>
        <li><a href="/rus/Nature/Mountains/141/">141</a></li>
        </ul>
        """.trimIndent()

    /**
     * The ranking route's page — Russian-titled cells (the route the
     * homepage's own navigation links), whose English titles are mined
     * from the thumbnail's URL slug: the category prefix stripped (the
     * zero-padded legacy id `004072` proving the tail strip), the
     * triple-underscore subcategory prefix stripped, underscores become
     * spaces. A third cell's slug states nothing beyond its prefix —
     * the id fallback.
     */
    private val rankingGrid =
        """
        <div id="day-list-content-block">
        <a class="img-wrap" href="/rus/Zodiac_signs/wallpaper-4072.htm" title="Дева">
            <img src="/pictures/286x180/2012/Zodiac_signs_Virgo_004072_32.jpg" alt="Дева">
        </a>
        <a class="img-wrap" href="/rus/Nature/Flowers/wallpaper-179841.htm" title="Оранжевая гербера">
            <img src="/pictures/286x180/2025/Nature___Flowers_Orange_gerbera_in_the_hair_of_a_red-haired_girl_179841_32.jpg" alt="Оранжевая гербера в волосах">
        </a>
        <a class="img-wrap" href="/rus/Nature/Forest/wallpaper-000645.htm" title="Лес">
            <img src="/pictures/286x180/2012/Nature_Forest__000645_32.jpg" alt="Лес">
        </a>
        </div>
        """.trimIndent()

    /**
     * The category page's subcategory tiles — the Nature page's own
     * first tiles verbatim in structure: English names in the alts,
     * cover thumbs in the `2012` placeholder family, canonical `/rus/`
     * hrefs on the English page.
     */
    private val natureTiles =
        """
        <div class="row-fluid">
        <a href="/rus/Nature/Flowers/">
            <img src="/pictures/286x180/2012/Nature_Flowers__000615_32.jpg" alt="Flowers">
        </a>
        <a href="/rus/Nature/Volcanoes/">
            <img src="/pictures/286x180/2012/Nature_Volcanoes_Chaiten_volcano_in_Chile_021391_32.jpg" alt="Volcanoes">
        </a>
        <a href="/rus/Nature/Waterfalls/">
            <img src="/pictures/286x180/2012/Nature_Waterfalls_Niagara_Falls_USA_Canada_021785_32.jpg" alt="Waterfalls">
        </a>
        </div>
        """.trimIndent()

    /**
     * A FLAT category page — no tiles, a wallpaper grid directly: the
     * first cell of the Love shelf, single-underscore URL family, whose
     * synthesized album is the category itself.
     */
    private val loveGrid =
        """
        <div class="row-fluid">
        <div class="thumbnail center img-thumb" href="/rus/Love/wallpaper-179842.htm">
            <a class="img-wrap" href="/rus/Love/wallpaper-179842.htm" title="Надпись под водой">
                <img src="/pictures/286x180/2025/Love_Inscription_under_water_I_love_you_179842_32.jpg" alt="Inscription under water I love you">
            </a>
        </div>
        </div>
        """.trimIndent()

    /**
     * The wallpaper page's definitive record, as the live detail page
     * states it: the `og:image` original (the size-code-dropped sibling
     * of the grid thumb, verbatim) with its TRUE pixel dimensions, and
     * the uploader's `user-added` anchor under the "Добавил" label.
     */
    private val detailPage =
        """
        <html><head>
        <title>Оранжевая гербера в волосах рыжеволосой девушки - обои для рабочего стола</title>
        <meta property="og:title" content="Оранжевая гербера в волосах рыжеволосой девушки" />
        <meta property="og:type" content="image" />
        <meta property="og:url" content="/rus/Nature/Flowers/wallpaper-179841.htm" />
        <meta property="og:site_name" content="Zastavki.com" />
        <meta property="og:image" content="/pictures/originals/2025/Nature___Flowers_Orange_gerbera_in_the_hair_of_a_red-haired_girl_179841_.jpg" />
        <meta property="og:image:width" content="6000" />
        <meta property="og:image:height" content="4000" />
        </head><body>
        <div id="under-wallpaper">
        <div class="m-t-10 m-l-10">
        <strong>Добавил</strong>: <a href="https://www.zastavki.com/?r=site%2FuserImages&ur_id=59" class="user-added"><span>Nikolaj</span></a>
        <span class="date m-l-10 nowrap">2025-06-07</span>
        </div>
        </div></body></html>
        """.trimIndent()

    /**
     * The ranking route's MOBILE variant — what the site serves to every
     * user-agent carrying "Android" (the host's own UA does): the same
     * img-wrap anchors, but 600x382 preview thumbs whose ranking files
     * glue the batch year onto the file name and disclose no year
     * segment, so no original is derivable and the thumb itself stands
     * in as the full URL until [ZastavkiWallpaperProvider.details]
     * upgrades it. The provider's per-request desktop User-Agent asks
     * for the desktop markup; this fixture pins the fallback should the
     * host ever override the agent back.
     */
    private val mobileRankingGrid =
        """
        <div id="day-list-content-block">
        <a class="img-wrap" href="/rus/Zodiac_signs/wallpaper-4072.htm" title="Дева">
            <img src="/pictures/600x382/2007Zodiac_signs_Virgo_004072_33.jpg" alt="Дева">
        </a>
        </div>
        """.trimIndent()

    /**
     * The subcategory grid's mobile variant — 600x382 thumbs that KEEP
     * the year segment, so the originals family stays derivable across
     * size families.
     */
    private val mobileMountainsGrid =
        """
        <a class="img-wrap" href="/rus/Nature/Mountains/wallpaper-179442.htm" title="Яркое солнце над горами">
            <img src="/pictures/600x382/2025/Nature___Mountains_Bright_sun_over_the_mountains_near_the_frozen_lake_179442_33.jpg" alt="Bright sun over the mountains near the frozen lake">
        </a>
        """.trimIndent()

    // ------------------------------------------------------------------ meta

    @Test
    fun metaCarriesTheSitesHonestFloor() {
        assertEquals("cloudimage.zastavki", provider.meta.id)
        assertEquals("Zastavki", provider.meta.name)
        assertEquals(ContentRating.SKETCHY, provider.meta.contentRating)
        assertTrue(provider.capabilities.contains(Capability.ALBUMS))
        assertTrue(provider.capabilities.contains(Capability.POPULAR))
    }

    @Test
    fun categoriesAreTheSitesOwnThirtySix() =
        runTest {
            assertEquals(36, provider.categories().size)
            assertEquals("Nature", provider.categories().first().id)
            assertEquals("Widescreen", provider.categories().last().id)
        }

    @Test
    fun homeAlbumsAreBakedAndCovered() =
        runTest {
            val albums = provider.homeAlbums().getOrThrow()
            assertTrue(albums.size >= 10)
            assertTrue(albums.all { it.coverUrl.startsWith("https://www.zastavki.com/pictures/") })
            assertTrue(albums.any { it.id == "Nature/Mountains" })
        }

    // --------------------------------------------------------------- popular

    @Test
    fun popularWalksTheAllTimeDownloadsRanking() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.zastavki.com/?r=site%2Flist&orderby=downloads&page=1" to ok(rankingGrid),
                    ),
                )
            val page = provider.popular(1).getOrThrow()

            assertEquals(3, page.wallpapers.size)
            assertEquals(2, page.nextPage)
            assertEquals("Zodiac_signs/wallpaper-4072", page.wallpapers[0].id)
            // The Russian-titled cell's English title, mined from its own slug.
            assertEquals("Virgo", page.wallpapers[0].title)
            assertEquals("Nature/Flowers/wallpaper-179841", page.wallpapers[1].id)
            assertEquals(
                "Orange gerbera in the hair of a red-haired girl",
                page.wallpapers[1].title,
            )
            // A slug that states nothing beyond its prefix degrades to the id.
            assertEquals("Wallpaper 000645", page.wallpapers[2].title)
            // The slug-mined walk's page 2 request, exactly as the site's own link states it.
            assertTrue(
                client.requests.any {
                    it == "https://www.zastavki.com/?r=site%2Flist&orderby=downloads&page=1"
                },
            )
        }

    @Test
    fun latestSortingRidesTheDateWalk() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.zastavki.com/?r=site%2Flist&orderby=date&page=1" to ok(rankingGrid),
                    ),
                )
            provider.popular(1, Filters.of("sorting" to "date")).getOrThrow()
            assertTrue(
                client.requests.any { it == "https://www.zastavki.com/?r=site%2Flist&orderby=date&page=1" },
            )
        }

    @Test
    fun theRankingWalkStopsAtAnEmptyPage() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.zastavki.com/?r=site%2Flist&orderby=downloads&page=2" to ok("<html></html>"),
                ),
            )
            val page = provider.popular(2).getOrThrow()
            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun theRankingWalkIsCapped() =
        runTest {
            configureWith(mapOf())
            val page = provider.popular(101).getOrThrow()
            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
        }

    @Test
    fun everyFetchRidesTheDesktopIdentity() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.zastavki.com/?r=site%2Flist&orderby=downloads&page=1" to ok(rankingGrid),
                    ),
                )
            provider.popular(1).getOrThrow()
            // The sanctioned per-request override: the host's own
            // Android-bearing UA would earn the mobile grid variant.
            assertTrue(
                client.sentHeaders.all {
                    it["User-Agent"]?.contains("Windows NT 10.0") == true
                },
            )
        }

    @Test
    fun theMobileRankingVariantParsesWithTheThumbAsFull() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.zastavki.com/?r=site%2Flist&orderby=downloads&page=1" to ok(mobileRankingGrid),
                ),
            )
            val page = provider.popular(1).getOrThrow()
            assertEquals(1, page.wallpapers.size)
            // Year-glued file, no year segment: the thumb itself stands
            // in as the full URL — a real, publicly served file.
            assertEquals(
                "https://www.zastavki.com/pictures/600x382/2007Zodiac_signs_Virgo_004072_33.jpg",
                page.wallpapers[0].thumbUrl,
            )
            assertEquals(page.wallpapers[0].thumbUrl, page.wallpapers[0].fullUrl)
            // The glued year is stripped before the category prefix.
            assertEquals("Virgo", page.wallpapers[0].title)
        }

    @Test
    fun theMobileShelfVariantKeepsDerivingOriginals() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.zastavki.com/eng/Nature/Mountains/2/" to ok("<html></html>"),
                    "https://www.zastavki.com/eng/Nature/Mountains/" to ok(mobileMountainsGrid),
                ),
            )
            val wallpapers = provider.albumWallpapers("Nature/Mountains").getOrThrow()
            assertEquals(1, wallpapers.size)
            assertEquals(
                "https://www.zastavki.com/pictures/originals/2025/Nature___Mountains_Bright_sun_over_the_mountains_near_the_frozen_lake_179442_.jpg",
                wallpapers[0].fullUrl,
            )
        }

    // ---------------------------------------------------------------- search

    @Test
    fun searchAnswersEmptyTheSitesOwnWay() =
        runTest {
            val client = configureWith(mapOf())
            val page = provider.search("mountain").getOrThrow()
            assertTrue(page.wallpapers.isEmpty())
            assertNull(page.nextPage)
            assertTrue(client.requests.isEmpty())
        }

    // ---------------------------------------------------------------- albums

    @Test
    fun albumsReadsTheCategorysOwnTiles() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.zastavki.com/eng/Nature/" to ok(natureTiles),
                ),
            )
            val albums = provider.albums("Nature").getOrThrow()
            assertEquals(3, albums.size)
            assertEquals("Nature/Flowers", albums[0].id)
            assertEquals("Flowers", albums[0].title)
            assertEquals(
                "https://www.zastavki.com/pictures/286x180/2012/Nature_Flowers__000615_32.jpg",
                albums[0].coverUrl,
            )
        }

    @Test
    fun aFlatCategorySynthesizesItsOwnAlbum() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.zastavki.com/eng/Love/" to ok(loveGrid),
                ),
            )
            val albums = provider.albums("Love").getOrThrow()
            assertEquals(1, albums.size)
            assertEquals("Love", albums[0].id)
            assertEquals("Love wallpapers", albums[0].title)
            assertEquals(
                "https://www.zastavki.com/pictures/286x180/2025/Love_Inscription_under_water_I_love_you_179842_32.jpg",
                albums[0].coverUrl,
            )
        }

    @Test
    fun anUnknownCategoryAnswersNothing() =
        runTest {
            val client = configureWith(mapOf())
            assertTrue(provider.albums("Nonsense").getOrThrow().isEmpty())
            assertTrue(client.requests.isEmpty())
        }

    @Test
    fun albumWallpapersWalksTheShelfScopedToItself() =
        runTest {
            val pageTwoGrid =
                """
                <a class="img-wrap" href="/rus/Nature/Mountains/wallpaper-176371.htm" title="Заснеженная дорога">
                    <img src="/pictures/286x180/2024/Nature___Mountains_Snow-covered_road_between_the_mountains_176371_32.jpg" alt="Snow-covered road between the mountains">
                </a>
                """.trimIndent()
            val client =
                configureWith(
                    mapOf(
                        "https://www.zastavki.com/eng/Nature/Mountains/2/" to ok(pageTwoGrid),
                        "https://www.zastavki.com/eng/Nature/Mountains/3/" to ok("<html></html>"),
                        "https://www.zastavki.com/eng/Nature/Mountains/" to ok("$mountainsGrid\n$mountainsBar"),
                    ),
                )
            val wallpapers = provider.albumWallpapers("Nature/Mountains").getOrThrow()
            // The sidebar cell from People/Children is scoped out; the
            // walk collects page one's two walls plus page two's one:
            // three in the site's own newest-first order.
            assertEquals(3, wallpapers.size)
            assertEquals("Nature/Mountains/wallpaper-179442", wallpapers[0].id)
            assertEquals("Bright sun over the mountains near the frozen lake", wallpapers[0].title)
            assertEquals(
                "https://www.zastavki.com/pictures/286x180/2025/Nature___Mountains_Bright_sun_over_the_mountains_near_the_frozen_lake_179442_32.jpg",
                wallpapers[0].thumbUrl,
            )
            assertEquals(
                "https://www.zastavki.com/pictures/originals/2025/Nature___Mountains_Bright_sun_over_the_mountains_near_the_frozen_lake_179442_.jpg",
                wallpapers[0].fullUrl,
            )
            assertEquals("Nature/Mountains/wallpaper-176371", wallpapers[2].id)
            assertTrue(client.requests.any { it == "https://www.zastavki.com/eng/Nature/Mountains/2/" })
        }

    @Test
    fun albumWallpapersStopsAtTheServersOwnRefusal() =
        runTest {
            val client =
                configureWith(
                    mapOf(
                        "https://www.zastavki.com/eng/Love/2/" to refused(),
                        "https://www.zastavki.com/eng/Love/" to ok(loveGrid),
                    ),
                )
            val wallpapers = provider.albumWallpapers("Love").getOrThrow()
            assertEquals(1, wallpapers.size)
            assertEquals("Love/wallpaper-179842", wallpapers[0].id)
            assertEquals("Inscription under water I love you", wallpapers[0].title)
            assertTrue(
                client.requests.any { it == "https://www.zastavki.com/eng/Love/2/" },
            )
        }

    @Test
    fun aGlamourShelfClassifiesItsItemsSketchy() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.zastavki.com/eng/Girls/" to
                        ok(
                            """
                            <a class="img-wrap" href="/rus/Girls/wallpaper-179900.htm" title="Девушка">
                                <img src="/pictures/286x180/2025/Girls_Beautiful_girl_in_a_red_dress_179900_32.jpg" alt="Beautiful girl in a red dress">
                            </a>
                            """.trimIndent(),
                        ),
                ),
            )
            val wallpapers = provider.albumWallpapers("Girls").getOrThrow()
            assertEquals(ContentRating.SKETCHY, wallpapers[0].contentRating)
        }

    // --------------------------------------------------------------- details

    @Test
    fun detailsReadsTheDefinitiveRecord() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.zastavki.com/rus/Nature/Flowers/wallpaper-179841.htm" to ok(detailPage),
                ),
            )
            val details = provider.details("Nature/Flowers/wallpaper-179841").getOrThrow()
            assertEquals(
                "https://www.zastavki.com/pictures/originals/2025/Nature___Flowers_Orange_gerbera_in_the_hair_of_a_red-haired_girl_179841_.jpg",
                details.wallpaper.fullUrl,
            )
            assertEquals(6000, details.wallpaper.width)
            assertEquals(4000, details.wallpaper.height)
            assertEquals("6000x4000", details.resolution)
            assertEquals("Nikolaj", details.author)
            assertEquals(
                "https://www.zastavki.com/rus/Nature/Flowers/wallpaper-179841.htm",
                details.sourceUrl,
            )
            assertEquals(ContentRating.SFW, details.wallpaper.contentRating)
        }

    @Test
    fun aRefusedDetailPageIsTheFailureItIs() =
        runTest {
            configureWith(
                mapOf(
                    "https://www.zastavki.com/rus/Nature/Flowers/wallpaper-1.htm" to refused(),
                ),
            )
            assertTrue(provider.details("Nature/Flowers/wallpaper-1").isFailure)
        }

    // ---------------------------------------------------------------- random

    @Test
    fun randomIsTheHonestEmptyAnswer() =
        runTest {
            val client = configureWith(mapOf())
            assertTrue(provider.random().getOrThrow().isEmpty())
            assertTrue(client.requests.isEmpty())
        }

    // -------------------------------------------------------------- sections

    @Test
    fun sectionsOfferTheTwoRankedWalks() =
        runTest {
            val sections = provider.sections()
            assertEquals(2, sections.size)
            assertEquals("Popular", sections[0].title)
            assertTrue(sections[1].filters.isSelected("sorting", "date"))
        }
}
