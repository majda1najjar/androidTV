package com.xtreamtv.ui.live

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.fragment.app.Fragment
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import com.google.android.exoplayer2.ExoPlayer
import com.google.android.exoplayer2.MediaItem
import com.google.android.exoplayer2.PlaybackException
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.StyledPlayerView
import com.xtreamtv.App
import com.xtreamtv.R
import com.xtreamtv.data.model.Category
import com.xtreamtv.data.model.EpgEntry
import com.xtreamtv.data.model.LiveStream
import com.xtreamtv.ui.MainActivity
import com.xtreamtv.ui.common.CategoryAdapter
import com.xtreamtv.ui.common.CategoryRow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Live TV browser with an embedded preview player (TiviMate-style):
 * categories | channel list | docked player + now-playing/EPG panel.
 *
 * OK on a channel tunes it in the docked player; OK on the channel that is
 * already playing expands to fullscreen; BACK collapses back to browsing.
 * One ExoPlayer instance is shared between the docked and fullscreen surfaces,
 * so browsing and expanding never interrupt playback.
 */
class LiveTvFragment : Fragment(R.layout.fragment_live) {

    private lateinit var app: App
    private lateinit var categoryList: VerticalGridView
    private lateinit var channelGrid: VerticalGridView
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var miniPlayerView: StyledPlayerView
    private lateinit var fullscreenContainer: FrameLayout
    private lateinit var fullscreenPlayerView: StyledPlayerView
    private lateinit var fullscreenStatus: TextView
    private lateinit var playerStatus: TextView
    private lateinit var nowChannelName: TextView
    private lateinit var nowChannelNumber: TextView
    private lateinit var epgNow: TextView
    private lateinit var epgNext: TextView
    private lateinit var categoryAdapter: CategoryAdapter
    private lateinit var channelAdapter: ChannelAdapter

    private var categories: List<Category> = emptyList()
    private var channelsByCategory: Map<String, List<LiveStream>> = emptyMap()
    private var allChannels: List<LiveStream> = emptyList()
    private var selectedCategoryId: String? = null
    private var focusedChannel: LiveStream? = null
    private var loaded = false
    private var loading = false
    private var pendingJumpToFavorites = false

    private var player: ExoPlayer? = null
    private var currentChannel: LiveStream? = null
    private var isFullscreen = false
    private var playbackFailed = false
    private var retryCount = 0
    private var retryJob: Job? = null
    private var epgJob: Job? = null
    private var resizeModeIndex = 0

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitFullscreen()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = App.from(requireContext())

        categoryList = view.findViewById(R.id.category_list)
        channelGrid = view.findViewById(R.id.channel_grid)
        progress = view.findViewById(R.id.live_progress)
        emptyText = view.findViewById(R.id.live_empty)
        miniPlayerView = view.findViewById(R.id.mini_player)
        fullscreenContainer = view.findViewById(R.id.fullscreen_container)
        fullscreenPlayerView = view.findViewById(R.id.fullscreen_player)
        fullscreenStatus = view.findViewById(R.id.fullscreen_status)
        playerStatus = view.findViewById(R.id.player_status)

        // OK while fullscreen retries a dead stream (controller is disabled).
        fullscreenPlayerView.setOnClickListener {
            if (playbackFailed) currentChannel?.let(::startChannelStream)
        }
        nowChannelName = view.findViewById(R.id.now_channel_name)
        nowChannelNumber = view.findViewById(R.id.now_channel_number)
        epgNow = view.findViewById(R.id.epg_now)
        epgNext = view.findViewById(R.id.epg_next)

        categoryAdapter = CategoryAdapter(
            onFocused = { row -> selectCategory(row.id) },
            onClicked = { row ->
                selectCategory(row.id)
                if (channelAdapter.itemCount > 0) channelGrid.requestFocus()
            }
        )
        categoryList.adapter = categoryAdapter

        channelAdapter = ChannelAdapter(
            isFavorite = { id -> app.favorites.isFavorite(id) },
            onFocused = { channel -> focusedChannel = channel },
            onClicked = { channel -> playChannel(channel) }
        )
        channelGrid.setNumColumns(1)
        channelGrid.adapter = channelAdapter

        // Empty leanback grids still accept (and swallow) D-pad focus with no
        // visible highlight; keep them unfocusable until they have content.
        categoryList.isFocusable = false
        channelGrid.isFocusable = false

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        updateNowPlaying(null)
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!loaded) load()
    }

    override fun onStart() {
        super.onStart()
        if (!isHidden) resumePlayback()
    }

    override fun onStop() {
        super.onStop()
        releasePlayer()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (hidden) {
            // Don't stream (or stay fullscreen) behind the Movies/Shows tabs.
            if (isFullscreen) exitFullscreen()
            releasePlayer()
        } else {
            if (!loaded) load()
            resumePlayback()
        }
    }

    // ---- Catalog ----

    private fun load() {
        if (loading || loaded) return
        loading = true
        // Hiding/unfocusing the retry text while it holds focus would teleport
        // focus to the first focusable in the activity; hand it to our tab.
        if (emptyText.hasFocus()) {
            activity?.findViewById<View>(R.id.tab_live)?.requestFocus()
        }
        clearRetryAffordance()
        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                categories = app.api.getLiveCategories()
                allChannels = app.api.getLiveStreams()
                channelsByCategory = allChannels.groupBy { it.categoryId ?: "" }
                loaded = true
                progress.visibility = View.GONE

                categoryAdapter.submit(buildCategoryRows())
                categoryList.isFocusable = true

                val initialCategory = when {
                    pendingJumpToFavorites -> FAVORITES_CATEGORY_ID
                    favoriteChannels().isNotEmpty() -> FAVORITES_CATEGORY_ID
                    else -> categories.firstOrNull()?.id ?: FAVORITES_CATEGORY_ID
                }
                selectCategory(initialCategory)
                // Keep the rail's leanback selection in sync so first focus
                // entry lands on the category actually displayed.
                categoryAdapter.positionOf(initialCategory)
                    .takeIf { it >= 0 }
                    ?.let { categoryList.selectedPosition = it }
                if (pendingJumpToFavorites) {
                    pendingJumpToFavorites = false
                    focusFavoritesRow()
                }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                emptyText.text = getString(R.string.error_loading_retry, e.message ?: "unknown error")
                emptyText.visibility = View.VISIBLE
                emptyText.isFocusable = true
                emptyText.setOnClickListener { load() }
                emptyText.requestFocus()
            } finally {
                loading = false
            }
        }
    }

    private fun clearRetryAffordance() {
        emptyText.isFocusable = false
        emptyText.setOnClickListener(null)
        emptyText.isClickable = false
    }

    private fun buildCategoryRows(): List<CategoryRow> =
        listOf(CategoryRow(FAVORITES_CATEGORY_ID, getString(R.string.category_favorites))) +
            categories.map { CategoryRow(it.id ?: "", it.name ?: getString(R.string.category_unnamed)) }

    private fun favoriteChannels(): List<LiveStream> =
        allChannels.filter { channel -> channel.streamId?.let(app.favorites::isFavorite) == true }

    private fun selectCategory(categoryId: String) {
        selectedCategoryId = categoryId
        categoryAdapter.setSelectedId(categoryId)
        refreshChannels()
    }

    private fun refreshChannels() {
        val channels = if (selectedCategoryId == FAVORITES_CATEGORY_ID) {
            favoriteChannels()
        } else {
            channelsByCategory[selectedCategoryId].orEmpty()
        }
        // The previously focused channel may no longer be on screen — a Yellow
        // press must not toggle an invisible item.
        if (focusedChannel != null && channels.none { it.streamId == focusedChannel?.streamId }) {
            focusedChannel = null
        }
        channelAdapter.submit(channels)
        channelGrid.isFocusable = channels.isNotEmpty()

        val showFavoritesHint = channels.isEmpty() && selectedCategoryId == FAVORITES_CATEGORY_ID
        emptyText.text = when {
            showFavoritesHint -> getString(R.string.favorites_empty_hint)
            channels.isEmpty() -> getString(R.string.category_empty)
            else -> ""
        }
        emptyText.visibility = if (channels.isEmpty() && loaded) View.VISIBLE else View.GONE
    }

    // ---- Playback ----

    private fun playChannel(channel: LiveStream) {
        val streamId = channel.streamId ?: return
        // OK on the channel that's already playing expands it to fullscreen
        // (unless it's in a failed state — then OK retries).
        if (currentChannel?.streamId == streamId && player != null && !playbackFailed) {
            enterFullscreen()
            return
        }
        currentChannel = channel
        channelAdapter.setPlayingId(streamId)
        updateNowPlaying(channel)
        fetchEpg(streamId)
        startChannelStream(channel)
    }

    private fun resumePlayback() {
        val channel = currentChannel ?: return
        if (player == null && view != null) startChannelStream(channel)
    }

    private fun startChannelStream(channel: LiveStream) {
        val streamId = channel.streamId ?: return
        val url = try {
            app.api.liveStreamUrl(streamId)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.error_no_session, Toast.LENGTH_SHORT).show()
            return
        }
        playbackFailed = false
        retryCount = 0
        retryJob?.cancel()
        showStatus(getString(R.string.player_loading))
        val exo = ensurePlayer()
        exo.setMediaItem(MediaItem.fromUri(url))
        exo.prepare()
        exo.playWhenReady = true
    }

    private fun ensurePlayer(): ExoPlayer {
        player?.let { return it }
        val exo = ExoPlayer.Builder(requireContext()).build()
        exo.addListener(playerListener)
        (if (isFullscreen) fullscreenPlayerView else miniPlayerView).player = exo
        player = exo
        return exo
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> {
                    retryCount = 0
                    playbackFailed = false
                    hideStatus()
                }
                // Live .ts hitting clean EOF (provider closed the connection)
                // ends without an error — reconnect anyway.
                Player.STATE_ENDED -> scheduleRetry()
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            // Hold the screen awake only while actually playing, on whichever
            // surface is active — a dead stream shouldn't pin the screen.
            miniPlayerView.keepScreenOn = isPlaying
            fullscreenPlayerView.keepScreenOn = isPlaying
        }

        override fun onPlayerError(error: PlaybackException) {
            scheduleRetry()
        }
    }

    /** Status text is mirrored onto the fullscreen overlay so stream problems
     *  are never silently hidden behind the fullscreen surface. */
    private fun showStatus(text: String) {
        playerStatus.text = text
        playerStatus.visibility = View.VISIBLE
        fullscreenStatus.text = text
        fullscreenStatus.visibility = if (isFullscreen) View.VISIBLE else View.GONE
    }

    private fun hideStatus() {
        playerStatus.visibility = View.GONE
        fullscreenStatus.visibility = View.GONE
    }

    private fun scheduleRetry() {
        if (retryCount >= MAX_RETRIES) {
            playbackFailed = true
            showStatus(getString(R.string.player_failed_mini))
            return
        }
        retryCount++
        showStatus(getString(R.string.player_reconnecting, retryCount, MAX_RETRIES))
        retryJob?.cancel()
        retryJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(RETRY_DELAY_MS)
            val streamId = currentChannel?.streamId ?: return@launch
            val url = runCatching { app.api.liveStreamUrl(streamId) }.getOrNull() ?: return@launch
            player?.let { exo ->
                exo.stop()
                exo.setMediaItem(MediaItem.fromUri(url))
                exo.prepare()
                exo.play()
            }
        }
    }

    private fun releasePlayer() {
        retryJob?.cancel()
        player?.release()
        player = null
        miniPlayerView.player = null
        fullscreenPlayerView.player = null
    }

    // ---- Now playing / EPG panel ----

    private fun updateNowPlaying(channel: LiveStream?) {
        epgNow.visibility = View.GONE
        epgNext.visibility = View.GONE
        if (channel == null) {
            nowChannelName.text = ""
            nowChannelNumber.visibility = View.GONE
            playerStatus.text = getString(R.string.live_select_channel)
            playerStatus.visibility = View.VISIBLE
            return
        }
        nowChannelName.text = channel.name.orEmpty()
        val num = channel.num
        if (num != null) {
            nowChannelNumber.text = getString(R.string.channel_number_format, num)
            nowChannelNumber.visibility = View.VISIBLE
        } else {
            nowChannelNumber.visibility = View.GONE
        }
    }

    private fun fetchEpg(streamId: Int) {
        epgJob?.cancel()
        epgJob = viewLifecycleOwner.lifecycleScope.launch {
            val listings = try {
                app.api.getShortEpg(streamId)
            } catch (e: CancellationException) {
                throw e // never swallow cancellation — a superseded fetch must not bind
            } catch (e: Exception) {
                emptyList()
            }
            ensureActive()
            if (currentChannel?.streamId != streamId) return@launch
            bindEpgLine(epgNow, getString(R.string.epg_now_label), listings.getOrNull(0))
            bindEpgLine(epgNext, getString(R.string.epg_next_label), listings.getOrNull(1))
        }
    }

    private fun bindEpgLine(view: TextView, label: String, entry: EpgEntry?) {
        val title = entry?.title?.takeIf { it.isNotBlank() }
        if (title == null) {
            view.visibility = View.GONE
            return
        }
        view.text = listOfNotNull(label, timeRange(entry).takeIf { it.isNotBlank() }, title)
            .joinToString("  •  ")
        view.visibility = View.VISIBLE
    }

    /** "2026-08-06 20:00:00" / "...21:00:00" -> "20:00–21:00" (panel-local time). */
    private fun timeRange(entry: EpgEntry): String {
        fun clock(stamp: String?): String? =
            stamp?.takeIf { it.length >= 16 }?.substring(11, 16)
        val start = clock(entry.start)
        val end = clock(entry.end)
        return when {
            start != null && end != null -> "$start–$end"
            start != null -> start
            else -> ""
        }
    }

    // ---- Fullscreen ----

    private fun enterFullscreen() {
        val exo = player ?: return
        if (isFullscreen) return
        isFullscreen = true
        (activity as? MainActivity)?.setTabRailVisible(false)
        fullscreenContainer.visibility = View.VISIBLE
        // Carry any active status message (reconnecting/failed) onto the
        // fullscreen overlay.
        fullscreenStatus.text = playerStatus.text
        fullscreenStatus.visibility = playerStatus.visibility
        StyledPlayerView.switchTargetView(exo, miniPlayerView, fullscreenPlayerView)
        fullscreenPlayerView.requestFocus()
        backCallback.isEnabled = true
    }

    fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        player?.let { StyledPlayerView.switchTargetView(it, fullscreenPlayerView, miniPlayerView) }
        fullscreenContainer.visibility = View.GONE
        fullscreenStatus.visibility = View.GONE
        (activity as? MainActivity)?.setTabRailVisible(true)
        backCallback.isEnabled = false
        if (channelAdapter.itemCount > 0) {
            channelGrid.requestFocus()
        } else {
            categoryList.requestFocus()
        }
    }

    // ---- Hardware color keys ----

    /** Yellow: toggle the focused (browsing) or playing (fullscreen) channel. */
    fun handleYellowKey(): Boolean {
        val channel = if (isFullscreen) {
            currentChannel
        } else {
            // Only act when the channel list itself is focused — otherwise a
            // press from the rail/tabs would toggle a stale, off-screen item.
            if (!channelGrid.hasFocus()) return false
            focusedChannel
        } ?: return false
        val streamId = channel.streamId ?: return false
        val added = app.favorites.toggle(streamId)
        Toast.makeText(
            requireContext(),
            getString(
                if (added) R.string.favorites_added else R.string.favorites_removed,
                channel.name.orEmpty()
            ),
            Toast.LENGTH_SHORT
        ).show()
        if (selectedCategoryId == FAVORITES_CATEGORY_ID) {
            refreshChannels()
        } else {
            channelAdapter.notifyChannelChanged(streamId)
        }
        return true
    }

    /** Green: cycle aspect ratio while fullscreen. */
    fun handleGreenKey(): Boolean {
        if (!isFullscreen) return false
        resizeModeIndex = (resizeModeIndex + 1) % RESIZE_MODES.size
        val (mode, labelRes) = RESIZE_MODES[resizeModeIndex]
        fullscreenPlayerView.resizeMode = mode
        Toast.makeText(
            requireContext(),
            getString(R.string.player_aspect, getString(labelRes)),
            Toast.LENGTH_SHORT
        ).show()
        return true
    }

    /** Blue button: jump straight to the Favorites category row. */
    fun jumpToFavorites() {
        if (isFullscreen) exitFullscreen()
        if (!loaded) {
            pendingJumpToFavorites = true
            load()
            return
        }
        selectCategory(FAVORITES_CATEGORY_ID)
        focusFavoritesRow()
    }

    private fun focusFavoritesRow() {
        val position = categoryAdapter.positionOf(FAVORITES_CATEGORY_ID)
        if (position >= 0) {
            categoryList.selectedPosition = position
        }
        categoryList.post {
            if (channelAdapter.itemCount > 0) {
                channelGrid.selectedPosition = 0
                channelGrid.requestFocus()
            } else {
                categoryList.requestFocus()
            }
        }
    }

    companion object {
        const val FAVORITES_CATEGORY_ID = "__favorites__"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 3000L

        private val RESIZE_MODES = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.aspect_fit,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.aspect_zoom,
            AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.aspect_stretch
        )
    }
}
