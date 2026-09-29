package echo.music.iad1tya.reelimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReelTitleParserTest {

  @Test
  fun `extracts url from share text with caption`() {
    val text = "Check this out https://www.instagram.com/reel/Cxyz123/ so cool"
    assertEquals("https://www.instagram.com/reel/Cxyz123/", ReelTitleParser.extractUrl(text))
  }

  @Test
  fun `extracts url and strips trailing punctuation`() {
    val text = "https://www.instagram.com/reel/Cxyz123/."
    assertEquals("https://www.instagram.com/reel/Cxyz123/", ReelTitleParser.extractUrl(text))
  }

  @Test
  fun `returns null for empty text`() {
    assertNull(ReelTitleParser.extractUrl(null))
    assertNull(ReelTitleParser.extractUrl(""))
    assertNull(ReelTitleParser.extractUrl("no links here"))
  }

  @Test
  fun `cleans on-instagram prefix`() {
    val title = "john.doe on Instagram: \"Midnight City - M83\""
    assertEquals("Midnight City - M83", ReelTitleParser.clean(title))
  }

  @Test
  fun `removes hashtags and mentions`() {
    val title = "SZA - Kill Bill #reels #viral @sza"
    assertEquals("SZA - Kill Bill", ReelTitleParser.clean(title))
  }

  @Test
  fun `collapses whitespace and quotes`() {
    val title = "\"  bla  bla   bla  \""
    assertEquals("bla bla bla", ReelTitleParser.clean(title))
  }

  @Test
  fun `handles instagram suffix variant`() {
    val title = "Cool song • Instagram"
    assertEquals("Cool song", ReelTitleParser.clean(title))
  }

  @Test
  fun `blank title becomes empty string`() {
    assertEquals("", ReelTitleParser.clean(""))
    assertEquals("", ReelTitleParser.clean(null))
  }
}
