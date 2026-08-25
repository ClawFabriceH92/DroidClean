# DroidClean

Application Android d'optimisation du stockage : elle vous montre ce qu'elle
propose de supprimer **avant** de supprimer quoi que ce soit.

> Distribuée hors Play Store, sous forme d'APK signé publié par la CI.

## Principe

Une application de nettoyage n'a que deux façons d'être utile : libérer de
l'espace pour de bon, et dire la vérité sur ce qu'elle peut faire. DroidClean
s'y tient :

- **Rien n'est supprimé sans sélection explicite.** L'analyse liste les éléments
  nettoyables, groupés par catégorie ; vous cochez, vous confirmez.
- **Vos documents passent par la corbeille du système** (Android 11+), où ils
  restent récupérables une trentaine de jours. Les caches, qui se régénèrent,
  sont supprimés définitivement.
- **Pas de promesse d'accélération.** Depuis Android 5.1 une app ne voit plus les
  processus des autres ; « libérer la RAM » les fait relancer plus tard, pour
  plus cher. La carte Mémoire informe, elle n'agit pas.

## Fonctionnalités

| Écran | Ce qu'il fait |
|---|---|
| 🧹 Nettoyage | Analyse, liste et sélection fine : caches, résidus d'apps désinstallées, vignettes, `LOST.DIR`, dossiers vides, téléchargements. Filtres par ancienneté, taille et niveau de risque. |
| 📦 Poids des applications | APK + données + cache par application, date de dernière utilisation, désinstallation. |
| 🔎 Analyse de l'espace | Les 100 plus gros fichiers, et la détection des doublons (taille → empreinte partielle → SHA-256). |
| 💾 Stockage | Espace utilisé/total via `StorageStatsManager`, volumes secondaires, accès à l'écran système des téléchargements. |
| ⚡ Mémoire | Occupation réelle et seuil de mémoire faible. Informatif. |
| 🔋 Batterie | Niveau, température, santé, tension, capacité réelle, cycles (Android 14+), temps de charge restant. |
| ⬆ Mises à jour | Vérification quotidienne, notes de version, progression du téléchargement, contrôle de signature, installation assistée. |
| ⚙️ Maintenance | Nettoyage hebdomadaire automatique des caches, corbeille activable, écran Confidentialité. |

Plus une **tuile Réglages rapides** (espace libre, analyse en un geste) et un
**raccourci d'appui long** sur l'icône.

## Ce que la plateforme interdit, et pourquoi

Ces limites sont structurelles, pas des manques de l'application :

- **Les caches des autres applications** sont inaccessibles sans root depuis
  Android 7. Le remplacement honnête est l'écran *Poids des applications*, qui
  montre où l'espace est réellement passé.
- **`Android/data`** est illisible depuis Android 11, même avec « Accès à tous
  les fichiers ». Seul `Android/media` reste ouvert : c'est là que DroidClean
  cherche les caches d'applications et les résidus.
- **`Android/obb`** n'est jamais touché : ce sont des données de jeux, dont le
  re-téléchargement coûterait des gigaoctets.

## Permissions et pourquoi

| Permission | Usage |
|---|---|
| `MANAGE_EXTERNAL_STORAGE` (Android 11+) | Lire et supprimer les fichiers que vous sélectionnez. Le stockage cloisonné rend l'opération impossible autrement. Accordée depuis les Réglages. |
| `WRITE_EXTERNAL_STORAGE` (Android ≤ 10) | Même usage, en permission d'exécution classique. |
| `PACKAGE_USAGE_STATS` | Poids réel des applications (`StorageStatsManager`) et date de dernière utilisation. Accordée depuis Réglages ▸ Accès spécial. |
| `QUERY_ALL_PACKAGES` | Compter les applications et repérer les résidus des applications désinstallées. |
| `REQUEST_INSTALL_PACKAGES` | Installer l'APK de mise à jour. |
| `POST_NOTIFICATIONS` | Prévenir qu'une mise à jour est prête ou qu'un ménage a eu lieu. |
| `INTERNET` | Interroger l'API GitHub Releases. |

L'application fonctionne sans accès au stockage partagé : seuls ses propres
caches sont alors nettoyables, et un bandeau le dit explicitement.

Aucune donnée personnelle ne quitte l'appareil. La seule connexion sortante
interroge l'API publique de GitHub. Les rapports de plantage restent locaux
jusqu'à ce que vous décidiez de les partager.

## Architecture

La logique de décision — quoi proposer, quoi compter, quoi refuser de toucher —
est en **Kotlin pur, sans dépendance Android**, donc testable sans émulateur :

```
clean/JunkScanner.kt     ce qui est proposé au nettoyage, à partir de simples File
clean/FileDeleter.kt     la suppression et sa comptabilité exacte
clean/FileTree.kt        parcours itératif, liens symboliques jamais suivis
analyze/StorageAnalyzer  plus gros fichiers, doublons en trois passes
history/CleanStats.kt    agrégation de l'historique
util/Formats.kt          tailles, pourcentages, durées
```

Les objets `Cleaner`, `AppStorage`, `StorageInfo`, `BatteryInfo` sont de fines
façades Android au-dessus. C'est ce qui permet aux 49 tests unitaires de couvrir
le code le plus dangereux de l'application.

## Build

```bash
./gradlew testDebugUnitTest   # tests unitaires
./gradlew lintRelease         # lint (les erreurs bloquent)
./gradlew assembleDebug       # APK de debug
python3 tools/check-strings.py  # cohérence des traductions
```

La version est pilotée par une propriété Gradle et le `versionCode` en découle
(`1.2.3` → `10203`) :

```bash
./gradlew assembleRelease -PdroidcleanVersionName=1.2.3
```

`minor` et `patch` doivent rester sous 100, sans quoi le `versionCode` cesserait
d'être strictement croissant — le build échoue explicitement dans ce cas.

## Publier une version

1. Ajouter la section correspondante dans `CHANGELOG.md` (elle devient les notes
   de release GitHub, que l'application affiche avant de proposer la mise à jour).
2. `git tag v1.2.3 && git push origin v1.2.3`

La CI construit l'APK signé, le nomme `droidclean-v1.2.3.apk` et crée la release
GitHub correspondante. Les installations existantes la détectent dans les 24 h.

Un push sur `main` publie en plus une release flottante `latest` (build de
développement, ignorée par la mise à jour automatique).

### Secrets requis

`DROIDCLEAN_KEYSTORE_B64`, `DROIDCLEAN_KEYSTORE_PASSWORD`, `DROIDCLEAN_KEY_ALIAS`,
`DROIDCLEAN_KEY_PASSWORD`. Sans eux, la CI produit un APK non signé : la mise à
jour automatique le détecte désormais et l'annonce clairement, au lieu de laisser
Android échouer sans explication.

## Prérequis

- Android 8.0 (API 26) minimum, ciblé API 35
- JDK 17, Android SDK 35

## Licence

MIT — voir [LICENSE](LICENSE).
