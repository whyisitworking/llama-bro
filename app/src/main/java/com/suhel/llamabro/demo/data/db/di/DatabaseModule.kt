package com.suhel.llamabro.demo.data.db.di

import android.app.Application
import androidx.room.Room
import com.suhel.llamabro.demo.data.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(app: Application): AppDatabase =
        Room.databaseBuilder<AppDatabase>(app, "llama-bro-db")
            .build()
}
