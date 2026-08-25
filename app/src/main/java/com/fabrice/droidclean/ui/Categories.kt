package com.fabrice.droidclean.ui

import androidx.annotation.StringRes
import com.fabrice.droidclean.R
import com.fabrice.droidclean.clean.JunkCategory

/** Libellés traduits des catégories de nettoyage. */
object Categories {

    @StringRes
    fun labelOf(category: JunkCategory): Int = when (category) {
        JunkCategory.DOWNLOADS -> R.string.category_downloads
        JunkCategory.APP_CACHE -> R.string.category_app_cache
        JunkCategory.APP_MEDIA_CACHE -> R.string.category_app_media_cache
        JunkCategory.LEFTOVER -> R.string.category_leftover
        JunkCategory.THUMBNAILS -> R.string.category_thumbnails
        JunkCategory.LOST_DIR -> R.string.category_lost_dir
        JunkCategory.EMPTY_DIR -> R.string.category_empty_dir
    }
}
