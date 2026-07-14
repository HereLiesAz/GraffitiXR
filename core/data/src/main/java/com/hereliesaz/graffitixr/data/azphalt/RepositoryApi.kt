package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AzphaltJson
import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import kotlinx.serialization.Serializable
import java.net.URLEncoder

/**
 * Client + models for an azphalt registry (spec/repository-api.md, format 0.1). A registry lets
 * GraffitiXR browse and download extensions from a live server instead of the bundled seed catalog.
 *
 * Transport-agnostic on purpose: [httpGet] performs the actual fetch, so this is trivially unit-
 * testable and reuses whatever HTTP stack the caller already has (GraffitiXR has no Retrofit; the
 * caller can pass a tiny HttpURLConnection lambda). [httpGet] receives the URL and the request
 * headers (e.g. an Authorization bearer for paid packages) and returns the response body.
 */
class RepositoryClient(
    baseUrl: String,
    private val httpGet: (url: String, headers: Map<String, String>) -> String,
) {
    private val base: String = baseUrl.trimEnd('/')

    /** GET /.well-known/azphalt-repository.json — registry identity and capabilities. */
    fun discover(): RepositoryInfo =
        AzphaltJson.decodeFromString(httpGet("$base/.well-known/azphalt-repository.json", emptyMap()))

    /**
     * GET /packages — paginated search. [types] filters by contribution type (e.g. "lut", "shader",
     * "code"); [tags] by free-form tag. Empty filters return the full catalog page.
     */
    fun search(
        q: String? = null,
        types: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        page: Int = 1,
    ): SearchResponse {
        val params = buildList {
            if (!q.isNullOrBlank()) add("q=" + enc(q))
            if (types.isNotEmpty()) add("types=" + enc(types.joinToString(",")))
            if (tags.isNotEmpty()) add("tags=" + enc(tags.joinToString(",")))
            add("page=$page")
        }.joinToString("&")
        return AzphaltJson.decodeFromString(httpGet("$base/packages?$params", emptyMap()))
    }

    /** GET /packages/{id} — full detail and version history. */
    fun detail(id: String): PackageDetail =
        AzphaltJson.decodeFromString(httpGet("$base/packages/${enc(id)}", emptyMap()))

    /**
     * The download URL for a specific version's `.azp`. Handed to [ExtensionRepository.install] as the
     * entry [MarketplaceEntry.source]; paid packages need an Authorization bearer at fetch time
     * (the server answers 401 without a token, 402 without a license).
     */
    fun downloadUrl(id: String, version: String): String =
        "$base/packages/${enc(id)}/versions/${enc(version)}/download"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** GET /.well-known/azphalt-repository.json */
@Serializable
data class RepositoryInfo(
    val name: String,
    val version: String,
    val description: String? = null,
)

/** A package as it appears in a registry search result or detail response. */
@Serializable
data class RepositoryPackage(
    val id: String,
    val name: String,
    val author: String? = null,
    val version: String,
    val types: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val description: String? = null,
    /** "free" or "paid"; absent is treated as free. */
    val priceStatus: String? = null,
)

/** GET /packages — paginated. */
@Serializable
data class SearchResponse(
    val packages: List<RepositoryPackage> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val pages: Int = 1,
)

/** GET /packages/{id} — detail plus the version list (newest resolvable via [versions]). */
@Serializable
data class PackageDetail(
    val id: String,
    val name: String,
    val author: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val types: List<String> = emptyList(),
    val priceStatus: String? = null,
    val versions: List<String> = emptyList(),
)

/** True when this package requires payment/entitlement to download. */
val RepositoryPackage.isPaid: Boolean get() = priceStatus.equals("paid", ignoreCase = true)

/**
 * Map a registry package to a GraffitiXR catalog card. [downloadUrl] is the resolved `.azp` URL the
 * installer fetches (see [RepositoryClient.downloadUrl]). Kind is inferred from [RepositoryPackage.types]:
 * a `code` type alone is CODE, `code` alongside asset types is MIXED, anything else is ASSET.
 */
fun RepositoryPackage.toMarketplaceEntry(downloadUrl: String): MarketplaceEntry {
    val hasCode = types.any { it.equals("code", ignoreCase = true) }
    val hasAsset = types.any { !it.equals("code", ignoreCase = true) }
    val kind = when {
        hasCode && hasAsset -> ExtensionKind.MIXED
        hasCode -> ExtensionKind.CODE
        else -> ExtensionKind.ASSET
    }
    return MarketplaceEntry(
        id = id,
        name = name,
        kind = kind,
        author = author ?: "",
        description = description ?: "",
        priceLabel = if (isPaid) "Paid" else "Free",
        downloads = 0,
        rating = null,
        tags = tags,
        source = downloadUrl,
    )
}
