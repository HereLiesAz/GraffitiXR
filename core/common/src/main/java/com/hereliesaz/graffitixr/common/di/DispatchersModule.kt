package com.hereliesaz.graffitixr.common.di

import com.hereliesaz.graffitixr.common.dispatcher.DefaultDispatcher
import com.hereliesaz.graffitixr.common.dispatcher.DispatcherProvider
import com.hereliesaz.graffitixr.common.dispatcher.IoDispatcher
import com.hereliesaz.graffitixr.common.dispatcher.MainDispatcher
import com.hereliesaz.graffitixr.common.dispatcher.MainImmediateDispatcher
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
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @MainDispatcher
    fun provideMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    @Provides
    @MainImmediateDispatcher
    fun provideMainImmediateDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    @Provides
    @Singleton
    fun provideDispatcherProvider(): DispatcherProvider = object : DispatcherProvider {
        override val main: CoroutineDispatcher get() = Dispatchers.Main
        override val mainImmediate: CoroutineDispatcher get() = Dispatchers.Main.immediate
        override val io: CoroutineDispatcher get() = Dispatchers.IO
        override val default: CoroutineDispatcher get() = Dispatchers.Default
    }
}
