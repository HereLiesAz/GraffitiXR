package com.hereliesaz.graffitixr.feature.editor

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StencilPackReaderTest {
    // Literal independently encoded 1x1 PNGs; expectations do not use the implementation's renderer.
    private val red = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGP4z8DwHwAFAAH/iZk9HQAAAABJRU5ErkJggg==",
        Base64.DEFAULT,
    )
    private val blue = Base64.decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR4nGNgYPj/HwADAgH/5ncLrgAAAABJRU5ErkJggg==",
        Base64.DEFAULT,
    )

    private val manifest = """{"format":"com.hereliesaz.stencilpack","version":1,"width":1,"height":1,""" +
        """"order":"bottom-to-top","coverage":"overpaint","layers":[""" +
        """{"name":"Base","file":"stencil-sheet-001.png","color":"#FFFF0000","visible":true},""" +
        """{"name":"Detail","file":"stencil-sheet-002.png","color":"#FF0000FF","visible":false}]}"""

    private fun archive(entries: List<Pair<String, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, bytes) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun pack(manifestText: String = manifest) = archive(
        listOf(
            "manifest.json" to manifestText.encodeToByteArray(),
            "stencil-sheet-001.png" to red,
            "stencil-sheet-002.png" to blue,
        ),
    )

    @Test
    fun readsAllSheetsInPaintOrderWithIndependentVisibility() {
        val loaded = StencilPackReader.read(ByteArrayInputStream(pack()))
        assertNotNull(loaded)
        assertEquals(1, loaded!!.width)
        assertEquals(1, loaded.height)
        assertEquals(listOf("Base", "Detail"), loaded.sheets.map { it.name })
        assertEquals(listOf(true, false), loaded.sheets.map { it.visible })
        assertEquals(listOf(0xFFFF0000L, 0xFF0000FFL), loaded.sheets.map { it.color })
        assertEquals(red.toList(), loaded.sheets[0].png.toList())
        assertEquals(blue.toList(), loaded.sheets[1].png.toList())
    }

    @Test
    fun rejectsUnknownVersionAndTraversal() {
        val versionTwo = manifest.replace("\"version\":1", "\"version\":2")
        assertNull(StencilPackReader.read(ByteArrayInputStream(pack(versionTwo))))
        val traversal = archive(
            listOf(
                "manifest.json" to manifest.encodeToByteArray(),
                "../stencil-sheet-001.png" to red,
                "stencil-sheet-002.png" to blue,
            ),
        )
        assertNull(StencilPackReader.read(ByteArrayInputStream(traversal)))
    }
}
