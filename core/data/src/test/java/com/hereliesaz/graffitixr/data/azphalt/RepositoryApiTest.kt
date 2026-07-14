package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the azphalt registry client (spec/repository-api.md): request URLs, response parsing, and the
 * mapping to catalog cards. Transport is faked, so this is a pure unit test.
 */
class RepositoryApiTest {

    /** Records the URLs requested and returns canned bodies keyed by a path fragment. */
    private class FakeHttp(private val bodies: Map<String, String>) {
        val requested = mutableListOf<String>()
        fun get(url: String, @Suppress("UNUSED_PARAMETER") headers: Map<String, String>): String {
            requested += url
            return bodies.entries.firstOrNull { url.contains(it.key) }?.value
                ?: error("no fake body for $url")
        }
    }

    @Test
    fun `search parses the paginated response and hits the right URL`() {
        val fake = FakeHttp(mapOf(
            "/packages" to """
                {
                  "packages": [
                    { "id": "com.a.lut", "name": "A LUT", "author": "A", "version": "1.2.0",
                      "types": ["lut"], "tags": ["warm"], "priceStatus": "free" },
                    { "id": "com.b.filter", "name": "B Filter", "author": "B", "version": "2.0.0",
                      "types": ["code"], "priceStatus": "paid" }
                  ],
                  "total": 2, "page": 1, "pages": 1
                }
            """.trimIndent(),
        ))
        val client = RepositoryClient("https://reg.example/", fake::get)

        val resp = client.search(q = "grade", types = listOf("lut"), page = 1)

        assertEquals(2, resp.total)
        assertEquals(1, resp.pages)
        assertEquals("com.a.lut", resp.packages[0].id)
        assertTrue(resp.packages[1].isPaid)
        assertFalse(resp.packages[0].isPaid)
        // URL is built off the trimmed base with the query params.
        val url = fake.requested.single()
        assertTrue(url.startsWith("https://reg.example/packages?"))
        assertTrue(url.contains("q=grade"))
        assertTrue(url.contains("types=lut"))
        assertTrue(url.contains("page=1"))
    }

    @Test
    fun `download url is well-formed and encodes segments`() {
        val client = RepositoryClient("https://reg.example", { _, _ -> "" })
        assertEquals(
            "https://reg.example/packages/com.a.lut/versions/1.2.0/download",
            client.downloadUrl("com.a.lut", "1.2.0"),
        )
    }

    @Test
    fun `package maps to a catalog card with kind inferred from types`() {
        val asset = RepositoryPackage("com.a.lut", "A", version = "1.0.0", types = listOf("lut"))
        val code = RepositoryPackage("com.b.f", "B", version = "1.0.0", types = listOf("code"))
        val mixed = RepositoryPackage("com.c.m", "C", version = "1.0.0", types = listOf("code", "lut"))

        assertEquals(ExtensionKind.ASSET, asset.toMarketplaceEntry("u").kind)
        assertEquals(ExtensionKind.CODE, code.toMarketplaceEntry("u").kind)
        assertEquals(ExtensionKind.MIXED, mixed.toMarketplaceEntry("u").kind)

        val paidCard = RepositoryPackage("p", "P", version = "1.0.0", types = listOf("lut"), priceStatus = "paid")
            .toMarketplaceEntry("https://reg.example/packages/p/versions/1.0.0/download")
        assertEquals("Paid", paidCard.priceLabel)
        assertEquals("https://reg.example/packages/p/versions/1.0.0/download", paidCard.source)
        // Asset card is installable by this host; a pure-code one is not.
        assertTrue(asset.toMarketplaceEntry("u").installable)
        assertFalse(code.toMarketplaceEntry("u").installable)
    }
}
