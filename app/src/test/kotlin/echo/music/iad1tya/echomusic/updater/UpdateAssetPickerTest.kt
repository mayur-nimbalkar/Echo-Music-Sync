package echo.music.iad1tya.echomusic.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UpdateAssetPickerTest {

  private val releaseAssets =
    listOf(
      "Echo-Music-v1.4.1.4-arm64-stable.apk",
      "Echo-Music-v1.4.1.4-universal-stable.apk",
      "Echo-Music-v1.4.1.4-arm64-debug.apk",
      "Echo-Music-v1.4.1.4-universal-debug.apk",
    )

  @Test
  fun `a stable install upgrades with the stable asset for its abi`() {
    assertEquals(
      "Echo-Music-v1.4.1.4-arm64-stable.apk",
      UpdateAssetPicker.bestApkName(releaseAssets, wantsArm64 = true, wantsDebug = false),
    )
    assertEquals(
      "Echo-Music-v1.4.1.4-universal-stable.apk",
      UpdateAssetPicker.bestApkName(releaseAssets, wantsArm64 = false, wantsDebug = false),
    )
  }

  @Test
  fun `a debug install upgrades with the debug asset, never the stable one`() {
    // The debug build is a different application (`.debug`): the stable APK would install a
    // second app instead of upgrading this one.
    assertEquals(
      "Echo-Music-v1.4.1.4-arm64-debug.apk",
      UpdateAssetPicker.bestApkName(releaseAssets, wantsArm64 = true, wantsDebug = true),
    )
    assertEquals(
      "Echo-Music-v1.4.1.4-universal-debug.apk",
      UpdateAssetPicker.bestApkName(releaseAssets, wantsArm64 = false, wantsDebug = true),
    )
  }

  @Test
  fun `an arm64 device falls back to the universal asset`() {
    val onlyUniversal = listOf("Echo-Music-v1.4.1.4-universal-stable.apk")
    assertEquals(
      "Echo-Music-v1.4.1.4-universal-stable.apk",
      UpdateAssetPicker.bestApkName(onlyUniversal, wantsArm64 = true, wantsDebug = false),
    )
  }

  @Test
  fun `a 32-bit device is never handed an arm64-only apk`() {
    val arm64Only = listOf("Echo-Music-v1.4.1.4-arm64-stable.apk")
    assertNull(UpdateAssetPicker.bestApkName(arm64Only, wantsArm64 = false, wantsDebug = false))
  }

  @Test
  fun `a release without the installed build type offers no update`() {
    val debugOnly = listOf("Echo-Music-v1.4.1.4-arm64-debug.apk")
    assertNull(UpdateAssetPicker.bestApkName(debugOnly, wantsArm64 = true, wantsDebug = false))
    assertNull(UpdateAssetPicker.bestApkName(emptyList(), wantsArm64 = true, wantsDebug = false))
  }

  @Test
  fun `non-apk and unlabelled assets are ignored or accepted sensibly`() {
    assertNull(UpdateAssetPicker.bestApkName(listOf("changelog.json"), wantsArm64 = true, wantsDebug = false))
    val mixed = listOf("changelog.json", "Echo-Music-v1.4.1.4-stable.apk")
    assertEquals(
      "Echo-Music-v1.4.1.4-stable.apk",
      UpdateAssetPicker.bestApkName(mixed, wantsArm64 = true, wantsDebug = false),
    )
  }

  @Test
  fun `asset names follow the release naming scheme`() {
    assertEquals(
      "Echo-Music-v1.4.1.4-arm64-stable.apk",
      UpdateAssetPicker.assetName("v1.4.1.4", wantsArm64 = true, wantsDebug = false),
    )
    assertEquals(
      "Echo-Music-v1.4.1.4-universal-debug.apk",
      UpdateAssetPicker.assetName("v1.4.1.4", wantsArm64 = false, wantsDebug = true),
    )
  }
}
