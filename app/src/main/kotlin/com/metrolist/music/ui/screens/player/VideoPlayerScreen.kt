/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.ui.screens.player

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.util.Rational
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import androidx.navigation.NavController
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.music.LocalDatabase
import com.metrolist.music.LocalPlayerConnection
import com.metrolist.music.R
import com.metrolist.music.utils.UrlValidator
import com.metrolist.music.utils.YTPlayerUtils
import io.sanghun.compose.video.RepeatMode
import io.sanghun.compose.video.VideoPlayer
import io.sanghun.compose.video.controller.VideoPlayerControllerConfig
import io.sanghun.compose.video.uri.VideoPlayerMediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import timber.log.Timber
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoPlayerScreen(
    navController: NavController,
    videoId: String,
    title: String? = null,
    artist: String? = null,
) {
    val context = LocalContext.current
    val activity = context as? Activity
    val clipboard = remember { context.getSystemService(ClipboardManager::class.java) }
    val connectivityManager = remember { context.getSystemService(ConnectivityManager::class.java) }
    val database = LocalDatabase.current
    val scope = rememberCoroutineScope()
    val playerConnection = LocalPlayerConnection.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current

    // Use rememberSaveable for state that should persist across rotation
    var videoItem by remember { mutableStateOf<VideoPlayerMediaItem.NetworkMediaItem?>(null) }
    var playerInstance by remember { mutableStateOf<ExoPlayer?>(null) }
    var isLoading by rememberSaveable { mutableStateOf(true) }
    var loadError by rememberSaveable { mutableStateOf<String?>(null) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var currentTitle by rememberSaveable(videoId, title) { mutableStateOf(title?.takeIf { it.isNotBlank() }) }
    var reloadKey by rememberSaveable { mutableStateOf(0) }
    var availableQualities by remember { mutableStateOf<List<QualityOption>>(emptyList()) }
    var selectedQualityId by rememberSaveable { mutableStateOf("auto") }
    // Adaptive playback data for 1080p+ support
    var adaptiveData by remember { mutableStateOf<YTPlayerUtils.AdaptiveVideoData?>(null) }
    var adaptiveQualities by remember { mutableStateOf<List<YTPlayerUtils.VideoQualityInfo>>(emptyList()) }
    var selectedQualityHeight by rememberSaveable { mutableStateOf(1080) } // Default to 1080p
    var playbackInfo by rememberSaveable { mutableStateOf<String?>(null) }
    var isInPipMode by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }
    var artistName by rememberSaveable(videoId, artist) { mutableStateOf(artist?.takeIf { it.isNotBlank() }) }
    var positionMs by rememberSaveable { mutableStateOf(0L) }
    var durationMs by rememberSaveable { mutableStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var lastInteraction by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var videoBottomPx by remember { mutableStateOf<Int?>(null) }

    // Auto-fullscreen on landscape orientation
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    LaunchedEffect(isLandscape, isFullscreen) {
        val act = activity ?: return@LaunchedEffect
        val window = act.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)

        if (isLandscape && !isFullscreen && !isInPipMode) {
            isFullscreen = true
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else if (!isLandscape && isFullscreen) {
            isFullscreen = false
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Restore system bars when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            activity?.let { act ->
                val window = act.window
                val insetsController = WindowCompat.getInsetsController(window, window.decorView)
                insetsController.show(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    LaunchedEffect(videoId) {
        val mappedSong = withContext(Dispatchers.IO) {
            val direct = database.getSongById(videoId)
            if (direct != null) return@withContext direct
            val setVideo = database.getSetVideoId(videoId)?.setVideoId
            if (setVideo != null) database.getSongById(setVideo) else null
        }
        mappedSong?.let { song ->
            if (currentTitle.isNullOrBlank()) {
                currentTitle = song.song.title
            }
            if (artistName.isNullOrBlank()) {
                val artistDisplay = song.artists.joinToString(" • ") { it.name }
                artistName = artistDisplay.ifBlank { null }
            }
        }
    }

    val httpClient = remember {
        OkHttpClient.Builder()
            .build()
    }

    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Pause any music playback while the user is watching video
    LaunchedEffect(Unit) {
        playerConnection?.player?.pause()
    }

    DisposableEffect(playerInstance) {
        val player = playerInstance ?: return@DisposableEffect onDispose { }
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                val qualities = tracks.groups
                    .filter { it.type == C.TRACK_TYPE_VIDEO }
                    .flatMap { group ->
                        val mtg = group.mediaTrackGroup
                        (0 until group.length).map { index ->
                            val format = group.getTrackFormat(index)
                            val height = format.height.takeIf { it > 0 }
                            val bitrate = format.bitrate.takeIf { it > 0 }
                            val label = buildString {
                                if (height != null) append("${height}p ")
                                if (bitrate != null) append("(${bitrate / 1000}kbps) ")
                                if (!format.codecs.isNullOrBlank()) append(format.codecs)
                            }.ifBlank { "Video" }
                            QualityOption(
                                id = "${mtg.hashCode()}_$index",
                                label = label.trim(),
                                height = height,
                                width = format.width.takeIf { it > 0 },
                                bitrate = bitrate,
                                codecs = format.codecs,
                                mimeType = format.sampleMimeType,
                                group = mtg,
                                trackIndex = index
                            )
                        }
                    }
                    .sortedByDescending { it.height ?: 0 }
                availableQualities = qualities

                val currentOverrideEntry = player.trackSelectionParameters.overrides.entries.firstOrNull { entry ->
                    qualities.any { it.group == entry.key }
                }
                selectedQualityId = currentOverrideEntry?.let { entry ->
                    val match = qualities.firstOrNull { opt ->
                        opt.group == entry.key && entry.value.trackIndices.contains(opt.trackIndex)
                    }
                    match?.id
                } ?: "auto"

                val format = player.videoFormat
                playbackInfo = format?.let { f ->
                    val resolution = if (f.width > 0 && f.height > 0) "${f.width}x${f.height}" else null
                    val bitrateKbps = f.bitrate.takeIf { it > 0 }?.div(1000)
                    val codec = when {
                        !f.codecs.isNullOrBlank() -> f.codecs
                        !f.sampleMimeType.isNullOrBlank() -> f.sampleMimeType
                        else -> null
                    }
                    buildString {
                        resolution?.let { append(it) }
                        bitrateKbps?.let {
                            if (isNotEmpty()) append(" • ")
                            append("${it}kbps")
                        }
                        codec?.let {
                            if (isNotEmpty()) append(" • ")
                            append(it)
                        }
                    }.ifBlank { null }
                }
            }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }

    LaunchedEffect(playerConnection?.mediaMetadata?.value, videoId) {
        val meta = playerConnection?.mediaMetadata?.value ?: return@LaunchedEffect
        if (meta.id == videoId || meta.setVideoId == videoId) {
            currentTitle = meta.title
            val artistDisplay = meta.artists.joinToString(" • ") { it.name }
            artistName = artistDisplay.ifBlank { artistName }
        }
    }

    val maxVideoBitrateKbps = remember(connectivityManager) {
        if (connectivityManager?.isActiveNetworkMetered == true) 1500 else 6000
    }
    val supportsPip = remember(activity) {
        activity?.packageManager?.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE) == true
    }
    val canEnterPip by remember {
        derivedStateOf {
            supportsPip &&
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                videoItem != null &&
                loadError == null
        }
    }

    DisposableEffect(lifecycleOwner, activity) {
        val observer = LifecycleEventObserver { _, _ ->
            isInPipMode = activity?.isInPictureInPictureMode == true
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    DisposableEffect(activity) {
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    LaunchedEffect(videoId, maxVideoBitrateKbps, reloadKey) {
        isLoading = true
        loadError = null
        videoItem = null
        adaptiveData = null

        // Try adaptive playback first (for 1080p+ support)
        // Quality selection is handled by the adaptive player, not by refetching
        val adaptiveResult = withContext(Dispatchers.IO) {
            YTPlayerUtils.getAdaptiveVideoData(videoId, targetHeight = selectedQualityHeight)
        }

        adaptiveResult.onSuccess { adaptive ->
            val videoUrl = UrlValidator.validateAndParseUrl(adaptive.videoUrl)?.toString()
            val audioUrl = UrlValidator.validateAndParseUrl(adaptive.audioUrl)?.toString()

            if (videoUrl != null && audioUrl != null) {
                adaptiveData = adaptive
                adaptiveQualities = adaptive.availableQualities

                val titleFromPlayback = adaptive.videoDetails?.title?.takeIf { it.isNotBlank() }
                val resolvedTitle = titleFromPlayback ?: currentTitle ?: videoId
                currentTitle = resolvedTitle

                // Get artist name, avoiding channel IDs (which start with "UC" and have no spaces)
                val authorFromPlayback = adaptive.videoDetails?.author?.takeIf {
                    it.isNotBlank() && !it.isChannelId()
                }
                val artistFromTitle = resolvedTitle.extractArtistFromTitle()

                if (artistName.isNullOrBlank() || artistName?.isChannelId() == true) {
                    artistName = authorFromPlayback ?: artistFromTitle ?: "Unknown Artist"
                }
                val thumbnail = adaptive.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url

                val mediaMetadata = MediaMetadata.Builder()
                    .setTitle(resolvedTitle)
                    .apply {
                        thumbnail?.let { setArtworkUri(Uri.parse(it)) }
                        artistName?.let { setArtist(it) }
                    }
                    .build()

                // Use video URL as placeholder - we'll set MergingMediaSource after player is created
                videoItem = VideoPlayerMediaItem.NetworkMediaItem(
                    url = videoUrl,
                    mediaMetadata = mediaMetadata,
                    mimeType = adaptive.videoFormat.mimeType,
                    drmConfiguration = null
                )
                isLoading = false
                return@LaunchedEffect
            }
        }

        // Fallback to progressive playback (limited to 720p)
        // Uses isVideoFallback=true to skip TVHTML5 (already tried for adaptive)
        val result = withContext(Dispatchers.IO) {
            val cm = connectivityManager ?: error("No connectivity manager")
            YTPlayerUtils.playerResponseForVideoPlayback(
                videoId = videoId,
                connectivityManager = cm,
                maxVideoBitrateKbps = maxVideoBitrateKbps,
                isVideoFallback = true,
            )
        }

        result.onSuccess { playback ->
            val validatedUrl = UrlValidator.validateAndParseUrl(playback.streamUrl)?.toString()
            if (validatedUrl == null) {
                loadError = "Invalid stream URL"
                isLoading = false
                return@onSuccess
            }

            val titleFromPlayback = playback.videoDetails?.title?.takeIf { it.isNotBlank() }
            val resolvedTitle = titleFromPlayback ?: currentTitle ?: videoId
            currentTitle = resolvedTitle

            // Get artist name, avoiding channel IDs
            val authorFromPlayback = playback.videoDetails?.author?.takeIf {
                it.isNotBlank() && !it.isChannelId()
            }
            val artistFromTitle = resolvedTitle.extractArtistFromTitle()

            if (artistName.isNullOrBlank() || artistName?.isChannelId() == true) {
                artistName = authorFromPlayback ?: artistFromTitle ?: "Unknown Artist"
            }
            val thumbnail = playback.videoDetails?.thumbnail?.thumbnails?.lastOrNull()?.url

            val mediaMetadata = MediaMetadata.Builder()
                .setTitle(resolvedTitle)
                .apply {
                    thumbnail?.let { setArtworkUri(Uri.parse(it)) }
                    artistName?.let { setArtist(it) }
                }
                .build()

            videoItem = VideoPlayerMediaItem.NetworkMediaItem(
                url = validatedUrl,
                mediaMetadata = mediaMetadata,
                mimeType = playback.format.mimeType,
                drmConfiguration = null
            )
            isLoading = false
        }.onFailure {
            loadError = it.localizedMessage ?: "Playback error"
            isLoading = false
        }
    }

    LaunchedEffect(playerInstance) {
        val player = playerInstance ?: return@LaunchedEffect
        while (isActive) {
            if (!isScrubbing) {
                positionMs = player.currentPosition
            }
            val d = player.duration
            if (d > 0) durationMs = d
            isPlaying = player.isPlaying
            delay(500)
        }
    }

    val enterPip: () -> Unit = pip@{
        val act = activity ?: return@pip
        if (!canEnterPip) return@pip
        val params = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PictureInPictureParams.Builder()
                .setAspectRatio(Rational(16, 9))
                .build()
        } else {
            null
        }
        try {
            @Suppress("DEPRECATION")
            val entered = if (params != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                act.enterPictureInPictureMode(params)
            } else {
                act.enterPictureInPictureMode()
                true
            }
            if (!entered) {
                Toast.makeText(context, "Unable to start PiP", Toast.LENGTH_SHORT).show()
            }
        } catch (e: IllegalStateException) {
            Toast.makeText(context, "PiP unavailable: ${e.localizedMessage}", Toast.LENGTH_SHORT).show()
        }
    }

    val markInteraction: () -> Unit = {
        showControls = true
        lastInteraction = System.currentTimeMillis()
    }

    val togglePlayPause: () -> Unit = {
        playerInstance?.let { player ->
            if (player.isPlaying) {
                player.pause()
                showControls = true
            } else {
                player.play()
                markInteraction()
            }
        }
    }

    val seekByMs: (Long) -> Unit = { delta ->
        playerInstance?.let { player ->
            val durationLimit = if (durationMs > 0) durationMs else Long.MAX_VALUE
            val newPos = (player.currentPosition + delta).coerceIn(0, durationLimit)
            player.seekTo(newPos)
            positionMs = newPos
            lastInteraction = System.currentTimeMillis()
            showControls = true
        }
    }

    val toggleFullscreen: () -> Unit = fullscreen@{
        val act = activity ?: return@fullscreen
        val next = !isFullscreen
        isFullscreen = next
        act.requestedOrientation = if (next) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }

        // Hide/show system bars for true fullscreen
        val window = act.window
        val insetsController = WindowCompat.getInsetsController(window, window.decorView)
        if (next) {
            insetsController.hide(WindowInsetsCompat.Type.systemBars())
            insetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            insetsController.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    val density = LocalDensity.current
    val dragSkipThresholdPx = remember(density) { with(density) { 80.dp.toPx() } }
    var dragAccum by remember { mutableStateOf(0f) }

    LaunchedEffect(isPlaying) {
        if (!isPlaying) {
            showControls = true
        } else {
            markInteraction()
        }
    }

    LaunchedEffect(showControls, lastInteraction, isPlaying) {
        if (!showControls) return@LaunchedEffect
        if (!isPlaying) return@LaunchedEffect
        delay(4000)
        if (System.currentTimeMillis() - lastInteraction >= 3800 && isPlaying) {
            showControls = false
        }
    }

    BackHandler(enabled = !isInPipMode) {
        navController.popBackStack()
    }

    Scaffold(containerColor = Color.Black) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                loadError != null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(text = loadError ?: "Playback error", color = Color.White)
                        TextButton(onClick = { reloadKey++ }) {
                            Text("Retry", color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                videoItem != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp)
                            .padding(vertical = if (isInPipMode) 0.dp else 12.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            tonalElevation = 6.dp,
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .onGloballyPositioned { coords ->
                                    videoBottomPx = coords.boundsInParent().bottom.toInt()
                                }
                        ) {
                            // Use custom ExoPlayer for adaptive playback (1080p+)
                            if (adaptiveData != null) {
                                val adaptive = adaptiveData!!

                                // Create stable OkHttpClient and DataSourceFactory
                                val okHttpClient = remember {
                                    OkHttpClient.Builder()
                                        .proxy(YouTube.proxy)
                                        .build()
                                }

                                val dataSourceFactory = remember(okHttpClient) {
                                    val requestHeaders = mutableMapOf<String, String>()
                                    requestHeaders["Origin"] = "https://www.youtube.com"
                                    requestHeaders["Referer"] = "https://www.youtube.com/"
                                    YouTube.cookie?.let { requestHeaders["Cookie"] = it }

                                    OkHttpDataSource.Factory(okHttpClient)
                                        .setUserAgent(YouTubeClient.USER_AGENT_WEB)
                                        .setDefaultRequestProperties(requestHeaders)
                                }

                                // Create player once per videoId (not per quality change)
                                val adaptivePlayer = remember(videoId) {
                                    ExoPlayer.Builder(context).build().apply {
                                        playWhenReady = true
                                        addListener(object : androidx.media3.common.Player.Listener {
                                            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                                                Timber.tag("VideoPlayer").e(error, "Playback error: ${error.message}")
                                            }
                                        })
                                    }.also {
                                        Timber.tag("VideoPlayer").d("Created stable ExoPlayer for videoId=$videoId")
                                    }
                                }

                                // Update media source when URLs change (quality switch)
                                LaunchedEffect(adaptive.videoUrl, adaptive.audioUrl) {
                                    val currentPos = adaptivePlayer.currentPosition
                                    val wasPlaying = adaptivePlayer.isPlaying

                                    val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                                        .createMediaSource(MediaItem.fromUri(adaptive.videoUrl))
                                    val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                                        .createMediaSource(MediaItem.fromUri(adaptive.audioUrl))

                                    val mergingSource = MergingMediaSource(videoSource, audioSource)

                                    adaptivePlayer.setMediaSource(mergingSource)
                                    adaptivePlayer.prepare()

                                    // Restore position on quality changes (not initial load)
                                    if (currentPos > 0) {
                                        adaptivePlayer.seekTo(currentPos)
                                    }
                                    adaptivePlayer.playWhenReady = wasPlaying || currentPos == 0L

                                    Timber.tag("VideoPlayer").d("Updated media source: video=${adaptive.videoFormat.height}p, restored pos=${currentPos}ms")
                                }

                                // Set playerInstance for controls
                                LaunchedEffect(adaptivePlayer) {
                                    playerInstance = adaptivePlayer
                                }

                                // Cleanup only when videoId changes or screen is disposed
                                DisposableEffect(videoId) {
                                    onDispose {
                                        Timber.tag("VideoPlayer").d("Releasing ExoPlayer for videoId=$videoId")
                                        adaptivePlayer.release()
                                        if (playerInstance == adaptivePlayer) {
                                            playerInstance = null
                                        }
                                    }
                                }

                                AndroidView(
                                    factory = { ctx ->
                                        PlayerView(ctx).apply {
                                            useController = false
                                            player = adaptivePlayer
                                            layoutParams = ViewGroup.LayoutParams(
                                                ViewGroup.LayoutParams.MATCH_PARENT,
                                                ViewGroup.LayoutParams.MATCH_PARENT
                                            )
                                        }
                                    },
                                    update = { playerView ->
                                        if (playerView.player != adaptivePlayer) {
                                            playerView.player = adaptivePlayer
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(adaptivePlayer) {
                                            detectTapGestures {
                                                // Tap toggles controls visibility
                                                showControls = !showControls
                                                if (showControls) {
                                                    lastInteraction = System.currentTimeMillis()
                                                }
                                            }
                                        }
                                        .pointerInput(adaptivePlayer, durationMs) {
                                            detectDragGestures(
                                                onDrag = { _, dragAmount ->
                                                    dragAccum += dragAmount.x
                                                    markInteraction()
                                                },
                                                onDragEnd = {
                                                    // Swipe left/right to seek
                                                    when {
                                                        dragAccum > dragSkipThresholdPx -> seekByMs(10_000)
                                                        dragAccum < -dragSkipThresholdPx -> seekByMs(-10_000)
                                                    }
                                                    dragAccum = 0f
                                                },
                                                onDragCancel = {
                                                    dragAccum = 0f
                                                }
                                            )
                                        }
                                )
                            } else if (videoItem != null) {
                                // Fallback to library's VideoPlayer for progressive streams (720p max)
                                VideoPlayer(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .pointerInput(playerInstance) {
                                            detectTapGestures {
                                                // Tap toggles controls visibility
                                                showControls = !showControls
                                                if (showControls) {
                                                    lastInteraction = System.currentTimeMillis()
                                                }
                                            }
                                        }
                                        .pointerInput(playerInstance, durationMs) {
                                            detectDragGestures(
                                                onDrag = { _, dragAmount ->
                                                    dragAccum += dragAmount.x
                                                    markInteraction()
                                                },
                                                onDragEnd = {
                                                    // Swipe left/right to seek
                                                    when {
                                                        dragAccum > dragSkipThresholdPx -> seekByMs(10_000)
                                                        dragAccum < -dragSkipThresholdPx -> seekByMs(-10_000)
                                                    }
                                                    dragAccum = 0f
                                                },
                                                onDragCancel = {
                                                    dragAccum = 0f
                                                }
                                            )
                                        },
                                    mediaItems = listOf(videoItem!!),
                                    handleLifecycle = false,
                                    autoPlay = true,
                                    usePlayerController = false,
                                    controllerConfig = VideoPlayerControllerConfig.Default,
                                    repeatMode = RepeatMode.NONE,
                                    enablePip = false,
                                    enablePipWhenBackPressed = false,
                                    playerInstance = { playerInstance = this }
                                )
                            }
                        }

                        if (!isInPipMode) {
                            val outlineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.9f)
                            val pillBg = MaterialTheme.colorScheme.surface
                            val pillBorder = outlineColor
                            val chipRowOffsetPx = videoBottomPx?.plus(with(density) { 8.dp.roundToPx() })

                            AnimatedVisibility(
                                visible = showControls,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 0.dp)
                            ) {
                                Surface(
                                    shape = RectangleShape,
                                    color = Color.Black.copy(alpha = 0.8f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        IconButton(
                                            onClick = {
                                                markInteraction()
                                                navController.popBackStack()
                                            },
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.08f))
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.arrow_back),
                                                contentDescription = null,
                                                tint = Color.White
                                            )
                                        }
                                        Text(
                                            text = currentTitle ?: videoId,
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                            modifier = Modifier.weight(1f)
                                        )
                                        if (supportsPip) {
                                            IconButton(
                                                onClick = {
                                                    markInteraction()
                                                    enterPip()
                                                },
                                                modifier = Modifier
                                                    .size(36.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White.copy(alpha = 0.08f))
                                            ) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_pip),
                                                    contentDescription = null,
                                                    tint = Color.White
                                                )
                                            }
                                        }
                                        IconButton(
                                            onClick = {
                                                markInteraction()
                                                val clip = ClipData.newPlainText("Video link", "https://music.youtube.com/watch?v=$videoId")
                                                clipboard?.setPrimaryClip(clip)
                                                Toast.makeText(context, R.string.link_copied, Toast.LENGTH_SHORT).show()
                                            },
                                            modifier = Modifier
                                                .size(36.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.08f))
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.link),
                                                contentDescription = null,
                                                tint = Color.White
                                            )
                                        }
                                    }
                                }
                            }

                            if (chipRowOffsetPx != null) {
                                AnimatedVisibility(
                                    visible = showControls,
                                    enter = fadeIn(),
                                    exit = fadeOut(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .offset { IntOffset(0, chipRowOffsetPx) }
                                        .padding(horizontal = 8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            verticalArrangement = Arrangement.spacedBy(2.dp),
                                            modifier = Modifier.weight(1f)
                                        ) {
                                            Text(
                                                text = currentTitle ?: videoId,
                                                color = Color.White,
                                                style = MaterialTheme.typography.titleMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                text = artistName ?: "Unknown artist",
                                                color = Color.LightGray,
                                                style = MaterialTheme.typography.labelMedium,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Spacer(modifier = Modifier.width(8.dp))
                                        // Quality button
                                        Surface(
                                            shape = RoundedCornerShape(24.dp),
                                            color = pillBg,
                                            border = BorderStroke(1.dp, pillBorder),
                                            modifier = Modifier.size(44.dp)
                                        ) {
                                            IconButton(onClick = {
                                                markInteraction()
                                                showQualityDialog = true
                                            }) {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_video_hd),
                                                    contentDescription = stringResource(R.string.video_quality)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            AnimatedVisibility(
                                visible = showControls,
                                enter = fadeIn(),
                                exit = fadeOut(),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 12.dp)
                            ) {
                                val sliderValue =
                                    if (durationMs > 0) positionMs.toFloat() / durationMs.toFloat() else 0f
                                val durationText = if (durationMs > 0) formatTime(durationMs) else "--:--"
                                val positionText = formatTime(positionMs)

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 12.dp)
                                        .clip(RoundedCornerShape(18.dp))
                                        .border(
                                            width = 1.dp,
                                            color = outlineColor,
                                            shape = RoundedCornerShape(18.dp)
                                        )
                                        .background(Color.Black.copy(alpha = 0.6f))
                                        .padding(horizontal = 12.dp, vertical = 12.dp)
                                ) {
                                    val buttonColors = IconButtonDefaults.outlinedIconButtonColors(
                                        contentColor = Color.White,
                                        containerColor = Color.Black.copy(alpha = 0.35f)
                                    )
                                    val buttonBorder = BorderStroke(1.dp, outlineColor)

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceEvenly,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        OutlinedIconButton(
                                            onClick = { showSpeedDialog = true },
                                            modifier = Modifier.size(36.dp),
                                            colors = buttonColors,
                                            border = buttonBorder,
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.speed),
                                                contentDescription = stringResource(R.string.video_speed)
                                            )
                                        }
                                        OutlinedIconButton(
                                            onClick = { seekByMs(-10_000) },
                                            modifier = Modifier.size(52.dp),
                                            colors = buttonColors,
                                            border = buttonBorder,
                                            shape = RoundedCornerShape(18.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.skip_previous),
                                                contentDescription = stringResource(R.string.video_previous)
                                            )
                                        }
                                        OutlinedIconButton(
                                            onClick = togglePlayPause,
                                            modifier = Modifier.size(64.dp),
                                            colors = buttonColors,
                                            border = buttonBorder,
                                            shape = RoundedCornerShape(22.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play),
                                                contentDescription = stringResource(if (isPlaying) R.string.video_pause else R.string.video_play)
                                            )
                                        }
                                        OutlinedIconButton(
                                            onClick = { seekByMs(10_000) },
                                            modifier = Modifier.size(52.dp),
                                            colors = buttonColors,
                                            border = buttonBorder,
                                            shape = RoundedCornerShape(18.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.skip_next),
                                                contentDescription = stringResource(R.string.video_next)
                                            )
                                        }
                                        OutlinedIconButton(
                                            onClick = toggleFullscreen,
                                            modifier = Modifier.size(36.dp),
                                            colors = buttonColors,
                                            border = buttonBorder,
                                            shape = RoundedCornerShape(12.dp)
                                        ) {
                                            Icon(
                                                painter = painterResource(R.drawable.fullscreen),
                                                contentDescription = stringResource(R.string.video_fullscreen)
                                            )
                                        }
                                    }

                                    Slider(
                                        value = sliderValue,
                                        onValueChange = { value ->
                                            if (durationMs > 0) {
                                                isScrubbing = true
                                                positionMs = (durationMs * value).toLong().coerceIn(0, durationMs)
                                            }
                                            lastInteraction = System.currentTimeMillis()
                                        },
                                        onValueChangeFinished = {
                                            if (durationMs > 0) {
                                                playerInstance?.seekTo(positionMs)
                                            }
                                            isScrubbing = false
                                        },
                                        enabled = durationMs > 0,
                                        colors = SliderDefaults.colors(
                                            thumbColor = MaterialTheme.colorScheme.primary,
                                            activeTrackColor = MaterialTheme.colorScheme.primary,
                                            inactiveTrackColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                        )
                                    )

                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(bottom = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(positionText, color = Color.White, style = MaterialTheme.typography.labelSmall)
                                        Text(durationText, color = Color.White, style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showSpeedDialog) {
        val speeds = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text("Playback speed") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    speeds.forEach { speed ->
                        TextButton(onClick = {
                            playerInstance?.setPlaybackSpeed(speed)
                            showSpeedDialog = false
                        }) {
                            Text(if (speed == 1f) "1.0x (Normal)" else "${speed}x")
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showSpeedDialog = false }) { Text("Close") }
            }
        )
    }

    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text("Video quality") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Show current playing quality
                    val playingQuality = adaptiveData?.videoFormat?.height ?: selectedQualityHeight
                    Text(
                        text = "Playing: ${playingQuality}p",
                        style = MaterialTheme.typography.labelMedium
                    )

                    // Use adaptive qualities if available (1080p+ support)
                    if (adaptiveQualities.isNotEmpty()) {
                        // Quality options (no "auto" - default is 1080p or highest below)
                        adaptiveQualities.forEach { quality ->
                            TextButton(
                                onClick = {
                                    selectedQualityHeight = quality.height
                                    showQualityDialog = false
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(quality.label)
                                    if (playingQuality == quality.height) {
                                        Text("✓", fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    } else if (availableQualities.isNotEmpty()) {
                        // Fallback to ExoPlayer track selection (progressive streams)
                        TextButton(onClick = {
                            playerInstance?.let { player ->
                                val params = player.trackSelectionParameters
                                    .buildUpon()
                                    .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                    .build()
                                player.trackSelectionParameters = params
                            }
                            selectedQualityId = "auto"
                            showQualityDialog = false
                        }) {
                            Text("Auto")
                        }
                        availableQualities.forEach { option ->
                            TextButton(onClick = {
                                playerInstance?.let { player ->
                                    val builder = player.trackSelectionParameters
                                        .buildUpon()
                                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                        .setOverrideForType(
                                            TrackSelectionOverride(option.group, listOf(option.trackIndex))
                                        )
                                    player.trackSelectionParameters = builder.build()
                                    selectedQualityId = option.id
                                }
                                showQualityDialog = false
                            }) {
                                Text(option.label.ifBlank { "Track ${option.trackIndex + 1}" })
                            }
                        }
                    } else {
                        Text("No quality options available", style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showQualityDialog = false }) { Text("Close") }
            }
        )
    }
}

private data class QualityOption(
    val id: String,
    val label: String,
    val height: Int?,
    val width: Int?,
    val bitrate: Int?,
    val codecs: String?,
    val mimeType: String?,
    val group: TrackGroup,
    val trackIndex: Int,
)

@Composable
private fun formatTime(ms: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0))
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/**
 * Check if a string looks like a YouTube channel ID.
 * Channel IDs start with "UC" and are alphanumeric with no spaces.
 */
private fun String.isChannelId(): Boolean {
    return this.startsWith("UC") && this.length >= 20 && !this.contains(" ")
}

/**
 * Extract artist name from video title.
 * Assumes format "Artist - Title" or "Artist | Title".
 */
private fun String.extractArtistFromTitle(): String? {
    // Try common separators
    val separators = listOf(" - ", " – ", " — ", " | ", " // ")
    for (separator in separators) {
        if (this.contains(separator)) {
            val artist = this.substringBefore(separator).trim()
            // Make sure we got something reasonable (not empty, not too long)
            if (artist.isNotBlank() && artist.length < 100) {
                return artist
            }
        }
    }
    return null
}
