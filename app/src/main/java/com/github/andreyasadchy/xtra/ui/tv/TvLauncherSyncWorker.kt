package com.github.andreyasadchy.xtra.ui.tv

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.github.andreyasadchy.xtra.ui.view.TvUi
import java.util.concurrent.TimeUnit

/**
 * Periodic WorkManager job (every 15 min when network is connected) that keeps the Android TV / Google TV
 * "Followed Live" launcher channel and "Watch Next" row up-to-date even while Xtra TV is closed.
 */
class TvLauncherSyncWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val ok = runCatching {
            TvLauncherChannels.syncFollowedLiveBackground(applicationContext)
        }.getOrDefault(false)
        return if (ok || runAttemptCount >= 2) Result.success() else Result.retry()
    }

    companion object {
        private const val WORK_NAME = "tv_launcher_followed_sync"

        fun schedule(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !TvUi.isTv(context)) return
            val appContext = context.applicationContext
            val request = PeriodicWorkRequestBuilder<TvLauncherSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }
    }
}
