package com.hereliesaz.graffitixr.feature.ar

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Every [StandaloneTextKey] must resolve to a real, non-empty string in the default resources and in
 * every locale, with the same format arguments. Reads the `core/design` resource XML and the
 * `StandaloneTextRes.kt` mapping source directly, so it needs neither the Android R class nor
 * Robolectric.
 */
class StandaloneTextResourcesTest {

    private val resDir: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "core/design/src/main/res") }
        .first { it.isDirectory }

    private val mappingSource: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .map { File(it, "feature/ar/src/main/java/com/hereliesaz/graffitixr/feature/ar/StandaloneTextRes.kt") }
        .first { it.isFile }

    private fun strings(dir: File): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(File(dir, "strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent }
    }

    private fun formatArgs(text: String): List<String> =
        Regex("%(\\d+\\$)?[sd]").findAll(text).map { it.value }.sorted().toList()

    /** key -> resource name, parsed from the `when` in StandaloneTextRes.kt. */
    private fun mappedResources(): Map<StandaloneTextKey, String> =
        Regex("StandaloneTextKey\\.([A-Z_]+) -> DesignR\\.string\\.([a-z0-9_]+)")
            .findAll(mappingSource.readText())
            .associate { StandaloneTextKey.valueOf(it.groupValues[1]) to it.groupValues[2] }

    @Test
    fun everyKeyIsMappedToAResourceName() {
        val mapped = mappedResources()
        assertEquals(StandaloneTextKey.entries.toSet(), mapped.keys)
        assertEquals("two keys share a resource", mapped.size, mapped.values.toSet().size)
    }

    @Test
    fun everyKeyHasANonEmptyStringInDefaultAndEveryLocale() {
        val mapped = mappedResources()
        val localeDirs = resDir.listFiles { f -> f.isDirectory && f.name.startsWith("values-") && File(f, "strings.xml").isFile }!!
        assertTrue("expected the 14 non-default locales", localeDirs.size >= 14)
        val default = strings(File(resDir, "values"))
        for ((key, name) in mapped) {
            val base = default[name]
            assertTrue("$key -> $name missing or empty in values/", !base.isNullOrBlank())
            val args = formatArgs(base!!)
            for (dir in localeDirs) {
                val localized = strings(dir)[name]
                assertTrue("$key -> $name missing or empty in ${dir.name}", !localized.isNullOrBlank())
                assertEquals("$name format args differ in ${dir.name}", args, formatArgs(localized!!))
            }
        }
    }

    @Test
    fun targetTooWeakTakesTheTwoCountsTheCallerSupplies() {
        val name = mappedResources().getValue(StandaloneTextKey.TARGET_TOO_WEAK)
        assertEquals(listOf("%1\$d", "%2\$d"), formatArgs(strings(File(resDir, "values")).getValue(name)))
    }
}
