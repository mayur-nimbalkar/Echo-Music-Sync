package echo.music.iad1tya.reelimport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pipeline stages surfaced in the import UI. */
enum class ReelImportStage {
  IDLE,
  FETCHING_METADATA,
  EXTRACTING,
  LISTENING,
  MATCHING,
  AWAITING_CONFIRM,
  ADDING,
  DONE,
  FAILED,
  YTDLP_ERROR,
  NOT_A_REEL,
  NO_MATCH,
}

/**
 * Single-owner state holder shared between [ReelMatcher] (worker side) and
 * the import UI (observer side). The matcher reports fine-grained pipeline
 * progress that the screen observes.
 */
object ReelImportState {

  data class PendingImport(
    val reelUrl: String,
    val reelTitle: String = "",
    val reelThumbnailUrl: String? = null,
    val stage: ReelImportStage = ReelImportStage.FETCHING_METADATA,
  )

  private val _current = MutableStateFlow<PendingImport?>(null)
  val current: StateFlow<PendingImport?> = _current.asStateFlow()

  fun report(stage: ReelImportStage) {
    _current.value = _current.value?.copy(stage = stage)
  }

  fun begin(url: String, title: String = "", thumbnailUrl: String? = null) {
    _current.value =
      PendingImport(
        reelUrl = url,
        reelTitle = title,
        reelThumbnailUrl = thumbnailUrl,
        stage = ReelImportStage.FETCHING_METADATA,
      )
  }

  fun update(transform: (PendingImport) -> PendingImport) {
    _current.value = _current.value?.let(transform)
  }

  fun clear() {
    _current.value = null
  }
}
