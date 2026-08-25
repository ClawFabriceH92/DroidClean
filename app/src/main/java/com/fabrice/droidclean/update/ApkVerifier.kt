package com.fabrice.droidclean.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Contrôle de l'APK téléchargé avant de lancer l'installeur.
 *
 * Android refusera de toute façon de remplacer l'app par un APK signé d'une
 * autre clé — mais il le fera par un échec opaque, une fois l'installeur ouvert.
 * Vérifier avant permet d'afficher la vraie raison (« cet APK n'est pas signé
 * par la même clé »), qui est en pratique le symptôme d'une CI sans secrets de
 * signature, pas d'une attaque.
 */
object ApkVerifier {

    enum class Verdict {
        OK,
        MISSING,
        UNREADABLE,
        WRONG_PACKAGE,
        WRONG_SIGNATURE,
        NOT_NEWER,
    }

    /** Lit la version déclarée par l'APK, ou null s'il est illisible. */
    fun versionNameOf(context: Context, apk: File): String? =
        archiveInfo(context, apk)?.versionName

    /** L'APK est-il installable en toute sécurité par-dessus l'app en place ? */
    fun verify(context: Context, apk: File): Verdict {
        if (!apk.isFile || apk.length() == 0L) return Verdict.MISSING
        val archive = archiveInfo(context, apk) ?: return Verdict.UNREADABLE
        if (archive.packageName != context.packageName) return Verdict.WRONG_PACKAGE

        val installed = try {
            packageInfo(context, context.packageName)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return Verdict.UNREADABLE

        if (UpdateChecker.compareVersions(
                archive.versionName.orEmpty(),
                installed.versionName.orEmpty(),
            ) <= 0
        ) {
            return Verdict.NOT_NEWER
        }

        val expected = signatureDigests(installed)
        val actual = signatureDigests(archive)
        if (expected.isEmpty() || actual.isEmpty()) return Verdict.UNREADABLE
        return if (expected == actual) Verdict.OK else Verdict.WRONG_SIGNATURE
    }

    private fun archiveInfo(context: Context, apk: File): PackageInfo? = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(
                apk.absolutePath,
                PackageManager.PackageInfoFlags.of(SIGNING_FLAGS.toLong()),
            )
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageArchiveInfo(apk.absolutePath, SIGNING_FLAGS)
        }
    } catch (_: Exception) {
        null
    }

    private fun packageInfo(context: Context, packageName: String): PackageInfo {
        val pm = context.packageManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(SIGNING_FLAGS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, SIGNING_FLAGS)
        }
    }

    /** Empreintes des certificats signataires, indépendantes de l'ordre. */
    private fun signatureDigests(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // `apkContentsSigners` dans les deux cas : c'est ce qui a réellement
            // signé CE fichier, donc la seule chose comparable de part et d'autre.
            info.signingInfo?.apkContentsSigners ?: return emptySet()
        } else {
            @Suppress("DEPRECATION")
            info.signatures
        }
        return signatures.orEmpty().mapNotNull { signature ->
            runCatching {
                MessageDigest.getInstance("SHA-256")
                    .digest(signature.toByteArray())
                    .joinToString("") { byte -> "%02x".format(byte) }
            }.getOrNull()
        }.toSet()
    }

    private val SIGNING_FLAGS: Int
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
}
