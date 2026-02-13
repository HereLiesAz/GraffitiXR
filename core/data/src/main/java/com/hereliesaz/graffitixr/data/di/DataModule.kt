package com.hereliesaz.graffitixr.data.di

import com.hereliesaz.graffitixr.data.repository.ProjectRepositoryImpl
import com.hereliesaz.graffitixr.core.domain.repository.ProjectRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindProjectRepository(
        impl: ProjectRepositoryImpl
    ): ProjectRepository
}