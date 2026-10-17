package com.hereliesaz.graffitixr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

/**
 * Every pass the native engine can report has a localized name for the CV view's legend. The names are
 * read from PassEvidence.h and DesignFilters.h, so a pass added there without a label here fails this
 * rather than showing a raw engine name to the user.
 */
class CvPassLabelTest {

    private fun source(path: String) = File("../$path").readText()

    @Test
    fun `every pass name the native engine reports has a label`() {
        val passEvidence = source("core/nativebridge/src/main/cpp/include/PassEvidence.h")
        val filters = source("core/nativebridge/src/main/cpp/include/DesignFilters.h")
        val names = Regex("""return "([\w-]+)";""").findAll(passEvidence + filters)
            .map { it.groupValues[1] }.filter { it != "unknown" }.toSet()
        assertEquals("expected the 12 pass names, got $names", 12, names.size)
        names.forEach { assertNotNull("no localized label for pass '$it'", cvPassLabelRes(it)) }
    }

    @Test
    fun `an unknown pass has no label and falls back to its own name`() {
        assertNull(cvPassLabelRes("something-new"))
    }
}
