package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import com.hereliesaz.graffitixr.common.model.Fingerprint
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer

/**
 * The ARCore target-save paths run on Dispatchers.IO after the confirm tap. Two failure modes:
 * the artist opens another project before the write lands (it must not be written into THAT
 * project), and the write itself throws (it must be reported, not crash the app).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTargetSaveTest {

    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val projectManager: ProjectManager = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)
    private val currentProject = MutableStateFlow<GraffitiProject?>(GraffitiProject(id = "captured"))
    private val toastTexts = mutableListOf<String>()
    private lateinit var viewModel: MainViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settingsRepository.completedTutorials } returns flowOf(emptySet())
        every { projectRepository.currentProject } returns currentProject
        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<CharSequence>(), any()) } answers {
            synchronized(toastTexts) { toastTexts.add(secondArg<CharSequence>().toString()) }
            mockk(relaxed = true)
        }
        mockkStatic(Bitmap::class)
        every { Bitmap.createScaledBitmap(any(), any(), any(), any()) } returns mockk(relaxed = true)
        every { slamManager.setWallFingerprint(any(), any(), any(), any(), any(), any(), any(), any()) } returns
            mockk<Fingerprint>(relaxed = true)
        viewModel = MainViewModel(projectRepository, slamManager, projectManager, settingsRepository, context)
    }

    @After
    fun tearDown() {
        unmockkStatic(Toast::class)
        unmockkStatic(Bitmap::class)
        Dispatchers.resetMain()
    }

    private fun bitmap(): Bitmap = mockk(relaxed = true) {
        every { width } returns 64
        every { height } returns 64
    }

    /** The depth-buffer path (no wall plane): no anchor wait, straight to the save. */
    private fun confirmDepthCapture() {
        viewModel.onConfirmTargetCreation(
            bitmap = bitmap(),
            depthBuffer = ByteBuffer.allocateDirect(16),
            depthW = 2, depthH = 2, depthStride = 4,
            intrinsics = floatArrayOf(100f, 100f, 32f, 32f),
            viewMatrix = FloatArray(16),
            wallPlane = null,
        )
    }

    private fun awaitSaveFinished() {
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.uiState.value.isConfirmingTarget && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
        }
        assertFalse("save never finished", viewModel.uiState.value.isConfirmingTarget)
    }

    @Test
    fun `a target confirmed in one project is not written into a project opened meanwhile`() {
        val other = GraffitiProject(id = "opened-meanwhile")
        var written: GraffitiProject? = null
        coEvery { projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>()) } coAnswers {
            written = firstArg<(GraffitiProject) -> GraffitiProject>()(other)
        }

        confirmDepthCapture()
        awaitSaveFinished()

        assertEquals("the other project must come back untouched", other, written)
        coVerify(exactly = 0) { projectRepository.loadProject(any()) }
        synchronized(toastTexts) {
            assertTrue("no user-visible failure: $toastTexts", toastTexts.any { it.contains("not saved") })
        }
    }

    @Test
    fun `an IOException while saving the target is reported instead of crashing`() {
        coEvery { projectManager.appendTargetImage(any(), any(), any(), any()) } throws IOException("disk full")

        confirmDepthCapture()
        awaitSaveFinished()

        coVerify(exactly = 0) { projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>()) }
        synchronized(toastTexts) {
            assertTrue("no failure toast: $toastTexts", toastTexts.any { it.startsWith("Couldn't save the target") })
        }
        verify(exactly = 0) { Toast.makeText(any(), "Doodle Saved & Locked", any()) }
    }
}
