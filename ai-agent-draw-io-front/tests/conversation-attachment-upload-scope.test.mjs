import assert from 'node:assert/strict';
import fs from 'node:fs';
import test from 'node:test';

const uploaderSource = fs.readFileSync(
  new URL('../src/features/materials/MaterialUploader.tsx', import.meta.url),
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

test('conversation attachments establish diagram ownership before upload initiation', () => {
  assert.match(uploaderSource, /await beforeUpload\?\.\(\);[\s\S]*client\.initiate/);
  assert.match(traySource, /target=\{\{[\s\S]*scopeType: 'CONVERSATION'[\s\S]*diagramId[\s\S]*\}\}/);
  assert.match(traySource, /beforeUpload=\{onPrepareUpload\}/);
});

test('conversation attachment UI treats a succeeded upload as terminal and ready', () => {
  assert.match(traySource, /SUCCEEDED:\s*'已就绪'/);
  assert.match(traySource, /isTerminalUploadStatus\(item\.state\)/);
  assert.match(pageSource, /isTerminalUploadStatus\(attachment\.state\)/);
});

test('composer presents conversation files as compact attachments', () => {
  assert.match(uploaderSource, /variant\?: 'panel' \| 'compact'/);
  assert.match(traySource, /variant="compact"/);
  assert.doesNotMatch(traySource, /仅用此项/);
  assert.match(pageSource, /ref=\{attachmentUploaderRef\}/);
  assert.match(pageSource, /attachmentUploaderRef\.current\?\.openPicker\(\)/);
  assert.match(uploaderSource, /target: preparedTarget \|\| target/);
  assert.match(traySource, /retryUpload\(attachment\.uploadId\)/);
  assert.match(traySource, /retryable\.has\(attachment\.uploadId\)/);
  assert.match(uploaderSource, /item\.uploadId && item\.retryable/);
  assert.match(traySource, /normalizedState === 'PARTIAL_READY'/);
  assert.match(pageSource, /资料与引用/);
});

test('composer uses a Codex-style vertical layout', () => {
  assert.match(
    pageSource,
    /min-h-\[196px\] flex-col rounded-\[26px\][\s\S]*<ConversationAttachmentTray[\s\S]*<textarea[\s\S]*mt-auto flex items-center justify-between[\s\S]*aria-label="Deterministic repair rounds"[\s\S]*aria-label="Model"[\s\S]*<Icons\.ArrowUp/,
  );
  assert.match(pageSource, /COMPOSER_TEXTAREA_MIN_HEIGHT_PX = 104/);
  assert.match(traySource, /flex gap-2 overflow-x-auto/);
  assert.match(traySource, /h-\[68px\] min-w-\[190px\] max-w-\[240px\][\s\S]*rounded-2xl/);
});
