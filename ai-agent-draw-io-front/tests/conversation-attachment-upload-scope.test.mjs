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
const globalStyles = fs.readFileSync(
  new URL('../src/app/globals.css', import.meta.url),
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

test('server material identity reaches the conversation attachment used for Files deduplication', () => {
  assert.match(uploaderSource, /materialId:\s*status\.materialId/);
  assert.match(uploaderSource, /versionId:\s*status\.versionId/);
  assert.match(uploaderSource, /reportServerStatus\(file,\s*completed\)/);
  assert.match(uploaderSource, /reportServerStatus\(file,\s*status\)/);
  assert.match(traySource, /onUploadStatus=\{upload => upsert\(upload\)\}/);
});

test('conversation attachment UI explains terminal rejection categories', () => {
  assert.match(traySource, /REJECTED_SECURITY:\s*'安全检查未通过'/);
  assert.match(traySource, /REJECTED_LIMIT:\s*'文件超出处理限制'/);
  assert.match(traySource, /REJECTED_FORMAT:\s*'文件格式不受支持'/);
  assert.match(traySource, /statusLabel\(normalizedState,\s*attachment\.errorCode\)/);
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
});

test('composer retains a plus shortcut while Files owns conversation file management', () => {
  assert.match(pageSource, /title="Files"/);
  assert.match(pageSource, /<FilesPanel[\s\S]*onUpload=\{\(\) => attachmentUploaderRef\.current\?\.openPicker\(\)\}/);
  assert.match(
    pageSource,
    /aria-label="Add conversation file"[\s\S]*attachmentUploaderRef\.current\?\.openPicker\(\)[\s\S]*disabled=\{isSending \|\| !selectedAgentId\}[\s\S]*<Icons\.Plus/,
  );
  assert.doesNotMatch(pageSource, /<ComposerSourceMenu/);
  assert.doesNotMatch(pageSource, /<SourceModeControl/);
  assert.doesNotMatch(pageSource, /<SourcePicker/);
  assert.doesNotMatch(pageSource, /selectedAttachmentUploadIds/);
});

test('remaining demo quota stays lightweight in the composer footer', () => {
  assert.match(
    pageSource,
    /Files owns management; the composer keeps a lightweight upload shortcut[\s\S]*<div className="flex items-center gap-1\.5">[\s\S]*demoQuotaState\.visible && !demoQuotaState\.exhausted/,
  );
  assert.match(pageSource, /whitespace-nowrap font-mono text-\[11px\][\s\S]*demoQuotaState\.remaining\} left/);
  assert.doesNotMatch(pageSource, /mb-2 flex justify-end px-1/);
});

test('chat history and composer share one surface without overlapping', () => {
  const messageAreaClasses = pageSource.match(
    /\{\/\* Messages Area \*\/\}\s*<div className="([^"]*)"/,
  )?.[1] || '';
  const inputAreaClasses = pageSource.match(
    /\{\/\* Input Area \*\/\}\s*<div className="([^"]*)"/,
  )?.[1] || '';

  assert.match(
    pageSource,
    /drawio-chat-panel relative flex flex-col[\s\S]*\{\/\* Messages Area \*\/\}[\s\S]*\{\/\* Input Area \*\/\}/,
  );
  assert.match(messageAreaClasses, /min-h-0/);
  assert.match(messageAreaClasses, /flex-1/);
  assert.match(messageAreaClasses, /overflow-y-auto/);
  assert.match(inputAreaClasses, /shrink-0/);
  assert.match(inputAreaClasses, /bg-\[var\(--app-bg\)\]/);
  assert.doesNotMatch(inputAreaClasses, /border-t/);
  assert.doesNotMatch(inputAreaClasses, /shadow-\[0_-4px/);
});

test('composer uses a Codex-style vertical layout', () => {
  const composerTextareaClasses = pageSource.match(
    /<textarea\s+ref=\{promptInputRef\}[\s\S]*?className="([^"]*)"/,
  )?.[1] || '';
  assert.match(
    pageSource,
    /min-h-\[176px\] flex-col rounded-\[26px\][\s\S]*<ConversationAttachmentTray[\s\S]*<textarea[\s\S]*mt-auto flex items-center justify-between[\s\S]*aria-label="Deterministic repair rounds"[\s\S]*aria-label="Model"[\s\S]*<Icons\.ArrowUp/,
  );
  assert.match(pageSource, /COMPOSER_TEXTAREA_MIN_HEIGHT_PX = 84/);
  assert.match(composerTextareaClasses, /composer-textarea/);
  assert.match(globalStyles, /textarea\.composer-textarea:focus-visible\s*\{\s*outline:\s*none;/);
  assert.match(traySource, /flex gap-2 overflow-x-auto/);
  assert.match(traySource, /h-\[68px\] min-w-\[190px\] max-w-\[240px\][\s\S]*rounded-2xl/);
});
