package com.hereliesaz.graffitixr.common.di

import com.hereliesaz.graffitixr.common.wearable.MetaGlassProvider
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

    @Binds
    @IntoSet
    abstract fun bindMetaProvider(provider: MetaGlassProvider): SmartGlassProvider

    @Binds
    @IntoSet
    abstract fun bindXrealProvider(provider: XrealGlassProvider): SmartGlassProvider
}
