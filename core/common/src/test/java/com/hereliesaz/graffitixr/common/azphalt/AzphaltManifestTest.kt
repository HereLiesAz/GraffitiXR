package com.hereliesaz.graffitixr.common.azphalt

import org.junit.Assert.assertEquals
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
