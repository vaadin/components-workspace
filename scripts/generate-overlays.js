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

function main(_opts) {
  throw new Error('not implemented');
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
