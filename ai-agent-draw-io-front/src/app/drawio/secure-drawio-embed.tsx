'use client';

import {
  forwardRef,
  useCallback,
  useEffect,
  useImperativeHandle,
  useMemo,
  useRef,
  useState,
} from 'react';
import type { DrawIoEmbedProps, DrawIoEmbedRef as LibraryDrawIoEmbedRef } from 'react-drawio';
import {
  createCanvasContextAction,
  createHighlightAction,
  parseSelectionEvent,
  type DrawioSelection,
} from './secure-drawio-bridge';

type ActionData = Record<string, unknown>;

export type DrawIoEmbedRef = LibraryDrawIoEmbedRef & {
  highlightCells: (cellIds: string[], canvasVersion: number, contentHash: string) => void;
};

type SecureDrawIoEmbedProps = DrawIoEmbedProps & {
  canvasVersion?: number;
  contentHash?: string;
  selectionPluginId?: string;
  onSelectionChange?: (selection: DrawioSelection) => void;
};

const eventData = (data: unknown): Record<string, unknown> | null => {
  try {
    const parsed = typeof data === 'string' ? JSON.parse(data) : data;
    return parsed && typeof parsed === 'object' ? parsed as Record<string, unknown> : null;
  } catch {
    return null;
  }
};

export const DrawIoEmbed = forwardRef<DrawIoEmbedRef, SecureDrawIoEmbedProps>((props, ref) => {
  const {
    autosave = false,
    baseUrl = 'https://embed.diagrams.net',
    urlParameters,
    configuration,
    xml,
    csv,
    exportFormat,
    canvasVersion,
    contentHash,
    selectionPluginId,
  } = props;
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const [initialized, setInitialized] = useState(false);
  const callbacksRef = useRef(props);
  useEffect(() => {
    callbacksRef.current = props;
  }, [props]);

  const iframeUrl = useMemo(() => {
    const url = new URL(baseUrl);
    url.searchParams.set('embed', '1');
    url.searchParams.set('proto', 'json');
    if (configuration) url.searchParams.set('configure', '1');
    Object.entries(urlParameters || {}).forEach(([key, value]) => {
      if (value !== undefined) url.searchParams.set(key, typeof value === 'boolean' ? (value ? '1' : '0') : String(value));
    });
    // Custom plugins are deliberately enabled only for the configured self-hosted/forked editor.
    if (selectionPluginId) url.searchParams.set('p', selectionPluginId);
    return url.toString();
  }, [baseUrl, configuration, selectionPluginId, urlParameters]);
  const expectedOrigin = useMemo(() => new URL(iframeUrl).origin, [iframeUrl]);

  const send = useCallback((payload: ActionData) => {
    iframeRef.current?.contentWindow?.postMessage(JSON.stringify(payload), expectedOrigin);
  }, [expectedOrigin]);
  const action = useMemo(() => ({
    load: (data: Parameters<LibraryDrawIoEmbedRef['load']>[0]) => send({ action: 'load', ...data }),
    configure: (data: Parameters<LibraryDrawIoEmbedRef['configure']>[0]) => send({ action: 'configure', ...data }),
    merge: (data: Parameters<LibraryDrawIoEmbedRef['merge']>[0]) => send({ action: 'merge', ...data }),
    dialog: (data: Parameters<LibraryDrawIoEmbedRef['dialog']>[0]) => send({ action: 'dialog', ...data }),
    prompt: (data: Parameters<LibraryDrawIoEmbedRef['prompt']>[0]) => send({ action: 'prompt', ...data }),
    template: (data: Parameters<LibraryDrawIoEmbedRef['template']>[0]) => send({ action: 'template', ...data }),
    layout: (data: Parameters<LibraryDrawIoEmbedRef['layout']>[0]) => send({ action: 'layout', ...data }),
    draft: (data: Parameters<LibraryDrawIoEmbedRef['draft']>[0]) => send({ action: 'draft', ...data }),
    status: (data: Parameters<LibraryDrawIoEmbedRef['status']>[0]) => send({ action: 'status', ...data }),
    spinner: (data: Parameters<LibraryDrawIoEmbedRef['spinner']>[0]) => send({ action: 'spinner', ...data }),
    exportDiagram: (data: Parameters<LibraryDrawIoEmbedRef['exportDiagram']>[0]) => send({ action: 'export', ...data }),
    highlightCells: (cellIds: string[], version: number, hash: string) => send(
      createHighlightAction(cellIds, version, hash),
    ),
  }), [send]);
  useImperativeHandle(ref, () => action, [action]);

  useEffect(() => {
    const handler = (event: MessageEvent) => {
      if (event.origin !== expectedOrigin || event.source !== iframeRef.current?.contentWindow) return;
      const selection = parseSelectionEvent({
        eventOrigin: event.origin,
        expectedOrigin,
        eventSource: event.source,
        expectedSource: iframeRef.current?.contentWindow,
        data: event.data,
      });
      if (selection) {
        callbacksRef.current.onSelectionChange?.(selection);
        return;
      }

      const data = eventData(event.data);
      if (!data || typeof data.event !== 'string') return;
      const callbacks = callbacksRef.current;
      switch (data.event) {
        case 'init': setInitialized(true); break;
        case 'load': callbacks.onLoad?.(data as Parameters<NonNullable<DrawIoEmbedProps['onLoad']>>[0]); break;
        case 'configure':
          if (callbacks.configuration) send({ action: 'configure', config: callbacks.configuration });
          callbacks.onConfigure?.(data as Parameters<NonNullable<DrawIoEmbedProps['onConfigure']>>[0]);
          break;
        case 'autosave': callbacks.onAutoSave?.(data as Parameters<NonNullable<DrawIoEmbedProps['onAutoSave']>>[0]); break;
        case 'save':
          send({ action: 'export', format: callbacks.exportFormat || 'xmlsvg', exit: data.exit, parentEvent: 'save' });
          break;
        case 'exit': callbacks.onClose?.(data as Parameters<NonNullable<DrawIoEmbedProps['onClose']>>[0]); break;
        case 'draft': callbacks.onDraft?.(data as Parameters<NonNullable<DrawIoEmbedProps['onDraft']>>[0]); break;
        case 'merge': callbacks.onMerge?.(data as Parameters<NonNullable<DrawIoEmbedProps['onMerge']>>[0]); break;
        case 'prompt': callbacks.onPrompt?.(data as Parameters<NonNullable<DrawIoEmbedProps['onPrompt']>>[0]); break;
        case 'template': callbacks.onTemplate?.(data as Parameters<NonNullable<DrawIoEmbedProps['onTemplate']>>[0]); break;
        case 'export': {
          callbacks.onExport?.(data as Parameters<NonNullable<DrawIoEmbedProps['onExport']>>[0]);
          if (callbacks.onSave) {
            const message = data.message && typeof data.message === 'object'
              ? data.message as Record<string, unknown> : {};
            callbacks.onSave({
              event: 'save',
              xml: String(data.data || ''),
              parentEvent: String(message.parentEvent || 'export'),
            });
          }
          break;
        }
      }
    };
    window.addEventListener('message', handler);
    return () => window.removeEventListener('message', handler);
  }, [expectedOrigin, send]);

  useEffect(() => {
    if (!initialized) return;
    if (xml) action.load(exportFormat === 'xmlpng' ? { xmlpng: xml, autosave } : { xml, autosave });
    else if (csv) action.load({ descriptor: { format: 'csv', data: csv }, autosave });
    else action.load({ xml: '', autosave });
  }, [action, autosave, csv, exportFormat, initialized, xml]);

  useEffect(() => {
    if (!initialized || canvasVersion === undefined || !contentHash) return;
    send(createCanvasContextAction(canvasVersion, contentHash));
  }, [canvasVersion, contentHash, initialized, send]);

  return (
    <iframe
      className="diagrams-iframe"
      src={iframeUrl}
      ref={iframeRef}
      allow="clipboard-read; clipboard-write"
      title="Diagrams.net"
      style={{ width: '100%', height: '100%', minWidth: '400px', minHeight: '400px', border: 'none' }}
    />
  );
});

DrawIoEmbed.displayName = 'SecureDrawIoEmbed';
