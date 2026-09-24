#!/usr/bin/env bash
# Applies web-components/patches/*.patch to the workspace-root node_modules.
# Idempotent: `-N` skips already-applied hunks; `-r /dev/null` suppresses
# .rej files (which would otherwise pollute node_modules on already-applied
# state). Resolves the workspace root from the script's own location, so it
# can be run from any cwd.
set -euo pipefail
cd "$(dirname "$0")/.."
for p in web-components/patches/*.patch; do
  echo "Applying $p"
  # patch exit codes: 0 = clean apply, 1 = hunks already applied or rejected,
  # >1 = fatal error. With -N + -r /dev/null we treat 0 and 1 as success.
  rc=0
  patch -p1 -d . -N -r /dev/null < "$p" || rc=$?
  if [ "$rc" -gt 1 ]; then
    echo "ERROR: patch failed with exit code $rc for $p" >&2
    exit "$rc"
  fi
done
# BSD patch (macOS) may ignore -r /dev/null for some hunks and write .rej files
# anyway. Remove any that ended up in node_modules so they don't pollute caches.
find node_modules -name '*.rej' -delete
