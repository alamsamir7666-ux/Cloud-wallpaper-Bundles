package com.cloudimage.zastavki

import com.cloudimage.provider.api.ProviderHttpClient
import com.cloudimage.provider.api.ProviderHttpResponse
import com.cloudimage.provider.api.ProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * Live check against zastavki.com, for maintenance: the fixture tests
 * pin the markup shapes, this answers whether the SITE still speaks
 * them.
 *
 * Run on demand with:
 *
 * `ZASTAVKI_LIVE=1 ./gradlew :providers:zastavki:test --tests '*LiveCheck*'`
 *
 * The assumption skips every test here unless that variable is set — CI
 * and plain `check` never touch the network. Unlike the Cloudflare-walled
 * sources in this set, zastavki.com serves plain HTML to every client
 * (CI runners included), so this check runs from anywhere.
 */
class ZastavkiLiveCheckTest {
    private val provider = ZastavkiWallpaperProvider()

    /**
     * The plugin facade over HttpURLConnection — honoring the
     * provider's per-request User-Agent override, exactly as the host's
     * real client does, so this check sees the markup the app sees.
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
            connection.setRequestProperty(
                "User-Agent",
                headers["User-Agent"]
                    ?: "Cloudimage/1.0 (Android; +https://github.com/alamsamir7666-ux/Cloud-Wallpaper)",
            )
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

    private fun live(): Boolean = System.getenv("ZASTAVKI_LIVE") == "1"

    @Test
    fun theRankingRouteStillSpeaksTheGrid() =
        runTest {
            assumeTrue(live())
            provider.configure(LiveClient(), ProviderSettings { null })
            val page = provider.popular(1).getOrThrow()
            assertTrue(page.wallpapers.isNotEmpty())
            // The Android identity earns the mobile markup: real
            // 600x382 preview thumbs, markup-stated.
            assertTrue(page.wallpapers.all { it.thumbUrl.contains("600x382") })
            // Every full URL is a real served file: the derivable
            // originals sibling where the thumb carries the year
            // segment, the thumb itself for the glued legacy files.
            assertTrue(
                page.wallpapers.all {
                    it.fullUrl.startsWith("https://www.zastavki.com/pictures/")
                },
            )
            assertNotNull(page.nextPage)
        }

    @Test
    fun theTagSurfaceStillSpeaksTheGrid() =
        runTest {
            assumeTrue(live())
            provider.configure(LiveClient(), ProviderSettings { null })
            val page = provider.search("night").getOrThrow()
            assertTrue(page.wallpapers.isNotEmpty())
            assertTrue(page.wallpapers.all { it.thumbUrl.contains("600x382") })
            assertNotNull(page.nextPage)
        }

    @Test
    fun theAlbumShelvesStillPaginate() =
        runTest {
            assumeTrue(live())
            provider.configure(LiveClient(), ProviderSettings { null })
            val albums = provider.albums("Nature").getOrThrow()
            assertTrue(albums.isNotEmpty())
            val wallpapers = provider.albumWallpapers("Nature/Mountains").getOrThrow()
            assertTrue(wallpapers.isNotEmpty())
            assertEquals("Nature/Mountains", wallpapers.first().id.substringBefore("/wallpaper-"))
        }

    @Test
    fun theTagAlbumsStillWalkTheirGrids() =
        runTest {
            assumeTrue(live())
            provider.configure(LiveClient(), ProviderSettings { null })
            val wallpapers = provider.albumWallpapers("tag/Sky").getOrThrow()
            assertTrue(wallpapers.isNotEmpty())
        }

    @Test
    fun theDetailPagesStillStateTheOriginal() =
        runTest {
            assumeTrue(live())
            provider.configure(LiveClient(), ProviderSettings { null })
            val page = provider.popular(1).getOrThrow()
            val first = page.wallpapers.first()
            val details = provider.details(first.id).getOrThrow()
            // The authoritative original — the `originals/{year}/`
            // family for recent uploads, the legacy max-size family
            // (e.g. `1600x1200/`) for the old files the all-time
            // ranking walks.
            assertTrue(details.wallpaper.fullUrl.startsWith("https://www.zastavki.com/pictures/"))
            assertNotNull(details.wallpaper.width)
            assertNotNull(details.wallpaper.height)
        }
}
