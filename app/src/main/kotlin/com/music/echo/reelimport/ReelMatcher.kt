package echo.music.iad1tya.reelimport

import android.content.Context
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.shazamkit.models.RecognitionResult
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.YoutubeDLRequest
import echo.music.iad1tya.recognition.AudioResampler
import echo.music.iad1tya.recognition.DecodedAudio
import echo.music.iad1tya.recognition.VibraSignature
import echo.music.iad1tya.utils.reportException
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
 * Pipeline (audio fingerprint first, metadata second — captions are never used):
 * 1. The reel's own audio is fingerprinted with the same [VibraSignature] used by Echo Find
 *    and identified through Shazam. This needs no account and is an exact match.
 * 2. Official track/artist/album metadata (yt-dlp `--dump-json`, Instagram's app media-info
 *    endpoint, the embed page's attribution line) is searched on YouTube Music as a fallback.
 * 3. As a last resort the creator's caption is mined for a song name and searched, but its
 *    results only ever reach the picker — captions misname songs too often to auto-confirm.
 * 4. Anything else lands in the manual-search picker, prefilled with the best song-name hint
 *    available (never the uploader's handle).
 *
 * Fingerprinting runs first on purpose: the metadata Instagram exposes anonymously (yt-dlp's
 * `track`/`artist`) is frequently the creator's *original sound* name, which produced convincing
 * but wrong "garbage" matches before. Shazam's answer is authoritative, so it decides first.
 *
 * A public [search] is exposed so the UI always offers a manual escape hatch.
 */
object ReelMatcher {

  /**
   * One fingerprint window: ~25 s of mono 16-bit PCM @ 16 kHz. The signature generator
   * stops after ~12 s (or 255 peaks, whichever comes first), so the extra room only
   * helps sparse audio; it never hurts.
   */
  private const val MAX_EXCERPT_BYTES = 800_000

  /** Total audio kept for the two fingerprint windows (start + middle): ~50 s of mono. */
  private const val MAX_TOTAL_PCM_BYTES = 1_600_000L

  /**
   * Audio format the excerpt is converted into before fingerprinting.
   *
   * Mono is REQUIRED: the Shazam signature is built from a single 16 kHz channel, exactly
   * like Echo Find's microphone path. Feeding 16 kHz interleaved stereo (as this used to)
   * doubles the sample count, warps the timeline and shifts the spectrogram, so Shazam can
   * never match it — every reel fell through to the metadata/caption guesses instead.
   */
  private const val PCM_SAMPLE_RATE = 16_000
  private const val PCM_CHANNELS = 1

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

  /** One shared client for every Instagram call — building a client per request is pure waste. */
  private val httpClient: okhttp3.OkHttpClient by lazy {
    okhttp3.OkHttpClient.Builder()
      .connectTimeout(ATTRIBUTION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      .readTimeout(ATTRIBUTION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
      .build()
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

  /**
   * Official audio metadata for a reel: the YouTube Music queries to try, plus the raw
   * track/artist/album the queries were built from. The raw fields are kept so the
   * candidates a query returns can be ranked by how well they match the reel's own
   * metadata — metadata matches are not acoustically verified.
   */
  private data class OfficialMetadata(
    val queries: List<String>,
    val track: String? = null,
    val artist: String? = null,
    val album: String? = null,
  )

  /** One-time native binary setup. Call from Application startup. */
  fun init(context: Context) {
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

      // Reel metadata is fetched once, up front, so the "reading the reel" step is shown
      // before fingerprinting. Its failure is NOT fatal: yt-dlp/site extractors drift and
      // the metadata is only ever used by the weaker fallback stages, so the fingerprint
      // (the reel's own sound, via Shazam) still runs and can decide on its own.
      val info = fetchReelInfo(reelUrl)

      var searchFailed = false
      var officialMetadata: OfficialMetadata? = null
      // The official-metadata lookup costs network calls (app API + embed page), so it
      // is resolved lazily and only once, on the first stage that needs it.
      suspend fun official(): OfficialMetadata {
        officialMetadata?.let { return it }
        val reel = info ?: return OfficialMetadata(emptyList())
        return collectOfficialMetadata(reelUrl, reel).also { officialMetadata = it }
      }

      // Stage 1 — fingerprint the reel's actual audio and identify it with Shazam. This
      // needs no account and is an exact match, so it decides first: the
      // metadata Instagram exposes anonymously is often the creator's original-sound
      // name, which used to produce convincing but wrong candidates.
      ReelImportState.report(ReelImportStage.EXTRACTING)
      val fingerprintResult = runCatching { recognizeByFingerprint(context, reelUrl) }
      val recognition = fingerprintResult.getOrNull()
      fingerprintResult.exceptionOrNull()?.let {
        Timber.tag("ReelMatcher").e(it, "Fingerprinting failed")
      }
      if (recognition != null) {
        ReelImportState.report(ReelImportStage.MATCHING)
        val label = listOfNotNull(recognition.title, recognition.artist).joinToString(" · ").trim()
        ReelImportState.update(reelUrl) { it.copy(searchQuery = label) }
        val song =
          searchYouTubeMusic(listOfNotNull(recognition.title, recognition.artist).joinToString(" ").trim())
            .orEmpty()
            .firstOrNull()
        if (song != null) {
          Timber.tag("ReelMatcher").i("Fingerprint identified: %s", label)
          return@withContext MatchResult.Matched(song, recognition, label)
        }
        // Identified by Shazam but not resolvable on YouTube Music right now: hand the
        // identified name to the picker instead of downgrading to weaker metadata guesses.
        val candidates = searchYouTubeMusic(recognition.title).orEmpty()
        return@withContext MatchResult.TitleFallback(candidates, label)
      }

      // Stage 2 — official audio metadata, searched on YouTube Music. Fast and
      // authoritative when present (licensed audio).
      if (!searchFailed) {
        val metadata = official()
        if (metadata.queries.isNotEmpty()) {
          ReelImportState.report(ReelImportStage.MATCHING)
          val hit =
            searchQueries(metadata.queries.take(MAX_METADATA_QUERIES), reelUrl) { searchFailed = true }
          if (hit != null) {
            Timber.tag("ReelMatcher").i("Official query \"%s\" produced candidates", hit.second)
            // Metadata candidates are not fingerprinted, so identical titles from
            // different films collide (e.g. "Bulleya" from two soundtracks). Rank them
            // by the reel's own album/artist so the right one comes first; the user
            // still confirms, so this only orders the picker.
            val ranked = rankByMetadata(hit.first, metadata.album, metadata.artist, metadata.track)
            return@withContext MatchResult.TitleFallback(ranked, hit.second)
          }
        }
      }

      // The reel itself could not be read at all (yt-dlp failed) and fingerprinting found
      // nothing — surface the retry/manual-search failure screen instead of an empty
      // picker. If the reel loaded but only the match was inconclusive, the picker (with
      // its prefilled hint) is the better destination.
      val reel = info ?: return@withContext MatchResult.Error("Reel could not be fetched")

      // Stage 3 — last look at the creator's own text. A caption song marker often names
      // the track; results only reach the picker as candidates, never as an auto-match.
      if (!searchFailed) {
        val captionQueries = ReelTitleParser.rankedQueryCandidates(reel.title, reel.caption).second
        val hit = searchQueries(captionQueries.take(MAX_CAPTION_QUERIES), reelUrl) { searchFailed = true }
        if (hit != null) {
          Timber.tag("ReelMatcher").i("Caption query \"%s\" produced candidates", hit.second)
          return@withContext MatchResult.TitleFallback(hit.first, hit.second)
        }
      }

      // Nothing identified. Prefill the manual search with the best song-name hint we
      // have: official attribution first, then the caption, and never the uploader
      // handle (the picker prefills its search box from this).
      val hint =
        officialMetadata?.queries?.firstOrNull()?.takeIf { it.isNotBlank() }
          ?: ReelTitleParser.songNameHint(reel.title, reel.caption)
      // Nothing identified automatically: always hand off to the picker, prefilled with the
      // best song-name hint we have (never a dead-end login wall).
      MatchResult.TitleFallback(emptyList(), hint)
    }

  /**
   * Runs [queries] against YouTube Music in order, returning the first non-empty result
   * (candidates + the query that produced them). [onSearchFailure] is invoked when a query
   * could not be completed at all (network/timeout) — search failure must never be mistaken
   * for "the song does not exist".
   */
  private suspend fun searchQueries(
    queries: List<String>,
    reelUrl: String,
    onSearchFailure: () -> Unit,
  ): Pair<List<SongItem>, String>? {
    for (query in queries) {
      if (query.isBlank()) continue
      // Show the live query so long stages are never a blind spinner.
      ReelImportState.update(reelUrl) { it.copy(searchQuery = query) }
      val candidates = searchYouTubeMusic(query)
      when {
        candidates == null -> onSearchFailure()
        candidates.isNotEmpty() -> return candidates to query
      }
    }
    return null
  }

  /**
   * Official audio metadata for the reel: yt-dlp's track/artist/album fields first, then
   * (only when yt-dlp has nothing) Instagram's app media-info endpoint and finally the
   * embed page's attribution line. Never falls back to creator captions.
   */
  private suspend fun collectOfficialMetadata(reelUrl: String, info: ReelInfo): OfficialMetadata {
    val ytDlpQueries = ReelTitleParser.officialQueryCandidates(info.track, info.artist, info.album)
    if (ytDlpQueries.isNotEmpty()) {
      return OfficialMetadata(ytDlpQueries, info.track, info.artist, info.album)
    }

    ReelImportState.report(ReelImportStage.FETCHING_METADATA)
    val apiTriple = fetchOfficialMetadataApi(reelUrl)
    if (apiTriple != null) {
      val (song, artist, album) = apiTriple
      val queries = ReelTitleParser.officialQueryCandidates(song, artist, album)
      if (queries.isNotEmpty()) return OfficialMetadata(queries, song, artist, album)
    }

    val attribution = fetchOfficialAttribution(reelUrl)
    return OfficialMetadata(
      queries = attribution?.let { listOf(it, "$it artist") }.orEmpty(),
      artist = attribution,
    )
  }

  /**
   * Orders metadata-derived candidates so the ones matching the reel's own audio
   * metadata come first. Metadata matches are not acoustically verified, so two
   * different songs can share a title ("Bulleya" from two films); the album is the
   * strongest discriminator, then the artist, then an exact title match. The sort is
   * stable, so YouTube Music's own relevance order survives for ties.
   */
  private fun rankByMetadata(
    songs: List<SongItem>,
    album: String?,
    artist: String?,
    track: String?,
  ): List<SongItem> {
    fun key(value: String?): String = value.orEmpty().lowercase().filter { it.isLetterOrDigit() }
    val albumKey = key(album)
    val artistKey = key(artist)
    val trackKey = key(track)
    if (albumKey.isBlank() && artistKey.isBlank()) return songs
    return songs.sortedByDescending { song ->
      var score = 0
      val songAlbum = key(song.album?.name)
      if (
        albumKey.isNotBlank() &&
          songAlbum.isNotBlank() &&
          (songAlbum.contains(albumKey) || albumKey.contains(songAlbum))
      ) {
        score += 3
      }
      val songArtist = key(song.artists.joinToString(" ") { it.name })
      if (artistKey.isNotBlank() && songArtist.contains(artistKey)) score += 2
      if (trackKey.isNotBlank() && key(song.title) == trackKey) score += 1
      score
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

  /** Fingerprints one PCM window (16-bit LE mono @ 16 kHz) with Shazam. */
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
      var workDir: File? = null
      try {
        // A fresh directory per run: yt-dlp treats an existing output file as an
        // already-completed download and skips it, so the target must not pre-exist.
        val dir = File(context.cacheDir, "reel_excerpt_${System.nanoTime()}").apply { mkdirs() }
        workDir = dir
        val request = YoutubeDLRequest(url)
        request.addOption("-x")
        // `s16le` is not a valid --audio-format; WAV decodes to PCM 16-bit little-endian.
        request.addOption("--audio-format", "wav")
        // Rate/channel conversion is not an yt-dlp flag — pass it to the bundled ffmpeg.
        // `-ac 1` downmixes to the mono 16 kHz PCM the Shazam signature requires.
        request.addOption(
          "--postprocessor-args",
          "ffmpeg:-ar $PCM_SAMPLE_RATE -ac $PCM_CHANNELS",
        )
        request.addOption("-o", File(dir, "audio.%(ext)s").absolutePath)
        request.addOption("--no-playlist")
        request.addOption("--no-part")
        request.addOption("--socket-timeout", SOCKET_TIMEOUT_SECONDS)
        request.addOption("--retries", YTDLP_RETRIES)
        // Public reels are usually downloadable anonymously; private ones are not.
        request.addOption("--no-check-certificates")
        request.addOption("--user-agent", IG_BROWSER_USER_AGENT)

        // The library injects --ffmpeg-location automatically for every execution.
        YoutubeDL.getInstance().execute(request)

        // yt-dlp names the extracted audio after the container (`.wav`).
        val produced = dir.listFiles().orEmpty()
        val wavFile =
          produced.firstOrNull { it.length() > 0L && it.extension.equals("wav", ignoreCase = true) }
            ?: produced.firstOrNull { it.length() > 0L }
        if (wavFile == null) {
          Timber.tag("ReelMatcher").w("PCM excerpt was not produced")
          return@withContext null
        }
        Timber.tag("ReelMatcher").i("PCM excerpt ready: %d bytes", wavFile.length())

        // Read the whole PCM payload (capped) so two fingerprint windows are possible:
        // the clip's start AND its middle, since many reels open with speech.
        val pcmSize = minOf(MAX_TOTAL_PCM_BYTES, (wavFile.length() - WAV_HEADER_BYTES).coerceAtLeast(0L)).toInt()
        val buffer = ByteBuffer.allocate(pcmSize)
        wavFile.inputStream().use { input ->
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
        // Clean this run's directory and any siblings left by interrupted runs.
        workDir?.deleteRecursively()
        context.cacheDir.listFiles()?.filter { it.name.startsWith("reel_excerpt_") }?.forEach { it.deleteRecursively() }
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
