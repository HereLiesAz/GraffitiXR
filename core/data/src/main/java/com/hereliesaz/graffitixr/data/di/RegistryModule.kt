package com.hereliesaz.graffitixr.data.di

import com.hereliesaz.graffitixr.data.azphalt.DEFAULT_REGISTRY_URL
import com.hereliesaz.graffitixr.data.azphalt.HttpTransport
import com.hereliesaz.graffitixr.data.azphalt.RepositoryClient
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides the azphalt registry client, pointed at the flagship storefront ([DEFAULT_REGISTRY_URL],
 * azphalt.store) by default. The client is transport-injected with [HttpTransport], so production talks
 * to the live registry while unit tests construct their own client with a fake transport.
 */
@Module
@InstallIn(SingletonComponent::class)
object RegistryModule {

    @Provides
    @Singleton
    fun provideRepositoryClient(): RepositoryClient =
        RepositoryClient(
            baseUrl = DEFAULT_REGISTRY_URL,
            httpGet = HttpTransport::get,
            httpPost = HttpTransport::post,
        )
}
