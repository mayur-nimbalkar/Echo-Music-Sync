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
   * Song-credit label prefixes found in caption credit blocks, e.g. `Song Name : Some Song`
   * or `Music / Composer : Some Artist`. Only known label words are stripped, so genuine
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

  /**
   * Generic non-song lines: yt-dlp placeholder titles ("Video by user", "Instagram video
   * by user"), boilerplate headings, disclaimers and follow-me pleas. Never good queries.
   */
  private val GENERIC_LINE =
    Regex(
      "^(?:video|reel|post|instagram|ig)(?:\\s+(?:by|from|of)\\s+.+)?$|^song\\s+credits?.*$|^lyrics?.*$|^credits?.*$|^disclaimer.*$|^follow.*$|^song\\s+credits?\\s*_+\\s*$",
      RegexOption.IGNORE_CASE,
    )

  private val TRIM_CHARS = "\"“”'‘’ \n\r\t♪♫🎵🎶"

  /**
   * Extracts the best official-attribution query from a reel's embed page text.
   * Returns the artist-list line when one exists (official Instagram data), null otherwise.
   */
  fun extractOfficialAttribution(html: String): String? {
    if (html.isBlank()) return null
    // "Audio attributed to X, Y, Z" phrasing Instagram uses on the audio page/embed.
    val attributed =
      Regex("(?:attributed to|original audio(?: of)? by|audio by)\\s*[:\u2013\u2014-]?\\s*([A-Za-z\\p{L}][\\p{L}\\p{N}'’.,&\\- ]{2,80})", RegexOption.IGNORE_CASE)
        .find(html)?.groupValues?.get(1)
    if (!attributed.isNullOrBlank()) {
      val stripped = sanitizeQuery(attributed)
      if (stripped.isNotBlank() && isPlausibleTrackTitle(stripped)) return stripped
    }
    // Fall back to the longest short text line of the embed body — the attribution line
    // sits alone on its own line in the embed markup.
    var bestLine: String? = null
    for (raw in html.lineSequence()) {
      val line = raw.trim()
      if (
        line.length in 3..120 &&
          !line.contains("http", ignoreCase = true) &&
          !line.contains('<') &&
          (bestLine == null || line.length > bestLine.length)
      ) {
        bestLine = line
      }
    }
    bestLine?.let { line ->
      val stripped = sanitizeQuery(line)
      if (stripped.isNotBlank() && isPlausibleTrackTitle(stripped)) return stripped
    }
    return null
  }

  /** Id-like tokens: 10+ mixed letters/digits (hashes, base64, media ids, share tokens). */
  private val ID_LIKE_TOKEN = Regex("(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9_-]{10,}")

  /** Mime-type-looking strings ("application/json") — extractor content types, not songs. */
  private val MIME_LIKE = Regex("^[a-z]+/[a-z0-9+.-]+$")

  /**
   * Plausibility check for a track/recognition name: rejects extractor garbage —
   * JSON blobs, hashes, share tokens, ids — before it can surface as a "match".
   * Official metadata and fingerprint results must both pass this.
   */
  fun isPlausibleTrackTitle(title: String?): Boolean {
    if (title.isNullOrBlank()) return false
    val value = title.trim()
    if (value.length !in 2..120) return false
    // JSON blobs (response payloads that leaked through as "titles").
    if (value.startsWith("{") || value.startsWith("[")) return false
    // Content-type strings like "application/json" (fully lowercase word/word).
    if (MIME_LIKE.matches(value)) return false
    val letters = value.count { it.isLetter() }
    if (letters == 0) return false
    // Under 40% letters means symbol/base64 soup, not a song name.
    if (letters * 100 < value.length * 40) return false
    // Mixed letter+digit runs of 10+ chars are machine identifiers, never titles.
    if (ID_LIKE_TOKEN.containsMatchIn(value)) return false
    return true
  }

  /**
   * Builds official-metadata queries from yt-dlp's track/artist/album fields in priority
   * order: "track artist", "track", "artist". Only OFFICIAL metadata — never captions.
   */
  fun officialQueryCandidates(track: String?, artist: String?, album: String?): List<String> {
    val trackQ = track?.let { sanitizeQuery(it) }.orEmpty()
    val artistQ = artist?.let { sanitizeQuery(it) }.orEmpty()
    val albumQ = album?.let { sanitizeQuery(it) }.orEmpty()
    val candidates = mutableListOf<String>()
    if (trackQ.isNotBlank() && artistQ.isNotBlank()) candidates.add("$trackQ $artistQ")
    if (trackQ.isNotBlank()) candidates.add(trackQ)
    if (artistQ.isNotBlank()) candidates.add(artistQ)
    if (albumQ.isNotBlank() && albumQ != trackQ) candidates.add(albumQ)
    return candidates.map { it.trim() }.filter { isUsableOfficialQuery(it) }.distinct()
  }

  /**
   * Instagram's placeholder for user-uploaded audio, which yt-dlp reports as the `track`
   * field for most reels. It names the creator's clip, not a song — searching it produced
   * the "garbage" matches. Such values must never become a query.
   */
  private val ORIGINAL_AUDIO = Regex("original\\s+(audio|sound)", RegexOption.IGNORE_CASE)

  /** True when an official-metadata value is a real song/artist worth searching. */
  private fun isUsableOfficialQuery(value: String): Boolean {
    if (value.isBlank()) return false
    // Extractor garbage (JSON blobs, hashes, share tokens) is never a song.
    if (!isPlausibleTrackTitle(value)) return false
    // "Original audio"/"Original sound" is Instagram's own placeholder, not a track.
    if (ORIGINAL_AUDIO.containsMatchIn(value)) return false
    // A leftover "… on Instagram" wrapper is boilerplate, not a song name.
    if (value.contains("instagram", ignoreCase = true)) return false
    return true
  }

  /**
   * Instagram handles: a single token with no spaces that carries handle punctuation
   * (dots, underscores) or digits. Used only to reject uploader names — a plain
   * lower-case word is ambiguous and therefore kept, since it may be a song title.
   */
  private val HANDLE_LIKE = Regex("^[A-Za-z0-9._]{1,39}$")

  /** Handle-shaped token inside a longer caption line, e.g. `user_1234` or `some.creator`. */
  private val HANDLE_TOKEN = Regex("^[A-Za-z0-9._]{5,39}$")

  /**
   * Best song-name hint mined from reel metadata, or "" when nothing usable exists.
   *
   * This is what prefills the manual-search box when identification fails, so it must be a
   * song name — never the uploader's handle (`handle on Instagram: "caption"`), Instagram
   * boilerplate or extractor garbage. Strong markers win over caption lines, every
   * candidate is validated with [isPlausibleTrackTitle], and handle-shaped values are
   * dropped even when they pass that check.
   */
  fun songNameHint(rawTitle: String?, caption: String? = null): String {
    val (strong, weak) = rankedQueryCandidates(rawTitle, caption)
    for (candidate in strong + weak) {
      val value = candidate.removePrefix("@").replace(Regex("\\s+"), " ").trim()
      if (value.isBlank() || !isPlausibleTrackTitle(value)) continue
      // A leftover "… on Instagram" wrapper means the wrapper never parsed cleanly —
      // that text is boilerplate, not a song name.
      if (value.contains("instagram", ignoreCase = true)) continue
      if (mentionsHandle(value)) continue
      return value
    }
    return ""
  }

  /** True when [value] looks like an Instagram username rather than a song name. */
  fun looksLikeHandle(value: String): Boolean {
    val trimmed = value.trim().removePrefix("@")
    if (trimmed.isEmpty() || !HANDLE_LIKE.matches(trimmed)) return false
    return trimmed.any { it.isDigit() || it == '.' || it == '_' }
  }

  /**
   * True when [value] is a handle itself or is a caption sentence built around one
   * ("posted by user_1234"). Short track fragments such as `Mr.` or `24K Magic` are
   * deliberately not treated as handles — dropping real song names is worse than
   * passing one through to the search box, which the user can edit.
   */
  fun mentionsHandle(value: String): Boolean {
    if (looksLikeHandle(value)) return true
    val tokens = value.split(' ').filter { it.isNotBlank() }
    if (tokens.size < 3) return false
    return tokens.any { HANDLE_TOKEN.matches(it) && looksLikeHandle(it) }
  }

  /** Extracts the first URL from arbitrary share text, or null when none exists. */
  fun extractUrl(text: String?): String? {
    if (text.isNullOrBlank()) return null
    val match = URL_REGEX.find(text) ?: return null
    // Drop common trailing punctuation that gets glued onto shared URLs.
    return match.value.trimEnd(')', ']', '>', '}', ',', '.', ';', ':', '"', '\'')
  }

  /**
   * Canonical reel URL: strips tracking query parameters (e.g. Instagram's `?stkn=…`)
   * and fragments. Share tokens make requests look different per share and break
   * extraction/attribution fetches; the bare reel URL is always enough.
   */
  fun canonicalReelUrl(url: String): String = url.substringBefore('?').substringBefore('#')

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
   * Ordered, de-duplicated search queries from reel metadata — the legacy flat view.
   * Strong hints first, then weak ones (see [rankedQueryCandidates]).
   */
  fun queryCandidates(rawTitle: String?, caption: String? = null): List<String> {
    val (strong, weak) = rankedQueryCandidates(rawTitle, caption)
    return strong + weak
  }

  /**
   * Strong/weak split of search queries mined from reel metadata.
   *
   * Strong = identifies a song on its own: explicit `song:` markers, ♪/🎵 fragments,
   * quoted phrases, and Instagram's audio track/artist fields. Weak = everything else
   * (caption lines, cleaned title) — names the song far less reliably. The matcher runs
   * strong queries before audio fingerprinting and weak ones only after it, so a junky
   * caption can never win over the reel's actual audio.
   *
   * Generic lines — yt-dlp's "Video by user" placeholder, credit-block headings,
   * disclaimers, follow-me pleas — are dropped entirely.
   */
  fun rankedQueryCandidates(rawTitle: String?, caption: String? = null): Pair<List<String>, List<String>> {
    val strong = LinkedHashSet<String>()
    val weak = LinkedHashSet<String>()

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
        if (stripped.isNotBlank()) strong.add(stripped)
      }
    }

    // 2. ♪ / 🎵 marked fragments.
    MUSIC_EMOJI.findAll(combined).forEach { match ->
      val start = match.range.last + 1
      val end = MUSIC_EMOJI_DELIMITER.find(combined, startIndex = start)?.range?.first ?: combined.length
      val fragment = combined.substring(start, if (end >= 0) end else combined.length)
      val stripped = sanitizeQuery(fragment)
      if (stripped.isNotBlank()) strong.add(stripped)
    }

    // 3. Quoted fragments — creators quote lyric or song lines.
    QUOTED.findAll(combined).forEach { match ->
      val stripped = sanitizeQuery(match.groupValues[1])
      if (stripped.isNotBlank()) strong.add(stripped)
    }

    // 4. Weak: caption lines and the cleaned title.
    caption?.lineSequence()?.forEach { line ->
      val stripped = sanitizeQuery(line)
      if (stripped.isNotBlank() && !GENERIC_LINE.containsMatchIn(stripped)) weak.add(stripped)
    }
    val cleanedTitle = clean(rawTitle)
    if (cleanedTitle.isNotBlank()) {
      val stripped = sanitizeQuery(cleanedTitle)
      if (stripped.isNotBlank() && !GENERIC_LINE.containsMatchIn(stripped)) weak.add(stripped)
    }

    return strong.toList() to weak.toList()
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
