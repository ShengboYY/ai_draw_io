# Draw.io selection/highlight spike

The application-side bridge now validates the exact iframe origin and `Window` source and sends
actions to an exact target origin. `zippSelection.js` is the matching pinned diagrams.net plugin.

To run the live browser check:

1. Build a pinned self-hosted diagrams.net image.
2. Copy `zippSelection.js` to its `src/main/webapp/plugins/` directory and add
   `'zippSelection': 'plugins/zippSelection.js'` to `App.pluginRegistry`.
3. Set `NEXT_PUBLIC_DRAWIO_BASE_URL` to that editor URL and rebuild the frontend.
4. Verify node, edge, and multi-selection events. Send a highlight with the current
   `(canvasVersion, contentHash)` and confirm the cells select; resend it with either stale value and
   confirm the plugin ignores it.

The public `embed.diagrams.net` editor is intentionally not given a custom plugin URL: its own code
blocks arbitrary cross-origin plugins. The official embed JSON protocol continues to work there,
but selection/highlight stays disabled until the pinned self-hosted editor is configured.
