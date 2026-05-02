package com.hereliesaz.graffitixr.nativebridge.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

// SlamManager is @Singleton @Inject constructor — Hilt injects it directly; no manual @Provides needed.
@Module
@InstallIn(SingletonComponent::class)
object NativeBridgeModule
