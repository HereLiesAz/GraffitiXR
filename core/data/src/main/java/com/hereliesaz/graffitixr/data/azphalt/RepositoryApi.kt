package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.AzphaltJson
import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.net.URLEncoder

/** The flagship azphalt storefront. The registry client points here unless reconfigured. */
const val DEFAULT_REGISTRY_URL: String = "https://azphalt.store"

/**
 * Client + models for an azphalt registry (spec/repository-api.md, format 0.1; server behaviour tracks
 * @azphalt/registry 0.2.x). A registry lets GraffitiXR browse and download extensions from a live
 * server instead of the bundled seed catalog.
 *
 * Transport-agnostic on purpose: [httpGet]/[httpPost] perform the actual fetch, so this is trivially
 * unit-testable and reuses whatever HTTP stack the caller already has (GraffitiXR has no Retrofit; the
 * caller can pass a tiny HttpURLConnection lambda). Each receives the URL and request headers (e.g. an
 * Authorization bearer for paid packages) and returns the response body. [httpPost] is optional — only
 * the batch [updates] check needs it — so existing GET-only callers construct the two-arg form.
 */
class RepositoryClient(
    baseUrl: String,
    private val httpGet: (url: String, headers: Map<String, String>) -> String,
    private val httpPost: (url: String, headers: Map<String, String>, body: String) -> String =
        { _, _, _ ->
            throw UnsupportedOperationException(
                "This RepositoryClient has no POST transport; pass httpPost to call updates()."
            )
        },
) {
    private val base: String = baseUrl.trimEnd('/')

    /** GET /.well-known/azphalt-repository.json — registry identity, auth, and signing keys. */
    fun discover(): RepositoryInfo =
        AzphaltJson.decodeFromString(httpGet("$base/.well-known/azphalt-repository.json", emptyMap()))

    /**
     * GET /packages — paginated search. [types] filters by contribution type (e.g. "lut", "shader",
     * "code"); [tags] by free-form tag; [app] scopes to a reverse-DNS host app id; [capabilities]
     * keeps packages whose required capabilities are a subset of the host's; [mediaDomains] keeps
     * packages that intersect the given media domains (image/video/audio/3d/model/font); [sort] orders
     * results (registry default is [RepositorySort.POPULAR]). Empty filters return the full catalog page.
     */
    fun search(
        q: String? = null,
        types: List<String> = emptyList(),
        tags: List<String> = emptyList(),
        app: String? = null,
        capabilities: List<String> = emptyList(),
        mediaDomains: List<String> = emptyList(),
        sort: RepositorySort? = null,
        page: Int = 1,
    ): SearchResponse {
        val params = buildList {
            if (!q.isNullOrBlank()) add("q=" + enc(q))
            if (types.isNotEmpty()) add("types=" + enc(types.joinToString(",")))
            if (tags.isNotEmpty()) add("tags=" + enc(tags.joinToString(",")))
            if (!app.isNullOrBlank()) add("app=" + enc(app))
            if (capabilities.isNotEmpty()) add("capabilities=" + enc(capabilities.joinToString(",")))
            if (mediaDomains.isNotEmpty()) add("mediaDomains=" + enc(mediaDomains.joinToString(",")))
            if (sort != null) add("sort=" + enc(sort.wire))
            add("page=$page")
        }.joinToString("&")
        return AzphaltJson.decodeFromString(httpGet("$base/packages?$params", emptyMap()))
    }

    /** GET /packages/{id} — full detail and version history. */
    fun detail(id: String): PackageDetail =
        AzphaltJson.decodeFromString(httpGet("$base/packages/${enc(id)}", emptyMap()))

    /**
     * GET /revocations — the registry's advisory feed of pulled package versions (a compromised or
     * malicious build). [since] (ISO-8601) fetches only revocations after a prior check. Advisory: a
     * host uses it to warn about or disable an already-installed version, not as an install-time gate.
     */
    fun revocations(since: String? = null): RevocationsResponse {
        val query = if (since.isNullOrBlank()) "" else "?since=" + enc(since)
        return AzphaltJson.decodeFromString(httpGet("$base/revocations$query", emptyMap()))
    }

    /**
     * POST /updates — batch "is there a newer version?" check. Sends the installed (id, version) set and
     * gets back only those with a newer non-yanked version. Needs the [httpPost] transport.
     */
    fun updates(installed: List<InstalledRef>): UpdatesResponse {
        val body = AzphaltJson.encodeToString(installed)
        val resp = httpPost("$base/updates", mapOf("Content-Type" to "application/json"), body)
        return AzphaltJson.decodeFromString(resp)
    }

    /**
     * The download URL for a specific version's `.azp`. Handed to [ExtensionRepository.install] as the
     * entry [MarketplaceEntry.source]; paid packages need an Authorization bearer at fetch time
     * (the server answers 401 without a token, 402 without a license).
     */
    fun downloadUrl(id: String, version: String): String =
        "$base/packages/${enc(id)}/versions/${enc(version)}/download"

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
}

/** GET /packages sort order (spec/repository-api.md). Wire values are the query-param strings. */
enum class RepositorySort(val wire: String) {
    POPULAR("popular"),
    RECENT("recent"),
    RATING("rating"),
    NAME("name"),
}

/** GET /.well-known/azphalt-repository.json */
@Serializable
data class RepositoryInfo(
    val name: String,
    val version: String,
    val description: String? = null,
    /** How the registry authenticates paid/entitled downloads, if any (e.g. `type = "oauth2"`). */
    val auth: RepositoryAuth? = null,
    /** Asset types this registry serves (subset of the manifest AssetType union). */
    val supportedTypes: List<String> = emptyList(),
    /** Host profiles the registry targets: "image", "video-audio", "companion", "mcp". */
    val profiles: List<String> = emptyList(),
    /** Ed25519 public keys for verifying signed packages — trust bootstrap. */
    val signingKeys: List<SigningKey> = emptyList(),
)

/** Discovery `auth` block: how to obtain a token for entitled downloads. */
@Serializable
data class RepositoryAuth(
    val type: String,
    val url: String? = null,
)

/** A registry signing key (discovery `signingKeys[]`): base64 SPKI Ed25519 public key + identity. */
@Serializable
data class SigningKey(
    val publicKey: String,
    val keyId: String,
    val label: String? = null,
)

/** GET /revocations — advisory list of pulled versions, newest first. */
@Serializable
data class RevocationsResponse(
    val revocations: List<Revocation> = emptyList(),
)

/** A single revoked package version. */
@Serializable
data class Revocation(
    val id: String,
    val version: String,
    val reason: String? = null,
    val revokedAt: String? = null,
)

/** POST /updates request element: one installed package to check. */
@Serializable
data class InstalledRef(
    val id: String,
    val version: String,
)

/** POST /updates response — only packages with a newer non-yanked version appear. */
@Serializable
data class UpdatesResponse(
    val updates: List<UpdateAvailable> = emptyList(),
)

/** A single available update: [latest] is the newest installable semver for [id]. */
@Serializable
data class UpdateAvailable(
    val id: String,
    val latest: String,
)

/** True when a revocations feed lists this exact package version as pulled. */
fun RevocationsResponse.isRevoked(id: String, version: String): Boolean =
    revocations.any { it.id == id && it.version == version }

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

/** GET /packages/{id} — detail plus the version list (newest resolvable via [latestVersion]). */
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
    /** Newest installable semver, disambiguating [versions] history (may skip yanked versions). */
    val latest: String? = null,
    /** BCP-47 language-tag → localized display name. */
    val nameLocalized: Map<String, String> = emptyMap(),
    /** BCP-47 language-tag → localized description. */
    val descriptionLocalized: Map<String, String> = emptyMap(),
)

/** The version to install: the registry-declared [PackageDetail.latest], else newest in history. */
val PackageDetail.latestVersion: String?
    get() = latest ?: versions.firstOrNull()

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
