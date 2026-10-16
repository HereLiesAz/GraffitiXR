package com.hereliesaz.graffitixr.feature.ar

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every artist-facing `FeedbackEvent` that `ArViewModel` emits must come from a `core/design` string
 * resource that exists, non-empty, in the default resources and every locale with the same format
 * arguments. Reads the resource XML and the view-model source directly (no Android R class, no
 * Robolectric), like [StandaloneTextResourcesTest].
 */
class ArViewModelFeedbackResourcesTest {

    private val resDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "core/design/src/main/res") }
        .first { it.isDirectory }

    private val source: String = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/ArViewModel.kt") }
        .first { it.isFile }
        .readText()

    private fun strings(dir: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(dir, "strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private fun formatArgs(text: String): List<String> =
        Regex("%(\\d+\\$)?[sd]").findAll(text).map { it.value }.sorted().toList()

    /** Resource names the view model resolves for feedback (`DesignR.string.ar_feedback_*`). */
    private fun referencedFeedbackResources(): Set<String> =
        Regex("DesignR\\.string\\.(ar_feedback_[a-z0-9_]+)").findAll(source)
            .map { it.groupValues[1] }.toSet()

    @Test
    fun feedbackEventsNeverCarryAnEnglishLiteral() {
        // The first argument of every Error/Toast must not start with a string literal.
        val literal = Regex("FeedbackEvent\\.(Error|Toast)\\(\\s*\"")
        val offenders = literal.findAll(source).map { source.substring(it.range.first).lineSequence().first() }.toList()
        assertTrue("English literal reaches FeedbackEvent: $offenders", offenders.isEmpty())
        assertFalse(
            "no feedback strings were found",
            referencedFeedbackResources().isEmpty(),
        )
    }

    @Test
    fun everyReferencedFeedbackStringExistsInDefaultAndEveryLocaleWithMatchingArgs() {
        val names = referencedFeedbackResources()
        assertTrue("expected the extracted feedback strings", names.size >= 25)
        val localeDirs = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").isFile }!!
        assertTrue("expected the 14 non-default locales", localeDirs.size >= 14)
        val default = strings(File(resDir, "values"))
        for (name in names) {
            val base = default[name]
            assertTrue("$name missing or empty in values/", !base.isNullOrBlank())
            val args = formatArgs(base!!)
            for (dir in localeDirs) {
                val localized = strings(dir)[name]
                assertTrue("$name missing or empty in ${dir.name}", !localized.isNullOrBlank())
                assertEquals("$name format args differ in ${dir.name}", args, formatArgs(localized!!))
            }
        }
    }

    @Test
    fun formattedFeedbackStringsTakeTheArgumentsTheCallersSupply() {
        val default = strings(File(resDir, "values"))
        assertEquals(listOf("%1\$s"), formatArgs(default.getValue("ar_feedback_host_failed")))
        assertEquals(listOf("%1\$s"), formatArgs(default.getValue("ar_feedback_join_failed")))
        assertEquals(listOf("%1\$d"), formatArgs(default.getValue("ar_feedback_backbone_warning")))
    }
}
