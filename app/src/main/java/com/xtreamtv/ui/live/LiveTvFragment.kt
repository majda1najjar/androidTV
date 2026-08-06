package com.xtreamtv.ui.live

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import com.xtreamtv.App
import com.xtreamtv.R
import com.xtreamtv.data.model.Category
import com.xtreamtv.data.model.LiveStream
import com.xtreamtv.ui.common.CategoryAdapter
import com.xtreamtv.ui.common.CategoryRow
import com.xtreamtv.ui.player.PlayerActivity
import kotlinx.coroutines.launch

class LiveTvFragment : Fragment(R.layout.fragment_live) {

    private lateinit var app: App
    private lateinit var categoryList: VerticalGridView
    private lateinit var channelGrid: VerticalGridView
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
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

    private val playerLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            // Favorites may have been toggled inside the player.
            if (result.resultCode == PlayerActivity.RESULT_JUMP_TO_FAVORITES) {
                jumpToFavorites()
            } else if (selectedCategoryId == FAVORITES_CATEGORY_ID) {
                refreshChannels()
            } else {
                channelAdapter.notifyDataSetChanged()
            }
        }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = App.from(requireContext())

        categoryList = view.findViewById(R.id.category_list)
        channelGrid = view.findViewById(R.id.channel_grid)
        progress = view.findViewById(R.id.live_progress)
        emptyText = view.findViewById(R.id.live_empty)

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
            onClicked = { channel -> play(channel) }
        )
        channelGrid.setNumColumns(GRID_COLUMNS)
        channelGrid.adapter = channelAdapter

        // Empty leanback grids still accept (and swallow) D-pad focus with no
        // visible highlight; keep them unfocusable until they have content.
        categoryList.isFocusable = false
        channelGrid.isFocusable = false

        load()
    }

    override fun onResume() {
        super.onResume()
        if (!loaded) load()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        // Fragments are kept alive across tab switches; retry a failed load
        // whenever the tab comes back into view.
        if (!hidden && !loaded) load()
    }

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

    private fun play(channel: LiveStream) {
        val streamId = channel.streamId ?: return
        val url = try {
            app.api.liveStreamUrl(streamId)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.error_no_session, Toast.LENGTH_SHORT).show()
            return
        }
        playerLauncher.launch(
            PlayerActivity.liveIntent(requireContext(), url, channel.name.orEmpty(), streamId)
        )
    }

    /** Yellow button: toggle the focused channel in/out of Favorites. */
    fun handleYellowKey(): Boolean {
        // Only act when the channel grid itself is focused — otherwise a press
        // from the rail/tabs would toggle a stale, off-screen channel.
        if (!channelGrid.hasFocus()) return false
        val channel = focusedChannel ?: return false
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

    /** Blue button: jump straight to the Favorites category row. */
    fun jumpToFavorites() {
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
        private const val GRID_COLUMNS = 3
    }
}
