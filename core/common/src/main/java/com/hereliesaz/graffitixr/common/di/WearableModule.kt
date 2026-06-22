package com.hereliesaz.graffitixr.common.di

import com.hereliesaz.graffitixr.common.wearable.SmartGlassProvider
import com.hereliesaz.graffitixr.common.wearable.XrealGlassProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

@Module
@InstallIn(SingletonComponent::class)
abstract class WearableModule {

    // Meta provider removed with the mwdat dependency; Xreal remains.
    @Binds
    @IntoSet
    abstract fun bindXrealProvider(provider: XrealGlassProvider): SmartGlassProvider
}
