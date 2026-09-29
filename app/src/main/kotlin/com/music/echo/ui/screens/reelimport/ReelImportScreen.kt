package echo.music.iad1tya.ui.screens.reelimport

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import echo.music.iad1tya.R
import echo.music.iad1tya.constants.ListThumbnailSize
import echo.music.iad1tya.db.entities.Playlist
import echo.music.iad1tya.reelimport.ReelImportStage
import echo.music.iad1tya.reelimport.ReelImportUiState
import echo.music.iad1tya.reelimport.ReelImportViewModel
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
  var showPlaylistPicker by remember { mutableStateOf(false) }
  var showStopDefaultDialog by remember { mutableStateOf(false) }

  /** Song awaiting a playlist choice from the picker dialog. */
  var pendingSong by remember { mutableStateOf<SongItem?>(null) }

  fun openPlaylistPicker(song: SongItem) {
    pendingSong = song
    showPlaylistPicker = true
  }

  // Auto-start when opened with a shared reel link.
  LaunchedEffect(initialReelUrl) {
    if (!initialReelUrl.isNullOrBlank() && uiState is ReelImportUiState.Idle) {
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
    Box(
      modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp),
      contentAlignment = Alignment.Center,
    ) {
      when (val state = uiState) {
        is ReelImportUiState.Idle -> IdleContent()

        is ReelImportUiState.Working -> WorkingContent(state.stage)

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
            onStopDefault = { showStopDefaultDialog = true },
            onDone = {
              viewModel.reset()
              navController.navigateUp()
            },
          )

        is ReelImportUiState.Failed -> FailedContent(state.reason) { viewModel.reset() }
      }
    }

    if (showPlaylistPicker) {
      PlaylistPickerDialog(
        playlists = playlists,
        defaultPlaylistId = defaultPlaylistId,
        onDismiss = {
          showPlaylistPicker = false
          pendingSong = null
        },
        onPick = { playlistId, makeDefault ->
          showPlaylistPicker = false
          if (makeDefault) viewModel.setDefaultPlaylist(playlistId)
          pendingSong?.let { viewModel.pickCandidate(it, playlistId) }
          pendingSong = null
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
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Icon(
      painter = painterResource(R.drawable.graphic_eq),
      contentDescription = null,
      modifier = Modifier.size(64.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = stringResource(R.string.reel_import),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
    )
  }
}

@Composable
private fun WorkingContent(stage: ReelImportStage) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    CircularProgressIndicator(
      modifier = Modifier.size(96.dp),
      strokeWidth = 6.dp,
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text =
        when (stage) {
          ReelImportStage.EXTRACTING -> stringResource(R.string.reel_import_extracting_audio)
          ReelImportStage.LISTENING -> stringResource(R.string.reel_import_listening)
          ReelImportStage.MATCHING -> stringResource(R.string.reel_import_matching)
          ReelImportStage.ADDING -> stringResource(R.string.reel_import_add_to_playlist)
          else -> stringResource(R.string.reel_import_fetching_metadata)
        },
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
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
    verticalArrangement = Arrangement.spacedBy(16.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    AsyncImage(
      model = song.thumbnail,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier.size(180.dp).clip(RoundedCornerShape(24.dp)),
    )

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
  onPick: (SongItem) -> Unit,
  onDismiss: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxWidth(),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    Text(
      text = stringResource(R.string.reel_import_confirm_prompt),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    Text(
      text = stringResource(R.string.reel_import_original_audio),
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(
      modifier = Modifier.fillMaxWidth().height(340.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      itemsIndexed(candidates) { index, song ->
        ListItem(
          title = song.title,
          subtitle = { Text(song.artists.joinToString { it.name }) },
          thumbnailContent = {
            AsyncImage(
              model = song.thumbnail,
              contentDescription = null,
              contentScale = ContentScale.Crop,
              modifier = Modifier.size(ListThumbnailSize).clip(RoundedCornerShape(8.dp)),
            )
          },
          shape = listItemShape(index = index, count = candidates.size),
          modifier = Modifier.clickable { onPick(song) },
          color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        )
      }
    }
    TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
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
) {
  var makeDefault by remember { mutableStateOf(false) }

  ListDialog(onDismiss = onDismiss) {
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
  onStopDefault: () -> Unit,
  onDone: () -> Unit,
) {
  val defaultName = playlists.firstOrNull { it.playlist.id == defaultPlaylistId }?.playlist?.name
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(20.dp),
  ) {
    Box(
      modifier =
        Modifier.size(96.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
      contentAlignment = Alignment.Center,
    ) {
      Icon(
        painter = painterResource(R.drawable.check),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier.size(48.dp),
      )
    }
    Text(
      text = stringResource(R.string.reel_import_added, playlistName.orEmpty()),
      style = MaterialTheme.typography.titleLarge,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
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
    Button(onClick = onDone) { Text(stringResource(android.R.string.ok)) }
  }
}

@Composable
private fun FailedContent(reason: ReelImportStage, onRetry: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp),
  ) {
    Icon(
      painter = painterResource(R.drawable.error),
      contentDescription = null,
      modifier = Modifier.size(64.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text =
        when (reason) {
          ReelImportStage.NOT_A_REEL -> stringResource(R.string.reel_import_not_reel)
          ReelImportStage.NO_MATCH -> stringResource(R.string.reel_import_failed)
          ReelImportStage.YTDLP_ERROR -> stringResource(R.string.reel_import_error_ytdlp)
          else -> stringResource(R.string.reel_import_failed)
        },
      style = MaterialTheme.typography.titleMedium,
      color = MaterialTheme.colorScheme.onSurface,
      textAlign = TextAlign.Center,
    )
    TextButton(onClick = onRetry) {
      Text(stringResource(R.string.reel_import_try_different_reel))
    }
  }
}
