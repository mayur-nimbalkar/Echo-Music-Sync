package echo.music.iad1tya.reelimport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

  @Test
  fun `song credit label is stripped from caption line`() {
    val caption = "Some intro text\n\n🎵 Song Name : Yeh Ishq Hai\n🎬 Movie Name : Jab We Met (2007)"
    val candidates = ReelTitleParser.queryCandidates(null, caption)
    assertEquals("Yeh Ishq Hai", candidates.first())
  }

  @Test
  fun `stacked credit labels are peeled`() {
    assertEquals(
      "Pritam",
      ReelTitleParser.extractSongHint("Music / Composer : Pritam", null),
    )
  }

  @Test
  fun `real credits caption mines the song first`() {
    val caption =
      "“Yeh Ishq Hai” is a popular song from the Bollywood film “Jab We Met” . It was released in 2007and composed by Pritam .\n\n" +
        "✨ Song Credits________\n" +
        "🎵 Song Name : Yeh Ishq Hai\n" +
        "🎬 Movie Name : Jab We Met (2007)\n" +
        "🎙️ Singers : Shreya Ghoshal\n" +
        "🎼 Music / Composer : Pritam\n" +
        "📝 Lyrics / Lyricist : Irshad Kamil\n" +
        "👥 Actors picturised : Kareena Kapoor Khan, Shahid Kapoor\n" +
        "📀 Music Label : T-Series\n\n" +
        "#YehIshqHai #JabWeMet #KareenaKapoor #ShahidKapoor #ShreyaGhoshal"
    val candidates = ReelTitleParser.queryCandidates("Video by my.playlistshare", caption)
    assertEquals("Yeh Ishq Hai", candidates.first())
    // Caption lines like the movie/actor credits follow as weaker candidates.
    assertTrue(candidates.any { it.contains("Jab We Met") })
    // The credit label itself must never leak into a query.
    assertTrue(candidates.none { it.startsWith("Song Name") })
  }

  @Test
  fun `movie credit line is not confused with a song`() {
    val candidates = ReelTitleParser.queryCandidates(null, "🎬 Movie Name : Jab We Met (2007)")
    // The movie title stays a candidate (weak), but the label itself is stripped.
    assertTrue(candidates.all { !it.startsWith("Movie") })
  }

  @Test
  fun `placeholder title and caption noise are weak, not strong`() {
    // Typical reel where Instagram gives no track info and the caption is noise:
    // nothing here may count as a strong hint that would win over fingerprinting.
    val (strong, weak) =
      ReelTitleParser.rankedQueryCandidates(
        "Video by abdulrehmankolsawala123",
        "Breathless song\n.\n.\n#instagram #trending #reel #viral #song",
      )
    assertTrue(strong.isEmpty())
    assertTrue(weak.isNotEmpty())
  }

  @Test
  fun `placeholder video-by title is dropped entirely`() {
    val (_, weak) = ReelTitleParser.rankedQueryCandidates("Video by some_user", null)
    assertTrue(weak.none { it.startsWith("Video by") })
  }

  @Test
  fun `credit headings and disclaimers are dropped`() {
    val (_, weak) =
      ReelTitleParser.rankedQueryCandidates(
        null,
        "✨ Song Credits________\n📌 Disclaimer :-\nsong: Real Song Name",
      )
    assertTrue(weak.none { it.contains("Credits") })
    assertTrue(weak.none { it.startsWith("Disclaimer") })
  }

  @Test
  fun `song marker is a strong hint`() {
    val (strong, _) = ReelTitleParser.rankedQueryCandidates(null, "song: Kesariya - Arijit Singh")
    assertEquals(listOf("Kesariya - Arijit Singh"), strong)
  }

  @Test
  fun `foreign caption without markers stays weak`() {
    val (strong, _) =
      ReelTitleParser.rankedQueryCandidates(
        "Video by queen_meghna_9876",
        "的现场表演。以独特时尚造型而闻名的他,这次依旧保持一贯的高级感",
      )
    assertTrue(strong.isEmpty())
    // The line itself may be kept as a weak fallback, but never as strong.
  }
}
