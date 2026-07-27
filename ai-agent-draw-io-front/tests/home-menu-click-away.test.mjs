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

test('home account menu opens from hover and stays reachable after leaving the trigger', () => {
  // The account menu lives in the shared workspace header used by every signed-in surface.
  const pagePath = fileURLToPath(new URL('../src/features/workspace/WorkspaceHeader.tsx', import.meta.url));
  const pageSource = readFileSync(pagePath, 'utf8');

  assert.match(pageSource, /onMouseEnter=\{openMenu\}/);
  assert.match(pageSource, /document\.addEventListener\('pointerdown', closeMenuOnOutsidePointerDown\)/);
  assert.doesNotMatch(pageSource, /onMouseLeave=\{closeAccountMenu\}/);
});

test('home places the new diagram action as the first recent diagram card', () => {
  const pagePath = fileURLToPath(new URL('../src/app/diagrams/page.tsx', import.meta.url));
  const pageSource = readFileSync(pagePath, 'utf8');
  const headerSource = pageSource.slice(pageSource.indexOf('<header'), pageSource.indexOf('</header>'));

  assert.doesNotMatch(headerSource, /New diagram/);
  assert.match(pageSource, /aria-label="Create new diagram"/);
  assert.match(pageSource, /Create new/);
  assert.match(pageSource, /Start blank or ask AI/);
  assert.match(pageSource, /Match saved-card height while keeping the create content centered over the full tile/);
  assert.match(pageSource, /absolute inset-0 flex flex-col items-center justify-center/);
  assert.match(pageSource, /cursor-pointer/);
  assert.match(pageSource, /bg-zinc-800/);
  assert.doesNotMatch(pageSource, /#fbbc04|#4285f4|#ea4335|#34a853/);
  assert.ok(pageSource.indexOf('aria-label="Create new diagram"') < pageSource.indexOf('{gridDiagrams.map'));
});

test('home diagram card metadata only shows the updated date', () => {
  const pagePath = fileURLToPath(new URL('../src/app/diagrams/page.tsx', import.meta.url));
  const pageSource = readFileSync(pagePath, 'utf8');
  const metadataStart = pageSource.indexOf('Keep card metadata focused');
  const metadataSource = pageSource.slice(
    metadataStart,
    pageSource.indexOf('</article>', metadataStart),
  );

  assert.match(metadataSource, /formatUpdatedAt\(diagram\.updatedAt\)/);
  assert.doesNotMatch(metadataSource, /diagram\.diagramType|diagram\.version|basic|v\{/);
});

test('home diagram cards use pointer cursors and stronger preview shadows', () => {
  const pagePath = fileURLToPath(new URL('../src/app/diagrams/page.tsx', import.meta.url));
  const pageSource = readFileSync(pagePath, 'utf8');
  const diagramListSource = pageSource.slice(pageSource.indexOf('{gridDiagrams.map'), pageSource.indexOf('{openMenuId === diagram.diagramId'));

  assert.match(diagramListSource, /cursor-pointer[^"]*shadow-md[^"]*hover:shadow-lg/);
  assert.match(diagramListSource, /line-clamp-2[^"]*cursor-pointer/);
});

test('home diagram cards leave missing thumbnails blank', () => {
  const pagePath = fileURLToPath(new URL('../src/app/diagrams/page.tsx', import.meta.url));
  const pageSource = readFileSync(pagePath, 'utf8');
  const diagramListSource = pageSource.slice(pageSource.indexOf('{gridDiagrams.map'), pageSource.indexOf('{openMenuId === diagram.diagramId'));

  assert.match(diagramListSource, /Missing thumbnails intentionally render as an empty white preview/);
  assert.doesNotMatch(diagramListSource, /h-12 w-16|rounded-full bg-zinc-200|grid grid-cols-2 gap-2/);
});
