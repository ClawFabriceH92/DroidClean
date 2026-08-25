package com.fabrice.droidclean.ui

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.ViewGroup
import android.util.LruCache
import androidx.recyclerview.widget.RecyclerView
import com.fabrice.droidclean.R
import com.fabrice.droidclean.apps.AppStorage
import com.fabrice.droidclean.databinding.ItemAppBinding
import com.fabrice.droidclean.util.Formats
import com.fabrice.droidclean.util.Sizes

/** Liste des applications avec leur poids réel (APK + données + cache). */
class AppsAdapter(
    private val onClick: (AppStorage.Entry) -> Unit,
) : RecyclerView.Adapter<AppsAdapter.Holder>() {

    private var entries: List<AppStorage.Entry> = emptyList()

    /** Les icônes sont coûteuses à charger : on garde les dernières sous la main. */
    private val icons = LruCache<String, Drawable>(64)

    @Suppress("NotifyDataSetChanged") // tri et filtre reconstruisent toute la liste
    fun submit(newEntries: List<AppStorage.Entry>) {
        entries = newEntries
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = entries.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) = holder.bind(entries[position])

    inner class Holder(private val binding: ItemAppBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entry: AppStorage.Entry) {
            val context = binding.root.context
            binding.tvAppLabel.text = entry.label
            binding.tvAppSize.text = Sizes.bytes(context, entry.totalBytes)

            val days = Formats.daysSince(entry.lastUsedAt)
            val app = Sizes.bytes(context, entry.appBytes)
            val data = Sizes.bytes(context, entry.dataBytes + entry.cacheBytes)
            binding.tvAppDetail.text = if (days < 0) {
                context.getString(R.string.apps_detail_never, app, data)
            } else {
                context.getString(R.string.apps_detail_used, app, data, days)
            }

            binding.ivAppIcon.setImageDrawable(iconOf(entry.packageName))
            binding.root.setOnClickListener { onClick(entry) }
            binding.root.contentDescription = "${entry.label}, ${binding.tvAppSize.text}"
        }

        private fun iconOf(packageName: String): Drawable? {
            icons.get(packageName)?.let { return it }
            val pm = binding.root.context.packageManager
            val drawable = runCatching { pm.getApplicationIcon(packageName) }.getOrNull()
            if (drawable != null) icons.put(packageName, drawable)
            return drawable
        }
    }
}
