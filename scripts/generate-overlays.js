#!/usr/bin/env node
// Generates minimal overlay package.json files for flow-components IT modules
// whose primary component declares @NpmPackage annotations matching @vaadin/*
// packages present in web-components/packages/. Non-destructive: never modifies
// files for modules already listed in overlays.txt.

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

// Module list from spec §Scope (all "included" modules; pilot is excluded by
// reading existing overlays.txt entries).
const CANDIDATE_MODULES = [
  'accordion', 'app-layout', 'aura-theme', 'avatar', 'badge', 'board',
  'breadcrumbs', 'card', 'charts', 'checkbox', 'confirm-dialog',
  'context-menu', 'crud', 'custom-field', 'dashboard', 'date-time-picker',
  'details', 'dialog', 'field-highlighter', 'form-layout', 'grid-pro',
  'icons', 'list-box', 'login', 'lumo-theme', 'map', 'markdown',
  'master-detail-layout', 'menu-bar', 'messages', 'notification',
  'ordered-layout', 'popover', 'progress-bar', 'radio-button', 'renderer',
  'rich-text-editor', 'select', 'side-nav', 'slider', 'split-layout',
  'tabs', 'text-field', 'time-picker', 'upload', 'virtual-list',
];

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

function readExistingOverlays(overlaysFile) {
  if (!fs.existsSync(overlaysFile)) return [];
  return fs.readFileSync(overlaysFile, 'utf8')
    .split('\n')
    .map((l) => l.trim())
    .filter((l) => l && !l.startsWith('#'));
}

function main({ workspaceRoot }) {
  const flowDir = path.join(workspaceRoot, 'flow-components');
  const webPackagesDir = path.join(workspaceRoot, 'web-components', 'packages');
  const overlayDir = path.join(workspaceRoot, 'flow-components-overlay');
  const overlaysFile = path.join(overlayDir, 'overlays.txt');

  const existing = new Set(readExistingOverlays(overlaysFile));
  const added = [];
  const skipped = [];
  const alreadyCovered = [];

  for (const name of CANDIDATE_MODULES) {
    const relPath = `vaadin-${name}-flow-parent/vaadin-${name}-flow-integration-tests`;
    if (existing.has(relPath)) {
      alreadyCovered.push(name);
      continue;
    }

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
    fs.writeFileSync(outFile, buildOverlayPackageJson(name, localPkgs));
    added.push({ name, packages: localPkgs });
  }

  // Append new entries to overlays.txt, preserving existing lines.
  if (added.length > 0) {
    const current = fs.existsSync(overlaysFile) ? fs.readFileSync(overlaysFile, 'utf8') : '';
    const trailing = current.endsWith('\n') || current === '' ? '' : '\n';
    const additions = added
      .map((a) => `vaadin-${a.name}-flow-parent/vaadin-${a.name}-flow-integration-tests`)
      .join('\n') + '\n';
    fs.writeFileSync(overlaysFile, current + trailing + additions);
  }

  console.log(`Added:           ${added.length}`);
  for (const a of added) console.log(`  + ${a.name}  [${a.packages.join(', ')}]`);
  console.log(`Already covered: ${alreadyCovered.length}`);
  for (const n of alreadyCovered) console.log(`  = ${n}`);
  console.log(`Skipped:         ${skipped.length}`);
  for (const s of skipped) console.log(`  - ${s.name}  (${s.reason})`);
}

module.exports = {
  extractVaadinPackages,
  filterToLocalPackages,
  buildOverlayPackageJson,
  main,
};

if (require.main === module) {
  main({
    workspaceRoot: path.resolve(__dirname, '..'),
  });
}
