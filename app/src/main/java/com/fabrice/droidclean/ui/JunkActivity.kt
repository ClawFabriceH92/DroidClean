package com.fabrice.droidclean.ui

import android.os.Bundle
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fabrice.droidclean.R
import com.fabrice.droidclean.clean.Cleaner
import com.fabrice.droidclean.clean.CleanScheduler
import com.fabrice.droidclean.clean.JunkCategory
import com.fabrice.droidclean.clean.JunkFilter
import com.fabrice.droidclean.clean.JunkItem
import com.fabrice.droidclean.clean.JunkScan
import com.fabrice.droidclean.databinding.ActivityJunkBinding
import com.fabrice.droidclean.history.CleanHistory
import com.fabrice.droidclean.util.Sizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Écran de sélection : ce qui peut être supprimé, catégorie par catégorie,
 * fichier par fichier.
 *
 * Rien n'est coché d'office dans les catégories qui contiennent des documents de
 * l'utilisateur — c'est tout l'objet de cet écran.
 */
class JunkActivity : AppCompatActivity() {

    private lateinit var binding: ActivityJunkBinding
    private lateinit var adapter: JunkAdapter

    private var scan: JunkScan = JunkScan.empty(hasStorageAccess = false)
    private val selectedPaths = LinkedHashSet<String>()
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityJunkBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.applySystemBarInsets(binding.junkRoot)

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = JunkAdapter(::toggle)
        binding.rvJunk.layoutManager = LinearLayoutManager(this)
        binding.rvJunk.adapter = adapter

        listOf(binding.chipOld, binding.chipBig, binding.chipSafeOnly).forEach { chip ->
            chip.setOnCheckedChangeListener { _, _ -> render() }
        }
        binding.chipSelectAll.setOnClickListener { toggleSelectAll() }
        binding.btnCleanSelected.setOnClickListener { confirmClean() }

        loadScan()
    }

    // ------------------------------------------------------------------ données

    private fun loadScan() {
        setLoading(true)
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { Cleaner.scan(applicationContext) }
            scan = result
            // Pré-sélection : uniquement ce qui se régénère. Les documents de
            // l'utilisateur restent décochés tant qu'il ne les coche pas lui-même.
            selectedPaths.clear()
            result.items.filter { it.category.isSafe }.forEach { selectedPaths.add(it.path) }
            setLoading(false)
            render()
        }
    }

    private fun visibleItems(): List<JunkItem> {
        val filter = JunkFilter(
            categories = if (binding.chipSafeOnly.isChecked) {
                scan.items.map { it.category }.filter { it.isSafe }.toSet()
            } else {
                scan.items.map { it.category }.toSet()
            },
            minBytes = if (binding.chipBig.isChecked) BIG_FILE_BYTES else 0L,
            olderThanDays = if (binding.chipOld.isChecked) OLD_FILE_DAYS else 0,
        )
        return filter.apply(scan.items, System.currentTimeMillis())
    }

    // ------------------------------------------------------------------ affichage

    private fun render() {
        val visible = visibleItems()
        val rows = ArrayList<JunkAdapter.Row>(visible.size + 8)
        visible.groupBy { it.category }
            .toList()
            .sortedWith(
                compareByDescending<Pair<JunkCategory, List<JunkItem>>> {
                    it.second.sumOf { item -> item.sizeBytes }
                }.thenBy { it.first.name }
            )
            .forEach { (category, items) ->
                rows.add(JunkAdapter.Row.Header(category, items.sumOf { it.sizeBytes }))
                items.forEach { item ->
                    rows.add(JunkAdapter.Row.Entry(item, item.path in selectedPaths))
                }
            }
        adapter.submit(rows)

        binding.tvJunkEmpty.visibility = if (rows.isEmpty() && !busy) View.VISIBLE else View.GONE
        binding.tvJunkSummary.text = getString(
            R.string.junk_summary,
            Sizes.bytes(this, selectedBytes()),
            Sizes.bytes(this, scan.totalBytes),
        )
        renderSelection()
    }

    private fun renderSelection() {
        val count = selectedItems().size
        val bytes = selectedBytes()
        binding.tvSelection.text = if (count == 0) {
            getString(R.string.junk_nothing_selected)
        } else {
            getString(R.string.junk_selection, count, Sizes.bytes(this, bytes))
        }
        binding.btnCleanSelected.isEnabled = count > 0 && !busy
        binding.chipSelectAll.setText(
            if (allVisibleSelected()) R.string.junk_select_none else R.string.junk_select_all
        )
    }

    private fun setLoading(loading: Boolean) {
        busy = loading
        binding.progressJunk.visibility = if (loading) View.VISIBLE else View.GONE
        binding.btnCleanSelected.isEnabled = !loading && selectedItems().isNotEmpty()
        binding.tvJunkSummary.setText(if (loading) R.string.junk_scanning else R.string.placeholder)
    }

    // ------------------------------------------------------------------ sélection

    private fun selectedItems(): List<JunkItem> = scan.items.filter { it.path in selectedPaths }

    private fun selectedBytes(): Long = selectedItems().sumOf { it.sizeBytes }

    private fun allVisibleSelected(): Boolean {
        val visible = visibleItems()
        return visible.isNotEmpty() && visible.all { it.path in selectedPaths }
    }

    private fun toggle(item: JunkItem) {
        if (!selectedPaths.remove(item.path)) selectedPaths.add(item.path)
        render()
    }

    private fun toggleSelectAll() {
        val visible = visibleItems()
        if (allVisibleSelected()) {
            visible.forEach { selectedPaths.remove(it.path) }
        } else {
            visible.forEach { selectedPaths.add(it.path) }
        }
        render()
    }

    // ------------------------------------------------------------------ nettoyage

    private fun confirmClean() {
        val items = selectedItems()
        if (items.isEmpty()) return
        val useTrash = CleanScheduler.useTrash(this)
        val message = getString(
            if (useTrash) R.string.junk_confirm_message else R.string.junk_confirm_message_no_trash,
            items.size,
            Sizes.bytes(this, items.sumOf { it.sizeBytes }),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.junk_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.junk_confirm_action) { _, _ -> doClean(items, useTrash) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doClean(items: List<JunkItem>, useTrash: Boolean) {
        setLoading(true)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                Cleaner.clean(applicationContext, items, useTrash)
            }
            CleanHistory.record(applicationContext, outcome)

            val message = buildString {
                append(getString(R.string.clean_freed, Sizes.bytes(this@JunkActivity, outcome.freedBytes)))
                if (outcome.trashedBytes > 0) {
                    append(" · ")
                    append(
                        getString(
                            R.string.clean_trashed,
                            Sizes.bytes(this@JunkActivity, outcome.trashedBytes),
                        )
                    )
                }
                if (outcome.failed > 0) {
                    append(" · ")
                    append(getString(R.string.clean_failed, outcome.failed))
                }
            }
            Ui.snack(binding.junkRoot, message, long = true)
            loadScan()
        }
    }

    private companion object {
        const val BIG_FILE_BYTES = 10L * 1024 * 1024
        const val OLD_FILE_DAYS = 30
    }
}
