package echo.music.iad1tya.reelimport

/**
 * Where an imported short-form clip comes from.
 *
 * The identification pipeline — extract the clip's audio with yt-dlp, fingerprint it, let Shazam
 * name it, then resolve that name on YouTube Music — is source-agnostic. Supporting a site is
 * therefore just link recognition and canonicalisation, plus skipping the metadata endpoints
 * that only Instagram exposes.
 *
 * TikTok is deliberately not supported.
 *
 * Kept free of Android dependencies so the URL rules can be unit tested on the JVM.
 */
enum class ReelSource {
  /** Instagram reels and posts: `instagram.com/reel|reels|p/<code>`. */
  INSTAGRAM,

  /** YouTube Shorts (`youtube.com/shorts/<id>`) and `youtu.be/<id>` share links. */
  YOUTUBE,

  /** X/Twitter posts: `x.com/<user>/status/<id>` (also `twitter.com`, `mobile.`). */
  X;

  /**
   * True when this source publishes its own audio metadata (today only Instagram's
   * `clips_metadata.music_info`), worth one extra request before falling back to captions.
   */
  val hasAudioMetadataApi: Boolean
    get() = this == INSTAGRAM

  companion object {
    /** Instagram reel/post link; group 1 is the media shortcode. */
    private val INSTAGRAM_URL =
      Regex(
        "^https?://(?:[A-Za-z0-9-]+\\.)*(?:instagram\\.com|instagr\\.am)/(?:reel|reels|p)/([A-Za-z0-9_-]+)",
        RegexOption.IGNORE_CASE,
      )

    /** YouTube Shorts link; group 1 is the video id. */
    private val YOUTUBE_SHORTS_URL =
      Regex(
        "^https?://(?:www\\.|m\\.)?youtube\\.com/shorts/([A-Za-z0-9_-]{6,})",
        RegexOption.IGNORE_CASE,
      )

    /** `youtu.be` share link — what the YouTube app puts on the clipboard; group 1 is the id. */
    private val YOUTU_BE_URL =
      Regex("^https?://youtu\\.be/([A-Za-z0-9_-]{6,})", RegexOption.IGNORE_CASE)

    /** X/Twitter status link (`/user/status/<id>`, `/i/status/<id>`, `/i/web/status/<id>`). */
    private val X_STATUS_URL =
      Regex(
        "^https?://(?:www\\.|mobile\\.|m\\.)?(?:x|twitter)\\.com/(?:.*/)?status/(\\d{5,})",
        RegexOption.IGNORE_CASE,
      )

    /** Source of [url], or null when it is not a link this importer understands. */
    fun of(url: String?): ReelSource? {
      if (url.isNullOrBlank()) return null
      val value = url.trim()
      return when {
        INSTAGRAM_URL.containsMatchIn(value) -> ReelSource.INSTAGRAM
        YOUTUBE_SHORTS_URL.containsMatchIn(value) -> ReelSource.YOUTUBE
        YOUTU_BE_URL.containsMatchIn(value) -> ReelSource.YOUTUBE
        X_STATUS_URL.containsMatchIn(value) -> ReelSource.X
        else -> null
      }
    }

    /**
     * Stable form of [url] for one import: tracking parameters and fragments removed, and the
     * canonical host/path kept so yt-dlp sees the same URL every time the clip is imported.
     *
     * Instagram share links carry `?stkn=…` tokens that break attribution fetches; YouTube and
     * X links carry `?si=` / `?s=` share tags. All of them are irrelevant to extraction.
     */
    fun canonicalize(url: String): String {
      val value = url.trim()
      val bare = value.substringBefore('?').substringBefore('#')
      return when (of(value)) {
        ReelSource.INSTAGRAM -> bare
        ReelSource.YOUTUBE -> {
          val shortsId = YOUTUBE_SHORTS_URL.find(value)?.groupValues?.get(1)
          val youtuBeId = YOUTU_BE_URL.find(value)?.groupValues?.get(1)
          when {
            shortsId != null -> "https://www.youtube.com/shorts/$shortsId"
            youtuBeId != null -> "https://youtu.be/$youtuBeId"
            else -> bare
          }
        }
        ReelSource.X ->
          X_STATUS_URL.find(value)?.groupValues?.get(1)?.let { "https://x.com/i/status/$it" }
            ?: bare
        null -> bare
      }
    }

    /** The Instagram media shortcode of [url], or null when [url] is not an Instagram link. */
    fun instagramShortcode(url: String): String? =
      INSTAGRAM_URL.find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
  }
}
