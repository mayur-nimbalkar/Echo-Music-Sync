package echo.music.iad1tya.echomusic.updater.downloadmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.graphics.toColorInt
import echo.music.iad1tya.R
import echo.music.iad1tya.echomusic.updater.inspectDownloadedApk

object DownloadNotificationManager {
  private lateinit var notificationManager: NotificationManager
  private lateinit var appContext: Context

  const val CHANNEL_ID = "download_progress_channel"
  private const val CHANNEL_NAME = "Download Progress"
  const val NOTIFICATION_ID = 5678

  fun initialize(context: Context) {
    appContext = context
    notificationManager =
      context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.download_progress_channel),
            NotificationManager.IMPORTANCE_HIGH
          )
          .apply {
            description = context.getString(R.string.download_progress_description)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            enableVibration(false)
            enableLights(false)
          }
      notificationManager.createNotificationChannel(channel)
    }
  }

  fun getDownloadStartingNotification(version: String, fileSize: String): Notification {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
      buildDownloadStartingModern(version, fileSize)
    } else {
      buildDownloadStartingLegacy(version, fileSize)
    }
  }

  fun getDownloadProgressNotification(progress: Int, version: String): Notification {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
      buildDownloadProgressModern(progress, version)
    } else {
      buildDownloadProgressLegacy(progress, version)
    }
  }

  fun showDownloadComplete(version: String, filePath: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
      showDownloadCompleteModern(version, filePath)
    } else {
      showDownloadCompleteLegacy(version, filePath)
    }
  }

  fun showDownloadFailed(version: String, errorMessage: String) {
    val notification =
      NotificationCompat.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle(appContext.getString(R.string.update_failed))
        .setContentText(appContext.getString(R.string.failed_to_download_version, version))
        .setStyle(
          NotificationCompat.BigTextStyle()
            .bigText(
              appContext.getString(R.string.failed_to_download_version_error, version, errorMessage)
            )
        )
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setAutoCancel(true)
        .build()

    notificationManager.notify(NOTIFICATION_ID, notification)
  }

  fun cancelNotification() {
    notificationManager.cancel(NOTIFICATION_ID)
  }

  /**
   * What the finished-download notification says when the APK cannot replace this app.
   *
   * The tap itself is always the same explicit intent into the app, so the preflight runs
   * there and names the reason; the notification only hints that installing may be blocked.
   */
  private fun installPromptText(version: String, filePath: String): String {
    val problem = inspectDownloadedApk(appContext, java.io.File(filePath))
    return if (problem != null) {
      appContext.getString(R.string.update_notification_blocked)
    } else {
      appContext.getString(R.string.tap_to_install_version, version)
    }
  }

  /**
   * Tap target for the finished-download notification.
   *
   * It opens the update screen instead of handing an implicit intent to whatever app claims
   * "package-archive" on this device. The app runs the install preflight itself, so a
   * mismatched signature is explained rather than answered by Android with a bare
   * "App not installed".
   */
  private fun updateTapPendingIntent(): PendingIntent =
    PendingIntent.getActivity(
      appContext,
      0,
      Intent(appContext, echo.music.iad1tya.MainActivity::class.java).apply {
        action = echo.music.iad1tya.MainActivity.ACTION_OPEN_UPDATES
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_SINGLE_TOP or
            Intent.FLAG_ACTIVITY_CLEAR_TOP
        )
      },
      // The intent is constant (same activity, same action), so there is nothing to refresh.
      PendingIntent.FLAG_IMMUTABLE
    )

  @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  private fun buildDownloadStartingModern(version: String, fileSize: String): Notification {
    val progressStyle =
      Notification.ProgressStyle()
        .also {
          for (i in 0 until 4) {
            it.addProgressSegment(
              Notification.ProgressStyle.Segment(25)
                .setColor(
                  if (i % 2 == 0) {
                    "#4285F4".toColorInt()
                  } else {
                    "#8E24AA".toColorInt()
                  }
                )
            )
          }
        }
        .setProgress(0)

    val builder =
      Notification.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(appContext.getString(R.string.downloading_update))
        .setContentText(appContext.getString(R.string.version_file_size, version, fileSize))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setStyle(progressStyle)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_PROGRESS)
        .setWhen(System.currentTimeMillis())

    setRequestPromotedOngoingSafely(builder, true)
    setShortCriticalTextSafely(builder, appContext.getString(R.string.starting))

    return builder.build()
  }

  @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  private fun buildDownloadProgressModern(progress: Int, version: String): Notification {
    val progressStyle =
      Notification.ProgressStyle()
        .also {
          for (i in 0 until 4) {
            it.addProgressSegment(
              Notification.ProgressStyle.Segment(25)
                .setColor(
                  if (i % 2 == 0) {
                    "#4285F4".toColorInt()
                  } else {
                    "#8E24AA".toColorInt()
                  }
                )
            )
          }
        }
        .setProgress(progress)

    val builder =
      Notification.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentTitle(appContext.getString(R.string.downloading_update))
        .setContentText(appContext.getString(R.string.version_progress, version, progress))
        .setOngoing(progress < 100)
        .setOnlyAlertOnce(true)
        .setStyle(progressStyle)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_PROGRESS)
        .setWhen(System.currentTimeMillis())

    setRequestPromotedOngoingSafely(builder, progress < 100)
    setShortCriticalTextSafely(builder, "$progress%")

    return builder.build()
  }

  @RequiresApi(Build.VERSION_CODES.BAKLAVA)
  private fun showDownloadCompleteModern(version: String, filePath: String) {
    val promptText = installPromptText(version, filePath)
    val pendingIntent = updateTapPendingIntent()

    val progressStyle =
      Notification.ProgressStyle()
        .also {
          for (i in 0 until 4) {
            it.addProgressSegment(
              Notification.ProgressStyle.Segment(25)
                .setColor(
                  if (i % 2 == 0) {
                    "#4285F4".toColorInt()
                  } else {
                    "#8E24AA".toColorInt()
                  }
                )
            )
          }
        }
        .setProgress(100)

    val builder =
      Notification.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.updated)
        .setContentTitle(appContext.getString(R.string.update_ready))
        .setContentText(promptText)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .setStyle(progressStyle)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_STATUS)
        .setWhen(System.currentTimeMillis())

    setRequestPromotedOngoingSafely(builder, false)
    setShortCriticalTextSafely(builder, appContext.getString(R.string.done))

    notificationManager.notify(NOTIFICATION_ID, builder.build())
  }

  private fun setShortCriticalTextSafely(builder: Notification.Builder, text: String) {
    try {
      val method =
        Notification.Builder::class.java.getMethod("setShortCriticalText", CharSequence::class.java)
      method.invoke(builder, text)
    } catch (e: Exception) {
      builder.getExtras().putCharSequence("android.shortCriticalText", text)
    }
  }

  private fun setRequestPromotedOngoingSafely(builder: Notification.Builder, promoted: Boolean) {

    builder.getExtras().putBoolean("android.requestPromotedOngoing", promoted)

    try {

      val methodNames =
        arrayOf("setRequestPromotedOngoing", "setPromotedOngoing", "setOngoingActivity")
      for (name in methodNames) {
        try {
          val method =
            Notification.Builder::class.java.getMethod(name, Boolean::class.javaPrimitiveType)
          method.invoke(builder, promoted)
          break
        } catch (e: Exception) {}
      }
    } catch (e: Exception) {}
  }

  private fun buildDownloadStartingLegacy(version: String, fileSize: String): Notification {
    return NotificationCompat.Builder(appContext, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher_foreground)
      .setContentTitle(appContext.getString(R.string.downloading_update))
      .setContentText(appContext.getString(R.string.version_file_size, version, fileSize))
      .setProgress(100, 0, false)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .build()
  }

  private fun buildDownloadProgressLegacy(progress: Int, version: String): Notification {
    return NotificationCompat.Builder(appContext, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_launcher)
      .setContentTitle(appContext.getString(R.string.downloading_update))
      .setContentText(appContext.getString(R.string.version_progress, version, progress))
      .setProgress(100, progress, false)
      .setOngoing(progress < 100)
      .setOnlyAlertOnce(true)
      .setPriority(NotificationCompat.PRIORITY_HIGH)
      .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
      .setCategory(NotificationCompat.CATEGORY_PROGRESS)
      .build()
  }

  private fun showDownloadCompleteLegacy(version: String, filePath: String) {
    val promptText = installPromptText(version, filePath)
    val pendingIntent = updateTapPendingIntent()

    val notification =
      NotificationCompat.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.updated)
        .setContentTitle(appContext.getString(R.string.update_ready))
        .setContentText(promptText)
        .setStyle(NotificationCompat.BigTextStyle().bigText(promptText))
        .setProgress(0, 0, false)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .build()

    notificationManager.notify(NOTIFICATION_ID, notification)
  }
}
