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
import com.xtreamtv.ui.common.CategoryAdapter
import com.xtreamtv.ui.common.CategoryRow
import com.xtreamtv.ui.common.PosterAdapter
import com.xtreamtv.ui.common.PosterItem
import com.xtreamtv.ui.player.PlayerActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class MoviesFragment : Fragment(R.layout.fragment_movies) {
    private lateinit var app: App
    private lateinit var grid: VerticalGridView
    private lateinit var categoryList: VerticalGridView
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var adapter: PosterAdapter
    private lateinit var categoryAdapter: CategoryAdapter
    private var categories: List<CategoryRow>? = null
    private val cache = mutableMapOf<String, List<VodStream>>()
    private var moviesById: Map<Int, VodStream> = emptyMap()
    private var selectedCategoryId = ALL_CATEGORY_ID
    private var loadJob: Job? = null
    private var categoryJob: Job? = null

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        app = App.from(requireContext())
        selectedCategoryId = savedInstanceState?.getString(STATE_CATEGORY) ?: selectedCategoryId
        grid = view.findViewById(R.id.poster_grid)
        categoryList = view.findViewById(R.id.category_list)
        progress = view.findViewById(R.id.grid_progress)
        emptyText = view.findViewById(R.id.grid_empty)
        adapter = PosterAdapter { play(it) }
        grid.setNumColumns(4)
        grid.adapter = adapter
        grid.isFocusable = false
        categoryAdapter = CategoryAdapter(onFocused = {}, onClicked = { selectCategory(it.id, true) })
        categoryList.adapter = categoryAdapter
        categoryList.isFocusable = false
        loadCategories()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_CATEGORY, selectedCategoryId)
    }

    private fun showLoading() {
        if (emptyText.hasFocus() || grid.hasFocus()) {
            if (categoryList.isFocusable) categoryList.requestFocus()
            else activity?.findViewById<View>(R.id.tab_movies)?.requestFocus()
        }
        emptyText.isFocusable = false
        emptyText.setOnClickListener(null)
        emptyText.isClickable = false
        emptyText.visibility = View.GONE
        progress.visibility = View.VISIBLE
        adapter.submit(emptyList())
        moviesById = emptyMap()
        grid.isFocusable = false
    }

    private fun loadCategories() {
        categoryJob?.cancel()
        showLoading()
        categoryJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val rows = categories ?: (listOf(CategoryRow(ALL_CATEGORY_ID, getString(R.string.movies_all))) +
                    app.api.getVodCategories().mapNotNull { category ->
                        category.id?.takeIf { it.isNotBlank() }?.let {
                            CategoryRow(it, category.name?.takeIf { name -> name.isNotBlank() }
                                ?: getString(R.string.category_unnamed))
                        }
                    }.distinctBy { it.id }).also { categories = it }
                ensureActive()
                categoryAdapter.submit(rows)
                categoryList.isFocusable = true
                if (rows.none { it.id == selectedCategoryId }) selectedCategoryId = ALL_CATEGORY_ID
                categoryList.selectedPosition = rows.indexOfFirst { it.id == selectedCategoryId }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e) { loadCategories() }
                return@launch
            }
            selectCategory(selectedCategoryId, false)
        }
    }

    private fun selectCategory(id: String, focusGrid: Boolean) {
        loadJob?.cancel()
        selectedCategoryId = id
        categoryAdapter.setSelectedId(id)
        showLoading()
        loadJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                val movies = cache[id] ?: app.api.getVodStreams(id.takeUnless { it == ALL_CATEGORY_ID })
                    .also { cache[id] = it }
                ensureActive()
                moviesById = movies.associateBy { it.streamId!! }
                adapter.submit(movies.map { PosterItem(it.streamId!!, it.name.orEmpty(), it.icon) })
                grid.isFocusable = movies.isNotEmpty()
                grid.selectedPosition = 0
                progress.visibility = View.GONE
                emptyText.text = getString(R.string.movies_empty)
                emptyText.visibility = if (movies.isEmpty()) View.VISIBLE else View.GONE
                if (focusGrid && movies.isNotEmpty() && isResumed && !isHidden) grid.requestFocus()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError(e) { selectCategory(id, true) }
            }
        }
    }

    private fun showError(error: Exception, retry: () -> Unit) {
        progress.visibility = View.GONE
        emptyText.text = getString(R.string.error_loading_retry, error.message ?: "unknown error")
        emptyText.visibility = View.VISIBLE
        emptyText.isFocusable = true
        emptyText.setOnClickListener { retry() }
        if (isResumed && !isHidden) emptyText.requestFocus()
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
        private const val ALL_CATEGORY_ID = "__all_movies__"
        private const val STATE_CATEGORY = "movie_category"
    }
}
