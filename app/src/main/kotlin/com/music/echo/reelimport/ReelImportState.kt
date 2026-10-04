package echo.music.iad1tya.reelimport

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Pipeline stages surfaced in the import UI. */
enum class ReelImportStage {
  FETCHING_METADATA,
  EXTRACTING,
  LISTENING,
  MATCHING,
  AWAITING_CONFIRM,
  ADDING,
  DONE,
  FAILED,
  /** Instagram rejected the request — expired session cookie, or a blocked network. */
  NEEDS_LOGIN,
  YTDLP_ERROR,
  NOT_A_REEL,
}

/**
 * Single-owner state holder shared between [ReelMatcher] (worker side) and
 * the import UI (observer side). The matcher reports fine-grained pipeline
 * progress that the screen observes.
 */
object ReelImportState {

  data class PendingImport(
    val reelUrl: String,
    val stage: ReelImportStage = ReelImportStage.FETCHING_METADATA,
    /** Query currently being searched on YouTube Music, when in [ReelImportStage.MATCHING]. */
    val searchQuery: String? = null,
  )

  private val _current = MutableStateFlow<PendingImport?>(null)
  val current: StateFlow<PendingImport?> = _current.asStateFlow()

  fun report(stage: ReelImportStage) {
    _current.value = _current.value?.copy(stage = stage)
  }

  fun begin(url: String) {
    _current.value = PendingImport(reelUrl = url)
  }

  /**
   * Applies [transform] only when the current pending import belongs to [url].
   * A cancelled previous run can still fire one last update; without the URL guard
   * it would pollute the new run's state (e.g. stale search queries on screen).
   */
  fun update(url: String, transform: (PendingImport) -> PendingImport) {
    val current = _current.value ?: return
    if (current.reelUrl == url) _current.value = transform(current)
  }

  fun clear() {
    _current.value = null
  }
}
