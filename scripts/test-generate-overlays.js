'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const { extractVaadinPackages, filterToLocalPackages, buildOverlayPackageJson, discoverLocalVaadinPackages, discoverItModules } = require('./generate-overlays.js');

test('extractVaadinPackages — single @vaadin annotation', () => {
  const src = '@NpmPackage(value = "@vaadin/accordion", version = "25.2.0-beta1")';
  assert.deepEqual(extractVaadinPackages(src), ['@vaadin/accordion']);
});

test('extractVaadinPackages — multiple @vaadin annotations, sorted unique', () => {
  const src = `
    @NpmPackage(value = "@vaadin/avatar-group", version = "25.2.0-beta1")
    @NpmPackage(value = "@vaadin/avatar", version = "25.2.0-beta1")
    @NpmPackage(value = "@vaadin/avatar", version = "25.2.0-beta1")
  `;
  assert.deepEqual(extractVaadinPackages(src), ['@vaadin/avatar', '@vaadin/avatar-group']);
});

test('extractVaadinPackages — ignores non-vaadin packages', () => {
  const src = `
    @NpmPackage(value = "@vaadin/map", version = "25.2.0-beta1")
    @NpmPackage(value = "ol", version = "10.6.1")
    @NpmPackage(value = "proj4", version = "2.17.0")
  `;
  assert.deepEqual(extractVaadinPackages(src), ['@vaadin/map']);
});

test('extractVaadinPackages — empty source returns empty array', () => {
  assert.deepEqual(extractVaadinPackages(''), []);
});

test('filterToLocalPackages — keeps only packages with a matching directory', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'gen-overlays-'));
  try {
    fs.mkdirSync(path.join(tmp, 'avatar'));
    fs.mkdirSync(path.join(tmp, 'avatar-group'));
    // no @vaadin/missing dir
    const result = filterToLocalPackages(
      ['@vaadin/avatar', '@vaadin/avatar-group', '@vaadin/missing'],
      tmp
    );
    assert.deepEqual(result, ['@vaadin/avatar', '@vaadin/avatar-group']);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('filterToLocalPackages — returns empty array when no matches', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'gen-overlays-'));
  try {
    assert.deepEqual(filterToLocalPackages(['@vaadin/x'], tmp), []);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('buildOverlayPackageJson — single dep', () => {
  const content = buildOverlayPackageJson('accordion', ['@vaadin/accordion']);
  const parsed = JSON.parse(content);
  assert.equal(parsed.name, '@vaadin-flow-integration-tests/vaadin-accordion-flow-integration-tests');
  assert.equal(parsed.version, '0.0.0');
  assert.equal(parsed.private, true);
  assert.deepEqual(parsed.dependencies, {
    '@vaadin/accordion': 'file:../../../web-components/packages/accordion',
  });
});

test('buildOverlayPackageJson — multiple deps, sorted', () => {
  const content = buildOverlayPackageJson('avatar', ['@vaadin/avatar-group', '@vaadin/avatar']);
  const parsed = JSON.parse(content);
  assert.deepEqual(parsed.dependencies, {
    '@vaadin/avatar': 'file:../../../web-components/packages/avatar',
    '@vaadin/avatar-group': 'file:../../../web-components/packages/avatar-group',
  });
  // Deterministic key order in serialized output (alphabetical).
  const depKeys = Object.keys(parsed.dependencies);
  assert.deepEqual(depKeys, [...depKeys].sort());
});

test('buildOverlayPackageJson — output ends with trailing newline', () => {
  const content = buildOverlayPackageJson('accordion', ['@vaadin/accordion']);
  assert.equal(content.endsWith('\n'), true);
});

test('discoverItModules — finds short names from vaadin-<name>-flow-parent/vaadin-<name>-flow-integration-tests pairs', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'gen-overlays-'));
  try {
    fs.mkdirSync(path.join(tmp, 'vaadin-button-flow-parent/vaadin-button-flow-integration-tests'), { recursive: true });
    fs.mkdirSync(path.join(tmp, 'vaadin-grid-flow-parent/vaadin-grid-flow-integration-tests'), { recursive: true });
    // parent with no integration-tests subdir → skipped
    fs.mkdirSync(path.join(tmp, 'vaadin-only-flow-parent/vaadin-only-flow'), { recursive: true });
    // unrelated directory → skipped
    fs.mkdirSync(path.join(tmp, 'docs'), { recursive: true });
    assert.deepEqual(discoverItModules(tmp), ['button', 'grid']);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('discoverItModules — returns empty array when flowDir does not exist', () => {
  assert.deepEqual(discoverItModules('/tmp/this-does-not-exist-' + Date.now()), []);
});

test('discoverLocalVaadinPackages — returns @vaadin/<dir> for every subdirectory, sorted', () => {
  const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'gen-overlays-'));
  try {
    fs.mkdirSync(path.join(tmp, 'avatar'));
    fs.mkdirSync(path.join(tmp, 'a11y-base'));
    fs.mkdirSync(path.join(tmp, 'button'));
    // A regular file under packages/ is ignored.
    fs.writeFileSync(path.join(tmp, 'README.md'), '');
    assert.deepEqual(discoverLocalVaadinPackages(tmp), [
      '@vaadin/a11y-base',
      '@vaadin/avatar',
      '@vaadin/button',
    ]);
  } finally {
    fs.rmSync(tmp, { recursive: true, force: true });
  }
});

test('discoverLocalVaadinPackages — returns empty array when packages dir does not exist', () => {
  assert.deepEqual(discoverLocalVaadinPackages('/tmp/this-does-not-exist-' + Date.now()), []);
});

test('buildOverlayPackageJson — full local @vaadin/* set produces a file: URL per package', () => {
  const content = buildOverlayPackageJson('button', [
    '@vaadin/a11y-base',
    '@vaadin/button',
    '@vaadin/component-base',
  ]);
  const parsed = JSON.parse(content);
  assert.equal(parsed.name, '@vaadin-flow-integration-tests/vaadin-button-flow-integration-tests');
  assert.deepEqual(parsed.dependencies, {
    '@vaadin/a11y-base': 'file:../../../web-components/packages/a11y-base',
    '@vaadin/button': 'file:../../../web-components/packages/button',
    '@vaadin/component-base': 'file:../../../web-components/packages/component-base',
  });
});
