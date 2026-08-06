package com.xtreamtv.ui.series

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xtreamtv.R
import com.xtreamtv.data.model.Episode
import com.xtreamtv.ui.common.FocusDecor

class EpisodeAdapter(
    private val onClicked: (Episode) -> Unit
) : RecyclerView.Adapter<EpisodeAdapter.ViewHolder>() {

    private val items = mutableListOf<Episode>()

    fun submit(episodes: List<Episode>) {
        items.clear()
        items.addAll(episodes)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_episode, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val episode = items[position]
        holder.number.text = episode.episodeNum?.toString() ?: (position + 1).toString()
        holder.title.text = episode.title?.takeIf { it.isNotBlank() }
            ?: holder.itemView.context.getString(R.string.episode_fallback, position + 1)
        val duration = episode.info?.duration.orEmpty()
        holder.duration.text = duration
        holder.duration.visibility = if (duration.isBlank()) View.GONE else View.VISIBLE
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val number: TextView = view.findViewById(R.id.episode_number)
        val title: TextView = view.findViewById(R.id.episode_title)
        val duration: TextView = view.findViewById(R.id.episode_duration)

        init {
            FocusDecor.install(view)
            view.setOnClickListener {
                bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }
                    ?.let(items::getOrNull)
                    ?.let(onClicked)
            }
        }
    }
}
