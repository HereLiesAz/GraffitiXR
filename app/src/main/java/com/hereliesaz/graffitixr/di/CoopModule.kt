package com.hereliesaz.graffitixr.di

import com.hereliesaz.graffitixr.common.coop.OpEmitter
import com.hereliesaz.graffitixr.core.collaboration.OpEmitterImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class CoopModule {
    @Binds
    @Singleton
    abstract fun bindOpEmitter(impl: OpEmitterImpl): OpEmitter
}
