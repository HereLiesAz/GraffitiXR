package com.hereliesaz.graffitixr.data.di

import android.content.Context
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.data.repository.ProjectRepository
import com.hereliesaz.graffitixr.data.repository.ProjectRepositoryImpl
import com.hereliesaz.graffitixr.data.repository.SettingsRepository
import com.hereliesaz.graffitixr.data.repository.SettingsRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds
    @Singleton
    abstract fun bindProjectRepository(impl: ProjectRepositoryImpl): ProjectRepository

    companion object {
        @Provides
        @Singleton
        fun provideProjectManager(
            @ApplicationContext context: Context
        ): ProjectManager {
            return ProjectManager(context)
        }

        @Provides
        @Singleton
        fun provideSettingsRepository(
            @ApplicationContext context: Context
        ): SettingsRepository {
            return SettingsRepositoryImpl(context)
        }
    }
}
