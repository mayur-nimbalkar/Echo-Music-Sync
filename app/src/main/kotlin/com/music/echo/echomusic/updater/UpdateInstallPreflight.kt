package echo.music.iad1tya.echomusic.updater

/**
 * Decides whether a downloaded update APK can actually replace the running app.
 *
 * When the package manager refuses an install it shows a bare **"App not installed"** and no
 * reason — a differently-signed APK, a truncated download, an older build and an unsupported
 * CPU all look identical. Checking the same conditions up front lets the app say what is
 * actually wrong.
 *
 * Kept free of Android dependencies so the rules can be unit tested on the JVM.
 */
object UpdateInstallPreflight {

  /** Why a downloaded APK cannot replace the installed app, most important first. */
  enum class Problem {
    /** Not a complete APK: truncated download, or the wrong file. */
    INCOMPLETE_DOWNLOAD,

    /** A different application (e.g. the `.debug` build), so it would not update this one. */
    DIFFERENT_APPLICATION,

    /**
     * Same application, different signing key. Android refuses this outright, and it is the
     * usual cause of "App not installed" — the previous install came from a build signed with
     * another key (an older fork build, or upstream Echo Music).
     */
    DIFFERENT_SIGNING_KEY,

    /** Older than the installed app: Android refuses to downgrade. */
    OLDER_VERSION,

    /** No native code for any CPU this device supports (e.g. an arm64 APK on a 32-bit phone). */
    INCOMPATIBLE_ABI,
  }

  /**
   * True when a release APK can replace this install in place.
   *
   * Android only replaces an app with an APK signed by the key that app was installed with, and
   * there is no override for that. An install carrying an older key therefore cannot be
   * downloaded into place at all: the only way forward is to uninstall it once, so the updater
   * checks this *before* a large download instead of after it.
   */
  fun releaseCanReplaceInstall(
    installedCertDigests: Set<String>,
    releaseCertDigest: String,
  ): Boolean =
    releaseCertDigest.isNotBlank() &&
      installedCertDigests.any { digest -> digest.equals(releaseCertDigest, ignoreCase = true) }

  /**
   * The problem that will make the installer fail, or null when the APK can be installed.
   * [apkSignerMatches] means the download's signing certificate equals the installed app's.
   */
  fun problem(
    apkPackageName: String?,
    apkVersionCode: Long?,
    apkSignerMatches: Boolean,
    apkAbis: List<String>,
    deviceAbis: List<String>,
    installedPackageName: String,
    installedVersionCode: Long,
  ): Problem? {
    if (apkPackageName.isNullOrBlank() || apkVersionCode == null) return Problem.INCOMPLETE_DOWNLOAD
    if (apkPackageName != installedPackageName) return Problem.DIFFERENT_APPLICATION
    if (!apkSignerMatches) return Problem.DIFFERENT_SIGNING_KEY
    if (apkVersionCode < installedVersionCode) return Problem.OLDER_VERSION
    // A pure-Java APK has no native libraries at all and installs anywhere.
    if (apkAbis.isNotEmpty() && !deviceAbis.any { device -> apkAbis.any { it.equals(device, true) } }) {
      return Problem.INCOMPATIBLE_ABI
    }
    return null
  }
}
