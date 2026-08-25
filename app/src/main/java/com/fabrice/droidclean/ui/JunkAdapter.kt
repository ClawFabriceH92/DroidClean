package com.fabrice.droidclean.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fabrice.droidclean.R
import com.fabrice.droidclean.clean.JunkCategory
import com.fabrice.droidclean.clean.JunkItem
import com.fabrice.droidclean.databinding.ItemGroupHeaderBinding
import com.fabrice.droidclean.databinding.ItemJunkBinding
import com.fabrice.droidclean.util.Formats
import com.fabrice.droidclean.util.Sizes

/**
 * Liste des éléments nettoyables, groupés par catégorie et cochables un à un.
 *
 * C'est le remplaçant du bouton « Nettoyer » d'origine, qui vidait le dossier
 * Téléchargements en entier : factures et photos comprises.
 */
class JunkAdapter(
    private val onToggle: (JunkItem) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed interface Row {
        data class Header(val category: JunkCategory, val bytes: Long) : Row
        data class Entry(val item: JunkItem, val selected: Boolean) : Row
    }

    private var rows: List<Row> = emptyList()

    @Suppress("NotifyDataSetChanged") // la liste est reconstruite en entier à chaque filtre
    fun submit(newRows: List<Row>) {
        rows = newRows
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = rows.size

    override fun getItemViewType(position: Int): Int =
        if (rows[position] is Row.Header) TYPE_HEADER else TYPE_ENTRY

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemGroupHeaderBinding.inflate(inflater, parent, false))
        } else {
            EntryHolder(ItemJunkBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).bind(row)
            is Row.Entry -> (holder as EntryHolder).bind(row)
        }
    }

    private class HeaderHolder(
        private val binding: ItemGroupHeaderBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(row: Row.Header) {
            val context = binding.root.context
            binding.root.text = context.getString(
                R.string.junk_category_header,
                context.getString(Categories.labelOf(row.category)),
                Sizes.bytes(context, row.bytes),
            )
        }
    }

    private inner class EntryHolder(
        private val binding: ItemJunkBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row.Entry) {
            val context = binding.root.context
            binding.tvItemName.text = row.item.name
            binding.tvItemSize.text = Sizes.bytes(context, row.item.sizeBytes)
            binding.tvItemMeta.text = metaOf(row.item)
            binding.cbSelected.isChecked = row.selected
            // La ligne entière est la cible tactile : viser une case à cocher de
            // 20dp au pouce n'est pas une expérience.
            binding.root.setOnClickListener { onToggle(row.item) }
            binding.root.contentDescription = "${row.item.name}, ${binding.tvItemSize.text}"
        }

        private fun metaOf(item: JunkItem): String {
            val context = binding.root.context
            val size = item.path
            return when (val days = Formats.daysSince(item.lastModified)) {
                -1 -> context.getString(R.string.junk_item_meta_unknown, size)
                0 -> context.getString(R.string.junk_item_meta_today, size)
                else -> context.getString(R.string.junk_item_meta, size, days)
            }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ENTRY = 1
    }
}
