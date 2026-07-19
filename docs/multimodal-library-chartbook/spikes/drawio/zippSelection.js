/**
 * diagrams.net plugin for the zipp-drawio-v1 selection/highlight bridge.
 * Copy this file into a pinned self-hosted diagrams.net build and register the plugin id
 * `zippSelection` in App.pluginRegistry. The hosted embed cannot load arbitrary cross-origin plugins.
 */
Draw.loadPlugin(function (ui) {
  var protocol = 'zipp-drawio-v1';
  var graph = ui.editor.graph;
  var context = null;
  var parentOrigin = null;

  try {
    parentOrigin = new URL(document.referrer).origin;
  } catch (e) {
    return;
  }

  function sendSelection() {
    if (context == null) return;
    var cells = graph.getSelectionCells();
    window.parent.postMessage(JSON.stringify({
      protocol: protocol,
      event: 'zippSelection',
      cellIds: cells.slice(0, 200).map(function (cell) { return cell.id; }),
      canvasVersion: context.canvasVersion,
      contentHash: context.contentHash
    }), parentOrigin);
  }

  graph.getSelectionModel().addListener(mxEvent.CHANGE, sendSelection);

  window.addEventListener('message', function (event) {
    if (event.source !== window.parent || event.origin !== parentOrigin) return;
    var message = null;
    try {
      message = typeof event.data === 'string' ? JSON.parse(event.data) : event.data;
    } catch (e) {
      return;
    }
    if (message == null || message.protocol !== protocol) return;

    if (message.action === 'zippCanvasContext') {
      context = {
        canvasVersion: message.canvasVersion,
        contentHash: message.contentHash
      };
      return;
    }

    if (message.action === 'zippHighlight') {
      // Reject candidate highlights calculated against a different server canvas snapshot.
      if (context == null
        || message.canvasVersion !== context.canvasVersion
        || message.contentHash !== context.contentHash
        || !Array.isArray(message.cellIds)) return;
      var cells = message.cellIds.slice(0, 200)
        .map(function (id) { return graph.getModel().getCell(id); })
        .filter(function (cell) { return cell != null; });
      graph.setSelectionCells(cells);
      if (cells.length > 0) graph.scrollCellToVisible(cells[0]);
    }
  });
});
