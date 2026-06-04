# Components Workspace Design Spec

## Overview

A workspace repository for coordinated development across Vaadin's web-components and flow-components repositories using git submodules.

## Goals

1. **Cross-repo development** — Make coordinated changes spanning both repos
2. **Local integration testing** — Test flow-components against web-components changes
3. **Code navigation** — Both codebases in one IDE for cross-referencing
4. **Version tracking** — Record which commits in each repo work together

## Non-Goals (Deferred)

- Local npm linking between repos (flow-components uses npm registry for now)
- Workspace-level build scripts
- Monorepo migration (future consideration)

## Repositories

| Repository | URL | Language | Build Tool |
|------------|-----|----------|------------|
| web-components | https://github.com/vaadin/web-components | TypeScript/JS | pnpm |
| flow-components | https://github.com/vaadin/flow-components | Java | Maven |

Both repos use `main` as the default branch.

## Directory Structure

```
components-workspace/
├── .git/
├── .gitmodules
├── web-components/             # Submodule: vaadin/web-components
├── flow-components/            # Submodule: vaadin/flow-components
├── docs/
│   └── superpowers/
│       └── specs/              # Design specs
└── README.md                   # Workspace usage instructions
```

## Submodule Configuration

`.gitmodules`:
```ini
[submodule "web-components"]
    path = web-components
    url = https://github.com/vaadin/web-components.git
    branch = main

[submodule "flow-components"]
    path = flow-components
    url = https://github.com/vaadin/flow-components.git
    branch = main
```

The `branch = main` setting enables branch tracking, avoiding detached HEAD issues.

## Workflows

### Initial Clone

```bash
git clone --recurse-submodules <workspace-url>
```

### Switching to Feature Branches

```bash
cd web-components
git checkout feature/my-feature
cd ..

cd flow-components
git checkout feature/my-feature
cd ..

# Record branch state in workspace
git add web-components flow-components
git commit -m "Point submodules to feature branches"
```

### Updating Submodules

```bash
git submodule update --remote --merge
```

### Building Each Repo

**web-components:**
```bash
cd web-components
pnpm install
pnpm build
```

**flow-components:**
```bash
cd flow-components
mvn install
```

## README Contents

The workspace README will document:
1. Prerequisites (git, node/pnpm, Java/Maven)
2. Setup (clone with `--recurse-submodules`)
3. Working with submodules (branch switching, updating, committing)
4. Building each repo
5. Future plans (local linking, potential monorepo migration)

## Future Considerations

- **Local npm linking** — Enable flow-components to use local web-components builds
- **Subtrees** — Alternative to submodules if workflow proves cumbersome
- **Monorepo** — Full merge of both repos into single repository

## Implementation Steps

1. Add web-components as git submodule
2. Add flow-components as git submodule
3. Create README.md with usage instructions
4. Initial commit
