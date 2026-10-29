package com.saurabh.offgridchat.di

import android.content.Context
import androidx.room.Room
import com.saurabh.offgridchat.database.AppDatabase
import com.saurabh.offgridchat.database.ChatDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    private const val DATABASE_NAME = "offgrid.db"

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase {

        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            DATABASE_NAME
        )
            /*
             * Version 2 -> Version 3
             *
             * This preserves existing conversations/messages/peers
             * instead of deleting the user's local database.
             */
            .addMigrations(
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4,
                AppDatabase.MIGRATION_4_5
            )
            /*
             * Intentionally DO NOT use:
             *
             * fallbackToDestructiveMigration(...)
             *
             * because chat history is user data and must not be
             * silently deleted when a migration is missing.
             */
            .build()
    }

    @Provides
    @Singleton
    fun provideChatDao(
        database: AppDatabase
    ): ChatDao {
        return database.chatDao()
    }
}
