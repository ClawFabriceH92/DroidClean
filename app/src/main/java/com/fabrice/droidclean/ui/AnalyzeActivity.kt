package com.fabrice.droidclean.ui

import android.os.Bundle
import android.os.Environment
import android.view.View
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.fabrice.droidclean.R
import com.fabrice.droidclean.analyze.StorageAnalyzer
import com.fabrice.droidclean.clean.Cleaner
import com.fabrice.droidclean.databinding.ActivityAnalyzeBinding
import com.fabrice.droidclean.util.Sizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * « Où sont passés mes gigaoctets » : plus gros fichiers et doublons.
 *
 * Aucune suppression automatique — chaque fichier se supprime individuellement,
 * après confirmation.
 */
class AnalyzeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAnalyzeBinding
    private lateinit var adapter: FileRowAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityAnalyzeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.applySystemBarInsets(binding.analyzeRoot)

        binding.toolbar.setNavigationOnClickListener { finish() }

        adapter = FileRowAdapter(::confirmDelete)
        binding.rvAnalyze.layoutManager = LinearLayoutManager(this)
        binding.rvAnalyze.adapter = adapter

        binding.chipsMode.setOnCheckedStateChangeListener { _, _ -> load() }
        load()
    }

    private fun roots(): List<File> {
        if (!Cleaner.hasStorageAccess(this)) return emptyList()
        val root = Environment.getExternalStorageDirectory() ?: return emptyList()
        return listOf(root).filter { it.isDirectory }
    }

    /** `Android/` est écarté : `data` et `obb` y sont illisibles ou intouchables. */
    private val skipAndroidDir: (File) -> Boolean = { file -> file.name == "Android" }

    private fun load() {
        val roots = roots()
        if (roots.isEmpty()) {
            showEmpty(getString(R.string.analyze_needs_permission))
            return
        }
        val duplicatesMode = binding.chipDuplicates.isChecked
        binding.tvAnalyzeEmpty.visibility = View.GONE
        binding.progressAnalyze.visibility = View.VISIBLE
        binding.tvAnalyzeSummary.setText(R.string.analyze_scanning)
        adapter.submit(emptyList())

        lifecycleScope.launch {
            if (duplicatesMode) loadDuplicates(roots) else loadLargest(roots)
            binding.progressAnalyze.visibility = View.GONE
        }
    }

    private suspend fun loadLargest(roots: List<File>) {
        val files = withContext(Dispatchers.IO) {
            StorageAnalyzer.largestFiles(roots = roots, limit = 100, skip = skipAndroidDir)
        }
        if (files.isEmpty()) {
            showEmpty(getString(R.string.analyze_empty_largest))
            return
        }
        adapter.submit(
            files.map { FileRowAdapter.Row.Entry(it.file, Sizes.bytes(this, it.sizeBytes)) }
        )
        binding.tvAnalyzeSummary.text = getString(
            R.string.analyze_summary_largest,
            files.size,
            Sizes.bytes(this, files.sumOf { it.sizeBytes }),
        )
    }

    private suspend fun loadDuplicates(roots: List<File>) {
        val groups = withContext(Dispatchers.IO) {
            StorageAnalyzer.duplicates(roots = roots, skip = skipAndroidDir)
        }
        if (groups.isEmpty()) {
            showEmpty(getString(R.string.analyze_empty_duplicates))
            return
        }
        val rows = ArrayList<FileRowAdapter.Row>()
        groups.forEach { group ->
            rows.add(
                FileRowAdapter.Row.Header(
                    getString(
                        R.string.analyze_group_header,
                        group.files.size,
                        Sizes.bytes(this, group.sizeBytes),
                        Sizes.bytes(this, group.wastedBytes),
                    )
                )
            )
            group.files.forEach { file ->
                rows.add(FileRowAdapter.Row.Entry(file, Sizes.bytes(this, group.sizeBytes)))
            }
        }
        adapter.submit(rows)
        binding.tvAnalyzeSummary.text = getString(
            R.string.analyze_summary_duplicates,
            groups.size,
            Sizes.bytes(this, groups.sumOf { it.wastedBytes }),
        )
    }

    private fun showEmpty(message: String) {
        adapter.submit(emptyList())
        binding.tvAnalyzeEmpty.text = message
        binding.tvAnalyzeEmpty.visibility = View.VISIBLE
        binding.tvAnalyzeSummary.text = ""
    }

    private fun confirmDelete(file: File) {
        AlertDialog.Builder(this)
            .setTitle(file.name)
            .setMessage(file.absolutePath)
            .setPositiveButton(R.string.analyze_delete) { _, _ -> delete(file) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun delete(file: File) {
        lifecycleScope.launch {
            val size = file.length()
            val deleted = withContext(Dispatchers.IO) { runCatching { file.delete() }.getOrDefault(false) }
            if (deleted) {
                Ui.snack(
                    binding.analyzeRoot,
                    getString(R.string.analyze_deleted, Sizes.bytes(this@AnalyzeActivity, size)),
                )
                load()
            } else {
                Ui.snack(binding.analyzeRoot, getString(R.string.analyze_delete_failed))
            }
        }
    }
}
