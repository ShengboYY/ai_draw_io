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
const addMenuSource = fs.readFileSync(
  new URL('../src/features/sources/ComposerAddMenu.tsx', import.meta.url),
  'utf8',
);
const pageSource = fs.readFileSync(
  new URL('../src/app/drawio/page.tsx', import.meta.url),
  'utf8',
);
const sentPreviewSource = fs.readFileSync(
  new URL('../src/features/sources/MessageAttachmentPreview.tsx', import.meta.url),
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

test('refresh restores the backend conversation before hydrating draft attachments', () => {
  assert.match(
    pageSource,
    /const mostRecent = [^;]+;[\s\S]*setCurrentSessionId\(mostRecent\.id\);[\s\S]*setSessionId\(mostRecent\.backendSessionId \|\| ''\);/,
  );
  assert.match(
    pageSource,
    /readConversationAttachments\(window\.sessionStorage,\s*attachmentSessionId\)/,
  );
});

test('sent attachment bindings are owned by the V2 turn instead of a second message write', () => {
  assert.match(pageSource, /currentTurnAttachmentRefs:\s*turnAttachments\.map\(attachment => attachment\.uploadId\)/);
  assert.match(pageSource, /clientMessageId:\s*userMsg\.id/);
  assert.doesNotMatch(pageSource, /agentApi\.saveDiagramMessages/);
});

test('sending moves attachments out of the composer and suppresses late upload callbacks', () => {
  assert.match(
    pageSource,
    /setMessages\(prev => \[\.\.\.prev, userMsg, initialAgentMsg\]\);[\s\S]*setSentAttachmentUploadIds[\s\S]*setConversationAttachments\(\[\]\);[\s\S]*agentApi\.chatStream/,
  );
  assert.match(pageSource, /suppressedUploadIds=\{sentAttachmentUploadIds\}/);
  assert.match(traySource, /suppressedUploadIds=\{allSuppressedUploadIds\}/);
  assert.match(
    traySource,
    /onSuppressedUploadStatus=\{upload => \{[\s\S]*onSentAttachmentStatus\?\.\(upload\)/,
  );
  assert.match(
    pageSource,
    /onSentAttachmentStatus=\{attachment => \{[\s\S]*setMessages[\s\S]*item\.uploadId === attachment\.uploadId/,
  );
});

test('sent image and PDF attachments render a protected page preview', () => {
  assert.match(pageSource, /<MessageAttachmentPreview/);
  assert.match(pageSource, /materialClient\.previewUrl\(attachment\.materialId,\s*attachment\.versionId\)/);
  assert.match(sentPreviewSource, /<img[\s\S]*src=\{previewUrl\}/);
  assert.match(sentPreviewSource, /kind === 'pdf'/);
  assert.match(sentPreviewSource, /Preview unavailable/);
});

test('conversation attachment UI treats a succeeded upload as preview-ready', () => {
  assert.match(traySource, /isReadyUploadStatus\(normalizedState\)/);
  assert.match(pageSource, /isTerminalUploadStatus\(attachment\.state\)/);
  assert.match(traySource, /\{attachments\.map\(attachment =>/);
});

test('composer image attachments render only a protected thumbnail and remove button', () => {
  assert.match(traySource, /aria-label="Conversation attachments"/);
  assert.match(
    traySource,
    /previewUrl = ready && imageExtensions\.has\(extension\)[\s\S]*client\.previewUrl\(attachment\.materialId,\s*attachment\.versionId\)/,
  );
  assert.match(traySource, /<img[\s\S]*src=\{previewUrl\}/);
  assert.match(traySource, /alt=\{`Preview of \$\{fileName\}`\}/);
  assert.match(traySource, /aria-label=\{`Remove \$\{attachment\.fileName\}`\}/);
  assert.match(traySource, /onClick=\{\(\) => removeAttachment\(attachment\.uploadId\)\}/);
  assert.doesNotMatch(traySource, /statusLabel|Attachments<\/div>|attachmentStatusLabel|>\s*Retry\s*</);
});

test('clicking a composer image opens an accessible detail viewer', () => {
  assert.match(
    traySource,
    /if \(previewUrl\) setAttachmentDetail\(\{ fileName: attachment\.fileName, previewUrl \}\)/,
  );
  assert.match(traySource, /aria-label=\{previewUrl \? `View \$\{attachment\.fileName\}`/);
  assert.match(traySource, /<AttachmentDetailDialog detail=\{attachmentDetail\}/);
  assert.match(traySource, /createPortal\([\s\S]*role="dialog"[\s\S]*aria-modal="true"/);
  assert.match(traySource, /event\.key === 'Escape'/);
  assert.match(traySource, /event\.target === event\.currentTarget/);
  assert.match(traySource, /aria-label="Close image preview"/);
});

test('server material identity reaches the conversation attachment used for Files deduplication', () => {
  assert.match(uploaderSource, /materialId:\s*status\.materialId/);
  assert.match(uploaderSource, /versionId:\s*status\.versionId/);
  assert.match(uploaderSource, /reportServerStatus\(file,\s*completed\)/);
  assert.match(uploaderSource, /reportServerStatus\(file,\s*status\)/);
  assert.match(traySource, /onUploadStatus=\{upload => upsert\(upload\)\}/);
});

test('removing a composer attachment suppresses late upload callbacks', () => {
  assert.match(traySource, /dismissedUploadIdsRef\.current\.add\(uploadId\)/);
  assert.match(traySource, /previous\.filter\(item => item\.uploadId !== uploadId\)/);
  assert.match(traySource, /if \(dismissedUploadIdsRef\.current\.has\(next\.uploadId\)\) return/);
  assert.match(traySource, /suppressedUploadIds=\{allSuppressedUploadIds\}/);
});

test('composer presents conversation files as compact removable thumbnails', () => {
  assert.match(uploaderSource, /variant\?: 'panel' \| 'compact'/);
  assert.match(traySource, /variant="compact"/);
  assert.doesNotMatch(traySource, /仅用此项/);
  assert.match(pageSource, /ref=\{attachmentUploaderRef\}/);
  assert.match(pageSource, /attachmentUploaderRef\.current\?\.openPicker\(\)/);
  assert.match(uploaderSource, /target: preparedTarget \|\| target/);
  assert.match(traySource, /className="relative h-20 w-20 shrink-0"/);
  assert.match(traySource, /rounded-full[\s\S]*bg-zinc-900[\s\S]*text-white/);
});

test('composer plus offers ChatGPT-style Library and local file choices', () => {
  assert.match(pageSource, /title="Files"/);
  assert.match(pageSource, /<FilesPanel[\s\S]*onUpload=\{\(\) => attachmentUploaderRef\.current\?\.openPicker\(\)\}/);
  assert.match(pageSource, /<ComposerAddMenu/);
  assert.match(pageSource, /onUploadFromComputer=\{\(\) => attachmentUploaderRef\.current\?\.openPicker\(\)\}/);
  assert.match(addMenuSource, /Choose from Library/);
  assert.match(addMenuSource, /Upload from computer/);
  assert.match(addMenuSource, /aria-haspopup="menu"/);
  assert.match(addMenuSource, /menuContainerRef\.current\?\.contains\(event\.target\)/);
  assert.match(addMenuSource, /document\.addEventListener\('pointerdown', closeOnOutsidePointerDown\)/);
  assert.match(addMenuSource, /window\.addEventListener\('blur', closeOnWindowBlur\)/);
  assert.match(addMenuSource, /query: searchQuery\.trim\(\) \|\| undefined/);
  assert.match(addMenuSource, /client\.list\(\{/);
  assert.match(pageSource, /<ComposerLibrarySelectionTray/);
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
  assert.doesNotMatch(traySource, /min-w-\[190px\]|max-w-\[240px\]|attachmentStatusLabel/);
});
