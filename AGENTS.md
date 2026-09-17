# Repository working agreement

These instructions apply to the entire repository.

## Product boundary

- Watch Together is the fork's defining feature.
- iOS, iPadOS, and Android mobile may also receive selected mobile, download,
  offline, reliability, and accessibility improvements.
- Desktop work should follow its chosen upstream client and add only the Watch
  Together integration needed for interoperability.
- Android TV is deferred unless the public roadmap changes.

## Before changing code

1. Read `README.md`, `CONTRIBUTING.md`, and `Docs/feature-status.md`.
2. Inspect the current branch and working tree. Preserve unrelated changes and
   untracked files.
3. Keep work focused on one approved issue or task. Use a `codex/` branch for
   new repository work unless the task specifies another existing branch.

## Public repository boundary

Treat every tracked file, commit, branch, pull request, comment, check log, and
uploaded artifact as public.

- Never commit credentials, tokens, private keys, signing material, private
  service endpoints or configuration, tester data, account identifiers,
  personal email addresses, absolute user paths, simulator or device
  identifiers, private logs, or internal review screenshots.
- Keep local configuration in ignored files such as `local.properties` and
  `iosApp/Configuration/Signing.local.xcconfig`.
- Do not add root-level handoff or review documents. Put durable public facts in
  the PR description, README, or feature ledger; keep private working notes
  outside the public payload.
- If a secret may have been committed, stop. Do not quote it in an issue or PR.
  Revoke or rotate it first, then use a private security channel to coordinate
  any history cleanup.

## Change tracking

- Update `Docs/feature-status.md` when a change affects user-visible behavior,
  platform support, validation status, upstream provenance, or a known limit.
- Update `README.md` only when the fork's current user-facing distinctions,
  scope, availability, or build instructions change.
- When upstream provides equivalent behavior, keep the historical provenance
  in the ledger and remove the item as a README differentiator.
- A merged feature must not remain labeled `In review` or `In testing` unless a
  separate, clearly identified part is still outside the stable branch.

## Pull requests

- Use the pull request template and complete every section.
- Put concise, reproducible evidence in the PR description. Do not commit a
  separate review dossier merely to support the PR.
- Run tests and builds in proportion to the changed platforms and risk. State
  what was run and what remains unverified.
- Review the full diff and changed-file list for public-safety issues before
  pushing.
- A feature task may push its branch and open a complete pull request, but it
  must not merge or release. Integration work requires independent review,
  explicit user approval of the reviewed head, and passing checks.
