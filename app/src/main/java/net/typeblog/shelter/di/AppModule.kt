package net.typeblog.shelter.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.typeblog.shelter.data.settings.SettingsStore
import net.typeblog.shelter.profile.AuthManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsStore(@ApplicationContext ctx: Context): SettingsStore =
        SettingsStore(ctx)

    @Provides
    @Singleton
    fun provideAuthManager(@ApplicationContext ctx: Context): AuthManager =
        AuthManager(ctx)
}
