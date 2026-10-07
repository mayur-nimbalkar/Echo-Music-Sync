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
    val title = "some.creator on Instagram: \"Some Song Title - Some Artist\""
    assertEquals("Some Song Title - Some Artist", ReelTitleParser.clean(title))
  }

  @Test
  fun `removes hashtags and mentions`() {
    val title = "Some Artist - Some Song #reels #viral @someartist"
    assertEquals("Some Artist - Some Song", ReelTitleParser.clean(title))
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
    val caption = "POV: late night drive\n\nsong: Artist One - Great Track\n#trending #reels"
    assertEquals("Artist One - Great Track", ReelTitleParser.extractSongHint("x on Instagram: \"vibes\"", caption))
  }

  @Test
  fun `music emoji fragment becomes a candidate`() {
    val caption = "new edit 🎵 Summer Nights - Some Singer 🎵"
    assertEquals("Summer Nights - Some Singer", ReelTitleParser.extractSongHint(null, caption))
  }

  @Test
  fun `quoted fragment is a candidate`() {
    val hint = ReelTitleParser.extractSongHint("caption about a \"Some Movie Title\" scene", null)
    assertEquals("Some Movie Title", hint)
  }

  @Test
  fun `noise prefixes are stripped`() {
    assertEquals("Late Night Melody", ReelTitleParser.extractSongHint("POV: Late Night Melody", null))
  }

  @Test
  fun `query candidates are ordered and deduplicated`() {
    val caption = "song: Golden Hour - Artist Two\n\nsong: Golden Hour - Artist Two #love"
    val candidates = ReelTitleParser.queryCandidates("reel title", caption)
    assertEquals("Golden Hour - Artist Two", candidates.first())
    assertEquals(1, candidates.count { it == "Golden Hour - Artist Two" })
  }

  @Test
  fun `cleaned title is always the last candidate`() {
    val candidates = ReelTitleParser.queryCandidates("some.creator on Instagram: \"chill vibes\"", null)
    assertEquals("chill vibes", candidates.last())
  }

  @Test
  fun `empty metadata yields empty candidates`() {
    assertEquals(emptyList<String>(), ReelTitleParser.queryCandidates(null, null))
  }

  @Test
  fun `song credit label is stripped from caption line`() {
    val caption = "Some intro text\n\n🎵 Song Name : Some Song Title\n🎬 Movie Name : Some Movie (2007)"
    val candidates = ReelTitleParser.queryCandidates(null, caption)
    assertEquals("Some Song Title", candidates.first())
  }

  @Test
  fun `stacked credit labels are peeled`() {
    assertEquals(
      "Some Composer",
      ReelTitleParser.extractSongHint("Music / Composer : Some Composer", null),
    )
  }

  @Test
  fun `real credits caption mines the song first`() {
    val caption =
      "“Some Song Title” is a popular song from the film “Some Movie” . It was released in 2007and composed by Some Composer .\n\n" +
        "✨ Song Credits________\n" +
        "🎵 Song Name : Some Song Title\n" +
        "🎬 Movie Name : Some Movie (2007)\n" +
        "🎙️ Singers : Some Singer\n" +
        "🎼 Music / Composer : Some Composer\n" +
        "📝 Lyrics / Lyricist : Some Lyricist\n" +
        "👥 Actors picturised : Some Actor, Another Actor\n" +
        "📀 Music Label : Some Label\n\n" +
        "#SomeSongTitle #SomeMovie #SomeSinger #SomeComposer"
    val candidates = ReelTitleParser.queryCandidates("Video by some.playlistshare", caption)
    assertEquals("Some Song Title", candidates.first())
    // Caption lines like the movie/actor credits follow as weaker candidates.
    assertTrue(candidates.any { it.contains("Some Movie") })
    // The credit label itself must never leak into a query.
    assertTrue(candidates.none { it.startsWith("Song Name") })
  }

  @Test
  fun `movie credit line is not confused with a song`() {
    val candidates = ReelTitleParser.queryCandidates(null, "🎬 Movie Name : Some Movie (2007)")
    // The movie title stays a candidate (weak), but the label itself is stripped.
    assertTrue(candidates.all { !it.startsWith("Movie") })
  }

  @Test
  fun `single word caption queries are not specific enough to auto-search`() {
    // A lone word mined from a caption is a topic, not a song title: YouTube Music's song
    // filter answers it with a page of unrelated tracks that merely contain the word.
    assertTrue(!ReelTitleParser.isSpecificQuery("Audience"))
    assertTrue(!ReelTitleParser.isSpecificQuery("Vibes"))
    assertTrue(!ReelTitleParser.isSpecificQuery(""))
    assertTrue(!ReelTitleParser.isSpecificQuery("a b"))
    assertTrue(ReelTitleParser.isSpecificQuery("Golden Hour"))
    assertTrue(ReelTitleParser.isSpecificQuery("Golden Hour - Artist Two"))
    assertTrue(ReelTitleParser.isSpecificQuery("summer.2019 nights"))
  }

  @Test
  fun `a bare caption word stays weak and is filterable by the matcher`() {
    val (strong, weak) = ReelTitleParser.rankedQueryCandidates(null, "Audience")
    assertTrue(strong.isEmpty())
    assertEquals(listOf("Audience"), weak)
    assertTrue(weak.none { ReelTitleParser.isSpecificQuery(it) })
  }

  @Test
  fun `explicit song markers stay usable even as a single word`() {
    val (strong, _) = ReelTitleParser.rankedQueryCandidates(null, "song: Kesariya")
    assertEquals(listOf("Kesariya"), strong)
  }

  @Test
  fun `placeholder title and caption noise are weak, not strong`() {
    // Typical reel where Instagram gives no track info and the caption is noise:
    // nothing here may count as a strong hint that would win over fingerprinting.
    val (strong, weak) =
      ReelTitleParser.rankedQueryCandidates(
        "Video by some_user_123",
        "Sad song\n.\n.\n#instagram #trending #reel #viral #song",
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
    val (strong, _) = ReelTitleParser.rankedQueryCandidates(null, "song: Golden Hour - Artist Two")
    assertEquals(listOf("Golden Hour - Artist Two"), strong)
  }

  @Test
  fun `foreign caption without markers stays weak`() {
    val (strong, _) =
      ReelTitleParser.rankedQueryCandidates(
        "Video by some_user_9876",
        "的现场表演。以独特时尚造型而闻名的他,这次依旧保持一贯的高级感",
      )
    assertTrue(strong.isEmpty())
    // The line itself may be kept as a weak fallback, but never as strong.
  }

  @Test
  fun `official queries are ordered track-artist first`() {
    val queries = ReelTitleParser.officialQueryCandidates("Some Track", "Some Artist", null)
    assertEquals(listOf("Some Track Some Artist", "Some Track", "Some Artist"), queries)
  }

  @Test
  fun `official queries empty without metadata`() {
    assertTrue(ReelTitleParser.officialQueryCandidates(null, null, null).isEmpty())
  }

  @Test
  fun `official queries skip duplicate album`() {
    val queries = ReelTitleParser.officialQueryCandidates("Same Track", null, "Same Track")
    assertEquals(listOf("Same Track"), queries)
  }

  @Test
  fun `official queries sanitize credit labels`() {
    val queries = ReelTitleParser.officialQueryCandidates("Song Name : Some Song Title", null, null)
    assertEquals(listOf("Some Song Title"), queries)
  }

  @Test
  fun `official queries drop instagram original-sound placeholder`() {
    // yt-dlp reports the creator's original audio as the track field for most reels;
    // searching it produced the wrong "garbage" matches.
    assertTrue(ReelTitleParser.officialQueryCandidates("some.creator_123 \u2022 Original audio", null, null).isEmpty())
    assertTrue(ReelTitleParser.officialQueryCandidates(null, "Original sound", null).isEmpty())
    assertTrue(ReelTitleParser.officialQueryCandidates("Original Audio - some.creator", null, null).isEmpty())
  }

  @Test
  fun `official queries drop extractor garbage and instagram wrappers`() {
    assertTrue(ReelTitleParser.officialQueryCandidates("{\"a\":1}", "application/json", null).isEmpty())
    assertTrue(ReelTitleParser.officialQueryCandidates("Mmk1ZjVib3JjenV3", null, null).isEmpty())
    assertTrue(ReelTitleParser.officialQueryCandidates(null, "Some User on Instagram", null).isEmpty())
  }

  @Test
  fun `official queries keep real track and artist`() {
    assertEquals(
      listOf("Some Track Some Artist", "Some Track", "Some Artist"),
      ReelTitleParser.officialQueryCandidates("Some Track", "Some Artist", null),
    )
    // An artist name with digits is legitimate and must survive the filter.
    assertEquals(listOf("Blink-182"), ReelTitleParser.officialQueryCandidates(null, "Blink-182", null))
  }

  @Test
  fun `official attribution found in attributed-to phrasing`() {
    val html = "<html>noise\nAudio attributed to Artist One, Artist Two\nmore noise</html>"
    val attribution = ReelTitleParser.extractOfficialAttribution(html)
    assertTrue(attribution != null && attribution.contains("Artist One"))
  }

  @Test
  fun `official attribution falls back to longest standalone line`() {
    val html = "<html>\n  \nSome Song\nArtist One, Artist Two\n</html>"
    val attribution = ReelTitleParser.extractOfficialAttribution(html)
    assertTrue(attribution != null && attribution.contains("Artist Two"))
  }

  @Test
  fun `official attribution null for empty or noise-only html`() {
    assertNull(ReelTitleParser.extractOfficialAttribution(""))
    assertNull(ReelTitleParser.extractOfficialAttribution("<html>   \n  \n</html>"))
  }

  @Test
  fun `plausible titles are accepted`() {
    assertTrue(ReelTitleParser.isPlausibleTrackTitle("Some Song Title"))
    assertTrue(ReelTitleParser.isPlausibleTrackTitle("Summer Nights 2010 (Remastered)"))
    assertTrue(ReelTitleParser.isPlausibleTrackTitle("AC/DC"))
  }

  @Test
  fun `extractor garbage is rejected as a track title`() {
    assertTrue(!ReelTitleParser.isPlausibleTrackTitle(null))
    assertTrue(!ReelTitleParser.isPlausibleTrackTitle(""))
    assertTrue(!ReelTitleParser.isPlausibleTrackTitle("   "))
    // JSON blobs that leaked through as titles
    assertTrue(!ReelTitleParser.isPlausibleTrackTitle("{\"responseContext\":{\"visitorData\":\"Cgs1LUpfOVY\"}}"))
    assertTrue(!ReelTitleParser.isPlausibleTrackTitle("application/json"))
    // Share tokens, hashes, machine ids
    assertTrue(!ReelTitleParser.isPlausibleTrackTitle("Mmk1ZjVib3JjenV3"))
    assertTrue(!ReelTitleParser.isPlausibleTrackTitle("a1b2c3d4e5f6g7h8"))
    // Symbol soup / digits only
    assertTrue(!ReelTitleParser.isPlausibleTrackTitle("///"))
    assertTrue(!ReelTitleParser.isPlausibleTrackTitle("12345"))
  }

  @Test
  fun `song hint is the quoted caption, not the uploader handle`() {
    assertEquals(
      "Summer Nights",
      ReelTitleParser.songNameHint("some.creator on Instagram: \"Summer Nights\"", null),
    )
  }

  @Test
  fun `song hint prefers explicit caption song markers`() {
    assertEquals(
      "Golden Hour - Artist Two",
      ReelTitleParser.songNameHint(null, "song: Golden Hour - Artist Two"),
    )
    assertEquals("some lyrics here", ReelTitleParser.songNameHint(null, "pov: summer\n\u266a some lyrics here \u266a\n@x.y"))
  }

  @Test
  fun `song hint is empty when only an uploader handle is known`() {
    assertEquals("", ReelTitleParser.songNameHint("john_doe_99 on Instagram: \"\"", null))
    assertEquals("", ReelTitleParser.songNameHint(null, "posted by user_1234"))
    assertEquals("", ReelTitleParser.songNameHint(null, "@cool.uploader"))
    assertEquals("", ReelTitleParser.songNameHint("{\"a\":1} on Instagram: \"x\"", null))
    assertEquals("", ReelTitleParser.songNameHint(null, null))
  }

  @Test
  fun `short track names that look handle-ish are kept`() {
    assertEquals("24K Magic", ReelTitleParser.songNameHint(null, "24K Magic"))
    assertEquals("Mr. Blue Sky", ReelTitleParser.songNameHint(null, "Mr. Blue Sky"))
    assertTrue(!ReelTitleParser.looksLikeHandle("24K Magic"))
    assertTrue(!ReelTitleParser.looksLikeHandle("Mr."))
    assertTrue(ReelTitleParser.looksLikeHandle("user_1234"))
    assertTrue(ReelTitleParser.looksLikeHandle("@some.creator"))
    assertTrue(ReelTitleParser.mentionsHandle("posted by user_1234"))
    assertTrue(!ReelTitleParser.mentionsHandle("Summer Nights"))
  }
}
