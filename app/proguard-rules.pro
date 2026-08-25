# DroidClean — règles R8
#
# Aucun code de l'app n'est appelé par réflexion, sauf ce que la plateforme
# instancie elle-même : les Workers de WorkManager, la classe Application, les
# activités, le receiver et le TileService (ceux-là sont déjà conservés via le
# manifeste). Un `-keep` global annulerait purement et simplement la minification.

-keep class com.fabrice.droidclean.update.UpdateWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

-keep class com.fabrice.droidclean.clean.AutoCleanWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
