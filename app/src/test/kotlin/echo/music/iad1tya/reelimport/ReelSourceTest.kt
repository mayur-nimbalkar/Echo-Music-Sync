package echo.music.iad1tya.reelimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelSourceTest {

  @Test
  fun `instagram reels posts and short links are recognised`() {
    assertEquals(
      ReelSource.INSTAGRAM,
      ReelSource.of("https://www.instagram.com/reel/C8xYzAbCdEf/"),
    )
    assertEquals(ReelSource.INSTAGRAM, ReelSource.of("https://instagram.com/reels/C8xYzAbCdEf"))
    assertEquals(ReelSource.INSTAGRAM, ReelSource.of("https://m.instagram.com/p/C8xYzAbCdEf/"))
    // instagr.am short domain, still used by old share links.
    assertEquals(ReelSource.INSTAGRAM, ReelSource.of("https://instagr.am/p/C8xYzAbCdEf/"))
  }

  @Test
  fun `youtube shorts and youtu be links are recognised`() {
    assertEquals(
      ReelSource.YOUTUBE,
      ReelSource.of("https://www.youtube.com/shorts/dQw4w9WgXcQ"),
    )
    assertEquals(ReelSource.YOUTUBE, ReelSource.of("https://m.youtube.com/shorts/dQw4w9WgXcQ"))
    // What the YouTube app puts in the share sheet.
    assertEquals(ReelSource.YOUTUBE, ReelSource.of("https://youtu.be/dQw4w9WgXcQ?si=abc123"))
  }

  @Test
  fun `plain youtube video links are not treated as clips`() {
    // Those have their own playback handling; hijacking them into the importer would break
    // opening an ordinary link.
    assertNull(ReelSource.of("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
    assertNull(ReelSource.of("https://music.youtube.com/watch?v=dQw4w9WgXcQ"))
  }

  @Test
  fun `x and twitter status links are recognised`() {
    assertEquals(ReelSource.X, ReelSource.of("https://x.com/somebody/status/1800000000000000000"))
    assertEquals(ReelSource.X, ReelSource.of("https://twitter.com/somebody/status/1800000000000000000"))
    assertEquals(ReelSource.X, ReelSource.of("https://x.com/i/status/1800000000000000000"))
    // The share link the X web app produces.
    assertEquals(ReelSource.X, ReelSource.of("https://x.com/i/web/status/1800000000000000000"))
    assertEquals(ReelSource.X, ReelSource.of("https://mobile.twitter.com/somebody/status/1800000000000000000"))
  }

  @Test
  fun `other links and noise are rejected`() {
    assertNull(ReelSource.of(null))
    assertNull(ReelSource.of(""))
    assertNull(ReelSource.of("   "))
    assertNull(ReelSource.of("just some text"))
    assertNull(ReelSource.of("https://www.youtube.com/@somechannel"))
    assertNull(ReelSource.of("https://x.com/somebody"))
    // TikTok is deliberately unsupported.
    assertNull(ReelSource.of("https://www.tiktok.com/@somebody/video/7400000000000000000"))
  }

  @Test
  fun `instagram canonical url drops tracking parameters`() {
    assertEquals(
      "https://www.instagram.com/reel/C8xYzAbCdEf/",
      ReelSource.canonicalize("https://www.instagram.com/reel/C8xYzAbCdEf/?stkn=abc123&igsh=xyz#top"),
    )
    // A /p/ post keeps its path: rewriting it to /reel/ could 404 for carousels.
    assertEquals(
      "https://instagram.com/p/C8xYzAbCdEf/",
      ReelSource.canonicalize("https://instagram.com/p/C8xYzAbCdEf/?stkn=abc123"),
    )
  }

  @Test
  fun `youtube canonical url is stable for both link shapes`() {
    assertEquals(
      "https://www.youtube.com/shorts/dQw4w9WgXcQ",
      ReelSource.canonicalize("https://youtube.com/shorts/dQw4w9WgXcQ?feature=share"),
    )
    assertEquals(
      "https://youtu.be/dQw4w9WgXcQ",
      ReelSource.canonicalize("https://youtu.be/dQw4w9WgXcQ?si=abc123"),
    )
  }

  @Test
  fun `x canonical url uses the share-stable status form`() {
    assertEquals(
      "https://x.com/i/status/1800000000000000000",
      ReelSource.canonicalize("https://twitter.com/somebody/status/1800000000000000000?s=20"),
    )
  }

  @Test
  fun `only instagram exposes the audio metadata api`() {
    assertTrue(ReelSource.INSTAGRAM.hasAudioMetadataApi)
    assertFalse(ReelSource.YOUTUBE.hasAudioMetadataApi)
    assertFalse(ReelSource.X.hasAudioMetadataApi)
  }

  @Test
  fun `instagram shortcode extraction stays instagram-specific`() {
    assertEquals(
      "C8xYzAbCdEf",
      ReelSource.instagramShortcode("https://www.instagram.com/reel/C8xYzAbCdEf/?stkn=abc"),
    )
    assertNull(ReelSource.instagramShortcode("https://youtu.be/dQw4w9WgXcQ"))
    assertNull(ReelSource.instagramShortcode("https://x.com/i/status/1800000000000000000"))
  }
}
