#!/bin/bash
# Runs Holdfast's rule checks and whole-flow simulations on a plain JVM, no phone needed.
# The app is compiled against a real android.jar, then run against the small stand-in
# Android classes in tools/sim/stubs (fake clock, fake screens, fake package list).
#
# Usage: ANDROID_JAR=/path/to/android-34/android.jar tools/sim/run.sh
set -e
cd "$(dirname "$0")/../.."
JAR="${ANDROID_JAR:?set ANDROID_JAR to an android.jar (API 34)}"
OUT=$(mktemp -d)
mkdir -p "$OUT/app" "$OUT/stubs" "$OUT/tests"
javac -source 17 -target 17 -Xlint:-options -nowarn -cp "$JAR" -d "$OUT/app" $(find app/src -name '*.java')
javac -nowarn -d "$OUT/stubs" $(find tools/sim/stubs -name '*.java')
javac -source 17 -target 17 -Xlint:-options -nowarn -cp "$OUT/stubs:$OUT/app" -d "$OUT/tests" $(find tools/sim/tests -name '*.java')
status=0
for c in Checks Scenarios; do
  java -XX:+UnlockDiagnosticVMOptions -XX:-BytecodeVerificationRemote \
    -cp "$OUT/stubs:$OUT/tests:$OUT/app" com.aaron.holdfast.$c || status=1
done
rm -rf "$OUT"
exit $status
