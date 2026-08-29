#!/usr/bin/env bash
# Builds if needed, then runs Simple-Obfuscator with the given arguments.
# Example: ./run.sh src/filesForObfuscation src/ObfuscatedFiles --clean
set -euo pipefail
cd "$(dirname "$0")"
if [ ! -e out/Main.class ] || [ -n "$(find src -name '*.java' -newer out/Main.class 2>/dev/null)" ]; then
    mkdir -p out
    javac -d out src/*.java
fi
exec java -cp out Main "$@"
