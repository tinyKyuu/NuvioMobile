#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 2 ]]; then
  echo "Usage: $0 <base-commit> <head-commit>" >&2
  exit 2
fi

base_commit="$1"
head_commit="$2"

git rev-parse --verify "${base_commit}^{commit}" >/dev/null
git rev-parse --verify "${head_commit}^{commit}" >/dev/null
range_start="$(git merge-base "${base_commit}" "${head_commit}")"

failed=0
temporary_file="$(mktemp)"
trap 'rm -f "${temporary_file}"' EXIT

credential_pattern='-----BEGIN ([A-Z0-9]+ )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{40,}|sbp_[A-Za-z0-9_-]{20,}|AKIA[0-9A-Z]{16}|sk_(live|test)_[A-Za-z0-9]{16,}|Authorization:[[:space:]]*Bearer[[:space:]]+[A-Za-z0-9._~-]{12,}'
private_temporary_root="/private/var/"
personal_path_pattern="/Users/[A-Za-z0-9._-]+/|/home/[A-Za-z0-9._-]+/|[A-Za-z]:\\\\Users\\\\[^\\\\]+\\\\|${private_temporary_root}folders/|CoreSimulator/Devices/[0-9A-Fa-f-]{36}"

report_error() {
  local path="$1"
  local reason="$2"
  echo "Public payload check failed: ${path}: ${reason}" >&2
  failed=1
}

inspect_content() {
  local label="$1"

  if LC_ALL=C grep -IqE -- "${credential_pattern}" "${temporary_file}"; then
    report_error "${label}" "possible credential or private key"
  fi

  if LC_ALL=C grep -IqE -- "${personal_path_pattern}" "${temporary_file}"; then
    report_error "${label}" "personal machine path or simulator identifier"
  fi
}

inspect_path() {
  local commit="$1"
  local path="$2"
  local short_commit="${commit:0:12}"

  case "${path}" in
    *-HANDOFF.md|*-REVIEW.md|FEATURE_REQUEST_REVIEW.md|*/FEATURE_REQUEST_REVIEW.md|UPSTREAM_REVIEW.md|*/UPSTREAM_REVIEW.md|UPSTREAM_REVIEW_DATA.json|*/UPSTREAM_REVIEW_DATA.json|review-assets/*|*/review-assets/*)
      report_error "${path}" "internal handoff or review artifact"
      return
      ;;
    local.properties|iosApp/Configuration/Signing.local.xcconfig|.cursor/*|*/.cursor/*|.env|*/.env|.env.local|*/.env.local|*.jks|*.p8|*.p12|*.mobileprovision)
      report_error "${path}" "local configuration must not be tracked"
      return
      ;;
  esac

  if ! git show "${commit}:${path}" >"${temporary_file}" 2>/dev/null; then
    report_error "${path} at ${short_commit}" "could not inspect the changed file"
    return
  fi

  inspect_content "${path} at ${short_commit}"
}

while IFS= read -r commit; do
  while IFS= read -r -d '' path; do
    inspect_path "${commit}" "${path}"
  done < <(git diff-tree --root -m --no-commit-id --name-only --diff-filter=ACMR -r -z "${commit}")
done < <(git rev-list --reverse "${range_start}..${head_commit}")

git log --format=%B "${range_start}..${head_commit}" >"${temporary_file}"
inspect_content "commit messages"

if [[ ${failed} -ne 0 ]]; then
  echo "Remove private material and keep durable public conclusions in the PR description or feature ledger." >&2
  exit 1
fi

echo "Public payload check passed."
