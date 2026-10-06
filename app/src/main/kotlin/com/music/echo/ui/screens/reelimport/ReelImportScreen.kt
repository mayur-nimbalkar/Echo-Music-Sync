package echo.music.iad1tya.ui.screens.reelimport

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import com.music.innertube.models.SongItem
import com.music.shazamkit.models.RecognitionResult
import echo.music.iad1tya.LocalPlayerConnection
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.ListThumbnailSize
import echo.music.iad1tya.db.entities.Playlist
import echo.music.iad1tya.extensions.toMediaItem
import echo.music.iad1tya.playback.queues.ListQueue
import echo.music.iad1tya.reelimport.ReelImportStage
import echo.music.iad1tya.reelimport.ReelImportUiState
import echo.music.iad1tya.reelimport.ReelImportViewModel
import echo.music.iad1tya.ui.component.CreatePlaylistDialog
import echo.music.iad1tya.ui.component.DefaultDialog
import echo.music.iad1tya.ui.component.ListDialog
import echo.music.iad1tya.ui.component.ListItem
import echo.music.iad1tya.ui.component.PlaylistListItem
import echo.music.iad1tya.utils.listItemShape

/**
 * Reel import flow: shows pipeline progress, asks the user to confirm the matched song,
 * then adds it to the selected (or remembered default) playlist.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReelImportScreen(
  navController: NavController,
  initialReelUrl: String? = null,
  viewModel: ReelImportViewModel = hiltViewModel(),
) {
  val uiState by viewModel.uiState.collectAsState()
  val playlists by viewModel.playlists.collectAsState()
  val defaultPlaylistId by viewModel.defaultPlaylistId.collectAsState()
  val relatedSongs by viewModel.relatedSongs.collectAsState()
  val addedRelatedIds by viewModel.addedRelatedIds.collectAsState()
  var showStopDefaultDialog by remember { mutableStateOf(false) }
  var showCreatePlaylist by remember { mutableStateOf(false) }

  /** Song awaiting a playlist choice from the picker dialog. */
  var pendingSong by remember { mutableStateOf<SongItem?>(null) }

  /** Related song awaiting explicit confirmation before it is added to the playlist. */
  var pendingRelatedSong by remember { mutableStateOf<SongItem?>(null) }

  fun openPlaylistPicker(song: SongItem) {
    pendingSong = song
  }

  // Auto-start whenever a reel link arrives — including while the screen shows a
  // previous import's result (Finished/Failed/picker), so a fresh share is never
  // silently ignored. The ViewModel decides whether to start or keep an in-flight run.
  LaunchedEffect(initialReelUrl) {
    if (!initialReelUrl.isNullOrBlank()) {
      viewModel.startFromLink(initialReelUrl)
    }
  }

  Scaffold(
    containerColor = MaterialTheme.colorScheme.surfaceContainer,
    topBar = {
      TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
        title = { Text(stringResource(R.string.reel_import_title)) },
        navigationIcon = {
          IconButton(onClick = { navController.navigateUp() }) {
            Icon(painter = painterResource(R.drawable.arrow_back), contentDescription = null)
          }
        },
      )
    },
  ) { paddingValues ->
    Column(
      modifier =
        Modifier.fillMaxSize()
          .background(
            Brush.verticalGradient(
              listOf(
                MaterialTheme.colorScheme.surfaceContainer,
                MaterialTheme.colorScheme.surface,
              )
            )
          )
          .padding(paddingValues)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 20.dp, vertical = 16.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      when (val state = uiState) {
        is ReelImportUiState.Idle -> IdleContent()

        is ReelImportUiState.Working -> WorkingContent(state.stage, state.searchQuery)

        is ReelImportUiState.Searching -> WorkingContent(ReelImportStage.MATCHING, state.query)

        is ReelImportUiState.AwaitingConfirmation ->
          ConfirmationContent(
            song = state.song,
            recognition = state.recognition,
            reelTitle = state.reelTitle,
            defaultPlaylistId = defaultPlaylistId,
            playlists = playlists,
            onConfirmWithDefault = { viewModel.confirm(state.song, null) },
            onChoosePlaylist = { openPlaylistPicker(state.song) },
          )

        is ReelImportUiState.PickCandidate ->
          CandidatePickerContent(
            candidates = state.candidates,
            reelTitle = state.reelTitle,
            onSearch = { query -> viewModel.manualSearch(query) },
            onPick = { song ->
              // No default set: let the user choose a playlist for this song.
              if (defaultPlaylistId.isBlank()) openPlaylistPicker(song)
              else viewModel.pickCandidate(song, null)
            },
            onDismiss = { viewModel.reset() },
          )

        is ReelImportUiState.Finished ->
          FinishedContent(
            playlistName = state.playlistName,
            defaultPlaylistId = defaultPlaylistId,
            playlists = playlists,
            relatedSongs = relatedSongs,
            addedRelatedIds = addedRelatedIds,
            onAddRelated = { pendingRelatedSong = it },
            onStopDefault = { showStopDefaultDialog = true },
            onDone = {
              viewModel.reset()
              navController.navigateUp()
            },
          )

        is ReelImportUiState.Failed ->
          FailedContent(
            reason = state.reason,
            onSearchManually = { viewModel.searchManuallyFromFailure() },
            onClose = {
              viewModel.reset()
              navController.navigateUp()
            },
          )
      }
    }

    pendingSong?.let { song ->
      PlaylistPickerDialog(
        playlists = playlists,
        defaultPlaylistId = defaultPlaylistId,
        onDismiss = { pendingSong = null },
        onPick = { playlistId, makeDefault ->
          pendingSong = null
          if (makeDefault) viewModel.setDefaultPlaylist(playlistId)
          viewModel.pickCandidate(song, playlistId)
        },
        onCreatePlaylist = { showCreatePlaylist = true },
      )
    }

    pendingRelatedSong?.let { song ->
      RelatedSongConfirmDialog(
        song = song,
        playlistName = (uiState as? ReelImportUiState.Finished)?.playlistName,
        onDismiss = { pendingRelatedSong = null },
        onConfirm = {
          viewModel.addRelatedToPlaylist(song)
          pendingRelatedSong = null
        },
      )
    }

    if (showCreatePlaylist) {
      CreatePlaylistDialog(
        onDismiss = { showCreatePlaylist = false },
        // Keep the dialog composed until the playlist insert finishes: the OK button
        // normally dismisses BEFORE running onDone, which cancels the insert
        // coroutine mid-flight and leaves the song without a target playlist.
        autoDismiss = false,
        onPlaylistCreated = { playlistId ->
          showCreatePlaylist = false
          pendingSong?.let { song ->
            pendingSong = null
            viewModel.pickCandidate(song, playlistId)
          }
        },
      )
    }

    if (showStopDefaultDialog) {
      DefaultDialog(
        title = { Text(stringResource(R.string.reel_import_stop_default)) },
        onDismiss = { showStopDefaultDialog = false },
        buttons = {
          TextButton(onClick = {
            viewModel.clearDefaultPlaylist()
            showStopDefaultDialog = false
          }) {
            Text(stringResource(R.string.reel_import_stop_default))
          }
          TextButton(onClick = { showStopDefaultDialog = false }) {
            Text(stringResource(android.R.string.cancel))
          }
        },
      ) {
        Text(
          text =
            playlists
              .firstOrNull { it.playlist.id == defaultPlaylistId }
              ?.let {
                stringResource(R.string.reel_import_default_playlist_desc, it.playlist.name)
              }
              .orEmpty(),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    }
  }
}

@Composable
private fun IdleContent() {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(20.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    EqualizerBadge()
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
        text = stringResource(R.string.reel_import),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
      Text(
        text = stringResource(R.string.reel_import_share_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(horizontal = 16.dp),
      )
    }
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      shape = RoundedCornerShape(22.dp),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
      ) {
        Text(
          text = stringResource(R.string.reel_import_how_it_works),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth(),
        )
        Row(
          horizontalArrangement = Arrangement.SpaceEvenly,
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.fillMaxWidth(),
        ) {
          IdleStep(icon = R.drawable.share, label = stringResource(R.string.reel_import_step_share))
          IdleStep(icon = R.drawable.graphic_eq, label = stringResource(R.string.reel_import_step_identify))
          IdleStep(icon = R.drawable.playlist_add, label = stringResource(R.string.reel_import_step_save))
        }
      }
    }
  }
}

@Composable
private fun IdleStep(icon: Int, label: String) {
  Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Box(
      modifier = Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painter = painterResource(icon),
        contentDescription = null,
        modifier = Modifier.size(22.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
    }
    Text(
      text = label,
      style = MaterialTheme.typography.labelMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
    )
  }
}

@Composable
private fun WorkingContent(stage: ReelImportStage, searchQuery: String? = null) {
  val stepIndex =
    when (stage) {
      ReelImportStage.EXTRACTING -> 1
      ReelImportStage.LISTENING -> 2
      ReelImportStage.MATCHING -> 3
      else -> 0
    }

  val headline =
    when (stage) {
      ReelImportStage.FETCHING_METADATA -> stringResource(R.string.reel_import_fetching_metadata)
      ReelImportStage.EXTRACTING -> stringResource(R.string.reel_import_extracting_audio)
      ReelImportStage.MATCHING -> stringResource(R.string.reel_import_matching)
      else -> stringResource(R.string.reel_import_listening)
    }

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(28.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    EqualizerBadge()
    Text(
      text = headline,
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      shape = RoundedCornerShape(24.dp),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(22.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp)) {
        WorkingStep(
          label = stringResource(R.string.reel_import_step_reel),
          state = if (stepIndex > 0) StepState.Done else StepState.Current,
        )
        WorkingStep(
          label = stringResource(R.string.reel_import_step_audio),
          state =
            when {
              stepIndex > 1 -> StepState.Done
              stepIndex == 1 -> StepState.Current
              else -> StepState.Pending
            },
        )
        WorkingStep(
          label = stringResource(R.string.reel_import_step_listen),
          state =
            when {
              stepIndex > 2 -> StepState.Done
              stepIndex == 2 -> StepState.Current
              else -> StepState.Pending
            },
        )
        WorkingStep(
          label =
            if (stepIndex == 3 && !searchQuery.isNullOrBlank()) {
              // Show the live query so a slow stage is never a blind spinner.
              stringResource(R.string.reel_import_searching, searchQuery)
            } else {
              stringResource(R.string.reel_import_step_match)
            },
          state = if (stepIndex == 3) StepState.Current else StepState.Pending,
        )
      }
    }
  }
}

private enum class StepState { Current, Done, Pending }

@Composable
private fun EqualizerBadge() {
  val transition = rememberInfiniteTransition(label = "equalizer")
  val bar1 by
    transition.animateFloat(
      initialValue = 0.35f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(500, easing = LinearEasing), RepeatMode.Reverse),
      label = "bar1",
    )
  val bar2 by
    transition.animateFloat(
      initialValue = 0.9f,
      targetValue = 0.3f,
      animationSpec = infiniteRepeatable(tween(640, easing = LinearEasing), RepeatMode.Reverse),
      label = "bar2",
    )
  val bar3 by
    transition.animateFloat(
      initialValue = 0.55f,
      targetValue = 1f,
      animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
      label = "bar3",
    )
  val bars = listOf(bar1, bar2, bar3)

  Box(
    modifier =
      Modifier.size(112.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
    contentAlignment = Alignment.Center,
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      bars.forEach { fraction ->
        Box(
          modifier =
            Modifier.width(7.dp)
              .height(48.dp * fraction)
              .clip(RoundedCornerShape(4.dp))
              .background(MaterialTheme.colorScheme.primary),
        )
      }
    }
  }
}

@Composable
private fun WorkingStep(label: String, state: StepState) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp),
  ) {
    when (state) {
      StepState.Done ->
        Icon(
          painter = painterResource(R.drawable.check),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(22.dp),
        )
      StepState.Current ->
        CircularProgressIndicator(
          modifier = Modifier.size(20.dp),
          strokeWidth = 2.5.dp,
          color = MaterialTheme.colorScheme.primary,
        )
      StepState.Pending ->
        Box(
          modifier = Modifier.size(10.dp).padding(1.dp).clip(CircleShape).background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      color =
        when (state) {
          StepState.Done -> MaterialTheme.colorScheme.onSurfaceVariant
          StepState.Current -> MaterialTheme.colorScheme.onSurface
          StepState.Pending -> MaterialTheme.colorScheme.outline
        },
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun ConfirmationContent(
  song: SongItem,
  recognition: RecognitionResult?,
  reelTitle: String,
  defaultPlaylistId: String,
  playlists: List<Playlist>,
  onConfirmWithDefault: () -> Unit,
  onChoosePlaylist: () -> Unit,
) {
  val defaultName = playlists.firstOrNull { it.playlist.id == defaultPlaylistId }?.playlist?.name

  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(20.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Box {
      // Soft glow ring behind the artwork to lift it off the gradient background.
      Box(
        modifier =
          Modifier.size(196.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f))
            .align(Alignment.Center),
      )
      AsyncImage(
        model = song.thumbnail,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(180.dp).clip(RoundedCornerShape(24.dp)).align(Alignment.Center),
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Where the match came from: the audio itself, or Instagram's official audio tag.
      Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = RoundedCornerShape(50),
      ) {
        Text(
          text =
            if (recognition != null) {
              stringResource(R.string.reel_import_source_fingerprint)
            } else {
              stringResource(R.string.reel_import_source_official)
            },
          style = MaterialTheme.typography.labelMedium,
          modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
        )
      }
      // Preview the song before confirming — the source badge tells the user whether
      // the name came from Shazam or from Instagram's metadata.
      SongPreviewButton(song)
    }

    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = stringResource(R.string.reel_import_confirm_prompt),
        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Text(
        text = song.title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = song.artists.joinToString { it.name },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      song.album?.name?.takeIf { it.isNotBlank() }?.let { album ->
        // The album/film tells apart songs that share a title (the metadata ambiguity).
        Text(
          text = album,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      if (reelTitle.isNotBlank()) {
        Text(
          text = stringResource(R.string.reel_import_confirmed_from_title, reelTitle),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    if (defaultName != null) {
      // "Add with the default playlist" one-tap confirm.
      Button(
        onClick = onConfirmWithDefault,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp),
      ) {
        Text(
          text = stringResource(R.string.reel_import_default_playlist_desc, defaultName),
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }

    Button(
      onClick = onChoosePlaylist,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(56.dp),
    ) {
      Icon(
        painter = painterResource(R.drawable.playlist_add),
        contentDescription = null,
        modifier = Modifier.size(24.dp),
      )
      Spacer(Modifier.width(8.dp))
      Text(stringResource(R.string.reel_import_add_to_playlist))
    }
  }
}

@Composable
private fun CandidatePickerContent(
  candidates: List<SongItem>,
  reelTitle: String,
  onSearch: (String) -> Unit,
  onPick: (SongItem) -> Unit,
  onDismiss: () -> Unit,
) {
  // Reset per-import: keyed on candidates+reelTitle, so stale text from a previous
  // reel never survives into the next search (remember{} alone survives recomposition).
  var searchText by remember(candidates, reelTitle) { mutableStateOf("") }
  var prefilled by remember(candidates, reelTitle) { mutableStateOf(false) }

  // Nothing found automatically: start the manual search from the official
  // attribution/hint so the user corrects it in one tap instead of typing from scratch.
  LaunchedEffect(candidates, reelTitle) {
    if (!prefilled && candidates.isEmpty() && searchText.isBlank() && reelTitle.isNotBlank()) {
      searchText = reelTitle
      prefilled = true
    }
  }

  val visibleCandidates = candidates.take(MAX_VISIBLE_CANDIDATES)

  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(14.dp),
  ) {
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = stringResource(R.string.reel_import_pick_title),
        style = MaterialTheme.typography.headlineSmall,
        color = MaterialTheme.colorScheme.onSurface,
        textAlign = TextAlign.Center,
      )
      Text(
        text =
          if (candidates.isEmpty()) {
            stringResource(R.string.reel_import_search_manually_hint)
          } else {
            stringResource(R.string.reel_import_original_audio)
          },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.fillMaxWidth(),
    ) {
      OutlinedTextField(
        value = searchText,
        onValueChange = { searchText = it },
        placeholder = { Text(stringResource(R.string.reel_import_search_hint)) },
        singleLine = true,
        modifier = Modifier.weight(1f),
      )
      Spacer(Modifier.width(8.dp))
      Button(
        onClick = {
          onSearch(searchText)
          searchText = ""
        },
        enabled = searchText.isNotBlank(),
      ) {
        Text(stringResource(R.string.reel_import_search_action))
      }
    }
    if (visibleCandidates.isNotEmpty()) {
      Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
      ) {
        LazyColumn(
          modifier =
            Modifier.fillMaxWidth()
              .height(if (candidates.size <= 4) 240.dp else 420.dp)
              .padding(vertical = 6.dp),
          verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
          items(visibleCandidates, key = { it.id }) { song ->
            SongCandidateRow(song = song, onPick = { onPick(song) })
          }
        }
      }
      if (candidates.size > MAX_VISIBLE_CANDIDATES) {
        Text(
          text = stringResource(R.string.reel_import_more_results, candidates.size - MAX_VISIBLE_CANDIDATES),
          style = MaterialTheme.typography.labelMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
    TextButton(onClick = onDismiss) { Text(stringResource(R.string.reel_import_close)) }
  }
}

/** Max songs shown in the picker before a "N more" hint appears. */
private const val MAX_VISIBLE_CANDIDATES = 10

/** One tappable song candidate with artwork, title and artist. */
@Composable
private fun SongCandidateRow(song: SongItem, onPick: () -> Unit) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onPick)
        .padding(horizontal = 12.dp, vertical = 8.dp),
  ) {
    AsyncImage(
      model = song.thumbnail,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)),
    )
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
      Text(
        text = song.title,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        // Album/film included so same-titled songs stay distinguishable.
        text =
          listOfNotNull(
              song.artists.joinToString { it.name }.takeIf { it.isNotBlank() },
              song.album?.name?.takeIf { it.isNotBlank() },
            )
            .joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    SongPreviewButton(song)
    Icon(
      painter = painterResource(R.drawable.playlist_add),
      contentDescription = null,
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(20.dp),
    )
  }
}

/**
 * Play/pause preview of one candidate through the app's player, so the user can actually
 * hear a song before confirming it. This matters most for metadata matches, where two
 * different songs can share a title and only the audio tells them apart.
 */
@Composable
private fun SongPreviewButton(song: SongItem) {
  val playerConnection = LocalPlayerConnection.current ?: return
  val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
  val isPlaying by playerConnection.isPlaying.collectAsState()
  val isCurrent = mediaMetadata?.id == song.id
  val playing = isCurrent && isPlaying
  IconButton(
    onClick = {
      if (isCurrent) {
        playerConnection.togglePlayPause()
      } else {
        // A single-item queue: the preview never spills into unrelated songs.
        playerConnection.playQueue(ListQueue(items = listOf(song.toMediaItem())))
      }
    },
    modifier = Modifier.size(40.dp),
  ) {
    Icon(
      painter = painterResource(if (playing) R.drawable.pause else R.drawable.play),
      contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
      tint = MaterialTheme.colorScheme.primary,
      modifier = Modifier.size(22.dp),
    )
  }
}

/**
 * Playlist chooser. Turning on "add future reels without asking" arms the picker; the next
 * playlist tapped becomes both the target for this song and the remembered default.
 */
@Composable
private fun PlaylistPickerDialog(
  playlists: List<Playlist>,
  defaultPlaylistId: String,
  onDismiss: () -> Unit,
  onPick: (playlistId: String, makeDefault: Boolean) -> Unit,
  onCreatePlaylist: () -> Unit,
) {
  var makeDefault by remember { mutableStateOf(false) }

  ListDialog(onDismiss = onDismiss) {
    item {
      ListItem(
        title = stringResource(R.string.create_playlist),
        thumbnailContent = {
          Icon(
            painter = painterResource(R.drawable.add),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(ListThumbnailSize),
          )
        },
        modifier = Modifier.clickable(onClick = onCreatePlaylist),
      )
    }

    item {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = stringResource(R.string.reel_import_default_playlist),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = stringResource(R.string.reel_import_choose_playlist),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(checked = makeDefault, onCheckedChange = { makeDefault = it })
      }
    }

    if (playlists.isEmpty()) {
      item {
        Text(
          text = stringResource(R.string.reel_import_no_playlists),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
          modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        )
      }
    }

    itemsIndexed(playlists) { index, playlist ->
      val isDefault = playlist.playlist.id == defaultPlaylistId
      PlaylistListItem(
        playlist = playlist,
        shape = listItemShape(index = index, count = playlists.size),
        modifier = Modifier.clickable { onPick(playlist.playlist.id, makeDefault || isDefault) },
        badges = {
          if (isDefault) {
            Icon(
              painter = painterResource(R.drawable.check),
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(20.dp),
            )
          }
        },
      )
    }
  }
}

@Composable
private fun FinishedContent(
  playlistName: String?,
  defaultPlaylistId: String,
  playlists: List<Playlist>,
  relatedSongs: List<SongItem>,
  addedRelatedIds: Set<String>,
  onAddRelated: (SongItem) -> Unit,
  onStopDefault: () -> Unit,
  onDone: () -> Unit,
) {
  val defaultName = playlists.firstOrNull { it.playlist.id == defaultPlaylistId }?.playlist?.name
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    Box {
      Box(
        modifier =
          Modifier.size(112.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)).align(Alignment.Center),
      )
      Box(
        modifier = Modifier.size(84.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary).align(Alignment.Center),
        contentAlignment = Alignment.Center,
      ) {
        Icon(
          painter = painterResource(R.drawable.check),
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onPrimary,
          modifier = Modifier.size(44.dp),
        )
      }
    }
    Text(
      text = stringResource(R.string.reel_import_added, playlistName.orEmpty()),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    if (relatedSongs.isNotEmpty()) {
      Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
          Text(
            text = stringResource(R.string.reel_import_related_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
          )
          Text(
            text = stringResource(R.string.reel_import_related_subtitle),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Surface(
          color = MaterialTheme.colorScheme.surfaceContainerLow,
          shape = RoundedCornerShape(24.dp),
          modifier = Modifier.fillMaxWidth(),
        ) {
          LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            itemsIndexed(relatedSongs) { index, song ->
              val added = song.id in addedRelatedIds
              ListItem(
                title = song.title,
                subtitle = {
                  Text(
                    song.artists.joinToString { it.name },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                  )
                },
                thumbnailContent = {
                  AsyncImage(
                    model = song.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(ListThumbnailSize).clip(RoundedCornerShape(8.dp)),
                  )
                },
                shape = listItemShape(index = index, count = relatedSongs.size),
                trailingContent = {
                  Row(verticalAlignment = Alignment.CenterVertically) {
                    // Preview first: recommendations are guesses, so hearing it before
                    // adding is part of deciding whether to confirm.
                    SongPreviewButton(song)
                    Icon(
                      painter = painterResource(if (added) R.drawable.check else R.drawable.playlist_add),
                      contentDescription = null,
                      tint = MaterialTheme.colorScheme.primary,
                      modifier = Modifier.size(20.dp),
                    )
                  }
                },
                modifier = Modifier.clickable(enabled = !added) { onAddRelated(song) },
              )
            }
          }
        }
      }
    }
    if (defaultName != null) {
      Column(
        modifier =
          Modifier.fillMaxWidth().padding(horizontal = 16.dp).clickable(onClick = onStopDefault),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = stringResource(R.string.reel_import_default_playlist_desc, defaultName),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
        Text(
          text = stringResource(R.string.reel_import_stop_default),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.primary,
        )
      }
    }
    Button(
      onClick = onDone,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(52.dp),
    ) {
      Text(stringResource(android.R.string.ok))
    }
  }
}

@Composable
private fun FailedContent(
  reason: ReelImportStage,
  onSearchManually: () -> Unit,
  onClose: () -> Unit,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(22.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Box(
      modifier =
        Modifier.size(96.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.surfaceContainerHighest),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painter = painterResource(R.drawable.error),
        contentDescription = null,
        modifier = Modifier.size(44.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Text(
      text =
        when (reason) {
          ReelImportStage.NOT_A_REEL -> stringResource(R.string.reel_import_not_reel)
          ReelImportStage.YTDLP_ERROR -> stringResource(R.string.reel_import_error_ytdlp)
          else -> stringResource(R.string.reel_import_failed)
        },
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Surface(
      color = MaterialTheme.colorScheme.surfaceContainerLow,
      shape = RoundedCornerShape(22.dp),
      modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
    ) {
      Text(
        text = stringResource(R.string.reel_import_failed_hint),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(20.dp),
      )
    }
    Button(
      onClick = onSearchManually,
      modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(54.dp),
    ) {
      Text(stringResource(R.string.reel_import_search_manually))
    }
    TextButton(onClick = onClose) {
      Text(stringResource(R.string.reel_import_close))
    }
  }
}

/**
 * Confirmation shown before a recommended song is added to the playlist. Recommendations are
 * guesses, so a single tap must never silently commit one — the user gets the artwork, a
 * preview and an explicit "Add to <playlist>" choice.
 */
@Composable
private fun RelatedSongConfirmDialog(
  song: SongItem,
  playlistName: String?,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  DefaultDialog(
    title = { Text(stringResource(R.string.reel_import_add_related_title)) },
    onDismiss = onDismiss,
    buttons = {
      TextButton(onClick = onConfirm) {
        Text(
          text =
            playlistName?.takeIf { it.isNotBlank() }?.let {
              stringResource(R.string.reel_import_add_related_confirm, it)
            } ?: stringResource(R.string.reel_import_add_to_playlist)
        )
      }
      TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
    },
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(14.dp),
      modifier = Modifier.fillMaxWidth(),
    ) {
      AsyncImage(
        model = song.thumbnail,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)),
      )
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = song.title,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = song.artists.joinToString { it.name },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        song.album?.name?.takeIf { it.isNotBlank() }?.let { album ->
          Text(
            text = album,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
      SongPreviewButton(song)
    }
  }
}
