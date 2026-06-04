# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

This is a **workspace repository** for coordinated development across two Vaadin repositories, included as git submodules:

- `web-components/` — [vaadin/web-components](https://github.com/vaadin/web-components) (TypeScript/Lit, pnpm/yarn)
- `flow-components/` — [vaadin/flow-components](https://github.com/vaadin/flow-components) (Java Flow wrappers, Maven)

Each submodule has its own `CLAUDE.md` with build/test commands and architecture details — read those when working inside a submodule. The workspace itself contains no code; it only tracks which commits of each repo are paired together.

## When Working in a Submodule

`cd` into the submodule first. Build and test commands only work from inside the submodule directory — do not run them from the workspace root. The two submodules use unrelated toolchains (pnpm/yarn vs. Maven) and cannot be built together from the root.

## Submodule Workflows

Submodules are configured with `branch = main` tracking in `.gitmodules`, so checkouts inside a submodule stay on a real branch rather than detaching HEAD.

```bash
# Initialize after fresh clone without --recurse-submodules
git submodule update --init

# Pull latest main on both submodules
git submodule update --remote --merge

# Check which commit each submodule points to
git submodule status
```

### Recording paired branches

When working on a cross-repo feature, switch each submodule to its feature branch, then commit the pointer change in the workspace so the pairing is recorded:

```bash
cd web-components && git checkout feature/my-feature && cd ..
cd flow-components && git checkout feature/my-feature && cd ..
git add web-components flow-components
git commit -m "Point submodules to feature branches"
```

The workspace commit records the submodule SHAs, not the branch names — the branch tracking only affects `git submodule update --remote`.

## Cross-Repo Integration

Local npm linking between `web-components` and `flow-components` is **not** set up. `flow-components` consumes `web-components` via the npm registry. To test unreleased `web-components` changes against `flow-components`, you must publish (or link manually) — this is listed as a future workspace concern in `docs/superpowers/specs/`.

## Docs

- `docs/superpowers/specs/` — design specs for workspace-level decisions
- `docs/superpowers/plans/` — implementation plans