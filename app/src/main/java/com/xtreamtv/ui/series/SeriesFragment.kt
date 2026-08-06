package com.xtreamtv.ui.series

import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import com.xtreamtv.App
import com.xtreamtv.R
import com.xtreamtv.ui.common.PosterAdapter
import com.xtreamtv.ui.common.PosterItem
import kotlinx.coroutines.launch

class SeriesFragment : Fragment(R.layout.fragment_grid) {

    private lateinit var app: App
    private lateinit var grid: VerticalGridView
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var adapter: PosterAdapter
    private var loaded = false
    private var loading = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = App.from(requireContext())

        grid = view.findViewById(R.id.poster_grid)
        progress = view.findViewById(R.id.grid_progress)
        emptyText = view.findViewById(R.id.grid_empty)

        adapter = PosterAdapter { item ->
            startActivity(SeriesDetailActivity.intent(requireContext(), item.id, item.title))
        }
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
            activity?.findViewById<View>(R.id.tab_series)?.requestFocus()
        }
        emptyText.isFocusable = false
        emptyText.setOnClickListener(null)
        emptyText.isClickable = false
        progress.visibility = View.VISIBLE
        emptyText.visibility = View.GONE
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val seriesList = app.api.getSeries()
                loaded = true
                adapter.submit(
                    seriesList.mapNotNull { series ->
                        series.seriesId?.let { id ->
                            PosterItem(id, series.name.orEmpty(), series.cover)
                        }
                    }
                )
                grid.isFocusable = seriesList.isNotEmpty()
                progress.visibility = View.GONE
                emptyText.visibility = if (seriesList.isEmpty()) View.VISIBLE else View.GONE
                emptyText.text = getString(R.string.series_empty)
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

    companion object {
        private const val GRID_COLUMNS = 6
    }
}
