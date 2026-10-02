package echo.music.iad1tya.reelimport

import android.content.Context
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.shazamkit.models.RecognitionResult
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import echo.music.iad1tya.constants.InstagramSessionIdKey
import echo.music.iad1tya.recognition.AudioResampler
import echo.music.iad1tya.recognition.DecodedAudio
import echo.music.iad1tya.recognition.VibraSignature
import echo.music.iad1tya.utils.reportException
import echo.music.iad1tya.utils.dataStore
import echo.music.iad1tya.utils.get
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import timber.log.Timber

/**
 * Identifies the background music of an Instagram Reel and resolves it to YouTube Music tracks.
 *
 * Pipeline (official metadata first, audio second — captions are never used):
 * 1. yt-dlp fetches reel metadata (`--dump-json`); if the official audio attribution is missing
 *    there, Instagram's web app embed page is fetched for it ("audio attributed to …").
 * 2. Official track/artist/album metadata is searched on YouTube Music. Fast, and authoritative
 *    when present — Instagram knows exactly which audio the reel uses.
 * 3. When no official metadata exists (original audio), a short excerpt is fingerprinted with
 *    the same [VibraSignature] used by Echo Find.
 * 4. As a last resort the creator's caption is mined for a song name and searched, but its
 *    results only ever reach the picker — captions misname songs too often to auto-confirm.
 * 5. Anything else lands in the manual-search picker, prefilled with the best song-name hint
 *    available (never the uploader's handle).
 *
 * A public [search] is exposed so the UI always offers a manual escape hatch.
 */
object ReelMatcher {

  /** One fingerprint window: ~12.5 s of 16-bit stereo @ 16 kHz. */
  private const val MAX_EXCERPT_BYTES = 800_000

  /** Total audio kept for the two fingerprint windows (start + middle): ~50 s. */
  private const val MAX_TOTAL_PCM_BYTES = 3_200_000L

  /** Audio format the excerpt is converted into before fingerprinting. */
  private const val PCM_SAMPLE_RATE = 16_000
  private const val PCM_CHANNELS = 2

  /** Standard RIFF/WAVE header size stripped before fingerprinting. */
  private const val WAV_HEADER_BYTES = 44L

  /** yt-dlp network timeouts in seconds. */
  private const val SOCKET_TIMEOUT_SECONDS = 15

  /** yt-dlp retry cap — the default of 10 turns dead networks into minute-long hangs. */
  private const val YTDLP_RETRIES = 3

  /** Hard wall-clock budget for one YouTube Music search (including InnerTube's internal retries). */
  private const val SEARCH_TIMEOUT_SECONDS = 15L

  /** Official-metadata queries tried before moving to fingerprinting. */
  private const val MAX_METADATA_QUERIES = 3

  /**
   * Caption-derived queries tried after fingerprinting failed. Captions name the song
   * often enough to be worth a couple of lookups, but not often enough to trust —
   * so they only ever reach the user as candidates in the picker, never as a match.
   */
  private const val MAX_CAPTION_QUERIES = 2

  /** Timeout for the Instagram embed-page attribution fetch (seconds). */
  private const val ATTRIBUTION_TIMEOUT_SECONDS = 8L

  /** Instagram's public web app id — accepted by the mobile media-info endpoint. */
  private const val IG_WEB_APP_ID = "936619743392459"

  /**
   * The user's own Instagram session (Settings → Content → Instagram session).
   * When set, Instagram's app endpoints and the embed page treat requests as a logged-in
   * app/browser instead of an anonymous datacenter crawler — the difference between
   * working metadata and 403s. Stored only on-device.
   *
   * Accepts a bare `sessionid` value, or a whole cookie line such as
   * `sessionid=…; ds_user_id=…; csrftoken=…` (parsed below).
   */
  @Volatile private var sessionId: String? = null
  @Volatile private var dsUserId: String? = null

  /** Live-updates the session cookie (called from Settings). */
  fun setSessionId(value: String?) {
    applySession(value)
  }

  /** The cookie header sent with every Instagram request, or null when signed out. */
  private fun sessionCookie(): String? {
    val sid = sessionId?.takeIf { it.isNotBlank() } ?: return null
    return buildString {
      append("sessionid=").append(sid)
      dsUserId?.takeIf { it.isNotBlank() }?.let { append("; ds_user_id=").append(it) }
    }
  }

  /** One shared client for every Instagram call — building a client per request is pure waste. */
  private val httpClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
      .connectTimeout(ATTRIBUTION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      .readTimeout(ATTRIBUTION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      .build()
  }

  /** Parses either a bare sessionid or a cookie line into the session fields. */
  private fun applySession(raw: String?) {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) {
      sessionId = null
      dsUserId = null
      return
    }
    if (text.contains("sessionid=")) {
      // Whole cookie line: pull the interesting parts out.
      sessionId = parseCookieValue(text, "sessionid")
      dsUserId = parseCookieValue(text, "ds_user_id")
    } else {
      sessionId = text
      dsUserId = null
    }
  }

  private fun parseCookieValue(cookieLine: String, name: String): String? {
    // Tolerate a pasted request header, e.g. "Cookie: sessionid=...; ds_user_id=...".
    val body = if (cookieLine.trimStart().startsWith("cookie:", ignoreCase = true)) {
      cookieLine.substringAfter(':')
    } else {
      cookieLine
    }
    val match = Regex("(?:^|[;\\s])$name=([^;\\s]+)").find(body) ?: return null
    return match.groupValues[1].trim().ifBlank { null }
  }

  /**
   * Probes a lightweight Instagram endpoint with the current session cookie to check
   * whether it is still valid. Used by the Settings screen after saving a cookie.
   */
  suspend fun testSession(): Boolean =
    withContext(Dispatchers.IO) {
      runCatching {
        withTimeoutOrNull(ATTRIBUTION_TIMEOUT_SECONDS.seconds) {
          val request =
            okhttp3.Request.Builder()
              .url("https://i.instagram.com/api/v1/accounts/current/?__a=1&__d=1")
              .header("User-Agent", IG_APP_USER_AGENT)
              .header("x-ig-app-id", IG_WEB_APP_ID)
              .header("Cookie", sessionCookie().orEmpty())
              .build()
          val response = httpClient.newCall(request).execute()
          response.use { it.code != 401 && it.code != 403 }
        }
          ?: false
      }
        .onFailure { Timber.tag("ReelMatcher").d(it, "Session test failed") }
        .getOrNull() ?: false
    }

  /**
   * Instagram mobile app user agent for the media-info endpoint. The desktop web page
   * is login-walled and exposes no audio metadata; the app endpoint returns the official
   * `clips_metadata.music_info` block (song/artist/album) for public reels.
   */
  private const val IG_APP_USER_AGENT =
    "Instagram 76.0.0.15.395 Android (29/10; 420dpi; 1080x2400; samsung; SM-A266B; a26x; exynos2100)"

  /** Plain browser agent for page fetches and the yt-dlp audio extraction. */
  private const val IG_BROWSER_USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"

  /** Reel URLs accepted by the importer; group 1 is the media shortcode. */
  private val REEL_URL_REGEX =
    Regex(
      "^https?://(?:www\\.|m\\.)?instagram\\.com/(?:reel|reels|p)/([A-Za-z0-9_-]+)",
      RegexOption.IGNORE_CASE,
    )

  fun isSupportedReelUrl(url: String?): Boolean =
    url != null && REEL_URL_REGEX.containsMatchIn(url)

  /** The reel's media shortcode, or null when [url] is not a reel link. */
  private fun shortcodeOf(url: String): String? =
    REEL_URL_REGEX.find(url)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

  sealed class MatchResult {
    /** A confident fingerprint match resolved on YouTube Music. */
    data class Matched(
      val song: SongItem,
      val recognition: RecognitionResult?,
      val reelTitle: String,
    ) : MatchResult()

    /**
     * Metadata-based candidates (caption hints / track fields), or a bare title search
     * when no hint could be mined. The user confirms or picks manually.
     */
    data class TitleFallback(val songs: List<SongItem>, val reelTitle: String) : MatchResult()

    /** The link is not a reel (or is private/deleted). */
    data object NotAReel : MatchResult()

    /**
     * Nothing matched and no Instagram session is configured — setup would likely
     * have provided the official metadata that makes identification work. [reelTitle]
     * carries the best mined hint for the manual-search prefill.
     */
    data class NeedsLogin(val reelTitle: String) : MatchResult()

    /** yt-dlp is unavailable (first-run binary update failed) or extraction failed. */
    data class Error(val message: String) : MatchResult()
  }

  /** Parsed reel metadata. Only the fields the pipeline actually consumes are kept. */
  private data class ReelInfo(
    val title: String,
    /** Full caption/description. Only mined for song hints (stage 3), never auto-matched. */
    val caption: String? = null,
    /** Track title reported by Instagram's audio metadata, when available. */
    val track: String? = null,
    /** Artist reported by Instagram's audio metadata, when available. */
    val artist: String? = null,
    /** Album reported by Instagram's audio metadata, when available. */
    val album: String? = null,
  )

  /** One-time native binary setup. Call from Application startup. */
  fun init(context: Context) {
    applySession(context.dataStore[InstagramSessionIdKey])
    try {
      YoutubeDL.init(context)
      FFmpeg.init(context)
      // The library ships a frozen yt-dlp from its release date; site extractors drift
      // constantly, so bring the binary current before first use (done once per version,
      // the updater no-ops when already current).
      runCatching { YoutubeDL.updateYoutubeDL(context, YoutubeDL.UpdateChannel.STABLE) }
        .onSuccess { status -> Timber.i("ReelMatcher: yt-dlp updated ($status)") }
        .onFailure { Timber.e(it, "ReelMatcher: yt-dlp update failed — using bundled binary") }
    } catch (e: Exception) {
      Timber.e(e, "ReelMatcher: yt-dlp init failed")
    }
  }

  /**
   * Runs the identification pipeline. All work happens on [Dispatchers.IO].
   * Throws nothing — every outcome is modelled in [MatchResult].
   */
  suspend fun match(context: Context, reelUrl: String): MatchResult =
    withContext(Dispatchers.IO) {
      if (!isSupportedReelUrl(reelUrl)) return@withContext MatchResult.NotAReel

      val info = fetchReelInfo(reelUrl) ?: return@withContext MatchResult.Error("Reel could not be fetched")

      // Official metadata only: yt-dlp's track/artist/album fields (Instagram's audio
      // attribution), optionally enriched from Instagram's embed page. Creator captions
      // are held back for stage 3 — they routinely misname or omit the song.
      val ytDlpOfficial = ReelTitleParser.officialQueryCandidates(info.track, info.artist, info.album)
      val officialQueries =
        ytDlpOfficial.ifEmpty {
          // Attribution missing from yt-dlp JSON (common for most reels): ask Instagram's
          // official media-info endpoint for the licensed-audio song/artist, then fall
          // back to the embed page's attribution line.
          ReelImportState.report(ReelImportStage.FETCHING_METADATA)
          val apiTriple = fetchOfficialMetadataApi(reelUrl)
          val apiQueries =
            apiTriple?.let { (song, artist, album) ->
              ReelTitleParser.officialQueryCandidates(song, artist, album)
            }.orEmpty()
          apiQueries.ifEmpty {
            fetchOfficialAttribution(reelUrl)?.let { attribution ->
              listOf(attribution, "$attribution artist").filter { it.isNotBlank() }
            }.orEmpty()
          }
        }

      // Stage 1 — search official audio metadata.
      var searchFailed = false
      if (officialQueries.isNotEmpty()) {
        ReelImportState.report(ReelImportStage.MATCHING)
        for (query in officialQueries.take(MAX_METADATA_QUERIES)) {
          // Show the live query so long stages are never a blind spinner.
          ReelImportState.update(reelUrl) { it.copy(searchQuery = query) }
          val candidates = searchYouTubeMusic(query)
          when {
            candidates == null -> searchFailed = true // network/timeout — emptiness proves nothing
            candidates.isNotEmpty() -> {
              Timber.tag("ReelMatcher").i("Official query \"%s\" matched: %s", query, candidates.first().title)
              return@withContext MatchResult.TitleFallback(candidates, query)
            }
          }
        }
      }

      // Stage 2 — audio: fingerprint the reel's actual audio (start + middle windows).
      // Skipped when YouTube Music itself is unreachable: a fingerprint match still needs
      // a YT Music resolve, so this would only add minutes to the same failure.
      if (!searchFailed) {
        ReelImportState.report(ReelImportStage.EXTRACTING)
        val fingerprintResult = runCatching { recognizeByFingerprint(context, reelUrl) }
        val recognition = fingerprintResult.getOrNull()

        if (recognition != null) {
          val resolved = resolveOnYouTubeMusic(recognition)
          if (resolved != null) return@withContext resolved
        } else {
          fingerprintResult.exceptionOrNull()?.let {
            Timber.tag("ReelMatcher").e(it, "Fingerprinting failed")
          }
        }
      } else {
        Timber.tag("ReelMatcher").w("YouTube Music unreachable — skipping extraction/fingerprinting")
      }

      // Stage 3 — last look at the creator's own text. A caption song marker often names
      // the track; results only reach the picker as candidates, never as an auto-match.
      if (!searchFailed) {
        val captionQueries = ReelTitleParser.rankedQueryCandidates(info.title, info.caption).second
        for (query in captionQueries.take(MAX_CAPTION_QUERIES)) {
          ReelImportState.update(reelUrl) { it.copy(searchQuery = query) }
          val candidates = searchYouTubeMusic(query)
          if (candidates == null) {
            searchFailed = true
            continue
          }
          if (candidates.isNotEmpty()) {
            Timber.tag("ReelMatcher").i("Caption query \"%s\" produced candidates", query)
            return@withContext MatchResult.TitleFallback(candidates, query)
          }
        }
      }

      // Nothing identified. Prefill the manual search with the best song-name hint we
      // have: official attribution first, then the caption, and never the uploader
      // handle (the picker prefills its search box from this).
      val hint =
        officialQueries.firstOrNull()?.takeIf { it.isNotBlank() }
          ?: ReelTitleParser.songNameHint(info.title, info.caption)
      if (sessionId.isNullOrBlank() && !searchFailed) {
        // Every stage came up empty with no session cookie: the most common fixable
        // cause. Suggest setup on the failure screen instead of a generic dead end.
        Timber.tag("ReelMatcher").i("No match and no Instagram session — suggesting setup")
        MatchResult.NeedsLogin(hint)
      } else {
        MatchResult.TitleFallback(emptyList(), hint)
      }
    }

  /** Manual search used by the UI so a failed match never dead-ends the flow. */
  suspend fun search(query: String): List<SongItem> = searchYouTubeMusic(query.trim()).orEmpty()

  /** Fetches reel metadata with yt-dlp (no media download). */
  private suspend fun fetchReelInfo(url: String): ReelInfo? {
    ReelImportState.report(ReelImportStage.FETCHING_METADATA)
    return runCatching {
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-json")
        request.addOption("--no-playlist")
        request.addOption("--socket-timeout", SOCKET_TIMEOUT_SECONDS)
        request.addOption("--retries", YTDLP_RETRIES)
        // With the user's session cookie, Instagram serves yt-dlp as a logged-in
        // client instead of walling it.
        sessionCookie()?.let { request.addOption("--add-headers", "Cookie: $it") }
        val response = YoutubeDL.getInstance().execute(request)
        if (response.exitCode != 0 && response.out.isBlank()) return@runCatching null
        // Parse --dump-json output with org.json — Jackson is runtime-scoped in the library.
        val json = org.json.JSONObject(response.out)
        ReelInfo(
          title = json.optString("fulltitle").ifBlank { json.optString("title") },
          // Keep the full caption: song markers like `song:` often sit at the end.
          caption = json.optString("description").ifBlank { null },
          track = json.optString("track").ifBlank { json.optString("igtv_track_name") }.ifBlank { null },
          artist = json.optString("artist").ifBlank { json.optString("igtv_artist_name") }.ifBlank { null },
          album = json.optString("album").ifBlank { null },
        )
      }
      .onFailure { Timber.tag("ReelMatcher").e(it, "fetchReelInfo failed for %s", url) }
      .getOrNull()
  }

  /**
   * Downloads the reel's audio and fingerprints two windows: the start and, when the
   * clip is long enough, the middle. Reels frequently open with speech or silence and
   * only play the song later — one window is often not enough.
   * Returns null when extraction/fingerprinting infrastructure is unavailable —
   * callers then fall back to weaker metadata hints.
   */
  private suspend fun recognizeByFingerprint(context: Context, url: String): RecognitionResult? {
    val pcm = extractPcmExcerpt(context, url) ?: return null

    ReelImportState.report(ReelImportStage.LISTENING)

    // Split the extracted audio into overlapping windows and fingerprint each.
    val windowBytes = MAX_EXCERPT_BYTES
    val totalWindows = if (pcm.size <= windowBytes) 1 else 2
    val secondWindowStart =
      if (totalWindows == 2) ((pcm.size - windowBytes).coerceAtLeast(0)) / 2 else 0

    for (windowIndex in 0 until totalWindows) {
      val start = if (windowIndex == 0) 0 else secondWindowStart
      val end = minOf(start + windowBytes, pcm.size)
      if (end - start < 32_000) continue // ~0.5 s — too short to fingerprint

      val window = pcm.copyOfRange(start, end)
      val recognition = fingerprintWindow(window)
      if (recognition != null) {
        Timber.tag("ReelMatcher").i("Fingerprint window %d matched", windowIndex)
        return recognition
      }
    }
    return null
  }

  /** Fingerprints one PCM window (16-bit LE stereo @ 16 kHz) with Shazam. */
  private suspend fun fingerprintWindow(pcm: ByteArray): RecognitionResult? {
    val decoded = DecodedAudio(pcm, PCM_CHANNELS, PCM_SAMPLE_RATE, android.media.AudioFormat.ENCODING_PCM_16BIT)
    val resampled =
      AudioResampler.resample(decoded, VibraSignature.REQUIRED_SAMPLE_RATE).getOrElse { error ->
        Timber.tag("ReelMatcher").w(error, "Resampling excerpt failed")
        return null
      }

    val signature = VibraSignature.fromI16(resampled.data)
    // 16-bit frames: bytes ÷ 2 per sample, ÷ channel count; ms = frames × 1000 ÷ rate.
    val sampleDurationMs =
      (resampled.data.size.toLong() / 2 / PCM_CHANNELS) * 1000L / VibraSignature.REQUIRED_SAMPLE_RATE

    val recognition = com.music.shazamkit.Shazam.recognize(signature, sampleDurationMs).getOrNull()
    if (recognition == null) return null
    if (!ReelTitleParser.isPlausibleTrackTitle(recognition.title)) {
      Timber.tag("ReelMatcher").w("Discarding implausible fingerprint match: %s", recognition.title)
      return null
    }
    return recognition
  }

  /**
   * yt-dlp extracts the reel's audio, transcodes it to raw 16-bit LE PCM with the bundled
   * FFmpeg, and the first chunk is kept for fingerprinting.
   */
  private suspend fun extractPcmExcerpt(context: Context, url: String): ByteArray? =
    withContext(Dispatchers.IO) {
      ReelImportState.report(ReelImportStage.EXTRACTING)
      var outFile: File? = null
      try {
        outFile = File.createTempFile("reel_excerpt_", ".wav", context.cacheDir)
        val request = YoutubeDLRequest(url)
        request.addOption("-x")
        // `s16le` is not a valid --audio-format; WAV decodes to PCM 16-bit little-endian.
        request.addOption("--audio-format", "wav")
        // Channel/rate conversion is not an yt-dlp flag — pass it to the bundled ffmpeg.
        request.addOption(
          "--postprocessor-args",
          "ffmpeg:-ar $PCM_SAMPLE_RATE -ac $PCM_CHANNELS",
        )
        request.addOption("-o", outFile.absolutePath.substringBeforeLast(".wav") + ".%(ext)s")
        request.addOption("--no-playlist")
        request.addOption("--no-part")
        request.addOption("--socket-timeout", SOCKET_TIMEOUT_SECONDS)
        request.addOption("--retries", YTDLP_RETRIES)
        // Public reels are usually downloadable anonymously; private ones are not.
        request.addOption("--no-check-certificates")
        request.addOption("--user-agent", IG_BROWSER_USER_AGENT)

        // The library injects --ffmpeg-location automatically for every execution.
        YoutubeDL.getInstance().execute(request)

        // yt-dlp names the output after the container (`.wav`), not our suffix.
        val wavFile =
          if (outFile.exists() && outFile.length() > 0L) {
            outFile
          } else {
            File(outFile.absolutePath.substringBeforeLast(".wav") + ".wav")
          }
        if (!wavFile.exists() || wavFile.length() == 0L) {
          Timber.tag("ReelMatcher").w("PCM excerpt was not produced")
          return@withContext null
        }
        outFile = wavFile
        Timber.tag("ReelMatcher").i("PCM excerpt ready: %d bytes", wavFile.length())

        // Read the whole PCM payload (capped) so two fingerprint windows are possible:
        // the clip's start AND its middle, since many reels open with speech.
        val pcmSize = minOf(MAX_TOTAL_PCM_BYTES, (outFile.length() - WAV_HEADER_BYTES).coerceAtLeast(0L)).toInt()
        val buffer = ByteBuffer.allocate(pcmSize)
        outFile.inputStream().use { input ->
          // Discard the WAV container header — only raw PCM goes to the fingerprinter.
          val header = ByteArray(WAV_HEADER_BYTES.toInt())
          var headerRead = 0
          while (headerRead < header.size) {
            val r = input.read(header, headerRead, header.size - headerRead)
            if (r <= 0) break
            headerRead += r
          }
          val bytes = ByteArray(16 * 1024)
          while (buffer.hasRemaining()) {
            val read = input.read(bytes, 0, minOf(bytes.size, buffer.remaining()))
            if (read <= 0) break
            buffer.put(bytes, 0, read)
          }
        }
        val data = ByteArray(buffer.position())
        System.arraycopy(buffer.array(), 0, data, 0, data.size)
        if (data.size < 32_000) { // ~0.5 s of audio is too short to fingerprint
          Timber.tag("ReelMatcher").w("PCM excerpt too short: %d bytes", data.size)
          null
        } else {
          data
        }
      } catch (e: YoutubeDLException) {
        Timber.tag("ReelMatcher").e(e, "yt-dlp audio extraction failed")
        ReelImportState.report(ReelImportStage.YTDLP_ERROR)
        null
      } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Timber.tag("ReelMatcher").e(e, "Audio extraction failed unexpectedly")
        null
      } finally {
        outFile?.delete()
        // yt-dlp may leave sibling files from interrupted runs.
        context.cacheDir.listFiles()?.filter { it.name.startsWith("reel_excerpt_") }?.forEach { it.delete() }
      }
    }

  /**
   * Fetches Instagram's web app embed page for the reel and extracts the official audio
   * attribution ("audio attributed to …" or the artist line Instagram renders).
   * Returns null when unavailable — never guesses from creator captions.
   */
  private suspend fun fetchOfficialAttribution(reelUrl: String): String? =
    withContext(Dispatchers.IO) {
      runCatching {
        withTimeoutOrNull(ATTRIBUTION_TIMEOUT_SECONDS.seconds) {
          val requestBuilder =
            okhttp3.Request.Builder()
              .url(reelUrl)
              .header("User-Agent", IG_BROWSER_USER_AGENT)
          // A session cookie avoids the login wall that strips audio attribution.
          sessionCookie()?.let { requestBuilder.header("Cookie", it) }
          val request = requestBuilder.build()
          httpClient
            .newCall(request)
            .execute()
            .use { response ->
              if (!response.isSuccessful) return@withTimeoutOrNull null
              // Only parse HTML pages: Instagram sometimes answers API-style requests
              // with JSON ("application/json") bodies that contain no attribution.
              val contentType = response.header("Content-Type").orEmpty()
              if (!contentType.contains("text/html", ignoreCase = true)) {
                Timber.tag("ReelMatcher").d("Attribution fetch got non-HTML response (%s)", contentType)
                return@withTimeoutOrNull null
              }
              val body = response.body?.string().orEmpty().trimStart()
              if (!body.startsWith("<")) return@withTimeoutOrNull null
              ReelTitleParser.extractOfficialAttribution(body)
            }
        }
      }
        .onFailure { Timber.tag("ReelMatcher").d(it, "Attribution fetch failed for %s", reelUrl) }
        .getOrNull()
    }

  /**
   * Instagram's official audio metadata via the app's `media/info` endpoint.
   *
   * Returns the `(track, artist, album)` triple from `clips_metadata.music_info` when
   * the reel uses licensed/library audio; null for original audio or when the endpoint
   * is unavailable (401/403 from some IPs/networks) — callers then fall through to
   * the next stage. This is the same data the Instagram app shows on the audio page.
   */
  private suspend fun fetchOfficialMetadataApi(reelUrl: String): Triple<String, String?, String?>? {
    val mediaId = shortcodeOf(reelUrl)?.let { shortcodeToMediaId(it) } ?: return null
    return runCatching {
      withTimeoutOrNull(ATTRIBUTION_TIMEOUT_SECONDS.seconds) {
        val requestBuilder =
          okhttp3.Request.Builder()
            .url("https://i.instagram.com/api/v1/media/$mediaId/info/")
            .header("User-Agent", IG_APP_USER_AGENT)
            .header("x-ig-app-id", IG_WEB_APP_ID)
        // The session cookie is what turns a datacenter-style 403 into app-grade access.
        // The app API wants both sessionid and ds_user_id (the numeric account id).
        sessionCookie()?.let { requestBuilder.header("Cookie", it) }
        val request = requestBuilder.build()
        httpClient
          .newCall(request)
          .execute()
          .use { response ->
            if (!response.isSuccessful) {
              Timber.tag("ReelMatcher").d("IG media/info returned HTTP %d", response.code)
              return@withTimeoutOrNull null
            }
            val body = response.body?.string().orEmpty()
            val json = org.json.JSONObject(body)
            val item = json.optJSONArray("items")?.optJSONObject(0) ?: return@withTimeoutOrNull null
            val clips = item.optJSONObject("clips_metadata")
            val musicInfo = clips?.optJSONObject("music_info")?.optJSONObject("music_metadata")?.optJSONObject("music_info")
            val song = musicInfo?.optString("song_name").orEmpty().ifBlank { null }
            val artist = musicInfo?.optString("artist_name").orEmpty().ifBlank { null }
            val album = musicInfo?.optString("album_name").orEmpty().ifBlank { null }
            if (song.isNullOrBlank() && artist.isNullOrBlank()) {
              // Original sound with no resolvable song: not usable as official metadata.
              return@withTimeoutOrNull null
            }
            Triple(song ?: "", artist, album)
          }
      }
    }
      .onFailure { Timber.tag("ReelMatcher").d(it, "IG media/info fetch failed for %s", reelUrl) }
      .getOrNull()
  }

  /** Instagram shortcode → numeric media id (base64url alphabet). */
  private fun shortcodeToMediaId(shortcode: String): String? {
    if (shortcode.isEmpty()) return null
    var id = 0L
    for (char in shortcode) {
      val value =
        when (char) {
          in 'A'..'Z' -> char - 'A'
          in 'a'..'z' -> char - 'a' + 26
          in '0'..'9' -> char - '0' + 52
          '-' -> 62
          '_' -> 63
          else -> return null
        }
      id = id * 64L + value
    }
    return id.toString()
  }

  /**
 * Maps a Shazam match to the same track on YouTube Music. The fingerprinted track is an* exact identification, so the top YT Music result is confirmed directly; the picked
   * track's name is what the confirmation screen shows.
   */
  private suspend fun resolveOnYouTubeMusic(recognition: RecognitionResult): MatchResult.Matched? {
    val query = "${recognition.title} ${recognition.artist}".trim()
    if (query.isBlank()) return null
    val song = searchYouTubeMusic(query).orEmpty().firstOrNull() ?: return null
    val label = listOfNotNull(recognition.title, recognition.artist).joinToString(" · ").trim()
    return MatchResult.Matched(song, recognition, label)
  }

  /**
   * Searches YouTube Music's song filter.
   *
   * Returns `null` when the search could not be completed (timeout / network error) so
   * callers can distinguish "no results" from "YouTube Music unreachable" — the latter
   * must not be mistaken for proof that the song does not exist. Bounded by
   * [SEARCH_TIMEOUT_SECONDS] so a stalled request can never hang the pipeline.
   */
  private suspend fun searchYouTubeMusic(query: String): List<SongItem>? {
    if (query.isBlank()) return emptyList()
    return withTimeoutOrNull(SEARCH_TIMEOUT_SECONDS.seconds) {
      runCatching { YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow() }
        .onFailure { reportException(it) }
        .getOrNull()
        ?.items
        ?.filterIsInstance<SongItem>()
        .orEmpty()
    }
  }
}
