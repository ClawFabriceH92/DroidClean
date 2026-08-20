# DroidClean

Application Android d'optimisation tout-en-un : nettoyage du stockage, état de la
mémoire, de la batterie et des applications, avec mise à jour automatique depuis
les GitHub Releases.

> Distribuée hors Play Store, sous forme d'APK signé publié par la CI.

## Fonctionnalités

| Carte | Ce qu'elle fait |
|---|---|
| 🧹 Nettoyage | Analyse puis vide le dossier *Téléchargements* et les caches de l'application |
| ⚡ Mémoire RAM | Affiche l'occupation réelle et demande au système de libérer les processus en cache |
| 🔋 Batterie | Niveau, température, santé, état de charge |
| 💾 Stockage | Espace utilisé / total, accès à l'écran système des téléchargements |
| 📱 Applications | Décompte applis utilisateur / système, accès au gestionnaire d'applications |
| ⬆ Mises à jour | Vérification quotidienne (WorkManager), téléchargement et installation assistée |

## Permissions et pourquoi

| Permission | Usage |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` (Android 11+) | Lire et vider le dossier *Téléchargements* partagé. Le *scoped storage* rend l'opération impossible autrement. Accordée par l'utilisateur depuis les Réglages. |
| `WRITE_EXTERNAL_STORAGE` (Android ≤ 10) | Même usage, en permission d'exécution classique. |
| `QUERY_ALL_PACKAGES` | Compter correctement les applications installées (Android 11+ filtre la liste sans elle). |
| `KILL_BACKGROUND_PROCESSES` | Bouton « Boost RAM ». |
| `REQUEST_INSTALL_PACKAGES` | Installer l'APK de mise à jour. |
| `POST_NOTIFICATIONS` | Prévenir qu'une mise à jour est prête. |
| `INTERNET` | Interroger l'API GitHub Releases. |

L'app fonctionne sans accès au stockage partagé : seuls les caches de
l'application sont alors nettoyables, et l'interface le signale.

## Mise à jour automatique

1. `UpdateManager` planifie une vérification quotidienne via **WorkManager**
   (contrainte : réseau disponible), désactivable depuis la carte « Mises à jour ».
2. `UpdateChecker` interroge `GET /repos/ClawFabriceH92/DroidClean/releases`.
   Les brouillons, les pré-versions et la release flottante `latest` sont ignorés :
   **seuls les tags `vX.Y.Z` déclenchent une mise à jour.**
3. Si une version supérieure existe, l'APK est téléchargé par `DownloadManager`
   (Wi-Fi uniquement), puis l'installation est proposée — directement si l'app est
   au premier plan, sinon par une notification (Android 10+ interdit de lancer
   l'installeur depuis l'arrière-plan).

## Build

```bash
./gradlew testDebugUnitTest   # tests unitaires
./gradlew lintRelease         # rapport lint
./gradlew assembleDebug       # APK de debug
```

La version est pilotée par une propriété Gradle et le `versionCode` en découle
(`1.2.3` → `10203`) :

```bash
./gradlew assembleRelease -PdroidcleanVersionName=1.2.3
```

## Publier une version

```bash
git tag v1.2.3 && git push origin v1.2.3
```

La CI construit l'APK signé, le nomme `droidclean-v1.2.3.apk` et crée la release
GitHub correspondante. Les installations existantes la détectent dans les 24 h.

Un push sur `main` publie en plus une release flottante `latest` (build de
développement, ignorée par la mise à jour automatique).

### Secrets requis

`DROIDCLEAN_KEYSTORE_B64`, `DROIDCLEAN_KEYSTORE_PASSWORD`, `DROIDCLEAN_KEY_ALIAS`,
`DROIDCLEAN_KEY_PASSWORD`. Sans eux, la CI produit un APK non signé : la mise à
jour automatique échoue alors, Android refusant de remplacer une app par un APK
d'une autre clé.

## Prérequis

- Android 8.0 (API 26) minimum, ciblé API 35
- JDK 17, Android SDK 35
