package com.fabrice.droidclean

import android.Manifest
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.fabrice.droidclean.clean.Cleaner
import com.fabrice.droidclean.update.AutoUpdater
import com.fabrice.droidclean.update.UpdateManager
import com.fabrice.droidclean.util.Formats
import com.google.android.material.switchmaterial.SwitchMaterial
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : AppCompatActivity() {

    // Nettoyage
    private lateinit var tvCleanSize: TextView
    private lateinit var btnAnalyzeClean: Button
    private lateinit var btnClean: Button
    private var lastScan: Cleaner.Scan? = null

    // RAM
    private lateinit var ramUsedBar: View
    private lateinit var ramFreeBar: View
    private lateinit var tvRamInfo: TextView
    private lateinit var btnBoost: Button

    // Batterie
    private lateinit var tvBatteryPct: TextView
    private lateinit var tvBatteryTemp: TextView
    private lateinit var tvBatteryHealth: TextView
    private lateinit var tvBatteryStatus: TextView

    // Stockage
    private lateinit var storageUsedBar: View
    private lateinit var storageFreeBar: View
    private lateinit var tvStorageInfo: TextView

    // Applications
    private lateinit var tvAppsInfo: TextView

    // Mises à jour
    private lateinit var swAutoUpdate: SwitchMaterial
    private lateinit var btnCheckUpdate: Button

    /** Une analyse est en attente de l'autorisation de stockage. */
    private var analyzeWhenGranted = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* informatif */ }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted && analyzeWhenGranted) analyzeClean()
            analyzeWhenGranted = false
        }

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Le résultat n'est pas fiable : on relit l'état réel de la permission.
            if (analyzeWhenGranted && Cleaner.hasStorageAccess(this)) analyzeClean()
            analyzeWhenGranted = false
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        UpdateManager.start(this)
        askNotificationPermissionIfNeeded()

        tvFooter().text = getString(R.string.footer_version, BuildConfig.VERSION_NAME)
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        UpdateManager.appInForeground = true
        refreshAll()
    }

    override fun onPause() {
        super.onPause()
        UpdateManager.appInForeground = false
    }

    private fun tvFooter(): TextView = findViewById(R.id.tvFooter)

    private fun bindViews() {
        // Nettoyage
        tvCleanSize = findViewById(R.id.tvCleanSize)
        btnAnalyzeClean = findViewById(R.id.btnAnalyzeClean)
        btnClean = findViewById(R.id.btnClean)
        btnAnalyzeClean.setOnClickListener { onAnalyzeClicked() }
        btnClean.setOnClickListener { confirmClean() }

        // RAM
        ramUsedBar = findViewById(R.id.ramUsedBar)
        ramFreeBar = findViewById(R.id.ramFreeBar)
        tvRamInfo = findViewById(R.id.tvRamInfo)
        btnBoost = findViewById(R.id.btnBoost)
        btnBoost.setOnClickListener { boostRam() }

        // Batterie
        tvBatteryPct = findViewById(R.id.tvBatteryPct)
        tvBatteryTemp = findViewById(R.id.tvBatteryTemp)
        tvBatteryHealth = findViewById(R.id.tvBatteryHealth)
        tvBatteryStatus = findViewById(R.id.tvBatteryStatus)

        // Stockage
        storageUsedBar = findViewById(R.id.storageUsedBar)
        storageFreeBar = findViewById(R.id.storageFreeBar)
        tvStorageInfo = findViewById(R.id.tvStorageInfo)
        findViewById<Button>(R.id.btnOpenDownloads).setOnClickListener { openDownloads() }

        // Applications
        tvAppsInfo = findViewById(R.id.tvAppsInfo)
        findViewById<Button>(R.id.btnAppSettings).setOnClickListener { openAppSettings() }

        // Mises à jour
        swAutoUpdate = findViewById(R.id.swAutoUpdate)
        swAutoUpdate.isChecked = UpdateManager.autoUpdateEnabled(this)
        swAutoUpdate.setOnCheckedChangeListener { _, checked ->
            UpdateManager.setAutoUpdate(this, checked)
        }
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnCheckUpdate.setOnClickListener { checkUpdateNow() }
    }

    private fun refreshAll() {
        refreshRamInfo()
        refreshBatteryInfo()
        refreshStorageInfo()
        refreshAppsInfo()
    }

    // ===================== NETTOYAGE =====================

    private fun onAnalyzeClicked() {
        if (Cleaner.hasStorageAccess(this)) {
            analyzeClean()
        } else {
            analyzeWhenGranted = true
            requestStorageAccess()
        }
    }

    /**
     * Android 11+ : « Accès à tous les fichiers » depuis les Réglages.
     * Avant : permission d'exécution classique.
     */
    private fun requestStorageAccess() {
        val intent = Cleaner.allFilesAccessIntent(this)
        if (intent != null) {
            try {
                allFilesAccessLauncher.launch(intent)
                return
            } catch (_: Exception) {
                analyzeWhenGranted = false
                showToast(getString(R.string.clean_permission_needed))
                return
            }
        }
        storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }

    private fun analyzeClean() {
        btnAnalyzeClean.isEnabled = false
        btnAnalyzeClean.text = getString(R.string.clean_analyzing)
        tvCleanSize.text = getString(R.string.placeholder)

        lifecycleScope.launch {
            val scan = withContext(Dispatchers.IO) { Cleaner.scan(applicationContext) }
            lastScan = scan

            if (scan.totalBytes > 0) {
                tvCleanSize.text = Formats.bytes(scan.totalBytes)
                btnClean.isEnabled = true
                btnAnalyzeClean.text = getString(R.string.clean_reanalyze)
            } else {
                tvCleanSize.text = Formats.bytes(0)
                btnClean.isEnabled = false
                btnAnalyzeClean.text = getString(R.string.clean_analyze)
            }
            btnAnalyzeClean.isEnabled = true

            if (!scan.hasStorageAccess) {
                showToast(getString(R.string.clean_permission_needed))
            } else {
                showToast(
                    getString(
                        R.string.clean_detail,
                        Formats.bytes(scan.downloadsBytes),
                        Formats.bytes(scan.cacheBytes),
                    )
                )
            }
        }
    }

    private fun confirmClean() {
        val scan = lastScan ?: return
        AlertDialog.Builder(this)
            .setTitle(R.string.clean_dialog_title)
            .setMessage(
                getString(
                    R.string.clean_dialog_message,
                    Formats.bytes(scan.downloadsBytes),
                    Formats.bytes(scan.cacheBytes),
                )
            )
            .setPositiveButton(R.string.clean_action) { _, _ -> doClean() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun doClean() {
        btnClean.isEnabled = false
        btnAnalyzeClean.isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { Cleaner.clean(applicationContext) }

            showToast(
                if (result.failedFiles > 0) {
                    getString(
                        R.string.clean_freed_with_errors,
                        Formats.bytes(result.freedBytes),
                        result.failedFiles,
                    )
                } else {
                    getString(R.string.clean_freed, Formats.bytes(result.freedBytes))
                }
            )

            lastScan = null
            tvCleanSize.text = Formats.bytes(0)
            btnClean.isEnabled = false
            btnAnalyzeClean.text = getString(R.string.clean_analyze)
            btnAnalyzeClean.isEnabled = true
            refreshAll()
        }
    }

    // ===================== RAM =====================

    private fun memoryInfo(): ActivityManager.MemoryInfo {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    }

    private fun refreshRamInfo() {
        val mi = memoryInfo()
        val totalMb = mi.totalMem / 1_048_576L
        val availMb = mi.availMem / 1_048_576L
        val usedMb = (totalMb - availMb).coerceAtLeast(0L)
        val pct = Formats.percent(usedMb, totalMb)

        tvRamInfo.text = getString(
            R.string.ram_info,
            Formats.megabytes(usedMb),
            Formats.megabytes(totalMb),
            pct,
        )
        setBarRatio(ramUsedBar, ramFreeBar, pct)
    }

    /**
     * Demande au système de libérer les processus en cache.
     * Note : depuis Android 5.1, une app ne « voit » plus les processus des autres
     * applications ; l'effet réel est donc limité et on se contente d'annoncer la
     * mémoire effectivement récupérée, sans promesse fantaisiste.
     */
    private fun boostRam() {
        btnBoost.isEnabled = false
        lifecycleScope.launch {
            val before = memoryInfo().availMem
            withContext(Dispatchers.Default) {
                try {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.runningAppProcesses?.forEach { process ->
                        if (process.importance >
                            ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                        ) {
                            runCatching { am.killBackgroundProcesses(process.processName) }
                        }
                    }
                } catch (_: Exception) {
                    // Rien à faire : la plateforme peut refuser.
                }
                System.gc()
            }
            delay(600) // laisse au système le temps de récupérer la mémoire
            val freedMb = (memoryInfo().availMem - before) / 1_048_576L

            showToast(
                if (freedMb > 0) getString(R.string.ram_freed, Formats.megabytes(freedMb))
                else getString(R.string.ram_no_change)
            )
            refreshRamInfo()
            btnBoost.isEnabled = true
        }
    }

    // ===================== BATTERIE =====================

    private fun refreshBatteryInfo() {
        val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            tvBatteryPct.text = getString(R.string.battery_unavailable)
            return
        }

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) level * 100 / scale else 0
        val tempC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f

        tvBatteryPct.text = getString(R.string.battery_pct, pct)
        tvBatteryTemp.text =
            getString(R.string.battery_temp, String.format(Locale.getDefault(), "%.1f", tempC))

        tvBatteryHealth.text = when (intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> getString(R.string.battery_health_good)
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> getString(R.string.battery_health_overheat)
            BatteryManager.BATTERY_HEALTH_DEAD -> getString(R.string.battery_health_dead)
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE ->
                getString(R.string.battery_health_over_voltage)
            BatteryManager.BATTERY_HEALTH_COLD -> getString(R.string.battery_health_cold)
            else -> getString(R.string.battery_health_unknown)
        }

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        tvBatteryStatus.text = when (intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> when (plugged) {
                BatteryManager.BATTERY_PLUGGED_USB -> getString(R.string.battery_charging_usb)
                BatteryManager.BATTERY_PLUGGED_AC -> getString(R.string.battery_charging_ac)
                BatteryManager.BATTERY_PLUGGED_WIRELESS ->
                    getString(R.string.battery_charging_wireless)
                else -> getString(R.string.battery_charging)
            }
            BatteryManager.BATTERY_STATUS_DISCHARGING -> getString(R.string.battery_discharging)
            BatteryManager.BATTERY_STATUS_FULL -> getString(R.string.battery_full)
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> getString(R.string.battery_not_charging)
            else -> ""
        }

        tvBatteryPct.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    pct <= 15 -> R.color.battery_critical
                    pct <= 30 -> R.color.battery_low
                    else -> R.color.battery_ok
                }
            )
        )
    }

    // ===================== STOCKAGE =====================

    private fun refreshStorageInfo() {
        try {
            val stat = StatFs(Environment.getExternalStorageDirectory().path)
            val totalBytes = stat.blockCountLong * stat.blockSizeLong
            val freeBytes = stat.availableBlocksLong * stat.blockSizeLong
            val usedBytes = (totalBytes - freeBytes).coerceAtLeast(0L)
            val pct = Formats.percent(usedBytes, totalBytes)

            tvStorageInfo.text = getString(
                R.string.storage_info,
                Formats.bytes(usedBytes),
                Formats.bytes(totalBytes),
                pct,
            )
            setBarRatio(storageUsedBar, storageFreeBar, pct)
        } catch (_: Exception) {
            tvStorageInfo.text = getString(R.string.storage_unavailable)
        }
    }

    private fun openDownloads() {
        // ACTION_VIEW_DOWNLOADS est l'écran système des téléchargements :
        // un chemin de fichier brut passé à ACTION_VIEW n'ouvre rien.
        try {
            startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS))
        } catch (_: Exception) {
            showToast(getString(R.string.storage_no_file_manager))
        }
    }

    // ===================== APPLICATIONS =====================

    private fun refreshAppsInfo() {
        lifecycleScope.launch {
            val counts = withContext(Dispatchers.IO) {
                val apps = try {
                    packageManager.getInstalledApplications(0)
                } catch (_: Exception) {
                    emptyList<ApplicationInfo>()
                }
                val user = apps.count { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                Triple(user, apps.size - user, apps.size)
            }
            tvAppsInfo.text =
                getString(R.string.apps_info, counts.first, counts.second, counts.third)
        }
    }

    private fun openAppSettings() {
        // ACTION_APPLICATION_DETAILS_SETTINGS exige une URI "package:" : sans elle,
        // l'intent ne résout rien. C'est la liste complète que l'on veut ici.
        val intents = listOf(
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
        )
        for (intent in intents) {
            try {
                startActivity(intent)
                return
            } catch (_: Exception) {
                // On tente le suivant.
            }
        }
        showToast(getString(R.string.apps_settings_unavailable))
    }

    // ===================== MISES À JOUR =====================

    private fun checkUpdateNow() {
        btnCheckUpdate.isEnabled = false
        btnCheckUpdate.text = getString(R.string.update_checking)
        lifecycleScope.launch {
            val result = UpdateManager.check(applicationContext)
            showToast(
                when (result) {
                    is UpdateManager.CheckResult.UpToDate ->
                        getString(R.string.update_up_to_date, result.current)
                    is UpdateManager.CheckResult.Downloading ->
                        getString(R.string.update_available, result.version)
                    is UpdateManager.CheckResult.PermissionNeeded ->
                        getString(R.string.update_notif_permission_text)
                    UpdateManager.CheckResult.Failed ->
                        getString(R.string.update_check_failed)
                }
            )
            if (result is UpdateManager.CheckResult.PermissionNeeded) {
                AutoUpdater.openInstallSettings(this@MainActivity)
            }
            btnCheckUpdate.text = getString(R.string.update_check_now)
            btnCheckUpdate.isEnabled = true
        }
    }

    private fun askNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ===================== UTILITAIRES =====================

    /** Répartit la barre en poids (indépendant de la densité et de la largeur d'écran). */
    private fun setBarRatio(usedBar: View, freeBar: View, pct: Int) {
        val used = usedBar.layoutParams as? LinearLayout.LayoutParams ?: return
        val free = freeBar.layoutParams as? LinearLayout.LayoutParams ?: return
        used.weight = pct.toFloat()
        free.weight = (100 - pct).toFloat()
        usedBar.layoutParams = used
        freeBar.layoutParams = free
    }

    private fun showToast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
