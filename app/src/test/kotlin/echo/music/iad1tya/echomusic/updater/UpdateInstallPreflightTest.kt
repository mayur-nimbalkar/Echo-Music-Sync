package echo.music.iad1tya.echomusic.updater

import echo.music.iad1tya.echomusic.updater.UpdateInstallPreflight.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
