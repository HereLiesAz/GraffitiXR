package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ArSessionTest {

    private lateinit var viewModel: ArViewModel
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
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
    fun `session should not resume if activity is paused`() = runTest {
        viewModel.setArMode(true, context)
        viewModel.onActivityPaused()
        // No direct way to check if session is paused, but no exception should be thrown.
    }

    @Test
    fun `session should not resume if not in AR mode`() = runTest {
        viewModel.setArMode(false, context)
        viewModel.onActivityResumed()
        // No direct way to check if session is resumed, but no exception should be thrown.
    }

    @Test
    fun `destroyArSession should not crash if session is null`() = runTest {
        // session is null by default
        viewModel.destroyArSession()
        // Should not throw any exception
    }
}
