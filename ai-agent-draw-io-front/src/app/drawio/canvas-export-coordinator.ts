export type CanvasExportPurpose =
  | 'chat-xml'
  | 'autosave-xml'
  | 'thumbnail-png'
  | 'visual-review-png';

export type CanvasExportFormat = 'xmlsvg' | 'png';

export type CanvasExportPayload = {
  data?: string;
  xml?: string;
  format?: string;
  requestId?: string;
};

export type CanvasExportRequest = {
  purpose: CanvasExportPurpose;
  diagramId: string;
  sessionId: string;
  format: CanvasExportFormat;
  options: { format: CanvasExportFormat; [key: string]: string | boolean };
  timeoutMs?: number;
};

export type CanvasExportResult = CanvasExportPayload & Pick<CanvasExportRequest, 'purpose' | 'diagramId' | 'sessionId'>;

type PendingCanvasExport = CanvasExportRequest & {
  requestId: string;
  resolve: (result: CanvasExportResult) => void;
  reject: (error: CanvasExportError) => void;
  settled: boolean;
};

const DEFAULT_EXPORT_TIMEOUT_MS = 10_000;

export class CanvasExportError extends Error {
  readonly code: 'EXPORT_TIMEOUT' | 'EXPORT_SCOPE_CHANGED' | 'EXPORT_DISPATCH_FAILED';

  constructor(
    code: 'EXPORT_TIMEOUT' | 'EXPORT_SCOPE_CHANGED' | 'EXPORT_DISPATCH_FAILED',
    message: string,
  ) {
    super(message);
    this.name = 'CanvasExportError';
    this.code = code;
  }
}

const matchesExpectedFormat = (format: CanvasExportFormat, payload: CanvasExportPayload) => {
  if (format === 'png') {
    return payload.format === 'png' || payload.data?.startsWith('data:image/png;base64,') === true;
  }
  return payload.format === 'xmlsvg' || Boolean(payload.xml) || payload.data?.includes('<mxGraphModel') === true;
};

export class CanvasExportCoordinator {
  private readonly dispatch: (options: CanvasExportRequest['options']) => void;
  private readonly queue: PendingCanvasExport[] = [];
  private active: PendingCanvasExport | null = null;
  private activeTimer: ReturnType<typeof setTimeout> | null = null;
  private sequence = 0;

  constructor(dispatch: (options: CanvasExportRequest['options']) => void) {
    this.dispatch = dispatch;
  }

  enqueue(request: CanvasExportRequest): Promise<CanvasExportResult> {
    return new Promise((resolve, reject) => {
      this.sequence += 1;
      this.queue.push({ ...request, requestId: `canvas-export-${this.sequence}`, resolve, reject, settled: false });
      this.startNext();
    });
  }

  handleExport(payload: CanvasExportPayload): boolean {
    if (!this.active
        || payload.requestId !== this.active.requestId
        || !matchesExpectedFormat(this.active.format, payload)) return false;

    const completed = this.active;
    this.clearActiveTimer();
    this.active = null;
    if (!completed.settled) {
      completed.settled = true;
      completed.resolve({
        ...payload,
        purpose: completed.purpose,
        diagramId: completed.diagramId,
        sessionId: completed.sessionId,
      });
    }
    this.startNext();
    return true;
  }

  retainScope(scope: { diagramId?: string | null; sessionId?: string | null }) {
    const belongsToScope = (item: PendingCanvasExport) => (
      item.sessionId === (scope.sessionId || '')
      && (!scope.diagramId || item.diagramId === scope.diagramId)
    );
    const scopeError = () => new CanvasExportError(
      'EXPORT_SCOPE_CHANGED',
      'Canvas export cancelled because the active diagram changed.',
    );

    if (this.active && !belongsToScope(this.active)) {
      const cancelled = this.active;
      this.clearActiveTimer();
      this.active = null;
      if (!cancelled.settled) {
        cancelled.settled = true;
        cancelled.reject(scopeError());
      }
    }

    for (let index = this.queue.length - 1; index >= 0; index -= 1) {
      const item = this.queue[index];
      if (belongsToScope(item)) continue;
      this.queue.splice(index, 1);
      if (!item.settled) {
        item.settled = true;
        item.reject(scopeError());
      }
    }
    this.startNext();
  }

  isBusy(purpose?: CanvasExportPurpose) {
    if (!purpose) return Boolean(this.active) || this.queue.length > 0;
    return this.active?.purpose === purpose || this.queue.some(item => item.purpose === purpose);
  }

  private startNext() {
    if (this.active || this.queue.length === 0) return;

    const next = this.queue.shift();
    if (!next) return;
    this.active = next;
    try {
      // draw.io echoes the original export message, including this correlation id.
      this.dispatch({ ...next.options, requestId: next.requestId });
    } catch (error) {
      this.active = null;
      next.settled = true;
      next.reject(new CanvasExportError(
        'EXPORT_DISPATCH_FAILED',
        error instanceof Error ? error.message : 'Canvas export could not be started.',
      ));
      this.startNext();
      return;
    }

    this.activeTimer = setTimeout(() => {
      if (this.active !== next) return;
      this.active = null;
      if (!next.settled) {
        next.settled = true;
        next.reject(new CanvasExportError('EXPORT_TIMEOUT', `Canvas ${next.purpose} export timed out.`));
      }
      this.startNext();
    }, next.timeoutMs ?? DEFAULT_EXPORT_TIMEOUT_MS);
  }

  private clearActiveTimer() {
    if (!this.activeTimer) return;
    clearTimeout(this.activeTimer);
    this.activeTimer = null;
  }

}
