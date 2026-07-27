import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const source = (relativePath) => {
  const pagePath = fileURLToPath(new URL(`../src/app/${relativePath}`, import.meta.url));
  return readFileSync(pagePath, 'utf8');
};

test('drawio page has a mobile canvas-first shell with overlay panels', () => {
  const pageSource = source('drawio/page.tsx');

  assert.match(pageSource, /h-\[100dvh\]/, 'mobile shell should use dynamic viewport height');
  assert.match(pageSource, /DRAWIO_MOBILE_BREAKPOINT = 640/, 'drawio mobile breakpoint should be explicit');
  assert.match(pageSource, /useState\(\(\) => \{[\s\S]*window\.innerWidth >= DRAWIO_MOBILE_BREAKPOINT/, 'assistant should default closed on phones');
  assert.match(pageSource, /fixed inset-x-0 bottom-0/, 'workspace rail should become a bottom bar on phones');
  assert.match(pageSource, /drawio-history-panel/, 'history should have a mobile overlay hook');
  assert.match(pageSource, /drawio-chat-panel/, 'assistant should have a mobile overlay hook');
  assert.match(pageSource, /drawio-chat-resizer/, 'desktop-only resizer should be identifiable');
});

test('home page has mobile rules for the drawing preview and card grids', () => {
  const pageSource = source('page.tsx');

  assert.match(pageSource, /@media \(max-width: 767px\)/, 'homepage should define phone-specific layout rules');
  assert.match(pageSource, /fd-preview-body/, 'drawing example preview should have a responsive layout hook');
  assert.match(pageSource, /fd-agent-preview/, 'agent side of the drawing example should stack on phones');
  assert.match(pageSource, /fd-example-row\{flex-wrap:wrap/, 'homepage prompt examples should wrap instead of clipping on phones');
  assert.match(pageSource, /fd-feature-grid/, 'feature cards should have a responsive grid hook');
  assert.match(pageSource, /fd-type-grid/, 'diagram type chips should use a mobile grid hook');
});

test('diagram library uses mobile search, filters, and shorter preview cards', () => {
  const pageSource = source('diagrams/page.tsx');
  const headerSource = readFileSync(
    fileURLToPath(new URL('../src/features/workspace/WorkspaceHeader.tsx', import.meta.url)), 'utf8');

  assert.match(pageSource, /aspect-\[16\/9\] sm:aspect-\[4\/3\]/, 'mobile cards should use a shorter preview aspect');
  assert.match(headerSource, /flex-wrap/, 'library header should wrap on phones');
  assert.match(pageSource, /overflow-x-auto/, 'filter tabs should scroll horizontally on phones');
  assert.match(pageSource, /sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4/, 'card grid should expand from one column');
});

test('login keeps the auth form focused on mobile', () => {
  const pageSource = source('login/page.tsx');

  assert.match(pageSource, /hidden lg:flex/, 'marketing panel should be desktop-only');
  assert.match(pageSource, /lg:hidden/, 'mobile form should show compact branding');
  assert.match(pageSource, /max-w-\[420px\] lg:max-w-\[880px\]/, 'login card should be narrow on phones and wide on desktop');
});
