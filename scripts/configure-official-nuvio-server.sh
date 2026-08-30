#!/usr/bin/env bash

set -euo pipefail

repository_root="$(cd "$(dirname "$0")/.." && pwd -P)"
properties_file="${repository_root}/local.properties"
discovery_url="${NUVIO_DISCOVERY_URL:-https://api.nuvio.tv/.well-known/nuvio}"
temporary_directory="$(mktemp -d "${TMPDIR:-/tmp}/nuvio-server-config.XXXXXX")"
trap 'rm -rf "${temporary_directory}"' EXIT

discovery_file="${temporary_directory}/discovery.json"
curl --fail --location --retry 3 --silent --show-error \
    --output "${discovery_file}" \
    "${discovery_url}"

backend_url="$(plutil -extract backend_url raw -o - "${discovery_file}")"
publishable_key="$(plutil -extract publishable_key raw -o - "${discovery_file}")"

if [[ "${backend_url}" != https://* || -z "${publishable_key}" ]]; then
    echo "Nuvio discovery returned an invalid server configuration." >&2
    exit 1
fi

temporary_properties="${temporary_directory}/local.properties"
if [[ -f "${properties_file}" ]]; then
    awk '!/^(NUVIO_IOS_DISTRIBUTION|NUVIO_SUPABASE_URL|NUVIO_SUPABASE_ANON_KEY|NUVIO_SUPABASE_FALLBACK_URL)=/' \
        "${properties_file}" > "${temporary_properties}"
fi

{
    printf '\nNUVIO_IOS_DISTRIBUTION=full\n'
    printf 'NUVIO_SUPABASE_URL=%s\n' "${backend_url}"
    printf 'NUVIO_SUPABASE_ANON_KEY=%s\n' "${publishable_key}"
    printf 'NUVIO_SUPABASE_FALLBACK_URL=\n'
} >> "${temporary_properties}"

chmod 600 "${temporary_properties}"
mv "${temporary_properties}" "${properties_file}"
echo "Configured the full iOS build for the official Nuvio account service."
