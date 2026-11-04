# OfflineTune — lecteur et téléchargeur de musique hors ligne (Android)

Document de conception, validé le 2026-11-04.

## 1. Intention

Aujourd'hui l'utilisateur récupère ses musiques (Spotify, Deezer, YouTube, SoundCloud…) via des sites de téléchargement remplis de publicités.
Il veut **une seule app Android, sans pub**, qui :

1. télécharge un morceau à partir d'un lien (ou d'une recherche), dans la meilleure qualité disponible ;
2. lui montre la fiche du morceau **avant** l'enregistrement, et le laisse la modifier (titre, artiste, pochette, année…) ;
3. range chaque morceau dans **son propre dossier** avec le fichier audio tagué, la pochette et les paroles `.lrc` ;
4. sert ensuite de **lecteur hors ligne de qualité, façon Poweramp** : égaliseur, balance stéréo, fondu enchaîné, etc.

## 2. Objectifs

- Coller un lien Spotify / Deezer / YouTube / YouTube Music / SoundCloud, ou partager le lien depuis l'app d'origine (menu « Partager » d'Android) → fiche du morceau.
- Recherche texte (« artiste titre ») → liste de résultats → fiche du morceau.
- Fiche modifiable : titre, artiste, album, artiste de l'album, année, n° de piste, genre, pochette (remplaçable depuis la galerie).
- Téléchargement de la meilleure qualité audio de la source, **sans réencodage**.
- Paroles : recherche automatique des paroles synchronisées (LRCLIB) et enregistrement en `.lrc`.
- Bibliothèque : l'utilisateur choisit le dossier de musique sur son téléphone ; liste par titres / albums / artistes, recherche.
- Lecture en arrière-plan avec notification et contrôles sur l'écran verrouillé.
- Réglages audio inspirés de Poweramp (détail §6).

## 3. Non-objectifs (v1)

- Pas de contournement des DRM : l'audio Spotify/Deezer n'est **pas** récupéré depuis Spotify/Deezer (voir §4).
- Pas de FLAC / lossless (aucune source légale accessible sans DRM ne le fournit).
- Pas de connexion à un compte (Spotify, Deezer, Google).
- Pas de téléchargement de playlists ou d'albums entiers en un clic (un morceau à la fois) — candidat v2.
- Pas de modification des tags d'un morceau **après** son téléchargement — candidat v2.
- Pas d'écoute en streaming en ligne : le lecteur ne lit que des fichiers locaux.

## 4. Anti-objectifs (ce qui serait un échec même si « ça marche »)

- Toute publicité, tout traqueur, toute analytics.
- Un réencodage qui dégrade la qualité.
- Des infos fausses enregistrées sans que l'utilisateur ait pu les voir et les corriger.
- Des fichiers que les autres lecteurs (Poweramp, VLC…) ne savent pas lire, ou dont ils ne voient pas les tags.

## 5. Sources et qualité (décidé avec l'utilisateur)

| Lien collé | Infos (métadonnées) | Audio | Paroles |
|---|---|---|---|
| Spotify | page publique `open.spotify.com/embed/track/<id>` + enrichissement API publique Deezer (album, année, n° piste, pochette HD, ISRC) | YouTube Music (meilleur résultat correspondant) | LRCLIB |
| Deezer | API publique `api.deezer.com/track/<id>` (+ `/album/<id>` pour le genre) | YouTube Music (meilleur résultat correspondant) | LRCLIB |
| YouTube / YouTube Music | NewPipeExtractor (titre, chaîne, miniature, date) + enrichissement Deezer si correspondance sûre | la vidéo elle-même | LRCLIB |
| SoundCloud | NewPipeExtractor (titre, artiste, artwork, date) | le morceau SoundCloud lui-même | LRCLIB |

- **Meilleure qualité** = le meilleur flux audio fourni par la source, copié tel quel :
  YouTube → Opus (itag 251, ~160 kb/s) en priorité, sinon AAC/M4A le plus haut débit ;
  SoundCloud → le flux de plus haut débit proposé.
- **Correspondance YouTube Music** (Spotify/Deezer) : recherche « artiste titre » dans les *chansons* YouTube Music,
  score = similarité titre/artiste + écart de durée (rejet si > 5 s quand une durée de référence existe).
  La fiche affiche la source retenue et permet d'en choisir une autre parmi les 5 meilleures.
- La fiche affiche la qualité qui sera obtenue (ex. « Opus · 160 kb/s »).
- **Recherche texte** *(choix du développeur)* : deux onglets de résultats.
  « Titres » interroge le catalogue Deezer (infos propres, pochette HD) puis suit le même chemin qu'un lien Deezer ;
  « YouTube » interroge YouTube Music / YouTube (remixes, live, morceaux absents de Deezer) puis suit le chemin d'un lien YouTube.
- **Quand la source audio ne convient pas** *(choix du développeur)* :
  - Spotify/Deezer sans correspondance à moins de 5 s : la fiche affiche un avertissement et les candidats écartés ; l'utilisateur peut en choisir un ou **coller son propre lien YouTube/SoundCloud comme source**. Rien n'est téléchargé sans source choisie.
  - SoundCloud qui ne fournit qu'un extrait (~30 s, morceaux Go+) : détecté (durée du flux ≪ durée annoncée), avertissement, et proposition d'utiliser la correspondance YouTube Music à la place. Jamais d'extrait enregistré en silence.

## 6. Le lecteur

### Bibliothèque
- Dossier racine choisi par l'utilisateur (sélecteur de dossier Android, permission persistante).
- Scan récursif des fichiers audio (`.opus .ogg .mp3 .m4a .flac .wav .aac`), lecture des tags, détection d'un `.lrc` de même nom et d'un `cover.jpg` dans le dossier.
- Résultat mis en cache pour un démarrage rapide ; bouton « Actualiser ».
- Onglets Titres / Albums / Artistes + recherche.

### Lecture
- Mini-lecteur au-dessus de la barre de navigation ; écran plein avec grande pochette, barre de progression, précédent / lecture / suivant, aléatoire, répétition (aucune / tout / un), file d'attente.
- Paroles synchronisées qui défilent et surlignent la ligne en cours (paroles non synchronisées affichées en texte simple).
- Lecture en arrière-plan (service média), notification, écran verrouillé, boutons casque Bluetooth.

### Son (inspiré de Poweramp)
Traitement fait par l'app elle-même (indépendant du téléphone) :
- **Égaliseur 10 bandes** (31 Hz → 16 kHz, ±12 dB) + **préampli**.
- Préréglages : Plat, Rock, Pop, Jazz, Classique, Électro, Hip-Hop, Bass boost, Voix, Acoustique + **préréglages personnels** enregistrables.
- **Basses** et **aigus** séparés.
- **Balance stéréo** gauche/droite, **mode mono**.
- Protection contre la saturation (limiteur doux).
- **Fondu enchaîné** activable, durée réglable 0–12 s, option « aussi lors d'un saut manuel ».
- **Fondu à la pause / reprise** (court, activable).
- Lecture sans blanc entre morceaux quand le fondu est désactivé.

## 7. Résultat d'un téléchargement

```
<Dossier de téléchargement choisi>/
└── <Artiste> - <Titre>/
    ├── <Artiste> - <Titre>.opus   ← audio + tags intégrés (titre, artiste, album, artiste album,
    │                                année, n° piste, genre, pochette, paroles)
    ├── <Artiste> - <Titre>.lrc    ← paroles (même nom que l'audio : Poweramp/autres les trouvent aussi)
    └── cover.jpg                  ← pochette
```

- Les noms de fichiers sont nettoyés des caractères interdits.
- Le `.lrc` contient les en-têtes `[ti:] [ar:] [al:] [length:]` puis les lignes horodatées.
  S'il n'existe que des paroles non synchronisées, le `.lrc` contient le texte sans horodatage.
  Sans paroles trouvées : pas de `.lrc`, la fiche le signale.
- Les paroles relèvent aussi de l'anti-objectif « rien de faux sans l'avoir vu » *(choix du développeur)* :
  la fiche montre un **aperçu des paroles** (premières lignes, synchronisées ou non), un bouton « Rechercher à nouveau »
  (relance avec le titre/artiste modifiés) et un interrupteur « Ne pas inclure les paroles ».
- Opus est enregistré dans un conteneur **Ogg** (`.opus`) réécrit sans réencodage depuis le WebM de YouTube : c'est le format qui permet des tags et une pochette intégrés lisibles partout.
  MP3 (SoundCloud) → tags ID3v2.3. Repli M4A → `.m4a` (tags dans les fichiers à côté si l'intégration n'est pas possible).

## 8. Écrans

Navigation du bas : **Bibliothèque · Télécharger · Son · Réglages** + mini-lecteur.

- **Télécharger** : champ « Coller un lien ou rechercher », bouton Coller, puce indiquant la plateforme détectée → résultats de recherche le cas échéant → **fiche** (pochette touchable, champs, source audio choisie, qualité, état des paroles) → bouton Télécharger → liste des téléchargements avec progression et erreurs lisibles.
- **Son** : égaliseur, préréglages, préampli, basses/aigus, balance, mono.
- **Réglages** : dossier de téléchargement, dossier de bibliothèque, fondu enchaîné (durée, saut manuel), fondu pause/reprise, paroles automatiques on/off.
- Style : thème sombre, couleurs d'accent tirées de la pochette en cours.

## 9. Choix techniques (choix du développeur, accepté par l'utilisateur)

- Kotlin, Jetpack Compose (Material 3), Android 8.0+ (minSdk 26).
- Lecture : AndroidX Media3 (ExoPlayer + MediaSessionService).
- Extraction YouTube / YouTube Music / SoundCloud : **NewPipeExtractor** (open source, GPL-3.0 → l'app est GPL-3.0, usage personnel).
- Métadonnées : API publique Deezer, page d'intégration publique Spotify ; paroles : **LRCLIB** (API libre, sans clé).
- Le cœur (liens, métadonnées, correspondance, téléchargement, réécriture Ogg, tags, paroles, calculs DSP) est un **module Kotlin pur** testable sur PC, séparé de l'app Android.
- Aucune dépendance publicitaire / analytics.

## 10. Vérification

- Tests automatiques du cœur **sur le PC, avec de vrais appels réseau** : un lien de chaque plateforme → fiche correcte ; correspondance YouTube Music ; téléchargement réel ; fichier `.opus` valide (en-têtes, tags, pochette, durée) ; `.lrc` récupéré.
- Tests unitaires du traitement audio (égaliseur, balance, mono, limiteur).
- Compilation de l'APK (`assembleDebug`) sans erreur.
- Test final par l'utilisateur sur son téléphone (installation de l'APK).

### Livraison et mises à jour *(choix du développeur)*
- L'APK est signé avec une **clé fixe conservée dans le projet** : chaque nouvelle compilation s'installe par-dessus l'ancienne sans perdre réglages ni préréglages.
- Un script `build.sh` + un `README` en français expliquent comment recompiler (ex. après mise à jour de NewPipeExtractor).
- Si l'extraction échoue, l'app affiche un message clair (« YouTube a changé, mise à jour de l'app nécessaire ») au lieu d'une erreur technique.
- Le dossier de téléchargement est par défaut le dossier de bibliothèque ; un morceau téléchargé apparaît tout de suite dans la bibliothèque.

## 11. Risques connus

- YouTube modifie régulièrement son site : si l'extraction casse, il faut mettre à jour NewPipeExtractor et recompiler.
- La page d'intégration Spotify n'est pas une API officielle : si son format change, repli sur la recherche par titre.
- La correspondance YouTube Music peut se tromper sur des titres rares : d'où l'affichage et le choix de la source dans la fiche.

## 12. Laissé au développeur

Architecture interne, structure des modules, bibliothèques secondaires, format du cache, détails visuels,
algorithme exact de score de correspondance, implémentation du fondu enchaîné et du DSP.
