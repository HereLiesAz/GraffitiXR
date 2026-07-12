package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The azphalt `manifest.json` at the root of every `.azp` package — GraffitiXR is the standard's
 * first conforming host. Mirrors the normative spec (azphalt spec/extension-manifest.md): identity,
 * what the package contributes, and — for code — exactly which least-privilege capabilities it needs.
 * A host grants only what is declared; anything unlisted is unreachable.
 */
@Serializable
data class AzphaltManifest(
    val azphalt: String,
    val id: String,
    val name: String,
    val version: String,
    val kind: ExtensionKind,
    val license: String,
    val compat: String,
    val author: String? = null,
    val description: String? = null,
    val homepage: String? = null,
    val entry: String? = null,
    val runtime: Runtime? = null,
    val capabilities: List<Capability> = emptyList(),
    val contributes: Contributes? = null,
    val assets: List<AssetContribution> = emptyList(),
    /** Payload path → `sha256-…` digest. A host MUST verify every file before use. */
    val files: Map<String, String> = emptyMap(),
)

@Serializable
enum class ExtensionKind {
    @SerialName("asset") ASSET,
    @SerialName("code") CODE,
    @SerialName("mixed") MIXED,
}

@Serializable
enum class Runtime {
    @SerialName("js") JS,
    @SerialName("wasm") WASM,
}

@Serializable
enum class Capability {
    @SerialName("canvas") CANVAS,
    @SerialName("layers") LAYERS,
    @SerialName("bitmap") BITMAP,
    @SerialName("selection") SELECTION,
    @SerialName("color") COLOR,
    @SerialName("params") PARAMS,
    @SerialName("assets") ASSETS,
}

@Serializable
data class Contributes(
    val filters: List<Contribution> = emptyList(),
    val tools: List<Contribution> = emptyList(),
    val commands: List<Contribution> = emptyList(),
)

@Serializable
data class Contribution(
    val id: String,
    val name: String,
    val entry: String,
    val ui: String? = null,
)

@Serializable
enum class AssetType {
    @SerialName("brush") BRUSH,
    @SerialName("lut") LUT,
    @SerialName("pattern") PATTERN,
    @SerialName("stamp") STAMP,
}

@Serializable
data class AssetContribution(
    val type: AssetType,
    val path: String,
)

/** Shared lenient JSON — tolerates unknown/future manifest fields rather than failing to parse. */
val AzphaltJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Parse a manifest.json string, or throw with context. */
fun parseManifest(json: String): AzphaltManifest = AzphaltJson.decodeFromString(json)
