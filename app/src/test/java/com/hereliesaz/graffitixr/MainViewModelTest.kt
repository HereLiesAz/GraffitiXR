package com.hereliesaz.graffitixr

import android.app.Application
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.xr.runtime.math.Pose
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.kotlin.mock

/**
 * Unit tests for the [MainViewModel].
 *
 * This class verifies the business logic and state management within the ViewModel,
 * ensuring that UI state is updated correctly in response to events.
 */
@ExperimentalCoroutinesApi
class MainViewModelTest {

    // This rule swaps the background executor used by the Architecture Components with a
    // different one which executes each task synchronously. This is crucial for testing LiveData/StateFlow.
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    // Mock the Application context, as it's a dependency of AndroidViewModel.
    private val application: Application = mock()

    // A test dispatcher for controlling coroutine execution in tests.
    private val testDispatcher = StandardTestDispatcher()

    // The instance of the ViewModel that will be tested.
    private lateinit var viewModel: MainViewModel

    /**
     * Sets up the test environment before each test.
     * This function initializes the main coroutine dispatcher to our test dispatcher
     * and creates a new instance of the ViewModel.
     */
    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        viewModel = MainViewModel(application)
    }

    /**
     * Tears down the test environment after each test.
     * This function resets the main coroutine dispatcher to its original state.
     */
    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /**
     * **Given** a fresh ViewModel,
     * **When** `onSelectImage` is called with a mock URI,
     * **Then** the `uiState` flow should emit a new state where the `imageUri` property
     * is updated to the provided URI.
     */
    @Test
    fun `onSelectImage updates imageUri in uiState`() = runTest {
        // Arrange
        val mockUri: Uri = mock()

        // Act & Assert
        viewModel.uiState.test {
            // The initial state should have a null imageUri.
            assertEquals(null, awaitItem().imageUri)

            // Trigger the state change.
            viewModel.onSelectImage(mockUri)

            // The new state should have the mockUri.
            val updatedState = awaitItem()
            assertEquals(mockUri, updatedState.imageUri)

            // Cancel the collector and ignore any further emissions.
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * **Given** a fresh ViewModel,
     * **When** `onOpacityChange` is called with a new value,
     * **Then** the `opacity` property in the `uiState` should be updated.
     */
    @Test
    fun `onOpacityChange updates opacity in uiState`() = runTest {
        // Arrange
        val newOpacity = 0.5f

        // Act & Assert
        viewModel.uiState.test {
            // Initial state
            assertEquals(1f, awaitItem().opacity)

            // Trigger the state change
            viewModel.onOpacityChange(newOpacity)

            // Assert the new state
            assertEquals(newOpacity, awaitItem().opacity)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * **Given** a valid `hitTestPose` is set,
     * **When** `onAddMarker` is called multiple times,
     * **Then** the `markerPoses` list should grow accordingly, but not exceed four markers.
     */
    @Test
    fun `onAddMarker adds markers but respects the limit of four`() = runTest {
        // Arrange
        val mockPose1: Pose = mock()
        val mockPose2: Pose = mock()
        val mockPose3: Pose = mock()
        val mockPose4: Pose = mock()
        val mockPose5: Pose = mock()

        // Act & Assert
        viewModel.uiState.test {
            // Initial state
            assertEquals(0, awaitItem().markerPoses.size)

            // Add first marker
            viewModel.onHitTestResult(mockPose1)
            awaitItem() // Consume state update from onHitTestResult
            viewModel.onAddMarker()
            assertEquals(1, awaitItem().markerPoses.size)

            // Add second marker
            viewModel.onHitTestResult(mockPose2)
            awaitItem() // Consume state update
            viewModel.onAddMarker()
            assertEquals(2, awaitItem().markerPoses.size)

            // Add third marker
            viewModel.onHitTestResult(mockPose3)
            awaitItem() // Consume state update
            viewModel.onAddMarker()
            assertEquals(3, awaitItem().markerPoses.size)

            // Add fourth marker
            viewModel.onHitTestResult(mockPose4)
            awaitItem() // Consume state update
            viewModel.onAddMarker()
            assertEquals(4, awaitItem().markerPoses.size)

            // Try to add a fifth marker
            viewModel.onHitTestResult(mockPose5)
            awaitItem() // Consume state update from onHitTestResult
            viewModel.onAddMarker() // This should not add a marker or emit a new state

            // Assert that no new state is emitted for the marker list size and the size remains 4
            expectNoEvents()
            assertEquals(4, viewModel.uiState.value.markerPoses.size)

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * **Given** a fresh ViewModel,
     * **When** `onSelectBackgroundImage` is called with a mock URI,
     * **Then** the `uiState` should be updated to switch to `STATIC_IMAGE` mode,
     * set the `backgroundImageUri`, and reset AR-related properties.
     */
    @Test
    fun `onSelectBackgroundImage updates state correctly`() = runTest {
        // Arrange
        val mockUri: Uri = mock()

        // Act & Assert
        viewModel.uiState.test {
            // Initial state
            awaitItem()

            // Trigger the state change
            viewModel.onSelectBackgroundImage(mockUri)

            // Assert the new state
            val updatedState = awaitItem()
            assertEquals(mockUri, updatedState.backgroundImageUri)
            assertEquals(EditorMode.STATIC_IMAGE, updatedState.editorMode)
            assertEquals(null, updatedState.hitTestPose)
            assertEquals(true, updatedState.markerPoses.isEmpty())
            assertEquals(4, updatedState.stickerCorners.size) // Check that corners are initialized

            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * **Given** the ViewModel is in AR mode with markers,
     * **When** `onClear` is called,
     * **Then** the `markerPoses` list in the `uiState` should be cleared.
     */
    @Test
    fun `onClear clears markers in AR mode`() = runTest {
        // Arrange
        val mockPose: Pose = mock()
        viewModel.onAddMarker() // This won't add a marker as hitTestPose is null
        viewModel.onHitTestResult(mockPose)
        viewModel.onAddMarker() // This will add a marker

        // Act & Assert
        viewModel.uiState.test {
            // Initial state after adding a marker
            val initialState = awaitItem()
            assertEquals(1, initialState.markerPoses.size)

            // Trigger the clear action
            viewModel.onClear()

            // The new state should have an empty list of markers
            val updatedState = awaitItem()
            assertEquals(true, updatedState.markerPoses.isEmpty())

            cancelAndIgnoreRemainingEvents()
        }
    }
}