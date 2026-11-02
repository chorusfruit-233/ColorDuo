#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")/.."
test_classes=$(mktemp -d)
trap 'rm -rf "$test_classes"' EXIT HUP INT TERM
test_sources=$(mktemp)
trap 'rm -rf "$test_classes"; rm -f "$test_sources"' EXIT HUP INT TERM
find tests/shims -name '*.java' > "$test_sources"
for name in Prefs TrioSettings TrioAppearance TrioGeometry TrioZones TrioSizing TrioRenderer DuoPolicy ChargeSpeed ColorOsChargeSpeed; do
    printf '%s\n' "app/src/main/java/io/github/yixing233/hyperduo/$name.java" >> "$test_sources"
done
printf '%s\n' tests/ColorOsSettingsTest.java >> "$test_sources"
printf '%s\n' tests/ColorOsRenderingTest.java >> "$test_sources"
printf '%s\n' tests/ChargeFlowTest.java >> "$test_sources"
printf '%s\n' tests/ChargeSpeedTest.java >> "$test_sources"
printf '%s\n' tests/ReviewChargeRegressionTest.java >> "$test_sources"
if command -v javac >/dev/null 2>&1; then
    javac -encoding UTF-8 -d "$test_classes" @"$test_sources"
else
    java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -d "$test_classes" @"$test_sources"
fi
java -Djava.awt.headless=true -Xmx512m -cp "$test_classes" io.github.yixing233.hyperduo.ColorOsSettingsTest
java -Djava.awt.headless=true -Xmx512m -cp "$test_classes" io.github.yixing233.hyperduo.ColorOsRenderingTest

java -Djava.awt.headless=true -cp "$test_classes" io.github.yixing233.hyperduo.ChargeSpeedTest
java -Djava.awt.headless=true -Xmx512m -cp "$test_classes" io.github.yixing233.hyperduo.ReviewChargeRegressionTest

java -Djava.awt.headless=true -cp "$test_classes" io.github.yixing233.hyperduo.ChargeFlowTest
