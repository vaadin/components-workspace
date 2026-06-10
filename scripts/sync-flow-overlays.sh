#!/usr/bin/env bash
# Materializes workspace-tracked flow-components overlay files as symlinks
# inside the flow-components/ submodule. Idempotent.
#
# Discovers overlays by scanning flow-components-overlay/ for every
# vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/package.json.
#
# After the overlay loop, also materializes targeted per-IT workarounds for
# Flow's TaskCopyTemplateFiles: it tries to physically locate paths from
# @JsModule annotations on LitTemplate subclasses at <IT>/node_modules/<path>
# and fails under npm workspace hoisting (where deps live at the workspace
# root). Each entry below is a narrow, cycle-safe symlink pointing at the
# matching web-components source package. Add more entries if other IT
# modules trigger the same Flow build-frontend failure.
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
    # flow-components/<parent>/<it-module>/ we need 3 ..'s to reach workspace
    # root, then into flow-components-overlay/<parent>/<it-module>/package.json.
    link_or_verify "$target_dir/package.json" \
        "../../../flow-components-overlay/$rel_path/package.json" || status=1
done

# Targeted Flow TaskCopyTemplateFiles workarounds (see header comment).
# vaadin-grid IT's TemplatedColumnsPage declares
#   @JsModule("@vaadin/grid/src/vaadin-grid-column-group.js")
# on a LitTemplate subclass; Flow looks for that path under
# <IT>/node_modules/@vaadin/grid/src/... but workspace hoisting puts the
# package at <workspace-root>/web-components/packages/grid. The symlink
# below makes the lookup hit. Cycle-safe: target has no node_modules
# subdir, so tree walks bottom out cleanly.
grid_it="$SUBMODULE_DIR/vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests"
if [[ -d "$grid_it" ]]; then
    mkdir -p "$grid_it/node_modules/@vaadin"
    # From <grid_it>/node_modules/@vaadin/ go 5 ../ to reach workspace root.
    link_or_verify "$grid_it/node_modules/@vaadin/grid" \
        "../../../../../web-components/packages/grid" || status=1
fi

exit $status
