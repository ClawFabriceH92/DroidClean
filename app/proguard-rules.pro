# DroidClean — règles R8
#
# Aucun code de l'app n'est appelé par réflexion, sauf le Worker instancié par
# WorkManager : inutile de tout conserver (un `-keep` global annulerait purement
# et simplement la minification).

-keep class com.fabrice.droidclean.update.UpdateWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
