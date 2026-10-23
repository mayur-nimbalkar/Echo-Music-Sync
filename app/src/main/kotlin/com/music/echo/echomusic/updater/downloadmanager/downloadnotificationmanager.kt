package echo.music.iad1tya.echomusic.updater.downloadmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.graphics.toColorInt
import echo.music.iad1tya.R
import echo.music.iad1tya.echomusic.updater.RELEASES_PAGE_URL
import echo.music.iad1tya.echomusic.updater.installProblemMessage
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
   * What the finished-download notification says, and where tapping it goes.
   *
   * Tapping used to hand the APK straight to the installer, so a download that cannot replace
   * this app (different signing key, other build type, truncated file) answered with Android's
   * bare "App not installed". Running the same preflight the in-app installer runs lets the
   * notification name the reason and point at the releases page instead.
   */
  private fun installPrompt(version: String, filePath: String): Pair<String, PendingIntent> {
    val problem = inspectDownloadedApk(appContext, java.io.File(filePath))
    if (problem != null) {
      return installProblemMessage(appContext, problem) to
        PendingIntent.getActivity(
          appContext,
          1,
          Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES_PAGE_URL)),
          // FLAG_IMMUTABLE alone, no UPDATE_CURRENT: both intents built here are constant
          // (one URL, one downloaded-file path), so there is nothing to refresh.
          PendingIntent.FLAG_IMMUTABLE
        )
    }
    val installIntent =
      Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(
          androidx.core.content.FileProvider.getUriForFile(
            appContext,
            "${appContext.packageName}.FileProvider",
            java.io.File(filePath)
          ),
          "application/vnd.android.package-archive"
        )
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
    return appContext.getString(R.string.tap_to_install_version, version) to
      PendingIntent.getActivity(
        appContext,
        0,
        installIntent,
        PendingIntent.FLAG_IMMUTABLE
      )
  }

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
    val (promptText, pendingIntent) = installPrompt(version, filePath)

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
        // The tap must reach whichever installer or browser the device provides, so the intent
        // stays implicit by design while the PendingIntent is FLAG_IMMUTABLE.
        .setContentIntent(pendingIntent) // lgtm[java/android-implicit-pendingintents]
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
    val (promptText, pendingIntent) = installPrompt(version, filePath)

    val notification =
      NotificationCompat.Builder(appContext, CHANNEL_ID)
        .setSmallIcon(R.drawable.updated)
        .setContentTitle(appContext.getString(R.string.update_ready))
        .setContentText(promptText)
        .setStyle(NotificationCompat.BigTextStyle().bigText(promptText))
        .setProgress(0, 0, false)
        .setAutoCancel(true)
        // Same deliberate implicit intent as the modern notification above; see the note there.
        .setContentIntent(pendingIntent) // lgtm[java/android-implicit-pendingintents]
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .build()

    notificationManager.notify(NOTIFICATION_ID, notification)
  }
}
