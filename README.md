# OfflineTune

Lecteur et téléchargeur de musique hors ligne pour Android, sans publicité ni traqueur.

- Collez (ou partagez depuis l'app d'origine) un lien **Spotify, Deezer, YouTube, YouTube Music ou SoundCloud**, ou tapez « artiste titre ».
- Vérifiez et corrigez la **fiche du morceau** (titre, artiste, album, année, pochette, paroles, source audio) avant l'enregistrement.
- Chaque morceau est rangé dans son propre dossier :

```
<Dossier choisi>/
└── Artiste - Titre/
    ├── Artiste - Titre.opus   (audio + tags + pochette + paroles intégrés)
    ├── Artiste - Titre.lrc    (paroles synchronisées)
    └── cover.jpg
```

- Le lecteur lit les fichiers du téléphone : égaliseur 10 bandes, préréglages, basses/aigus, balance, mono, limiteur, fondu enchaîné, fondu à la pause, paroles synchronisées.

## Installer

1. Copiez `OfflineTune.apk` sur le téléphone (ou installez-le par USB : `adb install -r OfflineTune.apk`).
2. Ouvrez-le et autorisez l'installation depuis cette source si Android le demande.
3. Au premier lancement, choisissez le dossier de musique (onglet Bibliothèque).

Chaque nouvelle version s'installe **par-dessus** l'ancienne sans perdre vos réglages ni vos préréglages,
car l'APK est toujours signé avec la même clé (`app/offlinetune.jks`). **Ne perdez pas ce fichier** :
sans lui, il faudrait désinstaller l'app pour installer une nouvelle version.

## Recompiler

Prérequis : JDK 17 et le SDK Android (installé par Android Studio), variable `ANDROID_HOME` définie
(ou un fichier `local.properties` contenant `sdk.dir=...`).

```bash
./build.sh
```

Le script lance les tests du cœur (sans réseau), compile l'APK et le copie à la racine : `OfflineTune.apk`.

Tests complets du cœur, avec de vrais appels réseau (Spotify, Deezer, YouTube, SoundCloud, LRCLIB) :

```bash
./gradlew :core:test
```

## Quand YouTube change

YouTube modifie régulièrement son site. Si l'app affiche « YouTube a changé, mise à jour de l'app nécessaire » :

1. Regardez la dernière version de NewPipeExtractor : <https://github.com/TeamNewPipe/NewPipeExtractor/releases>
2. Remplacez la valeur de `newpipe` dans `gradle/libs.versions.toml` (ex. `newpipe = "v0.26.6"`).
3. Lancez `./build.sh` puis réinstallez `OfflineTune.apk`.

## Bon à savoir

- **Sources audio** : l'audio de Spotify et Deezer est protégé (DRM) et n'est jamais récupéré chez eux.
  L'app lit leurs informations (titre, album, pochette…) puis cherche le même morceau sur YouTube.
  La fiche montre la source retenue ; vous pouvez en choisir une autre ou coller votre propre lien.
- **Qualité** : le meilleur flux de la source, copié sans réencodage (YouTube : Opus ~130–160 kb/s ;
  SoundCloud : AAC 160 kb/s en `.m4a`).
- **YouTube Music** n'est pas disponible dans tous les pays ; là où il ne l'est pas, la recherche passe
  par YouTube (vidéos officielles et chaînes « Topic »).
- **Extraits SoundCloud** (~30 s, morceaux Go+) : détectés, jamais enregistrés en silence ; la fiche propose la version YouTube.
- La page d'intégration Spotify n'est pas une API officielle : si elle change, l'app se rabat sur une recherche par titre.

## Organisation du code

- `core/` — module Kotlin pur, testé sur PC : liens, métadonnées (Deezer, Spotify, NewPipe), correspondance,
  téléchargement, conversion WebM → Ogg Opus sans réencodage, tags (Opus, MP3, M4A), paroles, traitement du son.
- `app/` — l'application Android (Jetpack Compose, Media3).

Licence : GPL-3.0 (voir `LICENSE`), comme NewPipeExtractor. Usage personnel.
