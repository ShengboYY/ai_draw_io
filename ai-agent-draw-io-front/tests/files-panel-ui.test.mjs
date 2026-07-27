import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const panelSource = fs.readFileSync(
  new URL('../src/features/files/FilesPanel.tsx', import.meta.url),
  'utf8',
);
const traySource = fs.readFileSync(
  new URL('../src/features/sources/ConversationAttachmentTray.tsx', import.meta.url),
  'utf8',
);
const pageSource = fs.readFileSync(
  new URL('../src/app/drawio/page.tsx', import.meta.url),
  'utf8',
);

test('files rail and history open mutually exclusive panels', () => {
  assert.match(pageSource, /title="Files"/);
  assert.match(pageSource, /setIsFilesPanelOpen\(prev => !prev\)/);
  assert.match(pageSource, /setIsSidebarOpen\(false\)/);
  assert.match(pageSource, /setIsFilesPanelOpen\(false\)/);
  assert.match(pageSource, /<FilesPanel/);
});

test('files panel owns upload and scoped file actions', () => {
  assert.match(panelSource, />\s*Upload\s*</);
  assert.match(panelSource, /Chartbook Shared Files/);
  assert.match(panelSource, /Conversation Files/);
  assert.match(panelSource, /chartbookSharedFiles !== undefined/);
  assert.match(panelSource, /Add to Chartbook/);
  assert.match(panelSource, /Remove from Chartbook/);
  assert.match(panelSource, /Remove from Conversation/);
  assert.match(panelSource, /Preview/);
  assert.match(panelSource, /Download/);
  assert.match(panelSource, /Retry/);
  assert.match(panelSource, /file\.searchStatus/);
  assert.match(panelSource, /PendingUploadRow/);
  assert.match(panelSource, /Retry Upload/);
  assert.match(panelSource, /groups\.chartbookSharedFiles \|\| \[\]/);
  assert.match(pageSource, /uploads=\{conversationAttachments\}/);
  assert.match(pageSource, /onRetryUpload=\{upload =>/);
  assert.match(pageSource, /file\.retentionClass === 'TEMPORARY'/);
  assert.match(pageSource, /scope\.scopeType === 'CONVERSATION' && scope\.scopeKey === sessionId/);
  assert.match(pageSource, /details\.scopes\.length > 1[\s\S]*materialClient\.removeScope/);
});

test('chartbook shared files load before a diagram has a conversation', () => {
  // Blank diagrams still have a chartbook scope even though their conversation id is empty.
  assert.match(pageSource, /if \(!conversationId && !currentDiagramId\)/);
  assert.match(pageSource, /conversationId\s*\?\s*materialClient\.listScope\('CONVERSATION'/);
  assert.match(pageSource, /setConversationFiles\(conversationPage\?\.items \|\| \[\]\)/);
});

test('composer has no file checkbox or source routing controls', () => {
  assert.doesNotMatch(traySource, /type="checkbox"/);
  assert.doesNotMatch(pageSource, /<SourceUseControl/);
  assert.doesNotMatch(pageSource, /<ComposerSourceMenu/);
  assert.doesNotMatch(pageSource, /selectedAttachmentUploadIds/);
  assert.doesNotMatch(pageSource, /selectedVersionIds/);
  assert.doesNotMatch(pageSource, /sourceUsePreference/);
  assert.match(traySource, /attachments\.map/);
  assert.match(pageSource, /hasProcessingAttachments/);
  assert.match(pageSource, /Wait for attachments to finish/);
});
