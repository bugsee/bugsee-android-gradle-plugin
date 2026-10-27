#!/bin/bash
# Regenerates SwitchAlloc.class from SwitchAlloc.java (needs JDK 17+ on PATH).
set -e
cd "$(dirname "$0")"
out=$(mktemp -d)
javac --release 17 -d "$out" SwitchAlloc.java
cp "$out/SwitchAlloc.class" SwitchAlloc.class
printf '\x00\x37' | dd of=SwitchAlloc.class bs=1 seek=6 conv=notrunc 2>/dev/null
rm -rf "$out"
