# Musique Pr v2.2 — bibliothèque avancée, file d'attente, édition, boutons DJ, vitesse

> Executed inline (superpowers:executing-plans). TDD on pure logic (core + app JVM), build + device check for UI.
> Requirements: user's 14-point message of 2026-11-04 (French), restated below. Builder's calls marked (choix).

## Requirements
1. Sort in every library tab (Titres, Albums, Artistes, Playlists): **date d'ajout/modification (défaut, plus récent d'abord)**, nom/titre, artiste, n° de piste, nom de fichier, emplacement (dossier), nombre de lectures; ascending/descending toggle.
2. Display mode: **liste** or **grille** (2 colonnes) per tab.
3. Queue: **Lire après** (right after the current track) and **Ajouter à la fin** (end of queue).
4. **Edit saved tracks** (Info): title, artist, album, album artist, year, track no, genre, cover (gallery), lyrics (.lrc: paste / search LRCLIB). Written into the file (Opus/MP3/M4A, no re-encoding). If the track has no cover and gets one, and is not already alone in its own folder, **create a folder** « Artiste - Titre » and move the audio (+ its .lrc) into it with `cover.jpg` (choix: same layout as downloads).
5. Son: preamp, bass, treble and balance as **round DJ knobs** (drag to turn).
6. Top library tabs add **Plus écoutés** and **Récents** (recently played) next to Titres / Albums / Artistes / Playlists.
7. Touching a track queues **that track and the ones after it** in the shown order — not the ones before.
8. **Vider la file** (keeps the current track).
9. **Multi-select** (long press): play in selection order, shuffle, play next, add to end, add to playlist, delete.
10. Row **⋮ menu**: Ajouter à une liste, Lire après, Ajouter à la fin, Supprimer (confirmation; deletes the track's own folder when it holds only that track + lrc/cover, else the audio + .lrc) (choix), Partager (share the audio file), Info (details with cover, editable).
11. After a scan (Actualiser), a dialog lists the tracks **without cover** (no cover file and no embedded picture).
12. **Playback speed** 0.5×, 0.75×, 1×, 1.25×, 1.5×, 2× (player screen).
13. Spotify chip/errors: show Spotify's real error (status + message) instead of « Connexion perdue pendant le téléchargement »; network errors outside downloads say « Pas de connexion ou serveur injoignable ».
14. **Télécharger page restyle**: glass search field, glass action buttons, « Pour vous » cards, downloads as glass cards with progress — consistent with mockups.

## Tasks
1. **core `library/LibrarySort.kt`** (pure, in core so it is JVM-tested): `enum SortKey { ADDED, TITLE, ARTIST, TRACK, FILENAME, LOCATION, PLAYS }`; `fun <T> sortTracks(list, key, desc, fields: (T) -> SortFields)`; `fun queueFrom(list, index)` = list.drop(index); `fun selectionOrder(selected: List<String>, all)`. Tests: each key, stable ties, desc toggle, queueFrom.
2. **core `ogg/OggReader.packets(file)`** + **`tags/TagEditor.kt`**: `TagEditor.write(file: File, ext, info, cover?, lyrics?)` → Opus: read OpusHead + packets, rewrite with new OpusTags (no re-encode; same granules); MP3: Id3Writer.rewrite; M4A: Mp4Tags.write; others → Unsupported. Tests: Opus round trip (packets identical, new TITLE, picture), MP3/M4A delegate.
3. **app play stats** `library/PlayStats.kt` (JSON in filesDir: uri → count, lastPlayed); PlayerConnection records a play when an item has played 30 s or to its end (choix). JVM test of the counter logic.
4. **app library UI**: tabs Titres / Albums / Artistes / Playlists / Plus écoutés / Récents; sort menu + list/grid toggle per tab (persisted in settings); queue-from-index (7); row ⋮ menu (10); multi-select bar (9); delete with dialog (10); share; missing-cover dialog after Actualiser (11; scanner records `hasArtwork` via embedded picture check).
5. **app queue**: PlayerConnection `playNext(tracks)`, `addToEnd(tracks)`, `clearQueue()`, `setSpeed(x)`; queue sheet « Vider la file »; player speed chip/menu (12).
6. **app Info / edit**: `TrackEditor` (copy SAF file to cache → TagEditor → write back "wt"; cover → folder creation + move via `DocumentsContract.moveDocument`; .lrc write) and Info screen (fields, cover picker, lyrics search/paste), then rescan the folder.
7. **app Son knobs**: `Knob` composable (circular, drag to rotate, value arc in accent, centre value) for preamp, bass, treble, balance.
8. **Spotify errors (13)** and **Télécharger restyle (14)**.
9. Delivery: tests, APK, install, device check (ask before driving), commit.
