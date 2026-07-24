package com.fabrice.droidclean

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.card.MaterialCardView
import java.io.File
import java.text.NumberFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Nettoyage
    private lateinit var tvCleanSize: TextView
    private lateinit var btnAnalyzeClean: Button
    private lateinit var btnClean: Button
    private var downloadSize: Long = 0L
    private var cacheSize: Long = 0L

    // RAM
    private lateinit var ramUsedBar: View
    private lateinit var ramFreeBar: View
    private lateinit var tvRamInfo: TextView

    // Batterie
    private lateinit var tvBatteryPct: TextView
    private lateinit var tvBatteryTemp: TextView
    private lateinit var tvBatteryHealth: TextView
    private lateinit var tvBatteryStatus: TextView

    // Stockage
    private lateinit var storageUsedBar: View
    private lateinit var tvStorageInfo: TextView

    private val nf = NumberFormat.getNumberInstance(Locale.FRANCE).apply {
        maximumFractionDigits = 1
        minimumFractionDigits = 1
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Nettoyage
        tvCleanSize = findViewById(R.id.tvCleanSize)
        btnAnalyzeClean = findViewById(R.id.btnAnalyzeClean)
        btnClean = findViewById(R.id.btnClean)
        btnAnalyzeClean.setOnClickListener { analyzeClean() }
        btnClean.setOnClickListener { doClean() }

        // RAM
        ramUsedBar = findViewById(R.id.ramUsedBar)
        ramFreeBar = findViewById(R.id.ramFreeBar)
        tvRamInfo = findViewById(R.id.tvRamInfo)
        findViewById<Button>(R.id.btnBoost).setOnClickListener { boostRam() }

        // Batterie
        tvBatteryPct = findViewById(R.id.tvBatteryPct)
        tvBatteryTemp = findViewById(R.id.tvBatteryTemp)
        tvBatteryHealth = findViewById(R.id.tvBatteryHealth)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)

        // Stockage
        storageUsedBar = findViewById(R.id.storageUsedBar)
        tvStorageInfo = findViewById(R.id.tvStorageInfo)
        findViewById<Button>(R.id.btnOpenDownloads).setOnClickListener { openDownloads() }

        // Apps
        findViewById<Button>(R.id.btnAppSettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS))
        }

        // Charger toutes les infos
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        refreshAll()
    }

    private fun refreshAll() {
        refreshRamInfo()
        refreshBatteryInfo()
        refreshStorageInfo()
        refreshAppsInfo()
    }

    // ===================== NETTOYAGE =====================

    private fun analyzeClean() {
        btnAnalyzeClean.isEnabled = false
        btnAnalyzeClean.text = "Analyse..."
        tvCleanSize.text = "..."

        Thread {
            downloadSize = getFolderSize(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            )
            cacheSize = getFolderSize(cacheDir ?: File(""))

            runOnUiThread {
                val total = downloadSize + cacheSize
                if (total > 0) {
                    tvCleanSize.text = formatBytes(total)
                    btnClean.isEnabled = true
                    btnAnalyzeClean.text = "Ré-analyser"
                } else {
                    tvCleanSize.text = "0 Ko"
                    btnClean.isEnabled = false
                    btnAnalyzeClean.text = "Analyser"
                }
                btnAnalyzeClean.isEnabled = true
                showToast("Téléchargements : ${formatBytes(downloadSize)} · Cache : ${formatBytes(cacheSize)}")
            }
        }.start()
    }

    private fun doClean() {
        AlertDialog.Builder(this)
            .setTitle("🧹 Nettoyer")
            .setMessage("Supprimer ${formatBytes(downloadSize)} du dossier Téléchargements et ${formatBytes(cacheSize)} du cache ?")
            .setPositiveButton("Nettoyer") { _, _ ->
                Thread {
                    var deleted = 0L
                    var errors = 0

                    // Vider Download
                    val downloadDir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS
                    )
                    if (downloadDir.exists()) {
                        val files = downloadDir.listFiles()
                        if (files != null) {
                            for (f in files) {
                                if (deleteRecursive(f)) deleted += getFileSize(f)
                                else errors++
                            }
                        }
                    }

                    // Vider notre cache
                    cacheDir?.let {
                        val files = it.listFiles()
                        if (files != null) {
                            for (f in files) {
                                if (deleteRecursive(f)) deleted += getFileSize(f)
                            }
                        }
                    }

                    val finalDeleted = deleted
                    val finalErrors = errors
                    runOnUiThread {
                        if (finalErrors > 0) {
                            showToast("✅ ${formatBytes(finalDeleted)} libérés · $finalErrors fichiers verrouillés")
                        } else {
                            showToast("✅ ${formatBytes(finalDeleted)} libérés !")
                        }
                        downloadSize = 0L
                        cacheSize = 0L
                        tvCleanSize.text = "0 Ko"
                        btnClean.isEnabled = false
                        btnAnalyzeClean.text = "Analyser"
                        btnAnalyzeClean.isEnabled = true
                        refreshAll()
                    }
                }.start()
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    private fun getFolderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var size = 0L
        val files = dir.listFiles() ?: return 0L
        for (f in files) {
            size += if (f.isDirectory) getFolderSize(f) else f.length()
        }
        return size
    }

    private fun getFileSize(f: File): Long {
        return if (f.isDirectory) getFolderSize(f) else f.length()
    }

    private fun deleteRecursive(f: File): Boolean {
        if (f.isDirectory) {
            f.listFiles()?.forEach { deleteRecursive(it) }
        }
        return f.delete()
    }

    // ===================== RAM =====================

    private fun refreshRamInfo() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)

        val totalMb = mi.totalMem / 1048576L
        val availMb = mi.availMem / 1048576L
        val usedMb = totalMb - availMb
        val pct = if (totalMb > 0) (usedMb * 100 / totalMb).toInt() else 0

        tvRamInfo.text = "Utilisé : ${formatMb(usedMb)} / ${formatMb(totalMb)} (${pct}%)"

        // Barre proportionnelle
        val lp = ramUsedBar.layoutParams
        lp.width = (pct * 9).coerceAtMost(2700)  // scale ~9x pour remplir l'écran
        ramUsedBar.layoutParams = lp
    }

    private fun boostRam() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val before = mi.availMem

        var killed = 0
        try {
            val runningApps = am.runningAppProcesses
            if (runningApps != null) {
                for (process in runningApps) {
                    if (process.importance > ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                        try {
                            am.killBackgroundProcesses(process.processName)
                            killed++
                        } catch (_: Exception) {}
                    }
                }
            }
        } catch (_: Exception) {}

        // GC
        System.gc()
        System.runFinalization()

        val am2 = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mi2 = ActivityManager.MemoryInfo()
        am2.getMemoryInfo(mi2)
        val after = mi2.availMem
        val freed = (after - before) / 1048576L

        showToast("⚡ $killed processus arrêtés · ${formatMb(freed)} libérés")
        refreshRamInfo()
    }

    // ===================== BATTERIE =====================

    private fun refreshBatteryInfo() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            tvBatteryPct.text = "N/A"
            return
        }

        val level = intent.getIntExtra("level", -1)
        val scale = intent.getIntExtra("scale", -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else 0

        val tempC = intent.getIntExtra("temperature", -1).toFloat() / 10f
        val health = intent.getIntExtra("health", -1)
        val status = intent.getIntExtra("status", -1)
        val plugged = intent.getIntExtra("plugged", -1)

        tvBatteryPct.text = "$pct%"
        tvBatteryTemp.text = "${tempC}°C"

        tvBatteryHealth.text = when (health) {
            android.os.BatteryManager.BATTERY_HEALTH_GOOD -> "✅ Bonne"
            android.os.BatteryManager.BATTERY_HEALTH_OVERHEAT -> "🔥 Surchauffe"
            android.os.BatteryManager.BATTERY_HEALTH_DEAD -> "💀 Défunte"
            android.os.BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "⚠️ Tension"
            else -> "?"
        }

        tvBatteryStatus.text = when (status) {
            android.os.BatteryManager.BATTERY_STATUS_CHARGING -> {
                when (plugged) {
                    android.os.BatteryManager.BATTERY_PLUGGED_USB -> "🔌 Charge USB"
                    android.os.BatteryManager.BATTERY_PLUGGED_AC -> "⚡ Charge secteur"
                    android.os.BatteryManager.BATTERY_PLUGGED_WIRELESS -> "🌀 Charge sans fil"
                    else -> "🔌 En charge"
                }
            }
            android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "📉 Décharge"
            android.os.BatteryManager.BATTERY_STATUS_FULL -> "✅ Pleine"
            android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "🔋 Pas en charge"
            else -> ""
        }

        tvBatteryPct.setTextColor(
            when {
                pct <= 15 -> resources.getColor(android.R.color.holo_red_dark, theme)
                pct <= 30 -> resources.getColor(android.R.color.holo_orange_dark, theme)
                else -> resources.getColor(android.R.color.holo_green_dark, theme)
            }
        )
    }

    // ===================== STOCKAGE =====================

    private fun refreshStorageInfo() {
        try {
            val path = Environment.getExternalStorageDirectory()
            val stat = StatFs(path.path)
            val blockSize = stat.blockSizeLong
            val totalBytes = stat.blockCountLong * blockSize
            val freeBytes = stat.availableBlocksLong * blockSize
            val usedBytes = totalBytes - freeBytes
            val pct = if (totalBytes > 0) (usedBytes * 100 / totalBytes).toInt() else 0

            tvStorageInfo.text = "Utilisé : ${formatBytes(usedBytes)} / ${formatBytes(totalBytes)} (${pct}%)"

            val lp = storageUsedBar.layoutParams
            lp.width = (pct * 9).coerceAtMost(2700)
            storageUsedBar.layoutParams = lp
        } catch (e: Exception) {
            tvStorageInfo.text = "Stockage : ${e.message}"
        }
    }

    private fun openDownloads() {
        // Essayer d'ouvrir le dossier Downloads
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                data = android.net.Uri.parse(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath
                )
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(Intent.createChooser(intent, "Ouvrir avec"))
        } catch (_: Exception) {
            showToast("Ouvrez un gestionnaire de fichiers")
        }
    }

    // ===================== APPLICATIONS =====================

    private fun refreshAppsInfo() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
        val userApps = apps.count {
            (it.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0
        }
        val systemApps = apps.size - userApps
        val tvAppsInfo = findViewById<TextView>(R.id.tvAppsInfo)
        tvAppsInfo.text = "📱 $userApps utilisateur · ⚙️ $systemApps système · ${apps.size} total"
    }

    // ===================== UTILITAIRES =====================

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes < 1024L -> "$bytes o"
            bytes < 1048576L -> "${nf.format(bytes / 1024.0)} Ko"
            bytes < 1073741824L -> "${nf.format(bytes / 1048576.0)} Mo"
            else -> "${nf.format(bytes / 1073741824.0)} Go"
        }
    }

    private fun formatMb(mb: Long): String {
        return if (mb < 1024) "${mb} Mo" else "${nf.format(mb / 1024.0)} Go"
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
