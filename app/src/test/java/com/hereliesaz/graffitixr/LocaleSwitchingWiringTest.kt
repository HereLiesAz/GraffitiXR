package com.hereliesaz.graffitixr

import androidx.appcompat.app.AppCompatActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The Language setting goes through AppCompatDelegate.setApplicationLocales, which below API 33
 * only reaches AppCompat activities and is only remembered across launches with the
 * autoStoreLocales service. Each of these was missing once, and each makes the setting a silent
 * no-op on API 26–32 without failing anything else.
 */
class LocaleSwitchingWiringTest {

    private val androidNs = "http://schemas.android.com/apk/res/android"
    private val manifest by lazy {
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))
    }

    @Test
    fun `MainActivity is an AppCompatActivity`() {
        assertTrue(AppCompatActivity::class.java.isAssignableFrom(MainActivity::class.java))
    }

    @Test
    fun `MainActivity uses an AppCompat theme`() {
        val activities = manifest.getElementsByTagName("activity")
        val main = (0 until activities.length).map { activities.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(androidNs, "name") == ".MainActivity" }
        assertEquals("@style/Theme.GraffitiXR.Main", main.getAttributeNS(androidNs, "theme"))
        val themes = File("src/main/res/values/themes.xml").readText()
        assertTrue(themes.contains("name=\"Theme.GraffitiXR.Main\" parent=\"Theme.AppCompat"))
    }

    @Test
    fun `app locales are auto-stored below API 33`() {
        val services = manifest.getElementsByTagName("service")
        val holder = (0 until services.length).map { services.item(it) as org.w3c.dom.Element }
            .single { it.getAttributeNS(androidNs, "name") == "androidx.appcompat.app.AppLocalesMetadataHolderService" }
        val meta = holder.getElementsByTagName("meta-data").item(0) as org.w3c.dom.Element
        assertEquals("autoStoreLocales", meta.getAttributeNS(androidNs, "name"))
        assertEquals("true", meta.getAttributeNS(androidNs, "value"))
    }
}
