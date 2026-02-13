package com.hereliesaz.graffitixr.nativebridge.di

import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NativeBridgeModule {

    @Provides
    @Singleton
    fun provideSlamManager(): SlamManager {
        val manager = SlamManager()
        // Initialize the engine immediately upon creation to ensure it's ready
        // for any consumer (AR or Editor).
        manager.initialize()
        return manager
    }
}