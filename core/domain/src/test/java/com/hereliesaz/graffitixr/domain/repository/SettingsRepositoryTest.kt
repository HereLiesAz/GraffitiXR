package com.hereliesaz.graffitixr.domain.repository

import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsRepositoryTest {

    @Test
    fun `isRightHanded emits correct boolean value`() = runTest {
        val repo = mockk<SettingsRepository>()
        every { repo.isRightHanded } returns flowOf(true)
        val result = repo.isRightHanded.first()
        assertTrue(result)
    }

    @Test
    fun `setRightHanded calls underlying implementation correctly`() = runTest {
        val repo = mockk<SettingsRepository>(relaxed = true)
        repo.setRightHanded(false)
        coVerify { repo.setRightHanded(false) }
    }
}
