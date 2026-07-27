import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const read = (path) => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8');

const adminShell = read('../src/app/admin/admin-shell.tsx');
const accountMenu = read('../src/app/admin/admin-account-menu.tsx');
const diagrams = read('../src/app/diagrams/page.tsx');
const workspaceHeader = read('../src/features/workspace/WorkspaceHeader.tsx');
const drawio = read('../src/app/drawio/page.tsx');
const landing = read('../src/app/page.tsx');
const login = read('../src/app/login/page.tsx');
const authVisuals = read('../src/app/auth-visuals.tsx');

test('workspace brand links return home without a full-page navigation', () => {
  assert.match(adminShell, /href="\/"[^>]+aria-label="FreeDraw home"/);
  // The diagrams workspace renders the brand mark through the shared header.
  assert.match(workspaceHeader, /href="\/"[^>]+aria-label="FreeDraw home"/);
  assert.match(diagrams, /<WorkspaceHeader/);
  assert.doesNotMatch(drawio, /window\.location\.href = '\/diagrams'/);
});

test('drawio brand icon returns to the page that opened the editor', () => {
  assert.match(drawio, /onClick=\{handleBackToSourcePage\}/);
  assert.match(drawio, /window\.history\.length > 1/);
  assert.match(drawio, /router\.back\(\)/);
  assert.match(drawio, /router\.replace\('\/diagrams'\)/);
  assert.doesNotMatch(drawio, /<Link\s+href="\/"\s+aria-label="FreeDraw home"/);
});

test('public-page brand marks link home without clearing authentication', () => {
  assert.match(landing, /href="\/" aria-label="FreeDraw home"/);
  assert.match(login, /href="\/" aria-label="FreeDraw home"/);
  assert.match(authVisuals, /href="\/" aria-label="FreeDraw home"/);
  assert.match(landing, /isSignedIn/);
  assert.match(landing, /AdminAccountMenu logoutHref="\/"/);
  assert.match(accountMenu, /My diagrams/);
  assert.match(accountMenu, /isAdmin &&/);
  assert.match(accountMenu, /Admin dashboard/);
  assert.match(accountMenu, /router\.replace\(logoutHref\)/);
  assert.doesNotMatch(landing, /router\.replace\('\/diagrams'\)/);
});

test('homepage intro is paced, session-aware, and respects reduced motion', () => {
  assert.match(landing, /sessionStorage\.getItem\(HOME_INTRO_SESSION_KEY\)/);
  assert.match(landing, /nextVariant === 'full' \? 4300 : 850/);
  assert.match(landing, /prefers-reduced-motion: reduce/);
  assert.match(landing, /fd-scene-full/);
  assert.match(landing, /fdReveal 2s ease-out/);
  assert.match(landing, /fd-scene-quick/);
  assert.match(landing, /\.fd-scene\{display:block;background:#14161a;border-radius:24%/);
  assert.doesNotMatch(landing, /animationDelay: '3\./);
});
