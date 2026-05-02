package com.hereliesaz.graffitixr.feature.ar

import android.content.Context
import com.google.ar.core.Session
import com.hereliesaz.graffitixr.common.model.ArScanMode
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.graffitixr.nativebridge.depth.StereoDepthProvider
import com.hereliesaz.graffitixr.common.util.NativeLibLoader
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.Dispatchers
import org.junit.Ignore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ArSessionTest {

    private lateinit var viewModel: ArViewModel
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val stereoProvider: StereoDepthProvider = mockk(relaxed = true)
    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val projectManager: com.hereliesaz.graffitixr.data.ProjectManager = mockk(relaxed = true)
    private val collaborationManager: com.hereliesaz.graffitixr.core.collaboration.CollaborationManager = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkObject(NativeLibLoader)
        every { NativeLibLoader.loadAll() } returns Unit
        every { settingsRepository.arScanMode } returns flowOf(ArScanMode.CLOUD_POINTS)
        every { settingsRepository.isRightHanded } returns flowOf(true)
        every { settingsRepository.showAnchorBoundary } returns flowOf(false)
        every { projectRepository.currentProject } returns MutableStateFlow(null)
        every { context.filesDir } returns File("/tmp")
        viewModel = ArViewModel(slamManager, stereoProvider, projectRepository, settingsRepository, projectManager, collaborationManager, context)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkObject(NativeLibLoader)
    }

    @Ignore("ARCore Session(context) triggers UnsatisfiedLinkError in JVM — belongs in instrumented tests")
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
