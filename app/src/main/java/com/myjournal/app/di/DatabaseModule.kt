package com.myjournal.app.di
import android.content.Context
import androidx.room.Room
import com.myjournal.app.data.local.AppDatabase
import com.myjournal.app.data.local.JournalDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "my_journal.db"
        ).fallbackToDestructiveMigration().build()
    }
    @Provides
    fun provideJournalDao(db: AppDatabase): JournalDao = db.journalDao()
}