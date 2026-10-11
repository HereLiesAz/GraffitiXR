package com.hereliesaz.graffitixr.data.repository

import com.hereliesaz.graffitixr.common.model.CoverageGlowColor
import com.hereliesaz.graffitixr.common.model.CoverageGlowIntensity
import com.hereliesaz.graffitixr.common.model.CoverageGlowStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Exercises [SettingsRepositoryImpl] against a real, Robolectric-backed `android.content.Context` —
 * a genuine DataStore-backed `Preferences` file, not a mock of [SettingsRepository] itself.
 *
 * The audit's finding here was that this file previously mocked `SettingsRepository`, called
 * `setRightHanded` on the mock, and asserted the mock recorded the call — which passes for any
 * implementation and none, and so caught nothing when the real persistence path was never wired up.
 * These tests instead call the production `setRightHanded`/`isRightHanded` and would fail if either
 * one silently stopped touching the DataStore (wrong key, no write, stale read, etc).
 *
 * Pin SDK to 34; compileSdk=37 exceeds what Robolectric 4.16.1 ships shadows for by default — same
 * pin as :collab's QrPayloadTest.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsRepositoryImplTest {

    private fun newRepository() = SettingsRepositoryImpl(RuntimeEnvironment.getApplication())

    @Test
    fun `isRightHanded defaults to true when nothing has been written`() = runTest {
        val repo = newRepository()
        assertTrue(repo.isRightHanded.first())
    }

    @Test
    fun `setRightHanded persists false and isRightHanded reads it back`() = runTest {
        val repo = newRepository()
        repo.setRightHanded(false)
        assertFalse(repo.isRightHanded.first())
    }

    @Test
    fun `setRightHanded round-trips back to true after being set false`() = runTest {
        val repo = newRepository()
        repo.setRightHanded(false)
        assertFalse(repo.isRightHanded.first())

        repo.setRightHanded(true)
        assertTrue(repo.isRightHanded.first())
    }

    @Test
    fun `crashReportingConsent defaults to false when nothing has been written`() = runTest {
        val repo = newRepository()
        assertFalse(repo.crashReportingConsent.first())
    }

    @Test
    fun `setCrashReportingConsent persists true and crashReportingConsent reads it back`() = runTest {
        val repo = newRepository()
        repo.setCrashReportingConsent(true)
        assertTrue(repo.crashReportingConsent.first())
    }

    @Test
    fun `crashReportToken defaults to blank when nothing has been written`() = runTest {
        val repo = newRepository()
        assertEquals("", repo.crashReportToken.first())
    }

    @Test
    fun `setCrashReportToken persists the token and reads it back`() = runTest {
        val repo = newRepository()
        repo.setCrashReportToken("ghp_example")
        assertEquals("ghp_example", repo.crashReportToken.first())
    }

    @Test
    fun `setCrashReportToken trims surrounding whitespace`() = runTest {
        val repo = newRepository()
        repo.setCrashReportToken("  ghp_example\n")
        assertEquals("ghp_example", repo.crashReportToken.first())
    }

    // One test for the whole coverage-glow lifecycle: the DataStore instance can outlive a single
    // test, so the defaults are asserted BEFORE this test writes its own keys, never after.
    @Test
    fun `coverage glow settings default to a hot pink 14 percent wash and round-trip`() = runTest {
        val repo = newRepository()
        assertEquals(CoverageGlowColor.HOT_PINK.argb, repo.coverageGlowColor.first())
        assertEquals(CoverageGlowIntensity.DEFAULT, repo.coverageGlowIntensity.first(), 0f)
        assertEquals(CoverageGlowStyle.WASH, repo.coverageGlowStyle.first())

        repo.setCoverageGlowColor(CoverageGlowColor.CYAN.argb)
        repo.setCoverageGlowIntensity(0.42f)
        repo.setCoverageGlowStyle(CoverageGlowStyle.MISSING_TILES)
        assertEquals(CoverageGlowColor.CYAN.argb, repo.coverageGlowColor.first())
        assertEquals(0.42f, repo.coverageGlowIntensity.first(), 0f)
        assertEquals(CoverageGlowStyle.MISSING_TILES, repo.coverageGlowStyle.first())

        // Colour is stored opaque (intensity owns opacity); intensity is clamped to the slider range.
        repo.setCoverageGlowColor(0x00808080)
        assertEquals(CoverageGlowColor.MID_GREY.argb, repo.coverageGlowColor.first())
        repo.setCoverageGlowIntensity(5f)
        assertEquals(CoverageGlowIntensity.MAX, repo.coverageGlowIntensity.first(), 0f)
        repo.setCoverageGlowIntensity(-1f)
        assertEquals(CoverageGlowIntensity.MIN, repo.coverageGlowIntensity.first(), 0f)
        repo.setCoverageGlowIntensity(Float.NaN)
        assertEquals(CoverageGlowIntensity.DEFAULT, repo.coverageGlowIntensity.first(), 0f)

        // Restore the defaults so no other test in this process sees this one's writes.
        repo.setCoverageGlowColor(CoverageGlowColor.DEFAULT.argb)
        repo.setCoverageGlowStyle(CoverageGlowStyle.WASH)
        assertEquals(CoverageGlowStyle.WASH, repo.coverageGlowStyle.first())
    }
}
