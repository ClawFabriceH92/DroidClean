package com.fabrice.droidclean.apps

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build

/** Accès à la liste des paquets installés, avec la variante d'API qui va bien. */
object Packages {

    /** Liste complète, ou vide si la plateforme refuse (jamais d'exception remontée). */
    fun installed(context: Context): List<ApplicationInfo> = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
        }
    } catch (_: Exception) {
        emptyList()
    }

    fun isUserApp(info: ApplicationInfo): Boolean =
        (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0 ||
            (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0

    /**
     * Noms de paquets installés. Un ensemble **vide signale un échec** : les
     * appelants doivent alors désactiver toute logique de type « résidu ».
     */
    fun installedNames(context: Context): Set<String> =
        installed(context).mapTo(HashSet()) { it.packageName }
}
