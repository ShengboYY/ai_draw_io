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
const composerSourceMenuSource = fs.readFileSync(
  new URL('../src/features/sources/ComposerSourceMenu.tsx', import.meta.url),
  'utf8',
);
const sourceModeControlSource = fs.readFileSync(
  new URL('../src/features/sources/SourceModeControl.tsx', import.meta.url),
  'utf8',
);
const sourcePickerSource = fs.readFileSync(
  new URL('../src/features/sources/SourcePicker.tsx', import.meta.url),
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

test('composer plus menu owns local uploads, library selection, and source mode', () => {
  assert.match(pageSource, /<ComposerSourceMenu/);
  assert.match(pageSource, /onUploadLocal=\{\(\) => attachmentUploaderRef\.current\?\.openPicker\(\)\}/);
  assert.doesNotMatch(pageSource, />\s*资料与引用\s*</);
  assert.match(composerSourceMenuSource, /Local upload/);
  assert.match(composerSourceMenuSource, /Choose from library/);
  assert.match(composerSourceMenuSource, /w-72/);
  assert.match(sourceModeControlSource, /Auto \(Recommended\)/);
  assert.match(sourcePickerSource, /Choose sources/);
  assert.doesNotMatch(
    `${composerSourceMenuSource}\n${sourceModeControlSource}\n${sourcePickerSource}`,
    /[\u3400-\u9fff]/,
  );
  assert.match(composerSourceMenuSource, /sourceModeAfterVersionSelection\(sourceMode, versionIds\.length\)/);
  assert.match(composerSourceMenuSource, /onUploadLocal\(\);[\s\S]*setOpen\(false\)/);
  assert.match(composerSourceMenuSource, /firstActionRef\.current\?\.focus\(\)/);
  assert.match(composerSourceMenuSource, /if \(!disabled\) return;[\s\S]*setOpen\(false\)/);
});

test('remaining demo quota sits beside the composer add button', () => {
  assert.match(
    pageSource,
    /<div className="flex items-center gap-1\.5">[\s\S]*<ComposerSourceMenu[\s\S]*demoQuotaState\.visible && !demoQuotaState\.exhausted/,
  );
  assert.match(pageSource, /whitespace-nowrap font-mono text-\[11px\][\s\S]*demoQuotaState\.remaining\} left/);
  assert.doesNotMatch(pageSource, /mb-2 flex justify-end px-1/);
});

test('composer uses a Codex-style vertical layout', () => {
  const composerTextareaClasses = pageSource.match(
    /<textarea\s+ref=\{promptInputRef\}[\s\S]*?className="([^"]*)"/,
  )?.[1] || '';
  assert.match(
    pageSource,
    /min-h-\[196px\] flex-col rounded-\[26px\][\s\S]*<ConversationAttachmentTray[\s\S]*<textarea[\s\S]*mt-auto flex items-center justify-between[\s\S]*aria-label="Deterministic repair rounds"[\s\S]*aria-label="Model"[\s\S]*<Icons\.ArrowUp/,
  );
  assert.match(pageSource, /COMPOSER_TEXTAREA_MIN_HEIGHT_PX = 104/);
  assert.match(composerTextareaClasses, /composer-textarea/);
  assert.match(globalStyles, /textarea\.composer-textarea:focus-visible\s*\{\s*outline:\s*none;/);
  assert.match(traySource, /flex gap-2 overflow-x-auto/);
  assert.match(traySource, /h-\[68px\] min-w-\[190px\] max-w-\[240px\][\s\S]*rounded-2xl/);
});
