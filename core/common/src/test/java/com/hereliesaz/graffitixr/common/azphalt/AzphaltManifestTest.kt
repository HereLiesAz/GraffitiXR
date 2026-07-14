package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AzphaltManifestTest {

    @Test
    fun `parses the invert example manifest`() {
        val m = parseManifest(
            """
            {
              "azphalt": "0.1",
              "id": "com.hereliesaz.invert",
              "name": "Invert",
              "version": "1.0.0",
              "kind": "code",
              "license": "MIT",
              "author": "Az",
              "description": "Invert layer colors, by adjustable strength.",
              "compat": ">=0.1",
              "entry": "code/main.js",
              "runtime": "js",
              "capabilities": ["bitmap", "params", "canvas"],
              "contributes": { "filters": [{ "id": "invert", "name": "Invert", "entry": "invert", "ui": "ui/panel.json" }] },
              "files": {}
            }
            """.trimIndent(),
        )
        assertEquals("com.hereliesaz.invert", m.id)
        assertEquals(ExtensionKind.CODE, m.kind)
        assertEquals(Runtime.JS, m.runtime)
        assertEquals(listOf(Capability.BITMAP, Capability.PARAMS, Capability.CANVAS), m.capabilities)
        assertEquals("invert", m.contributes?.filters?.single()?.id)
    }

    @Test
    fun `parses an asset manifest with lut assets`() {
        val m = parseManifest(
            """
            {
              "azphalt": "0.1", "id": "com.filmluts.teal", "name": "Teal LUT",
              "version": "1.0.0", "kind": "asset", "license": "CC-BY-4.0", "compat": ">=0.1",
              "assets": [{ "type": "lut", "path": "assets/teal.cube" }], "files": {}
            }
            """.trimIndent(),
        )
        assertEquals(ExtensionKind.ASSET, m.kind)
        assertEquals(AssetType.LUT, m.assets.single().type)
        assertEquals("assets/teal.cube", m.assets.single().path)
        assertNull(m.runtime)
        assertTrue(m.capabilities.isEmpty())
    }

    @Test
    fun `tolerates unknown future fields`() {
        val m = parseManifest(
            """
            { "azphalt":"0.2","id":"x.y","name":"N","version":"1.0.0","kind":"mixed",
              "license":"MIT","compat":">=0.2","files":{},"somethingNew":{"a":1},"pricing":42 }
            """.trimIndent(),
        )
        assertEquals(ExtensionKind.MIXED, m.kind)
    }

    @Test
    fun `parses shader and transition assets with params and ui`() {
        val m = parseManifest(
            """
            {
              "azphalt": "0.1", "id": "com.fx.pack", "name": "FX", "version": "1.0.0",
              "kind": "asset", "license": "MIT", "compat": ">=0.1",
              "assets": [
                { "type": "shader", "path": "assets/glow.fs", "params": { "format": "isf" }, "ui": "ui/glow.json" },
                { "type": "transition", "path": "assets/wipe.glsl", "params": { "format": "gl-transition" } }
              ],
              "files": {}
            }
            """.trimIndent(),
        )
        assertEquals(AssetType.SHADER, m.assets[0].type)
        assertEquals("ui/glow.json", m.assets[0].ui)
        assertEquals("isf", m.assets[0].params?.get("format")?.let { it.toString().trim('"') })
        assertEquals(AssetType.TRANSITION, m.assets[1].type)
        assertNull(m.assets[1].ui)
    }

    @Test
    fun `parses AI model assets with role and byteSize`() {
        val m = parseManifest(
            """
            {
              "azphalt": "0.1", "id": "com.ai.seg", "name": "Segmenter", "version": "1.0.0",
              "kind": "asset", "license": "MIT", "compat": ">=0.1",
              "targetApps": ["com.hereliesaz.graffitixr"],
              "assets": [
                { "type": "tflite", "path": "models/seg.tflite", "role": "segmentation", "byteSize": 4194304 },
                { "type": "lut", "path": "assets/grade.cube" }
              ],
              "files": {}
            }
            """.trimIndent(),
        )
        assertEquals(AssetType.TFLITE, m.assets[0].type)
        assertTrue(m.assets[0].type.isModel)
        assertEquals("segmentation", m.assets[0].role)
        assertEquals(4194304L, m.assets[0].byteSize)
        assertEquals(listOf("com.hereliesaz.graffitixr"), m.targetApps)
        assertFalse(m.assets[1].type.isModel)
    }

    @Test
    fun `unrecognized asset type parses as UNKNOWN instead of throwing`() {
        // A type newer than this build knows about must not break the parse.
        val m = parseManifest(
            """
            {
              "azphalt": "0.1", "id": "com.x.y", "name": "N", "version": "1.0.0",
              "kind": "asset", "license": "MIT", "compat": ">=0.1",
              "assets": [{ "type": "quantum-brush", "path": "assets/q.bin" }],
              "files": {}
            }
            """.trimIndent(),
        )
        assertEquals(AssetType.UNKNOWN, m.assets[0].type)
    }

    @Test
    fun `compat is validated against the implemented spec version`() {
        assertTrue(isCompatibleSpec(">=0.1"))
        assertTrue(isCompatibleSpec("0.1"))
        assertTrue(isCompatibleSpec("^0.1"))
        assertTrue(isCompatibleSpec("<0.2")) // lone upper bound → accepted (lenient)
        assertEquals(false, isCompatibleSpec(">=0.2"))
        assertEquals(false, isCompatibleSpec(">=1.0"))
        assertEquals("0.1", AZPHALT_SPEC_VERSION)
    }

    @Test
    fun `carries file digests for integrity`() {
        val m = parseManifest(
            """
            { "azphalt":"0.1","id":"x.y","name":"N","version":"1.0.0","kind":"code","license":"MIT",
              "compat":">=0.1","entry":"code/main.js","runtime":"wasm",
              "files":{"code/main.js":"sha256-abc","ui/panel.json":"sha256-def"} }
            """.trimIndent(),
        )
        assertEquals("sha256-abc", m.files["code/main.js"])
        assertEquals(Runtime.WASM, m.runtime)
    }
}
