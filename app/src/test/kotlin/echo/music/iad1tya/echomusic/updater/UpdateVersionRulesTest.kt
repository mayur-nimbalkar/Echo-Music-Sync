package echo.music.iad1tya.echomusic.updater

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionRulesTest {

  @Test
  fun `a build ahead of the release is never offered an update`() {
    // This fork is ahead of upstream (1.4.1.3 / 1.4.1.4 vs 1.4.1) — that must never nag.
    assertFalse(UpdateVersionRules.isNewerVersion("1.4.1", "1.4.1.3"))
    assertFalse(UpdateVersionRules.isNewerVersion("v1.4.1", "1.4.1.4"))
    // Ahead at patch level, at minor level, at major level.
    assertFalse(UpdateVersionRules.isNewerVersion("1.4.1.3", "1.4.1.4"))
    assertFalse(UpdateVersionRules.isNewerVersion("1.4.2", "1.5.0"))
    assertFalse(UpdateVersionRules.isNewerVersion("2.0", "10.0"))
  }

  @Test
  fun `an equal version is not an update`() {
    assertFalse(UpdateVersionRules.isNewerVersion("v1.4.1.4", "1.4.1.4"))
    assertFalse(UpdateVersionRules.isNewerVersion("1.4.1", "1.4.1"))
    assertFalse(UpdateVersionRules.isNewerVersion("1.4", "1.4.0"))
  }

  @Test
  fun `a genuinely newer release is still offered`() {
    assertTrue(UpdateVersionRules.isNewerVersion("v1.4.1.5", "1.4.1.4"))
    // Numerically newer, not "newer as a string".
    assertTrue(UpdateVersionRules.isNewerVersion("1.4.1.10", "1.4.1.9"))
    assertTrue(UpdateVersionRules.isNewerVersion("1.4.2", "1.4.1.9"))
    assertTrue(UpdateVersionRules.isNewerVersion("1.5", "1.4.1.3"))
    assertTrue(UpdateVersionRules.isNewerVersion("2", "1.9.9"))
  }

  @Test
  fun `zero-padded components compare numerically`() {
    assertTrue(UpdateVersionRules.isNewerVersion("1.4.01", "1.4.0"))
    assertFalse(UpdateVersionRules.isNewerVersion("1.4.0", "1.4.00"))
  }

  @Test
  fun `tags with trailing text are compared on their numbers`() {
    assertTrue(UpdateVersionRules.isNewerVersion("1.4.2-rc1", "1.4.1.3"))
    assertFalse(UpdateVersionRules.isNewerVersion("1.4.1.4-hotfix", "1.4.1.4"))
  }

  @Test
  fun `tags without a version number never report an update`() {
    assertFalse(UpdateVersionRules.isNewerVersion("nightly-r44", "1.4.1.3"))
    assertFalse(UpdateVersionRules.isNewerVersion("latest", "1.4.1.3"))
    assertFalse(UpdateVersionRules.isNewerVersion("", "1.4.1.3"))
  }

  @Test
  fun `a beta build is still offered the final release`() {
    assertTrue(UpdateVersionRules.isNewerVersion("1.4.2", "b1.4.2"))
    // A beta with a higher number is newer than a stable one.
    assertTrue(UpdateVersionRules.isNewerVersion("b1.4.3", "1.4.2"))
    assertFalse(UpdateVersionRules.isNewerVersion("b1.4.2", "1.4.3"))
  }
}
