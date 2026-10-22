package com.cloudimage.wallhere

import com.cloudimage.provider.api.ContentRating
import com.cloudimage.provider.api.Filters
import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live check against wallhere.com, for maintenance: the fixture tests
 * pin the markup shapes, this answers whether the SITE still speaks
 * them — and whether the v2.0.0 walk really walks past twenty-four
 * items on a real connection.
 *
 * Run on demand with:
 *
 * `WALLHERE_LIVE=1 ./gradlew :providers:wallhere:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI
 * and plain `check` never touch the network. NOTE for maintainers: the
 * site's Cloudflare zone refuses datacenter addresses outright (CI
 * runners included), so this check is meant for a developer machine on
 * a residential or carrier IP, where the site serves normally. On such
 * a machine the shared app client's Cloudflare machinery is not even
 * needed; this check rides plain HttpURLConnection.
 */
class WallhereLiveCheckTest {
    private val provider = WallhereWallpaperProvider()

    /**
     * The plugin facade over HttpURLConnection, sending the provider's
     * own DESKTOP identity — the same per-request User-Agent override
     * the provider rides on every document fetch, so an unchallenged
     * connection answers in the desktop dialect.
     */
    private class LiveClient : ProviderHttpClient {
        override suspend fun get(
            url: String,
            headers: Map<String, String>,
        ): ProviderHttpResponse {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            connection.instanceFollowRedirects = true
            headers.forEach { (name, value) ->
                connection.setRequestProperty(name, value)
            }
            val status = connection.responseCode
            val body =
                if (status in 200..399) {
                    connection.inputStream.use { it.readBytes() }
                } else {
                    ByteArray(0)
                }
            val responseHeaders = connection.headerFields.orEmpty()
            connection.disconnect()
            return ProviderHttpResponse(status, responseHeaders, body)
        }
    }

    @Test
    fun homepageStillSpeaksTheGrid() =
        runTest {
            assumeTrue(System.getenv("WALLHERE_LIVE") == "1")
            provider.configure(LiveClient(), ProviderSettings { null })

            val page = provider.popular(1, Filters.None).getOrThrow()

            assertTrue("homepage grid parsed no wallpapers", page.wallpapers.isNotEmpty())
            assertTrue(
                "grid items must carry CDN thumbnails",
                page.wallpapers.all { it.thumbUrl.contains("wallhere.com") },
            )
            assertTrue("popular feed must offer a next page", page.nextPage != null)
        }

    /**
     * THE v2.0.1 acceptance check: a real connection must walk past
     * the twenty-four-item cliff AND past the single-draw cliff — the
     * fallback must keep serving FRESH wallpapers page after page,
     * which is the exact shape the v2.0.0 line died on in the field
     * (its random re-draw served one usable draw and then repeated
     * the walk's memory on the second).
     */
    @Test
    fun theWalkPassesTwentyFourItems() =
        runTest {
            assumeTrue(System.getenv("WALLHERE_LIVE") == "1")
            provider.configure(LiveClient(), ProviderSettings { null })

            val first = provider.popular(1, Filters.None).getOrThrow()
            assertTrue("page one parsed no wallpapers", first.wallpapers.isNotEmpty())
            assertTrue("page one must offer a next page", first.nextPage != null)

            val second = provider.popular(2, Filters.None).getOrThrow()
            assertTrue(
                "the walk died at twenty-four items — the continuation served nothing",
                second.wallpapers.isNotEmpty(),
            )
            val served = first.wallpapers.map { it.id }.toMutableSet()
            assertTrue(
                "the first continuation must serve FRESH wallpapers, never repeats",
                second.wallpapers.none { it.id in served },
            )
            served += second.wallpapers.map { it.id }

            // The v2.0.0 cliff specifically: the THIRD page must still
            // serve fresh cells — the dead-parameter lane's second
            // random draw is where the reshuffle assumption died.
            val third = provider.popular(3, Filters.None).getOrThrow()
            assertTrue(
                "the walk died at two draws — the deep random cursor must keep serving",
                third.wallpapers.isNotEmpty(),
            )
            assertTrue(
                "a deep continuation must serve FRESH wallpapers, never repeats",
                third.wallpapers.none { it.id in served },
            )
        }

    /**
     * The tag lane: search must resolve "nature" onto the Nature
     * tag's OWN grid — the site's `?q=` lane is dead (probed live), so
     * the tag page is the only precision surface — and the items it
     * serves must actually carry nature vocabulary.
     */
    @Test
    fun searchRidesTheRealTagLane() =
        runTest {
            assumeTrue(System.getenv("WALLHERE_LIVE") == "1")
            provider.configure(LiveClient(), ProviderSettings { null })

            val first = provider.search("nature", 1, Filters.None).getOrThrow()
            assertTrue("tag search parsed no wallpapers", first.wallpapers.isNotEmpty())
            assertTrue(
                "the Nature tag grid must carry nature-ish tag rows",
                first.wallpapers.any { it.tags.any { tag -> tag.contains("nature", ignoreCase = true) } },
            )

            val second = provider.search("nature", 2, Filters.None).getOrThrow()
            assertTrue("tag search died at twenty-four items", second.wallpapers.isNotEmpty())
        }

    /**
     * The purity switch: a SFW-only request must serve a feed whose
     * items are ALL SFW-classified — the server-side filter working,
     * with the per-item classification as the witness.
     */
    @Test
    fun sfwOnlyRequestsServeSfwClassifiedFeeds() =
        runTest {
            assumeTrue(System.getenv("WALLHERE_LIVE") == "1")
            provider.configure(LiveClient(), ProviderSettings { null })

            val page = provider.popular(1, Filters.of("purity" to "sfw")).getOrThrow()

            assertTrue("SFW-only feed parsed no wallpapers", page.wallpapers.isNotEmpty())
            assertTrue(
                "SFW-only feed must carry no NSFW-classified items (NSFW=off ignored server-side?)",
                page.wallpapers.none { it.contentRating == ContentRating.NSFW },
            )
        }

    @Test
    fun randomStillServesFreshBatches() =
        runTest {
            assumeTrue(System.getenv("WALLHERE_LIVE") == "1")
            provider.configure(LiveClient(), ProviderSettings { null })

            val wallpapers = provider.random().getOrThrow()

            assertTrue("random batch parsed no wallpapers", wallpapers.isNotEmpty())
        }
}
