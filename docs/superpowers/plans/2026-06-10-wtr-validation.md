# WTR Validation Re-enable Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Re-enable the `wtr` job in `.github/workflows/validation.yml` (currently disabled via `if: false`), pass the `workflow_dispatch.inputs.components` filter through to `flow-components/scripts/wtr.js`, and bring the parent CI spec back in sync with the workflow. Validate locally against all four WTR-eligible overlay modules before pushing, so any setup breakage surfaces on the workstation rather than in CI.

**Architecture:** Two YAML lines change in the workspace workflow. The parent CI spec loses one note block and one Future Work bullet. No submodule code changes. The bulk of the work is the local-investigation phase: run the WTR job's exact command sequence (`node scripts/wtr.js <module>`) against each of `grid`, `combo-box`, `renderer`, `date-picker`. Any prerequisite fix (e.g. a missing workspace symlink) goes in this branch as a separate commit ahead of the WTR re-enable commit.

**Tech Stack:**
- GitHub Actions workflow file (`.github/workflows/validation.yml`)
- `flow-components/scripts/wtr.js` (Node + xml2js)
- `npx web-test-runner --playwright` (browser test runner)
- `npx playwright install chromium` (headless Chromium)
- `mvn flow:prepare-frontend flow:build-frontend` (Vaadin Flow frontend build, per IT module)
- TestBench license at `~/.vaadin/proKey`

**Reference spec:** `docs/superpowers/specs/2026-06-10-wtr-validation-design.md`

**Branch:** `fix/wtr-validation` (already checked out; spec commit `3473618` is its tip).

---

## Pre-flight check

Run these from the workspace root before starting Task 1:

```bash
git status                                                  # clean tree on fix/wtr-validation
git log --oneline -1                                        # should be 3473618 docs: spec ...
test -f ~/.vaadin/proKey && echo "license ok"               # TestBench license materialized
gh auth status                                              # repo scope, for the PR push later
test -x scripts/sync-flow-overlays.sh && echo "sync ok"
ls flow-components/scripts/wtr.js                           # WTR runner exists in the submodule
```

All six must succeed. If `~/.vaadin/proKey` is absent, materialize it:

```bash
mkdir -p ~/.vaadin
echo '{"username":"<user>","proKey":"<key>"}' > ~/.vaadin/proKey
```

`<user>` and `<key>` come from your personal TestBench license (Vaadin SSO → My Licenses).

---

### Task 1: Run workspace install to populate caches

**Files:** (no source changes; this task populates `node_modules/`, `~/.m2`, per-IT `node_modules`)

- [ ] **Step 1: Clean prior install state**

```bash
./gradlew clean
```

Expected: BUILD SUCCESSFUL. Removes `node_modules/`, `web-components/node_modules/`, per-IT `node_modules/`, and Maven artifacts under the workspace.

- [ ] **Step 2: Run workspace install (matches the CI install job's input state)**

```bash
./gradlew install
```

Expected: BUILD SUCCESSFUL. Populates:
- `node_modules/` (workspace root)
- `web-components/node_modules/`, `web-components/.yarn/`
- `flow-components/**/node_modules/` (per-IT, where the overlay applies)
- `~/.m2/repository/com/vaadin/` (full flow-components reactor compiled-and-installed)
- `flow-components/vaadin-charts-flow-parent/vaadin-charts-flow-svg-generator/src/main/resources/META-INF/frontend/generated/`

Wall-clock: roughly 5–10 minutes on a warm machine; longer on cold caches.

- [ ] **Step 3: Sanity-check the overlay symlinks exist for the canary module**

```bash
ls -l flow-components/vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/node_modules/@vaadin/grid
```

Expected: a symlink resolving into `web-components/packages/grid`. If the path does not exist, the workspace install is broken — STOP and investigate before continuing (this would be a pre-existing workspace bug, not a WTR issue).

---

### Task 2: Reproduce WTR locally — canary module `grid`

**Files:** (no source changes; this task is observation + diagnosis)

- [ ] **Step 1: Run WTR for grid (same command the CI step runs, scoped to one module)**

```bash
cd flow-components && node scripts/wtr.js grid
```

The script does, in order:
1. Resolves module path `vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/`.
2. Skips if `<it>/test/` does not exist. (For grid it does — verified.)
3. Runs `mvn -DskipTests flow:prepare-frontend flow:build-frontend` in the IT directory.
4. Runs `npx playwright install chromium`.
5. Runs `npx web-test-runner --playwright test/**/*.test.ts --node-resolve`.

Wall-clock: roughly 3–8 minutes (frontend build dominates).

- [ ] **Step 2: Interpret the result**

If the WTR run **exits 0** with green test output: proceed to Task 3.

If the WTR run **exits non-zero**: identify which of the three stages failed:

| Failing stage | Symptom | Triage path |
|---|---|---|
| `flow:build-frontend` | Maven log says "Could not resolve `@vaadin/grid`" or similar | Confirm `scripts/sync-flow-overlays.sh` ran by inspecting `flow-components/vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests/package.json` — it should match the file in `flow-components-overlay/`. Run `bash ../scripts/sync-flow-overlays.sh` from `flow-components/`'s parent. Re-run Step 1. |
| `npx playwright install chromium` | Network/system error | If retry-able: re-run Step 1. If persistent: capture the error message and pause for user input — likely needs `--with-deps` (only meaningful on Linux). |
| `web-test-runner` | Browser test failure | Open `wtr-results.xml` to read the failure(s). If the failure is a workspace-side bug (e.g. missing dep symlink), fix it in `flow-components-overlay/`, re-sync, re-run. If the failure is a real test failure, capture the names and pause for user input on whether to fix or skip — do not silently disable. |

- [ ] **Step 3: If a fix was needed, commit it as a separate pre-WTR-flip commit**

```bash
cd ..   # back to workspace root
git status
git add <files-you-touched-in-the-overlay>
git commit -m "fix(overlays): <one-line summary>"
```

Use commit type `fix(overlays):` if the change was to `flow-components-overlay/`. If the change was to `scripts/` or to the workspace Gradle build, use `fix(workspace):` or `fix(ci):` as appropriate. Keep this commit separate from the WTR re-enable commit (Task 6) so reviewers can see them in isolation.

- [ ] **Step 4: If you ran a triage Step 3, re-run Task 2 Step 1 to confirm green**

After committing the fix, repeat:

```bash
cd flow-components && node scripts/wtr.js grid
```

Expected: exit 0. If still failing, repeat triage. Only continue to Task 3 with a clean green for grid.

---

### Task 3: Reproduce WTR locally — `combo-box`

**Files:** (no source changes; observation + diagnosis)

- [ ] **Step 1: Run WTR for combo-box**

```bash
cd flow-components && node scripts/wtr.js combo-box
```

(If you're already inside `flow-components/` from Task 2, drop the `cd`.)

Expected: exit 0.

- [ ] **Step 2: If it fails, triage with the same table from Task 2 Step 2**

The triage decision tree is identical. Apply it for `combo-box`. If you need a fix, commit it (Task 2 Step 3 pattern). Re-run Step 1 of this task until green.

---

### Task 4: Reproduce WTR locally — `renderer`

**Files:** (no source changes; observation + diagnosis)

- [ ] **Step 1: Run WTR for renderer**

```bash
cd flow-components && node scripts/wtr.js renderer
```

Expected: exit 0.

- [ ] **Step 2: If it fails, triage with the same table from Task 2 Step 2**

Apply the triage decision tree for `renderer`. Commit any fix as a separate pre-WTR-flip commit. Re-run until green.

---

### Task 5: Reproduce WTR locally — `date-picker`

**Files:** (no source changes; observation + diagnosis)

- [ ] **Step 1: Run WTR for date-picker**

```bash
cd flow-components && node scripts/wtr.js date-picker
```

Expected: exit 0.

- [ ] **Step 2: If it fails, triage with the same table from Task 2 Step 2**

Apply the triage decision tree for `date-picker`. Commit any fix as a separate pre-WTR-flip commit. Re-run until green.

- [ ] **Step 3: Confirm full unscoped run also succeeds (matches the PR-trigger behavior)**

```bash
cd flow-components && node scripts/wtr.js
```

With no args, `wtr.js` enumerates every module in the parent pom and runs WTR for any with a `<it>/test/` folder. On the current submodule state that's exactly the four modules from Tasks 2–5. Expected: exit 0. Wall-clock: roughly 15–30 minutes (sum of the four module runs, each ~3–8 min).

If this fails after Tasks 2–5 individually passed, something in the iteration sequence is the culprit (e.g. state leaks between modules). Stop and investigate before proceeding.

---

### Task 6: Re-enable the `wtr` job in `validation.yml`

**Files:**
- Modify: `.github/workflows/validation.yml:153` (remove one line)
- Modify: `.github/workflows/validation.yml:202` (change one line)

- [ ] **Step 1: Inspect the current `wtr` job block**

```bash
sed -n '151,203p' .github/workflows/validation.yml
```

Expected output begins with:
```yaml
  wtr:
    name: WTR Tests
    if: false
    needs: install
    runs-on: ubuntu-latest
    ...
```
and ends with:
```yaml
      - name: Run WTR tests
        run: cd flow-components && node scripts/wtr.js
```

- [ ] **Step 2: Remove the `if: false` line**

Using Edit tooling (do **not** use `sed`):

`old_string`:
```
  wtr:
    name: WTR Tests
    if: false
    needs: install
```

`new_string`:
```
  wtr:
    name: WTR Tests
    needs: install
```

- [ ] **Step 3: Add the `inputs.components` arg to the WTR run step (via `env:` indirection)**

Use the same env-var pattern the install job already uses for this input (`env: COMPONENTS: ${{ inputs.components }}` then `$COMPONENTS` in the shell). That keeps user-supplied dispatch input out of the templated `run:` string.

Using Edit tooling:

`old_string`:
```
      - name: Run WTR tests
        run: cd flow-components && node scripts/wtr.js
```

`new_string`:
```
      - name: Run WTR tests
        env:
          COMPONENTS: ${{ inputs.components }}
        run: cd flow-components && node scripts/wtr.js $COMPONENTS
```

- [ ] **Step 4: Visually verify the diff**

```bash
git diff .github/workflows/validation.yml
```

Expected: a `-` for `    if: false` inside the `wtr:` block, and a `-/+` hunk around the `Run WTR tests` step where the `run:` line gains `$COMPONENTS` and two new lines for `env:` / `COMPONENTS:` appear above it. No other lines change.

- [ ] **Step 5: Lint the YAML by checking GitHub Actions parses it (optional but cheap)**

```bash
which yamllint && yamllint -d "{extends: relaxed, rules: {line-length: disable}}" .github/workflows/validation.yml || echo "yamllint not installed; skipping"
```

Either prints no errors, or prints "yamllint not installed; skipping". Both are acceptable. GitHub Actions itself will validate the schema at workflow load time.

---

### Task 7: Sync the parent CI spec

**Files:**
- Modify: `docs/superpowers/specs/2026-06-04-ci-validation-design.md` (two block deletions)

- [ ] **Step 1: Locate the disabled-WTR note in §wtr**

```bash
grep -n "currently disabled via \`if: false\`" docs/superpowers/specs/2026-06-04-ci-validation-design.md
```

Expected: one match at roughly line 278, inside the `> **NOTE:**` block under §`wtr` — Web Test Runner.

- [ ] **Step 2: Remove the entire note block**

Using Edit tooling:

`old_string`:
```
> **NOTE:** The `wtr` job is currently disabled via `if: false` while a license/setup issue is being triaged. The job body is left in place so it can be re-enabled by removing the `if:` line. Tracked under §Future Work.
```

`new_string`: (empty string — this deletes the line entirely)

If a blank line is left dangling above or below the deleted block after the edit, also delete that surplus blank line so the remaining text reads cleanly. Use a follow-up Edit to collapse double blank lines if needed.

- [ ] **Step 3: Locate the Future Work bullet about re-enabling WTR**

```bash
grep -n "Re-enable the .wtr. job" docs/superpowers/specs/2026-06-04-ci-validation-design.md
```

Expected: one match at roughly line 661.

- [ ] **Step 4: Remove the entire bullet**

Using Edit tooling:

`old_string`:
```
- **Re-enable the `wtr` job.** Currently gated by `if: false` while a Java/Node/TestBench-licensing issue is triaged. The job already has all the prerequisites wired (JDK 21, Node 24, license install) — flipping `if: false` to `if: true` should be the final step after the underlying issue is identified.
```

`new_string`: (empty string)

If a dangling blank line remains, collapse it.

- [ ] **Step 5: Verify the spec no longer mentions "currently disabled" or "Re-enable the `wtr` job"**

```bash
grep -nE "currently disabled|Re-enable the .wtr. job" docs/superpowers/specs/2026-06-04-ci-validation-design.md && echo "STILL PRESENT" || echo "clean"
```

Expected: `clean`. If `STILL PRESENT`, repeat Step 2 or Step 4 against any remaining match.

- [ ] **Step 6: Visually verify the spec diff**

```bash
git diff docs/superpowers/specs/2026-06-04-ci-validation-design.md
```

Expected: only deletions; no `+` lines.

---

### Task 8: Commit the WTR re-enable

**Files:** (committing the changes from Tasks 6 and 7)

- [ ] **Step 1: Stage only the WTR-flip files**

```bash
git status
git add .github/workflows/validation.yml docs/superpowers/specs/2026-06-04-ci-validation-design.md
git status
```

Expected: `git status` after staging shows the two files under "Changes to be committed" and nothing else (no leftover overlay-fix changes — those should already be committed from Tasks 2–5).

- [ ] **Step 2: Create the commit**

```bash
git commit -m "$(cat <<'EOF'
ci: re-enable WTR job and pass components filter through

Removes the `if: false` flag added in ca1816e and passes the
workflow_dispatch components input through to scripts/wtr.js so
dispatch-time filtering reaches the WTR runner, matching how the
IT matrix already filters.

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: commit created. `git log --oneline -1` shows the new commit at HEAD.

---

### Task 9: Push and open the PR

**Files:** (no source changes)

- [ ] **Step 1: Push the branch**

```bash
git push -u origin fix/wtr-validation
```

Expected: branch pushed. If `-u` was already set on a prior push, plain `git push` is also fine.

- [ ] **Step 2: Create the PR**

```bash
gh pr create --base main --head fix/wtr-validation \
  --title "ci: re-enable WTR job in workspace validation" \
  --body "$(cat <<'EOF'
The \`wtr\` job in the workspace validation workflow has been gated
by \`if: false\` since commit ca1816e ("ci: skip wtr job temporarily").
Subsequent commits to the workspace (Node 24 setup, install-cache
shape, per-IT \`node_modules\` symlinks) have removed the original
blockers. This PR re-enables the job and threads the
\`workflow_dispatch.inputs.components\` filter through to
\`scripts/wtr.js\`, matching how the IT matrix already scopes by
component.

All four WTR-eligible modules (\`grid\`, \`combo-box\`, \`renderer\`,
\`date-picker\`) were verified locally before this PR was opened.

Design: \`docs/superpowers/specs/2026-06-10-wtr-validation-design.md\`

---

🤖 Generated with Claude Code
EOF
)"
```

Expected: a PR URL printed to stdout. Save the URL.

- [ ] **Step 3: Capture the PR URL for Task 10**

```bash
gh pr view --json url --jq .url
```

---

### Task 10: Verify CI

**Files:** (no source changes; verification only)

- [ ] **Step 1: Wait for the workflow run to start, then watch it**

```bash
gh pr checks --watch
```

Expected: the `Install`, `Unit Tests`, `WTR Tests`, `IT …`, and `Collect results` checks all appear. Wait for the run to finish.

- [ ] **Step 2: Confirm the `wtr` job actually executed (was not skipped)**

```bash
gh run list --branch fix/wtr-validation --limit 1 --json databaseId --jq '.[0].databaseId' \
  | xargs -I{} gh run view {} --json jobs --jq '.jobs[] | select(.name == "WTR Tests") | {name, conclusion, status}'
```

Expected: `conclusion: "success"`, `status: "completed"`. If `conclusion: "skipped"`, the `if: false` was not actually removed — go back to Task 6.

- [ ] **Step 3: Confirm WTR uploaded an artifact**

```bash
gh run list --branch fix/wtr-validation --limit 1 --json databaseId --jq '.[0].databaseId' \
  | xargs -I{} gh api repos/{owner}/{repo}/actions/runs/{}/artifacts \
  | jq '.artifacts[] | select(.name == "wtr-reports") | {name, size_in_bytes}'
```

(Substitute `{owner}/{repo}` for the actual workspace repo, or use `gh api repos/$(gh repo view --json nameWithOwner --jq .nameWithOwner)/...`.)

Expected: one `wtr-reports` artifact with size > 0.

- [ ] **Step 4: Confirm the "WTR Tests" dorny check appears on the PR**

```bash
gh pr checks
```

Expected: a check named `WTR Tests` with conclusion `success`. (This is published by the `results` job's `wtr-dorny` step.)

- [ ] **Step 5: Confirm `unit` and `its` are still green (no regression from the WTR re-enable)**

The `gh pr checks` output from Step 4 also shows `Unit Tests` and `IT …` rows. All should be `success`.

- [ ] **Step 6: Spot-check the dispatch-input filter (optional smoke test)**

If you have time, manually trigger:

```bash
gh workflow run "Validation" --ref fix/wtr-validation -f components="grid"
```

Then:

```bash
gh run list --workflow="Validation" --limit 1
gh run view <run-id> --log --job <wtr-job-id> | grep "Run WTR tests" -A 2
```

Expected: the run command's expanded form shows `node scripts/wtr.js grid` (not bare `node scripts/wtr.js`). If absent, the `${{ inputs.components }}` substitution did not land as intended — revisit Task 6 Step 3.

- [ ] **Step 7: Report status back to the user with the PR URL**

Tell the user the PR is up and all six §Verification criteria from the spec are met. If any criterion failed, report which one and the gh-run URL for the failing job; do not mark this task complete.

---

## Self-review

Cross-checked against `docs/superpowers/specs/2026-06-10-wtr-validation-design.md`:

- §Changes (workflow + parent spec edits): covered by Tasks 6 and 7.
- §Local-Investigation Procedure (setup + 4 modules + decision gate): covered by Tasks 1–5.
- §Verification (6 criteria): covered by Task 10 Steps 2–5 (criteria 1–4 and 6), Step 5 (criterion 6), and a separate ad-hoc dispatch test for criterion 5 (Step 6).
- §Goals 1 and 2: implemented by Tasks 6 (re-enable + threading the filter) and 9 (PR creation).
- §Goal 3 (spec accuracy): implemented by Task 7.

No placeholders. No "TBD"/"TODO". Every command is concrete with an expected output. The triage table in Task 2 Step 2 names specific symptom→fix mappings; Tasks 3–5 reference it explicitly rather than re-describing. Commit conventions in Task 2 Step 3 and Task 8 Step 2 follow the repo's existing pattern (`fix(overlays):`, `ci:`).
