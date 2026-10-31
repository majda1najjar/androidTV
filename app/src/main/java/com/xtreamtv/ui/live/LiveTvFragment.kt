package com.xtreamtv.ui.live

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.core.widget.doAfterTextChanged
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
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Live TV browser with an embedded preview player (TiviMate-style):
 * categories | search + channel list | docked player + now-playing/EPG panel.
 *
 * Channels load lazily: focusing a category does nothing (so held D-pad
 * scrolling stays smooth); pressing OK fetches that category's channels from
 * the panel (cached afterwards). Favorites and the synthesized "All Channels"
 * section pull the full channel list on demand. The search box above the
 * channel list matches against all channels.
 *
 * OK on a channel tunes it in the docked player; OK on the channel that is
 * already playing expands to fullscreen; BACK collapses back to browsing.
 */
class LiveTvFragment : Fragment(R.layout.fragment_live) {

    private lateinit var app: App
    private lateinit var browseRoot: ViewGroup
    private lateinit var categoryList: VerticalGridView
    private lateinit var channelGrid: VerticalGridView
    private lateinit var searchInput: EditText
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
    private val channelCache = HashMap<String, List<LiveStream>>()
    private var allChannelsCache: List<LiveStream>? = null
    private var selectedCategoryId: String? = null
    private var searchQuery = ""
    private var focusedChannel: LiveStream? = null
    private var loaded = false
    private var loading = false
    private var pendingJumpToFavorites = false
    private var pendingFocusGrid = false
    private var channelLoadJob: Job? = null
    private var searchJob: Job? = null
    private var allChannelsFetch: Deferred<List<LiveStream>>? = null
    private var paneErrorActive = false

    private var player: ExoPlayer? = null
    private var currentChannel: LiveStream? = null
    private var isFullscreen = false
    private var playbackFailed = false
    private var retryCount = 0
    private var retryJob: Job? = null
    private var epgJob: Job? = null
    private var resizeModeIndex = 0
    private var inactivityJob: Job? = null

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            exitFullscreen()
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = App.from(requireContext())

        browseRoot = view.findViewById(R.id.browse_root)
        categoryList = view.findViewById(R.id.category_list)
        channelGrid = view.findViewById(R.id.channel_grid)
        searchInput = view.findViewById(R.id.search_input)
        progress = view.findViewById(R.id.live_progress)
        emptyText = view.findViewById(R.id.live_empty)
        miniPlayerView = view.findViewById(R.id.mini_player)
        fullscreenContainer = view.findViewById(R.id.fullscreen_container)
        fullscreenPlayerView = view.findViewById(R.id.fullscreen_player)
        fullscreenStatus = view.findViewById(R.id.fullscreen_status)
        playerStatus = view.findViewById(R.id.player_status)
        nowChannelName = view.findViewById(R.id.now_channel_name)
        nowChannelNumber = view.findViewById(R.id.now_channel_number)
        epgNow = view.findViewById(R.id.epg_now)
        epgNext = view.findViewById(R.id.epg_next)

        // OK while fullscreen retries a dead stream (controller is disabled).
        fullscreenPlayerView.setOnClickListener {
            if (playbackFailed) currentChannel?.let(::startChannelStream)
        }

        categoryAdapter = CategoryAdapter(
            // Selection happens on OK only — reacting to focus would rebuild
            // the channel pane on every hop of a held D-pad scroll.
            onFocused = { },
            onClicked = { row -> onCategoryClicked(row) }
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

        searchInput.doAfterTextChanged { editable ->
            onSearchTextChanged(editable?.toString().orEmpty())
        }
        searchInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && channelAdapter.itemCount > 0) {
                hideKeyboard()
                channelGrid.requestFocus()
                true
            } else {
                // Let the IME handle Done (dismiss) when there is nothing to
                // hand focus to — returning true would make the key feel dead.
                false
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)

        updateNowPlaying(null)
        load()
    }

    override fun onResume() {
        super.onResume()
        if (!loaded) load()
        onUserActivity()
    }

    override fun onPause() {
        inactivityJob?.cancel()
        super.onPause()
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
            onUserActivity()
        }
    }

    // ---- Catalog: categories up front, channels on demand ----

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
                loaded = true
                progress.visibility = View.GONE

                categoryAdapter.submit(buildCategoryRows())
                categoryList.isFocusable = true
                categoryList.selectedPosition = 0

                when {
                    pendingJumpToFavorites -> {
                        pendingJumpToFavorites = false
                        pendingFocusGrid = true
                        selectCategory(FAVORITES_CATEGORY_ID)
                    }
                    // Don't clobber a search the user started while categories
                    // were still loading.
                    searchQuery.isNotBlank() -> Unit
                    app.favorites.all().isNotEmpty() -> selectCategory(FAVORITES_CATEGORY_ID)
                    else -> showPaneHint(getString(R.string.live_pick_category))
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

    private fun buildCategoryRows(): List<CategoryRow> {
        val rows = mutableListOf(
            CategoryRow(FAVORITES_CATEGORY_ID, getString(R.string.category_favorites))
        )
        // Providers usually ship their own "ALL" category; synthesize one only
        // when they don't.
        if (categories.none { isAllLikeName(it.name) }) {
            rows.add(CategoryRow(ALL_CATEGORY_ID, getString(R.string.category_all)))
        }
        categories.mapTo(rows) {
            CategoryRow(it.id ?: "", it.name ?: getString(R.string.category_unnamed))
        }
        return rows
    }

    private fun isAllLikeName(name: String?): Boolean {
        val normalized = name?.trim()?.lowercase() ?: return false
        return normalized == "all" ||
            normalized == "all channels" ||
            ALL_LIKE_PREFIX.matches(normalized)
    }

    private fun onCategoryClicked(row: CategoryRow) {
        clearSearchText()
        pendingFocusGrid = true
        selectCategory(row.id)
    }

    /** Clears the search box without the watcher firing a redundant reload. */
    private fun clearSearchText() {
        // The watcher's early-return means it will never reach its own cancel —
        // a surviving search job would later stomp the pane with its spinner
        // or error.
        searchJob?.cancel()
        searchQuery = ""
        if (searchInput.text.isNotEmpty()) searchInput.setText("")
    }

    private fun hideKeyboard() {
        val imm = requireContext()
            .getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(searchInput.windowToken, 0)
    }

    private fun selectCategory(categoryId: String) {
        selectedCategoryId = categoryId
        categoryAdapter.setSelectedId(categoryId)
        loadChannelsFor(categoryId)
    }

    private fun loadChannelsFor(categoryId: String) {
        channelLoadJob?.cancel()
        if (searchQuery.isNotBlank()) return // search owns the pane right now
        cachedChannelsFor(categoryId)?.let {
            displayChannels(it)
            return
        }
        showPaneLoading()
        channelLoadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val channels = when (categoryId) {
                    FAVORITES_CATEGORY_ID -> fetchAllChannels()
                        .filter { it.streamId?.let(app.favorites::isFavorite) == true }
                    ALL_CATEGORY_ID -> fetchAllChannels()
                    else -> app.api.getLiveStreams(categoryId)
                        .also { channelCache[categoryId] = it }
                }
                ensureActive()
                if (selectedCategoryId == categoryId && searchQuery.isBlank()) {
                    displayChannels(channels)
                } else {
                    // Superseded (new category or a search took over) — the
                    // focus handoff must not fire on someone else's display.
                    pendingFocusGrid = false
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (selectedCategoryId == categoryId && searchQuery.isBlank()) {
                    showPaneError(e)
                } else {
                    pendingFocusGrid = false
                }
            }
        }
    }

    /** Cached list for a category, or null when it still needs a fetch. */
    private fun cachedChannelsFor(categoryId: String): List<LiveStream>? = when (categoryId) {
        FAVORITES_CATEGORY_ID -> allChannelsCache
            ?.filter { it.streamId?.let(app.favorites::isFavorite) == true }
        ALL_CATEGORY_ID -> allChannelsCache
        else -> channelCache[categoryId]
    }

    // Shared between search and Favorites/All — a single in-flight fetch is
    // reused so the (large) full catalog is never downloaded twice in parallel.
    private suspend fun fetchAllChannels(): List<LiveStream> {
        allChannelsCache?.let { return it }
        val existing = allChannelsFetch
        val deferred = if (existing != null && existing.isActive) {
            existing
        } else {
            viewLifecycleOwner.lifecycleScope.async {
                app.api.getLiveStreams().also { allChannelsCache = it }
            }.also { allChannelsFetch = it }
        }
        return try {
            deferred.await()
        } catch (e: CancellationException) {
            // The AWAITER was cancelled — the shared fetch is still running
            // and must stay registered for the next caller.
            throw e
        } catch (e: Exception) {
            if (allChannelsFetch === deferred) allChannelsFetch = null
            throw e
        }
    }

    private fun displayChannels(channels: List<LiveStream>, emptyMessage: String? = null) {
        progress.visibility = View.GONE
        // The previously focused channel may no longer be on screen — a Yellow
        // press must not toggle an invisible item.
        if (focusedChannel != null && channels.none { it.streamId == focusedChannel?.streamId }) {
            focusedChannel = null
        }
        val gridHadFocus = channelGrid.hasFocus()
        channelAdapter.submit(channels)
        channelGrid.isFocusable = channels.isNotEmpty()
        // If the grid empties out from under the user (e.g. Yellow removed the
        // last favorite), hand focus to the rail instead of letting the
        // framework warp it to the top of the window.
        if (channels.isEmpty() && gridHadFocus) {
            categoryList.requestFocus()
        }

        if (channels.isEmpty()) {
            val showFavoritesHint =
                searchQuery.isBlank() && selectedCategoryId == FAVORITES_CATEGORY_ID
            showPaneHint(
                emptyMessage ?: if (showFavoritesHint) {
                    getString(R.string.favorites_empty_hint)
                } else {
                    getString(R.string.category_empty)
                }
            )
        } else {
            emptyText.visibility = View.GONE
        }

        if (pendingFocusGrid) {
            pendingFocusGrid = false
            // Never steal focus from the search box while the user is typing.
            if (!searchInput.hasFocus()) {
                channelGrid.post {
                    if (channelAdapter.itemCount > 0) {
                        channelGrid.selectedPosition = 0
                        channelGrid.requestFocus()
                    } else {
                        categoryList.requestFocus()
                    }
                }
            }
        }
    }

    private fun showPaneLoading() {
        clearRetryAffordance()
        emptyText.visibility = View.GONE
        progress.visibility = View.VISIBLE
    }

    private fun showPaneHint(text: String) {
        clearRetryAffordance()
        progress.visibility = View.GONE
        emptyText.text = text
        emptyText.visibility = View.VISIBLE
    }

    private fun showPaneError(e: Exception) {
        pendingFocusGrid = false
        progress.visibility = View.GONE
        // Clear the stale list — leaving the previous category's channels
        // interactive under the error text misleads about what failed.
        val gridHadFocus = channelGrid.hasFocus()
        channelAdapter.submit(emptyList())
        channelGrid.isFocusable = false
        emptyText.text = getString(R.string.error_loading_retry, e.message ?: "unknown error")
        emptyText.visibility = View.VISIBLE
        paneErrorActive = true
        // emptyText sits OUTSIDE browse_root, so the fullscreen focus block
        // doesn't cover it — keep it unfocusable until fullscreen exits.
        emptyText.isFocusable = !isFullscreen
        emptyText.setOnClickListener { retryPane() }
        if (gridHadFocus && !isFullscreen) emptyText.requestFocus()
    }

    private fun retryPane() {
        // Unfocusing the retry text while it holds focus would warp focus to
        // the top of the window; hand it to the rail and re-enter the grid
        // once the retried load displays.
        if (emptyText.hasFocus()) {
            if (categoryList.isFocusable) {
                categoryList.requestFocus()
            } else {
                activity?.findViewById<View>(R.id.tab_live)?.requestFocus()
            }
        }
        clearRetryAffordance()
        if (searchQuery.isNotBlank()) {
            val query = searchQuery
            searchQuery = ""
            onSearchTextChanged(query)
            // Must be armed AFTER onSearchTextChanged, which clears the flag.
            pendingFocusGrid = true
        } else {
            pendingFocusGrid = true
            selectedCategoryId?.let(::loadChannelsFor)
        }
    }

    private fun clearRetryAffordance() {
        paneErrorActive = false
        emptyText.isFocusable = false
        emptyText.setOnClickListener(null)
        emptyText.isClickable = false
    }

    // ---- Search ----

    private fun onSearchTextChanged(text: String) {
        val query = text.trim()
        if (query == searchQuery) return
        searchQuery = query
        searchJob?.cancel()
        if (query.isBlank()) {
            selectedCategoryId?.let(::loadChannelsFor)
                ?: showPaneHint(getString(R.string.live_pick_category))
            return
        }
        // Search owns the pane now: stop any in-flight category load and make
        // sure its focus handoff can't fire on our results.
        channelLoadJob?.cancel()
        pendingFocusGrid = false
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(SEARCH_DEBOUNCE_MS)
            showPaneLoading()
            val all = try {
                fetchAllChannels()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showPaneError(e)
                return@launch
            }
            ensureActive()
            if (searchQuery != query) return@launch
            val matches = all.filter { it.name?.contains(query, ignoreCase = true) == true }
            displayChannels(matches, emptyMessage = getString(R.string.search_no_matches, query))
        }
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
        onUserActivity()
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
        inactivityJob?.cancel()
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

    /** Any remote, keyboard, touch or pointer input starts a fresh idle period. */
    fun onUserActivity() {
        inactivityJob?.cancel()
        if (!isResumed || isHidden || isFullscreen || currentChannel == null || player == null) return
        inactivityJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(AUTO_FULLSCREEN_DELAY_MS)
            if (isResumed && !isHidden && !isFullscreen && currentChannel != null && player != null) {
                hideKeyboard()
                enterFullscreen()
            }
        }
    }

    private fun enterFullscreen() {
        val exo = player ?: return
        if (isFullscreen) return
        inactivityJob?.cancel()
        isFullscreen = true
        (activity as? MainActivity)?.setTabRailVisible(false)
        fullscreenContainer.visibility = View.VISIBLE
        // Carry any active status message (reconnecting/failed) onto the
        // fullscreen overlay.
        fullscreenStatus.text = playerStatus.text
        fullscreenStatus.visibility = playerStatus.visibility
        StyledPlayerView.switchTargetView(exo, miniPlayerView, fullscreenPlayerView)
        fullscreenPlayerView.requestFocus()
        // FocusFinder ignores z-order: without this, D-pad presses could land
        // on the hidden rail/list/search box behind the video (OK would even
        // open the keyboard over playback).
        browseRoot.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        emptyText.isFocusable = false
        backCallback.isEnabled = true
    }

    fun exitFullscreen() {
        if (!isFullscreen) return
        isFullscreen = false
        player?.let { StyledPlayerView.switchTargetView(it, fullscreenPlayerView, miniPlayerView) }
        fullscreenContainer.visibility = View.GONE
        fullscreenStatus.visibility = View.GONE
        (activity as? MainActivity)?.setTabRailVisible(true)
        browseRoot.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        if (paneErrorActive) emptyText.isFocusable = true
        backCallback.isEnabled = false
        if (channelAdapter.itemCount > 0) {
            channelGrid.requestFocus()
        } else {
            categoryList.requestFocus()
        }
        onUserActivity()
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
        if (selectedCategoryId == FAVORITES_CATEGORY_ID && searchQuery.isBlank()) {
            cachedChannelsFor(FAVORITES_CATEGORY_ID)?.let(::displayChannels)
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
        clearSearchText()
        pendingFocusGrid = true
        categoryAdapter.positionOf(FAVORITES_CATEGORY_ID)
            .takeIf { it >= 0 }
            ?.let { categoryList.selectedPosition = it }
        selectCategory(FAVORITES_CATEGORY_ID)
    }

    companion object {
        const val FAVORITES_CATEGORY_ID = "__favorites__"
        const val ALL_CATEGORY_ID = "__all__"
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 3000L
        private const val SEARCH_DEBOUNCE_MS = 250L
        private const val AUTO_FULLSCREEN_DELAY_MS = 45_000L

        // "all", "all | channels", "all - live" … but not "allstar".
        private val ALL_LIKE_PREFIX = Regex("^all[ |_-].*$")

        private val RESIZE_MODES = listOf(
            AspectRatioFrameLayout.RESIZE_MODE_FIT to R.string.aspect_fit,
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM to R.string.aspect_zoom,
            AspectRatioFrameLayout.RESIZE_MODE_FILL to R.string.aspect_stretch
        )
    }
}
