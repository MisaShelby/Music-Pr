# Musique Pr v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Federated search over 6 platforms with per-source quality, personal `.m3u8` playlists, glass + Material UI (now-playing direction A), renamed "Musique Pr" with the Corail icon — on top of v1.

**Architecture:** `:core` gains search providers (Apple iTunes, Spotify Web API and Tidal Open API with the user's own keys, plus the existing Deezer and NewPipe), a pure grouper/federator, a source resolver feeding the existing `SheetService`, and a pure `.m3u8` codec. `:app` gains a SAF playlist repository, a glass design system, and redesigned screens. Download pipeline, tags, `.lrc` and folder layout are unchanged.

**Tech Stack:** unchanged from v1 (Kotlin 2.1, Compose BOM 2024.12, Media3 1.5.1, NewPipeExtractor v0.26.5, OkHttp, kotlinx.serialization, JUnit 5).

**Spec:** `docs/superpowers/specs/2026-11-04-musique-pr-v2-design.md` (+ mockups in `docs/superpowers/specs/mockups/`), on top of `files.md`.

## Global Constraints

- applicationId `fr.offlinetune` and `app/offlinetune.jks` unchanged (update in place). Launcher label exactly `Musique Pr`.
- Platforms: YouTube Music, SoundCloud, Deezer, Apple Music (keyless); Spotify, Tidal (official APIs, user-entered keys only). No Amazon Music, no Qobuz.
- Only YouTube/YouTube Music and SoundCloud audio is ever downloaded. Never: `.sflx` files, dynamically loaded third-party code, Spotify web-player anonymous token, any decryption/access-control bypass.
- No official platform logos: coloured rounded badges with initials. Icon: original Corail design (#ff6a88 → #ff2d55 → #c2185b, white double eighth note).
- Store country `FR`. All UI text French. Dark theme; accent from current cover.
- Glass = real blur (`RenderEffect`) on API 31+, translucent fallback below.
- Network tests `@Tag("network")`; Spotify/Tidal network tests run only when env vars `SPOTIFY_ID/SPOTIFY_SECRET`, `TIDAL_ID/TIDAL_SECRET` are set (`Assumptions.assumeTrue`).
- Do not drive the phone UI over adb without asking the user first.

## Review Focus

1. **One provider down (timeout, 429, bad key)** → results from the others still appear within ~8 s; its chip shows « indisponible » or « clé requise » / « clé refusée ». Test in Task 6.
2. **Same song, slightly different titles across providers** ("One More Time" vs "One More Time - Radio Edit", same ISRC) are merged; "One More Time (Live)" is not. Test in Task 1.
3. **`.m3u8` with accents, emoji, Windows `\` separators, `#EXTINF` missing, entries whose file was deleted** → read without loss; missing entries kept and flagged. Test in Task 8.
4. **v1 settings JSON** (no `spotifyId`/`tidalId` fields) decodes with defaults, nothing lost on upgrade. Test in Task 10.
5. **Song found only on paid platforms** → a YouTube match is offered (5 s rule) or the clear message; never a paid platform marked downloadable. Test in Task 7.

---

## File Structure

```
core/src/main/kotlin/fr/offlinetune/core/
  search/Provider.kt          Provider enum (label, initials, colour hex), ProviderHit, SongResult, ProviderStatus
  search/SongGrouper.kt       pure grouping of hits into songs
  search/FederatedSearch.kt   parallel providers, timeouts, statuses, filter
  search/providers/AppleMusicProvider.kt, SpotifyProvider.kt, TidalProvider.kt, DeezerProvider.kt, YouTubeMusicProvider.kt, SoundCloudProvider.kt
  search/SourceResolver.kt    SongResult → SongSources (downloadable with real quality / listen-only)
  playlist/M3u8.kt            pure parse/write, relative path resolution
app/src/main/java/fr/offlinetune/
  playlist/PlaylistRepository.kt   SAF read/write of .m3u8 in <library>/Playlists + other .m3u8 found by the scan
  ui/glass/Glass.kt                GlassSurface, GlassChip, GlassNavBar, PlatformBadge
  ui/player/PlayerScreen.kt        rewritten (direction A), AddToPlaylistSheet.kt
  ui/download/SearchResults.kt, SourcesSheet.kt  (replace ResultsList.kt)
  ui/library/PlaylistsTab.kt, PlaylistDetail.kt
  res: mipmap-anydpi-v26/ic_launcher.xml + drawable ic_launcher_fg/bg (Corail)
```

## Shared Interfaces (`search/Provider.kt`, Task 1)

```kotlin
enum class Provider(val label: String, val initials: String, val colorHex: Long, val downloadable: Boolean) {
  YOUTUBE_MUSIC("YouTube Music", "YT", 0xFFE53935, true), SOUNDCLOUD("SoundCloud", "SC", 0xFFFF6A00, true),
  DEEZER("Deezer", "D", 0xFFA238FF, false), APPLE_MUSIC("Apple Music", "A", 0xFFFA2D48, false),
  SPOTIFY("Spotify", "S", 0xFF1DB954, false), TIDAL("Tidal", "T", 0xFF33383F, false) }
data class ProviderHit(val provider: Provider, val url: String, val title: String, val artist: String, val album: String?,
  val durationSec: Int?, val isrc: String?, val coverUrl: String?, val quality: String /* e.g. "FLAC 24 bit (selon abonnement)" */)
data class SongResult(val title: String, val artist: String, val album: String?, val durationSec: Int?, val coverUrl: String?,
  val hits: Map<Provider, ProviderHit>)
sealed interface ProviderStatus { data object Ok : ProviderStatus; data object NeedsKey : ProviderStatus
  data object BadKey : ProviderStatus; data class Failed(val message: String) : ProviderStatus }
interface SearchProvider { val provider: Provider; suspend fun search(query: String, limit: Int = 15): List<ProviderHit> }
```
Static quality labels (when the API gives nothing per track): DEEZER "FLAC 16 bit (selon abonnement)", APPLE_MUSIC "ALAC sans perte (selon abonnement)", SPOTIFY "Ogg 320 kb/s / sans perte (selon abonnement)", TIDAL from `mediaTags`: HIRES_LOSSLESS → "FLAC jusqu'à 24 bit / 192 kHz", LOSSLESS → "FLAC 16 bit / 44,1 kHz", else "AAC 320 kb/s". YouTube/SoundCloud hits: quality "" until resolved (Task 7).

---

### Task 1: Search model and SongGrouper

**Files:** Create `search/Provider.kt`, `search/SongGrouper.kt`; Test `search/SongGrouperTest.kt`.
**Produces:** `object SongGrouper { fun group(hits: List<ProviderHit>): List<SongResult> }` — order = order of first appearance; same song when ISRCs equal, else `Matcher.normalize(title)` equal **and** `Matcher.normalize(firstArtist)` equal **and** durations within 3 s (or one unknown). Keeps the first hit per provider. SongResult fields from the first catalog hit in priority DEEZER > APPLE_MUSIC > SPOTIFY > TIDAL > YOUTUBE_MUSIC > SOUNDCLOUD.

- [ ] Tests: same ISRC with titles "One More Time" / "One More Time - Radio Edit" → 1 song, 2 providers; "One More Time (Live)" with different ISRC → separate song; no ISRC, same normalized title/artist, durations 320/322 → merged; 320/330 → separate; Deezer hit's album wins over YouTube's.
- [ ] FAIL → implement → PASS (`./gradlew :core:test --tests '*SongGrouperTest'`) → commit.

### Task 2: Apple Music provider (iTunes Search)

**Files:** `search/providers/AppleMusicProvider.kt`; Test `AppleMusicProviderTest.kt`.
**Produces:** `class AppleMusicProvider(base: String = "https://itunes.apple.com") : SearchProvider` — `GET /search?term=&entity=song&country=FR&limit=` → title `trackName`, artist `artistName`, album `collectionName`, duration `trackTimeMillis/1000` rounded, cover `artworkUrl100` with `100x100bb` → `1000x1000bb`, url `trackViewUrl`, isrc null.
- [ ] Unit test with MockWebServer fixture (2 results) + network test "daft punk one more time" → first hit artist "Daft Punk", cover contains `1000x1000`.
- [ ] FAIL → implement → PASS → commit.

### Task 3: Spotify provider (official Web API, user keys)

**Files:** `search/providers/SpotifyProvider.kt`; Test `SpotifyProviderTest.kt`.
**Produces:** `class SpotifyProvider(keys: () -> Pair<String,String>?, accounts: String = "https://accounts.spotify.com", api: String = "https://api.spotify.com") : SearchProvider`. Token: `POST /api/token` form `grant_type=client_credentials` with Basic auth; cached until `expires_in − 60 s`. Search: `GET /v1/search?type=track&market=FR&limit=&q=`. Map `name`, `artists[0].name`, `album.name`, `duration_ms`, `external_ids.isrc`, largest `album.images`, `external_urls.spotify`. Keys null → throw `NeedsKey` (new `OfflineTuneException` subclass, message « Clé Spotify requise »); token 400/401 → `BadKey` (« Clé Spotify refusée »).
- [ ] MockWebServer tests: token then search parse; token reused on 2nd search (2 requests total → 3 with second search); 401 → BadKey; no keys → NeedsKey. Network test only with env keys.
- [ ] FAIL → implement → PASS → commit.

### Task 4: Tidal provider (official Open API, user keys)

**Files:** `search/providers/TidalProvider.kt`; Test `TidalProviderTest.kt`.
**Produces:** `class TidalProvider(keys, auth = "https://auth.tidal.com", api = "https://openapi.tidal.com")`. Token `POST /v1/oauth2/token` client_credentials (Basic). Search `GET /v2/searchResults/{urlencoded query}/relationships/tracks?countryCode=FR&include=tracks` (JSON:API: `included[]` of type `tracks` with `attributes.title`, `isrc`, `duration` ISO-8601 `PT5M20S`, `mediaTags`, `externalLinks[0].href`; artist/album names via their `relationships` + included when present, else from the query's catalog grouping). Same NeedsKey/BadKey behaviour. Quality from `mediaTags` (see Shared Interfaces). If the live response shape differs, adapt the parser to it and ledger the ruling.
- [ ] MockWebServer tests with a JSON:API fixture: hit fields, `PT5M20S` → 320, HIRES_LOSSLESS → quality label, BadKey, NeedsKey.
- [ ] FAIL → implement → PASS → commit.

### Task 5: Deezer, YouTube Music, SoundCloud providers

**Files:** `search/providers/DeezerProvider.kt`, `YouTubeMusicProvider.kt`, `SoundCloudProvider.kt`; modify `meta/NewPipeSource.kt` (+ `searchSoundCloud(query, limit): List<SearchHit>` via `ServiceList.SoundCloud` "tracks" filter); Test `ProvidersNetworkTest.kt`.
**Produces:** adapters over existing `DeezerClient.search` (adds isrc via track fields when present, cover `album.cover_xl`) and `NewPipeSource.searchMusicSongs`/`searchSoundCloud`. YouTube provider labels hits YOUTUBE_MUSIC; when the v1 fallback (VIDEOS) was used, set `album = null` and keep provider YOUTUBE_MUSIC with `quality = ""` (UI calls it « YouTube »; see Task 13).
- [ ] Network tests: each provider returns ≥ 1 hit for "daft punk one more time"; SoundCloud hit url starts with `https://soundcloud.com/`.
- [ ] FAIL → implement → PASS → commit.

### Task 6: FederatedSearch

**Files:** `search/FederatedSearch.kt`; Test `FederatedSearchTest.kt`.
**Produces:** `data class SearchOutcome(val songs: List<SongResult>, val status: Map<Provider, ProviderStatus>)`; `class FederatedSearch(providers: List<SearchProvider>, timeoutMs: Long = 8000) { suspend fun search(q: String): SearchOutcome; companion fun filter(o: SearchOutcome, p: Provider?): List<SongResult> }` — providers run in parallel (`supervisorScope` + `withTimeout` each); hits concatenated in round-robin order across providers (1st of each, 2nd of each…) then `SongGrouper.group`; `filter(null)` = all, else songs whose `hits` contain p. `FederatedSearch.create(settings: () -> Keys)` wires the 6 providers.
- [ ] Tests with fake providers: one throws → its status Failed, others' songs present; one sleeps 20 s with timeoutMs 200 → Failed("délai dépassé"), call returns < 1 s; NeedsKey propagated; filter(DEEZER) only songs with a Deezer hit; round-robin order.
- [ ] FAIL → implement → PASS → commit.

### Task 7: SourceResolver + SheetService entry

**Files:** `search/SourceResolver.kt`; modify `sheet/SheetService.kt` (+ `suspend fun fromSong(song: SongResult, source: DownloadOption): Sheet`); Test `SourceResolverTest.kt` (network).
**Produces:** `data class DownloadOption(val provider: Provider, val url: String, val format: AudioFormat, val isMatch: Boolean /* found via YouTube matching */, val hasLyrics: Boolean)`; `data class SongSources(val downloadable: List<DownloadOption>, val listenOnly: List<ProviderHit>, val message: String?)`; `class SourceResolver(newPipe, sheetService) { suspend fun resolve(song: SongResult): SongSources }` — downloadable = YT/SC hits with `bestStream` format; if none, `Matcher.rank` over `searchMusicSongs("$artist $title")` → first accepted as `isMatch = true`; none → message « Aucune version téléchargeable trouvée — collez un lien YouTube ou SoundCloud ». `hasLyrics` = LRCLIB found (one lookup per song). listenOnly = all paid-platform hits. `fromSong` builds the v1 `Sheet` with info from the best catalog hit (Deezer track by id when present, else the hit's fields), source = chosen option, lyrics + cover as v1.
- [ ] Tests: Deezer-only song (built from `DeezerClient.search` hit) → downloadable has 1 `isMatch` YouTube option with OGG_OPUS format, listenOnly contains DEEZER; YouTube-only song → its own url downloadable, no match search; `fromSong` → `Sheet.source.url == option.url`, info.year from Deezer.
- [ ] FAIL → implement → PASS → commit.

### Task 8: M3u8 codec

**Files:** `playlist/M3u8.kt`; Test `M3u8Test.kt`.
**Produces:** `data class M3uEntry(val path: String /* relative, '/' separators */, val durationSec: Int?, val label: String?)`; `object M3u8 { fun write(entries: List<M3uEntry>): String; fun parse(text: String): List<M3uEntry>; fun relative(fromDir: List<String>, toFile: List<String>): String; fun resolve(fromDir: List<String>, rel: String): List<String> }` (paths as segment lists from the library root). Write: `#EXTM3U\n` then per entry `#EXTINF:<sec or -1>,<label>\n<path>\n`, UTF-8, no BOM. Parse tolerates BOM, CRLF, `\` separators, missing `#EXTINF`, blank lines, comments.
- [ ] Tests: round trip with "Été indien 🎵" names; `relative(["Playlists"], ["Joe Dassin - Été indien","x.opus"])` == `../Joe Dassin - Été indien/x.opus`; resolve back; parse of a Poweramp-style file with CRLF and `..\A\b.mp3`; missing EXTINF → durationSec null.
- [ ] FAIL → implement → PASS → commit.

### Task 9: PlaylistRepository (app, SAF)

**Files:** `app/.../playlist/PlaylistRepository.kt`; modify `library/LibraryScanner.kt` (also collect `.m3u8`/`.m3u` files as `PlaylistFile(uri, name, dirSegments)`), `library/LibraryRepository.kt` (expose `playlists: StateFlow<List<Playlist>>`); Test `PlaylistResolveTest.kt` (JVM, fake TreeNode).
**Produces:** `data class Playlist(val uri: String, val name: String, val dirSegments: List<String>, val entries: List<PlaylistItem>)`, `data class PlaylistItem(val entry: M3uEntry, val track: LibTrack?)` (null = missing → greyed); repository ops `create(name)`, `rename`, `delete` (UI confirms), `add(playlist, tracks)`, `remove(playlist, index)`, `move(playlist, from, to)` — each rewrites the file in place via `contentResolver.openOutputStream(uri, "wt")`; new lists go to `<library>/Playlists/<sanitized name>.m3u8`. LibTrack gets `pathSegments: List<String>` (filled by the scanner) for relative paths. Download-time add: done by `DownloadQueue` after the SAF copy when the download tree is the library tree (else error text « Ajout impossible : le dossier de téléchargement doit être dans la bibliothèque »).
- [ ] JVM test: scanner over fake tree with `Playlists/Mix.m3u8` referencing `../A - B/A - B.opus` and a missing file → playlist with 2 items, first resolved to the LibTrack, second `track == null`.
- [ ] FAIL → implement → PASS (`:app:testDebugUnitTest`) → commit.

### Task 10: Settings keys, rename, Corail icon

**Files:** modify `settings/SettingsStore.kt` (+ `spotifyId`, `spotifySecret`, `tidalId`, `tidalSecret: String? = null`), `ui/settings/SettingsScreen.kt` (section « Plateformes » with the 4 fields, masked secrets, link text to developer.spotify.com / developer.tidal.com), `AndroidManifest.xml` label `Musique Pr`, `res/drawable/ic_launcher_bg.xml` (gradient), `ic_launcher_fg.xml` (double eighth note path from mockup `icone.html` Corail, scaled to 108 dp safe zone), `README.md` title; Test `SettingsCompatTest.kt` (JVM).
- [ ] Test: decoding the v1 JSON `{"libraryTreeUri":"content://x","crossfadeSec":8,"dsp":{"bands":[1,0,0,0,0,0,0,0,0,0]}}` → libraryTreeUri kept, crossfadeSec 8, spotifyId null.
- [ ] FAIL → implement → PASS; `assembleDebug` → commit.

### Task 11: Glass design system

**Files:** `ui/glass/Glass.kt`; modify `ui/Nav.kt` (floating `GlassNavBar`, glass mini-player), `ui/player/MiniPlayer.kt`.
**Produces:** `@Composable fun GlassSurface(modifier, shape, content)` — translucent white 12–16 % + 1 px white 20 % border; on API 31+ background layer blurred with `Modifier.graphicsLayer { renderEffect = BlurEffect(24f, 24f) }` applied to a copy of the backdrop (cover gradient) — the app keeps a `LocalBackdrop` (current cover bitmap + accent) drawn behind screens; `GlassChip(selected, label, enabled, onClick)`; `PlatformBadge(provider, size)`; `GlassNavBar(items, selected, onSelect)` floating 12 dp above the gesture bar, 28 dp radius.
- [ ] `assembleDebug` → commit. (Visual check in Task 15.)

### Task 12: Now-playing screen A + add to playlist

**Files:** rewrite `ui/player/PlayerScreen.kt`; create `ui/player/AddToPlaylistSheet.kt`.
- Layout per `mockups/lecture.html` option A: full-screen blurred cover colours; top row (⌄, « En lecture », ⋯); large cover 22 dp radius with shadow; title 20 sp bold + « artiste — album »; progress bar with times and centred glass chip of quality (from file extension + bitrate: library tracks get `qualityLabel` from MediaMetadataRetriever bitrate); controls row (shuffle, prev, white 66 dp play, next, repeat); bottom glass bar « Paroles · File · Son · + Playlist ». Paroles swaps the cover area for `LyricsView`; File opens the queue sheet; Son navigates to the Son tab; + Playlist opens `AddToPlaylistSheet` (list of playlists with checkmarks + « Nouvelle liste »).
- [ ] `assembleDebug` → commit.

### Task 13: Download screen — federated results and sources sheet

**Files:** replace `ui/download/ResultsList.kt` with `SearchResults.kt`; create `SourcesSheet.kt`; modify `DownloadModel.kt` (text query → `FederatedSearch`; links keep v1 path; new phases `Federated(outcome, filter)` and `Sources(song, sources)`), `SheetScreen.kt` (+ « Ajouter à une playlist » multi-select, applied after a successful download via `DownloadRequest.playlists: List<String>`), `download/DownloadQueue.kt`.
- Results per `mockups/recherche.html` left: chips « Tout » + 6 providers (disabled chip text « clé requise » / « indisponible »), rows with cover, title, « artiste · album · durée », badge row. Sources sheet per mockup right: « Téléchargeable » (badge, label — « YouTube Music » or « Correspondance YouTube » when `isMatch` — quality, « paroles ✓/✗ », button « Choisir ») then « Disponible aussi (écoute dans leur appli) » (badge, quality, « Ouvrir ↗ » → `ACTION_VIEW` url). Choisir → `fromSong` → v1 sheet.
- [ ] `assembleDebug` → commit.

### Task 14: Library playlists tab

**Files:** create `ui/library/PlaylistsTab.kt`, `PlaylistDetail.kt`; modify `LibraryScreen.kt` (tabs Titres / Albums / Artistes / Playlists; long-press on a track → `AddToPlaylistSheet`; scan only on start/« Actualiser », not on every tab switch).
- Playlists tab: « Nouvelle liste » button, list with name + count + first cover. Detail: play, shuffle, rename, delete (confirm dialog « Supprimer la liste et son fichier ? »), items with drag handle (move up/down buttons acceptable), remove, missing items greyed « Fichier introuvable » and skipped by play.
- [ ] `assembleDebug` → commit.

### Task 15: Delivery and device check

- [ ] `./gradlew :core:test` (network) and `:app:testDebugUnitTest` all green; `./build.sh` → APK; `apksigner` CN=OfflineTune.
- [ ] Ask the user before driving the phone; then install over v1 and check: label « Musique Pr » + Corail icon, settings kept, federated search shows ≥ 4 providers, sources sheet, download, playlist created from the 3 entry points, `.m3u8` present in `Music/Playlists/`, now-playing A.
- [ ] README (French) updated: name, platforms, how to create Spotify/Tidal developer keys. Commit.
