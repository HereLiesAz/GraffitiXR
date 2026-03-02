package com.hereliesaz.graffitixr.feature.ar

import com.google.ar.core.Session
import com.hereliesaz.graffitixr.feature.ar.rendering.ArRenderer
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArSessionTest {

    private lateinit var viewModel: ArViewModel
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        viewModel = ArViewModel(slamManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `resumeArSession should not crash if session is null`() = runTest {
        // session is null by default
        viewModel.resumeArSession()
        // Should not throw any exception
    }

    @Test
    fun `pauseArSession should not crash if session is null`() = runTest {
        // session is null by default
        viewModel.pauseArSession()
        // Should not throw any exception
    }

    @Test
    fun `destroyArSession should not crash if session is null`() = runTest {
        // session is null by default
        viewModel.destroyArSession()
        // Should not throw any exception
    }
}
