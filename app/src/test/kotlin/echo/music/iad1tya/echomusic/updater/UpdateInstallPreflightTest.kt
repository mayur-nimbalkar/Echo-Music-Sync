package echo.music.iad1tya.echomusic.updater

import echo.music.iad1tya.echomusic.updater.UpdateInstallPreflight.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The certificate every release APK is signed with. */
private const val RELEASE_KEY =
  "a2502f2fd12aa1d66d6b29e292efc17344c57d3d3fa4d52d66ad8596b82b395b"

/** The key the fork's first stable release (v1.4.1.1) was signed with. */
private const val OLDER_KEY =
  "6d4d69b0e019b5038dd6a2722651eaf25eb56d03017ead1be6c77ff2eb97bb8d"

class UpdateInstallPreflightTest {

  private fun check(
    apkPackageName: String? = "echo.music.iad1tya",
    apkVersionCode: Long? = 182L,
    apkSignerMatches: Boolean = true,
    apkAbis: List<String> = listOf("arm64-v8a"),
    deviceAbis: List<String> = listOf("arm64-v8a"),
    installedPackageName: String = "echo.music.iad1tya",
    installedVersionCode: Long = 181L,
  ) =
    UpdateInstallPreflight.problem(
      apkPackageName = apkPackageName,
      apkVersionCode = apkVersionCode,
      apkSignerMatches = apkSignerMatches,
      apkAbis = apkAbis,
      deviceAbis = deviceAbis,
      installedPackageName = installedPackageName,
      installedVersionCode = installedVersionCode,
    )

  @Test
  fun `a matching newer apk is installable`() {
    assertNull(check())
    // Same version code (a re-install) is fine; only a downgrade is refused.
    assertNull(check(apkVersionCode = 181L))
  }

  @Test
  fun `a differently signed apk is reported as the key mismatch it is`() {
    assertEquals(Problem.DIFFERENT_SIGNING_KEY, check(apkSignerMatches = false))
  }

  @Test
  fun `the debug build is reported as a different application`() {
    assertEquals(Problem.DIFFERENT_APPLICATION, check(apkPackageName = "echo.music.iad1tya.debug"))
  }

  @Test
  fun `an older apk is reported as a downgrade`() {
    assertEquals(Problem.OLDER_VERSION, check(apkVersionCode = 180L, installedVersionCode = 181L))
  }

  @Test
  fun `a truncated download is reported as incomplete`() {
    assertEquals(Problem.INCOMPLETE_DOWNLOAD, check(apkPackageName = null))
    assertEquals(Problem.INCOMPLETE_DOWNLOAD, check(apkVersionCode = null))
    assertEquals(Problem.INCOMPLETE_DOWNLOAD, check(apkPackageName = ""))
  }

  @Test
  fun `an apk without code for this cpu is reported as incompatible`() {
    assertEquals(
      Problem.INCOMPATIBLE_ABI,
      check(apkAbis = listOf("arm64-v8a"), deviceAbis = listOf("armeabi-v7a", "armeabi")),
    )
    // A universal APK offers several ABIs: one supported ABI is enough.
    assertNull(check(apkAbis = listOf("arm64-v8a", "armeabi-v7a"), deviceAbis = listOf("armeabi-v7a")))
    // No native code at all is installable anywhere.
    assertNull(check(apkAbis = emptyList(), deviceAbis = listOf("armeabi-v7a")))
    // ABI names are matched case-insensitively.
    assertNull(check(apkAbis = listOf("ARM64-V8A"), deviceAbis = listOf("arm64-v8a")))
  }

  @Test
  fun `an install signed with the release key can be replaced in place`() {
    assertTrue(UpdateInstallPreflight.releaseCanReplaceInstall(setOf(RELEASE_KEY), RELEASE_KEY))
  }

  @Test
  fun `an install signed with an older key can never be replaced in place`() {
    // v1.4.1.1 shipped a stable build signed with a different key than every release since, so
    // that install reports "App not installed" for every later APK until it is uninstalled.
    assertFalse(UpdateInstallPreflight.releaseCanReplaceInstall(setOf(OLDER_KEY), RELEASE_KEY))
  }

  @Test
  fun `an unknown or blank certificate is never treated as compatible`() {
    assertFalse(UpdateInstallPreflight.releaseCanReplaceInstall(emptySet(), RELEASE_KEY))
    assertFalse(UpdateInstallPreflight.releaseCanReplaceInstall(setOf(RELEASE_KEY), ""))
    // Certificate digests are hex, so case must not decide the answer.
    assertTrue(UpdateInstallPreflight.releaseCanReplaceInstall(setOf(RELEASE_KEY.uppercase()), RELEASE_KEY))
  }

  @Test
  fun `the order of reporting puts the most actionable problem first`() {
    // A wrong-application, wrongly-signed, older, incompatible file is first reported as a
    // different application: that is what the user has to fix.
    assertEquals(
      Problem.DIFFERENT_APPLICATION,
      check(
        apkPackageName = "com.other.app",
        apkVersionCode = 1L,
        apkSignerMatches = false,
        apkAbis = listOf("x86"),
        deviceAbis = listOf("arm64-v8a"),
      ),
    )
  }
}
