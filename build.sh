#!/usr/bin/env bash
# Builds StatFacts with the JDK tools against the Paper 26.3 API jars in the local Gradle cache.
# No network needed. Output: build/StatFacts-2.0.0.jar
set -euo pipefail
cd "$(dirname "$0")"
CACHE="${GRADLE_CACHE:-$HOME/.gradle/caches/modules-2/files-2.1}"

# javac and jar run as JDK modules, so this works on a JRE that ships jdk.compiler.
JAVAC=(java -m jdk.compiler/com.sun.tools.javac.Main)
JAR=(java -m jdk.jartool/sun.tools.jar.Main)

find_jar() { # group artifact version
  local f
  f=$(find "$CACHE/$1/$2/$3" -name "$2-$3.jar" 2>/dev/null | head -n1)
  if [ -z "$f" ]; then
    echo "missing jar: $1:$2:$3" >&2
    exit 1
  fi
  printf '%s' "$f"
}

DEPS=(
  "com.google.guava guava 33.6.0-jre"
  "com.google.code.gson gson 2.14.0"
  "org.yaml snakeyaml 2.2"
  "org.joml joml 1.10.9"
  "it.unimi.dsi fastutil 8.5.18"
  "org.apache.logging.log4j log4j-api 2.26.0"
  "org.slf4j slf4j-api 2.0.17"
  "com.mojang brigadier 1.3.11"
  "org.jspecify jspecify 1.0.0"
  "org.checkerframework checker-qual 4.2.3"
  "org.jetbrains annotations 26.0.2"
  "net.kyori adventure-api 5.2.0"
  "net.kyori adventure-key 5.2.0"
  "net.kyori examination-api 1.3.0"
  "net.kyori examination-string 1.3.0"
  "net.kyori adventure-text-serializer-gson 5.2.0"
  "net.kyori adventure-text-serializer-legacy 5.2.0"
  "net.kyori adventure-text-serializer-plain 5.2.0"
  "net.kyori adventure-text-minimessage 5.2.0"
  "net.kyori adventure-text-logger-slf4j 5.2.0"
  "net.md-5 bungeecord-chat 1.21-R0.2-deprecated+build.21"
)

CP="$(find_jar io.papermc.paper paper-api 26.3.build.245-stable)"
for spec in "${DEPS[@]}"; do
  read -r group artifact version <<<"$spec"
  CP="$CP:$(find_jar "$group" "$artifact" "$version")"
done

rm -rf build && mkdir -p build/classes
find src/main/java -name '*.java' > build/sources.txt
"${JAVAC[@]}" -source 25 -target 25 -Xlint:all -Xlint:-processing -cp "$CP" -d build/classes @build/sources.txt
cp -r src/main/resources/. build/classes/
(cd build/classes && "${JAR[@]}" --create --file ../StatFacts-2.0.0.jar .)
echo "built build/StatFacts-2.0.0.jar"
