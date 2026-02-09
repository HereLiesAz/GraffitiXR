package com.hereliesaz.graffitixr.core.common.dispatcher.di

import com.hereliesaz.graffitixr.core.common.dispatcher.DispatcherProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider {
        return object : DispatcherProvider {
            override val main: CoroutineDispatcher get() = Dispatchers.Main
            override val io: CoroutineDispatcher get() = Dispatchers.IO
            override val default: CoroutineDispatcher get() = Dispatchers.Default
        }
    }
}