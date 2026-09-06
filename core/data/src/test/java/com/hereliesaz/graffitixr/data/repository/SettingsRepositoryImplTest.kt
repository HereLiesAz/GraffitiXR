package com.hereliesaz.graffitixr.data.repository

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
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
}
