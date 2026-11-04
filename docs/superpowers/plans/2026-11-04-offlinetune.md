# OfflineTune Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** An ad-free Android app that downloads a track from a Spotify / Deezer / YouTube / YouTube Music / SoundCloud link (or a text search) with editable metadata and lyrics, then plays local files with a Poweramp-style sound engine.

**Architecture:** Two Gradle modules. `:core` is pure Kotlin/JVM: link parsing, metadata (Deezer, Spotify embed, NewPipe), YouTube Music matching, downloading, WebM→Ogg Opus remux, tagging, lyrics, file layout, and DSP maths. All of it is tested on the PC with real network calls. `:app` is Android (Compose + Media3). It wraps `:core` with a playback service, a SAF library scanner and the screens.

**Tech Stack:** Kotlin 2.1.0, Gradle 8.11.1 wrapper, AGP 8.7.3, JDK 17, compileSdk/targetSdk 35, minSdk 26, Jetpack Compose (BOM 2024.12.01, Material 3), AndroidX Media3 1.5.1, NewPipeExtractor (latest JitPack release tag), OkHttp 4.12.0, kotlinx.serialization 1.7.3, kotlinx.coroutines 1.9.0, JUnit 5.11.

**Spec:** `files.md` (repo root). Read it before any task.

## Global Constraints

- Package / applicationId: `fr.offlinetune` (core: `fr.offlinetune.core.*`). App name shown: "OfflineTune". License: GPL-3.0 (`LICENSE` file).
- minSdk 26, Kotlin, Jetpack Compose Material 3, dark theme only, accent colours taken from the current cover (androidx.palette).
- No ads, trackers or analytics dependencies of any kind. No account login.
- Audio is never re-encoded. YouTube: Opus itag 251 first, otherwise the highest-bitrate AAC/M4A. SoundCloud: the highest-bitrate stream.
- Output layout: `<download root>/<Artiste> - <Titre>/<Artiste> - <Titre>.<ext>` + same-name `.lrc` + `cover.jpg`.
- `.lrc` starts with `[ti:] [ar:] [al:] [length:]` headers. Unsynced lyrics are written without timestamps. No lyrics means no `.lrc`.
- Match rejection: a duration gap > 5 s when a reference duration exists. Show the top 5 candidates.
- SoundCloud preview detection: stream duration ≪ announced duration (rule: stream < 60 % of announced and announced − stream > 20 s).
- EQ: 10 bands at 31, 62, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz, ±12 dB, plus preamp. Crossfade 0–12 s.
- User-facing text in French. Extraction failures show "YouTube a changé, mise à jour de l'app nécessaire" (or the platform's name), never a stack trace.
- APK signed with the fixed keystore `app/offlinetune.jks` (committed), used for the debug build type too.
- Network tests in `:core` are tagged `@Tag("network")`. `./gradlew :core:test` runs everything. `./gradlew :core:test -PnoNetwork` excludes them.

## Review Focus

1. **Hostile titles**: `AC/DC`, `What?`, `"Live"`, trailing dots, emoji, 200-char titles. Names are sanitised (`\/:*?"<>|` → `_`, trimmed of dots/spaces, folder name ≤ 120 chars). An existing folder gets a ` (2)` suffix, never overwritten. Test in Task 13.
2. **Connection lost mid-download**: no half folder remains (write to `.<name>.part` dir, rename at the end), and the error is readable ("Connexion perdue pendant le téléchargement"). Test in Task 9 (server closing mid-body) and Task 13 (temp dir cleanup).
3. **Title noise in matching**: "Song (Remastered 2011)", "Song - Radio Edit", "feat. X" in title vs. artist must still match the right YouTube Music song, and an "(Live)" or "(Cover)" candidate must lose to the studio one. Unit tests in Task 7.
4. **Missing pieces**: no cover, no duration, no lyrics, no album. The save still succeeds, without `cover.jpg` / `.lrc`, and `[length:]` is omitted. Test in Task 13.
5. **Library folder revoked / removed SD card / corrupt audio file**: the scan skips unreadable files and the screen asks to re-pick the folder instead of crashing. Covered by the `LibraryScanner` unit test with a fake tree in Task 16.

---

## File Structure

```
settings.gradle.kts, build.gradle.kts, gradle/libs.versions.toml, gradlew(.bat), build.sh, README.md, LICENSE, .gitignore
core/build.gradle.kts
core/src/main/kotlin/fr/offlinetune/core/
  model/Models.kt            TrackInfo, Platform, AudioFormat, SourceCandidate, Sheet, LyricsResult, SearchHit
  net/Http.kt                shared OkHttpClient, getText/getJson/resolveRedirect, OfflineTuneException hierarchy
  net/NewPipeInit.kt         NewPipe Downloader impl on OkHttp + init()
  link/LinkParser.kt         detect platform + id from text, resolve short links
  meta/DeezerClient.kt
  meta/SpotifyEmbedClient.kt
  meta/NewPipeSource.kt      YT/SC metadata, best stream, YT Music search, YouTube search
  match/Matcher.kt           pure scoring
  sheet/SheetService.kt      input → Sheet / search results; enrichment, warnings
  download/StreamDownloader.kt  ranged HTTP + HLS concat, progress, cancel
  ogg/WebmReader.kt          Matroska demux → OpusHead + packets
  ogg/OggOpusWriter.kt       Ogg pages, CRC, granules, OpusTags (VorbisComment + picture)
  tags/Id3Writer.kt          ID3v2.3 for MP3
  lyrics/LrclibClient.kt, lyrics/LrcFormat.kt
  save/TrackSaver.kt         folder layout, sanitising, temp dir, orchestration of download+remux+tag+lyrics
  dsp/Biquad.kt, dsp/DspChain.kt, dsp/Presets.kt
core/src/test/kotlin/...     one test file per main file + EndToEndTest.kt
app/build.gradle.kts, app/offlinetune.jks, app/src/main/AndroidManifest.xml
app/src/main/java/fr/offlinetune/
  App.kt, MainActivity.kt
  settings/SettingsStore.kt  DataStore: folders, crossfade, fades, lyrics auto, EQ state, user presets
  playback/PlaybackService.kt, playback/DspAudioProcessor.kt, playback/CrossfadeController.kt, playback/PlayerConnection.kt
  library/LibraryScanner.kt, library/LibraryRepository.kt, library/LibraryCache.kt
  download/DownloadQueue.kt
  ui/theme/Theme.kt, ui/Nav.kt, ui/library/*.kt, ui/player/*.kt, ui/download/*.kt, ui/sound/SoundScreen.kt, ui/settings/SettingsScreen.kt
app/src/test/...             JVM unit tests (scanner with fake tree)
```

## Shared Interfaces (`core/model/Models.kt`, created in Task 2)

```kotlin
enum class Platform { SPOTIFY, DEEZER, YOUTUBE, YOUTUBE_MUSIC, SOUNDCLOUD }
data class TrackInfo(val title: String, val artist: String, val album: String? = null, val albumArtist: String? = null,
  val year: Int? = null, val trackNumber: Int? = null, val genre: String? = null, val durationSec: Int? = null,
  val coverUrl: String? = null, val isrc: String? = null)
enum class Container { OGG_OPUS, MP3, M4A }
data class AudioFormat(val container: Container, val codecLabel: String, val bitrateKbps: Int) { fun label() = "$codecLabel · $bitrateKbps kb/s" }
data class SourceCandidate(val url: String, val title: String, val artist: String, val durationSec: Int?, val score: Double, val rejectedReason: String? = null)
data class LyricsResult(val synced: String?, val plain: String?)   // synced = LRC body lines "[mm:ss.xx]text"
data class Sheet(val info: TrackInfo, val origin: Platform, val source: SourceCandidate?, val candidates: List<SourceCandidate>,
  val format: AudioFormat?, val warnings: List<String>, val lyrics: LyricsResult?, val coverBytes: ByteArray?)
data class SearchHit(val kind: Platform, val url: String, val title: String, val artist: String, val durationSec: Int?, val thumbUrl: String?)
sealed class OfflineTuneException(msg: String) : Exception(msg)   // .userMessage() in French
  class ExtractionBroken(platform: Platform) ; class NotFound(what: String) ; class NetworkLost ; class Unsupported(what: String)
```

---

### Task 1: Project scaffold, signing, build script

**Files:** Create every root file listed above, plus `core/build.gradle.kts`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/src/main/java/fr/offlinetune/MainActivity.kt` (a "OfflineTune" text placeholder), `app/offlinetune.jks`.

- [ ] Run `git init`, then add `.gitignore` (build/, .gradle/, local.properties, *.iml, .idea/). The `.jks` is **not** ignored.
- [ ] Generate the Gradle wrapper 8.11.1. No global gradle is installed, so download the distribution zip into the scratchpad and run its `bin/gradle wrapper --gradle-version 8.11.1` once.
- [ ] Find the latest NewPipeExtractor release tag (`https://api.github.com/repos/TeamNewPipe/NewPipeExtractor/releases/latest`). Put it in `libs.versions.toml` together with every version from the Tech Stack line. Add the `jitpack.io` repository in `settings.gradle.kts`.
- [ ] `:core` uses `kotlin("jvm")`, `kotlin("plugin.serialization")`, jvmToolchain 17 and JUnit Platform. `-PnoNetwork` → `excludeTags("network")`.
- [ ] `:app`: compileSdk 35, minSdk 26, Compose enabled, `isCoreLibraryDesugaringEnabled = true` with `desugar_jdk_libs_nio:2.1.3` (NewPipe needs it), and `packaging { resources.excludes += "META-INF/*" }` where needed. Add the `signingConfigs.create("fixed")` block (store/key password `offlinetune`, alias `offlinetune`) and use it for debug and release. Create the keystore with `keytool -genkeypair -keystore app/offlinetune.jks -alias offlinetune -keyalg RSA -keysize 4096 -validity 36500 -storepass offlinetune -keypass offlinetune -dname "CN=OfflineTune"`.
- [ ] Add `build.sh`: `./gradlew :core:test -PnoNetwork && ./gradlew :app:assembleDebug && cp app/build/outputs/apk/debug/app-debug.apk OfflineTune.apk`.
- [ ] Verify: `./gradlew :core:test :app:assembleDebug` → BUILD SUCCESSFUL, and the APK exists.
- [ ] Commit `chore: scaffold project`.

### Task 2: Models, HTTP layer, NewPipe initialisation

**Files:** Create `model/Models.kt` (as in Shared Interfaces), `net/Http.kt`, `net/NewPipeInit.kt`. Test: `net/HttpTest.kt`.

**Produces:**
- `object Http { val client: OkHttpClient; suspend fun getText(url: String, headers: Map<String,String> = emptyMap()): String; suspend fun getBytes(url: String): ByteArray; suspend fun resolveRedirect(url: String): String }`. Desktop Chrome User-Agent, 20 s timeouts. `IOException` → `NetworkLost`, HTTP 404 → `NotFound`.
- `object NewPipeInit { fun ensure() }`: idempotent. `NewPipe.init(OkHttpDownloader, Localization("fr","FR"), ContentCountry("FR"))`.
- `fun OfflineTuneException.userMessage(): String` (French).

- [ ] Write the tests. `resolveRedirect("https://spotify.link/…")`: use any known short link, or the `httpbin.org/redirect-to?url=` pattern, and assert the final URL. After `NewPipeInit.ensure()`, `ServiceList.YouTube.getStreamExtractor("https://www.youtube.com/watch?v=dQw4w9WgXcQ").fetchPage()` returns a name containing "Never Gonna Give You Up".
- [ ] Run → FAIL. Implement. Run → PASS. Commit `feat(core): http + newpipe init`.

### Task 3: LinkParser

**Files:** `link/LinkParser.kt`, test `link/LinkParserTest.kt`.

**Produces:** `sealed interface ParsedInput { data class Link(val platform: Platform, val id: String, val url: String) : ParsedInput; data class Query(val text: String) : ParsedInput }` and `object LinkParser { fun parse(text: String): ParsedInput; suspend fun parseResolving(text: String): ParsedInput }`. The suspend version follows the redirects of `spotify.link`, `deezer.page.link`, `link.deezer.com`, `on.soundcloud.com` and `youtu.be`, then calls `parse`.

- [ ] Write the tests. Pure, no network:
  - `open.spotify.com/track/4uLU6hMCjMI75M1A2tKUQC?si=x` and `open.spotify.com/intl-fr/track/<id>` → SPOTIFY id
  - `www.deezer.com/fr/track/3135556` → DEEZER "3135556"
  - `youtu.be/dQw4w9WgXcQ`, `youtube.com/watch?v=dQw4w9WgXcQ&list=…`, `m.youtube.com/shorts/<id>` → YOUTUBE
  - `music.youtube.com/watch?v=<id>` → YOUTUBE_MUSIC
  - `soundcloud.com/artist/track` → SOUNDCLOUD, url kept
  - Text shared from an app ("Écoute ça sur Spotify : https://open.spotify.com/track/…") → the URL is extracted
  - `"daft punk one more time"` → Query
  - An album or playlist URL → `Unsupported("albums/playlists")` (v2)
- [ ] Run → FAIL. Implement (regexes). Run → PASS. Commit.

### Task 4: DeezerClient

**Files:** `meta/DeezerClient.kt`, test `meta/DeezerClientTest.kt` (network).

**Produces:** `class DeezerClient { suspend fun track(id: String): TrackInfo; suspend fun byIsrc(isrc: String): TrackInfo?; suspend fun search(q: String, limit: Int = 20): List<SearchHit>; suspend fun findBest(title: String, artist: String, durationSec: Int?): TrackInfo? }`
- `track`: `api.deezer.com/track/<id>` → title, artist.name, album.title, album artist (`/album/<id>` `artist.name`), `release_date` year, `track_position`, genre (`/album/<id>` `genres.data[0].name`), duration, `album.cover_xl`, isrc. The API returns `{"error":…}` on unknown ids, which maps to `NotFound`.
- `findBest`: `search/track?q=artist:"a" track:"t"`, then pick with `Matcher.score` (Task 7). Return null below 0.75 or when the duration gap > 5 s. *Task 4 may stub this; Task 7 wires it.*

- [ ] Tests: `track("3135556")` → title "Harder, Better, Faster, Stronger", artist "Daft Punk", year 2001, coverUrl ends with `1000x1000-000000-80-0-0.jpg`, genre non-null. `search("daft punk one more time")` is non-empty and its first hit's artist is "Daft Punk". `track("999999999999")` throws `NotFound`.
- [ ] FAIL → implement → PASS → commit.

### Task 5: SpotifyEmbedClient

**Files:** `meta/SpotifyEmbedClient.kt`, test.

**Produces:** `class SpotifyEmbedClient(private val deezer: DeezerClient) { suspend fun track(id: String): TrackInfo }`. GET `https://open.spotify.com/embed/track/<id>` and parse `<script id="__NEXT_DATA__">` JSON → `props.pageProps.state.data.entity` (`name`, `artists[].name` joined by ", ", `duration` ms, `visualIdentity.image` largest / `coverArt`). Enrichment: `deezer.findBest(title, firstArtist, duration)` fills album, albumArtist, year, trackNumber, genre, isrc and coverUrl (HD), but **keeps Spotify's title and artist**. If the page format changes, fall back to `open.spotify.com/oembed?url=…` (title only) followed by a Deezer search. Total failure → `ExtractionBroken(SPOTIFY)`.

- [ ] Test: id `4uLU6hMCjMI75M1A2tKUQC` (Rick Astley, Never Gonna Give You Up) → title contains "Never Gonna Give You Up", artist "Rick Astley", durationSec within 210–216, year 1987, album non-null.
- [ ] FAIL → implement → PASS → commit.

### Task 6: NewPipeSource

**Files:** `meta/NewPipeSource.kt`, test.

**Produces:**
```kotlin
data class ResolvedStream(val url: String, val format: AudioFormat, val durationSec: Int?, val isHls: Boolean, val contentLength: Long?)
class NewPipeSource {
  suspend fun info(url: String): Pair<TrackInfo, Platform>      // title, uploader (strip " - Topic"), thumbnail max-res, upload year, duration
  suspend fun bestStream(url: String): ResolvedStream
  suspend fun searchMusicSongs(query: String, limit: Int = 10): List<SourceCandidate>   // YouTube Music "music_songs" filter, score = 0
  suspend fun searchYoutube(query: String, limit: Int = 20): List<SearchHit>             // music_songs + videos merged
}
```
- `bestStream` order: WEBMA_OPUS itag 251 → highest-bitrate M4A → (SoundCloud) progressive MP3 → HLS MP3. Anything else → `Unsupported`.
- Wrap `ExtractionException` / `ParsingException` into `ExtractionBroken(platform)`.

- [ ] Tests: `bestStream("https://www.youtube.com/watch?v=dQw4w9WgXcQ")` → container OGG_OPUS, bitrate ≥ 120. `info(...)` → artist "Rick Astley". `searchMusicSongs("Daft Punk One More Time")` is non-empty and every candidate's url contains `watch?v=`. `bestStream` on a public SoundCloud track (e.g. `https://soundcloud.com/forss/flickermood`) → container MP3.
- [ ] FAIL → implement → PASS → commit.

### Task 7: Matcher

**Files:** `match/Matcher.kt`, test `match/MatcherTest.kt` (pure).

**Produces:** `object Matcher { fun normalize(s: String): String; fun score(refTitle: String, refArtist: String, refDurationSec: Int?, cand: SourceCandidate): SourceCandidate; fun rank(ref: TrackInfo, cands: List<SourceCandidate>): List<SourceCandidate> }`. `rank` returns all candidates sorted best first, with `score` and `rejectedReason` set.

Algorithm (developer's choice, pinned here):
- `normalize`: lowercase, strip accents, drop bracketed/after-dash fragments matching `remaster(ed)?|radio edit|official (video|audio)|lyrics?|clip officiel|\d{4} remaster|explicit`, drop `feat\.?|ft\.?|featuring …`, keep alphanumerics and spaces, collapse spaces.
- titleSim = token-set Jaccard of the normalized titles. artistSim = max Jaccard between the ref's first artist and the candidate artist **or** candidate title (uploads like "Artist - Title").
- `score = 0.6*titleSim + 0.4*artistSim − 0.3` if the candidate contains `live|cover|karaoke|instrumental|nightcore|sped up|slowed` absent from the ref, `− 0.02 per second` of duration gap.
- Rejected (`rejectedReason = "Écart de durée : Ns"`) when the gap > 5 s and both durations are known. Rejected ("Correspondance trop faible") when score < 0.5.

- [ ] Tests:
  - `"Song (Remastered 2011)"` vs `"Song"` scores ≥ 0.9
  - `"Song - Radio Edit"` normalizes equal to `"song"`
  - ref "One More Time"/"Daft Punk" 320 s: candidate "One More Time (Live)" 322 s ranks below "One More Time" 321 s
  - a gap of 7 s → `rejectedReason` starts with "Écart de durée"
  - ref artist "Daft Punk", candidate artist "Daft Punk - Topic" after strip → artistSim 1.0
- [ ] FAIL → implement → PASS. Wire `DeezerClient.findBest` to `Matcher`. Commit.

### Task 8: SheetService

**Files:** `sheet/SheetService.kt`, test (network).

**Produces:**
```kotlin
sealed interface InputResult { data class Ready(val sheet: Sheet) : InputResult; data class Results(val deezer: List<SearchHit>, val youtube: List<SearchHit>) : InputResult }
class SheetService(deezer, spotify, newPipe, lrclib /*Task 12*/) {
  suspend fun open(text: String): InputResult
  suspend fun openHit(hit: SearchHit): Sheet
  suspend fun withSource(sheet: Sheet, sourceUrl: String): Sheet          // user picks a candidate or pastes their own YT/SC link
  suspend fun refreshLyrics(sheet: Sheet, info: TrackInfo): Sheet          // "Rechercher à nouveau"
}
```
- SPOTIFY/DEEZER: metadata → `searchMusicSongs("$artist $title")` → `Matcher.rank` → source = first non-rejected, candidates = top 5 (rejected included, so they can be shown). No valid source → source null + warning "Aucune correspondance à moins de 5 s : choisissez une source ou collez un lien YouTube/SoundCloud".
- YOUTUBE/YT_MUSIC: `NewPipeSource.info`; when uploader/title split as "Artist - Title", use it; Deezer `findBest` enrichment only when it is not rejected (a "safe" match).
- SOUNDCLOUD: preview rule from Global Constraints. When it is a preview → warning "Extrait SoundCloud (~30 s) détecté", plus YouTube Music candidates computed as above with source = best YT match.
- Always: `format` from `bestStream(source)`, `coverBytes` downloaded, lyrics from LRCLIB (Task 12; inject an interface `LyricsProvider` now and fake it until then).

- [ ] Tests: `open(<spotify url above>)` → Ready, source non-null with youtube url, format OGG_OPUS, candidates.size ≤ 5. `open("https://www.deezer.com/track/3135556")` → info.year 2001, source non-null. `open("daft punk around the world")` → Results with both lists non-empty. `withSource(sheet, "https://www.youtube.com/watch?v=dQw4w9WgXcQ")` → source.url equals it.
- [ ] FAIL → implement → PASS → commit.

### Task 9: StreamDownloader

**Files:** `download/StreamDownloader.kt`, test (uses `okhttp3.mockwebserver` plus one network test).

**Produces:** `class StreamDownloader { suspend fun download(stream: ResolvedStream, dest: File, onProgress: (Float) -> Unit) }`. Progressive streams are fetched with sequential `Range: bytes=a-b` requests in 8 MiB chunks (avoids YouTube throttling) and up to 3 retries per chunk. HLS: parse the m3u8, download segments in order and concatenate. Cancellation is cooperative (`ensureActive()` between chunks). Failure → delete `dest` and throw `NetworkLost`.

- [ ] Tests: MockWebServer serving 20 MiB with Range support → file bytes equal, progress reaches 1.0 and is monotonic. A server that drops the connection on every attempt for chunk 2 → `NetworkLost` and `dest` does not exist. HLS fixture with 3 segments → concatenation equals the segments joined.
- [ ] FAIL → implement → PASS → commit.

### Task 10: WebM → Ogg Opus remux with tags

**Files:** `ogg/WebmReader.kt`, `ogg/OggOpusWriter.kt`, `ogg/OggReader.kt` (also used by tests and the library), tests `ogg/OggOpusWriterTest.kt`, `ogg/RemuxNetworkTest.kt`.

**Produces:**
```kotlin
class VorbisComment(val vendor: String = "OfflineTune", val fields: List<Pair<String,String>>, val picture: ByteArray? /* JPEG */)
object WebmReader { fun read(input: InputStream): WebmOpus }   // data class WebmOpus(val opusHead: ByteArray, val packets: Sequence<ByteArray>)
object OggOpusWriter { fun write(opusHead: ByteArray, packets: Sequence<ByteArray>, tags: VorbisComment, out: OutputStream) }
object OggReader { fun inspect(file: File): OggInfo }  // data class OggInfo(val pages: Int, val crcOk: Boolean, val preSkip: Int, val durationSec: Double, val comments: Map<String,List<String>>, val hasPicture: Boolean)
```
- WebmReader: EBML var-int parsing. Descend Segment → Tracks/TrackEntry/CodecPrivate (= OpusHead, `CodecID == "A_OPUS"`, otherwise `Unsupported`) and Cluster → SimpleBlock / BlockGroup.Block. Skip other elements by size; handle unknown-size Segment/Cluster.
- OggOpusWriter: serial random. Page 0 = OpusHead (BOS). OpusTags gets its own page(s), lacing over several pages when the picture is large. Audio pages hold ≤ 255 segments / ~4 KiB, granule = cumulative 48 kHz samples (samples per packet from the TOC byte: frame size × frame count, RFC 6716 §3.1) + preSkip offset per RFC 7845. The last page has EOS. CRC32 uses polynomial 0x04C11DB7, init 0, no reflection.
- Fields written: TITLE, ARTIST, ALBUM, ALBUMARTIST, DATE, TRACKNUMBER, GENRE, LYRICS, and METADATA_BLOCK_PICTURE (FLAC picture block, type 3, `image/jpeg`, base64).

- [ ] Tests: a synthetic stream of 100 fake packets with TOC 0xFC (20 ms) → `OggReader.inspect`: crcOk, durationSec ≈ 2.0, comments TITLE present. Large 300 KiB picture → still crcOk and hasPicture. Network: download itag 251 of `dQw4w9WgXcQ` via Task 9, remux → inspect gives durationSec within 1 s of 212. If `ffprobe` is on PATH, also assert that it exits 0 (otherwise skip).
- [ ] FAIL → implement → PASS → commit.

### Task 11: Id3Writer (MP3)

**Files:** `tags/Id3Writer.kt`, test.

**Produces:** `object Id3Writer { fun rewrite(mp3In: File, mp3Out: File, info: TrackInfo, cover: ByteArray?, lyrics: String?) }`. Strip an existing ID3v2 header (and trailing ID3v1), then write ID3v2.3 frames TIT2 TPE1 TALB TPE2 TYER TRCK TCON APIC (type 3) USLT (lang "fra"). Text is UTF-16 with BOM, and sizes use the synchsafe header.

- [ ] Test: a dummy MP3 body (bytes `FF FB 90 64` repeated) with an old ID3 header → the output starts with `ID3\u0003`, the parsed frames contain TIT2 = title (UTF-16), and the audio bytes after the tag equal the original body exactly.
- [ ] FAIL → implement → PASS → commit.

### Task 12: Lyrics (LRCLIB + .lrc format)

**Files:** `lyrics/LrclibClient.kt`, `lyrics/LrcFormat.kt`, tests.

**Produces:** `class LrclibClient : LyricsProvider { suspend fun find(info: TrackInfo): LyricsResult? }` (interface `LyricsProvider` from Task 8). Use `GET lrclib.net/api/get?artist_name&track_name&album_name&duration`, falling back to `/api/search?q=` and picking a result within 5 s. Set the header `User-Agent: OfflineTune (https://github.com/…)`. `object LrcFormat { fun build(info: TrackInfo, lyrics: LyricsResult): String; fun preview(lyrics: LyricsResult, lines: Int = 4): List<String>; fun parse(lrc: String): List<Pair<Long /*ms*/, String>> }`.
- `build`: headers `[ti:]`, `[ar:]`, `[al:]` (omitted if null), `[length:mm:ss]` (omitted without duration), then the synced body. With plain lyrics only, the headers are followed by the plain text.
- `parse` handles `[mm:ss.xx]`, `[mm:ss.xxx]` and several stamps on one line. Lines without a stamp → empty list (= plain text).

- [ ] Tests: network `find(Daft Punk / One More Time / 320)` → synced non-null. `build` with album null → no `[al:` line; 245 s → `[length:04:05]`. `parse("[00:12.50][01:00.00]x")` → two entries 12500 and 60000.
- [ ] FAIL → implement → PASS. Wire it into SheetService. Commit.

### Task 13: TrackSaver + end-to-end

**Files:** `save/TrackSaver.kt`, tests `save/TrackSaverTest.kt`, `EndToEndTest.kt`.

**Produces:** `class TrackSaver(downloader, newPipe) { suspend fun save(sheet: Sheet, edited: TrackInfo, includeLyrics: Boolean, root: File, onProgress: (Float) -> Unit): File /* audio file */; companion object { fun sanitize(name: String): String; fun folderName(info: TrackInfo): String } }`.
- Flow: work in `root/.<folder>.part/`. Download → OGG_OPUS: WebmReader→OggOpusWriter with tags; MP3: Id3Writer; M4A: copy as-is (sidecar only). Then write `cover.jpg` if coverBytes and the `.lrc` if lyrics are included and present. Rename to the final folder (` (2)`, ` (3)` … if it exists). On any exception, delete the `.part` dir and rethrow.
- The app uses SAF (`DocumentFile`), so `:app` saves into a cache dir through `TrackSaver` and copies the finished folder into the tree (Task 18). `TrackSaver` itself only knows `File`.

- [ ] Unit tests (no network, fake downloader writing a synthetic WebM fixture built with a small test-only WebM writer): `sanitize("AC/DC: What?")` == `"AC_DC_ What_"`; 200-char title → folder ≤ 120 chars; second save of the same track → folder `"… (2)"`; no cover and no lyrics → only the audio file; a downloader that throws → no folder and no `.part` left under root.
- [ ] Network end-to-end (`@Tag("network")`): for the Spotify, Deezer, YouTube, YouTube Music and SoundCloud test links above, `SheetService.open` → `save` into a temp dir → the folder contains the audio (`OggReader.inspect` crcOk + TITLE == edited title for Opus, `ID3` header for MP3), `cover.jpg`, and a `.lrc` where LRCLIB has lyrics.
- [ ] FAIL → implement → PASS (`./gradlew :core:test`) → commit.

### Task 14: DSP

**Files:** `dsp/Biquad.kt`, `dsp/DspChain.kt`, `dsp/Presets.kt`, test `dsp/DspChainTest.kt`.

**Produces:**
```kotlin
data class DspSettings(val enabled: Boolean = true, val preampDb: Float = 0f, val bands: FloatArray = FloatArray(10), val bassDb: Float = 0f,
  val trebleDb: Float = 0f, val balance: Float = 0f /* -1..1 */, val mono: Boolean = false, val limiter: Boolean = true)
class DspChain { fun configure(s: DspSettings, sampleRate: Int, channels: Int); fun process(buf: FloatArray, frames: Int); fun reset() }
object Presets { val builtIn: Map<String, FloatArray> }   // keys: Plat, Rock, Pop, Jazz, Classique, Électro, Hip-Hop, Bass boost, Voix, Acoustique
```
- EQ: RBJ peaking biquads (Q 1.41) at the 10 frequencies. Bass = low shelf 100 Hz, treble = high shelf 10 kHz. Preamp is linear gain. Mono = (L+R)/2 on both channels. Balance: constant-power pan law. Limiter: soft knee `tanh`-style above −1 dBFS (|out| ≤ 1). Band frequencies above 0.45·sampleRate are bypassed. Settings changes swap coefficients without resetting state (no clicks).

- [ ] Tests (sine generator + RMS): all bands 0 dB and limiter off → output == input within 1e-4. +12 dB at 1 kHz → 1 kHz sine gain ≈ +12 dB ± 0.5, and a 100 Hz sine stays within ±0.5 dB. Balance −1 → right channel RMS ≈ 0. Mono with L=sine, R=0 → L==R. Limiter on with preamp +12 dB on a full-scale sine → max |sample| ≤ 1.0. Every preset has 10 values within ±12.
- [ ] FAIL → implement → PASS → commit.

### Task 15: Playback service

**Files:** `settings/SettingsStore.kt`, `playback/PlaybackService.kt`, `playback/DspAudioProcessor.kt`, `playback/CrossfadeController.kt`, `playback/PlayerConnection.kt`, manifest (service with `foregroundServiceType="mediaPlayback"`, permissions `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `POST_NOTIFICATIONS`, `INTERNET`).

**Produces:**
- `SettingsStore(context)`: `Flow<AppSettings>` + setters. Fields: `downloadTreeUri: String?`, `libraryTreeUri: String?`, `crossfadeEnabled`, `crossfadeSec: Int` (0–12, default 6), `crossfadeOnSkip: Boolean`, `pauseFade: Boolean` (default true, 250 ms), `autoLyrics: Boolean` (default true), `dsp: DspSettings`, `userPresets: Map<String, FloatArray>`, `currentPresetName: String`. Stored with DataStore Preferences, complex values as JSON.
- `DspAudioProcessor(chain: DspChain) : BaseAudioProcessor`: accepts PCM 16-bit (and float). Converts → `chain.process` → back. `PlaybackService` collects the settings flow and calls `chain.configure` live.
- `PlaybackService : MediaSessionService`: ExoPlayer built with a `DefaultRenderersFactory` override of `buildAudioSink` that inserts `DspAudioProcessor`, `handleAudioBecomingNoisy(true)`, audio focus on. MediaSession exposes the player. When crossfade is off, a normal playlist plays gapless.
- `CrossfadeController(main: ExoPlayer, makeAux: () -> ExoPlayer)`: when crossfade is enabled and `duration − position ≤ crossfadeSec` (polled every 100 ms), an aux player (same DSP) resumes the current item at the current position, `main` moves to the next item, and volumes ramp main 0→1 / aux 1→0 over the crossfade time (equal-power curve) before aux is released. With `crossfadeOnSkip`, a manual next/prev does the same. The MediaSession always shows `main`.
- Pause fade: `PlayerConnection.togglePause()` and the session's play/pause are wrapped through a `ForwardingPlayer` that ramps volume 1→0 over 250 ms before `pause()` and 0→1 after `play()`.
- `PlayerConnection(context)`: a MediaController wrapper exposing `StateFlow<PlayerUiState>` (current item metadata, position, isPlaying, shuffle, repeat, queue) and actions.

- [ ] Write a JVM unit test for the pure ramp math in `CrossfadeController` (`fun gains(t: Float): Pair<Float,Float>`: equal power, t=0 → (0,1), t=1 → (1,0), sum of squares ≈ 1).
- [ ] Implement. `./gradlew :app:testDebugUnitTest :app:assembleDebug` → pass/success. Commit.

### Task 16: Library

**Files:** `library/LibraryScanner.kt`, `library/LibraryRepository.kt`, `library/LibraryCache.kt`, test `app/src/test/.../LibraryScannerTest.kt`.

**Produces:**
```kotlin
@Serializable data class LibTrack(val uri: String, val title: String, val artist: String, val album: String?, val albumArtist: String?,
  val year: Int?, val trackNumber: Int?, val durationMs: Long?, val lrcUri: String?, val coverUri: String?, val lastModified: Long)
interface TreeNode { val name: String; val uri: String; val isDir: Boolean; val lastModified: Long; fun children(): List<TreeNode> }
class LibraryScanner(private val tags: (uri: String) -> TagData?) { fun scan(root: TreeNode, previous: Map<String, LibTrack>): List<LibTrack> }
class LibraryRepository(context, settings) { val tracks: StateFlow<List<LibTrack>>; val state: StateFlow<LibState /* Idle, Scanning, NeedsFolder, Error(msg) */>; suspend fun refresh(); suspend fun addFolder(uri: Uri) }
```
- Extensions `.opus .ogg .mp3 .m4a .flac .wav .aac`, case-insensitive. Skip folders whose names start with `.`. A same-name `.lrc` and a `cover.jpg`/`folder.jpg` in the same dir are detected. Tags are read with `MediaMetadataRetriever` (falling back to filename "Artiste - Titre"). Files whose `lastModified` is unchanged reuse the previous entry (fast refresh).
- Cache = JSON in `filesDir/library.json`. A real `TreeNode` adapter over `DocumentFile`. Permission lost (`SecurityException`, `!canRead()`) → `NeedsFolder`.
- After a download completes (Task 18), `addFolder` scans just that folder.

- [ ] Tests with a fake `TreeNode` tree: finds 3 audio files among `.txt`/`.jpg`, links the `.lrc`, skips `.x.part`. A tags lambda that throws for one file → that file still appears with filename-derived title/artist. A child throwing `SecurityException` → the scan of the other children continues.
- [ ] FAIL → implement → PASS → commit.

### Task 17: UI — shell, library, player, lyrics

**Files:** `ui/theme/Theme.kt`, `ui/Nav.kt`, `ui/library/LibraryScreen.kt` (tabs Titres / Albums / Artistes + search field, album/artist detail lists), `ui/player/MiniPlayer.kt`, `ui/player/PlayerScreen.kt`, `ui/player/LyricsView.kt`, `ui/player/QueueSheet.kt`, `MainActivity.kt`.

- Bottom navigation: **Bibliothèque · Télécharger · Son · Réglages**, with the mini-player above it (hidden when nothing is queued). Tapping it opens `PlayerScreen`.
- PlayerScreen: large cover, seek bar, prev/play/next, shuffle, repeat cycle (none → all → one), queue sheet, lyrics toggle. Accent = `Palette` vibrant swatch of the current cover, animated, falling back to the default accent.
- LyricsView: `LrcFormat.parse` of the `.lrc`. When synced, a LazyColumn auto-scrolls to the current line and highlights it, and tapping a line seeks. Plain text is shown when there are no stamps.
- No folder chosen → the library shows a "Choisir le dossier de musique" button (`OpenDocumentTree`, `takePersistableUriPermission`).
- [ ] `./gradlew :app:assembleDebug` succeeds. Commit.

### Task 18: UI — Download screen, share intent, queue

**Files:** `download/DownloadQueue.kt`, `ui/download/DownloadScreen.kt`, `ui/download/SheetScreen.kt`, `ui/download/ResultsList.kt`, manifest intent-filter `ACTION_SEND text/plain` on MainActivity.

- `DownloadQueue(appScope)`: `StateFlow<List<DownloadItem(id, label, progress, state: Queued|Running|Done(uri)|Failed(userMessage))>>`. Runs one at a time. Each job: `TrackSaver.save` into `cacheDir/dl/`, then copies the folder into the download tree with `DocumentFile` (applying the same ` (2)` rule), deletes the cache copy and calls `LibraryRepository.addFolder`. An ongoing notification shows progress. Retry action on Failed.
- DownloadScreen: field "Coller un lien ou rechercher", a Coller button, a platform chip (from `LinkParser.parse` as the user types), Results with two tabs "Titres" (Deezer) / "YouTube", then the sheet and the downloads list below.
- SheetScreen: cover (tap → `PickVisualMedia`, replaces coverBytes, re-encoded to JPEG only if it is not a JPEG), editable fields (titre, artiste, album, artiste de l'album, année, n° piste, genre), a source card (chosen candidate + "Changer" opening the top 5 with rejected reasons, plus a field to paste a YouTube/SoundCloud link), a quality label `format.label()`, warnings in an error-coloured card, a lyrics preview (`LrcFormat.preview`), "Rechercher à nouveau", and a "Ne pas inclure les paroles" switch. **Télécharger is disabled while `source == null`.**
- Errors are shown with `userMessage()`.
- Default download folder = library folder when unset (spec §10).
- [ ] `./gradlew :app:assembleDebug` succeeds. Commit.

### Task 19: UI — Sound & Settings screens

**Files:** `ui/sound/SoundScreen.kt`, `ui/settings/SettingsScreen.kt`.

- Sound: on/off switch, 10 vertical sliders ±12 dB labelled 31…16k, preamp slider, preset dropdown (built-ins + user presets, "Enregistrer comme préréglage…" dialog, delete for user presets), bass and treble sliders, balance slider (G … D), Mono switch, Limiter switch. Every change writes `SettingsStore` and is applied live by the service.
- Settings: download folder, library folder (pickers), crossfade switch + slider 0–12 s + "Aussi lors d'un saut manuel", pause/resume fade switch, automatic lyrics switch, and an "À propos" line (version, GPL-3.0, NewPipeExtractor version).
- [ ] `./gradlew :app:assembleDebug` succeeds. Commit.

### Task 20: Delivery

**Files:** `README.md` (French), `build.sh`.

- README: what the app does; install (`OfflineTune.apk`, allow unknown sources); rebuilding (install JDK 17 + Android SDK, `./build.sh`); updating NewPipeExtractor (change the version in `gradle/libs.versions.toml`, run `./build.sh`); the fixed key is in `app/offlinetune.jks` and must not be lost; known risks (spec §11).
- [ ] `./gradlew :core:test` (with network) → all pass. `./build.sh` → `OfflineTune.apk` produced. Run `apksigner verify --print-certs OfflineTune.apk` and confirm the certificate CN=OfflineTune.
- [ ] Commit `docs: README + build script`. Hand the APK to the user for the on-phone test (spec §10).
