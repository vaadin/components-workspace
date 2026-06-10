# web-components Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add three sibling jobs (`wc-verify`, `wc-unit`, `wc-visual`) to the workspace `.github/workflows/validation.yml` so every workspace PR validates the `web-components` submodule alongside `flow-components`. Reduce the IT shard cap from 12 to 6 to keep total parallel job count in budget. Open the PR from the current branch (`ci/wc-validation`) and use its own pipeline run as the verification.

**Architecture:** Five focused commits on `ci/wc-validation`. Commit 1 is a one-line change in `scripts/compute-it-matrix.sh` (default `MAX_SHARDS` 12→6) plus an updated assertion in `scripts/test-compute-it-matrix.sh`. Commits 2–4 each add one new job to `validation.yml`. Commit 5 wires the new jobs into the `results` aggregator's `needs:` list and trailing failure check. No submodule changes. No README or branch-protection changes — `Collect results` already gates merge and inherits the new jobs via `needs:`. Verification happens on the live PR; we merge the same PR after the run goes green.

**Tech Stack:**
- GitHub Actions workflow YAML (`.github/workflows/validation.yml`)
- Bash matrix script (`scripts/compute-it-matrix.sh`) + its existing unit-test harness (`scripts/test-compute-it-matrix.sh`)
- `yarn` from inside `web-components/` (lint, snapshot, integration, unit, visual suites)
- `nick-fields/retry@v3` for visual-test retries
- `mcr.microsoft.com/playwright:v1.60.0-noble` container image (for `wc-visual` only)
- `actions/cache/restore@v5` with the existing install cache (already covers `web-components/node_modules` and `.yarn`)

**Reference spec:** `docs/superpowers/specs/2026-06-10-wc-validation-design.md`

**Branch:** `ci/wc-validation` (current; spec commit `66886e3` is its tip).

---

## Pre-flight check

Run from the workspace root before starting Task 1:

```bash
git status                                                  # clean tree on ci/wc-validation
git log --oneline -1                                        # 66886e3 docs: clarify wc-validation spec ...
gh auth status                                              # repo scope, for the PR push later
test -x scripts/compute-it-matrix.sh && echo "matrix ok"
test -x scripts/test-compute-it-matrix.sh && echo "test ok"
test -f .github/workflows/validation.yml && echo "wf ok"
which jq && echo "jq ok"                                    # used by test harness
which bash && bash --version | head -1                      # bash 4+ recommended (mapfile)
```

All eight must succeed. On macOS, system `/bin/bash` is 3.2 and the test harness picks up `$BASH` instead — confirm `bash --version` reports 4 or 5 (typically Homebrew bash at `/opt/homebrew/bin/bash`).

If `gh auth status` is not authenticated, run `gh auth login` and complete the browser flow.

---

### Task 1: Reduce IT shard cap from 12 to 6

The change is one line of source plus one line of the existing unit test. The unit test fails first (TDD), then we flip the default to make it pass.

**Files:**
- Modify: `scripts/test-compute-it-matrix.sh:120` (change asserted cap from 12 to 6)
- Modify: `scripts/compute-it-matrix.sh:18` (flip `MAX_SHARDS` default 12 → 6)
- Modify: `scripts/compute-it-matrix.sh:8` (update header comment so it reads "default 6")

- [ ] **Step 1: Run the existing test suite to confirm baseline green**

```bash
bash scripts/test-compute-it-matrix.sh
```

Expected: every `ok:` line prints; the script exits 0. If the baseline is red, STOP and fix the existing failure before changing anything.

- [ ] **Step 2: Update the cap assertion in the test to expect 6 (the failing test)**

Using Edit tooling on `scripts/test-compute-it-matrix.sh`:

`old_string`:
```bash
# Case 7: many small modules cap at MAX_SHARDS=12.
specs=()
for ((i=1; i<=20; i++)); do specs+=("c$i:30"); done
root=$(make_root "${specs[@]}")
names=""
for ((i=1; i<=20; i++)); do names+="c$i "; done
out=$(echo "$names" | "$BASH" "$SCRIPT" "$root")
n=$(echo "$out" | jq -r '.include | length')
[ "$n" = "12" ] || fail "20-module 30-each: got $n shards (want 12)"
```

`new_string`:
```bash
# Case 7: many small modules cap at MAX_SHARDS=6 (default).
specs=()
for ((i=1; i<=20; i++)); do specs+=("c$i:30"); done
root=$(make_root "${specs[@]}")
names=""
for ((i=1; i<=20; i++)); do names+="c$i "; done
out=$(echo "$names" | "$BASH" "$SCRIPT" "$root")
n=$(echo "$out" | jq -r '.include | length')
[ "$n" = "6" ] || fail "20-module 30-each: got $n shards (want 6)"
```

Also update the `pass` line three lines below — `old_string`:
```bash
pass "20 modules at 30 ITs each -> 12 shards covering all modules"
```

`new_string`:
```bash
pass "20 modules at 30 ITs each -> 6 shards covering all modules"
```

- [ ] **Step 3: Run the test suite to confirm Case 7 now fails (TDD red)**

```bash
bash scripts/test-compute-it-matrix.sh
```

Expected: the script exits non-zero with `FAIL: 20-module 30-each: got 12 shards (want 6)`. This is the expected red state. If it instead exits 0, the test edit did not take effect — re-do Step 2.

- [ ] **Step 4: Flip the `MAX_SHARDS` default in the matrix script**

Using Edit tooling on `scripts/compute-it-matrix.sh`:

`old_string`:
```bash
#   MAX_SHARDS        — default 12 (hard cap on parallel shards)
```

`new_string`:
```bash
#   MAX_SHARDS        — default 6 (hard cap on parallel shards)
```

Then a second edit, `old_string`:
```bash
MAX_SHARDS="${MAX_SHARDS:-12}"
```

`new_string`:
```bash
MAX_SHARDS="${MAX_SHARDS:-6}"
```

- [ ] **Step 5: Run the test suite to confirm Case 7 now passes (TDD green)**

```bash
bash scripts/test-compute-it-matrix.sh
```

Expected: every `ok:` line prints; the script exits 0. Pay particular attention to:
- `ok: 20 modules at 30 ITs each -> 6 shards covering all modules` (Case 7, the one we just touched)
- `ok: MAX_SHARDS env override respected` (Case 8, must still pass — proves the env override path still works)
- `ok: MAX_SHARDS=0 rejected` (Case 10, must still pass — proves input validation is unchanged)

If any case fails, STOP and debug before continuing.

- [ ] **Step 6: Sanity-check live behavior against the real overlay set**

```bash
bash scripts/overlay-component-names.sh \
  | TARGET_PER_SHARD=1 bash scripts/compute-it-matrix.sh \
  | jq -r '.include | length'
```

The overlay directory currently lists ~48 components. With `TARGET_PER_SHARD=1`, `n = ceil(total_classes / 1)` blows past the cap, and the default cap pins the result to `6`.

Expected: `6`.

If you see `12`, the default change in Step 4 did not actually land. If you see anything else, investigate before continuing.

- [ ] **Step 7: Inspect the diff and commit**

```bash
git diff scripts/compute-it-matrix.sh scripts/test-compute-it-matrix.sh
```

Expected: three small hunks (one in the test, two in the matrix script — comment + value). No other lines change.

```bash
git add scripts/compute-it-matrix.sh scripts/test-compute-it-matrix.sh
git commit -m "$(cat <<'EOF'
ci: reduce IT shard cap default from 12 to 6

Frees parallel-job budget for the new wc-verify/wc-unit/wc-visual
jobs introduced in the next commits. The cap matters only once the
overlay set grows past ~210 IT classes; until then the matrix
shrinks below the cap regardless.

Updates the unit-test assertion to match the new default; the
MAX_SHARDS env override path is unchanged and still tested.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit created. `git log --oneline -1` shows the new commit.

---

### Task 2: Add the `wc-verify` job

Adds the first of three new jobs. It runs `yarn lint`, then `yarn test:snapshots`, then `yarn test:it` inside `web-components/`. Cache-reuse via the existing install cache.

**Files:**
- Modify: `.github/workflows/validation.yml` (insert a new job block between the `its` job's end and the `results` job's start)

- [ ] **Step 1: Locate the insertion point**

```bash
grep -n "^  its:\|^  results:" .github/workflows/validation.yml
```

Expected: two matches. `its:` at line ~214 and `results:` at line ~309. The new block goes between line 307 (last line of the `its` job, the `if-no-files-found: ignore` of the screenshot upload) and line 309 (`  results:`).

- [ ] **Step 2: Insert the `wc-verify` block**

Using Edit tooling on `.github/workflows/validation.yml`:

`old_string`:
```yaml
      - name: Upload error screenshots
        if: failure()
        uses: actions/upload-artifact@v6
        with:
          name: error-screenshots-${{ steps.shardid.outputs.value }}
          path: flow-components/**/error-screenshots/
          retention-days: 5
          if-no-files-found: ignore

  results:
```

`new_string`:
```yaml
      - name: Upload error screenshots
        if: failure()
        uses: actions/upload-artifact@v6
        with:
          name: error-screenshots-${{ steps.shardid.outputs.value }}
          path: flow-components/**/error-screenshots/
          retention-days: 5
          if-no-files-found: ignore

  wc-verify:
    name: WC Verify
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v6
        with:
          submodules: recursive
          fetch-depth: 0

      - name: Setup Node
        uses: actions/setup-node@v6
        with:
          node-version: '24'

      - name: Restore install cache
        uses: actions/cache/restore@v5
        with:
          key: ${{ needs.install.outputs.cache-key }}
          path: |
            ~/.m2/repository/com/vaadin
            node_modules
            web-components/node_modules
            web-components/.yarn
            flow-components/**/node_modules
            flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
          fail-on-cache-miss: true

      - name: Lint
        working-directory: web-components
        run: yarn lint

      - name: Snapshot tests
        working-directory: web-components
        env:
          COMPONENTS: ${{ inputs.components }}
        run: |
          if [ -z "${COMPONENTS:-}" ]; then
            yarn test:snapshots --all
          else
            for c in $COMPONENTS; do
              yarn test:snapshots --group "$c"
            done
          fi

      - name: Integration tests
        working-directory: web-components
        env:
          COMPONENTS: ${{ inputs.components }}
        run: |
          if [ -z "${COMPONENTS:-}" ]; then
            yarn test:it --all
          else
            for c in $COMPONENTS; do
              yarn test:it --group "$c"
            done
          fi

  results:
```

- [ ] **Step 3: Validate the YAML parses**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

Expected: `yaml ok`. If a `yaml.YAMLError` traceback prints, indentation or a missing colon broke parsing — revisit Step 2.

- [ ] **Step 4: Inspect the diff**

```bash
git diff .github/workflows/validation.yml
```

Expected: a single hunk that inserts ~50 lines starting with `  wc-verify:` and ending right before `  results:`. No other lines change.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "$(cat <<'EOF'
ci: add wc-verify job for web-components lint + snapshot + integration

Mirrors upstream web-components/.github/workflows/verify.yml inside
the workspace pipeline so every workspace PR runs yarn lint,
yarn test:snapshots, and yarn test:it from web-components/.

Reuses the existing install cache (no separate yarn install step).
Honors the workflow_dispatch components input via --group; lint is
unscoped because yarn lint has no per-component filter.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit created. `git log --oneline -1` shows the new commit.

---

### Task 3: Add the `wc-unit` job (matrix on browser)

Adds the second new job. Three matrix entries: chrome, firefox, webkit. Firefox and WebKit require a `playwright install --with-deps` step.

**Files:**
- Modify: `.github/workflows/validation.yml` (insert a second new job block immediately after `wc-verify`)

- [ ] **Step 1: Insert the `wc-unit` block right after `wc-verify`**

Using Edit tooling on `.github/workflows/validation.yml`:

`old_string`:
```yaml
      - name: Integration tests
        working-directory: web-components
        env:
          COMPONENTS: ${{ inputs.components }}
        run: |
          if [ -z "${COMPONENTS:-}" ]; then
            yarn test:it --all
          else
            for c in $COMPONENTS; do
              yarn test:it --group "$c"
            done
          fi

  results:
```

`new_string`:
```yaml
      - name: Integration tests
        working-directory: web-components
        env:
          COMPONENTS: ${{ inputs.components }}
        run: |
          if [ -z "${COMPONENTS:-}" ]; then
            yarn test:it --all
          else
            for c in $COMPONENTS; do
              yarn test:it --group "$c"
            done
          fi

  wc-unit:
    name: WC Unit (${{ matrix.browser }})
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 30
    strategy:
      fail-fast: false
      matrix:
        include:
          - browser: chrome
            cmd: yarn test
          - browser: firefox
            cmd: yarn test:firefox
            playwright: firefox
          - browser: webkit
            cmd: yarn test:webkit
            playwright: webkit
    steps:
      - uses: actions/checkout@v6
        with:
          submodules: recursive
          fetch-depth: 0

      - name: Setup Node
        uses: actions/setup-node@v6
        with:
          node-version: '24'

      - name: Restore install cache
        uses: actions/cache/restore@v5
        with:
          key: ${{ needs.install.outputs.cache-key }}
          path: |
            ~/.m2/repository/com/vaadin
            node_modules
            web-components/node_modules
            web-components/.yarn
            flow-components/**/node_modules
            flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
          fail-on-cache-miss: true

      - name: Install Playwright browser
        if: matrix.playwright
        working-directory: web-components
        run: npx playwright install ${{ matrix.playwright }} --with-deps

      - name: Run unit tests
        working-directory: web-components
        env:
          COMPONENTS: ${{ inputs.components }}
          CMD: ${{ matrix.cmd }}
        run: |
          if [ -z "${COMPONENTS:-}" ]; then
            $CMD --all
          else
            for c in $COMPONENTS; do
              $CMD --group "$c"
            done
          fi

  results:
```

- [ ] **Step 2: Validate the YAML parses**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

Expected: `yaml ok`.

- [ ] **Step 3: Inspect the diff**

```bash
git diff .github/workflows/validation.yml
```

Expected: a single hunk that inserts ~50 lines, starting with `  wc-unit:` and ending right before `  results:`. The previously-inserted `wc-verify:` block is unchanged.

- [ ] **Step 4: Spot-check matrix expansion (sanity)**

```bash
python3 - <<'PY'
import yaml
with open('.github/workflows/validation.yml') as f:
    wf = yaml.safe_load(f)
wc_unit = wf['jobs']['wc-unit']
print('strategy:', wc_unit['strategy'])
print('matrix entries:', len(wc_unit['strategy']['matrix']['include']))
PY
```

Expected output:
```
strategy: {'fail-fast': False, 'matrix': {'include': [...]}}
matrix entries: 3
```

If `matrix entries` is not 3, the YAML is malformed in a parser-accepting but semantically-wrong way (e.g. mis-indented `include` entries).

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "$(cat <<'EOF'
ci: add wc-unit matrix job for web-components unit tests

Three matrix entries (chrome, firefox, webkit) mirror upstream
web-components/.github/workflows/unit-tests.yml. Chrome uses the
runner's pre-installed Chromium; Firefox and WebKit each add a
playwright install --with-deps step before tests run.

fail-fast: false so a single-browser flake does not mask the others.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit created.

---

### Task 4: Add the `wc-visual` job

Adds the third new job. Runs inside the Playwright docker image, fork-gated, with retries on each theme. Uploads failed-screenshot artifacts on failure.

**Files:**
- Modify: `.github/workflows/validation.yml` (insert a third new job block immediately after `wc-unit`)

- [ ] **Step 1: Insert the `wc-visual` block right after `wc-unit`**

Using Edit tooling on `.github/workflows/validation.yml`:

`old_string`:
```yaml
      - name: Run unit tests
        working-directory: web-components
        env:
          COMPONENTS: ${{ inputs.components }}
          CMD: ${{ matrix.cmd }}
        run: |
          if [ -z "${COMPONENTS:-}" ]; then
            $CMD --all
          else
            for c in $COMPONENTS; do
              $CMD --group "$c"
            done
          fi

  results:
```

`new_string`:
```yaml
      - name: Run unit tests
        working-directory: web-components
        env:
          COMPONENTS: ${{ inputs.components }}
          CMD: ${{ matrix.cmd }}
        run: |
          if [ -z "${COMPONENTS:-}" ]; then
            $CMD --all
          else
            for c in $COMPONENTS; do
              $CMD --group "$c"
            done
          fi

  wc-visual:
    name: WC Visual
    needs: install
    runs-on: ubuntu-latest
    timeout-minutes: 90
    if: github.repository_owner == 'vaadin'
    container:
      image: mcr.microsoft.com/playwright:v1.60.0-noble
      options: --ipc=host
    env:
      PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD: 1
    steps:
      - uses: actions/checkout@v6
        with:
          submodules: recursive
          fetch-depth: 0

      - name: Fix git safe directory
        run: git config --global --add safe.directory $GITHUB_WORKSPACE

      - name: Fetch origin/main
        run: git fetch origin main

      - name: Setup Node
        uses: actions/setup-node@v6
        with:
          node-version: '24'

      - name: Restore install cache
        uses: actions/cache/restore@v5
        with:
          key: ${{ needs.install.outputs.cache-key }}
          path: |
            ~/.m2/repository/com/vaadin
            node_modules
            web-components/node_modules
            web-components/.yarn
            flow-components/**/node_modules
            flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated
          fail-on-cache-miss: true

      - name: Visual tests — base
        uses: nick-fields/retry@v3
        with:
          timeout_minutes: 20
          retry_wait_seconds: 60
          max_attempts: 3
          command: |
            cd web-components
            if [ -z "${COMPONENTS:-}" ]; then
              yarn test:base --all
            else
              for c in $COMPONENTS; do yarn test:base --group "$c"; done
            fi
        env:
          COMPONENTS: ${{ inputs.components }}

      - name: Visual tests — Lumo
        uses: nick-fields/retry@v3
        with:
          timeout_minutes: 20
          retry_wait_seconds: 60
          max_attempts: 3
          command: |
            cd web-components
            if [ -z "${COMPONENTS:-}" ]; then
              yarn test:lumo --all
            else
              for c in $COMPONENTS; do yarn test:lumo --group "$c"; done
            fi
        env:
          COMPONENTS: ${{ inputs.components }}

      - name: Visual tests — Aura
        uses: nick-fields/retry@v3
        with:
          timeout_minutes: 20
          retry_wait_seconds: 60
          max_attempts: 3
          command: |
            cd web-components
            if [ -z "${COMPONENTS:-}" ]; then
              yarn test:aura --all
            else
              for c in $COMPONENTS; do yarn test:aura --group "$c"; done
            fi
        env:
          COMPONENTS: ${{ inputs.components }}

      - name: Upload failed screenshots
        if: failure()
        uses: actions/upload-artifact@v6
        with:
          name: wc-visual-screenshots
          path: |
            web-components/packages/*/test/visual/base/screenshots/*/failed/*.png
            web-components/packages/*/test/visual/lumo/screenshots/*/failed/*.png
            web-components/packages/*/test/visual/aura/screenshots/dark/*/failed/*.png
            web-components/packages/*/test/visual/aura/screenshots/default/*/failed/*.png
            web-components/packages/vaadin-lumo-styles/test/visual/screenshots/failed/*.png
          retention-days: 5
          if-no-files-found: ignore

  results:
```

- [ ] **Step 2: Validate the YAML parses**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

Expected: `yaml ok`.

- [ ] **Step 3: Spot-check the container declaration**

```bash
python3 - <<'PY'
import yaml
with open('.github/workflows/validation.yml') as f:
    wf = yaml.safe_load(f)
wc_visual = wf['jobs']['wc-visual']
print("if:", wc_visual.get('if'))
print("container:", wc_visual.get('container'))
print("step count:", len(wc_visual['steps']))
PY
```

Expected output:
```
if: github.repository_owner == 'vaadin'
container: {'image': 'mcr.microsoft.com/playwright:v1.60.0-noble', 'options': '--ipc=host'}
step count: 9
```

If `step count` differs from 9, a step block was lost or merged into a sibling — re-do Step 1.

- [ ] **Step 4: Inspect the diff**

```bash
git diff .github/workflows/validation.yml
```

Expected: a single hunk inserting ~90 lines starting with `  wc-visual:` and ending right before `  results:`. The two previously-inserted jobs are unchanged.

- [ ] **Step 5: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "$(cat <<'EOF'
ci: add wc-visual job for web-components visual regression

Runs base, Lumo, and Aura visual tests sequentially inside the
Playwright docker container that upstream visual-tests.yml uses.
Each theme is wrapped in nick-fields/retry@v3 to absorb visual
flakes, matching upstream behavior. Fork-gated via
github.repository_owner == 'vaadin' since fork PRs cannot run
against the workspace runner image reliably.

On failure, uploads failed-screenshot artifacts so reviewers can
inspect the diff without re-running the job locally.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit created.

---

### Task 5: Wire the new jobs into the `results` aggregator

Two surgical edits in the existing `results` job: extend `needs:` and extend the trailing failure check.

**Files:**
- Modify: `.github/workflows/validation.yml` (the `needs:` list of `results` and its final "Fail if any test failed" step)

- [ ] **Step 1: Extend the `results.needs:` list**

Using Edit tooling on `.github/workflows/validation.yml`:

`old_string`:
```yaml
  results:
    name: Collect results
    needs: [install, unit, wtr, its]
```

`new_string`:
```yaml
  results:
    name: Collect results
    needs: [install, unit, wtr, its, wc-verify, wc-unit, wc-visual]
```

- [ ] **Step 2: Extend the trailing "Fail if any test failed" step**

Using Edit tooling on `.github/workflows/validation.yml`:

`old_string`:
```yaml
      - name: Fail if any test failed
        if: always()
        run: |
          failed=false
          [[ "${{ steps.unit-dorny.outputs.conclusion }}" == "failure" ]] && failed=true
          [[ "${{ steps.wtr-dorny.outputs.conclusion }}"  == "failure" ]] && failed=true
          [[ "${{ steps.it-dorny.outputs.conclusion }}"   == "failure" ]] && failed=true
          [[ "$failed" == "true" ]] && exit 1 || exit 0
```

`new_string`:
```yaml
      - name: Fail if any test failed
        if: always()
        run: |
          failed=false
          [[ "${{ steps.unit-dorny.outputs.conclusion }}" == "failure" ]] && failed=true
          [[ "${{ steps.wtr-dorny.outputs.conclusion }}"  == "failure" ]] && failed=true
          [[ "${{ steps.it-dorny.outputs.conclusion }}"   == "failure" ]] && failed=true
          [[ "${{ needs.wc-verify.result }}" == "failure" ]] && failed=true
          [[ "${{ needs.wc-unit.result }}"   == "failure" ]] && failed=true
          [[ "${{ needs.wc-visual.result }}" == "failure" ]] && failed=true
          [[ "$failed" == "true" ]] && exit 1 || exit 0
```

`needs.<matrix-job>.result` is `failure` if any matrix entry in `wc-unit` failed; `skipped` (the case for `wc-visual` on a fork PR) is intentionally not treated as a failure.

- [ ] **Step 3: Validate the YAML parses**

```bash
python3 -c "import yaml; yaml.safe_load(open('.github/workflows/validation.yml'))" && echo "yaml ok"
```

Expected: `yaml ok`.

- [ ] **Step 4: Spot-check the wired-up `needs:` list**

```bash
python3 - <<'PY'
import yaml
with open('.github/workflows/validation.yml') as f:
    wf = yaml.safe_load(f)
print(wf['jobs']['results']['needs'])
PY
```

Expected: `['install', 'unit', 'wtr', 'its', 'wc-verify', 'wc-unit', 'wc-visual']`.

- [ ] **Step 5: Inspect the diff**

```bash
git diff .github/workflows/validation.yml
```

Expected: two hunks. One in the `results:` header (extends `needs:`), one in the "Fail if any test failed" step (adds three lines). No other lines change.

- [ ] **Step 6: Commit**

```bash
git add .github/workflows/validation.yml
git commit -m "$(cat <<'EOF'
ci: gate Collect results on wc-verify/wc-unit/wc-visual conclusions

Extends results.needs with the three new web-components jobs and
extends the trailing failure check to consult their conclusions
directly (no dorny step exists for them by design). needs.wc-visual
being 'skipped' on fork PRs is not treated as a failure, so forks
still see Collect results green when only visual is skipped.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit created.

---

### Task 6: Push the branch and open the PR

**Files:** (no source changes)

- [ ] **Step 1: Review the full set of new commits**

```bash
git log --oneline main..HEAD
```

Expected: five new commits on top of `main`:

```
<sha> ci: gate Collect results on wc-verify/wc-unit/wc-visual conclusions
<sha> ci: add wc-visual job for web-components visual regression
<sha> ci: add wc-unit matrix job for web-components unit tests
<sha> ci: add wc-verify job for web-components lint + snapshot + integration
<sha> ci: reduce IT shard cap default from 12 to 6
```

(Plus the two prior spec commits — `66886e3` and `68f320c` — for a total of 7 ahead of `main`.) If the commit order or count differs, STOP and investigate; do not push a mixed-up branch.

- [ ] **Step 2: Push the branch**

```bash
git push -u origin ci/wc-validation
```

Expected: branch pushed. If `-u` was set on a prior push, plain `git push` is fine too.

- [ ] **Step 3: Create the PR**

```bash
gh pr create --base main --head ci/wc-validation \
  --title "ci: add web-components validation jobs" \
  --body "$(cat <<'EOF'
Extends the workspace validation workflow to validate the
\`web-components\` submodule alongside the existing
flow-components-side jobs. Adds three sibling jobs:

- \`wc-verify\` — \`yarn lint\` + \`yarn test:snapshots\` + \`yarn test:it\`
- \`wc-unit\` — matrix on chrome/firefox/webkit
- \`wc-visual\` — base/Lumo/Aura inside the Playwright docker image, fork-gated

Reduces the IT shard cap from 12 to 6 to keep total parallel job
count in budget; the cap only kicks in once the overlay set grows
past ~210 IT classes.

The PR is its own verification: every checklist criterion in the
spec's §Verification section is checked against this PR's pipeline
run before merge.

Design: \`docs/superpowers/specs/2026-06-10-wc-validation-design.md\`
Plan: \`docs/superpowers/plans/2026-06-10-wc-validation.md\`

---

🤖 Generated with Claude Code
EOF
)"
```

Expected: a PR URL printed to stdout. Save the URL for Task 7.

- [ ] **Step 4: Capture the PR URL**

```bash
gh pr view --json url --jq .url
```

Expected: one URL string.

---

### Task 7: Verify on the live PR

**Files:** (no source changes; verification only)

This task walks the spec's §Verification checklist points against the PR's own pipeline run.

- [ ] **Step 1: Watch the workflow run finish**

```bash
gh pr checks --watch
```

Expected: the run completes (either green or red). The check list should include `Install`, `Unit Tests`, `WTR Tests`, `IT 1/N` through `IT N/N`, `WC Verify`, `WC Unit (chrome)`, `WC Unit (firefox)`, `WC Unit (webkit)`, `WC Visual`, and `Collect results`.

If `WC Visual` shows as `skipped`, you're on a fork PR — that's expected behavior, not a failure (spec §Verification point 3).

- [ ] **Step 2: Confirm `wc-verify` succeeded** (spec §Verification point 1)

```bash
gh run list --branch ci/wc-validation --limit 1 --json databaseId --jq '.[0].databaseId' \
  | xargs -I{} gh run view {} --json jobs --jq '.jobs[] | select(.name == "WC Verify") | {name, conclusion, status}'
```

Expected: `conclusion: "success"`, `status: "completed"`. If `skipped`, the job's `needs: install` was not satisfied — check the `install` job. If `failure`, drill into the run log via `gh run view <id> --log --job <wc-verify-job-id>`.

- [ ] **Step 3: Confirm all three `wc-unit` matrix entries appear** (spec §Verification point 2)

```bash
gh run list --branch ci/wc-validation --limit 1 --json databaseId --jq '.[0].databaseId' \
  | xargs -I{} gh run view {} --json jobs \
  --jq '.jobs[] | select(.name | startswith("WC Unit")) | {name, conclusion}'
```

Expected: three entries — `WC Unit (chrome)`, `WC Unit (firefox)`, `WC Unit (webkit)` — each with `conclusion: "success"`. If one is missing entirely, the matrix did not expand — re-check Task 3 Step 4's expansion check.

- [ ] **Step 4: Confirm `wc-visual` either ran green or was correctly skipped** (spec §Verification points 3 and 4)

```bash
gh run list --branch ci/wc-validation --limit 1 --json databaseId --jq '.[0].databaseId' \
  | xargs -I{} gh run view {} --json jobs \
  --jq '.jobs[] | select(.name == "WC Visual") | {name, conclusion}'
```

Expected, on an internal PR: `conclusion: "success"`.
Expected, on a fork PR: `conclusion: "skipped"`.
A `conclusion: "failure"` warrants drilling into the run log — visual flakes can mean a genuine regression or a flake the retries didn't recover from.

- [ ] **Step 5: Confirm `Collect results` reflects the new gates correctly** (spec §Verification point 5)

```bash
gh pr checks
```

Expected: a check named `Collect results` with conclusion `success` (or `failure` consistently with whatever the upstream jobs reported). If `Collect results` is green but a sibling job is red, the `needs:` wiring in Task 5 is wrong — revisit it.

- [ ] **Step 6: Confirm the IT shard count is still ≤ 6** (spec §Verification points 7 and 8)

```bash
gh run list --branch ci/wc-validation --limit 1 --json databaseId --jq '.[0].databaseId' \
  | xargs -I{} gh run view {} --json jobs \
  --jq '[.jobs[] | select(.name | startswith("IT "))] | length'
```

Expected: a number ≤ 6. With the current 4-overlay state it will likely be 4. If it's > 6, the matrix script's cap change did not propagate — likely a stale commit. If it's 0, the IT job did not run at all (no overlay names produced).

- [ ] **Step 7: Confirm `unit` and `wtr` still pass** (spec §Verification point regression-prevention)

```bash
gh pr checks
```

Expected: `Unit Tests` and `WTR Tests` both `success`. Their behavior is independent of the new jobs but they share the install cache; cache invalidation bugs could surface here.

- [ ] **Step 8: Smoke-test `workflow_dispatch` with `components: "grid"` (optional)** (spec §Verification point 6)

```bash
gh workflow run "Validation" --ref ci/wc-validation -f components="grid"
gh run list --workflow="Validation" --limit 1
```

Then identify the run ID and inspect a job log:

```bash
gh run view <run-id> --log --job <wc-verify-job-id> | grep -E "yarn test:snapshots --group grid|yarn test:it --group grid"
```

Expected: both grep matches print. If the substitution didn't happen, the `$COMPONENTS` indirection broke — revisit Task 2 Step 2.

- [ ] **Step 9: Report status back to the user**

Tell the user the PR is up, paste the URL, and either:
- (Green): confirm all relevant §Verification points pass. Ask if the PR is ready to merge.
- (Red): identify which job failed, link to the failing job's run log via `gh run view <id> --log --job <job-id>`, and surface the failure summary. Do NOT mark this task complete on a red run.

---

## Self-review

Cross-checked against `docs/superpowers/specs/2026-06-10-wc-validation-design.md`:

- §Pipeline Shape (three new jobs + cap reduction): Tasks 1–4 implement the new jobs and Task 1 implements the cap reduction. The shape matches the spec diagram (install → unit/wtr/its/wc-verify/wc-unit/wc-visual → results).
- §Shared Job Prelude (checkout + setup-node + cache restore): inlined verbatim into each of Tasks 2, 3, 4 (`wc-visual` adds container-specific steps for safe-directory and origin/main fetch).
- §`wc-verify` (lint + snapshot + integration): Task 2.
- §`wc-unit` (browser matrix): Task 3, with `fail-fast: false` and per-browser Playwright install step preserved.
- §`wc-visual` (themes + container + fork-gate + retries): Task 4, with the upload-screenshots step and all five upload globs preserved.
- §Components Filter Semantics (`--all` default, `--group "$c"` loop on filter, lint always unscoped): each test step in Tasks 2, 3, 4 includes the env-indirected loop; lint in Task 2 has no `COMPONENTS` env.
- §IT Shard Cap Reduction (default `MAX_SHARDS` 12 → 6, algorithm unchanged): Task 1, with the unit-test assertion updated alongside.
- §`results` Job Changes (extend `needs:`, extend failure check): Task 5.
- §Caching (no cache changes): each new job restores the existing cache key with the same `path:` list; no path changes.
- §Fork Gating (`wc-visual` only): Task 4 includes `if: github.repository_owner == 'vaadin'`; the failure check in Task 5 treats `skipped` as not-a-failure.
- §Verification (9 checklist points): Task 7 walks each point with a concrete `gh` command.

No placeholders. No "TBD"/"TODO". Every YAML block is shown in full inside an Edit step so an agent can copy-paste it verbatim. Every shell command has an expected output. The TDD red→green pattern in Task 1 (test edit → fails → source edit → passes) keeps the script change honest. Commit messages follow the existing repo style (`ci:` type, imperative subject, motivation in body).
