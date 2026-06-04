# Components Workspace

Workspace for coordinated development across Vaadin's web-components and flow-components repositories.

## Prerequisites

- Git 2.13+ (for submodule branch tracking)
- Node.js 18+ and pnpm (for web-components)
- Java 17+ and Maven 3.8+ (for flow-components)

## Setup

Clone with submodules:

```bash
git clone --recurse-submodules <workspace-url>
```

Or if already cloned:

```bash
git submodule init
git submodule update
```

## Working with Submodules

### Switch to feature branches

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

### Update submodules to latest

```bash
git submodule update --remote --merge
```

### Check submodule status

```bash
git submodule status
```

## Building

### web-components

```bash
cd web-components
pnpm install
pnpm build
```

### flow-components

```bash
cd flow-components
mvn install
```

## Future Plans

- Local npm linking between repos for integration testing
- Potential migration to subtrees or monorepo
