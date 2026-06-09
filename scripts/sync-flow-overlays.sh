#!/usr/bin/env bash
# Materializes workspace-tracked flow-components overlay files as symlinks
# inside the flow-components/ submodule. Idempotent.
#
# For each overlay <parent>/<it-module>, creates two symlinks inside the
# submodule:
#   1. package.json → ../../../flow-components-overlay/<parent>/<it-module>/package.json
#   2. node_modules → ../../../node_modules
#
# The node_modules symlink redirects Flow's per-module frontend lookup at
# `<it-module>/node_modules/...` to the workspace-root node_modules where
# npm hoists every workspace member's deps. Without it, flow-maven-plugin's
# `build-frontend` goal runs `npm install` inside the IT module (no-op in a
# workspace member), then fails the subsequent pre-bundle scan with
# "Failed to find the following imports in the `node_modules` tree".
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

# Create or verify a single symlink. Idempotent: leaves an existing symlink
# alone if it already points at link_target, errors out if the file exists
# but is something else.
link_or_verify() {
    local target_file=$1 link_target=$2

    if [[ -L "$target_file" ]]; then
        local existing
        existing="$(readlink "$target_file")"
        if [[ "$existing" == "$link_target" ]]; then
            echo "up-to-date: $target_file"
            return 0
        fi
        echo "error: $target_file is a symlink pointing at $existing (expected $link_target); not overwriting" >&2
        return 1
    fi

    if [[ -e "$target_file" ]]; then
        echo "error: $target_file exists and is not a symlink; not overwriting" >&2
        return 1
    fi

    ln -s "$link_target" "$target_file"
    echo "created: $target_file -> $link_target"
}

shopt -s nullglob
for source_file in "$OVERLAY_DIR"/vaadin-*-flow-parent/vaadin-*-flow-integration-tests/package.json; do
    rel_path="${source_file#"$OVERLAY_DIR/"}"
    rel_path="${rel_path%/package.json}"

    target_dir="$SUBMODULE_DIR/$rel_path"
    if [[ ! -d "$target_dir" ]]; then
        echo "error: missing submodule target dir: $target_dir" >&2
        status=1
        continue
    fi

    # Each rel_path has the form <parent>/<it-module> (2 segments). From
    # flow-components/<parent>/<it-module>/ three ..'s reach workspace root.
    link_or_verify "$target_dir/package.json" \
        "../../../flow-components-overlay/$rel_path/package.json" || status=1
    link_or_verify "$target_dir/node_modules" "../../../node_modules" || status=1
done

exit $status
