#!/usr/bin/env bash
# Materializes workspace-tracked flow-components overlay files as symlinks
# inside the flow-components/ submodule. Idempotent.
#
# Discovers overlays by scanning flow-components-overlay/ for every
# vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/package.json.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OVERLAY_DIR="$ROOT/flow-components-overlay"
SUBMODULE_DIR="$ROOT/flow-components"

if [[ ! -d "$OVERLAY_DIR" ]]; then
    echo "error: $OVERLAY_DIR not found" >&2
    exit 1
fi

status=0
shopt -s nullglob
for source_file in "$OVERLAY_DIR"/vaadin-*-flow-parent/vaadin-*-flow-integration-tests/package.json; do
    rel_path="${source_file#"$OVERLAY_DIR/"}"
    rel_path="${rel_path%/package.json}"

    target_file="$SUBMODULE_DIR/$rel_path/package.json"
    target_dir="$(dirname "$target_file")"

    if [[ ! -d "$target_dir" ]]; then
        echo "error: missing submodule target dir: $target_dir" >&2
        status=1
        continue
    fi

    # Each rel_path has the form <parent>/<it-module> (2 segments). From
    # flow-components/<parent>/<it-module>/ we need 3 ..'s to reach workspace
    # root, then into flow-components-overlay/<parent>/<it-module>/package.json.
    link_target="../../../flow-components-overlay/$rel_path/package.json"

    if [[ -L "$target_file" ]]; then
        existing="$(readlink "$target_file")"
        if [[ "$existing" == "$link_target" ]]; then
            echo "up-to-date: $target_file"
            continue
        else
            echo "error: $target_file is a symlink pointing at $existing (expected $link_target); not overwriting" >&2
            status=1
            continue
        fi
    fi

    if [[ -e "$target_file" ]]; then
        echo "error: $target_file exists and is not a symlink; not overwriting" >&2
        status=1
        continue
    fi

    ln -s "$link_target" "$target_file"
    echo "created: $target_file -> $link_target"
done

exit $status
