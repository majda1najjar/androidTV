package com.xtreamtv.ui.common

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.xtreamtv.R

data class CategoryRow(val id: String, val name: String)

/**
 * Left-hand rail of categories (also reused for the seasons list).
 * Focusing a row selects it, so browsing with the D-pad live-updates the grid.
 */
class CategoryAdapter(
    private val onFocused: (CategoryRow) -> Unit,
    private val onClicked: (CategoryRow) -> Unit
) : RecyclerView.Adapter<CategoryAdapter.ViewHolder>() {

    private val items = mutableListOf<CategoryRow>()
    private var selectedId: String? = null
    private var recyclerView: RecyclerView? = null

    override fun onAttachedToRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = recyclerView
    }

    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        this.recyclerView = null
    }

    fun submit(rows: List<CategoryRow>) {
        items.clear()
        items.addAll(rows)
        notifyDataSetChanged()
    }

    fun setSelectedId(id: String?) {
        if (selectedId == id) return
        val previousId = selectedId
        selectedId = id
        // This is called from item focus listeners, which leanback can fire while
        // the grid is still computing layout/scroll — a synchronous notify would
        // throw "Cannot call this method while RecyclerView is computing a
        // layout". Defer and target only the two affected rows.
        val grid = recyclerView ?: return
        grid.post {
            previousId?.let { positionOf(it) }?.takeIf { it >= 0 }?.let(::notifyItemChanged)
            id?.let { positionOf(it) }?.takeIf { it >= 0 }?.let(::notifyItemChanged)
        }
    }

    fun positionOf(id: String): Int = items.indexOfFirst { it.id == id }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_category, parent, false)
        return ViewHolder(view as TextView)
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val row = items[position]
        holder.label.text = row.name
        holder.label.isActivated = row.id == selectedId
    }

    inner class ViewHolder(val label: TextView) : RecyclerView.ViewHolder(label) {
        init {
            FocusDecor.install(label) { hasFocus ->
                if (hasFocus) currentRow()?.let(onFocused)
            }
            label.setOnClickListener { currentRow()?.let(onClicked) }
        }

        private fun currentRow(): CategoryRow? =
            bindingAdapterPosition.takeIf { it != RecyclerView.NO_POSITION }?.let(items::getOrNull)
    }
}
