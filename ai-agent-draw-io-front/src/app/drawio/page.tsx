'use client';

import { DrawIoEmbed, DrawIoEmbedRef } from 'react-drawio';
import Image from 'next/image';
import { Suspense, useRef, useState, useEffect, useCallback } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { getUserInfo, setUserInfo as persistUserInfo } from '@/utils/cookie';
import { getWorkspaceIdentity } from '@/utils/workspace-identity';
import { agentApi, ApiResponseError, StreamEvent } from '@/api/agent';
import type { CurrentAccountResponseDTO, DiagramCanvasStateResponseDTO, DiagramSummaryResponseDTO, ModelCredentialResponseDTO, ProviderPresetDTO } from '@/types/api';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { buildStepSummary } from './execution-step-summary';
import {
  buildStreamingPreviewXml,
  isValidDrawioCellXml,
  normalizeDrawioLegendSwatches,
  planFinalDiagramDelivery,
} from './streaming-preview';
import { buildDrawioChatRequestPayload } from './chat-request-payload';
import {
  CanvasStateMetadata,
  makeLocalDiagramId,
  mergeCanvasStateMetadata,
} from './canvas-state-metadata';
import {
  buildManualCanvasSaveRequest,
  latestCanvasVersion,
  shouldCreateConversationDiagramShell,
  shouldHandleManualAutosave,
  type ManualCanvasSaveRequest,
} from './manual-canvas-save';
import {
  beginAiCanvasMutation,
  createCanvasPersistenceState,
  finishAiCanvasMutation,
  shouldRetryManualCanvasSaveConflict,
} from './canvas-persistence-coordinator';
import { buildCanvasStateConflictMessage } from './canvas-state-conflict';
import { buildDiagramHistoryEntries } from './diagram-history';
import { buildRestoredDiagramState, normalizeRestoredDrawioXml } from './diagram-restore';
import { buildDiagramTitleFromPrompt, DEFAULT_DIAGRAM_TITLE } from './diagram-title';
import { buildRestoredConversationMessages } from './conversation-restore';
import {
  applyDemoQuotaConsumption,
  buildDemoQuotaState,
  demoQuotaExhaustedMessage,
  isDemoQuotaErrorCode,
  markDemoQuotaExhausted,
  quotaExhaustedMessageForCode,
} from './demo-quota';
import {
  AgentRunEvent,
  AgentRunEventStatus,
  AgentRunEventTone,
  buildAgentCompletionReply,
  buildAgentProgressSummary,
  buildAgentRunView,
  finishEventsAfterCanvasLoaded,
  finishPreviousPhaseEvents,
  getVisibleExecutionSteps,
  shouldShowAgentProgressCard,
  shouldShowAgentTyping,
} from './agent-run-presentation';
import {
  buildThumbnailExportRequest,
  isThumbnailExportResult,
  planThumbnailExport,
  shouldPersistThumbnail,
} from './thumbnail-export';

// Message type definition
type MessageStep = {
  id?: string;
  phase: string;
  label: string;
  content: string;
  status: 'running' | 'done' | 'pending';
};

type Message = {
  id: string;
  role: 'user' | 'agent';
  content: string;
  reasoning?: string;
  steps?: MessageStep[];
  events?: AgentRunEvent[];
  timestamp: number;
};

const CHAT_WIDTH_STORAGE_KEY = 'ai_drawio_chat_width';
const CHAT_DEFAULT_WIDTH = 380;
const CHAT_MIN_WIDTH = 320;
const CHAT_MAX_WIDTH = 720;
const CANVAS_MIN_WIDTH = 360;
// Keep this aligned with Tailwind's sm breakpoint for phone overlay behavior.
const DRAWIO_MOBILE_BREAKPOINT = 640;
const SIDEBAR_OPEN_STORAGE_KEY = 'ai_drawio_sidebar_open';
const DRAWIO_SESSIONS_STORAGE_KEY = 'drawio_sessions';
const MAX_REVIEW_ITERATIONS_STORAGE_KEY = 'ai_drawio_max_review_iterations';
const REVIEW_ITERATION_OPTIONS = [0, 1, 2, 3];
const EMPTY_DRAWIO_XML = '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/></root></mxGraphModel>';
const STREAMING_PREVIEW_FRAME_MS = 280;

type StructuredCanvasContext = {
  canvasXml?: string;
  canvasSummary?: string;
};

// Elegant SVG Icons with consistent styling
const Icons = {
  Chat: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
    </svg>
  ),
  Close: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <line x1="18" y1="6" x2="6" y2="18"></line>
      <line x1="6" y1="6" x2="18" y2="18"></line>
    </svg>
  ),
  Send: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <line x1="22" y1="2" x2="11" y2="13"></line>
      <polygon points="22 2 15 22 11 13 2 9 22 2"></polygon>
    </svg>
  ),
  ArrowRight: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <line x1="5" y1="12" x2="19" y2="12"></line>
      <polyline points="12 5 19 12 12 19"></polyline>
    </svg>
  ),
  User: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"></path>
      <circle cx="12" cy="7" r="4"></circle>
    </svg>
  ),
  Bot: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <path d="M12 2a2 2 0 0 1 2 2v2a2 2 0 0 1-2 2 2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z"></path>
      <path d="M4 11v6a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-6a2 2 0 0 0-2-2H6a2 2 0 0 0-2 2z"></path>
      <path d="M9 22v-3"></path>
      <path d="M15 22v-3"></path>
    </svg>
  ),
  Square: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="currentColor" className={className}>
      <rect x="6" y="6" width="12" height="12" rx="2" ry="2"></rect>
    </svg>
  ),
  Download: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"></path>
      <polyline points="7 10 12 15 17 10"></polyline>
      <line x1="12" y1="15" x2="12" y2="3"></line>
    </svg>
  ),
  Sparkles: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <path d="m12 3-1.912 5.813a2 2 0 0 1-1.275 1.275L3 12l5.813 1.912a2 2 0 0 1 1.275 1.275L12 21l1.912-5.813a2 2 0 0 1 1.275-1.275L21 12l-5.813-1.912a2 2 0 0 1-1.275-1.275L12 3Z" />
    </svg>
  ),
  Logout: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4"></path>
      <polyline points="16 17 21 12 16 7"></polyline>
      <line x1="21" y1="12" x2="9" y2="12"></line>
    </svg>
  ),
  Layers: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <polygon points="12 2 2 7 12 12 22 7 12 2"></polygon>
      <polyline points="2 17 12 22 22 17"></polyline>
      <polyline points="2 12 12 17 22 12"></polyline>
    </svg>
  ),
  Loader: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={`animate-spin ${className}`}>
      <path d="M21 12a9 9 0 1 1-6.219-8.56"></path>
    </svg>
  ),
  Plus: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <line x1="12" y1="5" x2="12" y2="19"></line>
      <line x1="5" y1="12" x2="19" y2="12"></line>
    </svg>
  ),
  Trash: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <polyline points="3 6 5 6 21 6"></polyline>
      <path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path>
    </svg>
  ),
  Edit: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <path d="M12 20h9"></path>
      <path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z"></path>
    </svg>
  ),
  MessageSquare: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"></path>
    </svg>
  ),
  ChevronLeft: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <polyline points="15 18 9 12 15 6"></polyline>
    </svg>
  ),
  ChevronRight: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <polyline points="9 18 15 12 9 6"></polyline>
    </svg>
  )
};

interface Session {
  id: string;
  backendSessionId?: string;
  diagramId?: string;
  canvasVersion?: number;
  canvasContentHash?: string;
  title: string;
  messages: Message[];
  drawIoXml: string | null;
  lastModified: number;
}

export interface CustomModelConfig {
  id: string;
  modelCredentialId: string;
  name: string;
  provider: string;
  baseUrl: string;
  model: string;
  completionsPath: string;
  maskedApiKey?: string;
  enabled: boolean;
}

type EditingModelConfig = CustomModelConfig & {
  apiKey: string;
  provider: string;
};

const normalizeCjkText = (text: string) => {
  let normalized = text;
  for (let i = 0; i < 3; i++) {
    normalized = normalized.replace(/([\u4e00-\u9fff])\s+([\u4e00-\u9fff])/g, '$1$2');
  }
  return normalized;
};

const formatStepContent = (content: string, phase?: string) => {
  const trimmed = content.trim();
  if (!trimmed) return '';

  const normalized = normalizeCjkText(trimmed)
    .replace(/```(?:json)?/gi, '')
    .replace(/```/g, '');

  if (/^(Working summary|Drawing progress|Quality checklist|Revision brief):/m.test(normalized)) {
    return normalized;
  }

  const looksStructured = phase === 'analyzing' || /["{][^"\n{}]{1,30}["]?\s*[:：]/.test(normalized);
  if (!looksStructured) return content;

  const keyValuePairs: string[] = [];
  const seen = new Set<string>();
  const pairPattern = /["“]?([^"“”{}[\],:\n]{2,18})["”]?\s*[:：]\s*["“]?([^"“”{}[\],\n]{1,80})["”]?/g;
  let match: RegExpExecArray | null;

  while ((match = pairPattern.exec(normalized)) !== null) {
    const key = match[1].trim().replace(/^content$/i, '');
    const value = match[2].trim().replace(/,$/, '');
    if (!key || !value || ['type', 'content'].includes(key.toLowerCase())) continue;
    if (value === key || value === 'drawio_request') continue;

    const line = `${key}：${value}`;
    if (!seen.has(line)) {
      seen.add(line);
      keyValuePairs.push(line);
    }
  }

  if (keyValuePairs.length > 0) {
    return `Requirement summary:\n${keyValuePairs.slice(0, 8).map(item => `- ${item}`).join('\n')}`;
  }

  // Some models stream pseudo-JSON line by line. Keep only human-readable text.
  const readableLines = normalized
    .split('\n')
    .map(line => line.trim().replace(/^["',{}[\]\s]+|["',{}[\]\s]+$/g, ''))
    .filter(line => line && !line.startsWith('type') && line !== 'content');

  return readableLines.join('\n');
};

const parseDrawioXml = (xml?: string | null) => {
  const parser = new DOMParser();
  const doc = parser.parseFromString(xml && xml.trim() ? xml : EMPTY_DRAWIO_XML, 'application/xml');
  if (doc.querySelector('parsererror')) {
    return parser.parseFromString(EMPTY_DRAWIO_XML, 'application/xml');
  }
  return doc;
};

const countDrawableCells = (xml?: string | null) => {
  const doc = parseDrawioXml(xml);
  const cells = Array.from(doc.querySelectorAll('mxCell'));
  return cells.reduce(
    (counts, cell) => {
      if (cell.getAttribute('vertex') === '1') counts.nodes += 1;
      if (cell.getAttribute('edge') === '1') counts.edges += 1;
      return counts;
    },
    { nodes: 0, edges: 0 }
  );
};

const extractDrawioXml = (payload?: { data?: string; xml?: string } | null) => {
  const candidates = [payload?.xml, payload?.data].filter(Boolean) as string[];

  for (const candidate of candidates) {
    const xmlStart = candidate.indexOf('<mxGraphModel');
    const xmlEnd = candidate.lastIndexOf('</mxGraphModel>');
    if (xmlStart >= 0 && xmlEnd >= xmlStart) {
      return candidate.slice(xmlStart, xmlEnd + '</mxGraphModel>'.length);
    }
  }

  return '';
};

const hasDrawableCells = (xml?: string | null) => {
  const counts = countDrawableCells(xml);
  return counts.nodes > 0 || counts.edges > 0;
};

const stripDrawioLabel = (value: string) => {
  const doc = new DOMParser().parseFromString(`<div>${value}</div>`, 'text/html');
  return (doc.body.textContent || value).replace(/\s+/g, ' ').trim();
};

const getCanvasSummary = (xml?: string | null) => {
  const doc = parseDrawioXml(xml);
  const cells = Array.from(doc.querySelectorAll('mxCell'));
  const nodes = cells.filter(cell => cell.getAttribute('vertex') === '1');
  const edges = cells.filter(cell => cell.getAttribute('edge') === '1');
  const labels = nodes
    .map(cell => stripDrawioLabel(cell.getAttribute('value') || ''))
    .filter(Boolean);
  const classNames = labels
    .map(label => label.split(/[\n\r|]/)[0].trim())
    .filter(Boolean);

  return [
    `Node count: ${nodes.length}`,
    `Edge count: ${edges.length}`,
    `Likely class/node labels: ${classNames.slice(0, 30).join(', ') || 'none'}`
  ].join('\n');
};

const chooseUsableCanvasXml = (
  exportedPayload: { data?: string; xml?: string } | null,
  storedXml?: unknown
) => {
  const exportedXml = extractDrawioXml(exportedPayload);
  const storedDrawioXml = normalizeRestoredDrawioXml(storedXml);
  if (hasDrawableCells(exportedXml)) return normalizeDrawioLegendSwatches(exportedXml);
  if (hasDrawableCells(storedDrawioXml)) return normalizeDrawioLegendSwatches(storedDrawioXml || EMPTY_DRAWIO_XML);
  return normalizeDrawioLegendSwatches(exportedXml || storedDrawioXml || EMPTY_DRAWIO_XML);
};

const buildStructuredCanvasContext = (xml: string): StructuredCanvasContext => {
  const canvasContext = hasDrawableCells(xml) ? xml : EMPTY_DRAWIO_XML;
  const canvasSummary = getCanvasSummary(canvasContext);

  return {
    canvasXml: canvasContext,
    canvasSummary,
  };
};

const cleanPlainTextFallback = (content: string) => {
  const text = content.replace(/\s+/g, ' ').trim();
  if (!text) return '';
  if (text.startsWith('{') || text.startsWith('[') || text.startsWith('<mxCell') || text.startsWith('<mxGraphModel')) return '';
  if (text.includes('"drawio_node"') || text.includes('"drawio_edge"') || text.includes('"drawio_done"')) return '';
  return text;
};

const normalizeAgentDisplayContent = (content: string) => {
  // The backend streams NDJSON, so model answers may arrive with escaped newlines.
  return content.replace(/\\n/g, '\n').replace(/\\"/g, '"').trim();
};

const phaseRunEvent: Record<string, { title: string; detail: string; tone: AgentRunEventTone }> = {
  analyzing: {
    title: 'Intent Router',
    detail: 'Classifying the request and canvas context',
    tone: 'analysis',
  },
  drawing: {
    title: 'Drawing Agent',
    detail: 'Generating canvas changes',
    tone: 'drawing',
  },
  reviewing: {
    title: 'Reviewer',
    detail: 'Checking XML, layout, and readability',
    tone: 'review',
  },
  revising: {
    title: 'Revision Agent',
    detail: 'Applying the smallest useful fix',
    tone: 'review',
  },
  thinking: {
    title: 'Agent',
    detail: 'Choosing the next action',
    tone: 'analysis',
  },
  generating: {
    title: 'Agent',
    detail: 'Preparing response',
    tone: 'analysis',
  },
};

const getValidationDetail = (chunk: StreamEvent['chunk']) => {
  if (chunk.type !== 'validation_result') return '';
  if (chunk.content) return chunk.content;
  if (chunk.issues && chunk.issues.length > 0) return `${chunk.issues.length} issue${chunk.issues.length === 1 ? '' : 's'} found`;
  return chunk.valid === false ? 'Validation needs attention' : 'XML OK';
};

const getValidationStatus = (chunk: StreamEvent['chunk']): AgentRunEventStatus => {
  if (chunk.type !== 'validation_result') return 'done';
  if (chunk.valid === false || chunk.severity === 'critical' || chunk.severity === 'error') return 'warning';
  if (chunk.severity === 'warning') return 'warning';
  return 'done';
};

const eventTextClasses: Record<AgentRunEventStatus, string> = {
  running: 'text-blue-600',
  done: 'text-emerald-600',
  warning: 'text-amber-600',
  error: 'text-rose-600',
};

const eventStatusMarks: Record<AgentRunEventStatus, string> = {
  running: '…',
  done: '✓',
  warning: '!',
  error: '×',
};

const summarizeEventDetail = (detail?: string) => {
  const normalized = detail?.replace(/\s+/g, ' ').trim() || '';
  if (!normalized) return '';
  return normalized.length > 180 ? `${normalized.slice(0, 177).trim()}...` : normalized;
};

const AgentProgressMessage = ({
  message,
  isRunning,
}: {
  message: Message;
  isRunning: boolean;
}) => {
  const view = buildAgentRunView({
    events: message.events,
    content: message.content,
    isRunning,
  });
  const summary = buildAgentProgressSummary(view, isRunning);

  return (
    <div className="w-full rounded-lg border border-stone-200 bg-white px-3.5 py-3 text-sm leading-relaxed text-zinc-700 shadow-sm">
      <p className="m-0">{summary}</p>

      {view.finalContent && (
        <div className="mt-2 prose prose-sm prose-zinc max-w-none prose-p:my-1.5 prose-ol:my-2 prose-ul:my-2 prose-li:my-1">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{view.finalContent}</ReactMarkdown>
        </div>
      )}

      {view.visibleEvents.length > 0 && (
        <details className="group/details mt-3 border-t border-stone-100 pt-2">
          <summary className="inline-flex cursor-pointer items-center gap-1.5 text-xs font-medium text-zinc-500 transition-colors hover:text-zinc-700">
            <Icons.Sparkles className="h-3.5 w-3.5 text-zinc-500" />
            <span className="group-open/details:hidden">View tool and validation details</span>
            <span className="hidden group-open/details:inline">Hide tool and validation details</span>
          </summary>
          <div className="mt-2 space-y-2 rounded-lg bg-stone-50 p-3 text-xs text-zinc-600">
            <div>
              <span className="font-medium text-zinc-700">Actions:</span> {view.toolLabel}
            </div>
            {view.visibleEvents.map(event => (
              <div key={event.id} className="flex gap-2">
                <span className={`mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded-full border text-[10px] font-semibold ${event.status === 'done' ? 'border-emerald-200 bg-emerald-50 text-emerald-600' : event.status === 'warning' ? 'border-amber-200 bg-amber-50 text-amber-600' : event.status === 'error' ? 'border-rose-200 bg-rose-50 text-rose-600' : 'border-blue-200 bg-blue-50 text-blue-600'}`}>
                  {eventStatusMarks[event.status]}
                </span>
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="font-medium text-zinc-700">{event.title}</span>
                    <span className={eventTextClasses[event.status]}>{event.statusLabel}</span>
                  </div>
                  {event.detail && (
                    <div className="mt-0.5 break-words text-zinc-500">{summarizeEventDetail(event.detail)}</div>
                  )}
                </div>
              </div>
            ))}
          </div>
        </details>
      )}
    </div>
  );
};

export default function Home() {
  return (
    <Suspense fallback={<main className="flex min-h-screen items-center justify-center bg-[var(--app-bg)] text-sm text-zinc-500">Loading...</main>}>
      <DrawioPageContent />
    </Suspense>
  );
}

function DrawioPageContent() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const restoreDiagramId = searchParams.get('diagramId');
  const [imgData, setImgData] = useState<string | null>(null);
  const drawioRef = useRef<DrawIoEmbedRef>(null);
  const restoredDiagramIdRef = useRef<string | null>(null);
  const hasInitializedSessionsRef = useRef(false);
  
  // User State
  const [currentUser, setCurrentUser] = useState('');
  // DrawIoEmbed keeps its first message handler, so iframe callbacks read mutable refs
  // for state that is initialized after the iframe mounts.
  const currentUserRef = useRef('');
  const [currentAccount, setCurrentAccount] = useState<CurrentAccountResponseDTO | null>(null);
  const [accountDisplayName, setAccountDisplayName] = useState('');
  const [isAccountPopoverOpen, setIsAccountPopoverOpen] = useState(false);
  const accountPopoverRef = useRef<HTMLDivElement>(null);

  const loadCurrentAccount = useCallback(async (ownerId: string) => {
    if (!ownerId) return;
    try {
      const res = await agentApi.currentAccount(ownerId);
      setCurrentAccount(res.data || null);
    } catch (error) {
      console.warn('Failed to load account status:', error);
    }
  }, []);

  const refreshCurrentAccount = async (ownerId = currentUser) => {
    await loadCurrentAccount(ownerId);
  };

  const accountQuotaRemaining = currentAccount?.ownerType === 'USER'
    ? currentAccount.platformDailyQuotaRemaining
    : currentAccount?.demoQuotaRemaining;
  const accountQuotaLabel = Number.isFinite(accountQuotaRemaining)
    ? `${accountQuotaRemaining} free AI requests left today`
    : 'Usage details loading';

  useEffect(() => {
    if (!isAccountPopoverOpen) return;

    const handleDocumentPointerDown = (event: PointerEvent) => {
      // Clicks inside the account popover should keep it open; outside clicks close it.
      if (accountPopoverRef.current?.contains(event.target as Node)) return;
      setIsAccountPopoverOpen(false);
    };

    document.addEventListener('pointerdown', handleDocumentPointerDown);
    return () => document.removeEventListener('pointerdown', handleDocumentPointerDown);
  }, [isAccountPopoverOpen]);

  // Chat State
  const [isChatOpen, setIsChatOpen] = useState(() => {
    if (typeof window === 'undefined') return true;
    return window.innerWidth >= DRAWIO_MOBILE_BREAKPOINT;
  });
  const [chatWidth, setChatWidth] = useState(CHAT_DEFAULT_WIDTH);
  const [isResizingChat, setIsResizingChat] = useState(false);
  const resizeStartXRef = useRef(0);
  const resizeStartWidthRef = useRef(CHAT_DEFAULT_WIDTH);
  const [messages, setMessages] = useState<Message[]>([
    {
      id: '1',
      role: 'agent',
      content: 'Hi! Tell me what diagram you want — a flowchart, architecture, UML class, sequence, ER, or state diagram. I can also edit the one on your canvas.',
      timestamp: Date.now()
    }
  ]);
  const [inputValue, setInputValue] = useState('');
  const promptInputRef = useRef<HTMLTextAreaElement | null>(null);
  const [isSending, setIsSending] = useState(false);
  const messagesEndRef = useRef<HTMLDivElement>(null);

  // "/" skill picker
  const [skillCatalog, setSkillCatalog] = useState<Array<{ name: string; description?: string }>>([]);
  const [selectedSkills, setSelectedSkills] = useState<string[]>([]);
  const [slashOpen, setSlashOpen] = useState(false);
  const [slashQuery, setSlashQuery] = useState('');
  const [slashIndex, setSlashIndex] = useState(0);
  const pendingSkillsRef = useRef<string[]>([]);

  // Load the selectable skill catalog for the "/" picker.
  useEffect(() => {
    if (!currentUser) return;
    agentApi.getSkillCatalog(currentUser)
      .then(res => setSkillCatalog((res?.data || []).map(s => ({ name: s.name, description: s.description }))))
      .catch(() => { /* skills are optional; ignore */ });
  }, [currentUser]);

  const filteredSkills = slashOpen
    ? skillCatalog.filter(s => s.name.toLowerCase().includes(slashQuery.toLowerCase())).slice(0, 8)
    : [];

  // Update input and detect a trailing "/skill" token to drive the picker.
  const handleInputChange = (value: string) => {
    setInputValue(value);
    const m = value.match(/(?:^|\s)\/([\w-]*)$/);
    if (m) { setSlashOpen(true); setSlashQuery(m[1]); setSlashIndex(0); }
    else { setSlashOpen(false); setSlashQuery(''); }
  };

  const focusPromptInput = () => {
    const textarea = promptInputRef.current;
    if (!textarea) return;
    textarea.focus();
    textarea.style.height = 'auto';
    textarea.style.height = Math.min(textarea.scrollHeight, 300) + 'px';
  };

  const handleTemplatePrompt = (prompt: string) => {
    // Template chips draft prompt text so users can edit before sending.
    setInputValue(prompt);
    setSlashOpen(false);
    setSlashQuery('');
    requestAnimationFrame(focusPromptInput);
  };

  // Pick a skill from the menu: drop the "/query" token, add it as a chip.
  const chooseSkill = (name: string) => {
    setInputValue(v => v.replace(/(^|\s)\/[\w-]*$/, '$1'));
    setSelectedSkills(prev => prev.includes(name) ? prev : [...prev, name]);
    setSlashOpen(false);
    setSlashQuery('');
  };
  const removeSkill = (name: string) => setSelectedSkills(prev => prev.filter(s => s !== name));

  // Sidebar State
  const [isSidebarOpen, setIsSidebarOpen] = useState(false);

  // Stream State
  const [streamPhase, setStreamPhase] = useState<string>('');
  const [streamProgress, setStreamProgress] = useState<string>('');
  const streamAbortRef = useRef<AbortController | null>(null);

  // Context State
  const [lastExportedData, setLastExportedData] = useState<{data: string, xml?: string, format?: string, timestamp: number} | null>(null);
  const isExportingForChatRef = useRef(false);
  const isAutosaveRef = useRef(false);
  const isExportingThumbnailRef = useRef(false);
  const pendingThumbnailDiagramIdRef = useRef('');
  const pendingThumbnailExportRef = useRef<{ diagramId: string; xml: string } | null>(null);
  const pendingMessageRef = useRef('');
  const [isDrawIoReady, setIsDrawIoReady] = useState(false);
  const isDrawIoReadyRef = useRef(false);
  const streamingPreviewQueueRef = useRef<string[]>([]);
  const streamingPreviewTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const scheduleStreamingPreviewDrainRef = useRef<() => void>(() => {});
  const pendingFinalDrawioXmlRef = useRef('');
  const [editorXml, setEditorXml] = useState(EMPTY_DRAWIO_XML);
  const [editorInstanceKey, setEditorInstanceKey] = useState(0);
  const forceBlankEditorLoadRef = useRef(false);
  const confirmingBlankEditorLoadRef = useRef(false);
  const ignoreAutosaveForBlankEditorRef = useRef(false);
  const blankEditorGuardTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const manualCanvasSaveTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const manualCanvasSaveInFlightRef = useRef(false);
  const pendingManualCanvasSaveRef = useRef<ManualCanvasSaveRequest | null>(null);
  // Authoritative latest-known canvas version per diagram; React session state can lag
  // behind while a save is in flight, so version locks must never be read from it alone.
  const manualCanvasVersionsRef = useRef(new Map<string, number>());
  const aiCanvasMutationDiagramIdsRef = useRef(new Set<string>());

  // Agent State
  const [selectedAgentId, setSelectedAgentId] = useState('');
  const [sessionId, setSessionId] = useState('');

  // Rename State
  const [isRenameModalOpen, setIsRenameModalOpen] = useState(false);
  const [renamingDiagramId, setRenamingDiagramId] = useState<string | null>(null);
  const [newSessionTitle, setNewSessionTitle] = useState('');

  // Custom API Config State
  const [showApiConfig, setShowApiConfig] = useState(false);
  const [customModels, setCustomModels] = useState<CustomModelConfig[]>([]);
  const [selectedCustomModelId, setSelectedCustomModelId] = useState<string>('default');
  const [maxReviewIterations, setMaxReviewIterations] = useState(1);
  const demoQuotaState = buildDemoQuotaState({
    account: currentAccount,
    selectedCustomModelId,
    customModels,
  });
  
  // Temporary state for editing in modal
  const [editingModel, setEditingModel] = useState<EditingModelConfig | null>(null);
  const [providerPresets, setProviderPresets] = useState<ProviderPresetDTO[]>([]);

  // Provider presets power the dropdown that prefills endpoint/model fields when adding a credential.
  useEffect(() => {
    let cancelled = false;
    void agentApi.listProviderPresets()
      .then(res => { if (!cancelled && res?.data) setProviderPresets(res.data); })
      .catch(err => console.warn('Failed to load provider presets:', err));
    return () => { cancelled = true; };
  }, []);

  // Apply a preset to the edit form: fill endpoint + a default model, keep the user's key/name.
  const applyProviderPreset = (presetId: string) => {
    setEditingModel(prev => {
      if (!prev) return prev;
      const preset = providerPresets.find(p => p.id === presetId);
      if (!preset) return { ...prev, provider: presetId };
      return {
        ...prev,
        provider: preset.id,
        baseUrl: preset.baseUrl || prev.baseUrl,
        completionsPath: preset.completionsPath || prev.completionsPath,
        model: preset.models[0] || prev.model,
      };
    });
  };

  const credentialToCustomModel = useCallback((credential: ModelCredentialResponseDTO): CustomModelConfig => ({
    id: credential.id,
    modelCredentialId: credential.id,
    name: credential.displayName || credential.model,
    provider: credential.provider || 'openai',
    baseUrl: credential.baseUrl,
    model: credential.model,
    completionsPath: credential.completionPath,
    maskedApiKey: credential.maskedApiKey,
    enabled: credential.status !== 'DISABLED',
  }), []);

  const persistCustomModelMetadata = useCallback((models: CustomModelConfig[]) => {
    const metadataOnly = models.map(model => ({
      id: model.id,
      modelCredentialId: model.modelCredentialId,
      name: model.name,
      provider: model.provider,
      baseUrl: model.baseUrl,
      model: model.model,
      completionsPath: model.completionsPath,
      maskedApiKey: model.maskedApiKey,
      enabled: model.enabled,
    }));
    setCustomModels(metadataOnly);
    localStorage.setItem('ai_agent_custom_models', JSON.stringify(metadataOnly));
  }, []);

  const loadModelCredentials = useCallback(async () => {
    try {
      const res = await agentApi.listModelCredentials();
      const models = (res.data || []).map(credentialToCustomModel);
      persistCustomModelMetadata(models);
      setSelectedCustomModelId(prev => {
        if (prev !== 'default' && models.some(model => model.id === prev && model.enabled)) {
          return prev;
        }
        localStorage.setItem('ai_agent_selected_model', 'default');
        return 'default';
      });
    } catch (error) {
      setCustomModels([]);
      localStorage.removeItem('ai_agent_custom_models');
      console.warn('Failed to load saved model credentials:', error);
    }
  }, [credentialToCustomModel, persistCustomModelMetadata]);

  // Session Management State
  const [sessions, setSessions] = useState<Session[]>([]);
  const sessionsRef = useRef<Session[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const currentSessionRef = useRef(currentSessionId);
  const [historyDiagrams, setHistoryDiagrams] = useState<DiagramSummaryResponseDTO[]>([]);
  const [isHistoryLoading, setIsHistoryLoading] = useState(false);
  const [historyError, setHistoryError] = useState('');

  const persistSessions = (nextSessions: Session[]) => {
    sessionsRef.current = nextSessions;
    try {
      localStorage.setItem(DRAWIO_SESSIONS_STORAGE_KEY, JSON.stringify(nextSessions));
    } catch (e) {
      console.error('Failed to save sessions to localStorage:', e);
    }
  };

  const refreshHistoryDiagrams = useCallback(async (ownerId = currentUserRef.current || currentUser) => {
    if (!ownerId) return;

    setIsHistoryLoading(true);
    setHistoryError('');
    try {
      const response = await agentApi.listDiagrams(ownerId);
      setHistoryDiagrams(response.data || []);
    } catch (error) {
      console.warn('Failed to load diagram history:', error);
      setHistoryError('Failed to load history.');
    } finally {
      setIsHistoryLoading(false);
    }
  }, [currentUser]);

  const saveCanvasXmlForSession = (
    targetSessionId?: string | null,
    xml?: string | null,
    canvasState?: CanvasStateMetadata,
  ) => {
    const normalizedXml = normalizeDrawioLegendSwatches(xml || EMPTY_DRAWIO_XML);
    if (!targetSessionId || !hasDrawableCells(normalizedXml)) return;

    setSessions(prev => {
      const nextSessions = prev.map(session => {
        if (session.id === targetSessionId) {
          const mergedCanvasState = mergeCanvasStateMetadata(
            { diagramId: session.diagramId, version: session.canvasVersion, contentHash: session.canvasContentHash },
            canvasState,
          );
          return {
            ...session,
            diagramId: mergedCanvasState.diagramId,
            canvasVersion: mergedCanvasState.version,
            canvasContentHash: mergedCanvasState.contentHash,
            drawIoXml: normalizedXml,
            lastModified: Date.now()
          };
        }
        return session;
      });

      // Write immediately so a browser refresh right after editing keeps the latest canvas.
      persistSessions(nextSessions);
      return nextSessions;
    });
  };

  const saveCurrentCanvasXml = (xml?: string | null, canvasState?: CanvasStateMetadata) => {
    saveCanvasXmlForSession(currentSessionRef.current, xml, canvasState);
  };

  const rememberManualCanvasVersion = (sessionId: string, diagramId: string, version?: number, contentHash?: string) => {
    const normalizedContentHash = contentHash?.trim();
    if (!Number.isFinite(version) && !normalizedContentHash) return;
    if (Number.isFinite(version)) {
      manualCanvasVersionsRef.current.set(diagramId, version as number);
    }
    setSessions(prev => {
      const nextSessions = prev.map(session => (
        session.id === sessionId
          ? {
              ...session,
              ...(Number.isFinite(version) && { canvasVersion: version }),
              ...(normalizedContentHash && { canvasContentHash: normalizedContentHash }),
            }
          : session
      ));
      persistSessions(nextSessions);
      return nextSessions;
    });
  };

  const fetchLatestCanvasVersion = async (userId: string, diagramId: string): Promise<number | undefined> => {
    try {
      const response = await agentApi.getDiagram(userId, diagramId);
      const version = response.data?.version;
      return Number.isFinite(version) ? version : undefined;
    } catch (error) {
      console.warn('Failed to load the latest canvas version after a save conflict:', error);
      return undefined;
    }
  };

  const currentCanvasXmlForDiagram = (diagramId: string): string | undefined => (
    sessionsRef.current.find(session => session.diagramId === diagramId)?.drawIoXml || undefined
  );

  const canvasPersistenceState = () => createCanvasPersistenceState<ManualCanvasSaveRequest>({
    aiCanvasMutationDiagramIds: aiCanvasMutationDiagramIdsRef.current,
    pendingManualCanvasSave: pendingManualCanvasSaveRef.current,
  });

  const beginAiCanvasMutationForDiagram = (diagramId?: string | null) => {
    const state = canvasPersistenceState();
    const result = beginAiCanvasMutation(state, diagramId);
    pendingManualCanvasSaveRef.current = state.pendingManualCanvasSave;
    if (result.droppedPendingManualCanvasSave && manualCanvasSaveTimerRef.current) {
      clearTimeout(manualCanvasSaveTimerRef.current);
      manualCanvasSaveTimerRef.current = null;
    }
    return result.started;
  };

  const finishAiCanvasMutationForDiagram = (diagramId?: string | null) => {
    finishAiCanvasMutation(canvasPersistenceState(), diagramId);
  };

  const currentVersionFromConflict = (error: ApiResponseError): number | undefined => {
    const conflictState = error.data as DiagramCanvasStateResponseDTO | null | undefined;
    return Number.isFinite(conflictState?.version) ? conflictState?.version : undefined;
  };

  const currentContentHashFromConflict = (error: ApiResponseError): string | undefined => {
    const conflictState = error.data as DiagramCanvasStateResponseDTO | null | undefined;
    return conflictState?.contentHash?.trim() || undefined;
  };

  const performManualCanvasSave = async (request: ManualCanvasSaveRequest, retryOnConflict: boolean) => {
    try {
      const response = await agentApi.saveDiagramCanvasState(
        request.userId,
        request.diagramId,
        request.canvasXml,
        request.expectedVersion,
      );
      // Only sync the version; the local canvas may already be newer than the XML just saved,
      // so writing the response XML back would briefly roll the session state backwards.
      rememberManualCanvasVersion(request.sessionId, request.diagramId, response.data?.version, response.data?.contentHash);
    } catch (error) {
      if (error instanceof ApiResponseError && error.code === 'CANVAS_VERSION_CONFLICT' && retryOnConflict) {
        const knownConflictVersion = currentVersionFromConflict(error);
        const knownConflictContentHash = currentContentHashFromConflict(error);
        const canRetry = shouldRetryManualCanvasSaveConflict({
          queuedCanvasXml: request.canvasXml,
          currentCanvasXml: currentCanvasXmlForDiagram(request.diagramId),
          aiMutationInFlight: aiCanvasMutationDiagramIdsRef.current.has(request.diagramId),
        });
        if (!canRetry) {
          // The queued autosave no longer represents the active canvas, so retrying would be a stale write.
          rememberManualCanvasVersion(request.sessionId, request.diagramId, knownConflictVersion, knownConflictContentHash);
          return;
        }

        const latestVersion = knownConflictVersion ?? await fetchLatestCanvasVersion(request.userId, request.diagramId);
        if (Number.isFinite(latestVersion)) {
          rememberManualCanvasVersion(request.sessionId, request.diagramId, latestVersion);
          // Manual edits treat the local canvas as the source of truth, so save over the fresh version.
          await performManualCanvasSave({ ...request, expectedVersion: latestVersion }, false);
          return;
        }
        console.warn('Manual canvas autosave conflicted and the latest backend version could not be loaded.');
      } else {
        console.warn('Failed to sync manual canvas autosave:', error);
      }
    }
  };

  const takePendingManualCanvasSave = (): ManualCanvasSaveRequest | null => {
    if (manualCanvasSaveTimerRef.current) {
      clearTimeout(manualCanvasSaveTimerRef.current);
      manualCanvasSaveTimerRef.current = null;
    }
    const request = pendingManualCanvasSaveRef.current;
    if (!request) return null;
    pendingManualCanvasSaveRef.current = null;
    return {
      ...request,
      expectedVersion: latestCanvasVersion(
        manualCanvasVersionsRef.current.get(request.diagramId),
        request.expectedVersion,
      ),
    };
  };

  const flushManualCanvasStateSave = async () => {
    if (manualCanvasSaveInFlightRef.current) return;
    const request = takePendingManualCanvasSave();
    if (!request) return;
    manualCanvasSaveInFlightRef.current = true;
    try {
      await performManualCanvasSave(request, true);
    } finally {
      manualCanvasSaveInFlightRef.current = false;
      if (pendingManualCanvasSaveRef.current) {
        void flushManualCanvasStateSave();
      }
    }
  };

  const flushManualCanvasSaveOnPageHide = () => {
    const request = takePendingManualCanvasSave();
    if (!request) return;
    // Best effort: keepalive keeps the request alive past unload but rejects bodies over
    // ~64KB; a failure here is recovered by the next autosave when the diagram is reopened.
    agentApi
      .saveDiagramCanvasState(request.userId, request.diagramId, request.canvasXml, request.expectedVersion, {
        keepalive: true,
      })
      .then(response => {
        const version = response.data?.version;
        if (Number.isFinite(version)) {
          manualCanvasVersionsRef.current.set(request.diagramId, version as number);
        }
      })
      .catch(() => {});
  };

  const queueManualCanvasStateSave = (xml?: string | null, session?: Session) => {
    const activeSession = session || sessionsRef.current.find(item => item.id === currentSessionRef.current);
    const request = buildManualCanvasSaveRequest({
      userId: currentUserRef.current,
      sessionId: activeSession?.id || currentSessionRef.current,
      diagramId: activeSession?.diagramId,
      canvasVersion: activeSession?.canvasVersion,
      canvasXml: xml,
    });
    // Blank canvases intentionally stay local: an accidental clear (or a glitchy empty
    // autosave event) must never wipe the server copy of the diagram.
    if (!request || !hasDrawableCells(request.canvasXml)) return;

    pendingManualCanvasSaveRef.current = request;
    if (manualCanvasSaveTimerRef.current) {
      clearTimeout(manualCanvasSaveTimerRef.current);
    }
    manualCanvasSaveTimerRef.current = setTimeout(() => {
      void flushManualCanvasStateSave();
    }, 750);
  };

  const persistDiagramTitle = async (diagramId?: string, title?: string) => {
    const ownerId = currentUserRef.current || currentUser;
    if (!ownerId || !diagramId) return;

    const normalizedTitle = buildDiagramTitleFromPrompt(title);
    try {
      const res = await agentApi.renameDiagram(ownerId, diagramId, normalizedTitle);
      const savedTitle = res.data?.title || normalizedTitle;
      const savedDiagram: DiagramSummaryResponseDTO = {
        diagramId,
        title: savedTitle,
        diagramType: res.data?.diagramType,
        thumbnailUrl: res.data?.thumbnailUrl,
        version: res.data?.version,
        updatedAt: res.data?.updatedAt || new Date().toISOString(),
      };
      setHistoryDiagrams(prev => {
        const nextDiagrams = prev.map(diagram => (
          diagram.diagramId === diagramId ? { ...diagram, ...savedDiagram } : diagram
        ));
        return nextDiagrams.some(diagram => diagram.diagramId === diagramId)
          ? nextDiagrams
          : [savedDiagram, ...nextDiagrams];
      });
      setSessions(prev => {
        const nextSessions = prev.map(session => (
          session.diagramId === diagramId ? { ...session, title: savedTitle, lastModified: Date.now() } : session
        ));
        persistSessions(nextSessions);
        return nextSessions;
      });
    } catch (e) {
      console.warn('Failed to sync diagram title:', e);
    }
  };

  const persistDiagramMessages = async (diagramId?: string, backendSessionId?: string, messagesToSave: Message[] = []) => {
    const ownerId = currentUserRef.current || currentUser;
    if (!ownerId || !diagramId || messagesToSave.length === 0) return;

    const payload = messagesToSave
      .filter(message => message.content.trim())
      .map(message => ({
        clientMessageId: message.id,
        sessionId: backendSessionId,
        role: message.role,
        content: message.content,
      }));
    if (payload.length === 0) return;

    try {
      await agentApi.saveDiagramMessages(ownerId, diagramId, backendSessionId, payload);
    } catch (e) {
      console.warn('Failed to sync diagram messages:', e);
    }
  };

  const ensureConversationDiagramShell = async ({
    diagramId,
    title,
    canvasXml,
    canvasVersion,
    hasConversationMessages,
  }: {
    diagramId?: string;
    title?: string;
    canvasXml?: string;
    canvasVersion?: number;
    hasConversationMessages: boolean;
  }) => {
    const ownerId = currentUserRef.current || currentUser;
    const normalizedDiagramId = diagramId?.trim();
    if (!ownerId || !shouldCreateConversationDiagramShell({
      diagramId: normalizedDiagramId,
      canvasVersion,
      hasDrawableContent: hasDrawableCells(canvasXml),
      hasConversationMessages,
    })) return;

    try {
      // Chat-only diagrams still need a canvas row so the history list can restore them later.
      const response = await agentApi.saveDiagramCanvasState(ownerId, normalizedDiagramId || '', EMPTY_DRAWIO_XML);
      const version = response.data?.version;
      if ((Number.isFinite(version) || response.data?.contentHash) && currentSessionRef.current) {
        rememberManualCanvasVersion(currentSessionRef.current, normalizedDiagramId || '', version as number, response.data?.contentHash);
      }
      await persistDiagramTitle(normalizedDiagramId, title);
    } catch (error) {
      if (error instanceof ApiResponseError && error.code === 'CANVAS_VERSION_CONFLICT') {
        const latestVersion = await fetchLatestCanvasVersion(ownerId, normalizedDiagramId || '');
        if (Number.isFinite(latestVersion) && currentSessionRef.current) {
          rememberManualCanvasVersion(currentSessionRef.current, normalizedDiagramId || '', latestVersion);
        }
        return;
      }
      console.warn('Failed to create chat-only diagram shell:', error);
    }
  };

  const persistDiagramThumbnail = async (diagramId?: string, thumbnailDataUrl?: string) => {
    const normalizedDiagramId = diagramId?.trim();
    if (!currentUser || !normalizedDiagramId || !shouldPersistThumbnail({ diagramId: normalizedDiagramId, dataUrl: thumbnailDataUrl })) return;

    try {
      await agentApi.updateDiagramThumbnail(currentUser, normalizedDiagramId, thumbnailDataUrl || '');
    } catch (e) {
      console.warn('Failed to sync diagram thumbnail:', e);
    }
  };

  const clearStreamingPreviewQueue = () => {
    streamingPreviewQueueRef.current = [];
    pendingFinalDrawioXmlRef.current = '';
    if (streamingPreviewTimerRef.current) {
      clearTimeout(streamingPreviewTimerRef.current);
      streamingPreviewTimerRef.current = null;
    }
  };

  const replaceEditorXml = (xml?: unknown) => {
    clearStreamingPreviewQueue();
    isDrawIoReadyRef.current = false;
    setIsDrawIoReady(false);
    const safeXml = normalizeRestoredDrawioXml(xml) || EMPTY_DRAWIO_XML;
    setEditorXml(normalizeDrawioLegendSwatches(safeXml) || EMPTY_DRAWIO_XML);
    setEditorInstanceKey(prev => prev + 1);
  };

  const applyStreamingPreviewXml = (xml: string) => {
    if (!xml || !drawioRef.current || !isDrawIoReadyRef.current) return false;

    drawioRef.current.load({ xml });
    return true;
  };

  const completeQueuedFinalDiagram = () => {
    const finalQueuedXml = pendingFinalDrawioXmlRef.current;
    if (!finalQueuedXml) return;

    replaceEditorXml(finalQueuedXml);
  };

  const scheduleStreamingPreviewDrain = () => {
    if (streamingPreviewTimerRef.current) return;

    streamingPreviewTimerRef.current = setTimeout(() => {
      streamingPreviewTimerRef.current = null;

      const nextPreviewXml = streamingPreviewQueueRef.current.shift();
      if (nextPreviewXml && !applyStreamingPreviewXml(nextPreviewXml)) {
        streamingPreviewQueueRef.current.unshift(nextPreviewXml);
        return;
      }

      if (streamingPreviewQueueRef.current.length > 0) {
        scheduleStreamingPreviewDrain();
        return;
      }

      completeQueuedFinalDiagram();
    }, STREAMING_PREVIEW_FRAME_MS);
  };

  const queueStreamingPreviewXml = (xml: string) => {
    const normalizedXml = normalizeDrawioLegendSwatches(xml);
    if (!normalizedXml || currentSessionId !== currentSessionRef.current) return;

    // Draw.io may receive model chunks faster than it can repaint, so replay them at a readable cadence.
    streamingPreviewQueueRef.current.push(normalizedXml);
    scheduleStreamingPreviewDrain();
  };

  const queueFinalDiagramXml = (xml: string) => {
    const normalizedXml = normalizeDrawioLegendSwatches(xml);
    if (!normalizedXml) return;

    const deliveryPlan = planFinalDiagramDelivery({
      finalXml: normalizedXml,
      previewQueueLength: streamingPreviewQueueRef.current.length,
      previewTimerActive: Boolean(streamingPreviewTimerRef.current),
    });

    pendingFinalDrawioXmlRef.current = deliveryPlan.pendingFinalXml;
    if (deliveryPlan.replaceImmediately) {
      replaceEditorXml(normalizedXml);
      return;
    }
    if (deliveryPlan.scheduleDrain) {
      scheduleStreamingPreviewDrain();
    }
  };

  // Localized edits (rename/recolor/route) merge into the live iframe to keep zoom/scroll/selection
  // and avoid the costly full remount. Falls back to a clean reload when the canvas isn't ready yet.
  const applyFinalDiagramXml = (xml: string, mode?: string) => {
    if (mode === 'local' && isDrawIoReadyRef.current && drawioRef.current) {
      const normalizedXml = normalizeDrawioLegendSwatches(xml);
      if (!normalizedXml) return;
      clearStreamingPreviewQueue();
      pendingFinalDrawioXmlRef.current = normalizedXml;
      drawioRef.current.load({ xml: normalizedXml });
      return;
    }
    queueFinalDiagramXml(xml);
  };

  const requestDiagramThumbnailExport = (diagramId: string) => {
    if (!drawioRef.current) return;

    pendingThumbnailDiagramIdRef.current = diagramId;
    isExportingThumbnailRef.current = true;
    try {
      drawioRef.current.exportDiagram(buildThumbnailExportRequest());
    } catch (e) {
      isExportingThumbnailRef.current = false;
      pendingThumbnailDiagramIdRef.current = '';
      console.warn('Thumbnail export failed:', e);
    }
  };

  const queueDiagramThumbnailExport = (diagramId?: string, xml?: unknown) => {
    const normalizedDiagramId = diagramId?.trim();
    const normalizedXml = normalizeRestoredDrawioXml(xml) || '';
    const plan = planThumbnailExport({
      diagramId: normalizedDiagramId,
      hasDrawableContent: hasDrawableCells(normalizedXml),
      editorReady: Boolean(drawioRef.current && isDrawIoReadyRef.current),
    });

    if (plan === 'skip') return;
    if (plan === 'defer') {
      pendingThumbnailExportRef.current = {
        diagramId: normalizedDiagramId || '',
        xml: normalizedXml,
      };
      return;
    }

    pendingThumbnailExportRef.current = null;
    requestDiagramThumbnailExport(normalizedDiagramId || '');
  };

  const flushPendingThumbnailExport = () => {
    const pending = pendingThumbnailExportRef.current;
    if (!pending || !drawioRef.current || !isDrawIoReadyRef.current) return;

    pendingThumbnailExportRef.current = null;
    requestDiagramThumbnailExport(pending.diagramId);
  };
  scheduleStreamingPreviewDrainRef.current = scheduleStreamingPreviewDrain;

  const clearBlankEditorGuard = () => {
    forceBlankEditorLoadRef.current = false;
    confirmingBlankEditorLoadRef.current = false;
    ignoreAutosaveForBlankEditorRef.current = false;
    if (blankEditorGuardTimerRef.current) {
      clearTimeout(blankEditorGuardTimerRef.current);
      blankEditorGuardTimerRef.current = null;
    }
  };

  const armBlankEditorGuard = () => {
    forceBlankEditorLoadRef.current = true;
    confirmingBlankEditorLoadRef.current = false;
    ignoreAutosaveForBlankEditorRef.current = true;
    if (blankEditorGuardTimerRef.current) {
      clearTimeout(blankEditorGuardTimerRef.current);
    }
    // Fallback in case draw.io does not emit a second load event after the explicit blank load.
    blankEditorGuardTimerRef.current = setTimeout(clearBlankEditorGuard, 2500);
  };

  const finishBlankEditorGuardSoon = () => {
    if (blankEditorGuardTimerRef.current) {
      clearTimeout(blankEditorGuardTimerRef.current);
    }
    blankEditorGuardTimerRef.current = setTimeout(clearBlankEditorGuard, 100);
  };

  const handleDrawioLoad = () => {
    isDrawIoReadyRef.current = true;
    setIsDrawIoReady(true);
    if (forceBlankEditorLoadRef.current && drawioRef.current) {
      forceBlankEditorLoadRef.current = false;
      confirmingBlankEditorLoadRef.current = true;
      drawioRef.current.load({ xml: EMPTY_DRAWIO_XML, autosave: true });
      return;
    }
    if (confirmingBlankEditorLoadRef.current) {
      finishBlankEditorGuardSoon();
    }
    flushPendingThumbnailExport();
  };

  const clampChatWidth = (width: number) => {
    const availableWidth = typeof window === 'undefined'
      ? CHAT_MAX_WIDTH
      : window.innerWidth - 256 - CANVAS_MIN_WIDTH;
    const maxWidth = Math.min(CHAT_MAX_WIDTH, Math.max(CHAT_MIN_WIDTH, availableWidth));
    return Math.min(Math.max(width, CHAT_MIN_WIDTH), maxWidth);
  };

  const handleChatResizeStart = (e: React.PointerEvent<HTMLDivElement>) => {
    e.preventDefault();
    e.currentTarget.setPointerCapture(e.pointerId);
    resizeStartXRef.current = e.clientX;
    resizeStartWidthRef.current = chatWidth;
    setIsResizingChat(true);
  };

  // Update ref
  useEffect(() => {
    currentSessionRef.current = currentSessionId;
  }, [currentSessionId]);

  useEffect(() => {
    currentUserRef.current = currentUser;
  }, [currentUser]);

  useEffect(() => {
    sessionsRef.current = sessions;
  }, [sessions]);

  useEffect(() => {
    if (!isSidebarOpen || !currentUser) return;
    void refreshHistoryDiagrams(currentUser);
  }, [currentUser, isSidebarOpen, refreshHistoryDiagrams]);

  useEffect(() => {
    isDrawIoReadyRef.current = isDrawIoReady;
    if (isDrawIoReady) {
      scheduleStreamingPreviewDrainRef.current();
    }
  }, [isDrawIoReady]);

  useEffect(() => () => {
    clearStreamingPreviewQueue();
    clearBlankEditorGuard();
    if (manualCanvasSaveTimerRef.current) {
      clearTimeout(manualCanvasSaveTimerRef.current);
    }
  }, []);

  useEffect(() => {
    // Flush the debounced manual save when the page goes away so the last ~750ms of
    // drawing is not lost on tab close / navigation; hidden tabs flush early too.
    const onPageHide = () => flushManualCanvasSaveOnPageHide();
    const onVisibilityChange = () => {
      if (document.visibilityState === 'hidden') flushManualCanvasSaveOnPageHide();
    };
    window.addEventListener('pagehide', onPageHide);
    document.addEventListener('visibilitychange', onVisibilityChange);
    return () => {
      window.removeEventListener('pagehide', onPageHide);
      document.removeEventListener('visibilitychange', onVisibilityChange);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- handler only touches stable refs
  }, []);

  useEffect(() => {
    const savedWidth = localStorage.getItem(CHAT_WIDTH_STORAGE_KEY);
    const parsedWidth = savedWidth ? Number(savedWidth) : NaN;
    if (Number.isFinite(parsedWidth)) {
      setChatWidth(clampChatWidth(parsedWidth));
    }

    // History is a per-visit panel; clear the legacy persisted flag so diagrams reopen clean.
    localStorage.removeItem(SIDEBAR_OPEN_STORAGE_KEY);
  }, []);

  useEffect(() => {
    if (!isResizingChat) return;

    const handlePointerMove = (e: PointerEvent) => {
      const deltaX = e.clientX - resizeStartXRef.current;
      setChatWidth(clampChatWidth(resizeStartWidthRef.current - deltaX));
    };

    const handlePointerUp = () => {
      setIsResizingChat(false);
    };

    document.body.style.cursor = 'col-resize';
    document.body.style.userSelect = 'none';
    window.addEventListener('pointermove', handlePointerMove);
    window.addEventListener('pointerup', handlePointerUp);

    return () => {
      document.body.style.cursor = '';
      document.body.style.userSelect = '';
      window.removeEventListener('pointermove', handlePointerMove);
      window.removeEventListener('pointerup', handlePointerUp);
    };
  }, [isResizingChat]);

  useEffect(() => {
    if (!isResizingChat) {
      localStorage.setItem(CHAT_WIDTH_STORAGE_KEY, String(chatWidth));
    }
  }, [chatWidth, isResizingChat]);

  // Load sessions from localStorage
  useEffect(() => {
    // Guard against StrictMode double-mount: the first run strips ?new=1 from the URL,
    // so a second run would fall into the restore branch and overwrite the fresh canvas.
    if (hasInitializedSessionsRef.current) return;
    hasInitializedSessionsRef.current = true;

    let savedSessions: Session[] = [];
    try {
      savedSessions = JSON.parse(localStorage.getItem(DRAWIO_SESSIONS_STORAGE_KEY) || '[]');
    } catch (e) {
      console.error('Failed to parse sessions:', e);
    }

    const freshParams = new URLSearchParams(window.location.search);
    const shouldCreateFreshDiagram = freshParams.get('new') === '1';
    if (shouldCreateFreshDiagram) {
      // Homepage "New diagram" must bypass the cached local session that normal /drawio
      // restores, while keeping the saved sessions so the history sidebar (and the
      // persistence effect) does not lose them.
      setSessions(savedSessions);
      createNewSession(true);
      // Landing page hands off the typed prompt via ?prompt=…; draft it into the
      // composer (focused, ready to send) rather than auto-sending on mount.
      const initialPrompt = freshParams.get('prompt');
      if (initialPrompt && initialPrompt.trim()) {
        setInputValue(initialPrompt);
        requestAnimationFrame(focusPromptInput);
      }
      window.history.replaceState(null, '', window.location.pathname);
      return;
    }

    if (savedSessions.length > 0) {
      setSessions(savedSessions);
      // Load the most recent session (first one if sorted by lastModified desc)
      const mostRecent = [...savedSessions].sort((a, b) => b.lastModified - a.lastModified)[0];
      setCurrentSessionId(mostRecent.id);
      setMessages(mostRecent.messages);
      replaceEditorXml(mostRecent.drawIoXml || EMPTY_DRAWIO_XML);
    } else {
      createNewSession(true);
    }
  }, []);

  // Save sessions to localStorage whenever they change
  useEffect(() => {
    if (sessions.length > 0) {
      persistSessions(sessions);
    }
  }, [sessions]);

  // Update current session messages and backendSessionId when they change
  useEffect(() => {
    if (currentSessionId) {
      setSessions(prev => prev.map(session => {
        if (session.id === currentSessionId) {
          return {
            ...session,
            messages,
            backendSessionId: sessionId,
            // Update title if it is still the default and we have a user message.
            title: session.title === DEFAULT_DIAGRAM_TITLE && messages.find(m => m.role === 'user')
              ? (messages.find(m => m.role === 'user')?.content.slice(0, 20) || DEFAULT_DIAGRAM_TITLE)
              : session.title
          };
        }
        return session;
      }));
    }
  }, [messages, currentSessionId, sessionId]);

  const createNewSession = (_isInitial = false, backendId = '') => {
    pendingThumbnailExportRef.current = null;
    armBlankEditorGuard();
    const localSessionId = Date.now().toString();
    const newSession: Session = {
      id: localSessionId,
      backendSessionId: backendId,
      diagramId: makeLocalDiagramId(localSessionId),
      title: DEFAULT_DIAGRAM_TITLE,
      messages: [{
        id: Date.now().toString(),
        role: 'agent',
        content: 'Hi! Tell me what diagram you want — a flowchart, architecture, UML class, sequence, ER, or state diagram. I can also edit the one on your canvas.',
        timestamp: Date.now()
      }],
      drawIoXml: null,
      lastModified: Date.now()
    };

    setSessions(prev => [newSession, ...prev]);
    currentSessionRef.current = newSession.id;
    setCurrentSessionId(newSession.id);
    setMessages(newSession.messages);
    setSessionId(backendId);
    replaceEditorXml(EMPTY_DRAWIO_XML);
  };

  useEffect(() => {
    if (!currentUser || !restoreDiagramId || restoredDiagramIdRef.current === restoreDiagramId) return;

    let cancelled = false;
    restoredDiagramIdRef.current = restoreDiagramId;
    Promise.all([
      agentApi.getDiagram(currentUser, restoreDiagramId),
      agentApi.listDiagramMessages(currentUser, restoreDiagramId).catch(() => ({ data: [] })),
    ])
      .then(([res, messageRes]) => {
        if (cancelled) return;
        const diagram = res.data;
        if (!diagram?.diagramId) {
          setMessages(prev => [...prev, {
            id: `${Date.now()}-restore-missing`,
            role: 'agent',
            content: 'Diagram not found.',
            timestamp: Date.now(),
          }]);
          return;
        }

        const restored = buildRestoredDiagramState(diagram);
        const restoredSessionId = `restored-${restored.diagramId}`;
        const restoredMessages: Message[] = buildRestoredConversationMessages(messageRes.data || [], restored.title);
        const restoredSession: Session = {
          id: restoredSessionId,
          backendSessionId: messageRes.data?.[0]?.sessionId || '',
          diagramId: restored.diagramId,
          canvasVersion: restored.canvasVersion,
          canvasContentHash: restored.canvasContentHash,
          title: restored.title,
          messages: restoredMessages,
          drawIoXml: restored.drawIoXml,
          lastModified: Date.now(),
        };

        setSessions(prev => {
          const nextSessions = [
            restoredSession,
            ...prev.filter(session => session.id !== restoredSessionId && session.diagramId !== restored.diagramId),
          ];
          persistSessions(nextSessions);
          return nextSessions;
        });
        pendingThumbnailExportRef.current = null;
        clearBlankEditorGuard();
        currentSessionRef.current = restoredSessionId;
        setCurrentSessionId(restoredSessionId);
        setMessages(restoredMessages);
        setSessionId(restoredSession.backendSessionId || '');
        replaceEditorXml(restored.drawIoXml || EMPTY_DRAWIO_XML);
        if (!diagram.thumbnailUrl) {
          queueDiagramThumbnailExport(restored.diagramId, restored.drawIoXml);
        }
      })
      .catch(() => {
        if (!cancelled) {
          setMessages(prev => [...prev, {
            id: `${Date.now()}-restore-error`,
            role: 'agent',
            content: 'Failed to load the diagram.',
            timestamp: Date.now(),
          }]);
        }
      });

    return () => {
      cancelled = true;
    };
  }, [currentUser, restoreDiagramId]);

  const openHistoryDiagram = (diagramId: string) => {
    setIsSidebarOpen(false);
    router.push(`/drawio?diagramId=${encodeURIComponent(diagramId)}`);
  };

  const removeLocalSessionsForDiagram = (diagramId: string) => {
    const nextSessions = sessionsRef.current.filter(session => session.diagramId !== diagramId);
    setSessions(nextSessions);
    persistSessions(nextSessions);
    return nextSessions;
  };

  const handleDeleteHistoryDiagram = async (
    e: React.MouseEvent,
    diagramId: string,
    title: string,
  ) => {
    e.stopPropagation();
    if (!currentUser || !window.confirm(`Delete "${title}"?`)) return;

    setHistoryError('');
    try {
      const response = await agentApi.deleteDiagram(currentUser, diagramId);
      if (!response.data) {
        setHistoryError('Diagram was not deleted.');
        return;
      }

      const deletedCurrentDiagram = sessionsRef.current.find(
        session => session.id === currentSessionRef.current,
      )?.diagramId === diagramId;
      setHistoryDiagrams(prev => prev.filter(diagram => diagram.diagramId !== diagramId));
      removeLocalSessionsForDiagram(diagramId);
      if (deletedCurrentDiagram) {
        window.history.replaceState(null, '', window.location.pathname);
        createNewSession();
      }
    } catch (error) {
      console.warn('Failed to delete diagram from history:', error);
      setHistoryError('Failed to delete diagram.');
    }
  };

  const openRenameHistoryDiagram = (diagramId: string, title: string) => {
    setRenamingDiagramId(diagramId);
    setNewSessionTitle(title);
    setIsRenameModalOpen(true);
  };

  const handleRenameCancel = () => {
    setIsRenameModalOpen(false);
    setRenamingDiagramId(null);
    setNewSessionTitle('');
  };

  const handleRenameSave = async () => {
    const title = newSessionTitle.trim();
    if (renamingDiagramId && title && currentUser) {
      try {
        const response = await agentApi.renameDiagram(currentUser, renamingDiagramId, title);
        const updated = response.data;
        setHistoryDiagrams(prev => prev.map(diagram => (
          diagram.diagramId === renamingDiagramId
            ? {
                ...diagram,
                title: updated?.title || title,
                diagramType: updated?.diagramType || diagram.diagramType,
                thumbnailUrl: updated?.thumbnailUrl || diagram.thumbnailUrl,
                version: updated?.version || diagram.version,
                updatedAt: updated?.updatedAt || diagram.updatedAt,
              }
            : diagram
        )));
        setSessions(prev => {
          const nextSessions = prev.map(session => (
            session.diagramId === renamingDiagramId ? { ...session, title, lastModified: Date.now() } : session
          ));
          persistSessions(nextSessions);
          return nextSessions;
        });
      } catch (error) {
        console.warn('Failed to rename diagram from history:', error);
        setHistoryError('Failed to rename diagram.');
      }
      handleRenameCancel();
    }
  };

  const saveCustomModels = (models: CustomModelConfig[]) => {
    persistCustomModelMetadata(models);
  };

  const handleAddNewModel = () => {
    setEditingModel({
      id: `draft-${Date.now()}`,
      modelCredentialId: '',
      name: 'New Model',
      baseUrl: 'https://api.openai.com/v1',
      apiKey: '',
      provider: 'openai',
      model: 'gpt-4o',
      completionsPath: '/chat/completions',
      enabled: true
    });
  };

  const handleSaveEditingModel = async () => {
    if (!editingModel) return;
    const apiKey = editingModel.apiKey.trim();
    if (!apiKey) return;
    try {
      const response = await agentApi.createModelCredential({
        provider: editingModel.provider || 'openai',
        baseUrl: editingModel.baseUrl,
        model: editingModel.model,
        completionPath: editingModel.completionsPath,
        displayName: editingModel.name,
        apiKey,
      });
      const savedModel = credentialToCustomModel(response.data);
      const newModels = [
        ...customModels.filter(model => model.id !== editingModel.id && model.id !== savedModel.id),
        savedModel,
      ];
      saveCustomModels(newModels);
      setSelectedCustomModelId(savedModel.id);
      localStorage.setItem('ai_agent_selected_model', savedModel.id);
      setEditingModel(null);
    } catch (error) {
      console.error('Failed to save model credential:', error);
    }
  };

  const handleDeleteModel = async (id: string) => {
    try {
      await agentApi.deleteModelCredential(id);
    } catch (error) {
      console.warn('Failed to delete model credential:', error);
    }
    const newModels = customModels.filter(m => m.id !== id);
    saveCustomModels(newModels);
    if (selectedCustomModelId === id) {
      setSelectedCustomModelId('default');
      localStorage.setItem('ai_agent_selected_model', 'default');
    }
  };

  const scrollToBottom = () => {
    messagesEndRef.current?.scrollIntoView({ behavior: "smooth" });
  };

  useEffect(() => {
    scrollToBottom();
  }, [messages, isChatOpen]);

  // Resolve workspace owner and load agents.
  useEffect(() => {
    let cancelled = false;

    const initializeWorkspace = async () => {
      const userInfo = getUserInfo();
      let ownerId = '';

      try {
        const res = await agentApi.me();
        const account = res.data;
        if (account?.status === 'SUCCESS' && account.userId) {
          // Spring session identity wins over browser-local anonymous workspace identity.
          ownerId = account.userId;
          if (account.email) {
            setAccountDisplayName(account.email);
            persistUserInfo(account.email);
          }
        }
      } catch {
        // Anonymous local work still needs to open when the auth endpoint is unavailable in dev.
      }

      if (!ownerId) {
        setAccountDisplayName('');
        ownerId = getWorkspaceIdentity(userInfo?.user).ownerId;
      }

      if (cancelled) return;
      currentUserRef.current = ownerId;
      setCurrentUser(ownerId);
      void loadCurrentAccount(ownerId);

      const savedSelected = localStorage.getItem('ai_agent_selected_model');
      if (savedSelected) {
        setSelectedCustomModelId(savedSelected);
      }
      void loadModelCredentials();
      const savedMaxReviewIterationsRaw = localStorage.getItem(MAX_REVIEW_ITERATIONS_STORAGE_KEY);
      if (savedMaxReviewIterationsRaw !== null && savedMaxReviewIterationsRaw !== '') {
        const savedMaxReviewIterations = Number(savedMaxReviewIterationsRaw);
        if (Number.isFinite(savedMaxReviewIterations)) {
          setMaxReviewIterations(Math.min(Math.max(savedMaxReviewIterations, 0), 3));
        }
      }

      // Load Agents
      try {
          const res = await agentApi.queryAiAgentConfigList();
          if (cancelled) return;
          const agentList = res.data || [];
          if (agentList.length > 0) {
            // Draw.io page should always use the drawing agent.
	          const drawIoAgent = agentList.find(agent => agent.agentId === '300000')
	            || agentList.find(agent => agent.agentName.toLowerCase().includes('draw'))
	            || agentList[0];
	          setSelectedAgentId(drawIoAgent.agentId);
	          setSessionId('');
	          localStorage.setItem('ai_agent_last_agent', drawIoAgent.agentId);
          }
        } catch (error) {
          if (cancelled) return;
          console.error('Failed to load agents:', error);
          setMessages(prev => [...prev, {
            id: Date.now().toString(),
            role: 'agent',
            content: 'Failed to load the agent list. Please check whether the backend service is running.',
            timestamp: Date.now()
          }]);
        }
    };

    void initializeWorkspace();
    return () => {
      cancelled = true;
    };
  }, [loadCurrentAccount, loadModelCredentials]);

  const finalizeNewChat = async () => {
    if (!selectedAgentId || !currentUser) return;
    
    try {
        const res = await agentApi.createSession(selectedAgentId, currentUser);
        createNewSession(false, res.data.sessionId);
        setInputValue('');
    } catch (error) {
        console.error('Failed to create new session:', error);
    }
  };

  const handleNewChat = async () => {
     finalizeNewChat();
  };

  const handleRestartSession = async () => {
    if (!selectedAgentId || !currentUser) return;

    if (!currentSessionId) {
      finalizeNewChat();
      return;
    }
    
    try {
        const res = await agentApi.createSession(selectedAgentId, currentUser);
        const newBackendId = res.data.sessionId;
        
        const initialMsg: Message = {
          id: Date.now().toString(),
          role: 'agent',
          content: 'Hi! Tell me what diagram you want — a flowchart, architecture, UML class, sequence, ER, or state diagram. I can also edit the one on your canvas.',
          timestamp: Date.now()
        };

        setSessionId(newBackendId);
        setMessages([initialMsg]);
        setInputValue('');

        setSessions(prev => prev.map(session => {
          if (session.id === currentSessionId) {
            return {
              ...session,
              backendSessionId: newBackendId,
              messages: [initialMsg],
              lastModified: Date.now()
            };
          }
          return session;
        }));
    } catch (error) {
        console.error('Failed to restart session:', error);
    }
  };

  const handleStopStream = () => {
    if (streamAbortRef.current) {
      streamAbortRef.current.abort();
      streamAbortRef.current = null;
    }
    aiCanvasMutationDiagramIdsRef.current.clear();
    setIsSending(false);
    setStreamPhase('');
    setStreamProgress('');
    setMessages(prev => [...prev, {
      id: Date.now().toString(),
      role: 'agent',
      content: '⚠️ Generation stopped.',
      timestamp: Date.now()
    }]);
  };

  const performSendMessage = async (displayContent: string, canvasContext: StructuredCanvasContext = {}) => {
    if (!selectedAgentId) {
      setMessages(prev => [...prev, {
        id: Date.now().toString(),
        role: 'agent',
        content: 'Please select an agent first.',
        timestamp: Date.now()
      }]);
      setIsSending(false);
      return;
    }

    setIsSending(true);

    const userMsg: Message = {
      id: Date.now().toString(),
      role: 'user',
      content: displayContent,
      timestamp: Date.now()
    };
    
    const agentMsgId = Date.now().toString() + '-agent';
    const initialAgentMsg: Message = {
      id: agentMsgId,
      role: 'agent',
      content: '',
      reasoning: '',
      steps: [],
      events: [],
      timestamp: Date.now()
    };

    setMessages(prev => [...prev, userMsg, initialAgentMsg]);
    let activeAiMutationDiagramId: string | undefined;

    try {
      // 1. Ensure Session
      let activeBackendSessionId = sessionId;
      if (!activeBackendSessionId) {
        const sessionRes = await agentApi.createSession(selectedAgentId, currentUser);
        activeBackendSessionId = sessionRes.data.sessionId;
        setSessionId(activeBackendSessionId);
      }

      // Update session lastModified
      setSessions(prev => prev.map(session => {
        if (session.id === currentSessionId) {
          return { ...session, lastModified: Date.now() };
        }
        return session;
      }));

      // 2. Send Message via Stream
      setStreamPhase('connecting');
      setStreamProgress('Connecting...');
      let activeStreamPhase = 'connecting';
      let activeStepKey = '';
      let lastStepSnapshot = '';
      const phaseVisitCounts: Record<string, number> = {};
      const repeatableStepPhases = new Set(['drawing', 'reviewing', 'revising']);

      // Track the current streamed draft. Each draw pass replaces the previous draft.
      let nodeCount = 0;
      let edgeCount = 0;
      let hasIncrementalContent = false;
      let finalXml = '';
      let agentTextContent = ''; // For non-drawio user-type responses
      let requestedMoreInfo = false;
      let receivedDrawioDone = false;
      let receivedVersionConflict = false;
      let completionMessageAdded = false;
      let emptyResponseMessageAdded = false;
      let previewSkeletonXml = '';
      let accumulatedNodes: string[] = []; // To hold incrementally added nodes
      let accumulatedEdges: string[] = []; // To hold incrementally added edges
      // Step-by-step canvas replay is only for drawing onto a blank canvas. When the canvas already
      // shows content (editing an existing diagram, or a review/repair pass after the first draw),
      // keep the last good diagram on screen and apply only the final drawio_done result.
      let suppressPreviewReplay = hasDrawableCells(canvasContext.canvasXml);
      let plainTextFallbackContent = ''; // Collect non-JSON model text in case no structured chunk arrives.
      
      let accumulatedContent = '';
      const accumulatedSteps: MessageStep[] = [];
      const accumulatedEvents: AgentRunEvent[] = [];
      const eventIndexByKey: Record<string, number> = {};
      let eventSequence = 0;

      // Helper to update steps
      const updateStep = (stepKey: string, phaseStr: string, phaseText: string, contentToAdd: string, isDone: boolean = false, replaceContent: boolean = false) => {
          const stepIndex = accumulatedSteps.findIndex(s => (s.id || s.phase) === stepKey);
          if (stepIndex >= 0) {
              if (contentToAdd) {
                  if (replaceContent) {
                      accumulatedSteps[stepIndex].content = contentToAdd + '\n';
                  } else if (!accumulatedSteps[stepIndex].content.includes(contentToAdd.trim())) {
                      accumulatedSteps[stepIndex].content += contentToAdd + '\n';
                  }
              }
              if (isDone) {
                  accumulatedSteps[stepIndex].status = 'done';
              }
          } else {
              // Mark previous running steps as done
              accumulatedSteps.forEach(s => { if (s.status === 'running') s.status = 'done'; });
              accumulatedSteps.push({
                  id: stepKey,
                  phase: phaseStr,
                  label: phaseText,
                  content: contentToAdd ? contentToAdd + '\n' : '',
                  status: isDone ? 'done' : 'running'
              });
          }
      };

      const ensurePhaseStep = (phaseStr: string, phaseText: string) => {
        if (phaseStr !== activeStreamPhase || !activeStepKey) {
          phaseVisitCounts[phaseStr] = (phaseVisitCounts[phaseStr] || 0) + 1;
          activeStepKey = `${phaseStr}:${phaseVisitCounts[phaseStr]}`;
        }

        const visitCount = phaseVisitCounts[phaseStr] || 1;
        const displayLabel = repeatableStepPhases.has(phaseStr) ? `${phaseText} ${visitCount}` : phaseText;

        return {
          key: activeStepKey,
          label: displayLabel
        };
      };

      const getPhaseStatusText = (phaseStr: string, note?: string) => buildStepSummary({
        phase: phaseStr,
        nodeCount,
        edgeCount,
        note,
        userRequest: displayContent
      });

      const markStepsDone = (steps?: MessageStep[]) => steps?.map(s => ({ ...s, status: 'done' as const }));

      const publishEvents = () => {
        setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, events: [...accumulatedEvents] } : m));
      };

      const upsertRunEvent = (key: string, event: Omit<AgentRunEvent, 'id'>) => {
        const existingIndex = eventIndexByKey[key];
        if (existingIndex !== undefined) {
          accumulatedEvents[existingIndex] = {
            ...accumulatedEvents[existingIndex],
            ...event,
          };
        } else {
          eventIndexByKey[key] = accumulatedEvents.length;
          accumulatedEvents.push({
            id: `${agentMsgId}-event-${++eventSequence}`,
            ...event,
          });
        }
        publishEvents();
      };

      const finishEventsBeforePhase = (activePhaseName: string) => {
        const settledEvents = finishPreviousPhaseEvents(accumulatedEvents, activePhaseName);
        accumulatedEvents.splice(0, accumulatedEvents.length, ...settledEvents);
        publishEvents();
      };

      const finishCanvasLoadEvents = () => {
        const settledEvents = finishEventsAfterCanvasLoaded(accumulatedEvents);
        accumulatedEvents.splice(0, accumulatedEvents.length, ...settledEvents);
        publishEvents();
      };

      const markRunEventsDone = () => {
        accumulatedEvents.forEach(event => {
          if (event.status === 'running') event.status = 'done';
        });
        publishEvents();
      };

      const publishSteps = () => {
        const stepSnapshot = JSON.stringify(accumulatedSteps);
        if (stepSnapshot === lastStepSnapshot) return;
        lastStepSnapshot = stepSnapshot;
        setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, steps: [...accumulatedSteps] } : m));
      };

      const canShowCompletion = () => !requestedMoreInfo && !receivedVersionConflict && (nodeCount > 0 || edgeCount > 0 || receivedDrawioDone);

      const loadStreamingPreview = (xml: string) => {
        if (!xml || currentSessionId !== currentSessionRef.current) return;

        queueStreamingPreviewXml(xml);
      };

      const appendCompletionMessage = () => {
        if (completionMessageAdded || !canShowCompletion()) return false;
        completionMessageAdded = true;
        const completionContent = buildAgentCompletionReply(
          buildAgentRunView({
            events: accumulatedEvents,
            content: accumulatedContent,
            isRunning: false,
          }),
          displayContent
        );
        if (completionContent && !accumulatedContent.includes(completionContent)) {
          accumulatedContent += (accumulatedContent ? '\n\n' : '') + completionContent;
        }
        setMessages(prev => prev.map(m => (
          m.id === agentMsgId ? { ...m, content: accumulatedContent, steps: markStepsDone(m.steps) } : m
        )));
        return true;
      };

      const appendAgentMessageContent = (content?: string) => {
        const displayContent = normalizeAgentDisplayContent(content || '');
        if (!displayContent) return false;

        const existingBlocks = accumulatedContent.split('\n\n').map(block => block.trim());
        if (!existingBlocks.includes(displayContent)) {
          accumulatedContent += (accumulatedContent ? '\n\n' : '') + displayContent;
        }
        if (!agentTextContent.includes(displayContent)) {
          agentTextContent += (agentTextContent ? '\n\n' : '') + displayContent;
        }
        setMessages(prev => prev.map(m => (
          m.id === agentMsgId ? { ...m, content: accumulatedContent, steps: [...accumulatedSteps] } : m
        )));
        return true;
      };

      const appendEmptyResponseMessage = () => {
        if (emptyResponseMessageAdded || agentTextContent || hasIncrementalContent) return false;
        emptyResponseMessageAdded = true;
        const fallbackContent = cleanPlainTextFallback(plainTextFallbackContent);
        accumulatedContent += (accumulatedContent ? '\n\n' : '') + (fallbackContent || `⚠️ No valid response received. Please try again.`);
        if (fallbackContent) {
          agentTextContent += fallbackContent;
        }
        setMessages(prev => prev.map(m => (
          m.id === agentMsgId ? { ...m, content: accumulatedContent, steps: markStepsDone(m.steps) } : m
        )));
        return true;
      };

      const activeModelConfig = customModels.find(m => m.id === selectedCustomModelId && m.enabled);
      const activeSession = sessions.find(session => session.id === currentSessionId);
      const diagramId = activeSession?.diagramId || (currentSessionId ? makeLocalDiagramId(currentSessionId) : undefined);
      if (diagramId) {
        activeAiMutationDiagramId = diagramId;
        beginAiCanvasMutationForDiagram(diagramId);
      }
      const diagramTitle = activeSession?.title && activeSession.title !== DEFAULT_DIAGRAM_TITLE
        ? activeSession.title
        : buildDiagramTitleFromPrompt(displayContent);
      let diagramTitlePersisted = false;
      let persistedDiagramId = diagramId;
      let conversationPersisted = false;

      const persistCurrentTurnConversation = () => {
        if (conversationPersisted) return;
        conversationPersisted = true;

        const agentContent = (accumulatedContent || agentTextContent).trim();
        if (!agentContent) return;

        const messagesToPersist = [
          userMsg,
          {
            ...initialAgentMsg,
            content: agentContent,
            steps: markStepsDone(initialAgentMsg.steps),
            timestamp: Date.now(),
          },
        ];

        void (async () => {
          await ensureConversationDiagramShell({
            diagramId: persistedDiagramId,
            title: diagramTitle,
            canvasXml: canvasContext.canvasXml,
            canvasVersion: latestCanvasVersion(
              persistedDiagramId ? manualCanvasVersionsRef.current.get(persistedDiagramId) : undefined,
              activeSession?.canvasVersion,
            ),
            hasConversationMessages: messagesToPersist.some(message => Boolean(message.content.trim())),
          });
          await persistDiagramMessages(persistedDiagramId, activeBackendSessionId, messagesToPersist);
        })();
      };

      const requestPayload = buildDrawioChatRequestPayload({
          agentId: selectedAgentId,
          userId: currentUser,
          sessionId: activeBackendSessionId,
          userMessage: displayContent,
          diagramId,
          expectedVersion: latestCanvasVersion(
            diagramId ? manualCanvasVersionsRef.current.get(diagramId) : undefined,
            activeSession?.canvasVersion,
          ),
          canvasXml: canvasContext.canvasXml,
          canvasSummary: canvasContext.canvasSummary,
          modelCredentialId: activeModelConfig?.modelCredentialId || undefined,
          maxReviewIterations,
          skills: pendingSkillsRef.current.length ? pendingSkillsRef.current : undefined
      });

      if (demoQuotaState.visible) {
        // Keep the visible counter in step with the backend; the refresh below reconciles failures.
        setCurrentAccount(prev => applyDemoQuotaConsumption(prev));
      }

      const controller = await agentApi.chatStream(
        requestPayload,
        // onEvent
        (event: StreamEvent) => {
          const { phase, chunk } = event;

          // Update phase display
          const phaseLabel: Record<string, string> = {
            analyzing: '🔍 Analyze request',
            revising: '🛠 Plan revision',
            drawing: '🎨 Draw diagram',
            reviewing: '✅ Review quality',
            thinking: '🤔 Thinking',
          };
          const currentPhaseLabel = phaseLabel[phase] || phaseLabel.thinking;
          let currentStep: { key: string; label: string } = { key: phase, label: currentPhaseLabel };

          if (phase !== 'done' && phase !== 'error') {
            const previousPhase = activeStreamPhase;
            currentStep = ensurePhaseStep(phase, currentPhaseLabel);
            updateStep(currentStep.key, phase, currentStep.label, getPhaseStatusText(phase), false, true);
            if (phase !== previousPhase) {
              finishEventsBeforePhase(phase);
              const phaseEvent = phaseRunEvent[phase] || phaseRunEvent.thinking;
              upsertRunEvent(`phase:${currentStep.key}`, {
                phase,
                title: phaseEvent.title,
                detail: phaseEvent.detail,
                status: 'running',
                tone: phaseEvent.tone,
              });
            }
            if (phase !== activeStreamPhase) {
              activeStreamPhase = phase;
              setStreamPhase(phase);
              publishSteps();
            } else if (phase !== previousPhase) {
              publishSteps();
            }
          }

          switch (chunk.type) {
            case 'drawio_preview': {
              if (requestedMoreInfo) break;

              const previewCounts = countDrawableCells(chunk.content);
              if (previewCounts.nodes === 0 && previewCounts.edges === 0) break;

              hasIncrementalContent = true;
              previewSkeletonXml = chunk.content;
              nodeCount = previewCounts.nodes;
              edgeCount = previewCounts.edges;
              updateStep(currentStep.key, 'drawing', currentStep.label, getPhaseStatusText('drawing', 'Loaded preview skeleton'), false, true);
              upsertRunEvent('drawio:stream', {
                phase: 'drawing',
                title: 'Update canvas preview',
                detail: 'Loaded preview skeleton',
                status: 'running',
                tone: 'drawing',
                nodes: nodeCount,
                edges: edgeCount,
              });
              setStreamProgress('Loaded preview skeleton...');
              publishSteps();
              // A preview arriving after drawio_done opens a new draw pass; never replay it over
              // the finished canvas.
              if (!receivedDrawioDone && !suppressPreviewReplay) {
                loadStreamingPreview(previewSkeletonXml);
              }
              break;
            }

            case 'drawio_node': {
              if (requestedMoreInfo) break;

              if (receivedDrawioDone) {
                // Each drawer pass streams a full canvas draft, so a new pass replaces the previous draft.
                // The finished canvas stays on screen; the repair pass applies once at its drawio_done.
                suppressPreviewReplay = true;
                accumulatedNodes = [];
                accumulatedEdges = [];
                previewSkeletonXml = '';
                nodeCount = 0;
                edgeCount = 0;
                receivedDrawioDone = false;
              }

              // Sometimes AI returns empty XML or malformed tags, skip adding to prevent crashing draw.io
              if (isValidDrawioCellXml(chunk.xml, 'node')) {
                  accumulatedNodes.push(chunk.xml);
                  hasIncrementalContent = true;
                  nodeCount = countDrawableCells(buildStreamingPreviewXml(accumulatedNodes, accumulatedEdges, previewSkeletonXml)).nodes;
                  updateStep(currentStep.key, 'drawing', currentStep.label, getPhaseStatusText('drawing', `Added node #${nodeCount}: ${chunk.label}`), false, true);
                  upsertRunEvent('drawio:stream', {
                    phase: 'drawing',
                    title: 'Update canvas preview',
                    detail: `Added node #${nodeCount}: ${chunk.label}`,
                    status: 'running',
                    tone: 'drawing',
                    nodes: nodeCount,
                    edges: edgeCount,
                  });
                  setStreamProgress(`Added node #${nodeCount}: ${chunk.label}`);
                  publishSteps();
                  if (!suppressPreviewReplay) {
                    loadStreamingPreview(buildStreamingPreviewXml(accumulatedNodes, accumulatedEdges, previewSkeletonXml));
                  }
              } else {
                  break;
              }

              break;
            }

            case 'drawio_edge': {
              if (requestedMoreInfo) break;

              if (receivedDrawioDone) {
                suppressPreviewReplay = true;
                accumulatedNodes = [];
                accumulatedEdges = [];
                previewSkeletonXml = '';
                nodeCount = 0;
                edgeCount = 0;
                receivedDrawioDone = false;
              }

              if (isValidDrawioCellXml(chunk.xml, 'edge')) {
                  accumulatedEdges.push(chunk.xml);
                  hasIncrementalContent = true;
                  edgeCount = countDrawableCells(buildStreamingPreviewXml(accumulatedNodes, accumulatedEdges, previewSkeletonXml)).edges;
                  updateStep(currentStep.key, 'drawing', currentStep.label, getPhaseStatusText('drawing', `Added edge #${edgeCount}: ${chunk.label || chunk.source + '→' + chunk.target}`), false, true);
                  upsertRunEvent('drawio:stream', {
                    phase: 'drawing',
                    title: 'Update canvas preview',
                    detail: `Added edge #${edgeCount}: ${chunk.label || chunk.source + '→' + chunk.target}`,
                    status: 'running',
                    tone: 'drawing',
                    nodes: nodeCount,
                    edges: edgeCount,
                  });
                  setStreamProgress(`Added edge #${edgeCount}: ${chunk.label || chunk.source + '→' + chunk.target}`);
                  publishSteps();
                  if (!suppressPreviewReplay) {
                    loadStreamingPreview(buildStreamingPreviewXml(accumulatedNodes, accumulatedEdges, previewSkeletonXml));
                  }
              } else {
                  break;
              }

              break;
            }

            case 'drawio_done': {
              if (requestedMoreInfo) break;

              const finalCounts = countDrawableCells(chunk.content);
              if (finalCounts.nodes === 0 && finalCounts.edges === 0) break;

              hasIncrementalContent = true;
              receivedDrawioDone = true;
              nodeCount = finalCounts.nodes;
              edgeCount = finalCounts.edges;
              finalXml = chunk.content;
              setStreamProgress('🎨 Drawing complete. Loading the final diagram...');
              upsertRunEvent('drawio:done', {
                phase,
                title: 'drawio_done',
                detail: 'Final canvas loaded',
                status: 'done',
                tone: 'drawing',
                tool: 'display_diagram',
                nodes: nodeCount,
                edges: edgeCount,
              });
              finishCanvasLoadEvents();
              updateStep(currentStep.key, phase, currentStep.label, getPhaseStatusText(phase, 'Final canvas loaded'), true, true);
              publishSteps();

              // Reviewer may send the polished final diagram after the drawing stage.
              const isFinalStage = phase === 'drawing' || phase === 'reviewing' || phase === 'done';
              
	              if (isFinalStage) {
	                // Local edits merge in place; full redraws recreate the iframe with the final stable XML.
	                if (currentSessionId === currentSessionRef.current && finalXml && finalXml.trim() !== '') {
	                  applyFinalDiagramXml(finalXml, chunk.mode);
	                }

	                saveCurrentCanvasXml(finalXml, {
	                  diagramId: chunk.diagramId,
	                  version: chunk.version,
                    contentHash: chunk.contentHash,
	                });
	                persistedDiagramId = chunk.diagramId || persistedDiagramId;
	                queueDiagramThumbnailExport(persistedDiagramId, finalXml);
	                if (!diagramTitlePersisted) {
	                  diagramTitlePersisted = true;
	                  persistDiagramTitle(chunk.diagramId || diagramId, diagramTitle);
	                }
	              }
              
              break;
            }

            case 'drawio': {
              if (requestedMoreInfo) break;

              // Legacy format: {"type":"drawio","content":"<xml>"}
              const legacyCounts = countDrawableCells(chunk.content);
              if (legacyCounts.nodes === 0 && legacyCounts.edges === 0) break;

              hasIncrementalContent = true;
              receivedDrawioDone = true;
              nodeCount = legacyCounts.nodes;
              edgeCount = legacyCounts.edges;
              finalXml = chunk.content;
              upsertRunEvent('drawio:legacy', {
                phase,
                title: 'drawio',
                detail: 'Final canvas loaded',
                status: 'done',
                tone: 'drawing',
                tool: 'display_diagram',
                nodes: nodeCount,
                edges: edgeCount,
              });

              const isFinalStage = phase === 'drawing' || phase === 'reviewing' || phase === 'done';

              if (isFinalStage) {
                  if (currentSessionId === currentSessionRef.current && finalXml && finalXml.trim() !== '') {
                    replaceEditorXml(finalXml);
                  }

                  saveCurrentCanvasXml(finalXml);
                  queueDiagramThumbnailExport(diagramId, finalXml);
              }
              
              break;
            }

            case 'user': {
              // AI returns a text response (not a diagram)
              if (chunk.content) {
                  // Sometimes the backend might leak raw JSON payload strings if the filter didn't catch it
                  let displayContent = chunk.content;
                  const trimmedContent = chunk.content.trim();
                  
                  // If it looks like a raw JSON string containing type="user", parse it and extract the content
                  if (trimmedContent.startsWith('{') && trimmedContent.includes('"type"') && trimmedContent.includes('"user"')) {
                      try {
                          const parsed = JSON.parse(trimmedContent);
                          if (parsed.content) {
                              displayContent = parsed.content;
                          }
                      } catch (e) {
                          // Try regex extraction if JSON parse fails due to streaming fragmentation
                          const match = trimmedContent.match(/"content"\s*:\s*"([^"]+)"/);
                          if (match && match[1]) {
                              displayContent = match[1].replace(/\\n/g, '\n');
                          }
                      }
                  }

                  displayContent = normalizeAgentDisplayContent(displayContent);

                  // We treat 'user' type responses during stream as intermediate conversational feedback
                  requestedMoreInfo = true;
                  agentTextContent += displayContent;
                  
                  // Make sure we only append actual text to the chat bubble, and skip any raw XML artifacts 
                  const isRawXmlLeak = displayContent.trim().startsWith('<mxCell') || displayContent.trim().startsWith('<mxGraphModel');
                  
                  if (!isRawXmlLeak) {
                      // Prevent duplicate consecutive lines
                      const newLines = displayContent.split('\n');
                      const currentLines = accumulatedContent.split('\n');
                      const lastLine = currentLines[currentLines.length - 1]?.trim();
                      
                      let shouldAppend = true;
                      if (newLines.length === 1 && newLines[0].trim() === lastLine) {
                          shouldAppend = false;
                      }

                      if (shouldAppend) {
                          const padding = accumulatedContent && !accumulatedContent.endsWith('\n\n') ? '\n\n' : '';
                          accumulatedContent += padding + displayContent;
                      }
                  }
                  
                  setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, content: accumulatedContent, steps: [...accumulatedSteps] } : m));
              }
              break;
            }

            case 'status': {
              // Intermediate status message
              if (chunk.content) {
                 const text = chunk.content.trim();
                 // Keep execution steps readable: do not print long model text into the process panel.
                 if (text !== '}' && text !== '{' && text !== ']' && text !== '[' && 
                     !text.startsWith('```') && 
                     !text.startsWith('"type":') &&
                     !text.startsWith('"id":') &&
                     !text.startsWith('"xml":') &&
                     !text.startsWith('<mxCell') &&
                     !text.startsWith('<mxGraphModel') &&
                     !text.includes('"drawio_node"') &&
                     !text.includes('"drawio_edge"') &&
                     !text.includes('"drawio_done"')) {

                     updateStep(currentStep.key, phase, currentStep.label, getPhaseStatusText(phase, text), false, true);
                     setStreamProgress(text.substring(0, 50) + '...');
                     publishSteps();
                 }
              }
              break;
            }

            case 'review_result': {
              const reviewText = chunk.approved ? 'Review passed. No changes needed.' : (chunk.content || 'Review found issues that need revision.');
              if (chunk.content) {
                appendAgentMessageContent(chunk.content);
              }
              upsertRunEvent('review:result', {
                phase: 'reviewing',
                title: 'review_result',
                detail: reviewText,
                status: chunk.approved ? 'done' : 'warning',
                tone: 'review',
                nodes: nodeCount,
                edges: edgeCount,
              });
              updateStep(currentStep.key, 'reviewing', currentStep.label, getPhaseStatusText('reviewing', reviewText), chunk.approved, true);
              setStreamProgress(reviewText.substring(0, 50) + '...');
              publishSteps();
              break;
            }

            case 'validation_result': {
              const validationDetail = getValidationDetail(chunk);
              upsertRunEvent('validation:result', {
                phase: 'reviewing',
                title: 'validate_diagram',
                detail: validationDetail,
                status: getValidationStatus(chunk),
                tone: 'validation',
                tool: 'validate_diagram',
                nodes: nodeCount,
                edges: edgeCount,
              });
              setStreamProgress(validationDetail.substring(0, 50) + '...');
              break;
            }

            case 'version_conflict': {
              receivedVersionConflict = true;
              const conflictSessionId = activeSession?.id || currentSessionId;
              if (conflictSessionId && chunk.diagramId && (Number.isFinite(chunk.currentVersion) || chunk.currentContentHash)) {
                rememberManualCanvasVersion(
                  conflictSessionId,
                  chunk.diagramId,
                  chunk.currentVersion,
                  chunk.currentContentHash,
                );
              }
              const conflictMessage = buildCanvasStateConflictMessage(chunk);
              upsertRunEvent('canvas:version_conflict', {
                phase: 'error',
                title: 'Canvas version conflict',
                detail: conflictMessage,
                status: 'error',
                tone: 'review',
              });
              accumulatedContent += (accumulatedContent ? '\n\n' : '') + `❌ ${conflictMessage}`;
              setStreamProgress(conflictMessage.substring(0, 50) + '...');
              setMessages(prev => prev.map(m => (
                m.id === agentMsgId ? { ...m, content: accumulatedContent, steps: [...accumulatedSteps] } : m
              )));
              break;
            }

            case 'token': {
              // Real-time token output
              if (chunk.content) {
                  const text = chunk.content.trim();
                  // Only use token text for the small progress hint; the process panel stays compact.
                  if (text !== '}' && text !== '{' && text !== ']' && text !== '[' && 
                     !text.startsWith('```') && 
                     !text.startsWith('"type":') &&
                     !text.startsWith('"id":') &&
                     !text.startsWith('"xml":') &&
                     !text.startsWith('<mxCell') &&
                     !text.startsWith('<mxGraphModel') &&
                     !text.includes('"drawio_node"') &&
                     !text.includes('"drawio_edge"') &&
                     !text.includes('"drawio_done"')) {
                      plainTextFallbackContent += chunk.content;
                      if (text.length > 1) {
                        setStreamProgress(text.substring(0, 50) + '...');
                      }
	                  }
              }
              break;
            }

            case 'error': {
              const isQuotaError = isDemoQuotaErrorCode(chunk.code);
              const errorContent = isQuotaError ? quotaExhaustedMessageForCode(chunk.code) : chunk.content;
              if (isQuotaError) {
                setCurrentAccount(prev => markDemoQuotaExhausted(prev));
                void refreshCurrentAccount();
              }
              upsertRunEvent('stream:error', {
                phase: 'error',
                title: isQuotaError ? 'Quota exhausted' : 'Stream error',
                detail: errorContent,
                status: 'error',
                tone: 'review',
              });
              accumulatedContent += (accumulatedContent ? '\n\n' : '') + `❌ ${errorContent}`;
              setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, content: accumulatedContent, steps: [...accumulatedSteps] } : m));
              break;
            }

            case 'done': {
              // Stream completed explicitly by backend
              accumulatedSteps.forEach(s => { s.status = 'done'; });
              markRunEventsDone();
              if (receivedVersionConflict) {
                setMessages(prev => prev.map(m => (
                  m.id === agentMsgId ? { ...m, steps: [...accumulatedSteps] } : m
                )));
              } else if (!appendCompletionMessage() && !appendEmptyResponseMessage()) {
                setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, steps: [...accumulatedSteps] } : m));
              }
              persistCurrentTurnConversation();
              setStreamPhase(receivedVersionConflict ? 'error' : 'done');
              break;
            }
          }
        },
        // onError
        (error: Error) => {
          if (activeAiMutationDiagramId) {
            finishAiCanvasMutationForDiagram(activeAiMutationDiagramId);
          }
          console.error('Stream error:', error);
          markRunEventsDone();
          
          // Only show error message if we didn't receive any content and it's not an AbortError
          if (error.name !== 'AbortError' && !hasIncrementalContent && !agentTextContent && nodeCount === 0) {
              accumulatedContent += (accumulatedContent ? '\n\n' : '') + `❌ Connection error: ${error.message}`;
              setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, content: accumulatedContent, steps: m.steps?.map(s => ({...s, status: 'done'})) } : m));
          } else {
              // If we already had content, just mark steps as done gracefully
              setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, steps: m.steps?.map(s => ({...s, status: 'done'})) } : m));
          }
          
          setIsSending(false);
          setStreamPhase('');
          setStreamProgress('');
          void refreshCurrentAccount();
        },
        // onComplete
        () => {
          if (activeAiMutationDiagramId) {
            finishAiCanvasMutationForDiagram(activeAiMutationDiagramId);
          }
          setIsSending(false);
          setStreamPhase('');
          setStreamProgress('');
          void refreshCurrentAccount();
          markRunEventsDone();
          
          if (!appendCompletionMessage() && !appendEmptyResponseMessage()) {
              setMessages(prev => prev.map(m => {
                  if (m.id === agentMsgId) {
                      return { ...m, steps: markStepsDone(m.steps) };
                  }
                  return m;
              }));
          }
          persistCurrentTurnConversation();
        }
      );

      streamAbortRef.current = controller;

    } catch (error) {
      if (activeAiMutationDiagramId) {
        finishAiCanvasMutationForDiagram(activeAiMutationDiagramId);
      }
      console.error('Chat error:', error);
      void refreshCurrentAccount();
      setMessages(prev => [...prev, {
        id: Date.now().toString(),
        role: 'agent',
        content: error instanceof Error ? `Error: ${error.message}` : 'Failed to send. Please try again.',
        timestamp: Date.now()
      }]);
      setIsSending(false);
      setStreamPhase('');
      setStreamProgress('');
    }
  };

  const sendContent = async (content: string) => {
    if (!content.trim() || isSending) return;
    if (demoQuotaState.exhausted) {
      setMessages(prev => [...prev, {
        id: Date.now().toString(),
        role: 'agent',
        content: demoQuotaState.exhaustedMessage || demoQuotaExhaustedMessage,
        timestamp: Date.now()
      }]);
      return;
    }

    setIsSending(true);

    if (drawioRef.current && isDrawIoReady) {
        isExportingForChatRef.current = true;
        pendingMessageRef.current = content;
        try {
            drawioRef.current.exportDiagram({
                 format: 'xmlsvg'
             });
        } catch (e) {
            console.error("Export failed", e);
            performSendMessage(content);
        }
    } else {
        performSendMessage(content);
    }
  };

  const handleSendMessage = async () => {
    const content = inputValue;
    // Capture user-picked skills for this message, then clear the chips.
    pendingSkillsRef.current = [...selectedSkills];
    setInputValue('');
    setSelectedSkills([]);
    setSlashOpen(false);
    // Reset textarea height
    const textarea = promptInputRef.current;
    if (textarea) textarea.style.height = '80px';
    sendContent(content);
  };

  useEffect(() => {
    if (!lastExportedData) return;

    // Thumbnail export can overlap with xmlsvg exports; only a png result consumes this request.
    if (isExportingThumbnailRef.current && isThumbnailExportResult(lastExportedData)) {
        isExportingThumbnailRef.current = false;
        const diagramId = pendingThumbnailDiagramIdRef.current;
        pendingThumbnailDiagramIdRef.current = '';
        persistDiagramThumbnail(diagramId, lastExportedData.data);
        return;
    }
    
    if (isExportingForChatRef.current) {
        isExportingForChatRef.current = false;
        const storedXml = sessions.find(session => session.id === currentSessionId)?.drawIoXml;
        const xml = chooseUsableCanvasXml(lastExportedData, storedXml);
        const content = pendingMessageRef.current;
        const canvasContext = buildStructuredCanvasContext(xml);
        saveCurrentCanvasXml(xml);
        performSendMessage(content, canvasContext);
        return;
    }
    
    // Autosave handling
    if (isAutosaveRef.current) {
        isAutosaveRef.current = false;
        const activeSession = sessions.find(session => session.id === currentSessionId);
        const storedXml = activeSession?.drawIoXml;
        const diagramId = activeSession?.diagramId;
        const xml = chooseUsableCanvasXml(lastExportedData, storedXml);
        saveCurrentCanvasXml(xml);
        queueManualCanvasStateSave(xml, activeSession);
        queueDiagramThumbnailExport(diagramId, xml);
        return;
    }

    // Manual Export
    setImgData(lastExportedData.data);
  }, [lastExportedData]);

  const handleKeyDown = (e: React.KeyboardEvent) => {
    // When the "/" picker is open, the keyboard drives the menu instead of sending.
    if (slashOpen && filteredSkills.length > 0) {
      if (e.key === 'ArrowDown') { e.preventDefault(); setSlashIndex(i => Math.min(i + 1, filteredSkills.length - 1)); return; }
      if (e.key === 'ArrowUp') { e.preventDefault(); setSlashIndex(i => Math.max(i - 1, 0)); return; }
      if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); chooseSkill(filteredSkills[Math.min(slashIndex, filteredSkills.length - 1)].name); return; }
      if (e.key === 'Escape') { e.preventDefault(); setSlashOpen(false); return; }
    }
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSendMessage();
    }
  };

  const quickActions = [
    { label: 'UML Class', text: 'Create a UML class diagram' },
    { label: 'Sequence', text: 'Create a sequence diagram' },
    { label: 'Architecture', text: 'Create an architecture diagram' },
    { label: 'Flowchart', text: 'Create a flowchart' }
  ];
  const historyEntries = buildDiagramHistoryEntries(historyDiagrams);
  const currentDiagramId = sessions.find(session => session.id === currentSessionId)?.diagramId;
  const formatHistoryUpdatedAt = (updatedAtMs: number) => {
    if (!updatedAtMs) return 'No updates yet';
    return `${new Date(updatedAtMs).toLocaleDateString()} ${new Date(updatedAtMs).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})}`;
  };

  return (
    <div className="relative flex h-[100dvh] w-full overflow-hidden bg-[var(--app-bg)] pb-14 font-sans text-zinc-800 sm:h-screen sm:pb-0">
      {/* Narrow rail keeps workspace navigation available without crowding the canvas. */}
      <aside className="fixed inset-x-0 bottom-0 z-50 flex h-14 w-full shrink-0 flex-row items-center justify-around gap-2 border-t border-stone-200 bg-[var(--app-bg)] px-3 py-2 text-zinc-500 sm:relative sm:inset-auto sm:z-30 sm:h-auto sm:w-14 sm:flex-col sm:justify-start sm:border-r sm:border-t-0 sm:px-2 sm:py-3">
        <button
          type="button"
          onClick={() => { window.location.href = '/diagrams'; }}
          className="relative grid h-10 w-10 place-items-center overflow-hidden rounded-lg bg-zinc-700 shadow-sm sm:h-9 sm:w-9"
          title="Diagram home"
        >
          {/* Match the shared app logo used on the home and auth pages. */}
          <Image src="/brand/freedraw-logo-dark.png" alt="" fill sizes="36px" className="object-cover" priority />
        </button>
        <button
          type="button"
          onClick={handleNewChat}
          className="grid h-10 w-10 place-items-center rounded-lg text-zinc-500 transition hover:bg-white hover:text-zinc-800 hover:shadow-sm sm:h-9 sm:w-9"
          title="New diagram"
        >
          <Icons.Plus className="h-5 w-5" />
        </button>
        <button
          type="button"
          onClick={() => setIsSidebarOpen(prev => !prev)}
          className={`grid h-10 w-10 place-items-center rounded-lg border transition sm:h-9 sm:w-9 ${
            isSidebarOpen
              ? 'border-stone-300 bg-white text-zinc-800 shadow-sm'
              : 'border-transparent text-zinc-500 hover:bg-white hover:text-zinc-800 hover:shadow-sm'
          }`}
          title="Diagram history"
        >
          <Icons.MessageSquare className="h-5 w-5" />
        </button>
        <button
          type="button"
          onClick={() => setShowApiConfig(true)}
          className="grid h-10 w-10 place-items-center rounded-lg text-zinc-500 transition hover:bg-white hover:text-zinc-800 hover:shadow-sm sm:h-9 sm:w-9"
          title="Model settings"
        >
          <Icons.Sparkles className="h-5 w-5" />
        </button>
        <div className="hidden flex-1 sm:block" />
        <div
          ref={accountPopoverRef}
          className="relative"
        >
          {/* Keep the avatar local to the canvas; click toggles account actions instead of navigating away. */}
          <button
            type="button"
            onClick={() => setIsAccountPopoverOpen(prev => !prev)}
            className="grid h-10 w-10 place-items-center rounded-lg text-zinc-500 transition hover:bg-white hover:text-zinc-800 hover:shadow-sm sm:h-9 sm:w-9"
            title="Account"
            aria-haspopup="dialog"
            aria-expanded={isAccountPopoverOpen}
          >
            <Icons.User className="h-5 w-5" />
          </button>
          {isAccountPopoverOpen && (
            <div
              role="dialog"
              aria-label="Account details"
              className="fixed bottom-16 right-3 z-50 w-[calc(100vw-1.5rem)] max-w-64 overflow-hidden rounded-lg border border-stone-200 bg-white text-left text-zinc-700 shadow-2xl shadow-zinc-700/15 sm:absolute sm:bottom-0 sm:left-11 sm:right-auto sm:w-64"
            >
              <div className="border-b border-stone-100 p-3">
                <div className="flex items-start gap-2">
                  <span className="grid h-8 w-8 shrink-0 place-items-center rounded-lg bg-zinc-700 text-white">
                    <Icons.User className="h-4 w-4" />
                  </span>
                  <div className="min-w-0">
                    <p className="text-sm font-semibold text-zinc-800">Account</p>
                    <p className="truncate text-xs text-zinc-500">
                      {accountDisplayName || (currentAccount?.authenticated ? 'Signed in' : 'Not signed in')}
                    </p>
                  </div>
                </div>
                <div className="mt-3 rounded-lg bg-stone-50 p-2 text-xs">
                  <p className="font-medium text-emerald-700">{accountQuotaLabel}</p>
                </div>
              </div>
              <div className="p-1">
                <button
                  type="button"
                  onClick={() => {
                    setIsAccountPopoverOpen(false);
                    setShowApiConfig(true);
                  }}
                  className="flex w-full items-center gap-2 rounded-md px-2 py-2 text-left transition hover:bg-stone-50"
                >
                  <Icons.Sparkles className="h-4 w-4 text-zinc-500" />
                  <span className="min-w-0">
                    <span className="block text-sm font-medium text-zinc-800">Settings</span>
                    <span className="block truncate text-xs text-zinc-500">Models and API keys</span>
                  </span>
                </button>
                <button
                  type="button"
                  onClick={() => { window.location.href = '/login'; }}
                  className="flex w-full items-center gap-2 rounded-md px-2 py-2 text-left transition hover:bg-stone-50"
                >
                  <Icons.User className="h-4 w-4 text-zinc-500" />
                  <span className="min-w-0">
                    <span className="block text-sm font-medium text-zinc-800">Account</span>
                    <span className="block truncate text-xs text-zinc-500">
                      {currentAccount?.authenticated ? 'Manage sign-in' : 'Sign in or create account'}
                    </span>
                  </span>
                </button>
              </div>
            </div>
          )}
        </div>
      </aside>

      {isSidebarOpen && (
        <div className="drawio-history-panel fixed inset-x-3 bottom-16 top-3 z-40 flex w-auto flex-col overflow-hidden rounded-lg border border-stone-200 bg-white shadow-2xl shadow-zinc-700/10 sm:absolute sm:bottom-3 sm:left-16 sm:top-3 sm:w-80">
          <div className="flex h-12 shrink-0 items-center justify-between border-b border-stone-100 px-4">
            <span className="flex items-center gap-2 text-sm font-semibold text-zinc-800">
              <Icons.MessageSquare className="h-4 w-4 text-zinc-500" />
              Diagram History
            </span>
            <div className="flex items-center gap-1">
              <button
                onClick={handleNewChat}
                className="rounded-md p-1.5 text-zinc-500 transition hover:bg-stone-100 hover:text-zinc-800"
                title="New diagram"
              >
                <Icons.Plus className="h-4 w-4" />
              </button>
              <button
                onClick={() => setIsSidebarOpen(false)}
                className="rounded-md p-1.5 text-zinc-500 transition hover:bg-stone-100 hover:text-zinc-800"
                title="Close history"
              >
                <Icons.Close className="h-4 w-4" />
              </button>
            </div>
          </div>
          <div className="flex-1 space-y-1 overflow-y-auto p-2">
            {historyError && (
              <div className="rounded-lg border border-rose-100 bg-rose-50 px-3 py-2 text-xs text-rose-700">
                {historyError}
              </div>
            )}
            {isHistoryLoading && (
              <div className="py-10 text-center text-xs text-zinc-400">
                Loading history...
              </div>
            )}
            {!isHistoryLoading && historyEntries.map(entry => (
              <div
                key={entry.diagramId}
                onClick={() => {
                  openHistoryDiagram(entry.diagramId);
                }}
                onDoubleClick={(e) => {
                  e.stopPropagation();
                  openRenameHistoryDiagram(entry.diagramId, entry.title);
                }}
                className={`
                  group flex cursor-pointer items-center gap-3 rounded-lg border px-3 py-3 transition
                  ${currentDiagramId === entry.diagramId
                    ? 'border-stone-300 bg-stone-50 text-zinc-800'
                    : 'border-transparent text-zinc-600 hover:bg-stone-50 hover:text-zinc-800'
                  }
                `}
              >
                <div className="min-w-0 flex-1">
                  <div className="truncate text-sm font-medium">
                    {entry.title}
                  </div>
                  <div className="mt-0.5 text-[10px] text-zinc-400">
                    {formatHistoryUpdatedAt(entry.updatedAtMs)}
                  </div>
                </div>
                <button
                  onClick={(e) => {
                    e.stopPropagation();
                    openRenameHistoryDiagram(entry.diagramId, entry.title);
                  }}
                  className="rounded-md p-1.5 text-zinc-400 opacity-0 transition hover:bg-white hover:text-zinc-700 group-hover:opacity-100"
                  title="Rename"
                >
                  <Icons.Edit className="h-4 w-4" />
                </button>
                <button
                  onClick={(e) => handleDeleteHistoryDiagram(e, entry.diagramId, entry.title)}
                  className="rounded-md p-1.5 text-zinc-400 opacity-0 transition hover:bg-rose-50 hover:text-rose-600 group-hover:opacity-100"
                  title="Delete"
                >
                  <Icons.Trash className="h-4 w-4" />
                </button>
              </div>
            ))}
            {!isHistoryLoading && historyEntries.length === 0 && (
              <div className="py-10 text-center text-xs text-zinc-400">
                No history yet
              </div>
            )}
          </div>
        </div>
      )}

      {/* Main Layout */}
      <div className="flex flex-1 min-w-0 h-full overflow-hidden relative">
        {isResizingChat && (
          <div className="fixed inset-0 z-50 cursor-col-resize bg-transparent" />
        )}

        {/* Keep the assistant toggle available after the chat panel is collapsed. */}
        {!isChatOpen && (
          <button
            onClick={() => setIsChatOpen(true)}
            className="absolute right-3 top-3 z-40 rounded-lg border border-stone-200 bg-white p-2 text-zinc-700 shadow-sm transition-colors hover:bg-stone-50"
            title="Open Assistant"
          >
            <Icons.Chat />
          </button>
        )}

        {/* Draw.io Canvas Area */}
        <div className="relative flex h-full min-w-0 flex-1 flex-col bg-[var(--app-bg)]">
          <div className="m-0 flex-1 overflow-hidden rounded-none border border-stone-200 bg-white shadow-sm sm:m-2 sm:rounded-lg">
            <DrawIoEmbed 
              key={editorInstanceKey}
              ref={drawioRef}
              xml={editorXml}
              autosave={true}
              onAutoSave={(data) => {
                if (ignoreAutosaveForBlankEditorRef.current) return;
                const activeSessionId = currentSessionRef.current || currentSessionId;
                const hasInlineXml = Boolean(data && typeof data === 'object' && 'xml' in data);
                if (shouldHandleManualAutosave({
                  currentSessionId: activeSessionId,
                  editorReady: isDrawIoReadyRef.current,
                  exportingForChat: isExportingForChatRef.current,
                  exportingThumbnail: isExportingThumbnailRef.current,
                  hasInlineXml,
                })) {
                   const activeSession = sessionsRef.current.find(session => session.id === activeSessionId);
                   const diagramId = activeSession?.diagramId;
                   // Prefer using the XML directly from the autosave event if available
                   if (hasInlineXml) {
                       const xmlContent = typeof data.xml === 'string' ? data.xml : '';
                       saveCurrentCanvasXml(xmlContent);
                       queueManualCanvasStateSave(xmlContent, activeSession);
                       queueDiagramThumbnailExport(diagramId, xmlContent);
                   } else {
                       // Fallback to export if no XML provided in event
                        isAutosaveRef.current = true;
                        drawioRef.current?.exportDiagram({ format: 'xmlsvg' });
                  }
                }
              }}
              onLoad={handleDrawioLoad}
              onExport={(data) => setLastExportedData({ data: data.data, xml: data.xml, format: data.format, timestamp: Date.now() })}
              urlParameters={{
                // The minimal draw.io shell keeps the app chrome closer to the Codex-style workspace.
                ui: 'min',
                spin: true,
                libraries: true,
                saveAndExit: false,
                noSaveBtn: true,
                noExitBtn: true
              }}
            />
          </div>
        </div>

        {isChatOpen && (
          <div
            onPointerDown={handleChatResizeStart}
            className="drawio-chat-resizer group relative z-30 w-2 shrink-0 cursor-col-resize bg-[var(--app-bg)] transition-colors hover:bg-stone-100"
            title="Drag to resize the assistant panel"
          >
            <div className="absolute inset-y-0 left-1/2 w-px -translate-x-1/2 bg-stone-200 transition-colors group-hover:bg-stone-300" />
            <div className="absolute left-1/2 top-1/2 h-10 w-1 -translate-x-1/2 -translate-y-1/2 rounded-full bg-stone-300 opacity-0 transition-opacity group-hover:opacity-100" />
          </div>
        )}

        {/* Chat Sidebar */}
        <div 
          className={`
            drawio-chat-panel relative flex flex-col border-l border-stone-200 bg-white ease-[cubic-bezier(0.25,0.1,0.25,1)]
            ${isResizingChat ? 'transition-none' : 'transition-all duration-300'}
            ${isChatOpen ? 'translate-x-0' : 'translate-x-full opacity-0 overflow-hidden'}
            z-20 shadow-lg shadow-zinc-700/5
          `}
          style={{
            width: isChatOpen ? chatWidth : 0,
            flexBasis: isChatOpen ? chatWidth : 0,
            flexShrink: 0
          }}
        >
          {/* Keep only the collapse control; the title block is removed to give the chat more vertical space. */}
          <button 
            onClick={() => setIsChatOpen(false)}
            className="absolute right-3 top-3 z-10 shrink-0 rounded-md p-1.5 text-zinc-400 transition-all hover:bg-stone-100 hover:text-zinc-700"
            title="Collapse assistant"
          >
            <Icons.Close className="w-5 h-5" />
          </button>

          {/* Messages Area */}
          <div className="flex-1 space-y-6 overflow-y-auto bg-[var(--app-bg)] p-5 pr-14 scrollbar-thin scrollbar-track-transparent scrollbar-thumb-stone-300">
            {messages.map((msg, index) => {
              const hasAgentRunEvents = msg.role === 'agent' && !!msg.events && msg.events.length > 0;
              const isLatestRunningAgent = index === messages.length - 1 && isSending;
              const showAgentProgressCard = shouldShowAgentProgressCard({
                role: msg.role,
                eventCount: msg.events?.length || 0,
                isLatestRunningAgent,
              });
              const visibleExecutionSteps = getVisibleExecutionSteps(msg.steps, isLatestRunningAgent);

              return (
                <div 
                  key={`${msg.id}-${index}`} 
                  className={`flex gap-3 ${msg.role === 'user' ? 'flex-row-reverse' : 'flex-row'}`}
                >
                  <div className={`
                    shrink-0 w-8 h-8 flex items-center justify-center shadow-sm mt-1
                    ${msg.role === 'user'
                      ? 'rounded-full bg-zinc-700 text-white ring-2 ring-white'
                      : 'rounded-xl bg-zinc-900 text-white'
                    }
                  `}>
                    {msg.role === 'user' ? <Icons.User className="w-5 h-5" /> : <Icons.Sparkles className="w-4 h-4" />}
                  </div>

                  <div className="flex flex-col max-w-[85%] w-full">
                      <div className={`flex flex-col gap-2 ${msg.role === 'user' ? 'items-end' : 'items-start'}`}>
                        {showAgentProgressCard && (
                          <AgentProgressMessage message={msg} isRunning={isLatestRunningAgent} />
                        )}

                        {/* Steps / Reasoning Block */}
                        {msg.role === 'agent' && (visibleExecutionSteps.length > 0 || (isLatestRunningAgent && msg.reasoning)) && (
                          <div className="w-full max-w-full">
                            <details className="w-full group/details open:pb-2" open={index === messages.length - 1 && isSending}>
                              <summary className="inline-flex cursor-pointer select-none items-center gap-2 rounded-lg border border-stone-200 bg-white px-3 py-1.5 text-xs font-medium text-zinc-500 shadow-sm transition-all hover:border-stone-300 hover:text-zinc-700">
                                 <Icons.Sparkles className="w-3.5 h-3.5 text-zinc-500" />
                                 <span className="group-open/details:hidden">Show execution steps</span>
                                 <span className="hidden group-open/details:inline">Hide execution steps</span>
                              </summary>
                              <div className="mt-2 flex max-w-none flex-col gap-2 overflow-x-auto rounded-lg border border-stone-200 bg-stone-50 p-3 text-sm text-zinc-600 shadow-sm">
                                 {visibleExecutionSteps.length > 0 ? (
                                   visibleExecutionSteps.map((step, idx) => (
                                     <div key={idx} className="flex flex-col gap-1.5 rounded-lg border border-stone-100 bg-white p-2 shadow-sm">
                                         <div className="flex items-center gap-2 font-medium text-zinc-700">
                                             <Icons.Loader className="w-3.5 h-3.5 text-zinc-500" />
                                             <span>{step.label}</span>
                                         </div>
                                         {step.content && (
                                             <div className="ml-1.5 border-l-2 border-stone-100 pl-6 text-xs text-zinc-500 prose prose-sm prose-zinc max-w-none prose-p:my-1 prose-pre:my-2 prose-pre:bg-stone-100 prose-pre:text-zinc-700">
                                               <ReactMarkdown remarkPlugins={[remarkGfm]}>{formatStepContent(step.content, step.phase)}</ReactMarkdown>
                                           </div>
                                         )}
                                     </div>
                                   ))
                                 ) : (
                                   <div className="rounded-lg border border-stone-100 bg-white p-2 shadow-sm prose prose-sm prose-zinc max-w-none prose-p:my-1 prose-pre:my-2 prose-pre:bg-stone-100 prose-pre:text-zinc-700">
                                     <ReactMarkdown remarkPlugins={[remarkGfm]}>{msg.reasoning || ''}</ReactMarkdown>
                                   </div>
                                 )}
                              </div>
                            </details>
                          </div>
                        )}

                        {/* Content Block */}
                        {msg.content && !hasAgentRunEvents && (
                          <div 
                            className={`
                                p-3.5 text-sm leading-relaxed shadow-sm w-fit
                                ${msg.role === 'user'
                                ? 'rounded-lg bg-zinc-700 text-white shadow-sm whitespace-pre-wrap'
                                : 'rounded-lg border border-stone-200 bg-white text-zinc-700 shadow-sm prose prose-sm prose-zinc max-w-none overflow-x-auto prose-p:my-1.5 prose-ol:my-2 prose-ul:my-2 prose-li:my-1 prose-pre:my-2 prose-pre:bg-stone-100 prose-pre:text-zinc-700'
                                }
                            `}
                          >
                            {msg.role === 'user' ? (
                               msg.content
                            ) : (
                               <ReactMarkdown remarkPlugins={[remarkGfm]}>{msg.content}</ReactMarkdown>
                            )}
                          </div>
                        )}

                        {/* Empty state while generating */}
                        {shouldShowAgentTyping({
                          role: msg.role,
                          content: msg.content,
                          reasoning: msg.reasoning,
                          eventCount: msg.events?.length || 0,
                          stepCount: msg.steps?.length || 0,
                          isLatestRunningAgent,
                        }) && (
                           <div className="flex items-center gap-1 rounded-lg border border-stone-200 bg-white px-4 py-3 text-sm text-zinc-600 shadow-sm">
                             <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-zinc-500" style={{ animationDelay: '0ms' }}></span>
                             <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-zinc-500" style={{ animationDelay: '150ms' }}></span>
                             <span className="h-1.5 w-1.5 animate-bounce rounded-full bg-zinc-500" style={{ animationDelay: '300ms' }}></span>
                           </div>
                        )}
                      </div>
                  </div>
                </div>
              );
            })}

            {/* Template starters live right under the greeting while the chat is empty. */}
            {messages.length <= 1 && (
              <div className="pl-11 animate-in fade-in slide-in-from-bottom-2 duration-300">
                <p className="mb-3 font-mono text-[11px] uppercase tracking-[0.14em] text-zinc-400">Try a template</p>
                <div className="flex flex-wrap gap-2.5">
                  {quickActions.map((action, idx) => (
                    <button
                      key={idx}
                      onClick={() => {
                        handleTemplatePrompt(action.text);
                      }}
                      disabled={demoQuotaState.exhausted}
                      className={`inline-flex items-center gap-2 rounded-full border px-4 py-2 text-sm font-medium shadow-sm transition-colors ${
                        demoQuotaState.exhausted
                          ? 'cursor-not-allowed border-stone-200 bg-stone-100 text-zinc-400'
                          : 'border-stone-200 bg-white text-zinc-700 hover:border-stone-300 hover:bg-stone-50'
                      }`}
                    >
                      <span className={`h-1.5 w-1.5 rounded-full ${demoQuotaState.exhausted ? 'bg-zinc-300' : 'bg-zinc-800'}`} aria-hidden="true" />
                      {action.label}
                    </button>
                  ))}
                </div>
              </div>
            )}

            <div ref={messagesEndRef} />
          </div>

          {/* Input Area */}
          <div className="relative z-20 shrink-0 border-t border-stone-200 bg-white p-4 shadow-[0_-4px_12px_rgba(24,24,27,0.03)]">
            {/* Model and loop controls */}
            <div className="flex flex-wrap items-center gap-2 mb-2 px-1">
                <div className="relative flex items-center rounded-full border border-stone-200 bg-white shadow-sm transition-colors hover:border-stone-300">
                    <Icons.Sparkles className={`ml-2 h-3 w-3 ${selectedCustomModelId !== 'default' ? 'text-zinc-700' : 'text-zinc-400'}`} />
                    <select
                        value={selectedCustomModelId}
                        onChange={(e) => {
                            if (e.target.value === 'add_new') {
                                setShowApiConfig(true);
                                e.target.value = selectedCustomModelId;
                            } else {
                                setSelectedCustomModelId(e.target.value);
                                localStorage.setItem('ai_agent_selected_model', e.target.value);
                            }
                        }}
                        className="cursor-pointer appearance-none border-none bg-transparent py-1 pl-1 pr-5 text-[11px] font-medium text-zinc-600 outline-none focus:ring-0"
                    >
                        <option value="default">Default Model</option>
                        {customModels.filter(m => m.enabled).map(m => (
                            <option key={m.id} value={m.id}>{m.name || m.model}</option>
                        ))}
                        <option disabled>──────────</option>
                        <option value="add_new">+ Manage Models</option>
                    </select>
                    <div className="pointer-events-none absolute inset-y-0 right-0 flex items-center px-1.5 text-zinc-400">
                        <svg className="fill-current h-3 w-3" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path d="M9.293 12.95l.707.707L15.657 8l-1.414-1.414L10 10.828 5.757 6.586 4.343 8z"/></svg>
                    </div>
                </div>
                <div className="relative flex items-center rounded-full border border-stone-200 bg-white shadow-sm transition-colors hover:border-stone-300">
                    <span className="pl-3 text-[11px] font-medium text-zinc-500">Max Loops</span>
                    <select
                        value={maxReviewIterations}
                        onChange={(e) => {
                            const nextValue = Number(e.target.value);
                            setMaxReviewIterations(nextValue);
                            localStorage.setItem(MAX_REVIEW_ITERATIONS_STORAGE_KEY, String(nextValue));
                        }}
                        className="cursor-pointer appearance-none border-none bg-transparent py-1 pl-1 pr-5 text-[11px] font-medium text-zinc-600 outline-none focus:ring-0"
                    >
                        {REVIEW_ITERATION_OPTIONS.map(count => (
                            <option key={count} value={count}>{count}x</option>
                        ))}
                    </select>
                    <div className="pointer-events-none absolute inset-y-0 right-0 flex items-center px-1.5 text-zinc-400">
                        <svg className="fill-current h-3 w-3" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path d="M9.293 12.95l.707.707L15.657 8l-1.414-1.414L10 10.828 5.757 6.586 4.343 8z"/></svg>
                    </div>
                </div>

                {/* Compact remaining-quota indicator, mirroring the mockup's "N left". */}
                {demoQuotaState.visible && !demoQuotaState.exhausted && (
                    <span className="ml-auto flex items-center gap-1.5 pr-1 font-mono text-[11px] font-medium text-zinc-500" title={demoQuotaState.label}>
                        <span className={`h-1.5 w-1.5 rounded-full ${demoQuotaState.remaining <= 1 ? 'bg-amber-500' : 'bg-emerald-500'}`} aria-hidden="true" />
                        {demoQuotaState.remaining} left
                    </span>
                )}
            </div>

            {demoQuotaState.visible && demoQuotaState.exhausted && (
              <div className="mb-2 flex flex-wrap items-center justify-between gap-2 rounded-xl border border-rose-200 bg-rose-50 px-3 py-2 text-xs text-rose-700">
                <span className="font-medium">{demoQuotaState.label}</span>
                {demoQuotaState.exhausted && (
                  <span className="flex items-center gap-2">
                    <button
                      type="button"
                      onClick={() => setShowApiConfig(true)}
                      className="rounded-lg border border-rose-200 bg-white px-2 py-1 font-medium text-rose-700 hover:bg-rose-100"
                    >
                      Add key
                    </button>
                    {demoQuotaState.kind === 'anonymous' && (
                      <button
                        type="button"
                        onClick={() => { window.location.href = '/login'; }}
                        className="rounded-lg bg-rose-600 px-2 py-1 font-medium text-white hover:bg-rose-700"
                      >
                        Sign up
                      </button>
                    )}
                  </span>
                )}
              </div>
            )}

            {selectedSkills.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mb-2">
                {selectedSkills.map(name => (
                  <span key={name} className="inline-flex items-center gap-1 rounded-full border border-stone-200 bg-stone-50 px-2 py-0.5 text-xs text-zinc-700">
                    /{name}
                    <button type="button" onClick={() => removeSkill(name)} className="leading-none text-zinc-400 hover:text-zinc-700">×</button>
                  </span>
                ))}
              </div>
            )}

            <div className="relative flex items-end gap-2 rounded-2xl border border-stone-300 bg-stone-50 p-2 shadow-sm transition-all focus-within:border-zinc-600 focus-within:bg-white focus-within:ring-4 focus-within:ring-zinc-700/5">
              {slashOpen && filteredSkills.length > 0 && (
                <div className="absolute bottom-full left-0 z-50 mb-2 max-h-72 w-80 overflow-auto rounded-lg border border-stone-200 bg-white py-1 shadow-lg">
                  {filteredSkills.map((s, i) => (
                    <button
                      key={s.name}
                      type="button"
                      onMouseDown={(e) => { e.preventDefault(); chooseSkill(s.name); }}
                      onMouseEnter={() => setSlashIndex(i)}
                      className={`block w-full px-3 py-2 text-left ${i === slashIndex ? 'bg-stone-100' : ''} hover:bg-stone-100`}
                    >
                      <div className="text-sm font-medium text-zinc-800">/{s.name}</div>
                      {s.description && <div className="truncate text-xs text-zinc-500">{s.description}</div>}
                    </button>
                  ))}
                </div>
              )}
              <textarea
                ref={promptInputRef}
                value={inputValue}
                onChange={(e) => {
                  handleInputChange(e.target.value);
                  e.target.style.height = 'auto';
                  e.target.style.height = Math.min(e.target.scrollHeight, 300) + 'px';
                }}
                onKeyDown={handleKeyDown}
                placeholder={isSending ? "AI is generating..." : demoQuotaState.exhausted ? demoQuotaState.exhaustedMessage : "Describe a diagram, or ask to edit this one…"}
                disabled={isSending || demoQuotaState.exhausted}
                className="max-h-[300px] min-h-[80px] flex-1 resize-none border-none bg-transparent px-4 py-3 text-[15px] leading-relaxed text-zinc-800 placeholder:text-zinc-400 focus:ring-0 disabled:cursor-not-allowed disabled:opacity-50 scrollbar-thin scrollbar-track-transparent scrollbar-thumb-stone-300"
                rows={1}
                style={{ height: 'auto', minHeight: '80px' }}
              />
              <div className="flex gap-1 mb-0.5 shrink-0">
                  {isSending ? (
                    <button
                      onClick={handleStopStream}
                      className="p-2.5 rounded-full transition-all duration-200 flex items-center justify-center bg-red-100 text-red-600 hover:bg-red-200 shadow-sm"
                      title="Stop generation"
                    >
                      <Icons.Square className="w-4 h-4" />
                    </button>
                  ) : (
                    <button
                      onClick={handleSendMessage}
                      disabled={!inputValue.trim() || demoQuotaState.exhausted}
                      className={`
                        p-2.5 rounded-full transition-all duration-200 flex items-center justify-center
                        ${inputValue.trim() && !demoQuotaState.exhausted
                          ? 'bg-zinc-800 text-white shadow-md shadow-zinc-700/10 hover:bg-zinc-700 hover:scale-105 active:scale-95'
                          : 'cursor-not-allowed bg-stone-200 text-zinc-400'
                        }
                      `}
                      title="Send message"
                    >
                      <Icons.ArrowRight className="w-4 h-4" />
                    </button>
                  )}
                  <button
                    onClick={handleRestartSession}
                    disabled={isSending}
                    className="rounded-full border border-stone-200 bg-white p-2.5 text-zinc-400 shadow-sm transition-all duration-200 hover:bg-stone-50 hover:text-zinc-700 disabled:cursor-not-allowed disabled:opacity-50"
                    title="Restart conversation"
                  >
                    <Icons.Plus className="w-4 h-4" />
                  </button>
              </div>
            </div>

          </div>
        </div>
      </div>

      {/* Export Modal - Polished */}
      {imgData && (
        <div className="absolute inset-0 z-50 flex items-center justify-center bg-zinc-700/35 p-6 backdrop-blur-sm animate-in fade-in duration-200">
            <div className="flex max-h-[90vh] w-full max-w-4xl flex-col overflow-hidden rounded-lg border border-stone-200 bg-white p-0 shadow-2xl shadow-zinc-700/20 animate-in zoom-in-95 duration-200">
                <div className="flex items-center justify-between border-b border-stone-200 bg-stone-50 px-6 py-4">
                    <div className="flex items-center gap-3">
                        <div className="rounded-lg bg-emerald-50 p-2 text-emerald-700">
                            <Icons.Download className="w-5 h-5" />
                        </div>
                        <div>
                            <h2 className="text-lg font-semibold text-zinc-800">Export Ready</h2>
                            <p className="text-xs text-zinc-500">Your diagram has been successfully converted</p>
                        </div>
                    </div>
                    <button 
                        onClick={() => setImgData(null)}
                        className="rounded-lg p-2 text-zinc-400 transition-colors hover:bg-stone-100 hover:text-zinc-700"
                    >
                        <Icons.Close className="w-5 h-5" />
                    </button>
                </div>
                
                <div className="flex min-h-[400px] flex-1 items-center justify-center overflow-auto bg-[var(--app-bg)] p-8">
                    <div className="rounded border border-stone-200 bg-white p-2 shadow-sm">
                        <img src={imgData} alt="Exported diagram" className="max-w-full h-auto object-contain" />
                    </div>
                </div>
                
                <div className="flex justify-end gap-3 border-t border-stone-200 bg-white px-6 py-4">
                   <button 
                        onClick={() => setImgData(null)}
                        className="rounded-lg px-5 py-2.5 text-sm font-medium text-zinc-600 transition-colors hover:bg-stone-100"
                    >
                        Close Preview
                    </button>
                    <a 
                        href={imgData} 
                        download="diagram.svg"
                        className="flex items-center gap-2 rounded-lg bg-zinc-700 px-5 py-2.5 text-sm font-medium text-white shadow-lg shadow-zinc-700/10 transition-all hover:bg-zinc-600"
                    >
                        <Icons.Download className="w-4 h-4" />
                        Download File
                    </a>
                </div>
            </div>
        </div>
      )}

      {/* Rename Modal */}
      {isRenameModalOpen && (
        <div className="absolute inset-0 z-50 flex items-center justify-center bg-zinc-700/35 p-6 backdrop-blur-sm animate-in fade-in duration-200">
            <div className="w-full max-w-md overflow-hidden rounded-lg border border-stone-200 bg-white shadow-2xl shadow-zinc-700/20 animate-in zoom-in-95 duration-200">
                <div className="flex items-center justify-between border-b border-stone-200 bg-stone-50 px-6 py-4">
                    <h2 className="text-lg font-semibold text-zinc-800">Rename Diagram</h2>
                    <button 
                        onClick={handleRenameCancel}
                        className="rounded-lg p-1 text-zinc-400 transition-colors hover:bg-stone-100 hover:text-zinc-700"
                        title="Close"
                    >
                        <Icons.Close className="w-5 h-5" />
                    </button>
                </div>
                
                <div className="p-6">
                    <label className="mb-2 block text-sm font-medium text-zinc-700">
                        Diagram Name
                    </label>
                    <input 
                        type="text" 
                        value={newSessionTitle}
                        onChange={(e) => setNewSessionTitle(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') handleRenameSave();
                          if (e.key === 'Escape') handleRenameCancel();
                        }}
                        className="w-full rounded-lg border border-stone-300 px-4 py-2 outline-none transition-all focus:border-zinc-600 focus:ring-4 focus:ring-zinc-700/5"
                        placeholder="Enter a new session name"
                        autoFocus
                    />
                </div>
                
                <div className="flex justify-end gap-3 border-t border-stone-200 bg-stone-50 px-6 py-4">
                   <button 
                        onClick={handleRenameCancel}
                        className="rounded-lg px-4 py-2 text-sm font-medium text-zinc-600 transition-colors hover:bg-stone-100"
                    >
                        Cancel
                    </button>
                    <button 
                        onClick={handleRenameSave}
                        disabled={!newSessionTitle.trim()}
                        className="rounded-lg bg-zinc-700 px-4 py-2 text-sm font-medium text-white shadow-lg shadow-zinc-700/10 transition-all hover:bg-zinc-600 disabled:cursor-not-allowed disabled:opacity-50 disabled:hover:bg-zinc-700"
                    >
                        Save
                    </button>
                </div>
            </div>
        </div>
      )}
      {/* Custom Models Settings Modal */}
      {showApiConfig && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-zinc-700/35 p-6 backdrop-blur-sm animate-in fade-in duration-200">
            <div className="flex max-h-[90vh] w-full max-w-2xl flex-col overflow-hidden rounded-lg border border-stone-200 bg-white shadow-2xl shadow-zinc-700/20 animate-in zoom-in-95 duration-200">
                <div className="flex shrink-0 items-center justify-between border-b border-stone-200 bg-stone-50 px-6 py-4">
                    <div className="flex items-center gap-2.5">
                      <div className="rounded-lg bg-white p-1.5 text-zinc-700 shadow-sm">
                        <Icons.Sparkles className="h-4 w-4" />
                      </div>
                      <h2 className="text-base font-semibold text-zinc-800">Custom Model Settings</h2>
                    </div>
                    <button
                        onClick={() => {
                          setShowApiConfig(false);
                          setEditingModel(null);
                        }}
                        className="rounded-lg p-1.5 text-zinc-400 transition-colors hover:bg-stone-100 hover:text-zinc-700"
                    >
                        <Icons.Close className="w-4 h-4" />
                    </button>
                </div>
                
                <div className="flex flex-1 overflow-hidden">
                    {/* List of Models */}
                    <div className="flex w-1/3 flex-col border-r border-stone-200 bg-stone-50">
                        <div className="p-3">
                            <button 
                                onClick={handleAddNewModel}
                                className="flex w-full items-center justify-center gap-2 rounded-lg border border-stone-200 bg-white py-2 text-sm font-medium text-zinc-700 shadow-sm transition-colors hover:bg-stone-100"
                            >
                                <Icons.Plus className="w-4 h-4" /> Add Model
                            </button>
                        </div>
                        <div className="flex-1 overflow-y-auto p-3 space-y-2">
                            {customModels.map(model => (
                                <div 
                                    key={model.id}
                                    onClick={() => setEditingModel({ ...model, apiKey: '', provider: model.provider || 'openai' })}
                                    className={`cursor-pointer rounded-lg border p-3 transition-all ${editingModel?.id === model.id ? 'border-stone-300 bg-white shadow-sm ring-1 ring-stone-200' : 'border-stone-200 bg-white hover:border-stone-300 hover:shadow-sm'}`}
                                >
                                    <div className="flex items-center justify-between mb-1">
                                        <div className="truncate pr-2 text-sm font-semibold text-zinc-800">{model.name}</div>
                                        <div className="flex items-center gap-1 shrink-0">
                                            {/* Toggle Switch */}
                                            <button 
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    const newModels = customModels.map(m => m.id === model.id ? {...m, enabled: !m.enabled} : m);
                                                    saveCustomModels(newModels);
                                                    if (!(!model.enabled) && selectedCustomModelId === model.id) {
                                                        setSelectedCustomModelId('default');
                                                        localStorage.setItem('ai_agent_selected_model', 'default');
                                                    }
                                                }}
                                                className={`relative inline-flex h-4 w-7 items-center rounded-full transition-colors focus:outline-none ${model.enabled ? 'bg-zinc-700' : 'bg-stone-300'}`}
                                            >
                                                <span className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${model.enabled ? 'translate-x-3.5' : 'translate-x-0.5'}`} />
                                            </button>
                                            <button onClick={(e) => { e.stopPropagation(); void handleDeleteModel(model.id); }} className="ml-1 text-zinc-400 hover:text-rose-600">
                                                <Icons.Trash className="w-3.5 h-3.5" />
                                            </button>
                                        </div>
                                    </div>
                                    <div className="truncate text-[10px] text-zinc-500">{model.model}{model.maskedApiKey ? ` · ${model.maskedApiKey}` : ''}</div>
                                </div>
                            ))}
                            {customModels.length === 0 && (
                                <div className="py-6 text-center text-xs text-zinc-400">
                                    No custom models yet<br/>Click the button above to add one
                                </div>
                            )}
                        </div>
                    </div>

                    {/* Edit Form */}
                    <div className="flex-1 p-6 overflow-y-auto bg-white">
                        {editingModel ? (
                            <div className="space-y-4 animate-in fade-in duration-200">
                                <div>
                                    <label className="mb-1 block text-xs font-medium text-zinc-700">Provider</label>
                                    <select
                                        value={editingModel.provider || 'custom'}
                                        onChange={e => applyProviderPreset(e.target.value)}
                                        className="w-full rounded-lg border border-stone-300 px-3 py-2 text-sm outline-none transition-all focus:border-zinc-600 focus:ring-4 focus:ring-zinc-700/5"
                                    >
                                        {providerPresets.length === 0 && (
                                            <option value={editingModel.provider || 'openai'}>{editingModel.provider || 'openai'}</option>
                                        )}
                                        {providerPresets.map(preset => (
                                            <option key={preset.id} value={preset.id}>{preset.displayName}</option>
                                        ))}
                                    </select>
                                    <p className="mt-1 text-[10px] text-zinc-400">Selecting a provider fills in the endpoint and a default model. Structured JSON output is enabled automatically where the provider supports it.</p>
                                </div>
                                <div>
                                    <label className="mb-1 block text-xs font-medium text-zinc-700">Display Name</label>
                                    <input type="text" value={editingModel.name} onChange={e => setEditingModel({...editingModel, name: e.target.value})} className="w-full rounded-lg border border-stone-300 px-3 py-2 text-sm outline-none transition-all focus:border-zinc-600 focus:ring-4 focus:ring-zinc-700/5" placeholder="Example: My GPT-4o" />
                                </div>
                                <div>
                                    <label className="mb-1 block text-xs font-medium text-zinc-700">Model Name</label>
                                    <input type="text" value={editingModel.model} onChange={e => setEditingModel({...editingModel, model: e.target.value})} className="w-full rounded-lg border border-stone-300 px-3 py-2 text-sm outline-none transition-all focus:border-zinc-600 focus:ring-4 focus:ring-zinc-700/5" placeholder="Example: gpt-4o" />
                                </div>
                                <div>
                                    <label className="mb-1 block text-xs font-medium text-zinc-700">Base URL</label>
                                    <input type="text" value={editingModel.baseUrl} onChange={e => setEditingModel({...editingModel, baseUrl: e.target.value})} className="w-full rounded-lg border border-stone-300 px-3 py-2 text-sm outline-none transition-all focus:border-zinc-600 focus:ring-4 focus:ring-zinc-700/5" placeholder="Example: https://api.openai.com" />
                                </div>
                                <div>
                                    <label className="mb-1 block text-xs font-medium text-zinc-700">API Key</label>
                                    <input type="password" value={editingModel.apiKey} onChange={e => setEditingModel({...editingModel, apiKey: e.target.value})} className="w-full rounded-lg border border-stone-300 px-3 py-2 text-sm outline-none transition-all focus:border-zinc-600 focus:ring-4 focus:ring-zinc-700/5" placeholder="sk-..." />
                                </div>
                                <div>
                                    <label className="mb-1 block text-xs font-medium text-zinc-700">Completions Path (optional)</label>
                                    <input type="text" value={editingModel.completionsPath} onChange={e => setEditingModel({...editingModel, completionsPath: e.target.value})} className="w-full rounded-lg border border-stone-300 px-3 py-2 text-sm outline-none transition-all focus:border-zinc-600 focus:ring-4 focus:ring-zinc-700/5" placeholder="Default: /chat/completions" />
                                </div>
                                <div className="pt-2 flex justify-end">
                                    <button onClick={handleSaveEditingModel} disabled={!editingModel.apiKey.trim()} className="rounded-lg bg-zinc-700 px-6 py-2 text-sm font-medium text-white shadow-sm transition-all hover:bg-zinc-600 disabled:cursor-not-allowed disabled:opacity-50">
                                        Save Settings
                                    </button>
                                </div>
                            </div>
                        ) : (
                            <div className="flex h-full flex-col items-center justify-center text-zinc-400">
                                <Icons.Sparkles className="w-12 h-12 mb-3 opacity-20" />
                                <p className="text-sm">Select a model on the left to edit, or click Add Model.</p>
                            </div>
                        )}
                    </div>
                </div>
            </div>
        </div>
      )}
    </div>
  );
}
