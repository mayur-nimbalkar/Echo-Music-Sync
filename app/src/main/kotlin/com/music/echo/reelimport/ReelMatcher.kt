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
import timber.log.Timber

/**
 * Identifies the background music of an Instagram Reel and resolves it to YouTube Music tracks.
 *
 * Pipeline (metadata-first, fast by default):
 * 1. yt-dlp fetches reel metadata (`--dump-json`) — caption/title, track/artist fields, thumbnail.
 * 2. Song hints are mined from the caption (`song:` markers, ♪ fragments, quoted lyrics, track
 *    fields) and searched on YouTube Music. This usually completes in seconds with no download.
 * 3. Only when the caption yields nothing does it fall back to extracting a short audio excerpt
 *    and fingerprinting it with the same [VibraSignature] used by Echo Find.
 *
 * A public [search] is exposed so the UI always offers a manual escape hatch.
 */
object ReelMatcher {

  /** How much audio we decode for fingerprinting (from the start of the track). */
  private const val MAX_EXCERPT_BYTES = 800_000 // ~12.5 s of 16-bit stereo @ 16 kHz

  /** Audio format the excerpt is converted into before fingerprinting. */
  private const val PCM_SAMPLE_RATE = 16_000
  private const val PCM_CHANNELS = 2

  /** yt-dlp network timeouts in seconds. */
  private const val SOCKET_TIMEOUT_SECONDS = 15

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
    /** Full caption/description when available; often names the song. */
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
      Timber.i("ReelMatcher: yt-dlp initialized")
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

      // Stage 1 — metadata: Instagram track fields, then mined caption hints.
      val metadataQueries = buildList {
        info.track?.let { track ->
          add(listOfNotNull(track, info.artist, info.album).joinToString(" ").trim())
        }
        addAll(ReelTitleParser.queryCandidates(info.title, info.caption))
      }
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

      if (metadataQueries.isNotEmpty()) {
        ReelImportState.report(ReelImportStage.MATCHING)
        for (query in metadataQueries) {
          val candidates = searchYouTubeMusic(query)
          if (candidates.isNotEmpty()) {
            Timber.tag("ReelMatcher").i("Metadata query \"%s\" matched: %s", query, candidates.first().title)
            return@withContext MatchResult.TitleFallback(candidates, info.title)
          }
        }
      }

      // Stage 2 — audio: fingerprint a short excerpt only when metadata failed.
      ReelImportState.report(ReelImportStage.EXTRACTING)
      val fingerprintResult = runCatching { recognizeByFingerprint(context, reelUrl) }
      val recognition = fingerprintResult.getOrNull()

      if (recognition != null) {
        // Prefer the fingerprint match resolved on YouTube Music.
        resolveOnYouTubeMusic(recognition, info.title) ?: fallbackToTitle(info.title, recognition.title)
      } else {
        fingerprintResult.exceptionOrNull()?.let {
          Timber.tag("ReelMatcher").e(it, "Fingerprinting failed")
        }
        fallbackToTitle(info.title, null)
      }
    }

  /** Manual search used by the UI so a failed match never dead-ends the flow. */
  suspend fun search(query: String): List<SongItem> = searchYouTubeMusic(query.trim())

  /** Title-based YouTube Music search used when the pipeline has nothing better. */
  private suspend fun fallbackToTitle(reelTitle: String, hint: String?): MatchResult {
    val query = ReelTitleParser.clean(reelTitle).ifBlank { hint.orEmpty() }
    val candidates = searchYouTubeMusic(query)
    return if (candidates.isEmpty()) MatchResult.NoMatch else MatchResult.TitleFallback(candidates, reelTitle)
  }

  /** Fetches reel metadata with yt-dlp (no media download). */
  private suspend fun fetchReelInfo(url: String): ReelInfo? =
    withContext(Dispatchers.IO) {
      ReelImportState.report(ReelImportStage.FETCHING_METADATA)
      runCatching {
          val request = YoutubeDLRequest(url)
          request.addOption("--dump-json")
          request.addOption("--no-playlist")
          request.addOption("--socket-timeout", SOCKET_TIMEOUT_SECONDS)
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
   * Downloads a short audio excerpt and recognizes it with Shazam.
   * Returns null when extraction/fingerprinting infrastructure is unavailable —
   * callers then fall back to a title-based search.
   */
  private suspend fun recognizeByFingerprint(context: Context, url: String): RecognitionResult? {
    val pcm = extractPcmExcerpt(context, url) ?: return null

    ReelImportState.report(ReelImportStage.LISTENING)

    val decoded = DecodedAudio(pcm, PCM_CHANNELS, PCM_SAMPLE_RATE, android.media.AudioFormat.ENCODING_PCM_16BIT)
    val resampled =
      AudioResampler.resample(decoded, VibraSignature.REQUIRED_SAMPLE_RATE).getOrElse { error ->
        Timber.tag("ReelMatcher").w(error, "Resampling excerpt failed")
        return null
      }

    val signature = VibraSignature.fromI16(resampled.data)
    val sampleDurationMs = (resampled.data.size / 2) * 1000L / VibraSignature.REQUIRED_SAMPLE_RATE

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
        outFile = File.createTempFile("reel_excerpt_", ".pcm", context.cacheDir)
        val request = YoutubeDLRequest(url)
        request.addOption("-x")
        request.addOption("--audio-format", "s16le")
        request.addOption("--audio-channels", PCM_CHANNELS)
        request.addOption("--audio-samplerate", PCM_SAMPLE_RATE)
        request.addOption("-o", outFile.absolutePath.substringBeforeLast(".pcm") + ".%(ext)s")
        request.addOption("--no-playlist")
        request.addOption("--no-part")
        request.addOption("--socket-timeout", SOCKET_TIMEOUT_SECONDS)
        // Public reels are usually downloadable anonymously; private ones are not.
        request.addOption("--no-check-certificates")
        request.addOption("--user-agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")

        // The library injects --ffmpeg-location automatically for every execution.
        YoutubeDL.getInstance().execute(request)

        if (!outFile.exists() || outFile.length() == 0L) {
          Timber.tag("ReelMatcher").w("PCM excerpt was not produced")
          return@withContext null
        }

        val excerptSize = minOf(MAX_EXCERPT_BYTES.toLong(), outFile.length()).toInt()
        val buffer = ByteBuffer.allocate(excerptSize)
        outFile.inputStream().use { input ->
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

  /** Maps a Shazam match to the same track on YouTube Music. */
  private suspend fun resolveOnYouTubeMusic(
    recognition: RecognitionResult,
    reelTitle: String,
  ): MatchResult.Matched? {
    val query = "${recognition.title} ${recognition.artist}".trim()
    if (query.isBlank()) return null
    val song = searchYouTubeMusic(query).firstOrNull() ?: return null
    return MatchResult.Matched(song, recognition, reelTitle)
  }

  /** Searches YouTube Music's song filter and returns plain [SongItem]s. */
  private suspend fun searchYouTubeMusic(query: String): List<SongItem> {
    if (query.isBlank()) return emptyList()
    return runCatching { YouTube.search(query, YouTube.SearchFilter.FILTER_SONG).getOrThrow() }
      .onFailure { reportException(it) }
      .getOrNull()
      ?.items
      ?.filterIsInstance<SongItem>()
      .orEmpty()
  }
}
