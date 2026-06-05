#!/usr/bin/env bash
# Emits a space-separated list of overlay short component names to stdout.
#
# Env overrides:
#   COMPONENTS  — space-separated short names to keep (e.g. "grid date-picker")
#
# Reads flow-components-overlay/overlays.txt by default; first positional
# argument overrides the source. Entries starting with # and blank lines are
# ignored.

set -euo pipefail

COMPONENTS="${COMPONENTS:-}"
SOURCE="${1:-flow-components-overlay/overlays.txt}"

if [ ! -f "$SOURCE" ]; then
  echo "::error::Overlay list not found at $SOURCE" >&2
  exit 1
fi

all_names=$(grep -vE '^[[:space:]]*(#|$)' "$SOURCE" \
  | sed 's,.*vaadin-\(.*\)-flow-integration-tests$,\1,')

if [ -z "$COMPONENTS" ]; then
  echo $all_names
  exit 0
fi

out=""
for n in $all_names; do
  for want in $COMPONENTS; do
    if [ "$n" = "$want" ]; then
      out="$out $n"
      break
    fi
  done
done
echo ${out# }
