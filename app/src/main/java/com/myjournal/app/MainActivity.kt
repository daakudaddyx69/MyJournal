package com.myjournal.app
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.myjournal.app.ui.MyJournalApp
import com.myjournal.app.ui.theme.MyJournalTheme
import com.myjournal.app.worker.TrashCleanupWorker
import dagger.hilt.android.AndroidEntryPoint
import java.util.concurrent.TimeUnit
@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Set window background to dark to eliminate white startup flash
        window.decorView.setBackgroundColor(0xFF1C1917.toInt())
        val cleanupRequest = PeriodicWorkRequestBuilder<TrashCleanupWorker>(1, TimeUnit.DAYS).build()
        WorkManager.getInstance(applicationContext).enqueueUniquePeriodicWork(
            "trash_30d_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            cleanupRequest
        )
        setContent {
            MyJournalTheme {
                MyJournalApp()
            }
        }
    }
}