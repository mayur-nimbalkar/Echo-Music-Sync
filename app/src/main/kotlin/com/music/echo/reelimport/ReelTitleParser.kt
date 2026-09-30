package echo.music.iad1tya.reelimport

/**
 * Pure parsing helpers for Instagram Reel imports.
 *
 * Kept free of Android dependencies so the parsing rules can be unit tested on the JVM.
 */
object ReelTitleParser {

  /** Matches the first http(s) URL inside a blob of shared text. */
  private val URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)

  /** Instagram metadata titles look like `username on Instagram: "caption"`. */
  private val ON_INSTAGRAM_PREFIX = Regex("^.*?\\s+on Instagram:\\s*", RegexOption.IGNORE_CASE)

  /** Some extractors produce `caption • Instagram` or `caption | Instagram`. */
  private val INSTAGRAM_SUFFIX = Regex("[•|·]\\s*Instagram\\s*$", RegexOption.IGNORE_CASE)

  /** Hashtags and @mentions are noise for a YouTube Music search. */
  private val TAGS_AND_MENTIONS = Regex("(?:^|\\s)[#@][\\p{L}\\p{N}_]+")

  /**
   * Song-credit label prefixes found in caption credit blocks, e.g. `Song Name : Yeh Ishq Hai`
   * or `Music / Composer : Pritam`. Only known label words are stripped, so genuine
   * `Title: Artist` captions are left alone.
   */
  private val CREDIT_LABEL =
    Regex(
      "^(?:(?:song|music|audio|track|movie|film|singers?|vocals?|composer|lyrics?|lyricist|album|label|artist|title)(?:\\s+name)?\\s*(?:[/&|]\\s*(?:song|music|audio|track|movie|film|singers?|vocals?|composer|lyrics?|lyricist|album|label|artist|title)(?:\\s+name)?\\s*)*[:\u2013\u2014-]\\s*)+",
      RegexOption.IGNORE_CASE,
    )

  /** Common caption prefixes that carry no song information. */
  private val NOISE_PREFIX =
    Regex(
      "^(?:pov\\s*:|me\\s+when|when\\s+the\\s*|no\\s+thoughts\\s+just|vibing\\s+to|listening\\s+to|obsessed\\s+with|currently\\s+obsessed\\s+with|this\\s+song\\s+is|song\\s*is|credit(?:s)?\\s*:|ctto)\\s*",
      RegexOption.IGNORE_CASE,
    )

  /** Explicit song markers creators use in captions, e.g. `song: Artist - Title`. */
  private val SONG_MARKERS =
    listOf("song:", "music:", "audio:", "track:", "sound:", "tune:", "song by", "music by", "audio by", "feat.", "ft.")

  /** Emoji used to mark song lyrics/notes in captions. */
  private val MUSIC_EMOJI = Regex("[♪♫🎵🎶]")

  /** End-of-fragment delimiters for ♪-marked song hints. */
  private val MUSIC_EMOJI_DELIMITER = Regex("[♪♫🎵🎶\\n]")

  /** Emoji and separators that pollute a search query. */
  private val EMOJI_AND_SYMBOLS =
    Regex(
      "[\\p{So}\\p{Cn}\\p{Sk}✨🌟💫⭐🔥💯😭🥹🥰😍🤩💜❤️‍🔥🫶🤍🖤✅️❌️‼️⁉️™️©️®️]+",
    )

  /** Unicode quotes wrapping explicit song hints. */
  private val QUOTED = Regex("[\"“”„«»'‘’]([^\"“”„«»'‘’]{3,80})[\"“”„«»'‘’]")

  private val TRIM_CHARS = "\"“”'‘’ \n\r\t♪♫🎵🎶"

  /** Extracts the first URL from arbitrary share text, or null when none exists. */
  fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val match = URL_REGEX.find(text) ?: return null
    // Drop common trailing punctuation that gets glued onto shared URLs.
    return match.value.trimEnd(')', ']', '>', '}', ',', '.', ';', ':', '"', '\'')
  }

  /**
   * Turns a raw Instagram reel title (as produced by yt-dlp) into a search-friendly song query:
   * strips the `on Instagram:` wrapper, hashtags/mentions and repeated whitespace.
   */
  fun clean(rawTitle: String?): String {
    if (rawTitle.isNullOrBlank()) return ""
    var title = rawTitle.trim()
    title = ON_INSTAGRAM_PREFIX.replace(title, "")
    title = INSTAGRAM_SUFFIX.replace(title, "")
    title = TAGS_AND_MENTIONS.replace(title, " ")
    title = title.trim(*TRIM_CHARS.toCharArray())
    return title.replace(Regex("\\s+"), " ").trim()
  }

  /**
   * Best single song hint from raw reel metadata (title + full caption), or null.
   *
   * Heuristics, in order:
   * 1. Explicit markers — `song:`, `music:`, `audio:`, `track:`, `sound:` …
   * 2. `♪ … ♪` / 🎵 … lyric fragments.
   * 3. Quoted fragments inside the caption.
   * 4. The cleaned caption itself (first meaningful line).
   */
  fun extractSongHint(rawTitle: String?, caption: String? = null): String? {
    val candidates = queryCandidates(rawTitle, caption)
    return candidates.firstOrNull()
  }

  /**
   * Generates ordered, de-duplicated YouTube Music search queries from reel metadata.
   * The first entries are the most specific song hints; the last is the cleaned title
   * as a generic fallback, so the list is never empty when [clean] isn't either.
   */
  fun queryCandidates(rawTitle: String?, caption: String? = null): List<String> {
    val results = LinkedHashSet<String>()

    val combined = listOfNotNull(rawTitle?.trim()?.takeIf { it.isNotBlank() }, caption?.trim()?.takeIf { it.isNotBlank() })
      .joinToString("\n")

    // 1. Explicit `song: Artist - Title` style markers anywhere in title or caption.
    for (marker in SONG_MARKERS) {
      val idx = combined.indexOf(marker, ignoreCase = true)
      if (idx >= 0) {
        var value = combined.substring(idx + marker.length)
        // Stop the hint at the next marker or line break.
        value = value.lineSequence().firstOrNull { it.isNotBlank() } ?: ""
        val stripped = sanitizeQuery(value)
        if (stripped.isNotBlank()) results.add(stripped)
      }
    }

    // 2. ♪ / 🎵 marked fragments.
    MUSIC_EMOJI.findAll(combined).forEach { match ->
      val start = match.range.last + 1
      val end = MUSIC_EMOJI_DELIMITER.find(combined, startIndex = start)?.range?.first ?: combined.length
      val fragment = combined.substring(start, if (end >= 0) end else combined.length)
      val stripped = sanitizeQuery(fragment)
      if (stripped.isNotBlank()) results.add(stripped)
    }

    // 3. Quoted fragments — creators quote lyric or song lines.
    QUOTED.findAll(combined).forEach { match ->
      val stripped = sanitizeQuery(match.groupValues[1])
      if (stripped.isNotBlank()) results.add(stripped)
    }

    // 4. Caption lines and the cleaned title as progressively generic candidates.
    caption?.lineSequence()?.forEach { line ->
      val stripped = sanitizeQuery(line)
      if (stripped.isNotBlank()) results.add(stripped)
    }
    val cleanedTitle = clean(rawTitle)
    if (cleanedTitle.isNotBlank()) results.add(sanitizeQuery(cleanedTitle))

    return results.toList().take(5)
  }

  /** Cleans one candidate line: strips noise prefixes, tags, emojis and extra quotes. */
  private fun sanitizeQuery(line: String): String {
    var value = line.trim()
    // Strip a leading explicit marker ("song: Artist - Title" → "Artist - Title").
    for (marker in SONG_MARKERS) {
      if (value.startsWith(marker, ignoreCase = true)) {
        value = value.substring(marker.length)
        break
      }
    }
    value = EMOJI_AND_SYMBOLS.replace(value, " ")
    value = value.trim(*TRIM_CHARS.toCharArray())
    // Peel stacked credit labels ("Song Name : Music : X" → "X"), including ones that
    // were only visible after emoji removal ("🎬 Movie Name : X" → "X"). Anchored,
    // so it only runs after trimming.
    while (true) {
      val stripped = CREDIT_LABEL.replace(value, "")
      if (stripped == value) break
      value = stripped
    }
    value = NOISE_PREFIX.replace(value, "")
    value = TAGS_AND_MENTIONS.replace(value, " ")
    value = value.replace(Regex("\\s+"), " ").trim()
    return if (value.length < 3) "" else value
  }
}
