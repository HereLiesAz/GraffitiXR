package com.hereliesaz.graffitixr.data.di

import android.content.Context
import com.hereliesaz.graffitixr.data.ProjectManager
import com.hereliesaz.graffitixr.data.repository.ProjectRepositoryImpl
import com.hereliesaz.graffitixr.data.repository.SettingsRepositoryImpl
import com.hereliesaz.graffitixr.data.repository.ProjectRepository
import com.hereliesaz.graffitixr.data.repository.SettingsRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideProjectRepository(
        @ApplicationContext context: Context,
        projectManager: ProjectManager
    ): ProjectRepository {
        return ProjectRepositoryImpl(context, projectManager)
    }

    @Provides
    @Singleton
    fun provideProjectManager(
        @ApplicationContext context: Context
    ): ProjectManager {
        // Bridging legacy ProjectManager to use the new Repository
        // This keeps MainViewModel happy without rewriting it entirely yet
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