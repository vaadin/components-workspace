# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Repository Overview

This is a **workspace repository** for coordinated development across two Vaadin repositories, included as git submodules:

- `web-components/` — [vaadin/web-components](https://github.com/vaadin/web-components) (TypeScript/Lit, Yarn)
- `flow-components/` — [vaadin/flow-components](https://github.com/vaadin/flow-components) (Java Flow wrappers, Maven)

Each submodule has its own `CLAUDE.md` with build/test commands and architecture details — read those when working inside a submodule. The workspace itself contains no code; it only tracks which commits of each repo are paired together.

## When Working in a Submodule

`cd` into the submodule first. Build and test commands only work from inside the submodule directory — do not run them from the workspace root. The two submodules use unrelated toolchains (Yarn vs. Maven) and cannot be built together from the root.

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

## Workspace-Level Build

A Gradle build at the workspace root orchestrates both submodules behind a uniform task surface:

| Command | What it does |
|---|---|
| `./gradlew install` | `npm install` at the workspace root (covers web-components, its `packages/*`, and every flow-components IT module — all workspace members) + Maven warm-up (no-op) in flow-components |
| `./gradlew build` | install + `mvn -DskipTests install` in flow-components (web-components is source-published; no compile step) |
| `./gradlew test` | `mvn test` (web-components tests run via `yarn test` inside the submodule directly) |
| `./gradlew clean` | remove `node_modules/` + `mvn clean` |

Use `./gradlew :web-components:<task>` or `./gradlew :flow-components:<task>` to target a single subproject. The submodules remain independently buildable from inside their own directories — the Gradle build is additive, not a replacement.

Build files live at the workspace root (`build.gradle.kts`, `settings.gradle.kts`, `gradle/*.kts`); nothing is added inside the submodules.

flow-components currently requires Node ≤ 24 (Node 25 breaks `vaadin-charts-flow-svg-generator`'s mocha tests via a `localStorage` API change in jsdom).

### Integration-test npm workspace

The workspace root also acts as an npm workspace. All `flow-components` integration-test modules whose primary component has a `@vaadin/*` peer in `web-components/packages/` consume those packages via workspace symlinks. The per-IT-module `package.json` files live in `flow-components-overlay/<parent>/<it>/` and are symlinked into the submodule by `scripts/sync-flow-overlays.sh` (wired into `./gradlew :flow-components:syncFlowOverlays`, run automatically by `./gradlew install`). The overlay tree itself is the source of truth — `sync-flow-overlays.sh` and `overlay-component-names.sh` discover entries by scanning the directory.

To add a new IT module:

1. Run `node scripts/generate-overlays.js` — it discovers IT modules under `flow-components/`, skips ones that already have an overlay, and writes new `package.json` files for any that have `@vaadin/*` `@NpmPackage` annotations matching `web-components/packages/`.
2. Or hand-author `flow-components-overlay/vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/package.json`.
3. Run `./gradlew install`.

To exclude a non-overlaid IT module from the npm workspaces glob, add a `!`-prefixed entry to the `workspaces` array in the root `package.json`.

See `docs/superpowers/specs/2026-06-04-it-npm-workspace-design.md`.

### Gradle-native pilot modules

A subset of `flow-components` modules is built natively by Gradle (no `mvn` invocation), reading their `pom.xml` files via the included build at `gradle/plugins/`. Current pilot scope: `vaadin-flow-components-shared-parent/*` and `vaadin-button-flow-parent/*` (5 leaf modules).

Pilot subprojects share Maven's `target/` output layout and publish to Maven Local, so `./gradlew :flow-components:build` runs them via Gradle and then `mvn -DskipTests install` over the rest of the reactor; mvn skips already-built pilot work (where Gradle 8's source-set layout permits — see follow-up below).

Per-module entry points:

```bash
./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow:test
./gradlew :flow-components:vaadin-button-flow-parent:vaadin-button-flow-integration-tests:integrationTest
```

To add a new pilot module: create `gradle/flow-components/<parent>/<module>.gradle.kts` containing one line — `plugins { id("vaadin.workspace.java-library") }` (or `vaadin.workspace.integration-tests` for IT modules) — and run `./gradlew :flow-components:build`.

Design: `docs/superpowers/specs/2026-06-12-gradle-maven-bridge-design.md`. Implementation plan: `docs/superpowers/plans/2026-06-12-gradle-maven-bridge.md`.

Known follow-ups:

- Gradle 8 forbids overlapping source-set outputs, so Gradle's compiled `.class` files land in `target/classes/java/...` rather than the canonical Maven `target/classes/...`. Maven still compiles pilot modules in its install pass. Maven Local publishing remains the working complementarity mechanism.
- The `vaadin.workspace.integration-tests` plugin forces `productionMode = true` whenever the IT's POM declares `flow-maven-plugin` configuration. Dev-mode IT runs (with hot reload) are not yet reachable from Gradle.
- Pilot subproject unit tests run in both the Gradle path and Maven path of CI; this duplication is acceptable for the current pilot scope and will be pruned once Gradle parity is proven for more components.

## Cross-Repo Integration

Local npm linking between `web-components` and `flow-components` is **not** set up. `flow-components` consumes `web-components` via the npm registry. To test unreleased `web-components` changes against `flow-components`, you must publish (or link manually) — this is listed as a future workspace concern in `docs/superpowers/specs/`.

## Docs

- `docs/superpowers/specs/` — design specs for workspace-level decisions
- `docs/superpowers/plans/` — implementation plans
