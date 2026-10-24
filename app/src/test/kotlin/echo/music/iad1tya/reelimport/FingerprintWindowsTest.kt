package echo.music.iad1tya.reelimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerprintWindowsTest {

  private val window = 800_000 // ~25 s of mono 16 kHz PCM, the size ReelMatcher uses

  @Test
  fun `a clip no longer than one window is fingerprinted once`() {
    assertEquals(listOf(0), FingerprintWindows.startsFor(window, window))
    assertEquals(listOf(0), FingerprintWindows.startsFor(window / 2, window))
  }

  @Test
  fun `a longer clip is fingerprinted at the start, middle and end`() {
    // A 50 s excerpt (two windows) is heard at 0 s, 12.5 s and 25 s.
    assertEquals(listOf(0, 400_000, 800_000), FingerprintWindows.startsFor(1_600_000, window))
    // A 90 s clip (the longest an import keeps) covers 0 s, 40 s and 65 s.
    assertEquals(
      listOf(0, 1_040_000, 2_080_000),
      FingerprintWindows.startsFor(2_880_000, window),
    )
  }

  @Test
  fun `every window lies inside the audio`() {
    listOf(1, window - 1, window, window + 1, 1_600_000, 2_880_000).forEach { size ->
      val starts = FingerprintWindows.startsFor(size, window)
      assertTrue("no window for $size bytes", starts.isNotEmpty())
      starts.forEach { start ->
        assertTrue("start $start is negative for $size bytes", start >= 0)
        assertTrue(
          "window at $start overruns $size bytes",
          start + window <= maxOf(size, window),
        )
      }
    }
  }

  @Test
  fun `an empty excerpt has nothing to fingerprint`() {
    assertTrue(FingerprintWindows.startsFor(0, window).isEmpty())
    assertTrue(FingerprintWindows.startsFor(-5, window).isEmpty())
  }
}
