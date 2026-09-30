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

  @Test
  fun `song marker hint wins over caption noise`() {
    val caption = "POV: late night drive\n\nsong: Anirudh - Hukum\n#trending #reels"
    assertEquals("Anirudh - Hukum", ReelTitleParser.extractSongHint("x on Instagram: \"vibes\"", caption))
  }

  @Test
  fun `music emoji fragment becomes a candidate`() {
    val caption = "new edit 🎵 Blinding Lights - The Weeknd 🎵"
    assertEquals("Blinding Lights - The Weeknd", ReelTitleParser.extractSongHint(null, caption))
  }

  @Test
  fun `quoted fragment is a candidate`() {
    val hint = ReelTitleParser.extractSongHint("caption about a \"Dil To Pagal Hai\" scene", null)
    assertEquals("Dil To Pagal Hai", hint)
  }

  @Test
  fun `noise prefixes are stripped`() {
    assertEquals("Late Night Melody", ReelTitleParser.extractSongHint("POV: Late Night Melody", null))
  }

  @Test
  fun `query candidates are ordered and deduplicated`() {
    val caption = "song: Kesariya - Arijit Singh\n\nsong: Kesariya - Arijit Singh #love"
    val candidates = ReelTitleParser.queryCandidates("reel title", caption)
    assertEquals("Kesariya - Arijit Singh", candidates.first())
    assertEquals(1, candidates.count { it == "Kesariya - Arijit Singh" })
  }

  @Test
  fun `cleaned title is always the last candidate`() {
    val candidates = ReelTitleParser.queryCandidates("john.doe on Instagram: \"chill vibes\"", null)
    assertEquals("chill vibes", candidates.last())
  }

  @Test
  fun `empty metadata yields empty candidates`() {
    assertEquals(emptyList<String>(), ReelTitleParser.queryCandidates(null, null))
  }
}
