# Journal des modifications

Le format suit [Keep a Changelog](https://keepachangelog.com/fr/1.1.0/).
Les sections `##` de ce fichier deviennent les notes de release GitHub, que
l'application affiche avant de proposer une mise à jour.

## 1.3.0

### Autonomie réelle de la batterie

Android n'expose que le niveau instantané : ni l'autonomie d'une charge, ni le
temps restant, ni la fréquence des recharges. DroidClean mesure désormais les
trois lui-même, à partir d'un historique de relevés local.

- **Temps restant avant la prochaine recharge**, estimé sur le rythme de
  décharge des dernières heures — pas sur une moyenne au long cours, qui ne
  dirait rien de l'usage du moment.
- **Autonomie moyenne** d'une charge complète, extrapolée à 100 %. L'estimateur
  est pondéré par le pourcentage consommé : une décharge de 80 % sur deux jours
  pèse plus lourd qu'une de 3 % sur vingt minutes.
- **Fréquence de recharge** : tous les combien l'appareil est rebranché.
- **Temps de charge restant** : l'estimation du système quand elle existe, sinon
  le rythme de charge observé — beaucoup d'appareils renvoient -1.

Tant qu'il n'y a pas assez de données, la carte annonce « mesure en cours »
plutôt qu'un chiffre extrapolé du vide.

Trois pièges que la mesure évite, et qui fausseraient les moyennes :

- **Appareil éteint.** Chaque relevé note aussi l'horloge monotone, qui n'avance
  pas hors tension : une nuit téléphone éteint ne compte plus comme de
  l'autonomie.
- **Redémarrage.** L'horloge monotone repart de zéro, la période est coupée.
- **Charge non observée.** Un niveau qui remonte pendant une décharge trahit une
  recharge passée entre deux relevés ; la période est coupée là aussi.

Un trou de relevés dû au Doze, en revanche, ne coupe rien : l'appareil est resté
allumé, la mesure est bonne.

**Comment c'est relevé** : une tâche toutes les 30 minutes, un relevé à chaque
branchement et débranchement, un autre à l'ouverture de l'application. Rien ne
quitte l'appareil, l'historique est purgé à 30 jours, et le suivi se coupe
depuis la carte Maintenance.

La durée de charge restante quitte la ligne de détails pour rejoindre le bloc de
prévisions, où elle ne fait plus doublon.

### Technique

- `BatteryStats` est en Kotlin pur — c'est un calcul statistique, il n'a pas à
  être vérifié sur un téléphone : **27 tests** couvrent le découpage en cycles,
  les trois détecteurs de discontinuité, la pondération et les prévisions.
- L'historique est un fichier CSV dans `filesDir` plutôt que des
  SharedPreferences, qui sont chargées en entier en mémoire.

## 1.2.0

### Le nettoyage ne supprime plus rien à l'aveugle

- **Écran de sélection** : l'analyse liste ce qui peut partir, groupé par
  catégorie, avec la taille et l'ancienneté de chaque élément. Vous cochez ce
  que vous voulez supprimer. Auparavant, « Nettoyer » vidait le dossier
  *Téléchargements* en entier — factures et documents compris.
- **Corbeille du système** (Android 11+) : vos documents restent récupérables
  environ 30 jours. Les caches, eux, sont supprimés définitivement puisqu'ils se
  régénèrent. Option désactivable dans *Maintenance*.
- **Filtres** : plus de 30 jours, plus de 10 Mo, catégories sans risque.
- **Nouvelles cibles** : caches d'applications dans `Android/media`, résidus
  d'applications désinstallées, vignettes, `LOST.DIR`, dossiers vides.
- L'index multimédia est prévenu des suppressions : plus d'entrées fantômes
  dans l'écran *Téléchargements* du système.

### Nouveaux écrans

- **Poids des applications** : APK + données + cache par application, date de
  dernière utilisation, désinstallation en deux gestes. C'est le vrai levier de
  récupération d'espace ; les caches des autres applications sont hors de portée
  sans root depuis Android 7.
- **Analyse de l'espace** : les 100 plus gros fichiers, et la détection des
  doublons (taille, puis empreinte partielle, puis SHA-256 complet).

### Corrections

- **Mode sombre illisible.** Le thème étant `DayNight`, les cartes passaient en
  surface sombre alors que les couleurs de texte restaient codées pour le mode
  clair : texte gris foncé sur fond noir. Palette `values-night` complète,
  contrastes vérifiés.
- **Bord-à-bord Android 15.** Avec `targetSdk 35`, Android 15 impose le
  bord-à-bord et ignore `android:statusBarColor` : le titre passait sous la
  barre d'état et le pied de page sous la barre de navigation.
- **Tâches planifiées figées.** `ExistingPeriodicWorkPolicy.KEEP` empêchait
  toute évolution future de l'intervalle ou des contraintes de s'appliquer aux
  installations existantes. Remplacé par `UPDATE`.
- **Comptabilité de nettoyage faussée.** La taille était mesurée sur
  l'arborescence puis `delete()` testé sur sa racine : un seul fichier verrouillé
  annulait tout le gain affiché. Chaque fichier est désormais compté au moment
  où il est réellement supprimé.
- Les liens symboliques ne sont plus jamais suivis, ni à l'analyse ni à la
  suppression.
- `StatFs` remplacé par `StorageStatsManager` : le total affiché correspond
  enfin à celui des Réglages Android.
- Le verrou d'orientation portrait a été retiré (ignoré sur grand écran depuis
  Android 16, et pénalisant sur tablette et pliable).

### Mises à jour

- **Les notes de version sont affichées** avant le téléchargement. Elles étaient
  récupérées depuis l'API GitHub… puis jetées.
- **Progression du téléchargement** visible dans la carte, avec un état explicite
  (inactif, vérification, disponible, téléchargement, prêt à installer).
- **Contrôle de signature** de l'APK avant d'ouvrir l'installeur : message clair
  au lieu d'un échec opaque d'Android.
- « Plus tard » mémorise la version ignorée ; la vérification manuelle la
  reproposera.
- L'APK est supprimé une fois installé ou périmé.

### Ajouts

- **Tuile Réglages rapides** : espace libre en un coup d'œil, analyse en un
  geste. Elle ne supprime rien elle-même.
- **Raccourci d'appui long** sur l'icône : analyser directement.
- **Nettoyage hebdomadaire automatique** des caches, appareil au repos, avec
  notification du volume libéré. Ne touche jamais aux documents.
- **Historique** : « X libérés ce mois-ci ».
- **Batterie** : tension, capacité réelle en mAh, nombre de cycles (Android 14+),
  temps de charge restant estimé.
- **Rapport de plantage local** : l'app étant distribuée hors Play Store, un
  plantage était jusqu'ici totalement invisible. Le rapport reste sur l'appareil
  et n'est partagé que si vous le décidez.
- **Écran Confidentialité** : ce que fait chaque permission, et ce qui sort de
  l'appareil (uniquement l'appel à l'API GitHub).
- **Traduction anglaise** complète, plus le choix de la langue par application
  (Android 13+).

### Retiré

- **Le bouton « Boost RAM ».** Depuis Android 5.1, une application ne voit plus
  les processus des autres : tuer les processus en cache les fait relancer plus
  tard, ce qui coûte plus de batterie que cela n'en économise. La carte Mémoire
  informe désormais au lieu de promettre. La permission
  `KILL_BACKGROUND_PROCESSES` a été supprimée.

### Technique

- ViewBinding partout : un identifiant erroné devient une erreur de compilation.
- La logique de décision (quoi proposer, quoi compter) est en Kotlin pur, sans
  dépendance Android, et **couverte par 49 tests unitaires** — dont le code de
  suppression, qui n'en avait aucun.
- Catalogue de versions Gradle, Dependabot, `.editorconfig`.
- Les erreurs lint bloquent désormais la CI.
- Contrôle automatique de cohérence des traductions (`tools/check-strings.py`) :
  lint signale une chaîne manquante, mais pas un `%1$s` devenu `%1$d`, qui
  plante à l'exécution dans cette langue seulement.
- Garde-fou sur le `versionCode` : `minor` et `patch` doivent rester sous 100,
  faute de quoi il cesserait d'être strictement croissant.

## 1.1.0

- Corrections de fond : nettoyage, interface, mises à jour.
- Durcissement du build.

## 1.0.1

- Mise à jour automatique depuis les GitHub Releases.
- Build release signé par la CI.

## 1.0.0

- Première version : nettoyage, mémoire, batterie, stockage, applications.
