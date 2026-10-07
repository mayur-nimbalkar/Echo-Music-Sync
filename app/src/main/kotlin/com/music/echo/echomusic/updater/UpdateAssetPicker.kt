package echo.music.iad1tya.echomusic.updater

/**
 * Chooses which release APK a device should install.
 *
 * Android only upgrades an app in place when the new APK has the **same application id and the
 * same signing key**. The release workflow publishes two build types — `stable` for
 * `echo.music.iad1tya` and `debug` for `echo.music.iad1tya.debug` — so an install can only ever
 * be upgraded by the asset of its own build type. Handing a stable asset to a debug install
 * looks like it worked but installs a second, separate app instead of upgrading.
 *
 * Kept free of Android dependencies so the selection rules can be unit tested on the JVM.
 */
object UpdateAssetPicker {

  /** Every release asset starts with this name. */
  const val ASSET_PREFIX = "Echo-Music"

  /** ABI label used in asset names. */
  fun abiLabel(wantsArm64: Boolean): String = if (wantsArm64) "arm64" else "universal"

  /** Build-type label used in asset names. */
  fun buildTypeLabel(wantsDebug: Boolean): String = if (wantsDebug) "debug" else "stable"

  /** Asset name for [version], e.g. `Echo-Music-v1.4.1.4-arm64-stable.apk`. */
  fun assetName(version: String, wantsArm64: Boolean, wantsDebug: Boolean): String =
    "$ASSET_PREFIX-$version-${abiLabel(wantsArm64)}-${buildTypeLabel(wantsDebug)}.apk"

  /** True when [name] is an APK of the requested build type. */
  fun isBuildType(name: String, wantsDebug: Boolean): Boolean {
    if (!name.endsWith(".apk", ignoreCase = true)) return false
    return name.lowercase().contains("debug") == wantsDebug
  }

  /**
   * The asset name best suited to this device, or null when the release offers none its build
   * type can install. The asset labelled for this device's ABI wins; otherwise any asset that
   * is not arm64-only is accepted, since an arm64 device can also install the universal build
   * and a 32-bit device can never install an arm64-only one.
   */
  fun bestApkName(names: List<String>, wantsArm64: Boolean, wantsDebug: Boolean): String? {
    val candidates = names.filter { isBuildType(it, wantsDebug) }
    if (candidates.isEmpty()) return null
    candidates.firstOrNull { it.lowercase().contains(abiLabel(wantsArm64)) }?.let { return it }
    val arm64Only = abiLabel(true)
    return candidates.firstOrNull { !it.lowercase().contains(arm64Only) }
  }
}
