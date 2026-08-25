package com.fabrice.droidclean.tile

import android.app.PendingIntent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.fabrice.droidclean.R
import com.fabrice.droidclean.storage.StorageInfo
import com.fabrice.droidclean.ui.MainIntent
import com.fabrice.droidclean.util.Sizes

/**
 * Tuile Réglages rapides : espace libre en un coup d'œil, analyse en un geste.
 *
 * La tuile **ne supprime rien elle-même**. Un raccourci qui efface des fichiers
 * sans confirmation depuis le volet des réglages serait exactement le genre de
 * comportement qu'on reproche aux « boosters » ; elle ouvre donc l'app avec
 * l'analyse déjà lancée, et la suppression reste derrière une confirmation.
 */
class CleanTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    // La surcharge Intent lève une exception dès qu'on cible Android 14+, d'où
    // l'avertissement de lint. Elle reste pourtant la seule disponible en dessous,
    // et cette branche ne s'exécute que là : la version PendingIntent n'existe qu'à
    // partir de l'API 34.
    @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
    override fun onClick() {
        super.onClick()
        val intent = MainIntent.of(this, startScan = true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(
                    this,
                    0,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                )
            )
        } else {
            startActivityAndCollapse(intent)
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val free = StorageInfo.primary(this)?.freeBytes
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.app_name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (free != null) {
                getString(R.string.tile_free, Sizes.bytes(this, free))
            } else {
                getString(R.string.tile_open)
            }
        }
        tile.contentDescription = getString(R.string.tile_content_description)
        tile.updateTile()
    }
}
