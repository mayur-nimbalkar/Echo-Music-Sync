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
 * Pipeline (official metadata first, audio second — captions are never used):
 * 1. yt-dlp fetches reel metadata (`--dump-json`); if the official audio attribution is missing
 *    there, Instagram's web app embed page is fetched for it ("audio attributed to …").
 * 2. Official track/artist/album metadata is searched on YouTube Music. Fast, and authoritative
 *    when present — Instagram knows exactly which audio the reel uses.
 * 3. When no official metadata exists (original audio), a short excerpt is fingerprinted with
 *    the same [VibraSignature] used by Echo Find.
 * 4. Anything else lands in the manual-search picker — creator captions are deliberately
 *    ignored because they routinely misname or omit the song.
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

  /** Timeout for the Instagram embed-page attribution fetch (seconds). */
  private const val ATTRIBUTION_TIMEOUT_SECONDS = 8L

  /** Reel URLs accepted by the importer. */
  private val REEL_URL_REGEX =
    Regex(
      "^https?://(?:www\\.|m\\.)?instagram\\.com/(?:reel|reels|p)/[A-Za-z0-9_-]+",
      RegexOption.IGNORE_CASE,
    )

  fun isSupportedReelUrl(url: String?): Boolean =
    url != null && REEL_URL_REGEX.containsMatchIn(url)

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

    /** Reel exists but no song could be identified. */
    data object NoMatch : MatchResult()

    /** yt-dlp is unavailable (first-run binary update failed) or extraction failed. */
    data class Error(val message: String) : MatchResult()
  }

  /** Parsed reel metadata used by the UI. */
  data class ReelInfo(
    val url: String,
    val title: String,
    val uploader: String?,
    val thumbnailUrl: String?,
    val durationSeconds: Int,
    /** Full caption/description when available. Not used for matching (unreliable). */
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
      // attribution), optionally enriched from Instagram's embed page. Captions are
      // deliberately NOT used — creators routinely misname or omit the song.
      val ytDlpOfficial = ReelTitleParser.officialQueryCandidates(info.track, info.artist, info.album)
      val officialQueries =
        ytDlpOfficial.ifEmpty {
          // Attribution missing from yt-dlp JSON (common for most reels): try Instagram's
          // own embed page, which carries the official audio-artist line.
          val attribution =
            run {
              ReelImportState.report(ReelImportStage.FETCHING_METADATA)
              fetchOfficialAttribution(reelUrl)
            }
          if (attribution != null) listOf(attribution) else emptyList()
        }

      // Stage 1 — search official audio metadata.
      var searchFailed = false
      if (officialQueries.isNotEmpty()) {
        ReelImportState.report(ReelImportStage.MATCHING)
        for (query in officialQueries.take(MAX_METADATA_QUERIES)) {
          // Show the live query so long stages are never a blind spinner.
          ReelImportState.update { it.copy(searchQuery = query) }
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
          val recognitionLabel =
            listOfNotNull(recognition.title, recognition.artist).joinToString(" · ").trim()
          val resolved = resolveOnYouTubeMusic(recognition, recognitionLabel)
          if (resolved != null) return@withContext resolved
          // Fingerprint matched a song that isn't on YT Music — search its name directly
          // (the placeholder reel title would only shadow it).
          val query = "${recognition.title} ${recognition.artist}".trim()
          val candidates = searchYouTubeMusic(query).orEmpty()
          if (candidates.isNotEmpty()) {
            return@withContext MatchResult.TitleFallback(candidates, recognitionLabel)
          }
        } else {
          fingerprintResult.exceptionOrNull()?.let {
            Timber.tag("ReelMatcher").e(it, "Fingerprinting failed")
          }
        }
      } else {
        Timber.tag("ReelMatcher").w("YouTube Music unreachable — skipping extraction/fingerprinting")
      }

      // Stage 3 — captions are never used for matching. Hand the official attribution
      // (when known) to the picker so manual search starts from something meaningful.
      val hint = officialQueries.firstOrNull().orEmpty()
      MatchResult.TitleFallback(emptyList(), hint.ifBlank { info.title })
    }

  /** Manual search used by the UI so a failed match never dead-ends the flow. */
  suspend fun search(query: String): List<SongItem> = searchYouTubeMusic(query.trim()).orEmpty()

  /** Fetches reel metadata with yt-dlp (no media download). */
  private suspend fun fetchReelInfo(url: String): ReelInfo? =
    withContext(Dispatchers.IO) {
      ReelImportState.report(ReelImportStage.FETCHING_METADATA)
      runCatching {
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
            url = url,
            title = json.optString("fulltitle").ifBlank { json.optString("title") },
            uploader = json.optString("uploader").ifBlank { null },
            thumbnailUrl = json.optString("thumbnail").ifBlank { null },
            durationSeconds = json.optInt("duration", 0),
            // Keep the full caption: song markers like `song:` often sit at the end.
            caption = json.optString("description").ifBlank { null },
            track = json.optString("track").ifBlank { null },
            artist = json.optString("artist").ifBlank { null },
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

    return com.music.shazamkit.Shazam.recognize(signature, sampleDurationMs).getOrNull()
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
        request.addOption("--user-agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")

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
          val request = okhttp3.Request.Builder().url(reelUrl).header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36").build()
          okhttp3.OkHttpClient.Builder()
            .connectTimeout(ATTRIBUTION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(ATTRIBUTION_TIMEOUT_SECONDS, java.util.concurrent.TimeUnit.SECONDS)
            .build()
            .newCall(request)
            .execute()
            .use { response ->
              if (!response.isSuccessful) return@withTimeoutOrNull null
              ReelTitleParser.extractOfficialAttribution(response.body?.string().orEmpty())
            }
        }
      }
        .onFailure { Timber.tag("ReelMatcher").d(it, "Attribution fetch failed for %s", reelUrl) }
        .getOrNull()
    }

  /** Maps a Shazam match to the same track on YouTube Music. */
  private suspend fun resolveOnYouTubeMusic(
    recognition: RecognitionResult,
    reelTitle: String,
  ): MatchResult.Matched? {
    val query = "${recognition.title} ${recognition.artist}".trim()
    if (query.isBlank()) return null
    val song = searchYouTubeMusic(query).orEmpty().firstOrNull() ?: return null
    return MatchResult.Matched(song, recognition, reelTitle)
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
