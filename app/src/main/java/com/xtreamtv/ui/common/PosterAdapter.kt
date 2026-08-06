package com.xtreamtv.ui.common

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.xtreamtv.R

data class PosterItem(val id: Int, val title: String, val imageUrl: String?)

/** Poster grid used by both the Movies and TV Shows tabs. */
class PosterAdapter(
    private val onClicked: (PosterItem) -> Unit
) : RecyclerView.Adapter<PosterAdapter.ViewHolder>() {

    private val items = mutableListOf<PosterItem>()

    fun submit(newItems: List<PosterItem>) {
        items.clear()
        items.addAll(newItems)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_poster, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        Glide.with(holder.poster)
            .load(item.imageUrl)
            .placeholder(R.drawable.ic_movie_placeholder)
            .error(R.drawable.ic_movie_placeholder)
            .centerCrop()
            .into(holder.poster)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val poster: ImageView = view.findViewById(R.id.poster_image)
        val title: TextView = view.findViewById(R.id.poster_title)

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
