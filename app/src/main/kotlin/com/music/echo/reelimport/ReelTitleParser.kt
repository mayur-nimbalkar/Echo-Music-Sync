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

  private val TRIM_CHARS = "\"“”'‘’ \n\r\t"

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
    title = title.trim(TRIM_CHARS)
    return title.replace(Regex("\\s+"), " ").trim()
  }
}
