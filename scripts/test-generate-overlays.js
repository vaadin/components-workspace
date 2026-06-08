'use strict';

const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const test = require('node:test');
const assert = require('node:assert/strict');
const { extractVaadinPackages, filterToLocalPackages } = require('./generate-overlays.js');

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
