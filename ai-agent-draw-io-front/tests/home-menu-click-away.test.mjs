import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import {
  ACCOUNT_MENU_ATTRIBUTE,
  DIAGRAM_ACTION_MENU_ATTRIBUTE,
  isAccountMenuTarget,
  isDiagramActionMenuTarget,
} from '../src/app/home-menu-click-away.ts';

test('isDiagramActionMenuTarget treats clicks inside the menu controls as internal', () => {
  const target = {
    closest: selector => selector === `[${DIAGRAM_ACTION_MENU_ATTRIBUTE}]` ? {} : null,
  };

  assert.equal(isDiagramActionMenuTarget(target), true);
});

test('isDiagramActionMenuTarget treats clicks outside the menu controls as external', () => {
  const target = {
    closest: () => null,
  };

  assert.equal(isDiagramActionMenuTarget(target), false);
});

test('isDiagramActionMenuTarget handles non-element event targets', () => {
  assert.equal(isDiagramActionMenuTarget(null), false);
  assert.equal(isDiagramActionMenuTarget({}), false);
});

test('isAccountMenuTarget treats clicks inside the account menu controls as internal', () => {
  const target = {
    closest: selector => selector === `[${ACCOUNT_MENU_ATTRIBUTE}]` ? {} : null,
  };

  assert.equal(isAccountMenuTarget(target), true);
});

test('home account menu opens from hover on the account area', () => {
  const pagePath = fileURLToPath(new URL('../src/app/page.tsx', import.meta.url));
  const pageSource = readFileSync(pagePath, 'utf8');

  assert.match(pageSource, /onMouseEnter=\{openAccountMenu\}/);
  assert.match(pageSource, /onMouseLeave=\{closeAccountMenu\}/);
});
