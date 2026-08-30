#!/usr/bin/env bash

set -euo pipefail

if [[ "$#" -ne 1 ]]; then
    echo "Usage: $0 /path/to/app-executable" >&2
    exit 1
fi

app_executable="$1"
if [[ ! -f "${app_executable}" ]]; then
    echo "App executable not found: ${app_executable}" >&2
    exit 1
fi

undefined_symbols="$(LC_ALL=C nm -u "${app_executable}")"
forbidden_symbols=(
    _CCCryptorGCMAddAAD
    _CCCryptorGCMDecrypt
    _CCCryptorGCMEncrypt
    _CCCryptorGCMFinal
)

for symbol in "${forbidden_symbols[@]}"; do
    if [[ "${undefined_symbols}" == *"${symbol}"* ]]; then
        echo "App Store compatibility check failed: ${symbol} is not a public iOS API." >&2
        exit 1
    fi
done

echo "App Store compatibility symbol check passed."
