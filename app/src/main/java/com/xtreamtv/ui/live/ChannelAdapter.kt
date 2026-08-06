package com.xtreamtv.ui.live

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.xtreamtv.R
import com.xtreamtv.data.model.LiveStream
import com.xtreamtv.ui.common.FocusDecor

class ChannelAdapter(
    private val isFavorite: (Int) -> Boolean,
    private val onFocused: (LiveStream) -> Unit,
    private val onClicked: (LiveStream) -> Unit
) : RecyclerView.Adapter<ChannelAdapter.ViewHolder>() {

    private val items = mutableListOf<LiveStream>()
    private var playingId: Int? = null

    fun submit(channels: List<LiveStream>) {
        items.clear()
        items.addAll(channels)
        notifyDataSetChanged()
    }

    fun notifyChannelChanged(streamId: Int) {
        val index = items.indexOfFirst { it.streamId == streamId }
        if (index >= 0) notifyItemChanged(index)
    }

    /** Marks the row of the channel currently playing in the preview player. */
    fun setPlayingId(streamId: Int?) {
        if (playingId == streamId) return
        val previous = playingId
        playingId = streamId
        previous?.let(::notifyChannelChanged)
        streamId?.let(::notifyChannelChanged)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_channel_row, parent, false)
        return ViewHolder(view)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val channel = items[position]
        holder.itemView.isActivated = channel.streamId != null && channel.streamId == playingId
        holder.name.text = channel.name.orEmpty()
        holder.number.text = channel.num?.toString().orEmpty()
        holder.favoriteBadge.visibility =
            if (channel.streamId?.let(isFavorite) == true) View.VISIBLE else View.GONE
        Glide.with(holder.logo)
            .load(channel.icon)
            .placeholder(R.drawable.ic_tv_placeholder)
            .error(R.drawable.ic_tv_placeholder)
            .fitCenter()
            .into(holder.logo)
    }

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val logo: ImageView = view.findViewById(R.id.channel_logo)
        val name: TextView = view.findViewById(R.id.channel_name)
        val number: TextView = view.findViewById(R.id.channel_number)
        val favoriteBadge: ImageView = view.findViewById(R.id.channel_favorite)

        init {
            FocusDecor.install(view) { hasFocus ->
                if (hasFocus) currentChannel()?.let(onFocused)
            }
            view.setOnClickListener { currentChannel()?.let(onClicked) }
        }

        private fun currentChannel(): LiveStream? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(items::getOrNull)
    }
}
