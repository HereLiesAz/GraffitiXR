package com.hereliesaz.graffitixr.data.di

import com.hereliesaz.graffitixr.data.DefaultUriProvider
import com.hereliesaz.graffitixr.data.UriProvider
import com.hereliesaz.graffitixr.data.repository.ProjectRepositoryImpl
import com.hereliesaz.graffitixr.domain.repository.ProjectRepository
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
        projectRepositoryImpl: ProjectRepositoryImpl
    ): ProjectRepository

    @Binds
    @Singleton
    abstract fun bindUriProvider(
        defaultUriProvider: DefaultUriProvider
    ): UriProvider
}
