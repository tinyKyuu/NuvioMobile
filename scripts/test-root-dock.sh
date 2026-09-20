#!/usr/bin/env bash
set -euo pipefail
repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
cd "$repository_root"
mkdir -p build/root-dock-tests
xcrun swiftc iosApp/iosApp/AdaptiveDockLayout.swift scripts/root-dock-tests/main.swift \
    -o build/root-dock-tests/root-dock-tests
build/root-dock-tests/root-dock-tests
