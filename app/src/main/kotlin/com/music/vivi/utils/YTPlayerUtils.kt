/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.music.vivi.utils

import android.net.ConnectivityManager
import android.util.Log
import androidx.media3.common.PlaybackException
import com.music.innertube.NewPipeExtractor
import com.music.innertube.YouTube
import com.music.innertube.models.YouTubeClient
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_CREATOR
import com.music.vivi.utils.BotDetectionMitigator
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_43_32
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_1_61_48
import com.music.innertube.models.YouTubeClient.Companion.ANDROID_VR_NO_AUTH
import com.music.innertube.models.YouTubeClient.Companion.IOS
import com.music.innertube.models.YouTubeClient.Companion.IPADOS
import com.music.innertube.models.YouTubeClient.Companion.MOBILE
import com.music.innertube.models.YouTubeClient.Companion.TVHTML5
import com.music.innertube.models.YouTubeClient.Companion.TVHTML5_SIMPLY_EMBEDDED_PLAYER
import com.music.innertube.models.YouTubeClient.Companion.WEB
import com.music.innertube.models.YouTubeClient.Companion.WEB_CREATOR
import com.music.innertube.models.YouTubeClient.Companion.WEB_REMIX
import com.music.innertube.models.response.PlayerResponse
import com.music.vivi.constants.AudioQuality
import com.music.vivi.utils.YTPlayerUtils.MAIN_CLIENT
import com.music.vivi.utils.YTPlayerUtils.STREAM_FALLBACK_CLIENTS
import com.music.vivi.utils.YTPlayerUtils.validateStatus
import com.music.vivi.utils.potoken.PoTokenGenerator
import com.music.vivi.utils.potoken.PoTokenResult
import com.music.vivi.utils.PlaybackLogLevel
import com.music.vivi.utils.PlaybackLogManager
import com.music.innertube.models.IpVersion
import com.music.innertube.models.WatchEndpoint
import com.music.jiosaavn.SaavnService
import okhttp3.Dns
import okhttp3.OkHttpClient
import timber.log.Timber
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object YTPlayerUtils {
    private const val logTag = "YTPlayerUtils"
    private const val TAG = "YTPlayerUtils"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .dns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val addresses = Dns.SYSTEM.lookup(hostname)
                return when (YouTube.ipVersion) {
                    IpVersion.IPV4 -> addresses.filter { it is Inet4Address }.ifEmpty { addresses }
                    IpVersion.IPV6 -> addresses.filter { it is Inet6Address }.ifEmpty { addresses }
                    IpVersion.AUTO -> addresses
                }
            }
        })
        .proxySelector(object : ProxySelector() {
            override fun select(uri: URI?): List<Proxy> = listOfNotNull(YouTube.proxy ?: Proxy.NO_PROXY)
            override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
                Timber.tag(TAG).e(ioe, "Proxy connection failed for URI: $uri")
            }
        })
        .proxyAuthenticator { _, response ->
            YouTube.proxyAuth?.let { auth ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", auth)
                    .build()
            } ?: response.request
        }
        .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val poTokenGenerator = PoTokenGenerator()

    /**
     * Client used for fast, low-latency stream resolution.
     * ANDROID_VR clients don't require PoToken and start instantly.
     * Note: ANDROID_VR has loginSupported=false, so metadata like audioConfig and
     * playbackTracking must be supplemented from an authenticated client (WEB_REMIX)
     * when the user is logged in.
     */
    private val MAIN_CLIENT: YouTubeClient = ANDROID_VR_1_43_32

    /**
     * Client used to fetch metadata (audioConfig, playbackTracking) when the user is
     * logged in. This ensures remote YouTube history is correctly updated.
     */
    private val METADATA_CLIENT: YouTubeClient = WEB_REMIX

    private val STREAM_FALLBACK_CLIENTS: Array<YouTubeClient> = arrayOf(
        ANDROID_VR_1_61_48,
        WEB_REMIX,
        TVHTML5_SIMPLY_EMBEDDED_PLAYER,  // Try embedded player first for age-restricted content
        TVHTML5,
        ANDROID_CREATOR,
        IPADOS,
        ANDROID_VR_NO_AUTH,
        MOBILE,
        IOS,
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
        /** True when the stream is sourced from JioSaavn (not YouTube). */
        val isSaavnStream: Boolean = false,
    )
    /**
     * Custom player response intended to use for playback.
     * Stream URLs come from [MAIN_CLIENT] or [STREAM_FALLBACK_CLIENTS] for fast loading.
     * Metadata (audioConfig, playbackTracking) come from [METADATA_CLIENT] (WEB_REMIX)
     * when the user is logged in, to ensure remote history recording works correctly.
     */
    private fun sanitizeTitle(title: String): String {
        if (title.isBlank()) return ""
        return title
            .replace(Regex("(?i)\\[(official\\s*(music\\s*)?video|video|audio|lyrics?|hd|4k|visualizer|remastered|explicit|hq)\\]"), "")
            .replace(Regex("(?i)\\((official\\s*(music\\s*)?video|video|audio|lyrics?|hd|4k|visualizer|remastered|explicit|hq)\\)"), "")
            .replace(Regex("(?i)\\[feat\\..*?\\]"), "")
            .replace(Regex("(?i)\\(feat\\..*?\\)"), "")
            .replace(Regex("(?i)\\[ft\\..*?\\]"), "")
            .replace(Regex("(?i)\\(ft\\..*?\\)"), "")
            .replace(Regex("(?i)\\b(feat|ft)\\.?\\s+.*$"), "")
            .replace(Regex("(?i)-\\s*single$"), "")
            .replace(Regex("(?i)-\\s*ep$"), "")
            .replace(Regex("(?i)-\\s*topic$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeForMatching(text: String): String {
        return text.lowercase(java.util.Locale.US)
            .replace("’", "'")
            .replace("‘", "'")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("–", "-")
            .replace("—", "-")
            .replace("&", "and")
            .replace(Regex("[^a-z0-9\\s]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun calculateLevenshteinSimilarity(s1: String, s2: String): Double {
        if (s1 == s2) return 1.0
        if (s1.isEmpty() || s2.isEmpty()) return 0.0
        val d = Array(s1.length + 1) { IntArray(s2.length + 1) }
        for (i in 0..s1.length) d[i][0] = i
        for (j in 0..s2.length) d[0][j] = j
        for (i in 1..s1.length) {
            for (j in 1..s2.length) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                d[i][j] = minOf(
                    d[i - 1][j] + 1,
                    d[i][j - 1] + 1,
                    d[i - 1][j - 1] + cost
                )
            }
        }
        val maxLen = maxOf(s1.length, s2.length)
        return 1.0 - (d[s1.length][s2.length].toDouble() / maxLen)
    }

    private fun calculateTokenJaccard(s1: String, s2: String): Double {
        val tokens1 = s1.split(" ").filter { it.isNotBlank() }.toSet()
        val tokens2 = s2.split(" ").filter { it.isNotBlank() }.toSet()
        if (tokens1.isEmpty() && tokens2.isEmpty()) return 1.0
        if (tokens1.isEmpty() || tokens2.isEmpty()) return 0.0
        val intersection = tokens1.intersect(tokens2).size
        val union = tokens1.union(tokens2).size
        return intersection.toDouble() / union
    }

    private fun calculateTitleScore(cleanWanted: String, cleanCandidate: String): Double {
        val normWanted = normalizeForMatching(cleanWanted)
        val normCand = normalizeForMatching(cleanCandidate)
        if (normWanted == normCand) return 1.0
        if (normWanted.isEmpty() || normCand.isEmpty()) return 0.0

        val levSim = calculateLevenshteinSimilarity(normWanted, normCand)
        val jaccardSim = calculateTokenJaccard(normWanted, normCand)
        var bestScore = maxOf(levSim, (levSim * 0.5) + (jaccardSim * 0.5))

        // Check if candidate title is embedded inside "Artist - Title" format
        if (cleanWanted.contains(" - ") || cleanWanted.contains("-")) {
            val parts = cleanWanted.split("-").map { normalizeForMatching(it) }.filter { it.isNotBlank() }
            for (part in parts) {
                val partLev = calculateLevenshteinSimilarity(part, normCand)
                val partJac = calculateTokenJaccard(part, normCand)
                val partScore = maxOf(partLev, (partLev * 0.5) + (partJac * 0.5))
                if (partScore > bestScore) bestScore = partScore
            }
        }

        // Substring check (e.g. "blinding lights" inside "the weeknd blinding lights")
        if (normWanted.contains(normCand) || normCand.contains(normWanted)) {
            val minLen = minOf(normWanted.length, normCand.length)
            val maxLen = maxOf(normWanted.length, normCand.length)
            if (minLen.toDouble() / maxLen >= 0.35) {
                bestScore = maxOf(bestScore, 0.90)
            }
        }

        return bestScore
    }

    private fun scoreCandidate(
        candidate: com.music.jiosaavn.SaavnSong,
        cleanWantedTitle: String,
        wantedArtists: List<String>,
        expectedDuration: Int?,
        wantedExplicit: Boolean
    ): Double {
        // Discard pro-only gated tracks (these return truncated 30s clips)
        if (candidate.isProOnly) return 0.0

        val cleanCandTitle = sanitizeTitle(candidate.name)
        val titleScore = calculateTitleScore(cleanWantedTitle, cleanCandTitle)
        if (titleScore < 0.60) return 0.0

        // 2. Artist score (match against primary + featured + all)
        val candAllArtists = (candidate.artists.primary + candidate.artists.featured + candidate.artists.all)
            .flatMap { it.name.split(",", "&", "feat", "ft", "with", "and", "/", "x") }
            .map { normalizeForMatching(it) }
            .filter { it.isNotBlank() }
            .toSet()

        val splitWantedArtists = wantedArtists
            .flatMap { it.split(",", "&", "feat", "ft", "with", "and", "/", "x") }
            .map { normalizeForMatching(it) }
            .filter { it.isNotBlank() }

        var artistScore = 0.0
        if (splitWantedArtists.isEmpty()) {
            artistScore = 0.80
        } else {
            var matchCount = 0
            for (wArtist in splitWantedArtists) {
                val matched = candAllArtists.any { cArtist ->
                    cArtist == wArtist || cArtist.contains(wArtist) || wArtist.contains(cArtist) ||
                    calculateLevenshteinSimilarity(cArtist, wArtist) >= 0.75
                }
                if (matched) matchCount++
            }
            artistScore = if (matchCount > 0) 1.0 else 0.0
        }

        // If artist did not match at all, only proceed if title score is near-perfect (>= 0.95)
        if (artistScore == 0.0) {
            if (titleScore < 0.95) return 0.0
            artistScore = 0.50
        }

        // 3. Duration Score
        var durationScore = 1.0
        val candDuration = candidate.duration
        if (expectedDuration != null && candDuration != null && candDuration > 0) {
            val diff = Math.abs(expectedDuration - candDuration)
            durationScore = when {
                diff <= 3 -> 1.0
                diff <= 8 -> 0.95
                diff <= 16 -> 0.85
                diff <= 28 -> 0.70
                diff <= 45 -> 0.40
                else -> 0.0
            }
        }
        if (durationScore == 0.0) return 0.0

        // 4. Version preservation / suspicious keyword penalty (prevents karaoke/cover/tribute match)
        var versionPenalty = 0.0
        val candLower = candidate.name.lowercase(java.util.Locale.US)
        val origLower = cleanWantedTitle.lowercase(java.util.Locale.US)
        val suspiciousWords = listOf("karaoke", "tribute", "cover", "instrumental", "acoustic", "live", "remix", "mashup", "reverb", "slowed", "flip")
        for (word in suspiciousWords) {
            val candHas = candLower.contains(word)
            val origHas = origLower.contains(word)
            if (candHas && !origHas) {
                versionPenalty += 0.40
            }
        }

        // 5. Explicit flag bonus
        val explicitBonus = if (candidate.explicitContent == wantedExplicit) 0.05 else 0.0

        val totalScore = (titleScore * 0.45) + (artistScore * 0.35) + (durationScore * 0.20) + explicitBonus - versionPenalty
        return totalScore.coerceIn(0.0, 1.0)
    }

    /**
     * Custom player response intended to use for playback and downloading.
     * Automatically resolves between JioSaavn and YouTube Music based on the user's configured [audioQuality] tier:
     * - MAX (193-320 kbps): Target 320 kbps from JioSaavn, falling back to YouTube Music highest.
     * - HIGH (129-192 kbps): Target 192 kbps from JioSaavn/YouTube, falling back to YouTube Music highest in range.
     * - MEDIUM (65-128 kbps): Target 128 kbps from JioSaavn/YouTube, falling back to YouTube Music in range.
     * - LOW (40-64 kbps): Target 64 kbps from YouTube Music low streams (itag 249/139).
     */
    suspend fun playerResponseForPlayback(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
        isDownload: Boolean = false,
        context: android.content.Context? = null,
    ): Result<PlaybackData> {
        // Low Quality: Strictly YouTube Music (40-64 kbps), bypass external search
        if (audioQuality == AudioQuality.LOW) {
            val firstAttempt = resolvePlaybackData(videoId, playlistId, AudioQuality.LOW, connectivityManager)
            if (firstAttempt.isFailure && YouTube.cookie == null) {
                Timber.tag(TAG).w("Playback failed for guest. Rotating session and retrying...")
                PlaybackLogManager.log(PlaybackLogLevel.BOT, "Playback failed for guest", "Triggering bot detection mitigation (rotating guest session)")
                BotDetectionMitigator.rotateGuestSession()
                val retryResult = resolvePlaybackData(videoId, playlistId, AudioQuality.LOW, connectivityManager)
                retryResult.onSuccess { BotDetectionMitigator.notifyPlaybackSuccess() }
                return retryResult
            }
            firstAttempt.onSuccess { BotDetectionMitigator.notifyPlaybackSuccess() }
            return firstAttempt
        }

        // For Max, High, and Medium: Concurrently search JioSaavn while fetching YouTube Music metadata
        return coroutineScope {
            val ytDeferred = async(Dispatchers.IO) {
                resolvePlaybackData(videoId, playlistId, audioQuality, connectivityManager)
            }

            val saavnPlayback = runCatching {
                val ytResult = ytDeferred.await().getOrNull() ?: return@runCatching null
                val cleanTitle = sanitizeTitle(ytResult.videoDetails?.title.orEmpty())
                if (cleanTitle.isBlank()) return@runCatching null

                val author = sanitizeTitle(ytResult.videoDetails?.author.orEmpty())
                val artistNames = if (author.isNotBlank()) listOf(author) else emptyList()
                val expectedDuration = ytResult.videoDetails?.lengthSeconds?.toIntOrNull()

                val pureSongTitle = if (cleanTitle.contains(" - ")) cleanTitle.substringAfter(" - ").trim() else cleanTitle
                val primaryQuery = if (author.isNotBlank()) "$pureSongTitle $author" else cleanTitle
                val fallbackQuery = if (pureSongTitle != cleanTitle) pureSongTitle else cleanTitle

                suspend fun findCandidate(query: String): com.music.jiosaavn.SaavnSong? {
                    if (query.isBlank()) return null
                    val rawSongs = SaavnService.searchSongs(query).getOrNull() ?: return null
                    val scoredCandidates = rawSongs.map { candidate ->
                        candidate to scoreCandidate(
                            candidate = candidate,
                            cleanWantedTitle = cleanTitle,
                            wantedArtists = artistNames,
                            expectedDuration = expectedDuration,
                            wantedExplicit = false
                        )
                    }.filter { it.second >= 0.72 }
                    .sortedByDescending { it.second }
                    return scoredCandidates.firstOrNull()?.first
                }

                var bestSong = findCandidate(primaryQuery)
                if (bestSong == null && primaryQuery != fallbackQuery) {
                    bestSong = findCandidate(fallbackQuery)
                }

                if (bestSong == null) return@runCatching null

                val minKbps = audioQuality.minBitrate / 1000
                val maxKbps = audioQuality.maxBitrate / 1000
                val targetKbps = audioQuality.targetDownloadBitrate / 1000

                val selectedStream = SaavnService.selectUrlForBitrateTier(
                    bestSong.downloadUrl,
                    minKbps = minKbps,
                    maxKbps = maxKbps,
                    targetKbps = targetKbps
                ) ?: run {
                    val fallbackQuality = when (audioQuality) {
                        AudioQuality.MAX -> "320kbps"
                        AudioQuality.HIGH -> "160kbps"
                        AudioQuality.MEDIUM -> "96kbps"
                        AudioQuality.LOW -> "48kbps"
                    }
                    val url = SaavnService.selectBestUrl(bestSong.downloadUrl, fallbackQuality)
                        ?: SaavnService.getBestStreamUrl(bestSong.id, fallbackQuality)
                    url?.let { it to targetKbps }
                }

                val streamUrl = selectedStream?.first
                val streamBitrateKbps = selectedStream?.second ?: targetKbps
                if (streamUrl.isNullOrBlank()) return@runCatching null

                Timber.tag(TAG).i("Saavn: matched \"${bestSong.name}\" at ${streamBitrateKbps}kbps for videoId=$videoId (tier=$audioQuality)")

                PlaybackData(
                    audioConfig = ytResult.audioConfig,
                    videoDetails = ytResult.videoDetails,
                    playbackTracking = ytResult.playbackTracking,
                    format = PlayerResponse.StreamingData.Format(
                        itag = 141,
                        url = streamUrl,
                        mimeType = "audio/mp4; codecs=\"mp4a.40.2\"",
                        bitrate = streamBitrateKbps * 1000,
                        width = null,
                        height = null,
                        contentLength = null,
                        quality = "${streamBitrateKbps}kbps",
                        fps = null,
                        qualityLabel = null,
                        averageBitrate = null,
                        audioQuality = "${streamBitrateKbps}kbps",
                        approxDurationMs = null,
                        audioSampleRate = 44100,
                        audioChannels = 2,
                        loudnessDb = null,
                        lastModified = null,
                        signatureCipher = null,
                        cipher = null,
                        audioTrack = null,
                    ),
                    streamUrl = streamUrl,
                    streamExpiresInSeconds = 3600,
                    isSaavnStream = true,
                )
            }.getOrNull()

            if (saavnPlayback != null) {
                Result.success(saavnPlayback)
            } else {
                Timber.tag(TAG).d("JioSaavn not matched for tier $audioQuality — returning YouTube Music stream")
                ytDeferred.await()
            }
        }
    }

    private suspend fun resolvePlaybackData(
        videoId: String,
        playlistId: String? = null,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Result<PlaybackData> = runCatching {
        Timber.tag(logTag).d("Fetching player response for videoId: $videoId, playlistId: $playlistId")
        PlaybackLogManager.log(PlaybackLogLevel.INFO, "Resolving playback data", "Video: $videoId")
        
        val isUploadedTrack = playlistId == "MLPT" || playlistId?.contains("MLPT") == true

        val isLoggedIn = YouTube.cookie != null
        Timber.tag(logTag).d("Session authentication status: ${if (isLoggedIn) "Logged in" else "Not logged in"}")

        // Get signature timestamp lazily (only when a client requires it)
        var signatureTimestamp: Int? = null
        var signatureTimestampFetched = false
        suspend fun getSigTimestampLazy(): Int? {
            if (!signatureTimestampFetched) {
                val result = getSignatureTimestampOrNull(videoId)
                signatureTimestamp = result.timestamp
                signatureTimestampFetched = true
                Timber.tag(logTag).d("Signature timestamp obtained lazily: $signatureTimestamp")
            }
            return signatureTimestamp
        }

        // Generate PoToken ONLY if MAIN_CLIENT uses it (which it now doesn't since we use ANDROID_VR)
        var poToken: PoTokenResult? = null
        val sessionId = if (isLoggedIn) YouTube.dataSyncId else YouTube.visitorData
        if (MAIN_CLIENT.useWebPoTokens && sessionId != null) {
            Timber.tag(logTag).d("Generating PoToken for MAIN_CLIENT with sessionId")
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                if (poToken != null) {
                    Timber.tag(logTag).d("PoToken generated successfully")
                }
            } catch (e: Exception) {
                Timber.tag(logTag).e(e, "PoToken generation failed: ${e.message}")
            }
        }

        // Try MAIN_CLIENT (ANDROID_VR) for fast stream resolution and METADATA_CLIENT (WEB_REMIX) for
        // history tracking in parallel. Both are awaited so that metadataResponse is always populated
        // before playbackTracking is read — previously using launch() caused a race condition where
        // metadataResponse was always null and history never registered.
        var metadataResponse: PlayerResponse? = null
        var mainPlayerResponse: PlayerResponse
        coroutineScope {
            val mainDeferred = async {
                Timber.tag(logTag).d("Attempting to get player response using MAIN_CLIENT: ${MAIN_CLIENT.clientName}")
                PlaybackLogManager.log(PlaybackLogLevel.DEBUG, "Trying ${MAIN_CLIENT.clientName} (Main)")
                val sigTimestamp = if (MAIN_CLIENT.useSignatureTimestamp) getSigTimestampLazy() else null
                YouTube.player(videoId, playlistId, MAIN_CLIENT, sigTimestamp, poToken?.playerRequestPoToken).getOrThrow()
            }
            val metaDeferred = if (isLoggedIn) async {
                Timber.tag(logTag).d("Fetching metadata from METADATA_CLIENT (WEB_REMIX) for authenticated tracking")
                try {
                    // Only generate PoToken for web client metadata fetch
                    var metaPoToken: PoTokenResult? = null
                    val metaSessionId = YouTube.visitorData
                    if (METADATA_CLIENT.useWebPoTokens && metaSessionId != null) {
                        try {
                            metaPoToken = poTokenGenerator.getWebClientPoToken(videoId, metaSessionId)
                        } catch (e: Exception) {
                            Timber.tag(logTag).e(e, "Metadata PoToken generation failed")
                        }
                    }
                    val sigTimestamp = if (METADATA_CLIENT.useSignatureTimestamp) getSigTimestampLazy() else null
                    YouTube.player(
                        videoId, playlistId, METADATA_CLIENT,
                        sigTimestamp, metaPoToken?.playerRequestPoToken
                    ).getOrNull()
                } catch (e: Exception) {
                    Timber.tag(logTag).e(e, "Failed to fetch metadata from METADATA_CLIENT")
                    null
                }
            } else null

            // Await both in parallel — main fetch drives playback, meta fetch drives history
            mainPlayerResponse = mainDeferred.await()
            metadataResponse = metaDeferred?.await()
            Timber.tag(logTag).d(
                "Parallel fetch complete: mainOK=${mainPlayerResponse.playabilityStatus.status == "OK"}, " +
                "metaTracking=${metadataResponse?.playbackTracking?.videostatsPlaybackUrl?.baseUrl?.take(40)}"
            )
        }



        var usedAgeRestrictedClient: YouTubeClient? = null
        val wasOriginallyAgeRestricted: Boolean

        // Check if MAIN_CLIENT response indicates age-restricted.
        // NOTE: Do NOT include LOGIN_REQUIRED here — ANDROID_VR returns LOGIN_REQUIRED as a
        // bot-detection / client-not-supported signal, NOT a content age gate. Treating it as
        // age-restricted incorrectly reroutes every bot-flagged request through WEB_CREATOR
        // and causes streaming failures for logged-in users.
        val mainStatus = mainPlayerResponse.playabilityStatus.status
        val isAgeRestrictedFromResponse = mainStatus in listOf(
            "AGE_CHECK_REQUIRED",
            "AGE_VERIFICATION_REQUIRED",
            "CONTENT_CHECK_REQUIRED"
        )
        wasOriginallyAgeRestricted = isAgeRestrictedFromResponse

        if (isAgeRestrictedFromResponse && isLoggedIn) {
            // Age-restricted: use WEB_CREATOR directly (no NewPipe needed from here)
            Timber.tag(logTag).d("Age-restricted detected, using WEB_CREATOR")
            Log.i(TAG, "Age-restricted: using WEB_CREATOR for videoId=$videoId")
            val creatorResponse = YouTube.player(videoId, playlistId, WEB_CREATOR, null, null).getOrNull()
            if (creatorResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("WEB_CREATOR works for age-restricted content")
                mainPlayerResponse = creatorResponse
                usedAgeRestrictedClient = WEB_CREATOR
            }
        }

        // mainPlayerResponse is guaranteed non-null here: mainDeferred uses getOrThrow(), so any
        // failure propagates as an exception rather than a null value.

        // Fetch audioConfig and playbackTracking from the metadata client if available (authenticated)
        // Fall back to mainPlayerResponse values if metadata fetch failed or user is not logged in
        val audioConfig = metadataResponse?.playerConfig?.audioConfig ?: mainPlayerResponse.playerConfig?.audioConfig
        val videoDetails = metadataResponse?.videoDetails ?: mainPlayerResponse.videoDetails
        val playbackTracking = metadataResponse?.playbackTracking ?: mainPlayerResponse.playbackTracking
        var format: PlayerResponse.StreamingData.Format? = null
        var streamUrl: String? = null
        var streamExpiresInSeconds: Int? = null
        var streamPlayerResponse: PlayerResponse? = null
        var retryMainPlayerResponse: PlayerResponse? = if (usedAgeRestrictedClient != null) mainPlayerResponse else null

        // Check current status
        val currentStatus = mainPlayerResponse.playabilityStatus.status
        var isAgeRestricted = currentStatus in listOf(
            "AGE_CHECK_REQUIRED",
            "AGE_VERIFICATION_REQUIRED",
            "CONTENT_CHECK_REQUIRED"
        )

        if (isAgeRestricted) {
            Timber.tag(logTag).d("Content is still age-restricted (status: $currentStatus), will try fallback clients")
            Log.i(TAG, "Age-restricted content detected: videoId=$videoId, status=$currentStatus")
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
                PlaybackLogManager.log(PlaybackLogLevel.DEBUG, "Trying fallback [${clientIndex + 1}/${STREAM_FALLBACK_CLIENTS.size}]", client.clientName)

                if (client.loginRequired && !isLoggedIn && YouTube.cookie == null) {
                    // skip client if it requires login but user is not logged in
                    Timber.tag(logTag).d("Skipping client ${client.clientName} - requires login but user is not logged in")
                    continue
                }

                // Lazily generate PoToken for fallback web clients if we haven't already
                if (client.useWebPoTokens && poToken == null && sessionId != null) {
                    Timber.tag(logTag).d("Lazily generating PoToken for fallback web client: ${client.clientName}")
                    try {
                        poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
                    } catch (e: Exception) {
                        Timber.tag(logTag).e(e, "Lazy PoToken generation failed")
                    }
                }

                Timber.tag(logTag).d("Fetching player response for fallback client: ${client.clientName}")
                // Only pass poToken for clients that support it
                val clientPoToken = if (client.useWebPoTokens) poToken?.playerRequestPoToken else null
                // Skip signature timestamp for age-restricted (faster), use it for normal content
                val clientSigTimestamp = if (wasOriginallyAgeRestricted || !client.useSignatureTimestamp) null else getSigTimestampLazy()
                streamPlayerResponse =
                    YouTube.player(videoId, playlistId, client, clientSigTimestamp, clientPoToken).getOrNull()
            }

            // process current client response
            if (streamPlayerResponse?.playabilityStatus?.status == "OK") {
                Timber.tag(logTag).d("Player response status OK for client: ${if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                PlaybackLogManager.log(PlaybackLogLevel.INFO, "Player response OK", if (clientIndex == -1) MAIN_CLIENT.clientName else STREAM_FALLBACK_CLIENTS[clientIndex].clientName)

                // Check if formats have direct URLs (no signatureCipher needed)
                val hasDirectUrls = streamPlayerResponse.streamingData?.adaptiveFormats
                    ?.any { !it.url.isNullOrEmpty() } == true
                val hasSignatureCipher = streamPlayerResponse.streamingData?.adaptiveFormats
                    ?.any { !it.signatureCipher.isNullOrEmpty() || !it.cipher.isNullOrEmpty() } == true

                Timber.tag(logTag).d("URL check: hasDirectUrls=$hasDirectUrls, hasSignatureCipher=$hasSignatureCipher")

                // Skip NewPipe - use direct URLs or custom cipher in findUrlOrNull
                val responseToUse = streamPlayerResponse

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

                // Apply n-transform FIRST for web clients (main branch order - critical!)
                if (currentClient.useWebPoTokens) {
                    try {
                        Timber.tag(logTag).d("Applying n-transform to stream URL for ${currentClient.clientName}")
                        val transformed = com.music.innertube.YouTubeExtractor.deobfuscateUrlNParam(streamUrl!!)
                        if (transformed != streamUrl) {
                            streamUrl = transformed
                            Timber.tag(logTag).d("N-transform applied successfully")
                        }
                    } catch (e: Exception) {
                        Timber.tag(logTag).e(e, "N-transform failed: ${e.message}")
                    }
                }

                // Apply PoToken SECOND (after n-transform - main branch order)
                // Note: pot token is base64 - do NOT Uri.encode it (breaks validation)
                if (currentClient.useWebPoTokens && poToken?.streamingDataPoToken != null) {
                    Timber.tag(logTag).d("Appending pot= parameter to stream URL")
                    val separator = if ("?" in streamUrl!!) "&" else "?"
                    streamUrl = "${streamUrl}${separator}pot=${poToken.streamingDataPoToken}"
                }

                streamExpiresInSeconds = streamPlayerResponse.streamingData?.expiresInSeconds
                if (streamExpiresInSeconds == null) {
                    Timber.tag(logTag).d("Stream expiration time not found")
                    continue
                }

                Timber.tag(logTag).d("Stream expires in: $streamExpiresInSeconds seconds")

                // Debug: Log URL host and pot token for debugging
                val urlHost = try { java.net.URL(streamUrl).host } catch (e: Exception) { "unknown" }
                Timber.tag(logTag).d("Stream URL host: $urlHost, pot length: ${poToken?.streamingDataPoToken?.length ?: 0}")

                // Check if this is a privately owned track (uploaded song)
                val isPrivatelyOwned = streamPlayerResponse.videoDetails?.musicVideoType == "MUSIC_VIDEO_TYPE_PRIVATELY_OWNED_TRACK"

                if (clientIndex == -1 || clientIndex == STREAM_FALLBACK_CLIENTS.size - 1 || isPrivatelyOwned) {
                    /** skip [validateStatus] for main client, last client or private tracks */
                    if (clientIndex == -1) {
                        Timber.tag(logTag).d("Skipping validation for main client: ${currentClient.clientName}")
                    } else if (isPrivatelyOwned) {
                        Timber.tag(logTag).d("Skipping validation for privately owned track: ${currentClient.clientName}")
                    } else {
                        Timber.tag(logTag).d("Using last fallback client without validation: ${STREAM_FALLBACK_CLIENTS[clientIndex].clientName}")
                    }
                    Log.i(TAG, "Playback: client=${currentClient.clientName}, videoId=$videoId, private=$isPrivatelyOwned")
                    break
                }

                if (validateStatus(streamUrl!!)) {
                    // working stream found
                    Timber.tag(logTag).d("Stream validated successfully with client: ${currentClient.clientName}")
                    PlaybackLogManager.log(PlaybackLogLevel.INFO, "Stream validated", currentClient.clientName)
                    // Log for release builds
                    Log.i(TAG, "Playback: client=${currentClient.clientName}, videoId=$videoId")
                    break
                } else {
                    Timber.tag(logTag).d("Stream validation failed for client: ${currentClient.clientName}")

                    // For web clients: try alternate n-transform and re-validate (Zemer approach)
                    if (currentClient.useWebPoTokens) {
                        var nTransformWorked = false

                        // Try YouTubeExtractor n-transform
                        try {
                            val nTransformed = com.music.innertube.YouTubeExtractor.deobfuscateUrlNParam(streamUrl!!)
                            if (nTransformed != streamUrl) {
                                Timber.tag(logTag).d("YouTubeExtractor n-transform applied, re-validating...")
                                if (validateStatus(nTransformed)) {
                                    Timber.tag(logTag).d("N-transformed URL VALIDATED OK!")
                                    streamUrl = nTransformed
                                    nTransformWorked = true
                                    Log.i(TAG, "Playback: client=${currentClient.clientName}, videoId=$videoId (cipher n-transform)")
                                }
                            }
                        } catch (e: Exception) {
                            Timber.tag(logTag).e(e, "YouTubeExtractor n-transform error")
                        }

                        if (nTransformWorked) break
                    }
                }
            } else {
                val status = streamPlayerResponse?.playabilityStatus?.status ?: "Unknown"
                val reason = streamPlayerResponse?.playabilityStatus?.reason ?: "No reason"
                Timber.tag(logTag).d("Player response status not OK: $status, reason: $reason")
                PlaybackLogManager.log(PlaybackLogLevel.WARNING, "Client failed: ${client.clientName}", "$status: $reason")
                
                // Restore original Timber log for Logcat
                Timber.tag(logTag).d("Player response status not OK: ${streamPlayerResponse?.playabilityStatus?.status}, reason: ${streamPlayerResponse?.playabilityStatus?.reason}")
            }
        }

        if (streamPlayerResponse == null) {
            Timber.tag(logTag).e("Bad stream player response - all clients failed")
            throw Exception("Bad stream player response")
        }

        if (streamPlayerResponse.playabilityStatus.status != "OK") {
            val errorReason = streamPlayerResponse.playabilityStatus.reason
            Timber.tag(logTag).e("Playability status not OK: $errorReason")
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
        PlaybackData(
            audioConfig,
            videoDetails,
            playbackTracking,
            format,
            streamUrl,
            streamExpiresInSeconds,
        )
    }.onFailure { e ->
        Timber.tag(logTag).e(e, "Playback resolution failed")
        PlaybackLogManager.log(PlaybackLogLevel.ERROR, "Playback failed", "${e::class.simpleName}: ${e.message}")
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
        val signatureTimestamp = getSignatureTimestampOrNull(videoId)
        val sessionId = YouTube.visitorData
        var poToken: PoTokenResult? = null
        if (WEB_REMIX.useWebPoTokens && sessionId != null) {
            try {
                poToken = poTokenGenerator.getWebClientPoToken(videoId, sessionId)
            } catch (_: Exception) { }
        }
        return YouTube.player(videoId, playlistId, WEB_REMIX, signatureTimestamp.timestamp, poToken?.playerRequestPoToken) // ANDROID_VR does not work with history
            .onSuccess { Timber.tag(logTag).d("Successfully fetched metadata") }
            .onFailure { Timber.tag(logTag).e(it, "Failed to fetch metadata") }
    }

    private fun findFormat(
        playerResponse: PlayerResponse,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): PlayerResponse.StreamingData.Format? {
        Timber.tag(logTag).d("Finding format with audioQuality: $audioQuality, network metered: ${connectivityManager.isActiveNetworkMetered}")

        val formats = playerResponse.streamingData?.adaptiveFormats
            ?.filter { it.isAudio && it.isOriginal } ?: return null

        val format = when (audioQuality) {
            AudioQuality.MAX -> {
                // Tier: 193-320 kbps (or highest available format, prioritizing Opus)
                formats.maxByOrNull {
                    it.bitrate + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
                }
            }
            AudioQuality.HIGH -> {
                // Tier: 129-192 kbps (e.g. itag 251 Opus ~160k, itag 140 AAC ~140k)
                val inRange = formats.filter { it.bitrate in 129_000..192_000 }
                inRange.maxByOrNull {
                    it.bitrate + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
                } ?: formats.maxByOrNull {
                    it.bitrate + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
                }
            }
            AudioQuality.MEDIUM -> {
                // Tier: 65-128 kbps (e.g. itag 140 AAC ~128k, itag 250 Opus ~70k)
                val inRange = formats.filter { it.bitrate in 65_000..128_000 }
                inRange.maxByOrNull {
                    it.bitrate + (if (it.mimeType.startsWith("audio/webm")) 10240 else 0)
                } ?: formats.minByOrNull {
                    Math.abs(it.bitrate - 128_000)
                }
            }
            AudioQuality.LOW -> {
                // Tier: 40-64 kbps (e.g. itag 249 Opus ~50k, itag 139 AAC ~48k)
                val inRange = formats.filter { it.bitrate in 40_000..64_000 }
                inRange.minByOrNull { it.bitrate }
                    ?: formats.minByOrNull { it.bitrate }
            }
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
                .header("User-Agent", YouTubeClient.USER_AGENT_WEB)

            // Do NOT add Cookie header — googlevideo.com CDN rejects account cookies with 403.
            // Stream URLs are already authenticated via signed URL parameters.

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
                    Log.i(TAG, "Age-restricted detected early via NewPipe: videoId=$videoId")
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
            val customDeobfuscatedUrl = com.music.innertube.YouTubeExtractor.decryptUrl(signatureCipher)
            if (customDeobfuscatedUrl.isNotEmpty()) {
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
}
