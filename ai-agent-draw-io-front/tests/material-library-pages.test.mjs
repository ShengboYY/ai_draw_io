import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const read = (path) => readFileSync(fileURLToPath(new URL(path, import.meta.url)), 'utf8');
const library = read('../src/app/library/page.tsx');
const materialDetails = read('../src/app/library/details/page.tsx');
const chartbooks = read('../src/app/chartbooks/page.tsx');
const chartbookDetails = read('../src/app/chartbooks/details/page.tsx');
const chartbookShelf = read('../src/features/chartbooks/use-chartbook-shelf.ts');
const folderGrid = read('../src/features/chartbooks/ChartbookFolderGrid.tsx');
const createDialog = read('../src/features/chartbooks/ChartbookCreateDialog.tsx');
const diagramMoveDialog = read('../src/features/chartbooks/DiagramMoveDialog.tsx');
const diagramRenameDialog = read('../src/features/chartbooks/DiagramRenameDialog.tsx');
const diagramActionMenu = read('../src/features/diagrams/DiagramActionMenu.tsx');
const itemPicker = read('../src/features/chartbooks/ChartbookItemPicker.tsx');
const fileList = read('../src/features/chartbooks/ChartbookFileList.tsx');
const workspaceHeader = read('../src/features/workspace/WorkspaceHeader.tsx');
const preview = read('../src/features/materials/MaterialPreview.tsx');
const uploader = read('../src/features/materials/MaterialUploader.tsx');
const processingBadge = read('../src/features/materials/MaterialProcessingBadge.tsx');
const gapDialog = read('../src/features/materials/MaterialGapDialog.tsx');
const libraryView = read('../src/features/materials/library-view.ts');
const diagrams = read('../src/app/diagrams/page.tsx');
const drawio = read('../src/app/drawio/page.tsx');

test('library pages use capability gating, uploader, recycle bin and generic owner errors', () => {
  assert.match(library, /createMaterialCapabilitiesClient/);
  assert.match(library, /MaterialUploader/);
  assert.match(library, /'TRASHED'/);
  assert.match(library, /materialClient\.restore/);
  assert.match(library, /queryInput/);
  assert.match(library, /setOffset/);
  assert.match(materialDetails, /materialAccessMessage/);
  assert.match(materialDetails, /permanentlyDelete/);
  assert.match(materialDetails, /replaceExcludedPages/);
  assert.match(materialDetails, /nativeTextStatus/);
  assert.match(materialDetails, /moveToTrash/);
});

test('material detail renders only a selected page preview and never fetches source content', () => {
  assert.match(materialDetails, /materialClient\.pageSet/);
  assert.match(materialDetails, /<MaterialPreview/);
  assert.match(preview, /selectedPage/);
  assert.match(preview, /materialPreviewUrl/);
  assert.doesNotMatch(preview, /fetch\(/);
});

test('chartbook pages manage shared material and diagram associations through their R1 clients', () => {
  assert.match(chartbookShelf, /chartbookClient\.create/);
  assert.match(chartbookShelf, /chartbookClient\.archive/);
  assert.match(chartbookDetails, /chartbookClient\.assignDiagram/);
  assert.match(chartbookDetails, /chartbookClient\.removeDiagram/);
  assert.match(chartbookDetails, /chartbookClient\.removeFile/);
  assert.match(chartbookDetails, /chartbookClient\.rename/);
  assert.match(chartbookDetails, /MaterialUploader/);
});

test('an open chartbook reuses the workspace shell and lists its diagrams and files', () => {
  // Same chrome as My diagrams, without the search box.
  assert.match(chartbookDetails, /<WorkspaceHeader identity=\{identity\}/);
  assert.doesNotMatch(chartbookDetails, /search=\{/);
  assert.match(workspaceHeader, /\{search && \(/);
  // Diagrams tab mirrors the workspace grid, including the create tile.
  assert.match(chartbookDetails, /aria-label="Create new diagram"/);
  assert.match(chartbookDetails, /new=1&chartbookId=/);
  assert.match(chartbookDetails, /role="tablist" aria-label="Chartbook contents"/);
  assert.match(chartbookDetails, /<ChartbookFileList/);
  // Toolbar affordances are icon-only, so each one carries its own label.
  assert.match(chartbookDetails, /aria-label="Rename chartbook"[\s\S]*<PencilIcon/);
  assert.match(chartbookDetails, /aria-label="Import from My diagrams"[\s\S]*<ImportIcon/);
  assert.match(chartbookDetails, /aria-label="Upload files from this device"[\s\S]*<UploadIcon/);
  assert.match(chartbookDetails, /aria-label="Add files from the library"[\s\S]*<LibraryIcon/);
  // Files arrive either from the device or from the existing library catalog.
  assert.match(chartbookDetails, /uploaderRef\.current\?\.openPicker\(\)/);
  assert.match(chartbookDetails, /chartbookClient\.addFile\(chartbookId, materialId/);
  assert.match(chartbookDetails, /materialClient\.list\(\{ lifecycleState: 'ACTIVE'/);
  // The files tab reports name, size and added date per file.
  assert.match(fileList, /formatBytes/);
  assert.match(fileList, /Added/);
  assert.match(chartbookDetails, /materialClient\.details\(file\.materialId\)/);
  // Leaving returns to whichever surface opened the chartbook.
  assert.match(chartbookDetails, /window\.history\.length > 1\) router\.back\(\)/);
  // A diagram created from inside the chartbook is filed there on its first save.
  assert.match(drawio, /pendingChartbookAssignmentRef/);
  assert.match(drawio, /freshParams\.get\('chartbookId'\)/);
  assert.match(drawio, /diagramId: newSession\.diagramId/);
  assert.match(drawio, /assignPendingChartbookAfterSave\(request\.diagramId\)/);
  assert.match(drawio, /await chartbookClient\.assignDiagram\(diagramId, pending\.chartbookId\)[\s\S]*pendingChartbookAssignmentRef\.current = null/);
  assert.match(drawio, /pending\.inFlight = false/);
});

test('chartbook diagram actions support renaming and moving to another destination', () => {
  assert.match(chartbookDetails, /<DiagramActionMenu[\s\S]*label: 'Rename'[\s\S]*label: 'Move'/);
  assert.match(diagramActionMenu, /role="menu"[\s\S]*actions\.map/);
  assert.match(chartbookDetails, /agentApi\.renameDiagram\(ownerId, renamingDiagram\.diagramId, name\)/);
  assert.match(chartbookDetails, /chartbookClient\.assignDiagram\(movingDiagram\.diagramId, destinationId\)/);
  assert.match(chartbookDetails, /chartbookClient\.removeDiagram\(movingDiagram\.diagramId\)/);
  assert.match(chartbookDetails, /<DiagramMoveDialog/);
  assert.match(chartbookDetails, /<DiagramRenameDialog/);
  assert.match(diagramMoveDialog, /My diagrams/);
  assert.match(diagramMoveDialog, /Other chartbooks/);
  assert.match(diagramRenameDialog, /role="dialog"/);
});

test('the chartbooks route and the diagrams tab share one chartbook shelf implementation', () => {
  for (const source of [chartbooks, diagrams]) {
    assert.match(source, /useChartbookShelf/);
    assert.match(source, /<ChartbookCreateDialog/);
    assert.match(source, /onCreate=\{/);
  }
  assert.match(chartbooks, /<ChartbookFolderGrid/);
  // Creating a chartbook uses the same tile shape as the workspace's create-new diagram tile.
  assert.match(folderGrid, /onCreate && <ChartbookCreateCard onCreate=\{onCreate\} \/>/);
  assert.match(folderGrid, /aria-label="Create new chartbook"/);
  assert.match(folderGrid, /border-2 border-dashed border-stone-300 bg-stone-50/);
  // On the diagrams page folders are cells of the shared workspace grid, not a separate section.
  assert.match(diagrams, /isFolderView && visibleChartbooks\.map\(chartbook => \(\s*<ChartbookFolderCard/);
  assert.doesNotMatch(diagrams, /aria-label="Chartbooks"/);
  // Folder tiles keep opening the static-export-compatible chartbook detail route.
  assert.match(folderGrid, /const href = chartbookDetailsHref\(chartbook\.chartbookId\)/);
});

test('dragging one diagram onto another groups both into a new chartbook', () => {
  // The gesture reuses the R1 contract: create the folder, then move each diagram in.
  assert.match(chartbookShelf, /chartbookClient\.assignDiagram\(diagramId, folder\.chartbookId\)/);
  assert.match(chartbookShelf, /groupDiagrams/);
  assert.match(diagrams, /draggable=\{canGroupByDrag\}/);
  assert.match(diagrams, /setData\(DIAGRAM_DRAG_MIME, diagram\.diagramId\)/);
  assert.match(diagrams, /groupDiagramsIntoChartbook\(event\.dataTransfer\.getData\(DIAGRAM_DRAG_MIME\), diagram\)/);
  // Naming happens in an in-page dialog rather than window.prompt.
  assert.match(diagrams, /<ChartbookCreateDialog/);
  assert.doesNotMatch(diagrams, /window\.prompt\('Name the new chartbook'/);
  assert.match(createDialog, /role="dialog"/);
  assert.match(createDialog, /event\.key === 'Escape'/);
  // Folders accept the same drag so a diagram can join an existing chartbook.
  assert.match(folderGrid, /onDropDiagram\?\.\(chartbook, diagramId\)/);
  assert.match(diagrams, /onDropDiagram=\{\(folder, diagramId\) => void moveDiagramIntoChartbook\(folder, diagramId\)\}/);
});

test('the create dialog offers optional diagram picking and file upload', () => {
  // Both extras are opt-in: the sections only render when the caller supplies them.
  assert.match(createDialog, /\{diagramOptions && diagramOptions\.length > 0 && \(/);
  assert.match(createDialog, /\{uploadTools && \(/);
  assert.match(itemPicker, /type="checkbox"/);
  assert.match(createDialog, /<ChartbookItemPicker/);
  // Uploads need a server-side scope, so the folder is created before bytes are sent.
  assert.match(createDialog, /beforeUpload=\{prepareUploadTarget\}/);
  assert.match(createDialog, /scopeType: 'CHARTBOOK'/);
  assert.match(createDialog, /disabled=\{!canUpload\}/);
  // ensureChartbook runs at most once per dialog, whichever step needs it first.
  assert.match(createDialog, /if \(created\) return created;/);
  assert.match(diagrams, /ensureChartbook=\{ensureChartbookForRequest\}/);
  assert.match(diagrams, /chartbookShelf\.addDiagram\(chartbook\.chartbookId, diagramId\)/);
  assert.match(diagrams, /capabilities\?\.upload === 'AVAILABLE'/);
});

test('diagram workspace exposes chartbooks as a workspace tab plus the library route in the account menu', () => {
  assert.match(workspaceHeader, /data-account-menu[\s\S]*href="\/library"[\s\S]*Chartbooks[\s\S]*Admin dashboard/);
  assert.match(diagrams, /onChartbooks=\{\(\) => chooseTab\(CHARTBOOKS_TAB\)\}/);
  assert.match(diagrams, /WORKSPACE_TABS\.map/);
  assert.match(diagrams, /chooseTab\(CHARTBOOKS_TAB\)/);
  assert.doesNotMatch(diagrams, /aria-label="Workspace resources"/);
});

test('library and chartbook surfaces use English interface copy', () => {
  // Keep the two resource areas consistent with the English diagram workspace.
  for (const source of [
    library,
    materialDetails,
    chartbooks,
    chartbookDetails,
    chartbookShelf,
    folderGrid,
    createDialog,
    diagramMoveDialog,
    diagramRenameDialog,
    itemPicker,
    fileList,
    preview,
    uploader,
    processingBadge,
    gapDialog,
    libraryView,
  ]) {
    assert.doesNotMatch(source, /[\u3400-\u9fff]/u);
  }
});

test('uploader keeps the current browser-post policy and resumes polling after a retry', () => {
  assert.match(uploader, /postPolicy/);
  assert.match(uploader, /completeAndPoll/);
  assert.match(uploader, /uploadBytesAndComplete/);
});
