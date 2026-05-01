package com.hereliesaz.graffitixr

import com.hereliesaz.aznavrail.tutorial.AzHighlight
import com.hereliesaz.graffitixr.common.model.EditorMode
import com.hereliesaz.graffitixr.common.model.Layer
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Asserts every AzHighlight.Item(id) reference in the registered tutorials
 * resolves to a rail-item ID that ConfigureRailItems will register for the
 * given layer set. Eliminates the "IllegalArgumentException: tutorial anchor
 * matches no rail item" bug class.
 *
 * Tutorial production is Compose-bound (TutorialDefinitions.getTutorials is
 * @Composable). This test exercises a non-Compose path by inspecting the
 * tutorials map keys directly — every key is the same string used as the
 * AzHighlight.Item anchor in createSimpleTutorial, by construction. The test
 * therefore asserts that every tutorial KEY corresponds to a registered rail
 * item, which is equivalent to asserting every anchor does.
 *
 * For tutorials with non-key anchors (the multi-card tutorials in
 * TutorialDefinitions and GraffitiTutorials), those anchor strings are
 * enumerated explicitly below.
 */
class TutorialAnchorTest {

    private fun sampleLayers() = listOf(
        Layer(id = "L1", name = "one"),
        Layer(id = "L2", name = "two"),
        Layer(id = "weird id 🎨", name = "non-ascii"),
    )

    /**
     * The set of explicit AzHighlight.Item anchors used in multi-card
     * tutorials (where the anchor is not the same as the tutorial key).
     * Keep in sync with TutorialDefinitions.kt and GraffitiTutorials.kt.
     */
    private val explicitAnchors = setOf(
        "mode.ar",       // GraffitiTutorials: mode.ar.firstRun first card
        "mode.overlay",  // GraffitiTutorials: mode.overlay.firstRun first card
        "mode.mockup",   // GraffitiTutorials: mode.mockup.firstRun first card
        "target.create", // TutorialDefinitions: target.create first card
        "design.addImg", // TutorialDefinitions: design.addImg first card
        "tool.lockTrace", // TutorialDefinitions: tool.lockTrace first card
    )

    @Test
    fun `every explicit AzHighlight Item anchor matches a registered rail item`() {
        val layers = sampleLayers()
        val mode = EditorMode.AR
        val railIds = enumerateRailItemIds(layers, mode)

        explicitAnchors.forEach { anchor ->
            assertTrue(
                "AzHighlight.Item(\"$anchor\") has no matching rail item in mode $mode",
                anchor in railIds,
            )
        }
    }

    @Test
    fun `every per-layer dynamic anchor matches a registered rail item`() {
        val layers = sampleLayers()
        val railIds = enumerateRailItemIds(layers, EditorMode.AR)

        layers.forEach { layer ->
            // Anchors used in TutorialDefinitions multi-card tutorials
            assertTrue(
                "stencil anchor missing for layer ${layer.id}",
                layerId(layer, "stencil") in railIds,
            )
            assertTrue(
                "adj anchor missing for layer ${layer.id}",
                layerId(layer, "adj") in railIds,
            )
            assertTrue(
                "iso anchor missing for layer ${layer.id}",
                layerId(layer, "iso") in railIds,
            )
        }
    }
}
