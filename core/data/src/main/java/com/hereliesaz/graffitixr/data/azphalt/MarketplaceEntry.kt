package com.hereliesaz.graffitixr.data.azphalt

import com.hereliesaz.graffitixr.common.azphalt.ExtensionKind

/**
 * A marketplace catalog card. In a deployed setup these come from the azphalt registry HTTP API;
 * here the seed is bundled so the feature works offline. [source] tells the installer where to fetch
 * the `.azp` from — a bundled asset (`asset:...`) or an `https:` URL.
 */
data class MarketplaceEntry(
    val id: String,
    val name: String,
    val kind: ExtensionKind,
    val author: String,
    val description: String,
    val priceLabel: String,
    val downloads: Int,
    val rating: Float?,
    val tags: List<String>,
    /** Where the .azp lives: "asset:marketplace/foo.azp" (bundled) or "https://…". */
    val source: String,
) {
    /**
     * True when GraffitiXR can install this today. As an asset-only host it takes `asset` and `mixed`
     * packages (a mixed package installs, but only its asset contributions are used); pure `code`
     * extensions need the JS/WASM sandbox, which isn't here yet.
     */
    val installable: Boolean get() = kind != ExtensionKind.CODE
}

/**
 * A catalog browse result: the [entries] to show, plus whether they came from the live registry
 * ([isLive] true) or the bundled [SEED_MARKETPLACE] fallback ([isLive] false, i.e. offline).
 */
data class CatalogResult(
    val entries: List<MarketplaceEntry>,
    val isLive: Boolean,
)

/**
 * Choose the catalog to show: live registry results when present, else the bundled [SEED_MARKETPLACE].
 * A registry error (a failed [live]) or an empty page both fall back, so browsing never dead-ends when
 * azphalt.store is unreachable or hasn't published its API yet. The seed is a temporary safety net —
 * once the live storefront is dependable it can be dropped and this collapses to the live result.
 */
fun resolveCatalog(live: Result<List<MarketplaceEntry>>): CatalogResult {
    val entries = live.getOrNull()
    return if (entries.isNullOrEmpty()) {
        CatalogResult(SEED_MARKETPLACE, isLive = false)
    } else {
        CatalogResult(entries, isLive = true)
    }
}

/**
 * Bundled reference catalog — the offline fallback when the live registry is unreachable. The two LUTs
 * ship as real `.azp` assets and install + apply end-to-end; the code entries are shown for browse but
 * aren't installable until the sandbox lands.
 */
val SEED_MARKETPLACE: List<MarketplaceEntry> = listOf(
    MarketplaceEntry(
        id = "com.azphalt.warm-sunset",
        name = "Warm Sunset",
        kind = ExtensionKind.ASSET,
        author = "azphalt demo",
        description = "Warm cinematic grade — lifts reds, cools the shadows. A .cube LUT.",
        priceLabel = "Free",
        downloads = 1240,
        rating = 4.7f,
        tags = listOf("lut", "grade", "warm"),
        source = "asset:marketplace/warm-sunset-1.0.0.azp",
    ),
    MarketplaceEntry(
        id = "com.azphalt.cool-noir",
        name = "Cool Noir",
        kind = ExtensionKind.ASSET,
        author = "azphalt demo",
        description = "Cool, moody grade — teal shadows, muted reds. A .cube LUT.",
        priceLabel = "Free",
        downloads = 980,
        rating = 4.5f,
        tags = listOf("lut", "grade", "cool"),
        source = "asset:marketplace/cool-noir-1.0.0.azp",
    ),
    MarketplaceEntry(
        id = "com.hereliesaz.halftone",
        name = "Halftone",
        kind = ExtensionKind.CODE,
        author = "Az",
        description = "CMYK halftone filter. Code extension — runs once the JS/WASM sandbox lands.",
        priceLabel = "Free",
        downloads = 2210,
        rating = 4.6f,
        tags = listOf("filter", "print", "code"),
        source = "https://registry.azphalt.example/com.hereliesaz.halftone-1.2.0.azp",
    ),
    MarketplaceEntry(
        id = "com.hereliesaz.edge-detect",
        name = "Edge Detect",
        kind = ExtensionKind.CODE,
        author = "Az",
        description = "Sobel / Canny edge detection. Code extension — sandbox pending.",
        priceLabel = "Free",
        downloads = 1670,
        rating = 4.5f,
        tags = listOf("filter", "edges", "code"),
        source = "https://registry.azphalt.example/com.hereliesaz.edge-detect-1.1.0.azp",
    ),
)
