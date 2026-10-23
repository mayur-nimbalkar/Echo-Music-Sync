package echo.music.iad1tya.echomusic.updater

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import echo.music.iad1tya.echomusic.UpdateNotificationHelper
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import timber.log.Timber

/**
 * Daily background update check.
 *
 * The app also checks for updates on launch ([checkForUpdate] from `MainActivity`), but a session
 * that stays open for days — music apps typically do — never sees that launch check again. This
 * worker runs the same check every 24h while the device is online and not low on battery, and
 * surfaces a release through the existing update notification instead of a dialog, so it never
 * interrupts playback.
 */
class UpdateCheckWorker(context: Context, workerParameters: WorkerParameters) :
  CoroutineWorker(context, workerParameters) {

  override suspend fun doWork(): Result {
    val context = applicationContext
    // Read at run time, not at scheduling time: turning automatic checks off then takes effect
    // on the next run without having to cancel and re-create the periodic work.
    if (!getAutoUpdateCheckSetting(context)) return Result.success()

    val result = CompletableDeferred<UpdateCheckResult?>()
    checkForUpdate(
      context = context,
      onSuccess = { result.complete(it) },
      onError = { result.complete(null) },
    )
    // A failed check (offline, GitHub unreachable) is not an answer — let WorkManager retry.
    val update = result.await() ?: return Result.retry()

    Timber.d("UpdateCheckWorker: latest=%s available=%s", update.version, update.isAvailable)
    saveLastCheckedTime(
      context,
      LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy, h:mm a"))
    )
    saveUpdateAvailableState(context, update.isAvailable)
    if (update.isAvailable && getUpdateNotificationsSetting(context)) {
      UpdateNotificationHelper.showUpdateNotification(context, update.version)
    }
    return Result.success()
  }

  companion object {
    /** Unique name so the work is refreshed in place instead of piling up. */
    const val UNIQUE_WORK_NAME = "periodic_update_check"

    private const val INTERVAL_HOURS = 24L

    /** Enqueues the daily check; safe to call on every app start (KEEP reuses the existing work). */
    fun schedule(context: Context) {
      val request =
        PeriodicWorkRequestBuilder<UpdateCheckWorker>(INTERVAL_HOURS, TimeUnit.HOURS)
          .setConstraints(
            Constraints.Builder()
              .setRequiredNetworkType(NetworkType.CONNECTED)
              .setRequiresBatteryNotLow(true)
              .build()
          )
          .build()
      WorkManager.getInstance(context)
        .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
  }
}
