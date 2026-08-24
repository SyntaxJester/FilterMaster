package com.filtermaster.app

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView

class CatalogAdapter(
    private var items: List<FilterCatalog.Entry>,
    private val onUse: (FilterCatalog.Entry) -> Unit
) : RecyclerView.Adapter<CatalogAdapter.VH>() {

    fun submit(list: List<FilterCatalog.Entry>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_catalog, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        val ctx = h.itemView.context

        h.tvModel.text = "${item.brand} · ${item.model}"
        h.tvOe.text = "OE  ${item.oe}"
        h.tvType.text = "${FilterAdapter.typeIcon(item.type)} ${item.type.removeSuffix("滤芯")}"
        val (bg, fg) = FilterAdapter.badgeColors(item.type)
        h.tvType.background?.mutate()?.setTint(ContextCompat.getColor(ctx, bg))
        h.tvType.setTextColor(ContextCompat.getColor(ctx, fg))

        if (item.note.isBlank()) {
            h.tvNote.visibility = View.GONE
        } else {
            h.tvNote.visibility = View.VISIBLE
            h.tvNote.text = "ℹ️ ${item.note}"
        }

        h.itemView.setOnClickListener { onUse(item) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvModel: TextView = v.findViewById(R.id.tvCatalogModel)
        val tvType: TextView = v.findViewById(R.id.tvCatalogType)
        val tvOe: TextView = v.findViewById(R.id.tvCatalogOe)
        val tvNote: TextView = v.findViewById(R.id.tvCatalogNote)
    }
}
