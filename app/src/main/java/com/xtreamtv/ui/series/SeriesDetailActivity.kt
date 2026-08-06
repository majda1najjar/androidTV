package com.xtreamtv.ui.series

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.leanback.widget.VerticalGridView
import androidx.lifecycle.lifecycleScope
import com.xtreamtv.App
import com.xtreamtv.R
import com.xtreamtv.data.model.Episode
import com.xtreamtv.ui.common.CategoryAdapter
import com.xtreamtv.ui.common.CategoryRow
import com.xtreamtv.ui.player.PlayerActivity
import kotlinx.coroutines.launch

/** Seasons on the left, episodes on the right — same browsing model as Live TV. */
class SeriesDetailActivity : FragmentActivity() {

    private lateinit var app: App
    private lateinit var titleText: TextView
    private lateinit var plotText: TextView
    private lateinit var seasonList: VerticalGridView
    private lateinit var episodeList: VerticalGridView
    private lateinit var progress: ProgressBar
    private lateinit var emptyText: TextView
    private lateinit var seasonAdapter: CategoryAdapter
    private lateinit var episodeAdapter: EpisodeAdapter

    private var episodesBySeason: Map<String, List<Episode>> = emptyMap()
    private var currentSeasonKey: String = ""
    private var seriesName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_series_detail)
        app = App.from(this)

        val seriesId = intent.getIntExtra(EXTRA_SERIES_ID, -1)
        seriesName = intent.getStringExtra(EXTRA_SERIES_NAME).orEmpty()

        titleText = findViewById(R.id.series_title)
        plotText = findViewById(R.id.series_plot)
        seasonList = findViewById(R.id.season_list)
        episodeList = findViewById(R.id.episode_list)
        progress = findViewById(R.id.series_progress)
        emptyText = findViewById(R.id.series_empty)

        titleText.text = seriesName

        seasonAdapter = CategoryAdapter(
            onFocused = { row -> showSeason(row.id) },
            onClicked = { row ->
                showSeason(row.id)
                if (episodeAdapter.itemCount > 0) episodeList.requestFocus()
            }
        )
        seasonList.adapter = seasonAdapter

        episodeAdapter = EpisodeAdapter { episode -> play(episode) }
        episodeList.adapter = episodeAdapter

        // Empty leanback grids swallow D-pad focus invisibly.
        seasonList.isFocusable = false
        episodeList.isFocusable = false

        if (seriesId <= 0) {
            finish()
            return
        }
        load(seriesId)
    }

    private fun load(seriesId: Int) {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            try {
                val info = app.api.getSeriesInfo(seriesId)
                progress.visibility = View.GONE

                info.info?.let { details ->
                    if (!details.name.isNullOrBlank()) titleText.text = details.name
                    plotText.text = details.plot.orEmpty()
                    plotText.visibility =
                        if (details.plot.isNullOrBlank()) View.GONE else View.VISIBLE
                }

                // Season keys sorted numerically ("2" before "10").
                episodesBySeason = info.episodes
                val seasonKeys = info.episodes.keys.sortedBy { it.toIntOrNull() ?: Int.MAX_VALUE }
                if (seasonKeys.isEmpty()) {
                    emptyText.text = getString(R.string.series_no_episodes)
                    emptyText.visibility = View.VISIBLE
                    return@launch
                }

                seasonAdapter.submit(
                    seasonKeys.map { key -> CategoryRow(key, getString(R.string.season_label, key)) }
                )
                seasonList.isFocusable = true
                showSeason(seasonKeys.first())
                seasonList.requestFocus()
            } catch (e: Exception) {
                progress.visibility = View.GONE
                emptyText.text = getString(R.string.error_loading, e.message ?: "unknown error")
                emptyText.visibility = View.VISIBLE
            }
        }
    }

    private fun showSeason(seasonKey: String) {
        currentSeasonKey = seasonKey
        seasonAdapter.setSelectedId(seasonKey)
        val episodes = episodesBySeason[seasonKey].orEmpty()
        episodeAdapter.submit(episodes)
        episodeList.isFocusable = episodes.isNotEmpty()
        emptyText.visibility = if (episodes.isEmpty()) View.VISIBLE else View.GONE
        emptyText.text = getString(R.string.series_no_episodes)
    }

    private fun play(episode: Episode) {
        val url = try {
            app.api.seriesEpisodeUrl(episode)
        } catch (e: Exception) {
            Toast.makeText(this, R.string.error_no_session, Toast.LENGTH_SHORT).show()
            return
        }
        val season = episode.season?.toString() ?: currentSeasonKey
        val title = buildString {
            append(seriesName)
            episode.episodeNum?.let { num ->
                append("  S").append(season.ifBlank { "?" }).append("E").append(num)
            }
            episode.title?.takeIf { it.isNotBlank() }?.let { append(" — ").append(it) }
        }
        startActivity(PlayerActivity.vodIntent(this, url, title))
    }

    companion object {
        private const val EXTRA_SERIES_ID = "series_id"
        private const val EXTRA_SERIES_NAME = "series_name"

        fun intent(context: Context, seriesId: Int, name: String): Intent =
            Intent(context, SeriesDetailActivity::class.java)
                .putExtra(EXTRA_SERIES_ID, seriesId)
                .putExtra(EXTRA_SERIES_NAME, name)
    }
}
