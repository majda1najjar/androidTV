package com.xtreamtv.ui.vod

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import com.xtreamtv.App
import com.xtreamtv.R
import com.xtreamtv.data.model.VodStream
import com.xtreamtv.ui.common.PosterAdapter
import com.xtreamtv.ui.common.PosterItem
import com.xtreamtv.ui.player.PlayerActivity
import kotlinx.coroutines.launch

class MoviesFragment : Fragment(R.layout.fragment_grid) {

    private lateinit var app: App
    private lateinit var grid: VerticalGridView
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var adapter: PosterAdapter

    private var moviesById: Map<Int, VodStream> = emptyMap()
    private var loaded = false
    private var loading = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = App.from(requireContext())

        grid = view.findViewById(R.id.poster_grid)
        progress = view.findViewById(R.id.grid_progress)
        emptyText = view.findViewById(R.id.grid_empty)

        adapter = PosterAdapter { item -> play(item) }
        grid.setNumColumns(GRID_COLUMNS)
        grid.adapter = adapter
        // Empty leanback grids swallow D-pad focus invisibly.
        grid.isFocusable = false

        load()
    }

    override fun onResume() {
        super.onResume()
        if (!loaded) load()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden && !loaded) load()
    }

    private fun load() {
        if (loading || loaded) return
        loading = true
        // Hand focus to our tab before unfocusing the retry text, or Android
        // teleports it to the first focusable in the activity.
        if (emptyText.hasFocus()) {
            activity?.findViewById<View>(R.id.tab_movies)?.requestFocus()
        }
        emptyText.isFocusable = false
        emptyText.setOnClickListener(null)
        emptyText.isClickable = false
        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val movies = app.api.getVodStreams()
                loaded = true
                moviesById = movies.filter { it.streamId != null }.associateBy { it.streamId!! }
                adapter.submit(
                    movies.mapNotNull { movie ->
                        movie.streamId?.let { id ->
                            PosterItem(id, movie.name.orEmpty(), movie.icon)
                        }
                    }
                )
                grid.isFocusable = movies.isNotEmpty()
                progress.visibility = View.GONE
                emptyText.visibility = if (movies.isEmpty()) View.VISIBLE else View.GONE
                emptyText.text = getString(R.string.movies_empty)
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

    private fun play(item: PosterItem) {
        val movie = moviesById[item.id] ?: return
        val url = try {
            app.api.vodStreamUrl(movie)
        } catch (e: Exception) {
            Toast.makeText(requireContext(), R.string.error_no_session, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(PlayerActivity.vodIntent(requireContext(), url, movie.name.orEmpty()))
    }

    companion object {
        private const val GRID_COLUMNS = 6
    }
}
