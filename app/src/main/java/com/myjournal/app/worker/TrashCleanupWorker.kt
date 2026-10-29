package com.myjournal.app.worker
import android.content.Context
import androidx.room.Room
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.myjournal.app.data.local.AppDatabase
import java.util.concurrent.TimeUnit
class TrashCleanupWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return try {
            val db = Room.databaseBuilder(
                applicationContext,
                AppDatabase::class.java,
                "my_journal.db"
            ).build()
            val thirtyDaysAgo = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
            db.journalDao().purgeOldTrash(thirtyDaysAgo)
            Result.success()
        } catch (e: Exception) {
            e.printStackTrace()
            Result.retry()
        }
    }
}