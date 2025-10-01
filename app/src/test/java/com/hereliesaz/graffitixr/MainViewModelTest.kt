package com.hereliesaz.graffitixr

import android.app.Application
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
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
}