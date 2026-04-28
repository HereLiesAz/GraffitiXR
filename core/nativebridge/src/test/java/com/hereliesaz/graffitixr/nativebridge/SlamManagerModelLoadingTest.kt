package com.hereliesaz.graffitixr.nativebridge

import android.content.res.AssetManager
import io.mockk.mockk
import io.mockk.verify
import org.junit.Test

class SlamManagerModelLoadingTest {

    private val slamManager: SlamManager = mockk(relaxed = true)
    private val assets: AssetManager = mockk(relaxed = true)

    @Test
    fun `loadSuperPoint is callable on SlamManager`() {
        slamManager.loadSuperPoint(assets)
        verify { slamManager.loadSuperPoint(assets) }
    }

    @Test
    fun `loadLowLightEnhancer is callable on SlamManager`() {
        slamManager.loadLowLightEnhancer(assets)
        verify { slamManager.loadLowLightEnhancer(assets) }
    }
}
