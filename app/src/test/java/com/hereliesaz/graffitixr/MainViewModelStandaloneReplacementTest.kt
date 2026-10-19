package com.hereliesaz.graffitixr

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import com.hereliesaz.graffitixr.common.model.Doodle
import com.hereliesaz.graffitixr.common.model.GraffitiProject
import com.hereliesaz.graffitixr.common.model.SphereSlamAtlasPage
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
import com.hereliesaz.graffitixr.domain.repository.SettingsRepository
import com.hereliesaz.graffitixr.feature.ar.StandaloneFingerprintPlacement
import com.hereliesaz.graffitixr.feature.ar.anchor.StandaloneFingerprintBuilder
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import com.hereliesaz.sphereslam.TileId
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Replacing a standalone target commits the candidate before native KPM has seen it. The previous
 * target must survive until KPM accepts the candidate, and come back when KPM rejects it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelStandaloneReplacementTest {

    private val projectRepository: ProjectRepository = mockk(relaxed = true)
    private val slamManager: SlamManager = mockk(relaxed = true)
    private val projectManager: ProjectManager = mockk(relaxed = true)
    private val settingsRepository: SettingsRepository = mockk(relaxed = true)
    private val context: Context = mockk(relaxed = true)

    private val oldUri: Uri = mockk(relaxed = true)
    private val oldPage: Uri = mockk(relaxed = true)
    private val oldDoodle: Doodle = mockk(relaxed = true)
    private val candidates = ArrayDeque<Uri>()
    private val deleted = mutableListOf<Uri?>()

    private val previous = GraffitiProject(
        id = "p",
        sphereSlamReferenceUri = oldUri,
        sphereSlamReferenceWidthMeters = 2f,
        sphereSlamAnchorGeneration = 3L,
        sphereSlamPlacementAnchorGeneration = 3L,
        sphereSlamFingerprint = oldDoodle,
        sphereSlamMapFromFingerprint = List(16) { 1f },
        sphereSlamAtlasPages = listOf(
            SphereSlamAtlasPage(
                pageNo = 1,
                referenceUri = oldPage,
                referenceWidthMeters = 2f,
                physicallyMetric = false,
                canonicalFromPage = List(16) { if (it % 5 == 0) 1f else 0f },
            ),
        ),
    )
    private val current = MutableStateFlow<GraffitiProject?>(previous)
    private lateinit var viewModel: MainViewModel

    @Before
    fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        every { settingsRepository.completedTutorials } returns flowOf(emptySet())
        every { projectRepository.currentProject } returns current
        coEvery { projectRepository.updateProject(any<(GraffitiProject) -> GraffitiProject>()) } coAnswers {
            current.value = firstArg<(GraffitiProject) -> GraffitiProject>()(current.value!!)
        }
        coEvery { projectManager.saveSphereSlamReference(any(), any(), any()) } answers { candidates.removeFirst() }
        coEvery { projectManager.appendDoodleImage(any(), any(), any(), any()) } returns emptyList()
        coEvery { projectManager.deleteSphereSlamReference(any(), any(), any()) } coAnswers {
            synchronized(deleted) { deleted += thirdArg<Uri?>() }
        }
        coEvery { projectManager.deleteSphereSlamAtlasPage(any(), any(), any()) } coAnswers {
            synchronized(deleted) { deleted += thirdArg<Uri?>() }
        }
        mockkStatic(Toast::class)
        every { Toast.makeText(any(), any<CharSequence>(), any()) } returns mockk(relaxed = true)
        mockkStatic(Bitmap::class)
        every { Bitmap.createScaledBitmap(any(), any(), any(), any()) } returns mockk(relaxed = true)
        mockkObject(StandaloneFingerprintBuilder)
        every { StandaloneFingerprintBuilder.build(any(), any(), any(), any(), any()) } returns mockk(relaxed = true)
        viewModel = MainViewModel(projectRepository, slamManager, projectManager, settingsRepository, context)
    }

    @After
    fun tearDown() {
        unmockkObject(StandaloneFingerprintBuilder)
        unmockkStatic(Toast::class)
        unmockkStatic(Bitmap::class)
        Dispatchers.resetMain()
    }

    private fun confirm(candidate: Uri) {
        candidates += candidate
        viewModel.onConfirmStandaloneTargetCreation(
            bitmap = mockk(relaxed = true),
            selectionMask = null,
            placement = StandaloneFingerprintPlacement(FloatArray(16), 1.5f, false, TileId(0, 0)),
        )
        val deadline = System.currentTimeMillis() + 5_000
        while (viewModel.uiState.value.isConfirmingDoodle && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertFalse("save never finished", viewModel.uiState.value.isConfirmingDoodle)
        assertSame(candidate, current.value!!.sphereSlamReferenceUri)
    }

    private fun settle() = Thread.sleep(200)

    @Test
    fun `the previous target is kept until KPM accepts the candidate, then deleted`() {
        val candidate: Uri = mockk(relaxed = true)
        confirm(candidate)
        assertTrue("deleted before validation: $deleted", deleted.isEmpty())

        viewModel.onStandaloneReferenceRegistered(candidate)
        settle()
        assertEquals(setOf(oldUri, oldPage), synchronized(deleted) { deleted.toSet() })
        // Settled: a later rejection of the same URI no longer rolls anything back.
        assertFalse(viewModel.onStandaloneReferenceRejected(candidate))
    }

    @Test
    fun `a rejected candidate restores the previous target and deletes the candidate`() {
        val candidate: Uri = mockk(relaxed = true)
        confirm(candidate)

        assertTrue(viewModel.onStandaloneReferenceRejected(candidate))
        settle()
        val restored = current.value!!
        assertSame(oldUri, restored.sphereSlamReferenceUri)
        assertEquals(2f, restored.sphereSlamReferenceWidthMeters)
        assertSame(oldDoodle, restored.sphereSlamFingerprint)
        assertEquals(previous.sphereSlamMapFromFingerprint, restored.sphereSlamMapFromFingerprint)
        assertEquals(previous.sphereSlamAtlasPages, restored.sphereSlamAtlasPages)
        // A new generation (everything re-solves for the page), still placed.
        assertEquals(5L, restored.sphereSlamAnchorGeneration)
        assertEquals(5L, restored.sphereSlamPlacementAnchorGeneration)
        assertEquals(listOf<Uri?>(candidate), synchronized(deleted) { deleted.toList() })
        coVerify { projectRepository.loadProject("p") }
    }

    @Test
    fun `replacing an unvalidated candidate keeps the original rollback`() {
        val first: Uri = mockk(relaxed = true)
        val second: Uri = mockk(relaxed = true)
        confirm(first)
        confirm(second)
        // The first candidate is dropped at once; the validated original is still kept.
        assertEquals(listOf<Uri?>(first), synchronized(deleted) { deleted.toList() })

        assertFalse("the superseded candidate is no longer pending", viewModel.onStandaloneReferenceRejected(first))
        assertTrue(viewModel.onStandaloneReferenceRejected(second))
        settle()
        assertSame(oldUri, current.value!!.sphereSlamReferenceUri)
    }

    @Test
    fun `a first target has nothing to roll back`() {
        current.value = GraffitiProject(id = "p")
        val candidate: Uri = mockk(relaxed = true)
        confirm(candidate)
        assertFalse(viewModel.onStandaloneReferenceRejected(candidate))
    }
}
