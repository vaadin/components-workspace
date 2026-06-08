'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const { extractVaadinPackages } = require('./generate-overlays.js');

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
