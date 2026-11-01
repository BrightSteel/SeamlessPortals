#!/bin/sh
# Builds the Fabric mod and the Paper plugin for every Minecraft version in versions/ and
# collects the jars in build/dist (one Gradle run per version: each needs its own Minecraft).
set -e
cd "$(dirname "$0")"
mkdir -p build/dist
for file in versions/*.properties; do
	mc=$(basename "$file" .properties)
	echo "== Minecraft $mc"
	./gradlew build -Pmc="$mc" -q
	cp fabric/build/libs/netherview-fabric-mc"$mc"-*.jar paper/build/libs/netherview-paper-mc"$mc"-*.jar build/dist/
done
ls build/dist
