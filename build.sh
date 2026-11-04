#!/usr/bin/env bash
# Recompile OfflineTune : tests du cœur (sans réseau) puis APK signé avec la clé fixe.
set -euo pipefail
cd "$(dirname "$0")"
./gradlew :core:test -PnoNetwork
./gradlew :app:assembleDebug
cp app/build/outputs/apk/debug/app-debug.apk MusiquePr.apk
echo "APK prêt : $(pwd)/MusiquePr.apk"
