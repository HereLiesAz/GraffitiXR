package com.hereliesaz.graffitixr.common.azphalt

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** The azphalt package-format spec version GraffitiXR implements (spec/package-format.md: "0.1"). */
const val AZPHALT_SPEC_VERSION: String = "0.1"

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
    // Added in spec 0.1's asset-host guide: GLSL/ISF shaders and gl-transition transitions.
    @SerialName("shader") SHADER,
    @SerialName("transition") TRANSITION,
}

@Serializable
data class AssetContribution(
    val type: AssetType,
    val path: String,
    /**
     * Declarative parameters for the asset (spec 0.1). For [AssetType.SHADER] this carries
     * `format` (`"isf"` | `"glsl"`) and the shader's declared inputs; for [AssetType.TRANSITION],
     * `format: "gl-transition"`. Modelled as raw JSON so the host reads only what it understands.
     */
    val params: JsonObject? = null,
    /** Optional path to a native UI panel schema (spec/ui-schema.md), e.g. `"ui/grade.json"`. */
    val ui: String? = null,
)

/** Shared lenient JSON — tolerates unknown/future manifest fields rather than failing to parse. */
val AzphaltJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** Parse a manifest.json string, or throw with context. */
fun parseManifest(json: String): AzphaltManifest = AzphaltJson.decodeFromString(json)

/**
 * Whether a package declaring [compat] is compatible with the spec version this host implements
 * ([AZPHALT_SPEC_VERSION]). The asset-host conformance suite requires validating `compat`.
 *
 * We enforce only a lower bound written as `">=x.y"`, `"^x.y"`, `"~x.y"`, or a bare `"x.y"`: the
 * package is accepted when that minimum is ≤ our spec version. Anything we can't parse as a minimum
 * (e.g. a lone upper bound `"<0.2"`) is accepted — the payload digest check is the real integrity
 * gate, so `compat` errs toward leniency rather than rejecting a package we could actually use.
 */
fun isCompatibleSpec(compat: String): Boolean {
    val m = Regex("""^\s*(?:>=|\^|~)?\s*(\d+)\.(\d+)""").find(compat) ?: return true
    val reqMajor = m.groupValues[1].toInt()
    val reqMinor = m.groupValues[2].toInt()
    val specParts = AZPHALT_SPEC_VERSION.split(".")
    val specMajor = specParts[0].toInt()
    val specMinor = specParts.getOrElse(1) { "0" }.toInt()
    return reqMajor < specMajor || (reqMajor == specMajor && reqMinor <= specMinor)
}
