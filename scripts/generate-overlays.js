#!/usr/bin/env node
// Generates overlay package.json files for flow-components IT modules whose
// primary component declares @NpmPackage annotations matching @vaadin/*
// packages present in web-components/packages/.
//
// Discovers IT modules by scanning flow-components/ directly. Each generated
// overlay declares **every** @vaadin/* package present in web-components/
// packages/ as a `file:` dependency, so npm resolves them all to the local
// workspace regardless of which one the IT module is primarily testing and
// regardless of what versions Flow's maven plugin later adds.
//
// Always overwrites: re-running the script restores the canonical shape after
// Flow's maven plugin has merged its own deps into the symlinked overlay.

'use strict';

const fs = require('fs');
const path = require('path');

function extractVaadinPackages(javaSource) {
  const re = /@NpmPackage\s*\(\s*value\s*=\s*"(@vaadin\/[^"]+)"/g;
  const found = new Set();
  let m;
  while ((m = re.exec(javaSource)) !== null) {
    found.add(m[1]);
  }
  return [...found].sort();
}

function filterToLocalPackages(packageNames, webComponentsPackagesDir) {
  return packageNames.filter((name) => {
    const shortName = name.replace(/^@vaadin\//, '');
    return fs.existsSync(path.join(webComponentsPackagesDir, shortName));
  });
}

// Lists every @vaadin/<name> for which web-components/packages/<name>/ exists.
function discoverLocalVaadinPackages(webComponentsPackagesDir) {
  if (!fs.existsSync(webComponentsPackagesDir)) return [];
  return fs.readdirSync(webComponentsPackagesDir, { withFileTypes: true })
    .filter((e) => e.isDirectory())
    .map((e) => `@vaadin/${e.name}`)
    .sort();
}

function buildOverlayPackageJson(componentName, packages) {
  const sortedPackages = [...packages].sort();
  const dependencies = {};
  for (const pkg of sortedPackages) {
    const shortName = pkg.replace(/^@vaadin\//, '');
    dependencies[pkg] = `file:../../../web-components/packages/${shortName}`;
  }
  const obj = {
    name: `@vaadin-flow-integration-tests/vaadin-${componentName}-flow-integration-tests`,
    version: '0.0.0',
    private: true,
    dependencies,
  };
  return JSON.stringify(obj, null, 2) + '\n';
}

// Returns short component names for every IT module present in
// `flow-components/`. Match pattern: `vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests/`.
function discoverItModules(flowDir) {
  const names = new Set();
  if (!fs.existsSync(flowDir)) return [];
  for (const entry of fs.readdirSync(flowDir, { withFileTypes: true })) {
    if (!entry.isDirectory()) continue;
    const parentMatch = entry.name.match(/^vaadin-(.+)-flow-parent$/);
    if (!parentMatch) continue;
    const name = parentMatch[1];
    const itDir = path.join(flowDir, entry.name, `vaadin-${name}-flow-integration-tests`);
    if (fs.existsSync(itDir) && fs.statSync(itDir).isDirectory()) {
      names.add(name);
    }
  }
  return [...names].sort();
}

function readJavaSources(componentSrcDir) {
  if (!fs.existsSync(componentSrcDir)) return '';
  const out = [];
  const walk = (dir) => {
    for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
      const full = path.join(dir, entry.name);
      if (entry.isDirectory()) walk(full);
      else if (entry.isFile() && entry.name.endsWith('.java')) {
        out.push(fs.readFileSync(full, 'utf8'));
      }
    }
  };
  walk(componentSrcDir);
  return out.join('\n');
}

function main({ workspaceRoot }) {
  const flowDir = path.join(workspaceRoot, 'flow-components');
  const webPackagesDir = path.join(workspaceRoot, 'web-components', 'packages');
  const overlayDir = path.join(workspaceRoot, 'flow-components-overlay');

  if (!fs.existsSync(webPackagesDir)) {
    process.stderr.write(`ERROR: web-components/packages/ not found at ${webPackagesDir}\n  Did you run: git submodule update --init ?\n`);
    process.exit(1);
  }
  if (!fs.existsSync(flowDir)) {
    process.stderr.write(`ERROR: flow-components/ not found at ${flowDir}\n  Did you run: git submodule update --init ?\n`);
    process.exit(1);
  }

  const allLocalVaadinPackages = discoverLocalVaadinPackages(webPackagesDir);
  const candidates = discoverItModules(flowDir);
  const written = [];
  const skipped = [];

  for (const name of candidates) {
    const srcDir = path.join(flowDir, `vaadin-${name}-flow-parent`, `vaadin-${name}-flow`, 'src');
    const allJava = readJavaSources(srcDir);
    const vaadinPkgs = extractVaadinPackages(allJava);
    const localPkgs = filterToLocalPackages(vaadinPkgs, webPackagesDir);

    if (localPkgs.length === 0) {
      skipped.push({ name, reason: 'no @vaadin/* annotations matching web-components/packages/' });
      continue;
    }

    const outDir = path.join(overlayDir, `vaadin-${name}-flow-parent`, `vaadin-${name}-flow-integration-tests`);
    const outFile = path.join(outDir, 'package.json');
    fs.mkdirSync(outDir, { recursive: true });
    fs.writeFileSync(outFile, buildOverlayPackageJson(name, allLocalVaadinPackages));
    written.push(name);
  }

  console.log(`Written:          ${written.length} (every overlay lists all ${allLocalVaadinPackages.length} local @vaadin/* packages)`);
  for (const n of written) console.log(`  + ${n}`);
  console.log(`Skipped:          ${skipped.length}`);
  for (const s of skipped) console.log(`  - ${s.name}  (${s.reason})`);
}

module.exports = {
  extractVaadinPackages,
  filterToLocalPackages,
  buildOverlayPackageJson,
  discoverLocalVaadinPackages,
  discoverItModules,
  main,
};

if (require.main === module) {
  main({
    workspaceRoot: path.resolve(__dirname, '..'),
  });
}
