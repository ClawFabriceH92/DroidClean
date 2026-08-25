package com.fabrice.droidclean.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.fabrice.droidclean.databinding.ItemFileBinding
import com.fabrice.droidclean.databinding.ItemGroupHeaderBinding
import java.io.File

/** Liste de fichiers, éventuellement groupés par un en-tête libre. */
class FileRowAdapter(
    private val onClick: (File) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    sealed interface Row {
        data class Header(val text: String) : Row
        data class Entry(val file: File, val sizeLabel: String) : Row
    }

    private var rows: List<Row> = emptyList()

    @Suppress("NotifyDataSetChanged") // changement de mode = liste entièrement neuve
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
            EntryHolder(ItemFileBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = rows[position]) {
            is Row.Header -> (holder as HeaderHolder).binding.root.text = row.text
            is Row.Entry -> (holder as EntryHolder).bind(row)
        }
    }

    private class HeaderHolder(val binding: ItemGroupHeaderBinding) :
        RecyclerView.ViewHolder(binding.root)

    private inner class EntryHolder(private val binding: ItemFileBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: Row.Entry) {
            binding.tvFileName.text = row.file.name
            binding.tvFilePath.text = row.file.parent.orEmpty()
            binding.tvFileSize.text = row.sizeLabel
            binding.root.setOnClickListener { onClick(row.file) }
            binding.root.contentDescription = "${row.file.name}, ${row.sizeLabel}"
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ENTRY = 1
    }
}
