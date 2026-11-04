#!/usr/bin/env bash
# Installeren met gelijke Java-klassen maar verschillende pakketten/providers moet naast elkaar werken.
set -euo pipefail
cd "$(dirname "$0")/../.."
source VERSION
adb install -r "Renes-Tools-v$VERSION_NAME.apk"
MANIFEST=build-play/test-manifest.xml bash tests/telefoon/rooktest.sh build-play/play-test.apk build-play/telefoon
adb shell pm path nl.rene.tools | grep -q package:
adb shell pm path nl.rene.tools.play | grep -q package:
