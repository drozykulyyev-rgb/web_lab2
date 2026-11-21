#!/bin/sh
set -eu
cd "$(dirname "$0")"
mkdir -p build/classes
javac --release 17 -encoding UTF-8 -d build/classes src/*.java
jar --create --file build/lab2.jar --main-class Main -C build/classes .
echo 'Built build/lab2.jar (Java 17+)'
