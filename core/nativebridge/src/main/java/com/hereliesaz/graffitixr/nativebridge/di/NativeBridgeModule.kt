package com.hereliesaz.graffitixr.nativebridge.di

import android.content.Context
import com.hereliesaz.graffitixr.nativebridge.SlamManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NativeBridgeModule {

    @Provides
    @Singleton
    fun provideSlamManager(@ApplicationContext context: Context): SlamManager {
        return SlamManager(context)
    }
}
