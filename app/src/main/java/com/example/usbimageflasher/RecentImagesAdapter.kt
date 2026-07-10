package com.example.usbimageflasher

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.ImageButton
import android.widget.TextView

class RecentImagesAdapter(
    context: Context,
    private var items: List<RecentImage>,
    private val onSelect: (RecentImage) -> Unit,
    private val onToggleFavorite: (RecentImage) -> Unit,
    private val onRemove: (RecentImage) -> Unit
) : ArrayAdapter<RecentImage>(context, 0, items) {

    fun updateItems(newItems: List<RecentImage>) {
        items = newItems
        clear()
        addAll(newItems)
        notifyDataSetChanged()
    }

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context)
            .inflate(R.layout.item_recent_image, parent, false)
        val item = items[position]

        val nameView = view.findViewById<TextView>(R.id.itemName)
        val detailsView = view.findViewById<TextView>(R.id.itemDetails)
        val favoriteButton = view.findViewById<ImageButton>(R.id.favoriteButton)
        val removeButton = view.findViewById<ImageButton>(R.id.removeButton)

        nameView.text = item.name
        val sizeMb = item.sizeBytes / (1024 * 1024)
        detailsView.text = "$sizeMb MB"

        favoriteButton.setImageResource(
            if (item.favorite) android.R.drawable.btn_star_big_on
            else android.R.drawable.btn_star_big_off
        )

        view.setOnClickListener { onSelect(item) }
        favoriteButton.setOnClickListener { onToggleFavorite(item) }
        removeButton.setOnClickListener { onRemove(item) }

        return view
    }
}
