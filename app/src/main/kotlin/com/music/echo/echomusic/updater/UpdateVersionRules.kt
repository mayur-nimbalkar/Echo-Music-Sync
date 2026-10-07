package echo.music.iad1tya.echomusic.updater

/**
 * Version rules for the update check.
 *
 * Kept free of Android dependencies so they can be unit tested on the JVM.
 */
object UpdateVersionRules {

  /**
   * True only when [latestVersion] is strictly newer than [currentVersion].
   *
   * Only the leading numeric components are compared ("v1.4.1.3" → 1.4.1.3), so a build that is
   * ahead of the newest release at ANY level — major, minor, patch, or the fork's own fourth
   * component — never reports an update: "1.4.1.3" is not older than "1.4.1", and "1.4.2" is not
   * older than "1.4.1.9". Trailing text on a tag ("1.4.2-rc1") is ignored, and a tag carrying no
   * version number at all ("nightly-r44") counts as "not newer" instead of being compared as a
   * jumble of digits.
   */
  fun isNewerVersion(latestVersion: String, currentVersion: String): Boolean {
    val latestParts = versionParts(latestVersion) ?: return false
    val currentParts = versionParts(currentVersion) ?: return false

    for (i in 0 until maxOf(latestParts.size, currentParts.size)) {
      val latest = latestParts.getOrElse(i) { 0 }
      val current = currentParts.getOrElse(i) { 0 }
      if (latest != current) return latest > current
    }

    // Same numbers: a beta build ("b1.4.2") is still offered the final "1.4.2".
    return isBetaTag(currentVersion) && !isBetaTag(latestVersion)
  }

  /** A tag whose version is a pre-release ("b1.4.2"). */
  private fun isBetaTag(version: String): Boolean = version.trim().startsWith("b")

  /**
   * Leading numeric components of a version, or null when it has none: "v1.4.1.3" → [1, 4, 1, 3],
   * "b1.4.2" → [1, 4, 2], "1.4.2-rc1" → [1, 4, 2], "nightly-r44" → null.
   */
  private fun versionParts(version: String): List<Int>? {
    val match = Regex("^[vVbB]?(\\d+(?:[._]\\d+)*)").find(version.trim()) ?: return null
    return match.groupValues[1].split('.', '_').mapNotNull { it.toIntOrNull() }.ifEmpty { null }
  }
}
