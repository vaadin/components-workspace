# flow-components Overlay Tree

This directory contains workspace-tracked `package.json` files that get
symlinked into the `flow-components/` submodule by `scripts/sync-flow-overlays.sh`.

Each subdirectory mirrors the corresponding submodule path so the mapping
between overlay file and target location is obvious from the layout alone.

The symlinks created inside `flow-components/` are caught by the submodule's
own `.gitignore` (`**/package*json`), so `git status` inside the submodule
stays clean.

See `docs/superpowers/specs/2026-06-04-it-npm-workspace-design.md` for the
full design rationale.
