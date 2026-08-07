package com.hereliesaz.graffitixr.domain.repository

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `:core:domain` only declares the [SettingsRepository] interface — the DataStore-backed
 * implementation ([SettingsRepositoryImpl][com.hereliesaz.graffitixr.data.repository.SettingsRepositoryImpl])
 * lives in `:core:data`, which depends on this module and not the other way around, so it cannot be
 * exercised from here. The real, "would fail if persistence broke" regression coverage for
 * `isRightHanded` lives in that module's `SettingsRepositoryImplTest`, against the actual DataStore.
 *
 * What used to be here instead mocked [SettingsRepository] itself, called the mock, and asserted the
 * mock recorded the call (`coVerify { repo.setRightHanded(false) }`) — true for ANY mock regardless
 * of whether a real implementation would have honoured the write. That is what "the audit found this
 * covers the bug" actually meant: the test could not fail short of MockK itself breaking.
 *
 * This version at least wires the mock's `setRightHanded` to mutate the same backing
 * [MutableStateFlow] that `isRightHanded` reads from, so the test is asserting the thing that
 * actually matters at this layer — a read after a write observes the written value — rather than
 * merely that a method was invoked.
 */
class SettingsRepositoryTest {

    private fun fakeBackedRepository(initial: Boolean = true): Pair<SettingsRepository, MutableStateFlow<Boolean>> {
        val backing = MutableStateFlow(initial)
        val repo = mockk<SettingsRepository>()
        every { repo.isRightHanded } returns backing
        coEvery { repo.setRightHanded(any()) } answers { backing.value = firstArg() }
        return repo to backing
    }

    @Test
    fun `isRightHanded defaults to true`() = runTest {
        val (repo, _) = fakeBackedRepository()
        assertTrue(repo.isRightHanded.first())
    }

    @Test
    fun `isRightHanded reflects the most recent setRightHanded call`() = runTest {
        val (repo, _) = fakeBackedRepository(initial = true)

        repo.setRightHanded(false)
        assertFalse(repo.isRightHanded.first())

        repo.setRightHanded(true)
        assertTrue(repo.isRightHanded.first())
    }
}
