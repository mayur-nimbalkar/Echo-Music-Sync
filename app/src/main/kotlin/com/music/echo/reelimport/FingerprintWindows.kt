package echo.music.iad1tya.reelimport

/**
 * Which parts of an extracted audio excerpt get fingerprinted.
 *
 * Clips routinely open with speech, silence or an intro hook, so the song is often not in the
 * first seconds. Listening to the start alone missed it; the start, the middle and the end
 * cover the whole clip with three windows, which is the most Shazam lookups a single import is
 * worth.
 *
 * Kept free of Android dependencies so the coverage rule can be unit tested on the JVM.
 */
internal object FingerprintWindows {

  /**
   * Byte offsets to fingerprint for an excerpt of [size] bytes, each window being
   * [windowBytes] long. A clip no longer than one window is fingerprinted once; otherwise the
   * windows start at the beginning, the middle and the last position that still fits, so
   * every window lies inside the audio.
   */
  fun startsFor(size: Int, windowBytes: Int): List<Int> {
    require(windowBytes > 0) { "windowBytes must be positive" }
    if (size <= 0) return emptyList()
    val lastStart = (size - windowBytes).coerceAtLeast(0)
    if (lastStart == 0) return listOf(0)
    return listOf(0, lastStart / 2, lastStart).distinct()
  }
}
