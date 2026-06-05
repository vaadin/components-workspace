#!/usr/bin/env bash
# Emits a space-separated list of overlay short component names to stdout.
#
# Env overrides:
#   COMPONENTS  — space-separated short names to keep (e.g. "grid date-picker")
#
# Reads flow-components-overlay/overlays.txt by default; first positional
# argument overrides the source. Entries starting with # and blank lines are
# ignored. Lines that don't match the vaadin-*-flow-integration-tests pattern
# are dropped.

set -euo pipefail

COMPONENTS="${COMPONENTS:-}"
SOURCE="${1:-flow-components-overlay/overlays.txt}"

if [ ! -f "$SOURCE" ]; then
  echo "::error::Overlay list not found at $SOURCE" >&2
  exit 1
fi

all_names=$(tr -d '\r' < "$SOURCE" \
  | grep -vE '^[[:space:]]*(#|$)' \
  | sed -n 's,.*vaadin-\([^/]*\)-flow-integration-tests$,\1,p' \
  || true)

if [ -z "$COMPONENTS" ]; then
  printf '%s' "$all_names" | tr '\n' ' ' | sed 's/[[:space:]]*$//'
  echo
  exit 0
fi

out=""
while IFS= read -r n; do
  [ -z "$n" ] && continue
  for want in $COMPONENTS; do
    if [ "$n" = "$want" ]; then
      out="$out $n"
      break
    fi
  done
done <<< "$all_names"
echo "${out# }"
