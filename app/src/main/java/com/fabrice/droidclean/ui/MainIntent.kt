package com.fabrice.droidclean.ui

import android.content.Context
import android.content.Intent
import com.fabrice.droidclean.MainActivity

/** Intent de retour vers l'écran principal, partagé par les notifications et la tuile. */
object MainIntent {

    /** Demande à l'écran principal de lancer une analyse dès son ouverture. */
    const val EXTRA_START_SCAN = "com.fabrice.droidclean.START_SCAN"

    fun of(context: Context, startScan: Boolean = false): Intent =
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_START_SCAN, startScan)
}
