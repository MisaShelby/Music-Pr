# Musique Pr v2.4 — mix DJ automatique, barre de temps, identification d'un son

> Inline execution; TDD on pure logic (core); build + device check for UI.

## Requirements (user, 2026-11-04)
1. **Mix auto DJ** (replaces v2.3 « Mix automatique »): not a fixed number of seconds — transitions like a DJ: tempo detected (BPM), beats aligned, the next track time-stretched to the current tempo during the transition then eased back, **bass swap** at the middle (incoming bass cut, then outgoing bass cut), length = 16 beats (8 when the outro is short), started on the outgoing beat grid before the outro/silence. Falls back to the v2.3 loudness plan when no reliable tempo (or tempos > ±8 % apart: no stretch, beat-aligned crossfade). The seconds slider is hidden when it is on.
2. **Barre de temps** of the player redesigned: thick rounded track that grows while dragging, elapsed / remaining under it, the playing position marker only while dragging, accent fill.
3. **Identifier un son** (Télécharger page): records 10 s from the microphone (radio, TV…), identifies it with **AudD** (official API, user's token in Réglages), then runs the multi-source search « artiste titre ». **Offline**: the recording is kept and identified automatically when the phone is back online (notification + list « Sons à identifier » / « Identifiés »).

## Builder's calls
- Beat tracking: onset strength from a 10 ms energy envelope, autocorrelation 70–180 BPM (octave errors folded into 80–160), phase = best comb alignment; confidence = peak ratio, below threshold → no tempo.
- Time-stretch with ExoPlayer `PlaybackParameters(speed, pitch = 1)` (Sonic keeps the pitch).
- Bass swap: extra low-shelf (150 Hz) per player in `DspAudioProcessor`, −24 dB, crossed over 2 beats around the middle.
- Recording: MediaRecorder AAC 44.1 kHz mono → `.m4a` in `filesDir/recognitions/`, pending list JSON; processed on start and on `ConnectivityManager` network-available.

## Tasks
1. core `playback/BeatAnalyzer.kt` + `playback/DjMix.kt` — tests: synthetic 120/128 BPM click tracks (bpm ±1, phase ±20 ms), half/double tempo folding, noise → no tempo; DjMix plan: 16 beats at 120 BPM = 8000 ms, start on beat grid before music end, stretch ratio when within 8 %, none otherwise, fallback to AutoMix.
2. core `recognize/AudDClient.kt` — tests: multipart fields (api_token, file, return=spotify,deezer,apple_music), success parse, no match → null, error 900/901 → BadKey/NeedsKey.
3. app DJ mix in CrossfadeController + DspAudioProcessor bass hook + settings UI.
4. app player seek bar.
5. app recognition: recorder, pending queue, network callback, Télécharger button + lists, Réglages token, RECORD_AUDIO permission.
6. Build + install.
