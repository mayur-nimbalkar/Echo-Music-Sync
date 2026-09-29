package echo.music.iad1tya.reelimport

import android.content.Context
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.shazamkit.models.RecognitionResult
import com.yausername.youtubedl_android.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.YoutubeDLException
import com.yausername.youtubedl_android.mapper.VideoInfo
import echo.music.iad1tya.recognition.AudioResampler
import echo.music.iad1tya.recognition.DecodedAudio
import echo.music.iad1tya.recognition.VibraSignature
import echo.music.iad1tya.utils.reportException
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Identifies the background music of an Instagram Reel and resolves it to a YouTube Music track.
 *
 * Pipeline:
 * 1. yt-dlp fetches reel metadata (`--dump-json`) — title, uploader, thumbnail.
 * 2. yt-dlp extracts a short audio excerpt (`-x --audio-format s16le`).
 * 3. The excerpt is resampled to 16 kHz mono and fingerprinted with the same
 *    [VibraSignature] used by Echo Find.
 * 4. Shazam matches the fingerprint; the match is resolved on YouTube Music via InnerTube search
 *    (falling back to a title-based search when fingerprinting fails).
 */
object ReelMatcher {

  /** How much audio we decode for fingerprinting (from the start of the track). */
  private const val MAX_EXCERPT_BYTES = 800_000 // ~12.5 s of 16-bit stereo @ 16 kHz

  /** Audio format the excerpt is converted into before fingerprinting. */
  private const val PCM_SAMPLE_RATE = 16_000
  private const val PCM_CHANNELS = 2

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

    /** Fingerprinting failed, but a title-based YouTube Music search found candidates. */
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
   * Runs the full identification pipeline. All work happens on [Dispatchers.IO].
   * Throws nothing — every outcome is modelled in [MatchResult].
   */
  suspend fun match(context: Context, reelUrl: String): MatchResult =
    withContext(Dispatchers.IO) {
      if (!isSupportedReelUrl(reelUrl)) return@withContext MatchResult.NotAReel

      val info =
        fetchReelInfo(reelUrl)
          ?: return@withContext MatchResult.Error("Reel could not be fetched")

      ReelImportState.report(ReelImportStage.MATCHING)

      val fingerprintResult = runCatching { recognizeByFingerprint(context, reelUrl) }
      val recognition = fingerprintResult.getOrNull()

      if (recognition != null) {
        // Prefer the fingerprint match resolved on YouTube Music.
        resolveOnYouTubeMusic(recognition, info.title) ?: fallbackToTitle(info.title, recognition.title)
      } else {
        fingerprintResult.exceptionOrNull()?.let {
          Timber.tag("ReelMatcher").e(it, "Fingerprinting failed; falling back to title search")
        }
        fallbackToTitle(info.title, null)
      }
    }

  /** Title-based YouTube Music search used when fingerprinting is unavailable. */
  private suspend fun fallbackToTitle(reelTitle: String, hint: String?): MatchResult {
    val query = ReelTitleParser.clean(reelTitle).ifBlank { hint.orEmpty() }
    val candidates = searchYouTubeMusic(query)
    return if (candidates.isEmpty()) MatchResult.NoMatch
    else MatchResult.TitleFallback(candidates, reelTitle)
  }

  /** Fetches reel metadata with yt-dlp (no media download). */
  private suspend fun fetchReelInfo(url: String): ReelInfo? =
    withContext(Dispatchers.IO) {
      runCatching {
        val request = YoutubeDLRequest(url)
        request.addOption("--dump-json")
        request.addOption("--no-playlist")
        request.addOption("--socket-timeout", 15)
        val response = YoutubeDL.getInstance().execute(request)
        if (response.exitCode != 0 && response.out.isBlank()) return@runCatching null
        val info = YoutubeDL.objectMapper.readValue(response.out, VideoInfo::class.java)
        ReelInfo(
          url = url,
          title = info.fulltitle ?: info.title ?: "",
          uploader = info.uploader,
          thumbnailUrl = info.thumbnail,
          durationSeconds = info.duration,
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
    val sampleDurationMs =
      (resampled.data.size / 2) * 1000L / VibraSignature.REQUIRED_SAMPLE_RATE

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
        request.addOption("--socket-timeout", 15)

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
        ensureActive()
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
    return runCatching { YouTube.search(query, YouTube.SearchFilter.FILTER_SONG) }
      .onFailure { reportException(it) }
      .getOrNull()
      ?.items
      ?.filterIsInstance<SongItem>()
      .orEmpty()
  }
}
