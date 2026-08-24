package com.filtermaster.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayout
import java.io.File

private fun dp(ctx: Context, value: Int): Int =
    (value * ctx.resources.displayMetrics.density + 0.5f).toInt()

class FilterAdapter(
    private var items: List<FilterItem>,
    private val onClick: (FilterItem) -> Unit
) : RecyclerView.Adapter<FilterAdapter.VH>() {

    fun submit(list: List<FilterItem>) {
        items = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_filter, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val item = items[position]
        val ctx = h.itemView.context

        h.tvCode.text = item.goodsCode.ifBlank { "未编码" }

        // 类型徽章
        if (item.type.isBlank()) {
            h.tvTypeBadge.visibility = View.GONE
        } else {
            h.tvTypeBadge.visibility = View.VISIBLE
            val (bg, fg) = badgeColors(item.type)
            h.tvTypeBadge.text = typeIcon(item.type) + " " + item.type.replace("滤芯", "")
            h.tvTypeBadge.background?.mutate()?.setTint(ContextCompat.getColor(ctx, bg))
            h.tvTypeBadge.setTextColor(ContextCompat.getColor(ctx, fg))
        }

        // OE 码芯片
        if (item.oeCode.isBlank()) {
            h.tvOeChip.visibility = View.GONE
        } else {
            h.tvOeChip.visibility = View.VISIBLE
            h.tvOeChip.text = "OE  ${item.oeCode}"
        }

        // 元信息标签
        h.metaRow.removeAllViews()
        listOf(
            "车型" to item.carModel,
            "规格" to item.specification,
            "胶圈" to item.rubberRing,
            "盒子" to item.boxInfo
        ).filter { it.second.isNotBlank() }.forEach { (label, value) ->
            val tv = TextView(ctx)
            tv.text = "$label  $value"
            tv.textSize = 12f
            tv.setTextColor(ContextCompat.getColor(ctx, R.color.meta_text))
            tv.background = ContextCompat.getDrawable(ctx, R.drawable.bg_rounded_8)?.mutate()
            tv.background?.setTint(ContextCompat.getColor(ctx, R.color.field_bg))
            val d9 = dp(ctx, 9)
            val d4 = dp(ctx, 4)
            tv.setPadding(d9, d4, d9, d4)
            h.metaRow.addView(
                tv,
                FlexboxLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        // 缩略图
        val imgPath = item.imagePath
        if (!imgPath.isNullOrBlank() && File(imgPath).exists()) {
            h.ivThumb.visibility = View.VISIBLE
            h.ivThumb.setImageBitmap(FilterAdapter.decodeSampled(imgPath, 160))
        } else {
            h.ivThumb.visibility = View.GONE
        }

        h.itemView.setOnClickListener { onClick(item) }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val tvCode: TextView = v.findViewById(R.id.tvCode)
        val tvTypeBadge: TextView = v.findViewById(R.id.tvTypeBadge)
        val tvOeChip: TextView = v.findViewById(R.id.tvOeChip)
        val metaRow: FlexboxLayout = v.findViewById(R.id.metaRow)
        val ivThumb: ImageView = v.findViewById(R.id.ivThumb)
    }

    companion object {
        fun typeIcon(type: String) = when (type) {
            "机油滤芯" -> "🛢️"
            "空气滤芯" -> "🌬️"
            "空调滤芯" -> "❄️"
            "燃油滤芯" -> "⛽"
            else -> ""
        }

        fun badgeColors(type: String): Pair<Int, Int> = when (type) {
            "机油滤芯" -> R.color.oil_bg to R.color.oil_fg
            "空气滤芯" -> R.color.air_bg to R.color.air_fg
            "空调滤芯" -> R.color.ac_bg to R.color.ac_fg
            "燃油滤芯" -> R.color.fuel_bg to R.color.fuel_fg
            else -> R.color.none_bg to R.color.none_fg
        }

        fun decodeSampled(path: String, reqSize: Int): Bitmap? {
            return try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > reqSize * 2 || bounds.outHeight / sample > reqSize * 2) {
                    sample *= 2
                }
                BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
            } catch (e: Exception) {
                null
            }
        }
    }
}
