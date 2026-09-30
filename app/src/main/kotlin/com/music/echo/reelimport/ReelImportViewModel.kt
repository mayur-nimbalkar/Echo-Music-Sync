package echo.music.iad1tya.reelimport

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.innertube.models.SongItem
import com.music.shazamkit.models.RecognitionResult
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import echo.music.iad1tya.constants.PlaylistSortType
import echo.music.iad1tya.constants.ReelImportDefaultPlaylistIdKey
import echo.music.iad1tya.db.MusicDatabase
import echo.music.iad1tya.db.entities.Playlist
import echo.music.iad1tya.db.entities.RecognitionHistory
import echo.music.iad1tya.models.toMediaMetadata
import echo.music.iad1tya.utils.dataStore
import java.time.LocalDateTime
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** UI-facing state of the reel import flow. */
sealed class ReelImportUiState {
  data object Idle : ReelImportUiState()
  data class Working(val stage: ReelImportStage) : ReelImportUiState()

  data class AwaitingConfirmation(
    val song: SongItem,
    val recognition: RecognitionResult?,
    val reelTitle: String,
  ) : ReelImportUiState()

  /** Metadata/caption candidates, or an empty list the user can extend by manual search. */
  data class PickCandidate(val candidates: List<SongItem>, val reelTitle: String) : ReelImportUiState()

  /** While a manual/inline search is running. */
  data class Searching(val query: String) : ReelImportUiState()

  data class Finished(val playlistName: String?) : ReelImportUiState()
  data class Failed(val reason: ReelImportStage) : ReelImportUiState()
}

@HiltViewModel
class ReelImportViewModel
@Inject
constructor(
  @ApplicationContext private val context: Context,
  private val database: MusicDatabase,
) : ViewModel() {

  private val _uiState = MutableStateFlow<ReelImportUiState>(ReelImportUiState.Idle)
  val uiState: StateFlow<ReelImportUiState> = _uiState.asStateFlow()

  /** Playlists for the chooser; the same set the Add-to-Playlist dialog shows. */
  val playlists: StateFlow<List<Playlist>> =
    database.playlists(PlaylistSortType.CREATE_DATE, descending = false)
      .stateIn(viewModelScope, SharingStarted.Lazily, emptyList())

  val defaultPlaylistId = MutableStateFlow("")

  /** Kept from the confirmation step so history can be saved after state transitions. */
  private var pendingRecognition: RecognitionResult? = null

  init {
    viewModelScope.launch {
      defaultPlaylistId.value = context.dataStore.data.first()[ReelImportDefaultPlaylistIdKey].orEmpty()
    }

    // Observe fine-grained pipeline progress reported by ReelMatcher.
    viewModelScope.launch {
      ReelImportState.current.collect { pending ->
        val stage = pending?.stage ?: return@collect
        if (
          stage == ReelImportStage.FETCHING_METADATA ||
            stage == ReelImportStage.EXTRACTING ||
            stage == ReelImportStage.LISTENING ||
            stage == ReelImportStage.MATCHING
        ) {
          if (_uiState.value is ReelImportUiState.Idle || _uiState.value is ReelImportUiState.Working) {
            _uiState.value = ReelImportUiState.Working(stage)
          }
        }
      }
    }
  }

  /** Entry point from a shared Instagram link (raw share text or a bare URL). */
  fun startFromLink(rawTextOrUrl: String) {
    val url = ReelTitleParser.extractUrl(rawTextOrUrl) ?: rawTextOrUrl.trim()
    if (!ReelMatcher.isSupportedReelUrl(url)) {
      _uiState.value = ReelImportUiState.Failed(ReelImportStage.NOT_A_REEL)
      return
    }
    if (_uiState.value is ReelImportUiState.Working) return

    pendingRecognition = null
    ReelImportState.begin(url)
    _uiState.value = ReelImportUiState.Working(ReelImportStage.FETCHING_METADATA)

    viewModelScope.launch(Dispatchers.IO) {
      when (val result = ReelMatcher.match(context, url)) {
        is ReelMatcher.MatchResult.Matched -> {
          pendingRecognition = result.recognition
          ReelImportState.update {
            it.copy(reelTitle = result.reelTitle, stage = ReelImportStage.AWAITING_CONFIRM)
          }
          _uiState.value =
            ReelImportUiState.AwaitingConfirmation(
              song = result.song,
              recognition = result.recognition,
              reelTitle = result.reelTitle,
            )
        }
        is ReelMatcher.MatchResult.TitleFallback ->
          _uiState.value = ReelImportUiState.PickCandidate(result.songs, result.reelTitle)
        ReelMatcher.MatchResult.NotAReel ->
          _uiState.value = ReelImportUiState.Failed(ReelImportStage.NOT_A_REEL)
        ReelMatcher.MatchResult.NoMatch ->
          // Nothing matched — show the manual search picker instead of a dead end.
          _uiState.value = ReelImportUiState.PickCandidate(emptyList(), "")
        is ReelMatcher.MatchResult.Error ->
          _uiState.value =
            ReelImportUiState.Failed(
              if (result.message.contains("yt-dlp", ignoreCase = true)) {
                ReelImportStage.YTDLP_ERROR
              } else {
                ReelImportStage.FAILED
              }
            )
      }
    }
  }

  /**
   * User confirmed the match — persist it. Uses the given [playlistId], or the
   * remembered default playlist when null.
   */
  fun confirm(song: SongItem, playlistId: String?) {
    val target = playlistId ?: defaultPlaylistId.value
    if (target.isBlank()) return
    addToPlaylist(song, target)
  }

  /** User picked a candidate from the title fallback list. */
  fun pickCandidate(song: SongItem, playlistId: String?) = confirm(song, playlistId)

  /**
   * Manual search from the candidate picker. Replaces the candidate list so the user
   * can refine repeatedly without leaving the flow. Also lets the user search when
   * automatic identification found nothing.
   */
  fun manualSearch(query: String) {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return
    viewModelScope.launch(Dispatchers.IO) {
      _uiState.value = ReelImportUiState.Searching(trimmed)
      val candidates = ReelMatcher.search(trimmed)
      _uiState.value =
        if (candidates.isEmpty()) {
          // Stay in the picker so the user can refine the search — never a dead end.
          ReelImportUiState.PickCandidate(emptyList(), trimmed)
        } else {
          ReelImportUiState.PickCandidate(candidates, trimmed)
        }
    }
  }

  fun setDefaultPlaylist(id: String) {
    defaultPlaylistId.value = id
    viewModelScope.launch {
      context.dataStore.edit { it[ReelImportDefaultPlaylistIdKey] = id }
    }
  }

  fun clearDefaultPlaylist() {
    defaultPlaylistId.value = ""
    viewModelScope.launch {
      context.dataStore.edit { it[ReelImportDefaultPlaylistIdKey] = "" }
    }
  }

  private fun addToPlaylist(song: SongItem, playlistId: String) {
    viewModelScope.launch(Dispatchers.IO) {
      ReelImportState.report(ReelImportStage.ADDING)
      val playlist = database.getPlaylistById(playlistId)
      if (playlist == null) {
        _uiState.value = ReelImportUiState.Failed(ReelImportStage.FAILED)
        return@launch
      }
      try {
        database.withTransaction {
          insert(song.toMediaMetadata())
          addSongToPlaylist(playlist, listOf(song.id))
        }
        // Mirror to YouTube Music when the playlist is synced to a remote one.
        playlist.playlist.browseId?.let { browseId ->
          runCatching { com.music.innertube.YouTube.addToPlaylist(browseId, song.id) }
        }
        saveRecognitionHistory(song)
        ReelImportState.report(ReelImportStage.DONE)
        _uiState.value = ReelImportUiState.Finished(playlist.playlist.name)
      } catch (e: Exception) {
        _uiState.value = ReelImportUiState.Failed(ReelImportStage.FAILED)
      }
    }
  }

  /** Persist the fingerprint match in recognition history like Echo Find does. */
  private suspend fun saveRecognitionHistory(song: SongItem) {
    val recognition = pendingRecognition ?: return
    runCatching {
      database.query {
        insert(
          RecognitionHistory(
            trackId = recognition.trackId,
            title = song.title,
            artist = song.artists.joinToString { it.name },
            album = recognition.album,
            coverArtUrl = recognition.coverArtUrl,
            coverArtHqUrl = recognition.coverArtHqUrl,
            genre = recognition.genre,
            releaseDate = recognition.releaseDate,
            label = recognition.label,
            shazamUrl = recognition.shazamUrl,
            appleMusicUrl = recognition.appleMusicUrl,
            spotifyUrl = recognition.spotifyUrl,
            isrc = recognition.isrc,
            youtubeVideoId = song.id,
            recognizedAt = LocalDateTime.now(),
          )
        )
      }
    }
  }

  fun reset() {
    pendingRecognition = null
    ReelImportState.clear()
    _uiState.value = ReelImportUiState.Idle
  }
}
