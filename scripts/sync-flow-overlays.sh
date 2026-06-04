#!/usr/bin/env bash
# Materializes workspace-tracked flow-components overlay files as symlinks
# inside the flow-components/ submodule. Idempotent.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OVERLAY_DIR="$ROOT/flow-components-overlay"
SUBMODULE_DIR="$ROOT/flow-components"
OVERLAYS_FILE="$OVERLAY_DIR/overlays.txt"

if [[ ! -f "$OVERLAYS_FILE" ]]; then
    echo "error: $OVERLAYS_FILE not found" >&2
    exit 1
fi

status=0
while IFS= read -r rel_path || [[ -n "$rel_path" ]]; do
    # Skip blank lines and comments.
    [[ -z "$rel_path" || "$rel_path" =~ ^# ]] && continue

    source_file="$OVERLAY_DIR/$rel_path/package.json"
    target_file="$SUBMODULE_DIR/$rel_path/package.json"
    target_dir="$(dirname "$target_file")"

    if [[ ! -f "$source_file" ]]; then
        echo "error: missing overlay source: $source_file" >&2
        status=1
        continue
    fi

    if [[ ! -d "$target_dir" ]]; then
        echo "error: missing submodule target dir: $target_dir" >&2
        status=1
        continue
    fi

    # Compute symlink target as a relative path from target_dir back to source_file.
    # Each rel_path has the form <parent>/<it-module>, i.e., 2 segments. From
    # flow-components/<parent>/<it-module>/ we need 3 ..'s to reach workspace root,
    # then into flow-components-overlay/<parent>/<it-module>/package.json.
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
done < "$OVERLAYS_FILE"

exit $status
