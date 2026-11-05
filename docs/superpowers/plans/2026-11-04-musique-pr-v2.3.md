# Musique Pr v2.3 — écran Son façon console, réinitialiser, icône rose-rouge, mix automatique, Spotify 2026

> Inline execution; TDD on pure logic; build + device check for UI.

## Requirements (user, 2026-11-04)
1. **Son screen laid out like the user's reference screenshot** (`Screenshot_20261104_160931_Poweramp.jpg`): segmented top bar (Égaliseur · Tonalité · Effets); tall vertical EQ sliders with round handles and a glowing accent fill from the centre line, value + frequency under each; Préampli column apart; an EQ curve preview strip; buttons « Égal. » (EQ on/off), « Ton. » (bass/treble on/off), « Limiteur », « Pré-réglage » (preset picker), ⋮ menu; Basses / Aigus knobs with a coloured arc. Our own colours (accent), no third-party branding.
2. **Réinitialiser** (⋮ menu, confirmation): every sound setting back to default.
3. **Launcher icon**: pink-red gradient background (#FF5E7A → #FA233B → #D3122F), our white note; no Apple artwork.
4. **Mix automatique** (switch in Réglages › Lecture, independent of manual crossfade): analyse the loudness envelope of the outgoing track's last 40 s and the incoming track's first 15 s; start the transition where the outro begins (energy stays below −24 dB of the track's body level, or trailing silence), skip the incoming track's leading silence, fade length 2–12 s chosen from the outro length. Falls back to the user's manual crossfade when analysis fails.
5. **Spotify 2026 rules**: search `limit ≤ 10`; playlist tracks via `/v1/playlists/{id}/items` (`items[].item`), `/tracks` only as fallback; 403 « premium required » shown verbatim (already done in v2.2).

## Tasks
1. core `playback/AutoMix.kt` (pure): `fun envelopeDb(pcm: FloatArray, rate: Int, channels: Int, windowMs = 100): FloatArray`; `fun plan(outTail: FloatArray /*dB per 100 ms, last 40 s*/, tailStartMs: Long, durationMs: Long, inHead: FloatArray, manualFadeMs: Long): MixPlan(startAtMs, fadeMs, skipInMs)`. Tests: trailing silence → start before silence; long quiet outro → longer fade; incoming 2 s silence → skipIn ≈ 2000; flat loud track → falls back to manual fade at end.
2. core Spotify: search limit 10; playlist `/items` first. Tests updated.
3. app `playback/AudioAnalyzer.kt`: decode a time range with MediaExtractor/MediaCodec to mono float PCM → envelope (cached per uri). CrossfadeController: when autoMix on, analyse current + next item when a track starts; use MixPlan (prepare at startAt − lead, seek aux/main, fade fadeMs, main seeks to skipIn). Settings `autoMix: Boolean`.
4. app Son screen rewrite + Réinitialiser.
5. app icon background.
6. Build, tests, install, device check (ask first), commit.
