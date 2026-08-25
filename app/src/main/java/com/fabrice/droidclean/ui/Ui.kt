package com.fabrice.droidclean.ui

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar

/**
 * Petits utilitaires d'interface.
 *
 * [applySystemBarInsets] est la contrepartie obligatoire du bord-à-bord imposé
 * par Android 15 quand `targetSdk` vaut 35 : sans lui, le contenu passe sous la
 * barre d'état et sous la barre de navigation.
 */
object Ui {

    /**
     * Décale [view] de la hauteur des barres système et de l'encoche, en
     * conservant son rembourrage d'origine.
     */
    fun applySystemBarInsets(view: View) {
        val base = intArrayOf(
            view.paddingLeft,
            view.paddingTop,
            view.paddingRight,
            view.paddingBottom,
        )
        ViewCompat.setOnApplyWindowInsetsListener(view) { target, windowInsets ->
            val insets = windowInsets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            target.updatePadding(
                left = base[0] + insets.left,
                top = base[1] + insets.top,
                right = base[2] + insets.right,
                bottom = base[3] + insets.bottom,
            )
            windowInsets
        }
        ViewCompat.requestApplyInsets(view)
    }

    /**
     * Un Snackbar plutôt qu'un Toast : le Toast est tronqué à deux lignes depuis
     * Android 12, ne peut porter aucune action, et est limité en fréquence.
     */
    fun snack(
        anchor: View,
        message: CharSequence,
        actionLabel: CharSequence? = null,
        long: Boolean = false,
        action: (() -> Unit)? = null,
    ): Snackbar = Snackbar.make(
        anchor,
        message,
        if (long) Snackbar.LENGTH_LONG else Snackbar.LENGTH_SHORT,
    ).apply {
        if (actionLabel != null && action != null) setAction(actionLabel) { action() }
        show()
    }
}
