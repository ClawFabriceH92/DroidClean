package com.fabrice.droidclean.ui

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fabrice.droidclean.R
import com.fabrice.droidclean.apps.AppStorage
import com.fabrice.droidclean.databinding.ActivityAppsBinding
import com.fabrice.droidclean.util.Sizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Poids réel de chaque application, et le moyen d'en désinstaller une.
 *
 * Le vrai levier de récupération d'espace sur un téléphone plein : une app de
 * 1,2 Go inutilisée depuis huit mois pèse plus lourd que tous les caches réunis.
 */
class AppsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAppsBinding
    private lateinit var adapter: AppsAdapter
    private var entries: List<AppStorage.Entry> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.applySystemBarInsets(binding.appsRoot)

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = AppsAdapter(::showActions)
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter

        listOf(
            binding.chipSortSize,
            binding.chipSortUnused,
            binding.chipSortName,
            binding.chipShowSystem,
        ).forEach { chip -> chip.setOnCheckedChangeListener { _, _ -> render() } }

        binding.btnGrantUsage.setOnClickListener {
            runCatching { startActivity(AppStorage.usageAccessIntent()) }
                .onFailure { Ui.snack(binding.appsRoot, getString(R.string.apps_usage_unavailable)) }
        }
    }

    override fun onResume() {
        super.onResume()
        // L'autorisation d'accès à l'usage peut avoir été accordée entre-temps,
        // et une app désinstallée doit disparaître de la liste.
        load()
    }

    private fun load() {
        if (!AppStorage.hasUsageAccess(this)) {
            binding.progressApps.visibility = View.GONE
            binding.emptyApps.visibility = View.VISIBLE
            binding.tvAppsSummary.text = ""
            adapter.submit(emptyList())
            return
        }
        binding.emptyApps.visibility = View.GONE
        binding.progressApps.visibility = View.VISIBLE
        lifecycleScope.launch {
            entries = withContext(Dispatchers.IO) { AppStorage.list(applicationContext) }
            binding.progressApps.visibility = View.GONE
            render()
        }
    }

    private fun render() {
        val visible = entries
            .filter { binding.chipShowSystem.isChecked || !it.isSystem }
            .sortedWith(currentComparator())
        adapter.submit(visible)
        binding.tvAppsSummary.text = getString(
            R.string.apps_summary,
            visible.size,
            Sizes.bytes(this, visible.sumOf { it.totalBytes }),
        )
    }

    private fun currentComparator(): Comparator<AppStorage.Entry> = when {
        binding.chipSortName.isChecked -> compareBy { it.label.lowercase() }
        // « Inutilisées » d'abord : jamais utilisée (0) devient le plus ancien possible.
        binding.chipSortUnused.isChecked ->
            compareBy<AppStorage.Entry> { if (it.lastUsedAt == 0L) Long.MIN_VALUE else it.lastUsedAt }
                .thenByDescending { it.totalBytes }
        else -> compareByDescending<AppStorage.Entry> { it.totalBytes }
            .thenBy { it.label.lowercase() }
    }

    private fun showActions(entry: AppStorage.Entry) {
        val actions = arrayOf(
            getString(R.string.apps_action_info),
            getString(R.string.apps_action_uninstall),
        )
        AlertDialog.Builder(this)
            .setTitle(entry.label)
            .setItems(actions) { _, which ->
                val intent = if (which == 0) {
                    AppStorage.appInfoIntent(entry.packageName)
                } else {
                    AppStorage.uninstallIntent(entry.packageName)
                }
                runCatching { startActivity(intent) }.onFailure {
                    Ui.snack(binding.appsRoot, getString(R.string.apps_uninstall_unavailable))
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
