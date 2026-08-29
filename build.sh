#!/usr/bin/env bash
# Compiles Simple-Obfuscator into ./out
set -euo pipefail
cd "$(dirname "$0")"
mkdir -p out
javac -d out src/*.java
echo "Built. Run with: java -cp out Main --help"
