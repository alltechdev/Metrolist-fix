/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.ConnectivityManager
import android.net.Uri
import android.util.Log
import androidx.media3.common.PlaybackException
import com.metrolist.innertube.NewPipeExtractor
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.YouTubeClient
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.metrolist.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.metrolist.innertube.models.YouTubeClient.Companion.IOS
import com.metrolist.innertube.models.YouTubeClient.Companion.IPADOS
import com.metrolist.innertube.models.YouTubeClient.Companion.MOBILE
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5
import com.metrolist.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.metrolist.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import com.metrolist.music.utils.cipher.CipherDeobfuscator
import com.metrolist.music.utils.YTPlayerUtils.MAIN_CLIENT
import com.metrolist.music.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.metrolist.music.utils.YTPlayerUtils.validateStatus
import com.metrolist.music.utils.potoken.PoTokenGenerator
import com.metrolist.music.utils.potoken.PoTokenResult
import com.metrolist.music.utils.sabr.EjsNTransformSolver
import okhttp3.OkHttpClient
import timber.log.Timber

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"

    private val httpClient = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    private val MAIN_CLIENT: YouTubeClient = WEB_REMIX

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,  // Try embedded player first for age-restricted content
        TVHTML5,
        ANDROID_VR_1_43_32,
        ANDROID_VR_1_61_48,
        ANDROID_CREATOR,
        IPADOS,
        ANDROID_VR_NO_AUTH,
        MOBILE,
        IOS,
        WEB,
        WEB_CREATOR
    )

    // Video-specific fallback (excludes TVHTML5 since it was already tried for adaptive 1080p+)
    private val VIDEO_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        ANDROID_VR_1_43_32,
        IOS,
        IPADOS,
        ANDROID_VR_1_61_48,
        ANDROID_CREATOR,
        ANDROID_VR_NO_AUTH,
        MOBILE,
        WEB,
        WEB_CREATOR
    )
    data class PlaybackData(
        val audioConfig: PlayerResponse.PlayerConfig.AudioConfig?,
        val videoDetails: PlayerResponse.VideoDetails?,
        val playbackTracking: PlayerResponse.PlaybackTracking?,
        val format: PlayerResponse.StreamingData.Format,
        val streamUrl: String,
        val streamExpiresInSeconds: Int,
    )

    /**
     * Data for adaptive video playback with separate video and audio streams.
     * Used with ExoPlayer's MergingMediaSource for 1080p+ playback.
     */
    data class AdaptiveVideoData(
        val videoDetails: PlayerResponse.VideoDetails?,
        val videoUrl: String,
        val audioUrl: String,
        val videoFormat: PlayerResponse.StreamingData.Format,
        val audioFormat: PlayerResponse.StreamingData.Format,
        val expiresInSeconds: Int,
        val availableQualities: List<VideoQualityInfo>,
    )

    data class VideoQualityInfo(
        val height: Int,
        val width: Int?,
        val fps: Int?,
        val bitrate: Int,
        val label: String,
        val format: PlayerResponse.StreamingData.Format,
    )
    /**
     * Custom player response intended to use for playback.
     * Metadata like audioConfig and videoDetails are from [MAIN_CLIENT].
     * Format & stream can be from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS].
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Timber.tag(logTag).d("Fetching player response for videoId: $videoId, playlistId: $playlistId")
        // Debug: Log ALL playback attempts
        println("[PLAYBACK_DEBUG] playerResponseForPlayback called: videoId=$videoId, playlistId=$playlistId")
        // Check if this is an uploaded/privately owned track
        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true

        val isLoggedIn = YouTube.cookie != null
        Timber.tag(logTag).d("Session authentication status: ${if (isLoggedIn) "Logged in" else "Not logged in"}")

        // Get signature timestamp (same as before for normal content)
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        Timber.tag(logTag).d("Signature timestamp: ${signatureTimestamp.timestamp}")

        // Generate PoToken
        var poToken: PoTokenResult? = null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        if (MAIN_CLIENT.useWebPoTokens && sessionId != null) {
            Timber.tag(logTag).d("Generating PoToken for WEB_REMIX with sessionId")
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                if (poToken != null) {
                    Timber.tag(logTag).d("PoToken generated successfully")
                }
            } catch (e: Exception) {
                Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
            }
        }

        // Try WEB_REMIX with signature timestamp and poToken (same as before)
        Timber.tag(logTag).d("Attempting to get player response using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        var mainPlayerResponse = YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp.timestamp, poToken?.playerRequestPoToken).getOrThrow()

        // Debug uploaded track response
        if (isUploadedTrack || playlistId?.contains("MLPT") == true) {
            println("[PLAYBACK_DEBUG] Main player response status: ${mainPlayerResponse.playabilityStatus.status}")
            println("[PLAYBACK_DEBUG] Playability reason: ${mainPlayerResponse.playabilityStatus.reason}")
            println("[PLAYBACK_DEBUG] Video details: title=${mainPlayerResponse.videoDetails?.title}, videoId=${mainPlayerResponse.videoDetails?.videoId}")
            println("[PLAYBACK_DEBUG] Streaming data null? ${mainPlayerResponse.streamingData == null}")
            println("[PLAYBACK_DEBUG] Adaptive formats count: ${mainPlayerResponse.streamingData?.adaptiveFormats?.size ?: 0}")
        }

        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        // Check if WEB_REMIX response indicates age-restricted
        val mainStatus = mainPlayerResponse.playabilityStatus.status
        val isAgeRestrictedFromResponse = mainStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse && isLoggedIn) {
            // Age-restricted: use WEB_CREATOR directly (no NewPipe needed from here)
            Timber.tag(logTag).d("Age-restricted detected, using WEB_CREATOR")
            Timber.tag(TAG).i("Age-restricted: using WEB_CREATOR for videoId=$videoId")
            val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("WEB_CREATOR works for age-restricted content")
                mainPlayerResponse = creatorResponse
                usedAgeRestrictedClient = WEB_CREATOR
            }
        }

        // If we still don't have a valid response, throw
        if (mainPlayerResponse == null) {
            throw Exception("Failed to get player response")
        }

        val audioConfig = mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = mainPlayerResponse.videoDetails
        val playbackTracking = mainPlayerResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        var retryMainPlayerResponse: PlayerResponse? = if (usedAgeRestrictedClient != null) mainPlayerResponse else null

        // Check current status
        val currentStatus = mainPlayerResponse.playabilityStatus.status
        var isAgeRestricted = currentStatus in listOf("AGE_CHECK_REQUIRED", "AGE_VERIFICATION_REQUIRED", "LOGIN_REQUIRED", "CONTENT_CHECK_REQUIRED")

        if (isAgeRestricted) {
            Timber.tag(logTag).d("Content is still age-restricted (status: $currentStatus), will try fallback clients")
            Timber.tag(TAG)
                .i("Age-restricted content detected: videoId=$videoId, status=$currentStatus")
        }

        // Check if this is a privately owned track (uploaded song)
        val isPrivateTrack = mainPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

        // For private tracks: use TVHTML5 (index 1) with PoToken + n-transform
        // For age-restricted: skip main client, start with fallbacks
        // For normal content: standard order
        val startIndex = when {
            isPrivateTrack -> 1  // TVHTML5
            isAgeRestricted -> 0
            else -> -1
        }

        for (clientIndex in (startIndex until STREAM_FALLBACK_CLIENTS.size)) {
            // reset for each client
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            // decide which client to use for streams and load its player response
            val client: YouTubeClient
            if (clientIndex == -1) {
                // try with streams from main client first (use retry response if available)
                client = MAIN_CLIENT
                streamPlayerResponse = retryMainPlayerResponse ?: mainPlayerResponse
                Timber.tag(logTag).d("Trying stream from MAIN_CLIENT: ${client.clientName}")
            } else {
                // after main client use fallback clients
                client = STREAM_FALLBACK_CLIENTS[clientIndex]
                Timber.tag(logTag).d("Trying fallback client ${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}: ${client.clientName}")

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    // skip client if it requires login but user is not logged in
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")
                // Only pass poToken for clients that support it
                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                // Skip signature timestamp for age-restricted (faster), use it for normal content
                val clientSigTimestamp = if (wasOriginallyAgeRestricted) null else signatureTimestamp.timestamp
                streamPlayerResponse =
                    YouTube.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken).getOrNull()
            }

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")

                // Skip NewPipe for age-restricted content (NewPipe doesn't use our auth)
                val responseToUse = if (wasOriginallyAgeRestricted) {
                    Timber.tag(logTag).d("Skipping NewPipe for age-restricted content")
                    streamPlayerResponse
                } else {
                    // Try to get streams using newPipePlayer method
                    val newPipeResponse = YouTube.newPipePlayer(videoId, streamPlayerResponse)
                    newPipeResponse ?: streamPlayerResponse
                }

                format =
                    findFormat(
                        responseToUse,
                        audioQuality,
                        connectivityManager,
                    )

                if (format == null) {
                    Timber.tag(logTag).d("No suitable format found for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    continue
                }

                Timber.tag(logTag).d("Format found: ${format.mimeType}, bitrate: ${format.bitrate}")

                streamUrl = findUrlOrNull(format, videoId, responseToUse, skipNewPipe = wasOriginallyAgeRestricted)
                if (streamUrl == null) {
                    Timber.tag(logTag).d("Stream URL not found for format")
                    continue
                }

                // Apply n-transform for throttle parameter handling
                val currentClient = if (clientIndex == -1) {
                    usedAgeRestrictedClient ?: MAIN_CLIENT
                } else {
                    STREAM_FALLBACK_CLIENTS[clientIndex]
                }

                // Check if this is a privately owned track
                val isPrivatelyOwnedTrack = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

                // Apply n-transform and PoToken for web clients OR for private tracks (including TVHTML5)
                val needsNTransform = currentClient.useWebPoTokens ||
                    currentClient.clientName in listOf("WEB", "WEB_REMIX", "WEB_CREATOR", "TVHTML5") ||
                    isPrivatelyOwnedTrack

                if (needsNTransform) {
                    try {
                        Timber.tag(logTag).d("Applying n-transform to stream URL for ${currentClient.clientName}")
                        streamUrl = EjsNTransformSolver.transformNParamInUrl(streamUrl!!)

                        // Append pot= parameter with streaming data poToken
                        if ((currentClient.useWebPoTokens || isPrivatelyOwnedTrack) && poToken?.streamingDataPoToken != null) {
                            Timber.tag(logTag).d("Appending pot= parameter to stream URL")
                            val separator = if ("?" in streamUrl!!) "&" else "?"
                            streamUrl = "${streamUrl}${separator}pot=${Uri.encode(poToken.streamingDataPoToken)}"
                        }
                    } catch (e: Exception) {
                        Timber.tag(logTag).e(e, "N-transform or pot append failed: ${e.message}")
                        // Continue with original URL
                    }
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Timber.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                // Check if this is a privately owned track (uploaded song)
                val isPrivatelyOwned = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

                if (clientIndex == STREAM_FALLBACK_CLIENTS.size - 1 || isPrivatelyOwned) {
                    /** skip [validateStatus] for last client or private tracks */
                    if (isPrivatelyOwned) {
                        Timber.tag(logTag).d("Skipping validation for privately owned track: ${currentClient.clientName}")
                        println("[PLAYBACK_DEBUG] Using stream without validation for PRIVATELY_OWNED_TRACK")
                    } else {
                        Timber.tag(logTag).d("Using last fallback client without validation: ${STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    }
                    Timber.tag(TAG)
                        .i("Playback: client=${currentClient.clientName}, videoId=$videoId, private=$isPrivatelyOwned")
                    break
                }

                if (validateStatus(streamUrl!!)) {
                    // working stream found
                    Timber.tag(logTag).d("Stream validated successfully with client: ${currentClient.clientName}")
                    // Log for release builds
                    Timber.tag(TAG).i("Playback: client=${currentClient.clientName}, videoId=$videoId")
                    break
                } else {
                    Timber.tag(logTag).d("Stream validation failed for client: ${currentClient.clientName}")
                }
            } else {
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
            }
        }

        if (streamPlayerResponse == null) {
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            if (isUploadedTrack) {
                println("[PLAYBACK_DEBUG] FAILURE: All clients failed for uploaded track videoId=$videoId")
            }
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
            if (isUploadedTrack) {
                println("[PLAYBACK_DEBUG] FAILURE: Playability not OK for uploaded track - status=${streamPlayerResponse.playabilityStatus.status}, reason=$errorReason")
            }
            throw PlaybackException(
                errorReason,
                null,
                PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        if (streamExpiresInSeconds == null) {
            Timber.tag(logTag).e("Missing stream expire time")
            throw Exception("Missing stream expire time")
        }

        if (format == null) {
            Timber.tag(logTag).e("Could not find format")
            throw Exception("Could not find format")
        }

        if (streamUrl == null) {
            Timber.tag(logTag).e("Could not find stream url")
            throw Exception("Could not find stream url")
        }

        Timber.tag(logTag).d("Successfully obtained playback data with format: ${format.mimeType}, bitrate: ${format.bitrate}")
        if (isUploadedTrack) {
            println("[PLAYBACK_DEBUG] SUCCESS: Got playback data for uploaded track - format=${format.mimeType}, streamUrl=${streamUrl?.take(100)}...")
        }
        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }.onFailure { e ->
        println("[PLAYBACK_DEBUG] EXCEPTION during playback for videoId=$videoId: ${e::class.simpleName}: ${e.message}")
        e.printStackTrace()
    }
    /**
     * Simple player response intended to use for metadata only.
     * Stream URLs of this response might not work so don't use them.
     */
    suspend fun playerResponseForMetadata(
        videoId: String,
        playlistId: String? = null,
    ): Result<PlayerResponse> {
        Timber.tag(logTag).d("Fetching metadata-only player response for videoId: $videoId using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
        return YouTube.player(videoId, playlistId, client = WEB_REMIX) // ANDROID_VR does not work with history
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val format = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.isOriginal }
            ?.maxByOrNull {
                it.bitrate * when (audioQuality) {
                    AudioQuality.AUTO -> if (connectivityManager.isActiveNetworkMetered) -1 else 1
                    AudioQuality.HIGH -> 1
                    AudioQuality.LOW -> -1
                } + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0) // prefer opus stream
            }

        if (format != null) {
            Timber.tag(logTag).d("Selected format: ${format.mimeType}, bitrate: ${format.bitrate}")
        } else {
            Timber.tag(logTag).d("No suitable audio format found")
        }

        return format
    }
    /**
     * Checks if the stream url returns a successful status.
     * If this returns true the url is likely to work.
     * If this returns false the url might cause an error during playback.
     */
    private fun validateStatus(url: String): Boolean {
        Timber.tag(logTag).d("Validating stream URL status")
        try {
            val requestBuilder = okhttp3.Request.Builder()
                .head()
                .url(url)

            // Add authentication cookie for privately owned tracks
            YouTube.cookie?.let { cookie ->
                requestBuilder.addHeader("Cookie", cookie)
                println("[PLAYBACK_DEBUG] Added cookie to validation request")
            }

            val response = httpClient.newCall(requestBuilder.build()).execute()
            val isSuccessful = response.isSuccessful
            Timber.tag(logTag).d("Stream URL validation result: ${if (isSuccessful) "Success" else "Failed"} (${response.code})")
            return isSuccessful
        } catch (e: Exception) {
            Timber.tag(logTag).e(e, "Stream URL validation failed with exception")
            reportException(e)
        }
        return false
    }
    data class SignatureTimestampResult(
        val timestamp: Int?,
        val isAgeRestricted: Boolean
    )

    private fun getSignatureTimestampOrNull(videoId: String): SignatureTimestampResult {
        Timber.tag(logTag).d("Getting signature timestamp for videoId: $videoId")
        val result = NewPipeExtractor.getSignatureTimestamp(videoId)
        return result.fold(
            onSuccess = { timestamp ->
                Timber.tag(logTag).d("Signature timestamp obtained: $timestamp")
                SignatureTimestampResult(timestamp, isAgeRestricted = false)
            },
            onFailure = { error ->
                val isAgeRestricted = error.message?.contains("age-restricted", ignoreCase = true) == true ||
                    error.cause?.message?.contains("age-restricted", ignoreCase = true) == true
                if (isAgeRestricted) {
                    Timber.tag(logTag).d("Age-restricted content detected from NewPipe")
                    Timber.tag(TAG).i("Age-restricted detected early via NewPipe: videoId=$videoId")
                } else {
                    Timber.tag(logTag).e(error, "Failed to get signature timestamp")
                    reportException(error)
                }
                SignatureTimestampResult(null, isAgeRestricted)
            }
        )
    }

    private suspend fun findUrlOrNull(
        format: PlayerResponse.StreamingData.Format,
        videoId: String,
        playerResponse: PlayerResponse,
        skipNewPipe: Boolean = false
    ): String? {
        Timber.tag(logTag).d("Finding stream URL for format: ${format.mimeType}, videoId: $videoId, skipNewPipe: $skipNewPipe")

        // First check if format already has a URL
        if (!format.url.isNullOrEmpty()) {
            Timber.tag(logTag).d("Using URL from format directly")
            return format.url
        }

        // Try custom cipher deobfuscation for signatureCipher formats
        val signatureCipher = format.signatureCipher ?: format.cipher
        if (!signatureCipher.isNullOrEmpty()) {
            Timber.tag(logTag).d("Format has signatureCipher, using custom deobfuscation")
            val customDeobfuscatedUrl = CipherDeobfuscator.deobfuscateStreamUrl(signatureCipher, videoId)
            if (customDeobfuscatedUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained via custom cipher deobfuscation")
                return customDeobfuscatedUrl
            }
            Timber.tag(logTag).d("Custom cipher deobfuscation failed")
        }

        // Skip NewPipe for age-restricted content
        if (skipNewPipe) {
            Timber.tag(logTag).d("Skipping NewPipe methods for age-restricted content")
            return null
        }

        // Try to get URL using NewPipeExtractor signature deobfuscation
        val deobfuscatedUrl = NewPipeExtractor.getStreamUrl(format, videoId)
        if (deobfuscatedUrl != null) {
            Timber.tag(logTag).d("Stream URL obtained via NewPipe deobfuscation")
            return deobfuscatedUrl
        }

        // Fallback: try to get URL from StreamInfo
        Timber.tag(logTag).d("Trying StreamInfo fallback for URL")
        val streamUrls = YouTube.getNewPipeStreamUrls(videoId)
        if (streamUrls.isNotEmpty()) {
            val streamUrl = streamUrls.find { it.first == format.itag }?.second
            if (streamUrl != null) {
                Timber.tag(logTag).d("Stream URL obtained from StreamInfo")
                return streamUrl
            }

            // If exact itag not found, try to find any audio stream
            val audioStream = streamUrls.find { urlPair ->
                playerResponse.streamingData?.adaptiveFormats?.any {
                    it.itag == urlPair.first && it.isAudio
                } == true
            }?.second

            if (audioStream != null) {
                Timber.tag(logTag).d("Audio stream URL obtained from StreamInfo (different itag)")
                return audioStream
            }
        }

        Timber.tag(logTag).e("Failed to get stream URL")
        return null
    }

    fun forceRefreshForVideo(videoId: String) {
        Timber.tag(logTag).d("Force refreshing for videoId: $videoId")
    }

    /**
     * Get adaptive video playback data with separate video and audio URLs.
     * This enables 1080p+ playback using ExoPlayer's MergingMediaSource.
     * Uses TVHTML5 client only - if it fails, VideoPlayerScreen falls back to progressive (720p).
     *
     * @param videoId The YouTube video ID
     * @param targetHeight Target video height (e.g., 1080, 720). If null, picks highest available.
     * @param preferMp4 Set true for downloads - MediaMuxer only supports H.264/MP4
     * @return AdaptiveVideoData with separate video/audio URLs, or failure if not available
     */
    suspend fun getAdaptiveVideoData(
        videoId: String,
        targetHeight: Int? = null,
        preferMp4: Boolean = false,
    ): Result<AdaptiveVideoData> = runCatching {
        // Use TVHTML5 for adaptive video - doesn't require n-transform
        // If this fails, VideoPlayerScreen will fallback to progressive playback (720p max)
        val client = TVHTML5

        Timber.tag(TAG).d("=== Adaptive video START for videoId=$videoId, targetHeight=$targetHeight, preferMp4=$preferMp4 ===")
        Timber.tag(TAG).d("Using client: ${client.clientName} (useWebPoTokens=${client.useWebPoTokens})")

        val signatureTimestamp = getSignatureTimestampOrNull(videoId).timestamp
        Timber.tag(TAG).d("Adaptive: signature timestamp=$signatureTimestamp")

        // Auth and PoToken setup
        val isLoggedIn = YouTube.cookie != null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId ?: YouTube.visitorData else YouTube.visitorData
        Timber.tag(TAG).d("Adaptive: isLoggedIn=$isLoggedIn, sessionId=${sessionId?.take(10)}...")

        val poTokenResult: PoTokenResult? = try {
            if (sessionId == null || !client.useWebPoTokens) {
                Timber.tag(TAG).d("Adaptive: Skipping PoToken (sessionId=$sessionId, useWebPoTokens=${client.useWebPoTokens})")
                null
            } else {
                Timber.tag(TAG).d("Adaptive: Generating PoToken...")
                poTokenGenerator.getWebClientPoToken(videoId, sessionId)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Adaptive: PoToken generation failed")
            null
        }
        Timber.tag(TAG).d("Adaptive: poTokenResult=${poTokenResult?.playerRequestPoToken?.take(20)}...")

        // Fetch player response
        Timber.tag(TAG).d("Adaptive: Fetching player response with client ${client.clientName}...")
        val playerResult = YouTube.player(
            videoId, null, client, signatureTimestamp,
            poToken = if (client.useWebPoTokens) poTokenResult?.playerRequestPoToken else null
        )

        if (playerResult.isFailure) {
            Timber.tag(TAG).e(playerResult.exceptionOrNull(), "Adaptive: YouTube.player() FAILED")
            throw playerResult.exceptionOrNull() ?: Exception("Unknown player error")
        }

        val playerResponse = playerResult.getOrThrow()
        Timber.tag(TAG).d("Adaptive: Player response status=${playerResponse.playabilityStatus.status}")

        if (playerResponse.playabilityStatus.status != "OK") {
            throw androidx.media3.common.PlaybackException(
                playerResponse.playabilityStatus.reason ?: "Playback not available",
                null, androidx.media3.common.PlaybackException.ERROR_CODE_REMOTE_ERROR
            )
        }

        val adaptiveFormats = playerResponse.streamingData?.adaptiveFormats
            ?: throw androidx.media3.common.PlaybackException("No adaptive formats", null, androidx.media3.common.PlaybackException.ERROR_CODE_REMOTE_ERROR)

        // Get video formats (video-only, sorted by height descending)
        val videoFormats = adaptiveFormats
            .filter { !it.isAudio && (it.height ?: 0) > 0 }
            .sortedByDescending { it.height ?: 0 }

        if (videoFormats.isEmpty()) {
            throw androidx.media3.common.PlaybackException("No video formats available", null, androidx.media3.common.PlaybackException.ERROR_CODE_REMOTE_ERROR)
        }

        // Build available qualities list (show all for selection)
        val availableQualities = videoFormats
            .distinctBy { it.height }
            .mapNotNull { format ->
                val height = format.height ?: return@mapNotNull null
                VideoQualityInfo(
                    height = height,
                    width = format.width,
                    fps = format.fps,
                    bitrate = format.bitrate,
                    label = buildString {
                        append("${height}p")
                        format.fps?.takeIf { it > 30 }?.let { append(" ${it}fps") }
                    },
                    format = format
                )
            }

        // When preferMp4 is true (for downloads), only use MP4 formats (MediaMuxer doesn't support WebM/VP9)
        val filteredFormats = if (preferMp4) {
            videoFormats.filter { it.mimeType.contains("mp4") }.ifEmpty { videoFormats }
        } else {
            videoFormats
        }

        val selectedVideoFormat = if (targetHeight != null) {
            // Find exact match or closest lower
            filteredFormats.find { it.height == targetHeight }
                ?: filteredFormats.filter { (it.height ?: 0) <= targetHeight }.maxByOrNull { it.height ?: 0 }
                ?: filteredFormats.first()
        } else {
            // Pick highest quality, prefer MP4 over WebM
            val mp4Formats = filteredFormats.filter { it.mimeType.contains("mp4") }
            (mp4Formats.ifEmpty { filteredFormats }).first()
        }

        Timber.tag(TAG).d("Selected video: ${selectedVideoFormat.height}p, itag=${selectedVideoFormat.itag}, mime=${selectedVideoFormat.mimeType}")

        // Get best audio format (prefer AAC/M4A for compatibility)
        val audioFormat = adaptiveFormats
            .filter { it.isAudio && it.isOriginal }
            .let { audioFormats ->
                // Prefer MP4/AAC for compatibility
                audioFormats.filter { it.mimeType.contains("mp4") }.maxByOrNull { it.bitrate }
                    ?: audioFormats.maxByOrNull { it.bitrate }
            }
            ?: throw androidx.media3.common.PlaybackException("No audio format available", null, androidx.media3.common.PlaybackException.ERROR_CODE_REMOTE_ERROR)

        Timber.tag(TAG).d("Selected audio: itag=${audioFormat.itag}, mime=${audioFormat.mimeType}, bitrate=${audioFormat.bitrate}")

        // Resolve URLs
        var videoUrl = findUrlOrNull(selectedVideoFormat, videoId, playerResponse, skipNewPipe = false)
            ?: throw androidx.media3.common.PlaybackException("Cannot resolve video URL", null, androidx.media3.common.PlaybackException.ERROR_CODE_REMOTE_ERROR)
        var audioUrl = findUrlOrNull(audioFormat, videoId, playerResponse, skipNewPipe = false)
            ?: throw androidx.media3.common.PlaybackException("Cannot resolve audio URL", null, androidx.media3.common.PlaybackException.ERROR_CODE_REMOTE_ERROR)

        // Apply PoToken FIRST (before n-transform for TVHTML5)
        if (client.useWebPoTokens && poTokenResult?.streamingDataPoToken != null) {
            val pot = poTokenResult.streamingDataPoToken
            videoUrl = appendQueryParam(videoUrl, "pot", pot)
            audioUrl = appendQueryParam(audioUrl, "pot", pot)
            Timber.tag(TAG).d("Appended PoToken to URLs")
        }

        // Apply n-transform proactively
        if (client.useWebPoTokens) {
            try {
                videoUrl = EjsNTransformSolver.transformNParamInUrl(videoUrl)
                audioUrl = EjsNTransformSolver.transformNParamInUrl(audioUrl)
                Timber.tag(TAG).d("N-transform applied to URLs")
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "N-transform failed: ${e.message}")
            }
        }

        // Validate URLs for web clients (non-web clients like TVHTML5 don't need n-transform)
        if (client.useWebPoTokens) {
            if (!validateStatus(videoUrl)) {
                Timber.tag(TAG).w("Video URL validation failed, trying CipherDeobfuscator n-transform...")
                try {
                    val transformed = CipherDeobfuscator.transformNParamInUrl(videoUrl)
                    if (transformed != videoUrl && validateStatus(transformed)) {
                        videoUrl = transformed
                        Timber.tag(TAG).d("Video URL fixed with CipherDeobfuscator n-transform")
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "CipherDeobfuscator n-transform failed for video: ${e.message}")
                }
            }

            if (!validateStatus(audioUrl)) {
                Timber.tag(TAG).w("Audio URL validation failed, trying CipherDeobfuscator n-transform...")
                try {
                    val transformed = CipherDeobfuscator.transformNParamInUrl(audioUrl)
                    if (transformed != audioUrl && validateStatus(transformed)) {
                        audioUrl = transformed
                        Timber.tag(TAG).d("Audio URL fixed with CipherDeobfuscator n-transform")
                    }
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "CipherDeobfuscator n-transform failed for audio: ${e.message}")
                }
            }

            Timber.tag(TAG).d("Video URL validated: ${validateStatus(videoUrl)}")
            Timber.tag(TAG).d("Audio URL validated: ${validateStatus(audioUrl)}")
        } else {
            Timber.tag(TAG).d("Skipping URL validation for non-web client: ${client.clientName}")
        }

        val expiresInSeconds = playerResponse.streamingData?.expiresInSeconds ?: (6 * 60 * 60)

        Timber.tag(TAG).d("=== Adaptive video SUCCESS: ${selectedVideoFormat.height}p, expires=${expiresInSeconds}s ===")

        AdaptiveVideoData(
            videoDetails = playerResponse.videoDetails,
            videoUrl = videoUrl,
            audioUrl = audioUrl,
            videoFormat = selectedVideoFormat,
            audioFormat = audioFormat,
            expiresInSeconds = expiresInSeconds,
            availableQualities = availableQualities,
        )
    }

    /**
     * Get progressive video playback data (combined video+audio in single stream).
     * Limited to 720p max. Falls back from adaptive playback.
     *
     * @param isVideoFallback Set true when falling back from adaptive - uses VIDEO_FALLBACK_CLIENTS (excludes TVHTML5)
     */
    suspend fun playerResponseForVideoPlayback(
        videoId: String,
        playlistId: String? = null,
        connectivityManager: ConnectivityManager,
        maxVideoBitrateKbps: Int? = null,
        isVideoFallback: Boolean = false,
    ): Result<PlaybackData> = runCatching {
        // For video fallback, use VIDEO_FALLBACK_CLIENTS which excludes TVHTML5 (already tried for adaptive)
        val fallbackClients = if (isVideoFallback) VIDEO_FALLBACK_CLIENTS else STREAM_FALLBACK_CLIENTS

        Timber.tag(TAG).d("=== Video playback START for videoId=$videoId, isVideoFallback=$isVideoFallback ===")

        val defaultStreamTtlSeconds = 6 * 60 * 60 // 6 hours
        val signatureTimestamp = getSignatureTimestampOrNull(videoId).timestamp
        val isLoggedIn = YouTube.cookie != null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId ?: YouTube.visitorData else YouTube.visitorData

        var poToken: PoTokenResult? = null
        if (MAIN_CLIENT.useWebPoTokens && sessionId != null) {
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "PoToken generation failed")
            }
        }

        Timber.tag(TAG).d("Fetching main player response with client: ${MAIN_CLIENT.clientName}")
        val mainPlayerResponse = YouTube.player(videoId, playlistId, MAIN_CLIENT, signatureTimestamp, poToken?.playerRequestPoToken).getOrThrow()
        Timber.tag(TAG).d("Main response status: ${mainPlayerResponse.playabilityStatus.status}")

        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        var successClient: String? = null

        for (clientIndex in (-1 until fallbackClients.size)) {
            format = null
            streamUrl = null
            streamExpiresInSeconds = null

            val client: YouTubeClient
            if (clientIndex == -1) {
                client = MAIN_CLIENT
                streamPlayerResponse = mainPlayerResponse
                Timber.tag(TAG).d("--- Trying streams from main client: ${client.clientName} ---")
            } else {
                client = fallbackClients[clientIndex]
                Timber.tag(TAG).d("--- Trying fallback client ${clientIndex + 1}/${fallbackClients.size}: ${client.clientName} ---")

                if (client.loginRequired && !isLoggedIn) {
                    Timber.tag(TAG).d("Skipping ${client.clientName} - requires login but not authenticated")
                    continue
                }

                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                streamPlayerResponse = YouTube.player(videoId, playlistId, client, signatureTimestamp, clientPoToken).getOrNull()
            }

            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(TAG).d("Status OK for ${client.clientName}")

                // Find progressive video format (combined video+audio)
                format = findVideoFormat(streamPlayerResponse, maxVideoBitrateKbps)
                if (format == null) {
                    Timber.tag(TAG).d("No suitable video format found for ${client.clientName}")
                    continue
                }
                Timber.tag(TAG).d("Format: itag=${format.itag}, mime=${format.mimeType}, bitrate=${format.bitrate}, height=${format.height}")

                streamUrl = findUrlOrNull(format, videoId, streamPlayerResponse, skipNewPipe = false)
                if (streamUrl == null) {
                    Timber.tag(TAG).d("No stream URL for format on ${client.clientName}")
                    continue
                }
                Timber.tag(TAG).d("Stream URL (${client.clientName}): ${streamUrl.take(80)}...")

                // Append streaming PoToken before validation (for web clients)
                if (client.useWebPoTokens && poToken?.streamingDataPoToken != null) {
                    val separator = if ("?" in streamUrl) "&" else "?"
                    streamUrl = "${streamUrl}${separator}pot=${poToken.streamingDataPoToken}"
                    Timber.tag(TAG).d("Appended streaming PoToken to URL")
                }

                // Apply n-transform proactively for web clients (avoids 403 round-trip)
                if (client.useWebPoTokens) {
                    Timber.tag(TAG).d("Attempting proactive n-transform...")
                    try {
                        val transformed = EjsNTransformSolver.transformNParamInUrl(streamUrl)
                        if (transformed != streamUrl) {
                            streamUrl = transformed
                            Timber.tag(TAG).d("Proactive n-transform applied successfully")
                        } else {
                            Timber.tag(TAG).d("Proactive n-transform returned same URL (no change)")
                        }
                    } catch (e: Exception) {
                        Timber.tag(TAG).w(e, "Proactive n-transform failed, will retry if needed: ${e.message}")
                    }
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds ?: defaultStreamTtlSeconds
                Timber.tag(TAG).d("Expires in: ${streamExpiresInSeconds}s")

                if (streamExpiresInSeconds <= 0) {
                    Timber.tag(TAG).d("Stream already expired, skipping")
                    continue
                }

                if (clientIndex == fallbackClients.size - 1) {
                    Timber.tag(TAG).d("Last fallback - skipping validation: ${client.clientName}")
                    successClient = client.clientName
                    break
                }

                val validationResult = validateStatus(streamUrl)
                if (validationResult) {
                    Timber.tag(TAG).d("Stream VALIDATED OK with ${client.clientName}")
                    successClient = client.clientName
                    break
                } else {
                    Timber.tag(TAG).d("Stream validation FAILED for ${client.clientName}")

                    // For web clients: try n-parameter transform and re-validate
                    if (client.useWebPoTokens) {
                        var nTransformWorked = false

                        // Try CipherDeobfuscator n-transform first
                        try {
                            val nTransformed = CipherDeobfuscator.transformNParamInUrl(streamUrl)
                            if (nTransformed != streamUrl) {
                                Timber.tag(TAG).d("CipherDeobfuscator n-transform applied, re-validating...")
                                if (validateStatus(nTransformed)) {
                                    Timber.tag(TAG).d("N-transformed URL VALIDATED OK!")
                                    streamUrl = nTransformed
                                    nTransformWorked = true
                                    successClient = client.clientName
                                }
                            }
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "CipherDeobfuscator n-transform error")
                        }

                        // If CipherDeobfuscator failed, try EjsNTransformSolver
                        if (!nTransformWorked) {
                            try {
                                val ejsTransformed = EjsNTransformSolver.transformNParamInUrl(streamUrl)
                                if (ejsTransformed != streamUrl) {
                                    Timber.tag(TAG).d("EJS n-transform applied, re-validating...")
                                    if (validateStatus(ejsTransformed)) {
                                        Timber.tag(TAG).d("EJS n-transformed URL VALIDATED OK!")
                                        streamUrl = ejsTransformed
                                        nTransformWorked = true
                                        successClient = client.clientName
                                    }
                                }
                            } catch (e: Exception) {
                                Timber.tag(TAG).e(e, "EJS n-transform error")
                            }
                        }

                        if (nTransformWorked) break
                    }
                }
            } else {
                Timber.tag(TAG).d("Status NOT OK for ${client.clientName}: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
            }
        }

        if (format == null || streamUrl == null || streamExpiresInSeconds == null) {
            Timber.tag(TAG).e("All clients failed for video $videoId")
            throw Exception("Could not find video stream")
        }

        Timber.tag(TAG).d("=== Video playback SUCCESS: client=${successClient ?: "unknown"}, itag=${format.itag}, expires=${streamExpiresInSeconds}s ===")
        Log.i(TAG, "Video playback: client=${successClient ?: "unknown"}, itag=${format.itag}, videoId=$videoId")

        PlaybackData(
            mainPlayerResponse.playerConfig?.audioConfig,
            mainPlayerResponse.videoDetails,
            mainPlayerResponse.playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }

    private fun findVideoFormat(
        playerResponse: PlayerResponse,
        maxVideoBitrateKbps: Int?,
    ): PlayerResponse.StreamingData.Format? {
        val progressive = playerResponse.streamingData?.formats.orEmpty()
            .filter { it.mimeType.startsWith("video") && (it.audioQuality != null || it.audioChannels != null) }
        val progressiveMp4 = progressive.filter { it.mimeType.contains("mp4") }
        val ordered = (progressiveMp4.ifEmpty { progressive }).sortedBy { it.bitrate }
        val capped = maxVideoBitrateKbps?.let { cap ->
            ordered.filter { (it.bitrate / 1000) <= cap }
        }.orEmpty()
        return when {
            capped.isNotEmpty() -> capped.maxByOrNull { it.bitrate }
            else -> ordered.maxByOrNull { it.bitrate }
        }
    }

    private fun appendQueryParam(url: String, key: String, value: String): String {
        val separator = if ("?" in url) "&" else "?"
        return "$url$separator$key=$value"
    }
}
