# Musique Pr (v2 d'OfflineTune) — recherche multi-sources, listes de lecture, nouvelle interface

Document de conception, 2026-11-04. Il complète `files.md` (v1), qui reste valable pour tout ce qui n'est pas modifié ici.
Maquettes validées par l'utilisateur : `docs/superpowers/specs/mockups/` (`lecture.html` option **A**, `recherche.html`, `icone.html` option **Corail**).

## 1. Intention

L'utilisateur veut que l'app devienne son lecteur principal, avec trois améliorations :

1. **Chercher partout à la fois.** Une recherche doit renvoyer des chansons venant de plusieurs plateformes : Amazon Music, Apple Music, Deezer, SoundCloud, Spotify, Tidal, YouTube Music. Des filtres permettent de n'en garder qu'une.
2. **Ses propres listes de lecture**, visibles aussi dans les autres lecteurs.
3. **Une interface plus belle :** style verre (flou, transparence) combiné à Material, et un nouveau nom, « Musique Pr », avec une nouvelle icône.

## 2. Décisions prises avec l'utilisateur

- **Qobuz est retiré** (décision de l'utilisateur) : il n'existe aucun moyen public de chercher dans son catalogue, et l'utilisateur refuse de réutiliser l'identifiant du lecteur web. Les maquettes montrent encore Qobuz : l'ignorer partout (pas de puce, pas de pastille, pas de ligne dans les sources). Il reste 7 plateformes.
- **Pas de contournement des DRM** (rappel de la v1, confirmé : « oui »). L'audio de Spotify, Deezer, Apple Music, Amazon Music et Tidal n'est **jamais** téléchargé.
  - Ces plateformes servent à la recherche, aux infos et à la pochette.
  - La fiche affiche leur qualité, marquée « écoute dans leur appli », avec un bouton « Ouvrir ↗ ».
  - Seuls YouTube / YouTube Music et SoundCloud sont **téléchargeables**, comme en v1.
- **Recherche (maquette `recherche.html`, validée) :**
  - Des puces de filtre en haut : **Tout** puis une par plateforme.
  - Chaque résultat affiche des pastilles indiquant les plateformes où la chanson existe.
  - **Sans filtre (Tout) :** les résultats de toutes les plateformes sont mélangés. Une même chanson trouvée à plusieurs endroits n'apparaît qu'une fois, avec plusieurs pastilles.
  - **Avec un filtre :** seulement les chansons trouvées sur cette plateforme.
  - **Toucher une chanson** ouvre la liste de ses sources en deux groupes :
    1. **Téléchargeable** : YouTube Music et SoundCloud, avec la qualité réelle du fichier obtenu (ex. « Opus · 160 kb/s », « AAC · 160 kb/s ») et l'indication paroles ✓ / ✗.
    2. **Disponible aussi (écoute dans leur appli)** : les autres plateformes, avec la qualité qu'elles proposent pour ce titre (ex. « FLAC 24 bit / 96 kHz » pour Tidal) et « Ouvrir ↗ ».
  - **Choisir une source téléchargeable** mène à la fiche de la v1 : mêmes champs modifiables, même `.lrc`, même dossier, même aperçu des paroles. Rien ne change dans ce que la fiche enregistre.
- **Listes de lecture :**
  - On peut ajouter un titre à une liste depuis trois endroits : la bibliothèque (appui long sur un titre), l'écran de lecture (« + Playlist ») et la fiche au moment du téléchargement (l'ajout se fait quand le téléchargement réussit).
  - Les listes sont **aussi des fichiers `.m3u8`** dans le dossier de musique, lisibles par Poweramp et VLC.
- **Écran de lecture : direction A** (maquette `lecture.html`).
  - Tout le fond reprend les couleurs floutées de la pochette, avec une grande pochette.
  - Titre et artiste, barre de progression, puce de qualité (« Opus · 160 kb/s »).
  - Commandes : aléatoire, précédent, lecture/pause en gros bouton blanc, suivant, répétition.
  - En bas, une **barre en verre** : Paroles · File · Son · + Playlist.
- **Nom et icône :**
  - Nom affiché : **« Musique Pr »** (exactement).
  - Icône **Corail** : carré arrondi, dégradé rose-rouge (#ff6a88 → #ff2d55 → #c2185b), double croche blanche. C'est un dessin original : ne pas reproduire l'icône d'Apple Music.
- **Style général :** le style verre + Material s'applique aussi aux autres menus.
  - Barre de navigation flottante en verre, mini-lecteur en verre, puces de filtre en verre.
  - Écran Télécharger conforme à la maquette.
  - Le thème reste sombre, et l'accent reste tiré de la pochette en cours.

## 3. Objectifs

1. Recherche fédérée sur les 7 plateformes, avec filtres, regroupement par chanson, pastilles et liste des sources par chanson (§2).
2. Qualité affichée par source : réelle pour les sources téléchargeables, telle que proposée par la plateforme pour les autres.
3. Listes de lecture :
   - créer, renommer, supprimer ;
   - ajouter et retirer des titres, réordonner ;
   - lire une liste (dans l'ordre ou en aléatoire) ;
   - les trois points d'ajout de §2 ;
   - fichiers `.m3u8` synchronisés.
4. Nouvelle interface verre + Material sur tous les écrans, et l'écran de lecture A.
5. Renommage « Musique Pr » et icône Corail. **L'identifiant Android `fr.offlinetune` et la clé de signature ne changent pas**, pour que la mise à jour s'installe par-dessus et garde les réglages, les préréglages et la bibliothèque.

## 4. Non-objectifs

- Télécharger l'audio (FLAC ou autre) de Spotify, Deezer, Apple Music, Amazon Music, Tidal ou Qobuz. Aucun outil de déchiffrement, aucun service tiers de « ripping », aucune clé ou jeton récupéré pour obtenir leur audio.
- Se connecter à un compte (aucune des 8 plateformes).
- Importer des listes depuis Spotify, Deezer ou d'autres services, et télécharger une liste ou un album entier d'un coup (candidat v3).
- Écouter en streaming.

## 5. Anti-objectifs (ce qui serait un échec même si « ça marche »)

- Montrer une plateforme payante comme « téléchargeable », ou laisser croire qu'on obtiendra son FLAC.
- Copier l'icône, le logo ou la charte d'Apple Music, ou d'une autre marque. Pas de logos officiels de plateformes : seulement des pastilles de couleur avec une initiale.
- Perdre les réglages, préréglages, listes ou la bibliothèque à la mise à jour.
- Des fichiers `.m3u8` que Poweramp ou VLC ne savent pas lire.
- Une recherche qui reste vide ou bloquée parce qu'une plateforme ne répond pas. Chaque plateforme est indépendante : celles qui échouent sont simplement absentes, et la puce filtre correspondante l'indique (« indisponible »).

## 6. Contraintes et faits connus

- **Base :** la v1 telle que livrée (branche `feat/offlinetune`). Le module `:core` (Kotlin pur, tests sur PC avec vrais appels réseau) accueille la recherche fédérée et les `.m3u8`. Le module `:app` (Compose, Media3) accueille l'interface.
- **Pays de l'utilisateur :** YouTube Music ne répond pas (« aucun résultat ») depuis sa connexion. La v1 se rabat déjà sur la recherche YouTube. La source téléchargeable s'appelle « YouTube Music » quand elle vient de YouTube Music, et « YouTube » sinon.
- **SoundCloud :** AAC 160 kb/s, converti en MP4 non fragmenté (v1).
- **Moyens de recherche connus (l'agrégation et le regroupement sont laissés au développeur) :**
  - Deezer : API publique sans clé (déjà utilisée).
  - Apple Music : API publique iTunes Search, sans clé.
  - YouTube Music et SoundCloud : NewPipeExtractor (déjà utilisé).
  - Spotify : page d'intégration publique (déjà utilisée) ; pour la recherche, au développeur de trouver un moyen sans compte.
  - Tidal, Amazon Music : pas d'API de recherche publique simple. Le service de correspondance song.link / Odesli (sans clé, environ 10 requêtes par minute) donne, à partir d'un lien connu, les liens de la même chanson sur Spotify, Apple Music, Amazon Music, Deezer, Tidal, YouTube Music et SoundCloud.
- **Effet verre :** le vrai flou d'arrière-plan n'existe qu'à partir d'Android 12 (le S26 Ultra de l'utilisateur est sous Android API 37). En dessous, un fond translucide sans flou est acceptable.
- **Langue et licence :** toute l'interface est en français, licence GPL-3.0, sans publicité ni traqueur.

## 7. Laissé au développeur (choix du développeur, à signaler dans le plan)

- **Fournisseurs de recherche :** comment interroger chaque plateforme. Si une plateforme n'a aucun moyen fiable sans compte, ses résultats viennent des correspondances (Odesli), et le filtre de cette plateforme peut être plus lent et montrer moins de résultats. *Choix du développeur, accepté implicitement par l'approche §2.*
- **Regroupement :** comment reconnaître qu'il s'agit de la même chanson (titre/artiste normalisés comme dans `Matcher`, durée à ±3 s, ISRC quand il existe).
- **Qualité par plateforme :** la valeur par titre quand l'API la donne, sinon le niveau maximal de la plateforme avec la mention « selon abonnement ».
- **Emplacement des `.m3u8` :** dans un sous-dossier `Playlists/` du dossier de musique, avec des chemins relatifs. Encodage UTF-8, en-tête `#EXTM3U`, une ligne `#EXTINF:durée,Artiste - Titre` par titre. Les `.m3u8` déjà présents dans le dossier sont lus comme des listes.
- **Ordre des puces et choix par défaut des filtres.**
- **Architecture interne, cache des recherches, détails visuels** au-delà des maquettes.

### Précisions après relecture (choix du développeur, à vérifier par l'utilisateur)

- **Chanson trouvée seulement sur des plateformes payantes :** comme en v1, l'app cherche la même chanson sur YouTube (Music) avec `Matcher`, en rejetant tout écart de plus de 5 s. Si une correspondance existe, elle apparaît dans « Téléchargeable » comme « Correspondance YouTube Music » avec sa qualité. Sinon, le groupe affiche « Aucune version téléchargeable trouvée — collez un lien YouTube ou SoundCloud ».
- **Les fichiers `.m3u8` font foi.** L'app relit les listes du dossier à chaque actualisation, y compris celles créées par Poweramp ou VLC, et réécrit le fichier là où il se trouve quand on modifie une liste. Supprimer une liste supprime son fichier, après confirmation.
- **Titre supprimé du disque :** la ligne reste dans la liste, grisée, et elle est sautée à la lecture. Le fichier `.m3u8` n'est jamais nettoyé automatiquement.
- **Dossier `Playlists/` :** il est dans le dossier de bibliothèque. Si le dossier de téléchargement n'est pas dans la bibliothèque, l'option « Ajouter à une playlist » de la fiche indique qu'elle ne fonctionne qu'avec un dossier de téléchargement placé dans la bibliothèque.
- **Pastilles Tidal / Amazon (venant d'Odesli) :** elles se remplissent progressivement pour les premiers résultats, dans la limite du quota, et sont complètes à l'ouverture d'une chanson. Les autres pastilles apparaissent tout de suite.
- **Pays des recherches :** FR (Apple Music, Deezer, Odesli). Ces plateformes ne servent qu'aux infos, sans lecture chez elles.

## 8. Vérification

- **Tests sur PC (vrais appels réseau) :** une requête comme « daft punk one more time » renvoie des résultats d'au moins 4 plateformes. Une chanson connue montre ses sources avec la qualité. Chaque filtre ne renvoie que sa plateforme.
- **Tests unitaires `.m3u8` :** écriture et relecture, chemins relatifs, caractères accentués, titre supprimé du disque.
- **Sur le téléphone de l'utilisateur (adb) :**
  - installation par-dessus la v1 sans perte de réglages ;
  - recherche multi-sources, choix d'une source, téléchargement ;
  - création d'une liste depuis les 3 points d'ajout, puis lecture ;
  - liste visible dans un autre lecteur ;
  - écran de lecture A ;
  - nom « Musique Pr » et icône Corail sur l'écran d'accueil.
- **Ne pas piloter l'écran du téléphone quand l'utilisateur s'en sert** (constaté en v1) : demander avant les tests sur l'appareil.
