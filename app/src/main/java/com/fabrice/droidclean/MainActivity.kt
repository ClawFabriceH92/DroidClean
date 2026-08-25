package com.fabrice.droidclean

import android.Manifest
import android.app.ActivityManager
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.LinearLayout
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fabrice.droidclean.apps.Packages
import com.fabrice.droidclean.battery.BatteryInfo
import com.fabrice.droidclean.clean.CleanScheduler
import com.fabrice.droidclean.clean.Cleaner
import com.fabrice.droidclean.crash.CrashReporter
import com.fabrice.droidclean.databinding.ActivityMainBinding
import com.fabrice.droidclean.history.CleanHistory
import com.fabrice.droidclean.storage.StorageInfo
import com.fabrice.droidclean.ui.AnalyzeActivity
import com.fabrice.droidclean.ui.AppsActivity
import com.fabrice.droidclean.ui.Categories
import com.fabrice.droidclean.ui.JunkActivity
import com.fabrice.droidclean.ui.MainIntent
import com.fabrice.droidclean.ui.Ui
import com.fabrice.droidclean.update.AutoUpdater
import com.fabrice.droidclean.update.UpdateInfo
import com.fabrice.droidclean.update.UpdateManager
import com.fabrice.droidclean.util.Formats
import com.fabrice.droidclean.util.Sizes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Tableau de bord.
 *
 * Il informe et oriente ; les actions destructrices vivent dans [JunkActivity],
 * derrière une sélection explicite. La seule suppression déclenchable d'ici est
 * le nettoyage des caches, qui ne touche à aucun document.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    private var pendingUpdate: UpdateInfo? = null
    private var scanning = false

    /** État du bouton de mise à jour : « Vérifier » ou « Installer ». */
    private var installMode = false
    private var apkFingerprint: String? = null
    private var readyInstallIntent: Intent? = null

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* informatif */ }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) openJunkScreen() else showPermissionDenied()
        }

    private val allFilesAccessLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Le résultat n'est pas fiable : on relit l'état réel de la permission.
            if (Cleaner.hasStorageAccess(this)) openJunkScreen() else showPermissionDenied()
            refreshPermissionBanner()
        }

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // De retour des réglages : si c'est bon, on reprend là où on s'était arrêté.
            val info = pendingUpdate
            if (info != null && AutoUpdater.canRequestInstalls(this)) startDownload(info)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        Ui.applySystemBarInsets(binding.scrollRoot)

        bindActions()
        binding.tvFooter.text = getString(R.string.footer_version, BuildConfig.VERSION_NAME)

        if (isFirstRun()) showOnboarding()
        if (intent?.getBooleanExtra(MainIntent.EXTRA_START_SCAN, false) == true) openJunkScreen()

        // Un APK de mise à jour déjà installé n'a plus rien à faire sur le disque.
        lifecycleScope.launch(Dispatchers.IO) { AutoUpdater.discardObsoleteApk(applicationContext) }

        observeDownloadProgress()
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

    // ------------------------------------------------------------------- câblage

    private fun bindActions() {
        binding.swipeRefresh.setOnRefreshListener { refreshAll() }

        binding.btnAnalyze.setOnClickListener { onAnalyzeClicked() }
        binding.btnQuickClean.setOnClickListener { quickClean() }
        binding.btnGrantStorage.setOnClickListener { requestStorageAccess() }

        binding.btnAnalyzeStorage.setOnClickListener {
            if (Cleaner.hasStorageAccess(this)) {
                startActivity(Intent(this, AnalyzeActivity::class.java))
            } else {
                requestStorageAccess()
            }
        }
        binding.btnOpenDownloads.setOnClickListener { openDownloads() }

        binding.btnAppsStorage.setOnClickListener {
            startActivity(Intent(this, AppsActivity::class.java))
        }
        binding.btnAppSettings.setOnClickListener { openAppSettings() }

        binding.btnCheckUpdate.setOnClickListener { checkUpdateNow() }
        binding.swAutoUpdate.isChecked = UpdateManager.autoUpdateEnabled(this)
        binding.swAutoUpdate.setOnCheckedChangeListener { _, checked ->
            UpdateManager.setAutoUpdate(this, checked)
            if (checked) askNotificationPermission()
        }

        binding.swAutoClean.isChecked = CleanScheduler.autoCleanEnabled(this)
        binding.swAutoClean.setOnCheckedChangeListener { _, checked ->
            CleanScheduler.setAutoClean(this, checked)
            if (checked) askNotificationPermission()
        }
        binding.swUseTrash.isChecked = CleanScheduler.useTrash(this)
        binding.swUseTrash.setOnCheckedChangeListener { _, checked ->
            CleanScheduler.setUseTrash(this, checked)
        }

        binding.btnPrivacy.setOnClickListener { showPrivacy() }
        binding.btnCrashShare.setOnClickListener { shareCrashReport() }
        binding.btnCrashDismiss.setOnClickListener {
            CrashReporter.clear(this)
            binding.cardCrash.visibility = View.GONE
        }
    }

    private fun refreshAll() {
        refreshPermissionBanner()
        refreshCrashBanner()
        refreshCleanCard()
        refreshStorage()
        refreshApps()
        refreshRam()
        refreshBattery()
        binding.swipeRefresh.isRefreshing = false
    }

    // --------------------------------------------------------------- permissions

    private fun refreshPermissionBanner() {
        binding.cardPermission.visibility =
            if (Cleaner.hasStorageAccess(this)) View.GONE else View.VISIBLE
    }

    /**
     * Android 11+ : « Accès à tous les fichiers » depuis les Réglages.
     * En dessous : permission d'exécution classique.
     */
    private fun requestStorageAccess() {
        val intent = Cleaner.allFilesAccessIntent(this)
        if (intent == null) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        runCatching { allFilesAccessLauncher.launch(intent) }
            .onFailure { showPermissionDenied() }
    }

    private fun showPermissionDenied() {
        Ui.snack(binding.scrollRoot, getString(R.string.permission_denied), long = true)
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ------------------------------------------------------------------ plantage

    private fun refreshCrashBanner() {
        binding.cardCrash.visibility =
            if (CrashReporter.pendingReport(this) != null) View.VISIBLE else View.GONE
    }

    private fun shareCrashReport() {
        val report = CrashReporter.pendingReport(this) ?: return
        val intent = CrashReporter.shareIntent(getString(R.string.crash_subject), report)
        runCatching { startActivity(Intent.createChooser(intent, getString(R.string.crash_share))) }
            .onSuccess { CrashReporter.clear(this) }
            .onFailure { Ui.snack(binding.scrollRoot, getString(R.string.crash_no_app)) }
    }

    // ----------------------------------------------------------------- nettoyage

    private fun onAnalyzeClicked() {
        if (Cleaner.hasStorageAccess(this)) openJunkScreen() else requestStorageAccess()
    }

    private fun openJunkScreen() {
        startActivity(Intent(this, JunkActivity::class.java))
    }

    private fun refreshCleanCard() {
        val month = CleanHistory.bytesThisMonth(this)
        binding.tvCleanHistory.text = if (month > 0) {
            getString(R.string.clean_history_month, Sizes.bytes(this, month))
        } else {
            getString(R.string.clean_history_none)
        }

        if (scanning) return
        scanning = true
        binding.tvCleanSize.text = getString(R.string.placeholder)
        binding.tvCleanBreakdown.setText(R.string.clean_analyzing)

        lifecycleScope.launch {
            val scan = withContext(Dispatchers.IO) { Cleaner.scan(applicationContext) }
            scanning = false
            binding.tvCleanSize.text = Sizes.bytes(this@MainActivity, scan.totalBytes)
            binding.tvCleanBreakdown.text = if (scan.items.isEmpty()) {
                getString(R.string.clean_nothing)
            } else {
                scan.categoriesByWeight().take(3).joinToString(" · ") { category ->
                    getString(
                        R.string.junk_category_header,
                        getString(Categories.labelOf(category)),
                        Sizes.bytes(this@MainActivity, scan.bytesOf(category)),
                    )
                }
            }
        }
    }

    /** Ne supprime que ce qui se régénère : aucun document n'est concerné. */
    private fun quickClean() {
        binding.btnQuickClean.isEnabled = false
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { Cleaner.cleanSafeOnly(applicationContext) }
            CleanHistory.record(applicationContext, outcome)
            Ui.snack(
                binding.scrollRoot,
                if (outcome.freedBytes > 0) {
                    getString(R.string.clean_freed, Sizes.bytes(this@MainActivity, outcome.freedBytes))
                } else {
                    getString(R.string.clean_nothing)
                },
            )
            binding.btnQuickClean.isEnabled = true
            refreshAll()
        }
    }

    // ------------------------------------------------------------------ stockage

    private fun refreshStorage() {
        val primary = StorageInfo.primary(this)
        if (primary == null) {
            binding.tvStorageInfo.setText(R.string.storage_unavailable)
            return
        }
        val pct = Formats.percent(primary.usedBytes, primary.totalBytes)
        binding.tvStorageInfo.text = getString(
            R.string.storage_info,
            Sizes.bytes(this, primary.usedBytes),
            Sizes.bytes(this, primary.totalBytes),
            pct,
        )
        setBarRatio(binding.storageUsedBar, binding.storageFreeBar, pct)

        val others = StorageInfo.volumes(this).filterNot { it.isPrimary }
        binding.tvStorageVolumes.visibility = if (others.isEmpty()) View.GONE else View.VISIBLE
        binding.tvStorageVolumes.text = others.joinToString("\n") { volume ->
            getString(
                R.string.storage_volume_line,
                volume.label.ifBlank { getString(R.string.storage_volume_default) },
                Sizes.bytes(this, volume.freeBytes),
                Sizes.bytes(this, volume.totalBytes),
            )
        }
    }

    private fun openDownloads() {
        // ACTION_VIEW_DOWNLOADS est l'écran système des téléchargements :
        // un chemin de fichier brut passé à ACTION_VIEW n'ouvre rien.
        runCatching { startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) }
            .onFailure { Ui.snack(binding.scrollRoot, getString(R.string.storage_no_file_manager)) }
    }

    // -------------------------------------------------------------- applications

    private fun refreshApps() {
        lifecycleScope.launch {
            val counts = withContext(Dispatchers.IO) {
                val apps = Packages.installed(applicationContext)
                val user = apps.count { Packages.isUserApp(it) }
                user to (apps.size - user)
            }
            binding.tvAppsInfo.text = getString(R.string.apps_info, counts.first, counts.second)
        }
    }

    private fun openAppSettings() {
        // ACTION_APPLICATION_DETAILS_SETTINGS exige une URI "package:" : sans elle,
        // l'intent ne résout rien. C'est la liste complète que l'on veut ici.
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_APPLICATIONS_SETTINGS),
            Intent(Settings.ACTION_APPLICATION_SETTINGS),
        )
        for (intent in candidates) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        Ui.snack(binding.scrollRoot, getString(R.string.apps_settings_unavailable))
    }

    // ------------------------------------------------------------------- mémoire

    private fun refreshRam() {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am == null) {
            binding.tvRamInfo.setText(R.string.ram_unavailable)
            return
        }
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val totalMb = info.totalMem / BYTES_PER_MB
        val availMb = info.availMem / BYTES_PER_MB
        val usedMb = (totalMb - availMb).coerceAtLeast(0L)
        val pct = Formats.percent(usedMb, totalMb)

        binding.tvRamInfo.text = getString(
            R.string.ram_info,
            Sizes.megabytes(this, usedMb),
            Sizes.megabytes(this, totalMb),
            pct,
        )
        setBarRatio(binding.ramUsedBar, binding.ramFreeBar, pct)
        binding.tvRamNote.setText(if (info.lowMemory) R.string.ram_low else R.string.ram_note)
    }

    // ------------------------------------------------------------------ batterie

    private fun refreshBattery() {
        val battery = BatteryInfo.read(this)
        if (!battery.available) {
            binding.tvBatteryPct.setText(R.string.battery_unavailable)
            return
        }

        binding.tvBatteryPct.text = getString(R.string.battery_pct, battery.percent)
        binding.tvBatteryTemp.text = getString(
            R.string.battery_temp,
            String.format(Locale.getDefault(), "%.1f", battery.temperatureC),
        )
        binding.tvBatteryHealth.setText(healthLabel(battery.health))
        binding.tvBatteryStatus.setText(statusLabel(battery.status, battery.plugged))
        binding.tvBatteryDetails.text = batteryDetails(battery)

        binding.tvBatteryPct.setTextColor(
            ContextCompat.getColor(
                this,
                when {
                    battery.percent <= 15 -> R.color.battery_critical
                    battery.percent <= 30 -> R.color.battery_low
                    else -> R.color.battery_ok
                },
            )
        )
    }

    /** Détails d'usure : seules les valeurs réellement publiées par l'appareil. */
    private fun batteryDetails(battery: BatteryInfo.Snapshot): String {
        val parts = ArrayList<String>(4)
        if (battery.voltageMv > 0) {
            parts.add(
                getString(
                    R.string.battery_detail_voltage,
                    String.format(Locale.getDefault(), "%.2f", battery.voltageMv / 1000f),
                )
            )
        }
        battery.capacityMah?.let { parts.add(getString(R.string.battery_detail_capacity, it)) }
        if (battery.cycleCount > 0) {
            parts.add(getString(R.string.battery_detail_cycles, battery.cycleCount))
        }
        if (battery.isCharging && battery.chargeTimeRemainingMs > 0) {
            val minutes = (battery.chargeTimeRemainingMs / 60_000L).toInt()
            if (minutes > 0) parts.add(getString(R.string.battery_detail_charge_time, minutes))
        }
        battery.technology?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return parts.joinToString(" · ")
    }

    private fun healthLabel(health: Int): Int = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> R.string.battery_health_good
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> R.string.battery_health_overheat
        BatteryManager.BATTERY_HEALTH_DEAD -> R.string.battery_health_dead
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> R.string.battery_health_over_voltage
        BatteryManager.BATTERY_HEALTH_COLD -> R.string.battery_health_cold
        else -> R.string.battery_health_unknown
    }

    private fun statusLabel(status: Int, plugged: Int): Int = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> when (plugged) {
            BatteryManager.BATTERY_PLUGGED_USB -> R.string.battery_charging_usb
            BatteryManager.BATTERY_PLUGGED_AC -> R.string.battery_charging_ac
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> R.string.battery_charging_wireless
            else -> R.string.battery_charging
        }
        BatteryManager.BATTERY_STATUS_DISCHARGING -> R.string.battery_discharging
        BatteryManager.BATTERY_STATUS_FULL -> R.string.battery_full
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> R.string.battery_not_charging
        else -> R.string.placeholder
    }

    // --------------------------------------------------------------- mises à jour

    private fun checkUpdateNow() {
        binding.btnCheckUpdate.isEnabled = false
        binding.btnCheckUpdate.setText(R.string.update_checking)
        binding.tvUpdateStatus.setText(R.string.update_checking)

        lifecycleScope.launch {
            when (val result = UpdateManager.check(applicationContext, interactive = true)) {
                is UpdateManager.CheckResult.UpToDate ->
                    binding.tvUpdateStatus.text = getString(R.string.update_up_to_date, result.current)

                is UpdateManager.CheckResult.Available -> showUpdateDialog(result.info)

                is UpdateManager.CheckResult.Downloading ->
                    binding.tvUpdateStatus.text =
                        getString(R.string.update_downloading, result.info.versionName)

                is UpdateManager.CheckResult.PermissionNeeded -> requestInstallPermission(result.info)

                UpdateManager.CheckResult.Failed ->
                    binding.tvUpdateStatus.setText(R.string.update_check_failed)
            }
            binding.btnCheckUpdate.setText(R.string.update_check_now)
            binding.btnCheckUpdate.isEnabled = true
        }
    }

    /** Les notes de version étaient récupérées puis jetées : elles sont désormais montrées. */
    private fun showUpdateDialog(info: UpdateInfo) {
        val body = buildString {
            append(info.notes?.takeIf { it.isNotBlank() } ?: getString(R.string.update_available_no_notes))
            if (info.sizeBytes > 0) {
                append("\n\n")
                append(getString(R.string.update_available_size, Sizes.bytes(this@MainActivity, info.sizeBytes)))
            }
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.update_available_title, info.versionName))
            .setMessage(body)
            .setPositiveButton(R.string.update_download) { _, _ -> startDownload(info) }
            .setNegativeButton(R.string.update_later) { _, _ ->
                UpdateManager.skipVersion(this, info.versionName)
                binding.tvUpdateStatus.text = getString(R.string.update_skipped, info.versionName)
            }
            .show()
    }

    private fun startDownload(info: UpdateInfo) {
        pendingUpdate = info
        when (val result = UpdateManager.startDownload(this, info)) {
            is UpdateManager.CheckResult.Downloading -> {
                askNotificationPermission()
                binding.tvUpdateStatus.text =
                    getString(R.string.update_downloading, result.info.versionName)
            }
            is UpdateManager.CheckResult.PermissionNeeded -> requestInstallPermission(info)
            else -> binding.tvUpdateStatus.setText(R.string.update_download_failed)
        }
    }

    private fun requestInstallPermission(info: UpdateInfo) {
        pendingUpdate = info
        binding.tvUpdateStatus.setText(R.string.update_permission_needed)
        Ui.snack(binding.scrollRoot, getString(R.string.update_permission_needed), long = true)
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            android.net.Uri.parse("package:$packageName"),
        )
        runCatching { installPermissionLauncher.launch(intent) }
            .onFailure { AutoUpdater.openInstallSettings(this) }
    }

    /**
     * Suit l'avancement du téléchargement tant que l'écran est visible.
     * Sans cela, la carte n'affichait rien entre « téléchargement lancé » et
     * « mise à jour prête », soit plusieurs mégaoctets de silence.
     */
    private fun observeDownloadProgress() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    renderDownloadState()
                    delay(POLL_INTERVAL_MS)
                }
            }
        }
    }

    private suspend fun renderDownloadState() {
        val progress = withContext(Dispatchers.IO) { AutoUpdater.progress(applicationContext) }
        if (progress != null && !progress.failed) {
            showDownloadProgress(progress)
            return
        }
        val ready = withContext(Dispatchers.IO) { cachedInstallIntent() }
        if (ready != null) showReadyToInstall(ready) else showCheckButton()
    }

    private fun showDownloadProgress(progress: AutoUpdater.Progress) {
        binding.progressUpdate.visibility = View.VISIBLE
        if (progress.percent >= 0) {
            binding.progressUpdate.isIndeterminate = false
            binding.progressUpdate.setProgressCompat(progress.percent, true)
            binding.tvUpdateStatus.text = getString(R.string.update_progress, progress.percent)
        } else {
            binding.progressUpdate.isIndeterminate = true
        }
    }

    private fun showReadyToInstall(intent: Intent) {
        binding.progressUpdate.visibility = View.GONE
        binding.tvUpdateStatus.setText(R.string.update_ready)
        if (installMode) return
        installMode = true
        binding.btnCheckUpdate.setText(R.string.update_install)
        binding.btnCheckUpdate.setOnClickListener { runCatching { startActivity(intent) } }
    }

    /** Rend au bouton son rôle de vérification si l'APK a disparu entre-temps. */
    private fun showCheckButton() {
        binding.progressUpdate.visibility = View.GONE
        if (!installMode) return
        installMode = false
        binding.btnCheckUpdate.setText(R.string.update_check_now)
        binding.btnCheckUpdate.setOnClickListener { checkUpdateNow() }
    }

    /**
     * Vérifier la signature d'un APK suppose de le relire en entier : hors de
     * question de le refaire à chaque seconde de la boucle. Le résultat n'est
     * recalculé que si le fichier a changé de taille ou de date.
     */
    private fun cachedInstallIntent(): Intent? {
        val file = AutoUpdater.apkFile(applicationContext)
        if (!file.isFile) {
            apkFingerprint = null
            readyInstallIntent = null
            return null
        }
        val fingerprint = "${file.length()}:${file.lastModified()}"
        if (fingerprint != apkFingerprint) {
            apkFingerprint = fingerprint
            readyInstallIntent = AutoUpdater.installIntent(applicationContext)
        }
        return readyInstallIntent
    }

    // ------------------------------------------------------------------ divers

    private fun showPrivacy() {
        AlertDialog.Builder(this)
            .setTitle(R.string.privacy_title)
            .setMessage(R.string.privacy_message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun isFirstRun(): Boolean {
        val prefs = getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ONBOARDED, false)) {
            prefs.edit().putBoolean(KEY_ONBOARDED, true).apply()
            return true
        }
        return false
    }

    private fun showOnboarding() {
        AlertDialog.Builder(this)
            .setTitle(R.string.onboarding_title)
            .setMessage(R.string.onboarding_message)
            .setPositiveButton(R.string.onboarding_ok, null)
            .show()
    }

    /** Répartit la barre en poids : indépendant de la densité et de la largeur d'écran. */
    private fun setBarRatio(usedBar: View, freeBar: View, pct: Int) {
        val used = usedBar.layoutParams as? LinearLayout.LayoutParams ?: return
        val free = freeBar.layoutParams as? LinearLayout.LayoutParams ?: return
        used.weight = pct.toFloat()
        free.weight = (100 - pct).toFloat()
        usedBar.layoutParams = used
        freeBar.layoutParams = free
    }

    private companion object {
        const val BYTES_PER_MB = 1_048_576L
        const val POLL_INTERVAL_MS = 1_000L
        const val UI_PREFS = "droidclean_ui"
        const val KEY_ONBOARDED = "onboarded"
    }
}
