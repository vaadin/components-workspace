#!/usr/bin/env bash
# Emits a space-separated list of overlay short component names to stdout.
#
# Discovers overlays by scanning the overlay directory for every
# vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/package.json
# and extracting <name>.
#
# Env overrides:
#   COMPONENTS  — space-separated short names to keep (e.g. "grid date-picker")
#
# First positional argument overrides the overlay directory
# (default flow-components-overlay).

set -euo pipefail

COMPONENTS="${COMPONENTS:-}"
SOURCE_DIR="${1:-flow-components-overlay}"

if [ ! -d "$SOURCE_DIR" ]; then
  echo "::error::Overlay directory not found at $SOURCE_DIR" >&2
  exit 1
fi

# Discover all overlay short names, sorted alphabetically. nullglob lets the
# loop skip cleanly when no overlays exist.
shopt -s nullglob
all_names=""
for pkg in "$SOURCE_DIR"/vaadin-*-flow-parent/vaadin-*-flow-integration-tests/package.json; do
  it_dir="${pkg%/package.json}"
  it_name="${it_dir##*/}"               # vaadin-<name>-flow-integration-tests
  short="${it_name#vaadin-}"
  short="${short%-flow-integration-tests}"
  all_names+="$short"$'\n'
done
all_names=$(printf '%s' "$all_names" | sort -u)

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
