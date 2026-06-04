# Components Workspace

Workspace for coordinated development across Vaadin's web-components and flow-components repositories.

## Prerequisites

- Git 2.13+ (for submodule branch tracking)
- Node.js 18–24 and Yarn (for web-components; Node 25+ breaks the upstream `vaadin-charts-flow-svg-generator` build)
- JDK 21+ and Maven 3.8+ (for flow-components)

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

The workspace exposes Gradle tasks that orchestrate both submodules:

```bash
./gradlew install   # yarn install (web-components) + Maven warm-up (no-op)
./gradlew build     # web-components ready + mvn -DskipTests install
./gradlew test      # yarn test (web-components, changed packages) + mvn test
./gradlew clean     # remove node_modules + mvn clean
```

Run a single subproject's task by prefixing the subproject path:

```bash
./gradlew :web-components:build
./gradlew :flow-components:test
```

The submodules' native commands continue to work directly inside each submodule:

```bash
# web-components
cd web-components && yarn install

# flow-components
cd flow-components && mvn install
```

See `docs/superpowers/specs/2026-06-04-common-gradle-build-design.md` for design details.

## Future Plans

- Local npm linking between repos for integration testing
- Potential migration to subtrees or monorepo
