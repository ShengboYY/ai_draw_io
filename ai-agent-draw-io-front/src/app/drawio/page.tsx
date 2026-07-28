'use client';

import { DrawIoEmbed, type DrawIoEmbedRef } from './secure-drawio-embed';
import type { DrawioSelection } from './secure-drawio-bridge';
import Image from 'next/image';
import { Suspense, useRef, useState, useEffect, useCallback, useMemo } from 'react';
import { useRouter, useSearchParams } from 'next/navigation';
import { setUserInfo as persistUserInfo } from '@/utils/cookie';
import { rememberAnonymousWorkspaceHint } from '@/utils/workspace-identity';
import { agentApi, ApiResponseError, StreamEvent, type CellCitationDTO } from '@/api/agent';
import type { CanvasVisualReviewEvidenceDTO, CurrentAccountResponseDTO, DiagramCanvasStateResponseDTO, DiagramSummaryResponseDTO, ModelCredentialResponseDTO, ProviderPresetDTO } from '@/types/api';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import {
  buildStreamingPreviewXml,
  isValidDrawioCellXml,
  normalizeDrawioLegendSwatches,
  planFinalDiagramDelivery,
} from './streaming-preview';
import { buildDrawioChatRequestPayload } from './chat-request-payload';
import { API_CONFIG } from '@/config/api-config';
import { createMaterialClient } from '@/api/material';
import { createMaterialCapabilitiesClient } from '@/api/material-capabilities';
import { createChartbookClient } from '@/api/chartbook';
import { ConversationAttachmentTray } from '@/features/sources/ConversationAttachmentTray';
import { MessageAttachmentPreview } from '@/features/sources/MessageAttachmentPreview';
import { ContextReceiptBar } from '@/features/context/ContextReceiptBar';
import { buildCitationReceipt, buildContextReceipts } from '@/features/context/context-receipts';
import {
  ComposerAddMenu,
  ComposerLibrarySelectionTray,
} from '@/features/sources/ComposerAddMenu';
import type { MaterialUploaderHandle } from '@/features/materials/MaterialUploader';
import type { Chartbook, MaterialCatalogCard } from '@/features/materials/material-types';
import { isReadyUploadStatus, isTerminalUploadStatus } from '@/features/materials/upload-machine';
import { FilesPanel } from '@/features/files/FilesPanel';
import { buildFilesPanelGroups } from '@/features/files/files-panel-model';
import { DirectConfirmationPanel } from '@/features/sources/DirectConfirmationPanel';
import {
  buildDirectClarifications,
  type DirectClarification,
  type DirectClarificationResolution,
} from '@/features/sources/direct-confirmation';
import {
  readConversationAttachments,
  writeConversationAttachments,
  type ConversationAttachment,
} from '@/features/sources/conversation-attachments';
import {
  MAX_CONVERSATION_LIBRARY_SELECTIONS,
  readConversationLibrarySelections,
  writeConversationLibrarySelections,
  type ConversationLibrarySelection,
} from '@/features/sources/conversation-library-selections';
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
  type VisualRepairProvenance,
} from './manual-canvas-save';
import {
  beginAiCanvasMutation,
  createCanvasPersistenceState,
  finishAiCanvasMutation,
  shouldRetryManualCanvasSaveConflict,
} from './canvas-persistence-coordinator';
import { buildCanvasStateConflictMessage } from './canvas-state-conflict';
import { buildDiagramHistoryEntries } from './diagram-history';
import {
  buildRestoredDiagramState,
  isDiagramRouteReady,
  normalizeRestoredDrawioXml,
} from './diagram-restore';
import { buildDiagramTitleFromPrompt, DEFAULT_DIAGRAM_TITLE } from './diagram-title';
import {
  buildRestoredConversationMessages,
  mergeRestoredConversationPresentation,
  type RestoredAttachmentMetadata,
} from './conversation-restore';
import { citationOriginLabel } from './citation-origin';
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
  VisualReviewPresentation,
  buildAgentCompletionReply,
  buildAgentRunView,
  buildRouteStepDetail,
  buildVisualRepairStepDetail,
  buildVisualReviewStepDetail,
  finishEventsAfterCanvasLoaded,
  finishPreviousPhaseEvents,
  getVisibleExecutionSteps,
  projectUserExecutionStep,
  shouldShowAgentTyping,
  thinkingRouteLabel,
  usesChinesePresentation,
  visualReviewUnavailableReason,
  visualReviewStageLabel,
} from './agent-run-presentation';
import {
  buildThumbnailExportRequest,
  planThumbnailExport,
  shouldPersistThumbnail,
} from './thumbnail-export';
import {
  CanvasExportCoordinator,
  CanvasExportError,
} from './canvas-export-coordinator';
import {
  VISUAL_REVIEW_DETAIL_WIDTH,
  VISUAL_REVIEW_RENDERER_VERSION,
  buildVisualReviewEvidencePlan,
  buildVisualReviewExportRequest,
  createVisualReviewDetailTiles,
  isVisualReviewPngDataUrl,
} from './visual-review-export';
import {
  buildCanvasVisualReviewRequest,
  canStartPostMutationReview,
  nextVisualReviewStage,
  shouldShowUnavailableReview,
  shouldReviewSavedRepair,
} from './visual-review-chain';
import { streamErrorMessage } from './stream-error-presentation';

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
  routeType?: string;
  language?: 'zh' | 'en';
  steps?: MessageStep[];
  events?: AgentRunEvent[];
  evidenceSources?: Array<{
    citationKey: string;
    sourceLabel: string;
    pageNumber?: number;
    modality?: string;
    origin: 'EXISTING_REFERENCE' | 'EXPLICIT' | 'SEARCH' | 'SUPPLEMENTAL';
  }>;
  evidenceClaims?: Array<{
    claimKey: string;
    citationKeys: string[];
    supportType: 'DIRECT' | 'SYNTHESIZED' | 'VISUAL_VERIFIED' | 'AI_KNOWLEDGE';
  }>;
  contextReceipts?: ReturnType<typeof buildContextReceipts>;
  citationReceipt?: ReturnType<typeof buildCitationReceipt>;
  attachments?: ConversationAttachment[];
  timestamp: number;
};

type TargetClarification = {
  candidates: Array<{ cellId: string; kind: string; shortLabel: string; reasonCode: string }>;
  canvasVersion: number;
  contentHash: string;
};

type DirectConfirmation = {
  issues: Array<{
    reasonCode: string;
    observedValue?: string;
    observedFingerprint?: string;
  }>;
  sourceVersionId: string;
  originalPrompt: string;
  selections: Record<string, DirectClarificationResolution>;
};

type SendContentOptions = {
  requestContent?: string;
  directClarifications?: DirectClarification[];
  directConfirmationSourceVersionId?: string;
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
const DETERMINISTIC_REPAIR_ROUNDS_STORAGE_KEY = 'ai_drawio_max_deterministic_repair_rounds';
const LEGACY_REVIEW_ITERATIONS_STORAGE_KEY = 'ai_drawio_max_review_iterations';
const DETERMINISTIC_REPAIR_ROUND_OPTIONS = [0, 1, 2, 3];
const EMPTY_DRAWIO_XML = '<mxGraphModel><root><mxCell id="0"/><mxCell id="1" parent="0"/></root></mxGraphModel>';
const DRAWIO_BASE_URL = process.env.NEXT_PUBLIC_DRAWIO_BASE_URL || 'https://embed.diagrams.net';
const DRAWIO_SELECTION_PLUGIN_ID = process.env.NEXT_PUBLIC_DRAWIO_BASE_URL ? 'zippSelection' : undefined;
const STREAMING_PREVIEW_FRAME_MS = 280;
// Keep the empty composer compact; attachments and multiline input can still expand it naturally.
const COMPOSER_TEXTAREA_MIN_HEIGHT_PX = 84;

type StructuredCanvasContext = {
  canvasXml?: string;
  canvasSummary?: string;
  canvasImageDataUrl?: string;
  canvasImageRendererVersion?: typeof VISUAL_REVIEW_RENDERER_VERSION;
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
  ArrowUp: ({ className }: { className?: string }) => (
    <svg xmlns="http://www.w3.org/2000/svg" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" className={className}>
      <line x1="12" y1="19" x2="12" y2="5"></line>
      <polyline points="5 12 12 5 19 12"></polyline>
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

const parseDrawioXml = (xml?: string | null) => {
  const parser = new DOMParser();
  const doc = parser.parseFromString(xml && xml.trim() ? xml : EMPTY_DRAWIO_XML, 'application/xml');
  if (doc.querySelector('parsererror')) {
    return parser.parseFromString(EMPTY_DRAWIO_XML, 'application/xml');
  }
  return doc;
};

const provenanceRefForCell = (xml: string | null | undefined, cellId: string) => {
  const cell = Array.from(parseDrawioXml(xml).querySelectorAll('mxCell'))
    .find(candidate => candidate.getAttribute('id') === cellId);
  const ref = cell?.getAttribute('zippProvenanceRef') || '';
  return /^prv_[a-f0-9]{24}$/.test(ref) ? ref : undefined;
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

const getValidationStatus = (chunk: StreamEvent['chunk']): AgentRunEventStatus => {
  if (chunk.type !== 'validation_result') return 'done';
  if (chunk.valid === false || chunk.severity === 'critical' || chunk.severity === 'error') return 'warning';
  if (chunk.severity === 'warning') return 'warning';
  return 'done';
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
  const selectedCellsRef = useRef<DrawioSelection | null>(null);
  const citationRequestRef = useRef(0);
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
  const [citationCellId, setCitationCellId] = useState<string | null>(null);
  const [cellCitations, setCellCitations] = useState<CellCitationDTO[]>([]);
  const [citationsLoading, setCitationsLoading] = useState(false);
  const [targetClarification, setTargetClarification] = useState<TargetClarification | null>(null);
  const [directConfirmation, setDirectConfirmation] = useState<DirectConfirmation | null>(null);
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
  const [isFilesPanelOpen, setIsFilesPanelOpen] = useState(false);

  // Stream State
  const streamAbortRef = useRef<AbortController | null>(null);

  // Context State
  const pendingThumbnailExportRef = useRef<{ diagramId: string; xml: string } | null>(null);
  const canvasLoadWaitersRef = useRef<Array<{
    sessionId: string;
    resolve: (loaded: boolean) => void;
    timer: ReturnType<typeof setTimeout>;
  }>>([]);
  const exportCoordinatorRef = useRef<CanvasExportCoordinator | null>(null);
  if (!exportCoordinatorRef.current) {
    exportCoordinatorRef.current = new CanvasExportCoordinator(options => {
      const editor = drawioRef.current;
      if (!editor) throw new Error('Draw.io editor is not ready.');
      editor.exportDiagram(options);
    });
  }
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
  const visualRepairProvenanceRef = useRef(new Map<string, VisualRepairProvenance>());
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
  const [maxDeterministicRepairRounds, setMaxDeterministicRepairRounds] = useState(0);
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
  const currentDiagramId = sessions.find(session => session.id === currentSessionId)?.diagramId;
  const isRequestedDiagramReady = isDiagramRouteReady(restoreDiagramId, currentDiagramId);
  const materialClient = useMemo(() => createMaterialClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const capabilitiesClient = useMemo(() => createMaterialCapabilitiesClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const chartbookClient = useMemo(() => createChartbookClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  // Keep the originating chartbook until this exact diagram has reached the server.
  const pendingChartbookAssignmentRef = useRef<{
    chartbookId: string;
    diagramId: string;
    inFlight: boolean;
  } | null>(null);
  const [conversationAttachments, setConversationAttachments] = useState<ConversationAttachment[]>([]);
  // A turn may bind only attachments that finished producing a usable direct/retrieval artifact.
  const hasProcessingAttachments = conversationAttachments.some(attachment => {
    const state = attachment.state.trim().toUpperCase();
    return !isReadyUploadStatus(state) && state !== 'PARTIAL_READY';
  });
  const [sentAttachmentUploadIds, setSentAttachmentUploadIds] = useState<string[]>([]);
  const attachmentUploaderRef = useRef<MaterialUploaderHandle>(null);
  const [attachmentSessionLoaded, setAttachmentSessionLoaded] = useState('');
  const [restoredAttachments, setRestoredAttachments] = useState<ConversationAttachment[]>([]);
  const [librarySelections, setLibrarySelections] = useState<ConversationLibrarySelection[]>([]);
  const [librarySelectionSessionLoaded, setLibrarySelectionSessionLoaded] = useState('');
  const [acceptedMaterialMimeTypes, setAcceptedMaterialMimeTypes] = useState<string[]>([
    'application/pdf', 'image/png', 'image/jpeg',
  ]);
  const [filesChartbook, setFilesChartbook] = useState<Chartbook | null>(null);
  const [conversationFiles, setConversationFiles] = useState<MaterialCatalogCard[]>([]);
  const [chartbookFiles, setChartbookFiles] = useState<MaterialCatalogCard[]>([]);
  const [isFilesLoading, setIsFilesLoading] = useState(false);
  const [filesError, setFilesError] = useState('');
  const [busyFileId, setBusyFileId] = useState('');
  const filesRequestRef = useRef(0);
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

  const assignPendingChartbookAfterSave = useCallback(async (diagramId: string) => {
    const pending = pendingChartbookAssignmentRef.current;
    if (!pending || pending.diagramId !== diagramId || pending.inFlight) return;

    pending.inFlight = true;
    try {
      await chartbookClient.assignDiagram(diagramId, pending.chartbookId);
      // A newer Create New action must not be cleared by an older request finishing late.
      if (pendingChartbookAssignmentRef.current === pending) {
        pendingChartbookAssignmentRef.current = null;
      }
    } catch (error) {
      // Keep the assignment so the next successful diagram save can retry it.
      pending.inFlight = false;
      console.warn('Failed to file the saved diagram into its chartbook:', error);
    }
  }, [chartbookClient]);

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

  const fetchLatestCanvasBaseline = async (
    userId: string,
    diagramId: string,
  ): Promise<{ version?: number; contentHash?: string }> => {
    try {
      const response = await agentApi.getDiagram(userId, diagramId);
      const version = response.data?.version;
      return {
        version: Number.isFinite(version) ? version : undefined,
        contentHash: response.data?.contentHash?.trim() || undefined,
      };
    } catch (error) {
      console.warn('Failed to load the latest canvas baseline after a save conflict:', error);
      return {};
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
    if (result.started && diagramId) {
      // A later AI mutation supersedes the last repair baseline; only direct human edits count.
      visualRepairProvenanceRef.current.delete(diagramId);
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
        {
          expectedContentHash: request.expectedContentHash,
          visualRepairSourceRunId: request.visualRepairSourceRunId,
          visualRepairRunId: request.visualRepairRunId,
          visualRepairRound: request.visualRepairRound,
        },
      );
      // Only sync the version; the local canvas may already be newer than the XML just saved,
      // so writing the response XML back would briefly roll the session state backwards.
      rememberManualCanvasVersion(request.sessionId, request.diagramId, response.data?.version, response.data?.contentHash);
      void assignPendingChartbookAfterSave(request.diagramId);
      if (request.visualRepairRunId) {
        visualRepairProvenanceRef.current.delete(request.diagramId);
      }
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

        // Version and hash form one optimistic-lock baseline; never retry with only half of it.
        const fetchedBaseline = knownConflictVersion !== undefined && knownConflictContentHash
          ? {}
          : await fetchLatestCanvasBaseline(request.userId, request.diagramId);
        const latestVersion = knownConflictVersion ?? fetchedBaseline.version;
        const latestContentHash = knownConflictContentHash ?? fetchedBaseline.contentHash;
        if (Number.isFinite(latestVersion) && latestContentHash) {
          rememberManualCanvasVersion(
            request.sessionId,
            request.diagramId,
            latestVersion,
            latestContentHash,
          );
          // Manual edits treat the local canvas as the source of truth, so save over the fresh version.
          await performManualCanvasSave({
            ...request,
            expectedVersion: latestVersion,
            expectedContentHash: latestContentHash,
          }, false);
          return;
        }
        console.warn('Manual canvas autosave conflicted and the latest backend baseline could not be loaded.');
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
        expectedContentHash: request.expectedContentHash,
        visualRepairSourceRunId: request.visualRepairSourceRunId,
        visualRepairRunId: request.visualRepairRunId,
        visualRepairRound: request.visualRepairRound,
      })
      .then(response => {
        const version = response.data?.version;
        if (Number.isFinite(version)) {
          manualCanvasVersionsRef.current.set(request.diagramId, version as number);
        }
        if (request.visualRepairRunId) {
          visualRepairProvenanceRef.current.delete(request.diagramId);
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
      canvasContentHash: activeSession?.canvasContentHash,
      canvasXml: xml,
      visualRepairProvenance: activeSession?.diagramId
        && !aiCanvasMutationDiagramIdsRef.current.has(activeSession.diagramId)
        ? visualRepairProvenanceRef.current.get(activeSession.diagramId)
        : undefined,
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

  const resolveRestoredAttachmentMetadata = useCallback(async (
    restoredMessages: Array<{ attachmentRefs?: string[] }>,
  ): Promise<Record<string, RestoredAttachmentMetadata>> => {
    const uploadIds = [...new Set(restoredMessages.flatMap(message => message.attachmentRefs || []))];
    const resolved = await Promise.all(uploadIds.map(async uploadId => {
      try {
        const status = await materialClient.status(uploadId);
        if (!status.materialId) {
          return [uploadId, { fileName: uploadId, state: status.state }] as const;
        }
        const details = await materialClient.details(status.materialId).catch(() => null);
        return [uploadId, {
          fileName: details?.material.displayName || uploadId,
          state: status.state,
          materialId: status.materialId,
          versionId: status.versionId || details?.material.latestVersionId,
        }] as const;
      } catch {
        // Expired upload metadata degrades to the opaque reference already stored with the message.
        return [uploadId, { fileName: uploadId, state: 'SUCCEEDED' }] as const;
      }
    }));
    return Object.fromEntries(resolved);
  }, [materialClient]);

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
      hasConversationMessages,
    })) return true;

    try {
      // V2 admission requires the owned diagram row before it can bind the runtime session.
      const initialCanvasXml = canvasXml?.trim() || EMPTY_DRAWIO_XML;
      const response = await agentApi.saveDiagramCanvasState(ownerId, normalizedDiagramId || '', initialCanvasXml);
      const version = response.data?.version;
      if ((Number.isFinite(version) || response.data?.contentHash) && currentSessionRef.current) {
        rememberManualCanvasVersion(currentSessionRef.current, normalizedDiagramId || '', version as number, response.data?.contentHash);
      }
      void assignPendingChartbookAfterSave(normalizedDiagramId || '');
      await persistDiagramTitle(normalizedDiagramId, title);
      return true;
    } catch (error) {
      if (error instanceof ApiResponseError && error.code === 'CANVAS_VERSION_CONFLICT') {
        const latestBaseline = await fetchLatestCanvasBaseline(ownerId, normalizedDiagramId || '');
        if ((Number.isFinite(latestBaseline.version) || latestBaseline.contentHash) && currentSessionRef.current) {
          rememberManualCanvasVersion(
            currentSessionRef.current,
            normalizedDiagramId || '',
            latestBaseline.version,
            latestBaseline.contentHash,
          );
        }
        return true;
      }
      console.warn('Failed to create chat-only diagram shell:', error);
      return false;
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
    const sessionId = currentSessionRef.current;
    if (!drawioRef.current || !sessionId) return;

    void exportCoordinatorRef.current?.enqueue({
      purpose: 'thumbnail-png',
      diagramId,
      sessionId,
      format: 'png',
      options: buildThumbnailExportRequest(),
    }).then(result => {
      if (result.sessionId !== currentSessionRef.current) return;
      void persistDiagramThumbnail(result.diagramId, result.data);
    }).catch(error => {
      if (error instanceof CanvasExportError && error.code === 'EXPORT_SCOPE_CHANGED') return;
      console.warn('Thumbnail export failed:', error);
    });
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

  const requestAutosaveXmlExport = (session: Session) => {
    const diagramId = session.diagramId || makeLocalDiagramId(session.id);
    void exportCoordinatorRef.current?.enqueue({
      purpose: 'autosave-xml',
      diagramId,
      sessionId: session.id,
      format: 'xmlsvg',
      options: { format: 'xmlsvg' },
    }).then(result => {
      if (result.sessionId !== currentSessionRef.current) return;
      const latestSession = sessionsRef.current.find(item => item.id === result.sessionId);
      const xml = chooseUsableCanvasXml(result, latestSession?.drawIoXml);
      saveCurrentCanvasXml(xml);
      queueManualCanvasStateSave(xml, latestSession);
      queueDiagramThumbnailExport(result.diagramId, xml);
    }).catch(error => {
      if (error instanceof CanvasExportError && error.code === 'EXPORT_SCOPE_CHANGED') return;
      console.warn('Autosave export failed:', error);
    });
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
    const loadedSessionId = currentSessionRef.current;
    const completedWaiters = canvasLoadWaitersRef.current.filter(waiter => waiter.sessionId === loadedSessionId);
    canvasLoadWaitersRef.current = canvasLoadWaitersRef.current.filter(waiter => waiter.sessionId !== loadedSessionId);
    completedWaiters.forEach(waiter => {
      clearTimeout(waiter.timer);
      waiter.resolve(true);
    });
    flushPendingThumbnailExport();
  };

  const waitForCanvasLoad = (sessionId: string, timeoutMs = 5_000) => new Promise<boolean>(resolve => {
    const waiter = {
      sessionId,
      resolve,
      timer: setTimeout(() => {
        canvasLoadWaitersRef.current = canvasLoadWaitersRef.current.filter(candidate => candidate !== waiter);
        resolve(false);
      }, timeoutMs),
    };
    canvasLoadWaitersRef.current.push(waiter);
  });

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
    setDirectConfirmation(null);
    const staleWaiters = canvasLoadWaitersRef.current.filter(waiter => waiter.sessionId !== currentSessionId);
    canvasLoadWaitersRef.current = canvasLoadWaitersRef.current.filter(waiter => waiter.sessionId === currentSessionId);
    staleWaiters.forEach(waiter => {
      clearTimeout(waiter.timer);
      waiter.resolve(false);
    });
    const activeSession = sessionsRef.current.find(session => session.id === currentSessionId);
    exportCoordinatorRef.current?.retainScope({
      sessionId: currentSessionId,
      diagramId: activeSession?.diagramId,
    });
  }, [currentSessionId]);

  useEffect(() => {
    const attachmentSessionId = sessionId.trim();
    setSentAttachmentUploadIds([]);
    if (!attachmentSessionId) {
      setConversationAttachments([]);
      setAttachmentSessionLoaded('');
      setRestoredAttachments([]);
      return;
    }
    const restored = readConversationAttachments(window.sessionStorage, attachmentSessionId);
    setConversationAttachments(restored);
    setRestoredAttachments(restored);
    setAttachmentSessionLoaded(attachmentSessionId);
  }, [sessionId]);

  useEffect(() => {
    const attachmentSessionId = sessionId.trim();
    if (!attachmentSessionId || attachmentSessionLoaded !== attachmentSessionId) return;
    writeConversationAttachments(window.sessionStorage, attachmentSessionId, conversationAttachments);
  }, [attachmentSessionLoaded, conversationAttachments, sessionId]);

  useEffect(() => {
    const librarySessionId = sessionId.trim();
    if (!librarySessionId) {
      setLibrarySelections([]);
      setLibrarySelectionSessionLoaded('');
      return;
    }
    setLibrarySelections(readConversationLibrarySelections(window.localStorage, librarySessionId));
    setLibrarySelectionSessionLoaded(librarySessionId);
  }, [sessionId]);

  useEffect(() => {
    const librarySessionId = sessionId.trim();
    if (!librarySessionId || librarySelectionSessionLoaded !== librarySessionId) return;
    writeConversationLibrarySelections(window.localStorage, librarySessionId, librarySelections);
  }, [librarySelectionSessionLoaded, librarySelections, sessionId]);

  useEffect(() => {
    if (!attachmentSessionLoaded || restoredAttachments.length === 0) return;
    let cancelled = false;
    restoredAttachments
      .filter(attachment => !isTerminalUploadStatus(attachment.state))
      .forEach(attachment => {
        // Resume status polling after a reload without retaining file bytes in browser storage.
        void materialClient.pollStatus(attachment.uploadId, status => {
          if (cancelled || sessionId !== attachmentSessionLoaded) return;
          setConversationAttachments(previous => previous.map(item => item.uploadId === attachment.uploadId ? {
            ...item,
            state: status.state,
            errorCode: status.errorCode,
          } : item));
        }).catch(() => {
          if (cancelled || sessionId !== attachmentSessionLoaded) return;
          setConversationAttachments(previous => previous.map(item => item.uploadId === attachment.uploadId ? {
            ...item,
            state: 'FAILED',
          } : item));
        });
      });
    return () => { cancelled = true; };
  }, [attachmentSessionLoaded, materialClient, restoredAttachments, sessionId]);

  useEffect(() => {
    let cancelled = false;
    void capabilitiesClient.get()
      .then(capabilities => {
        if (!cancelled) setAcceptedMaterialMimeTypes(capabilities.acceptedMimeTypes);
      })
      .catch(error => {
        if (!cancelled) console.warn('Failed to load material capabilities:', error);
      });
    return () => { cancelled = true; };
  }, [capabilitiesClient]);

  const refreshFilesPanel = useCallback(async () => {
    const requestId = ++filesRequestRef.current;
    const conversationId = sessionId.trim();
    if (!conversationId && !currentDiagramId) {
      setFilesChartbook(null);
      setConversationFiles([]);
      setChartbookFiles([]);
      return;
    }
    setIsFilesLoading(true);
    setFilesError('');
    try {
      const chartbook = currentDiagramId
        ? await chartbookClient.forDiagram(currentDiagramId)
        : null;
      const [conversationPage, chartbookPage] = await Promise.all([
        // A blank diagram can belong to a chartbook before it has a conversation.
        conversationId
          ? materialClient.listScope('CONVERSATION', conversationId,
            { lifecycleState: 'ACTIVE', limit: 100 })
          : Promise.resolve(null),
        chartbook
          ? materialClient.listScope('CHARTBOOK', chartbook.chartbookId,
            { lifecycleState: 'ACTIVE', limit: 100 })
          : Promise.resolve(null),
      ]);
      if (requestId !== filesRequestRef.current) return;
      setFilesChartbook(chartbook);
      setConversationFiles(conversationPage?.items || []);
      setChartbookFiles(chartbookPage?.items || []);
    } catch (error) {
      if (requestId !== filesRequestRef.current) return;
      console.warn('Failed to load files panel:', error);
      setFilesError('Failed to load files.');
    } finally {
      if (requestId === filesRequestRef.current) setIsFilesLoading(false);
    }
  }, [chartbookClient, currentDiagramId, materialClient, sessionId]);

  useEffect(() => {
    if (!isFilesPanelOpen) return;
    void refreshFilesPanel();
  }, [conversationAttachments, isFilesPanelOpen, refreshFilesPanel]);

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
      persistSessions(savedSessions);
      const newSession = createNewSession();
      // Read before the URL is stripped below, and bind the folder to this new diagram only.
      const originatingChartbookId = freshParams.get('chartbookId')?.trim();
      if (originatingChartbookId) {
        pendingChartbookAssignmentRef.current = {
          chartbookId: originatingChartbookId,
          diagramId: newSession.diagramId,
          inFlight: false,
        };
      }
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

    if (restoreDiagramId?.trim()) {
      // A URL-targeted diagram must be authorized and loaded before any cached session becomes active.
      setSessions(savedSessions);
      persistSessions(savedSessions);
      currentSessionRef.current = null;
      setCurrentSessionId(null);
      setSessionId('');
      setMessages([]);
      replaceEditorXml(EMPTY_DRAWIO_XML);
      return;
    }

    if (savedSessions.length > 0) {
      setSessions(savedSessions);
      // The restore request can finish before React publishes the state update below.
      persistSessions(savedSessions);
      // Load the most recent session (first one if sorted by lastModified desc)
      const mostRecent = [...savedSessions].sort((a, b) => b.lastModified - a.lastModified)[0];
      setCurrentSessionId(mostRecent.id);
      // Draft attachments and conversation files are keyed by the backend conversation ID.
      setSessionId(mostRecent.backendSessionId || '');
      setMessages(mostRecent.messages);
      replaceEditorXml(mostRecent.drawIoXml || EMPTY_DRAWIO_XML);
    } else {
      createNewSession();
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

  const createNewSession = (backendId = '') => {
    pendingThumbnailExportRef.current = null;
    armBlankEditorGuard();
    const localSessionId = Date.now().toString();
    const newSession: Session & { diagramId: string } = {
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
    return newSession;
  };

  useEffect(() => {
    if (!currentUser || !restoreDiagramId || restoredDiagramIdRef.current === restoreDiagramId) return;

    let cancelled = false;
    restoredDiagramIdRef.current = restoreDiagramId;
    // Detach the previous route immediately so it cannot receive messages for this URL.
    currentSessionRef.current = null;
    setCurrentSessionId(null);
    setSessionId('');
    setMessages([]);
    replaceEditorXml(EMPTY_DRAWIO_XML);
    Promise.all([
      agentApi.getDiagram(currentUser, restoreDiagramId),
      agentApi.listDiagramMessages(currentUser, restoreDiagramId).catch(() => ({ data: [] })),
    ])
      .then(async ([res, messageRes]) => {
        if (cancelled) return;
        const diagram = res.data;
        if (!diagram?.diagramId) {
          setMessages([{
            id: `${Date.now()}-restore-missing`,
            role: 'agent',
            content: 'Diagram not found.',
            timestamp: Date.now(),
          }]);
          return;
        }

        const restored = buildRestoredDiagramState(diagram);
        const restoredSessionId = `restored-${restored.diagramId}`;
        const attachmentMetadata = await resolveRestoredAttachmentMetadata(messageRes.data || []);
        if (cancelled) return;
        const durableMessages: Message[] = buildRestoredConversationMessages(
          messageRes.data || [],
          restored.title,
          attachmentMetadata,
        );
        const cachedSession = sessionsRef.current.find(session => session.diagramId === restored.diagramId);
        const restoredMessages: Message[] = mergeRestoredConversationPresentation(
          durableMessages,
          cachedSession?.messages || [],
        );
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
          setMessages([{
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
  }, [currentUser, resolveRestoredAttachmentMetadata, restoreDiagramId]);

  const openHistoryDiagram = (diagramId: string) => {
    setIsSidebarOpen(false);
    router.push(`/drawio?diagramId=${encodeURIComponent(diagramId)}`);
  };

  const handleBackToSourcePage = () => {
    // Return to the page that opened the editor; direct visits fall back to the diagram list.
    if (window.history.length > 1) {
      router.back();
      return;
    }
    router.replace('/diagrams');
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
        // Continue below and ask the server for an anonymous capability.
      }

      if (!ownerId) {
        setAccountDisplayName('');
        try {
          const anonymous = await agentApi.ensureAnonymousWorkspace();
          ownerId = anonymous.data?.ownerId || '';
          rememberAnonymousWorkspaceHint(window.localStorage, ownerId);
        } catch {
          setHistoryError('Failed to initialize the anonymous workspace.');
          return;
        }
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
      // Read the legacy key only as a one-release migration fallback.
      const savedRepairRoundsRaw = localStorage.getItem(DETERMINISTIC_REPAIR_ROUNDS_STORAGE_KEY)
        ?? localStorage.getItem(LEGACY_REVIEW_ITERATIONS_STORAGE_KEY);
      if (savedRepairRoundsRaw !== null && savedRepairRoundsRaw !== '') {
        const savedRepairRounds = Number(savedRepairRoundsRaw);
        if (Number.isFinite(savedRepairRounds)) {
          setMaxDeterministicRepairRounds(Math.min(Math.max(savedRepairRounds, 0), 3));
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
        createNewSession(res.data.sessionId);
        setInputValue('');
    } catch (error) {
        console.error('Failed to create new session:', error);
    }
  };

  const handleNewChat = async () => {
     finalizeNewChat();
  };

  const handleStopStream = () => {
    if (streamAbortRef.current) {
      streamAbortRef.current.abort();
      streamAbortRef.current = null;
    }
    aiCanvasMutationDiagramIdsRef.current.clear();
    setIsSending(false);
    setMessages(prev => [...prev, {
      id: Date.now().toString(),
      role: 'agent',
      content: '⚠️ Generation stopped.',
      timestamp: Date.now()
    }]);
  };

  const performSendMessage = async (
    displayContent: string,
    canvasContext: StructuredCanvasContext = {},
    options: SendContentOptions = {},
  ) => {
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

    // Freeze the composer selection for this message before any asynchronous request work starts.
    const turnAttachments = conversationAttachments.map(attachment => ({ ...attachment }));
    const userMsg: Message = {
      id: Date.now().toString(),
      role: 'user',
      content: displayContent,
      attachments: turnAttachments,
      timestamp: Date.now()
    };
    
    const agentMsgId = Date.now().toString() + '-agent';
    const useChinese = usesChinesePresentation(displayContent);
    const initialAgentMsg: Message = {
      id: agentMsgId,
      role: 'agent',
      content: '',
      reasoning: '',
      language: useChinese ? 'zh' : 'en',
      steps: [],
      events: [],
      timestamp: Date.now()
    };

    setMessages(prev => [...prev, userMsg, initialAgentMsg]);
    const sentUploadIds = turnAttachments.map(attachment => attachment.uploadId);
    setSentAttachmentUploadIds(previous => [...new Set([...previous, ...sentUploadIds])]);
    // Sending consumes the draft immediately; later upload polling must not recreate composer cards.
    setConversationAttachments([]);
    const activeAttachmentSessionId = sessionId.trim();
    if (activeAttachmentSessionId) {
      writeConversationAttachments(window.sessionStorage, activeAttachmentSessionId, []);
    }
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
      let activeStreamPhase = 'connecting';
      let activeRouteType: string | undefined;
      let activeSourceUse: string | undefined;
      let sourceRunId = '';
      let postDrawReviewPromise: Promise<void> | null = null;
      let lastStepSnapshot = '';

      // Track the current streamed draft. Each draw pass replaces the previous draft.
      let nodeCount = 0;
      let edgeCount = 0;
      let hasIncrementalContent = false;
      let finalXml = '';
      let agentTextContent = ''; // For non-drawio user-type responses
      let requestedMoreInfo = false;
      let receivedDrawioDone = false;
      let receivedVersionConflict = false;
      let receivedStreamError = false;
      let agentLoopVisualReviewObserved = false;
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
      const visualReviews: VisualReviewPresentation[] = [];
      const eventIndexByKey: Record<string, number> = {};
      let eventSequence = 0;

      // Helper to update steps
      const updateStep = (phaseStr: string, contentToAdd: string, isDone: boolean = false, replaceContent: boolean = false) => {
          const projected = projectUserExecutionStep({
            phase: phaseStr,
            routeType: activeRouteType,
            sourceUse: activeSourceUse,
            useChinese,
          });
          const projectedKey = projected.key;
          const stepIndex = accumulatedSteps.findIndex(s => (s.id || s.phase) === projectedKey);
          if (stepIndex >= 0) {
              accumulatedSteps[stepIndex].phase = projected.phase;
              accumulatedSteps[stepIndex].label = projected.label;
              if (contentToAdd) {
                  if (replaceContent) {
                      accumulatedSteps[stepIndex].content = contentToAdd + '\n';
                  } else if (!accumulatedSteps[stepIndex].content.includes(contentToAdd.trim())) {
                      accumulatedSteps[stepIndex].content += contentToAdd + '\n';
                  }
              }
              if (isDone) {
                  accumulatedSteps[stepIndex].status = 'done';
              } else if (projected.phase !== 'analysis' || accumulatedSteps[stepIndex].status !== 'done') {
                  // Retries and bounded repairs update their existing row instead of adding another step.
                  accumulatedSteps.forEach((step, index) => {
                    if (index !== stepIndex && step.status === 'running') step.status = 'done';
                  });
                  accumulatedSteps[stepIndex].status = 'running';
              }
          } else {
              // Mark previous running steps as done
              accumulatedSteps.forEach(s => { if (s.status === 'running') s.status = 'done'; });
              accumulatedSteps.push({
                  id: projectedKey,
                  phase: projected.phase,
                  label: projected.label,
                  content: contentToAdd ? contentToAdd + '\n' : '',
                  status: isDone ? 'done' : 'running'
              });
          }
      };

      const getVisibleStepDetail = (phaseStr: string, state?: 'loaded' | 'passed' | 'needs_attention') => {
        // Thinking shows observable progress only; model-produced status text stays out of the UI.
        if (state === 'loaded') {
          return useChinese
            ? `已将 ${nodeCount} 个节点、${edgeCount} 条连线加载到画布。`
            : `Loaded ${nodeCount} nodes and ${edgeCount} connectors onto the canvas.`;
        }
        if (state === 'passed') return useChinese ? '结构校验通过。' : 'Structural validation passed.';
        if (state === 'needs_attention') return useChinese ? '结构校验发现需要处理的问题。' : 'Structural validation found issues to address.';
        if (phaseStr === 'drawing' && (nodeCount > 0 || edgeCount > 0)) {
          return useChinese
            ? `正在构建画布：${nodeCount} 个节点、${edgeCount} 条连线。`
            : `Building the canvas: ${nodeCount} nodes and ${edgeCount} connectors.`;
        }
        return '';
      };

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

      const recordVisualReview = (review: VisualReviewPresentation) => {
        // A review round has one authoritative result; keep distinct post-repair checkpoints visible.
        const existingIndex = visualReviews.findIndex(item => item.stage === review.stage
          && item.visualRepairRound === review.visualRepairRound);
        if (existingIndex >= 0) visualReviews[existingIndex] = review;
        else visualReviews.push(review);
      };

      const markVisualRepairCompleted = (reviewRound: number) => {
        // A repair decision is only a proposal; drawio_done is the observable completion signal.
        const reviewIndex = visualReviews.findIndex(item => item.decision === 'REPAIR'
          && item.visualRepairRound === reviewRound);
        if (reviewIndex >= 0) {
          visualReviews[reviewIndex] = { ...visualReviews[reviewIndex], repairCompleted: true };
        }
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
          displayContent,
          visualReviews,
        );
        if (completionContent && !accumulatedContent.includes(completionContent)) {
          accumulatedContent += (accumulatedContent ? '\n\n' : '') + completionContent;
        }
        setMessages(prev => prev.map(m => (
          m.id === agentMsgId ? { ...m, content: accumulatedContent, steps: markStepsDone(m.steps) } : m
        )));
        return true;
      };

      const appendEmptyResponseMessage = () => {
        // A structured or transport error is already the terminal response shown to the user.
        if (emptyResponseMessageAdded || receivedStreamError || agentTextContent || hasIncrementalContent) return false;
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

      const diagramPrepared = await ensureConversationDiagramShell({
        diagramId,
        title: diagramTitle,
        canvasXml: canvasContext.canvasXml,
        canvasVersion: latestCanvasVersion(
          diagramId ? manualCanvasVersionsRef.current.get(diagramId) : undefined,
          activeSession?.canvasVersion,
        ),
        hasConversationMessages: true,
      });
      if (!diagramPrepared) {
        throw new Error('TURN_DIAGRAM_PREPARATION_FAILED');
      }

      const requestPayload = buildDrawioChatRequestPayload({
          agentId: selectedAgentId,
          userId: currentUser,
          sessionId: activeBackendSessionId,
          clientMessageId: userMsg.id,
          responseMessageId: agentMsgId,
          userMessage: options.requestContent || displayContent,
          diagramId,
          expectedVersion: latestCanvasVersion(
            diagramId ? manualCanvasVersionsRef.current.get(diagramId) : undefined,
            activeSession?.canvasVersion,
          ),
          expectedContentHash: activeSession?.canvasContentHash,
          canvasXml: canvasContext.canvasXml,
          canvasSummary: canvasContext.canvasSummary,
          canvasImageDataUrl: canvasContext.canvasImageDataUrl,
          canvasImageRendererVersion: canvasContext.canvasImageRendererVersion,
          modelCredentialId: activeModelConfig?.modelCredentialId || undefined,
          directClarifications: options.directClarifications,
          directConfirmationSourceVersionId: options.directConfirmationSourceVersionId,
          currentTurnAttachmentRefs: turnAttachments.map(attachment => attachment.uploadId),
          memoryChartbookId: filesChartbook?.chartbookId,
          selectedLibraryVersionIds: librarySelections.map(selection => selection.versionId),
          selectedCellIds: selectedCellsRef.current?.cellIds,
          selectionCanvasVersion: selectedCellsRef.current?.canvasVersion,
          selectionContentHash: selectedCellsRef.current?.contentHash,
          maxDeterministicRepairRounds,
          skills: pendingSkillsRef.current.length ? pendingSkillsRef.current : undefined,
          conversationMessages: messages,
      });
      type ReviewStreamOutcome = {
        decision?: string;
        visualReviewRunId?: string;
        repairRunId?: string;
        repairedCanvas?: {
          diagramId: string;
          version: number;
          contentHash: string;
          contentHashBeforeRepair: string;
          imageBeforeRepair: string;
          canvasXml: string;
          loadPromise: Promise<boolean>;
        };
      };

      type VisualReviewEvidenceBundle = {
        primaryImageDataUrl: string;
        primaryPageId?: string;
        primaryPageName: string;
        totalPageCount: number;
        truncatedPageCount: number;
        additionalAfterImages: CanvasVisualReviewEvidenceDTO[];
      };

      const nextReviewRequestId = () => (
        typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
          ? `review-${crypto.randomUUID()}`
          : `review-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`
      );

      const exportVisualReviewPage = async (
        targetDiagramId: string,
        pageId?: string,
        width?: string,
      ) => {
        const exported = await exportCoordinatorRef.current?.enqueue({
          purpose: 'visual-review-png',
          diagramId: targetDiagramId,
          sessionId: activeSession?.id || currentSessionId || '',
          format: 'png',
          options: buildVisualReviewExportRequest(pageId, width),
        });
        if (!exported || !isVisualReviewPngDataUrl(exported.data)) {
          throw new CanvasExportError('EXPORT_INVALID_PAYLOAD', 'Draw.io returned an invalid visual-review PNG.');
        }
        return exported.data as string;
      };

      const exportVisualReviewEvidence = async (
        targetDiagramId: string,
        canvasXml: string,
      ): Promise<VisualReviewEvidenceBundle> => {
        const counts = countDrawableCells(canvasXml);
        const plan = buildVisualReviewEvidencePlan({
          canvasXml,
          nodeCount: counts.nodes,
          edgeCount: counts.edges,
        });
        const pageImages: Array<{ pageId?: string; pageName: string; dataUrl: string }> = [];
        for (const page of plan.pages) {
          pageImages.push({
            ...page,
            dataUrl: await exportVisualReviewPage(targetDiagramId, page.pageId),
          });
        }
        const primary = pageImages[0];
        if (!primary) {
          throw new CanvasExportError('EXPORT_INVALID_PAYLOAD', 'No visual-review page was available.');
        }
        const additionalAfterImages: CanvasVisualReviewEvidenceDTO[] = pageImages.slice(1).map(page => ({
          role: 'PAGE_OVERVIEW',
          pageId: page.pageId,
          pageName: page.pageName,
          dataUrl: page.dataUrl,
        }));
        if (plan.detailTiles) {
          const detailSource = await exportVisualReviewPage(
            targetDiagramId,
            primary.pageId,
            VISUAL_REVIEW_DETAIL_WIDTH,
          );
          const tiles = await createVisualReviewDetailTiles(detailSource);
          tiles.forEach((dataUrl, index) => additionalAfterImages.push({
            role: 'DETAIL_TILE',
            pageId: primary.pageId,
            pageName: primary.pageName,
            tileIndex: index + 1,
            tileCount: tiles.length,
            dataUrl,
          }));
        }
        return {
          primaryImageDataUrl: primary.dataUrl,
          primaryPageId: primary.pageId,
          primaryPageName: primary.pageName,
          totalPageCount: plan.totalPageCount,
          truncatedPageCount: plan.truncatedPageCount,
          additionalAfterImages,
        };
      };

      const reviewEvidenceFields = (evidence: VisualReviewEvidenceBundle) => ({
        afterImageDataUrl: evidence.primaryImageDataUrl,
        afterImagePageId: evidence.primaryPageId,
        afterImagePageName: evidence.primaryPageName,
        totalPageCount: evidence.totalPageCount,
        truncatedPageCount: evidence.truncatedPageCount,
        additionalAfterImages: evidence.additionalAfterImages,
      });

      const executeVisualReview = (reviewRequest: ReturnType<typeof buildCanvasVisualReviewRequest>) => (
        new Promise<ReviewStreamOutcome>(resolve => {
          const outcome: ReviewStreamOutcome = {};
          const reviewKeySuffix = `${reviewRequest.stage}:${reviewRequest.visualRepairRound}`;
          const repairStepKey = `visual-repair:${reviewRequest.visualRepairRound + 1}`;
          const reviewStepLabel = visualReviewStageLabel(reviewRequest.stage, useChinese);
          let settled = false;
          let hasReviewPresentation = false;
          const finish = () => {
            if (settled) return;
            if (sourceRunId && outcome.repairRunId && outcome.repairedCanvas) {
              visualRepairProvenanceRef.current.set(outcome.repairedCanvas.diagramId, {
                sourceRunId,
                repairRunId: outcome.repairRunId,
                repairRound: reviewRequest.visualRepairRound + 1,
                repairedCanvasXml: outcome.repairedCanvas.canvasXml,
              });
            }
            settled = true;
            resolve(outcome);
          };
          const finishRepairWithoutSave = (detail: string) => {
            if (outcome.decision !== 'REPAIR') return;
            updateStep('visual_repair', detail, true, true);
            publishSteps();
            upsertRunEvent(repairStepKey, {
              phase: 'revising',
              title: 'Visual repair',
              detail,
              status: 'warning',
              tone: 'review',
            });
          };
          const showUnavailableReview = () => {
            // A later repair transport failure must not erase VLM findings already shown to the user.
            if (!shouldShowUnavailableReview(hasReviewPresentation)) return;
            const unavailableReview: VisualReviewPresentation = {
              stage: reviewRequest.stage,
              visualRepairRound: reviewRequest.visualRepairRound,
              decision: 'UNAVAILABLE',
              unavailableReason: 'REVIEW_REQUEST_FAILED',
            };
            recordVisualReview(unavailableReview);
            const detail = buildVisualReviewStepDetail({ ...unavailableReview, useChinese });
            updateStep('visual_review', detail, true, true);
            publishSteps();
            upsertRunEvent(`visual-review:${reviewKeySuffix}`, {
              phase: 'reviewing',
              title: reviewStepLabel,
              detail,
              status: 'warning',
              tone: 'review',
            });
          };

          void agentApi.visualReviewStream(
            reviewRequest,
            event => {
              const { chunk } = event;
              if (chunk.type === 'meta') {
                if (chunk.visualReviewRunId) outcome.visualReviewRunId = chunk.visualReviewRunId;
                if (chunk.runId && outcome.visualReviewRunId) outcome.repairRunId = chunk.runId;
                return;
              }
              if (chunk.type === 'review_started') {
                updateStep('visual_review', '', false, true);
                publishSteps();
                upsertRunEvent(`visual-review:${chunk.stage}:${chunk.visualRepairRound ?? reviewRequest.visualRepairRound}`, {
                  phase: 'reviewing',
                  title: visualReviewStageLabel(chunk.stage || reviewRequest.stage, useChinese),
                  detail: useChinese ? '正在检查当前版本的真实渲染画布。' : 'Inspecting the rendered canvas for this version.',
                  status: 'running',
                  tone: 'review',
                });
                return;
              }
              if (chunk.type === 'review_result') {
                hasReviewPresentation = true;
                outcome.decision = chunk.decision;
                const review: VisualReviewPresentation = {
                  stage: chunk.stage || reviewRequest.stage,
                  visualRepairRound: chunk.visualRepairRound ?? reviewRequest.visualRepairRound,
                  decision: chunk.decision,
                  summary: chunk.content,
                  issues: chunk.issues,
                  unavailableReason: visualReviewUnavailableReason(
                    chunk.available,
                    chunk.unavailableReason,
                    chunk.decision,
                  ),
                };
                recordVisualReview(review);
                if (chunk.decision === 'REPAIR' && !activeAiMutationDiagramId) {
                  activeAiMutationDiagramId = reviewRequest.diagramId;
                  beginAiCanvasMutationForDiagram(reviewRequest.diagramId);
                }
                const reviewDetail = buildVisualReviewStepDetail({ ...review, useChinese });
                updateStep('visual_review', reviewDetail, true, true);
                publishSteps();
                upsertRunEvent(`visual-review:${chunk.stage || reviewRequest.stage}:${chunk.visualRepairRound ?? reviewRequest.visualRepairRound}`, {
                  phase: 'reviewing',
                  title: visualReviewStageLabel(chunk.stage || reviewRequest.stage, useChinese),
                  detail: reviewDetail,
                  status: chunk.approved ? 'done' : 'warning',
                  tone: 'review',
                });
                if (chunk.decision === 'REPAIR') {
                  const repairStepLabel = visualReviewStageLabel('REPAIR', useChinese);
                  const repairDetail = buildVisualRepairStepDetail({ issues: chunk.issues, useChinese });
                  updateStep('visual_repair', repairDetail, false, true);
                  publishSteps();
                  upsertRunEvent(repairStepKey, {
                    phase: 'revising',
                    title: repairStepLabel,
                    detail: repairDetail,
                    status: 'running',
                    tone: 'review',
                  });
                }
                return;
              }
              if (chunk.type === 'review_stale') {
                hasReviewPresentation = true;
                outcome.decision = 'STALE';
                const staleReview: VisualReviewPresentation = {
                  stage: reviewRequest.stage,
                  visualRepairRound: reviewRequest.visualRepairRound,
                  decision: 'UNAVAILABLE',
                  stale: true,
                };
                recordVisualReview(staleReview);
                const staleDetail = buildVisualReviewStepDetail({ ...staleReview, useChinese });
                updateStep('visual_review', staleDetail, true, true);
                publishSteps();
                upsertRunEvent(`visual-review:${reviewKeySuffix}`, {
                  phase: 'reviewing',
                  title: reviewStepLabel,
                  detail: staleDetail,
                  status: 'warning',
                  tone: 'review',
                });
                return;
              }
              if (chunk.type === 'drawio_done' && Number.isFinite(chunk.version) && chunk.contentHash) {
                markVisualRepairCompleted(reviewRequest.visualRepairRound);
                const repairedDiagramId = chunk.diagramId || reviewRequest.diagramId;
                const loadPromise = waitForCanvasLoad(activeSession?.id || currentSessionId || '');
                applyFinalDiagramXml(chunk.content, chunk.mode);
                saveCurrentCanvasXml(chunk.content, {
                  diagramId: repairedDiagramId,
                  version: chunk.version,
                  contentHash: chunk.contentHash,
                });
                if (activeSession?.id) {
                  rememberManualCanvasVersion(activeSession.id, repairedDiagramId, chunk.version, chunk.contentHash);
                }
                outcome.repairedCanvas = {
                  diagramId: repairedDiagramId,
                  version: chunk.version as number,
                  contentHash: chunk.contentHash,
                  contentHashBeforeRepair: reviewRequest.expectedContentHash,
                  imageBeforeRepair: reviewRequest.afterImageDataUrl,
                  canvasXml: chunk.content,
                  loadPromise,
                };
                const repairCompletedDetail = useChinese
                  ? '已完成一轮局部视觉修复，并重新加载画布。'
                  : 'Completed one local visual repair and reloaded the canvas.';
                updateStep('visual_repair', repairCompletedDetail, true, true);
                publishSteps();
                upsertRunEvent(repairStepKey, {
                  phase: 'revising',
                  title: visualReviewStageLabel('REPAIR', useChinese),
                  detail: repairCompletedDetail,
                  status: 'done',
                  tone: 'review',
                });
                return;
              }
              if (chunk.type === 'mutation_rejected') {
                finishRepairWithoutSave(useChinese
                  ? `视觉修复未通过安全检查（${chunk.reason || chunk.status}），已保留原画布。`
                  : `The visual repair failed the safety check (${chunk.reason || chunk.status}); the original canvas was kept.`);
                return;
              }
              if (chunk.type === 'version_conflict') {
                finishRepairWithoutSave(useChinese
                  ? '视觉修复期间画布版本已变化，已跳过本次修复。'
                  : 'The canvas changed during visual repair, so this repair was skipped.');
                return;
              }
              if (chunk.type === 'error') {
                finishRepairWithoutSave(useChinese
                  ? '视觉修复暂时不可用，当前画布已保留。'
                  : 'Visual repair is temporarily unavailable; the current canvas was kept.');
                showUnavailableReview();
              }
            },
            () => {
              finishRepairWithoutSave(useChinese
                ? '视觉修复请求失败，当前画布已保留。'
                : 'The visual repair request failed; the current canvas was kept.');
              showUnavailableReview();
              finish();
            },
            finish,
          ).then(controller => {
            streamAbortRef.current = controller;
          });
        })
      );

      const runPostDrawVisualReview = async ({
        finalDiagramId,
        finalVersion,
        finalContentHash,
        finalCanvasXml,
        canvasLoaded,
      }: {
        finalDiagramId: string;
        finalVersion: number;
        finalContentHash: string;
        finalCanvasXml: string;
        canvasLoaded: Promise<boolean>;
      }) => {
        if (!await canvasLoaded || currentSessionRef.current !== activeSession?.id) return;
        if (!sourceRunId) return;

        const collectEvidence = async (
          diagramId: string,
          canvasXml: string,
          stage: VisualReviewPresentation['stage'],
          repairRound: number,
        ) => {
          const stepKey = `visual-evidence:${repairRound}`;
          const label = useChinese ? '准备视觉证据' : 'Prepare visual evidence';
          updateStep('visual_evidence', '', false, true);
          publishSteps();
          try {
            const evidence = await exportVisualReviewEvidence(diagramId, canvasXml);
            const detailCount = evidence.additionalAfterImages.filter(item => item.role === 'DETAIL_TILE').length;
            const reviewedPageCount = evidence.totalPageCount - evidence.truncatedPageCount;
            const detail = useChinese
              ? `已准备 ${reviewedPageCount} 页画布预览${detailCount ? `和 ${detailCount} 张局部图` : ''}，正在检查布局与连线。`
              : `Prepared ${reviewedPageCount} canvas preview page${reviewedPageCount === 1 ? '' : 's'}${detailCount ? ` and ${detailCount} detail image${detailCount === 1 ? '' : 's'}` : ''} to check layout and connectors.`;
            updateStep('visual_evidence', detail, true, true);
            publishSteps();
            upsertRunEvent(stepKey, {
              phase: 'reviewing',
              title: label,
              detail,
              status: evidence.truncatedPageCount > 0 ? 'warning' : 'done',
              tone: 'review',
            });
            return evidence;
          } catch (error) {
            const review: VisualReviewPresentation = {
              stage,
              visualRepairRound: repairRound,
              decision: 'UNAVAILABLE',
              unavailableReason: 'EXPORT_FAILED',
            };
            recordVisualReview(review);
            const detail = buildVisualReviewStepDetail({ ...review, useChinese });
            updateStep('visual_evidence', detail, true, true);
            publishSteps();
            upsertRunEvent(stepKey, {
              phase: 'reviewing',
              title: useChinese ? '视觉证据导出' : 'Visual evidence export',
              detail,
              status: 'warning',
              tone: 'review',
            });
            console.warn('Visual review evidence export failed:',
              error instanceof CanvasExportError ? error.code : 'UNKNOWN');
            return undefined;
          }
        };

        let evidence = await collectEvidence(
          finalDiagramId,
          finalCanvasXml,
          'POST_MUTATION',
          0,
        );
        if (!evidence) return;

        let reviewRequest = buildCanvasVisualReviewRequest({
          userId: currentUser,
          agentId: selectedAgentId,
          sessionId: activeBackendSessionId,
          requestId: nextReviewRequestId(),
          sourceRunId,
          parentRunId: sourceRunId,
          visualRepairRound: 0,
          diagramId: finalDiagramId,
          expectedVersion: finalVersion,
          beforeContentHash: activeSession?.canvasContentHash,
          expectedContentHash: finalContentHash,
          originalUserTask: displayContent,
          stage: 'POST_MUTATION',
          beforeImageDataUrl: canvasContext.canvasImageDataUrl,
          ...reviewEvidenceFields(evidence),
          modelCredentialId: activeModelConfig?.modelCredentialId,
        });
        let reviewed = await executeVisualReview(reviewRequest);
        while (reviewed.repairedCanvas && shouldReviewSavedRepair({
          decision: reviewed.decision,
          reviewedVersion: reviewRequest.expectedVersion,
          reviewedContentHash: reviewRequest.expectedContentHash,
          version: reviewed.repairedCanvas.version,
          contentHash: reviewed.repairedCanvas.contentHash,
        })) {
          const repaired = reviewed.repairedCanvas;
          const completedRepairRounds = reviewRequest.visualRepairRound + 1;
          const nextStage = nextVisualReviewStage(completedRepairRounds);
          // A persisted Drawer run id is required to prove each follow-up review's lineage.
          if (!nextStage || !reviewed.repairRunId) return;
          if (!await repaired.loadPromise || currentSessionRef.current !== activeSession?.id) return;
          evidence = await collectEvidence(
            repaired.diagramId,
            repaired.canvasXml,
            nextStage,
            completedRepairRounds,
          );
          if (!evidence) return;
          reviewRequest = buildCanvasVisualReviewRequest({
            userId: currentUser,
            agentId: selectedAgentId,
            sessionId: activeBackendSessionId,
            requestId: nextReviewRequestId(),
            sourceRunId,
            parentRunId: reviewed.repairRunId,
            visualRepairRound: completedRepairRounds,
            diagramId: repaired.diagramId,
            expectedVersion: repaired.version,
            beforeContentHash: repaired.contentHashBeforeRepair,
            expectedContentHash: repaired.contentHash,
            originalUserTask: displayContent,
            stage: nextStage,
            beforeImageDataUrl: repaired.imageBeforeRepair,
            ...reviewEvidenceFields(evidence),
            modelCredentialId: activeModelConfig?.modelCredentialId,
          });
          reviewed = await executeVisualReview(reviewRequest);
        }
      };

      if (demoQuotaState.visible) {
        // Keep the visible counter in step with the backend; the refresh below reconciles failures.
        setCurrentAccount(prev => applyDemoQuotaConsumption(prev));
      }

      const controller = await agentApi.chatStream(
        requestPayload,
        // onEvent
        (event: StreamEvent) => {
          const { phase, chunk } = event;
          if (chunk.type === 'meta') {
            // Correlation metadata is for diagnostics and should not create a visible run step.
            sourceRunId = chunk.runId || sourceRunId;
            return;
          }
          if (chunk.type === 'route') {
            // The router has selected the work path, so label the next visible steps accordingly.
            activeRouteType = chunk.routeType;
            activeSourceUse = chunk.sourceUse;
            setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, routeType: activeRouteType } : m));
            const routeDetail = buildRouteStepDetail({
              routeType: chunk.routeType,
              diagramType: chunk.diagramType,
              skillName: chunk.skillName,
              sourceUse: chunk.sourceUse,
              useChinese,
            });
            const contextReceipts = buildContextReceipts({
              location: filesChartbook
                ? { kind: 'CHARTBOOK', label: filesChartbook.name }
                : { kind: 'STANDALONE' },
              attachments: [
                ...turnAttachments.map(attachment => ({
                  label: attachment.fileName,
                  state: attachment.state,
                })),
                ...librarySelections.map(selection => ({
                  label: selection.displayName,
                  state: 'READY',
                })),
              ],
              sourceUse: chunk.sourceUse,
              useChinese,
            });
            updateStep('analyzing', routeDetail, true, true);
            publishSteps();
            setMessages(prev => prev.map(m => m.id === agentMsgId
              ? { ...m, contextReceipts }
              : m));
            upsertRunEvent('route', {
              phase: 'analyzing',
              title: 'Intent Router',
              detail: routeDetail,
              status: 'done',
              tone: 'analysis',
            });
            return;
          }
          // Update phase display
          const currentPhaseLabel = projectUserExecutionStep({
            phase,
            routeType: activeRouteType,
            sourceUse: activeSourceUse,
            useChinese,
          }).label;
          let currentStep: { key: string; label: string } = { key: phase, label: currentPhaseLabel };

          // A drawing turn may send its final assistant text in the answer phase after the canvas
          // is complete; that terminal copy must not relabel the generation step as an answer task.
          const shouldTrackPhase = phase !== 'done'
            && phase !== 'error'
            && !(phase === 'answer' && receivedDrawioDone);
          if (shouldTrackPhase) {
            const previousPhase = activeStreamPhase;
            currentStep = projectUserExecutionStep({
              phase,
              routeType: activeRouteType,
              sourceUse: activeSourceUse,
              useChinese,
            });
            updateStep(phase, getVisibleStepDetail(phase), false, true);
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
              publishSteps();
            } else if (phase !== previousPhase) {
              publishSteps();
            }
          }

          switch (chunk.type) {
            case 'drawio_preview': {
              if (requestedMoreInfo) break;

              if (chunk.reset) {
                // Every Agent mutation streams a complete working copy, so replay it from a clean
                // preview state without treating the draft as a committed drawio_done result.
                accumulatedNodes = [];
                accumulatedEdges = [];
                previewSkeletonXml = '';
                nodeCount = 0;
                edgeCount = 0;
                receivedDrawioDone = false;
              }
              const previewCounts = countDrawableCells(chunk.content);
              if (previewCounts.nodes === 0 && previewCounts.edges === 0) break;

              hasIncrementalContent = true;
              previewSkeletonXml = chunk.content;
              nodeCount = previewCounts.nodes;
              edgeCount = previewCounts.edges;
              updateStep('drawing', getVisibleStepDetail('drawing'), false, true);
              upsertRunEvent('drawio:stream', {
                phase: 'drawing',
                title: 'Update canvas preview',
                detail: 'Loaded preview skeleton',
                status: 'running',
                tone: 'drawing',
                nodes: nodeCount,
                edges: edgeCount,
              });
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
                  updateStep('drawing', getVisibleStepDetail('drawing'), false, true);
                  upsertRunEvent('drawio:stream', {
                    phase: 'drawing',
                    title: 'Update canvas preview',
                    detail: `Added node #${nodeCount}: ${chunk.label}`,
                    status: 'running',
                    tone: 'drawing',
                    nodes: nodeCount,
                    edges: edgeCount,
                  });
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
                  updateStep('drawing', getVisibleStepDetail('drawing'), false, true);
                  upsertRunEvent('drawio:stream', {
                    phase: 'drawing',
                    title: 'Update canvas preview',
                    detail: `Added edge #${edgeCount}: ${chunk.label || chunk.source + '→' + chunk.target}`,
                    status: 'running',
                    tone: 'drawing',
                    nodes: nodeCount,
                    edges: edgeCount,
                  });
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
              updateStep(phase, getVisibleStepDetail(phase, 'loaded'), true, true);
              publishSteps();

              // Reviewer may send the polished final diagram after the drawing stage.
              const isFinalStage = phase === 'drawing' || phase === 'reviewing' || phase === 'done';
              
              if (isFinalStage) {
                const shouldReviewFinalCanvas = Boolean(chunk.diagramId || diagramId)
                  && !postDrawReviewPromise
                  // Agentic Plain already reviewed the same attempt-scoped draft before commit.
                  && !agentLoopVisualReviewObserved
                  && canStartPostMutationReview({
                    version: chunk.version,
                    contentHash: chunk.contentHash,
                  });
                const canvasLoaded = shouldReviewFinalCanvas && activeSession?.id
                  ? waitForCanvasLoad(activeSession.id)
                  : null;
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
                void assignPendingChartbookAfterSave(persistedDiagramId || '');
                queueDiagramThumbnailExport(persistedDiagramId, finalXml);
                if (!diagramTitlePersisted) {
                  diagramTitlePersisted = true;
                  persistDiagramTitle(chunk.diagramId || diagramId, diagramTitle);
                }
                if (canvasLoaded && chunk.version !== undefined && chunk.contentHash) {
                  // The initial mutation is already persisted. Release autosave ownership while the
                  // VLM thinks so a manual edit can advance version/hash and make its result stale.
                  if (activeAiMutationDiagramId) {
                    finishAiCanvasMutationForDiagram(activeAiMutationDiagramId);
                    activeAiMutationDiagramId = undefined;
                  }
                  postDrawReviewPromise = runPostDrawVisualReview({
                    finalDiagramId: chunk.diagramId || diagramId || '',
                    finalVersion: chunk.version,
                    finalContentHash: chunk.contentHash,
                    finalCanvasXml: finalXml,
                    canvasLoaded,
                  }).catch(error => {
                    console.warn('Post-draw visual review failed open:', error);
                    const hasPostMutationReview = visualReviews.some(review => review.stage === 'POST_MUTATION');
                    if (shouldShowUnavailableReview(hasPostMutationReview)) {
                      recordVisualReview({ stage: 'POST_MUTATION', decision: 'UNAVAILABLE' });
                    }
                  });
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
                      } catch {
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
              // Raw model status may include process text, so it is intentionally not rendered in Thinking.
              break;
            }

            case 'agent_progress': {
              const elapsed = typeof chunk.latencyMs === 'number' && chunk.latencyMs > 0
                ? (chunk.latencyMs >= 1000
                  ? `${(chunk.latencyMs / 1000).toFixed(1)} s`
                  : `${chunk.latencyMs} ms`)
                : '';
              const step = chunk.step || 0;
              const tool = chunk.tool || chunk.action || '';
              if (chunk.stage === 'visual_review_completed') {
                agentLoopVisualReviewObserved = true;
              }
              let detail = '';
              if (chunk.stage === 'agent_started') {
                detail = useChinese ? 'Agent Loop 已启动。' : 'Agent loop started.';
              } else if (chunk.stage === 'skills_loaded') {
                const count = chunk.skillCount || 0;
                detail = useChinese
                  ? `已加载 ${count} 个绘图 Skill。`
                  : `Loaded ${count} diagram skill${count === 1 ? '' : 's'}.`;
              } else if (chunk.stage === 'decision_started') {
                detail = useChinese
                  ? `正在决定第 ${step} 步操作。`
                  : `Choosing action for step ${step}.`;
              } else if (chunk.stage === 'decision_completed') {
                detail = useChinese
                  ? `第 ${step} 步选择了 ${tool || '提交候选图'}${elapsed ? `（${elapsed}）` : ''}。`
                  : `Step ${step} selected ${tool || 'candidate submission'}${elapsed ? ` (${elapsed})` : ''}.`;
              } else if (chunk.stage === 'tool_started') {
                detail = useChinese
                  ? `正在执行 ${tool}。`
                  : `Running ${tool}.`;
              } else if (chunk.stage === 'visual_review_started') {
                detail = useChinese
                  ? 'Visual Review Agent 正在审查当前草稿。'
                  : 'Visual Review Agent is reviewing the current draft.';
              } else if (chunk.stage === 'visual_review_completed') {
                const issues = chunk.issueCount || 0;
                detail = useChinese
                  ? `视觉审查已完成${elapsed ? `（${elapsed}）` : ''}，结论为 ${chunk.outcome || 'UNAVAILABLE'}，发现 ${issues} 个问题。`
                  : `Visual review completed${elapsed ? ` (${elapsed})` : ''}; decision ${chunk.outcome || 'UNAVAILABLE'}, ${issues} issue${issues === 1 ? '' : 's'}.`;
              } else if (chunk.stage === 'tool_completed') {
                if (tool === 'inspect_draft') {
                  detail = useChinese
                    ? `草稿读取完成${elapsed ? `（${elapsed}）` : ''}。`
                    : `Draft read completed${elapsed ? ` (${elapsed})` : ''}.`;
                } else {
                  detail = useChinese
                    ? `${tool} 已完成${elapsed ? `（${elapsed}）` : ''}。`
                    : `${tool} completed${elapsed ? ` (${elapsed})` : ''}.`;
                }
              } else if (chunk.stage === 'candidate_submitted') {
                detail = useChinese
                  ? '候选草稿已完成，正在提交正式画布。'
                  : 'Candidate draft is ready and is being committed.';
              }
              if (!detail) break;

              const isDone = chunk.stage === 'decision_completed'
                || chunk.stage === 'tool_completed'
                || chunk.stage === 'visual_review_completed'
                || chunk.stage === 'candidate_submitted';
              updateStep(phase, detail, isDone, true);
              upsertRunEvent(`agent:${chunk.stage}:${step}:${tool}`, {
                phase,
                title: chunk.stage,
                detail,
                status: isDone ? 'done' : 'running',
                tone: chunk.stage.includes('tool') || chunk.stage.includes('visual_review')
                  ? 'tool'
                  : 'analysis',
                tool: chunk.tool,
              });
              publishSteps();
              break;
            }

            case 'evidence_progress': {
              const progressCompleted = Math.max(0, chunk.completed);
              const progressTotal = Math.max(0, chunk.total);
              const progressDone = progressTotal > 0 && progressCompleted >= progressTotal;
              const detail = useChinese
                ? progressDone
                  ? `所需内容已准备完成（${progressCompleted}/${progressTotal}）。`
                  : `正在准备所需内容（${progressCompleted}/${progressTotal}）。`
                : progressDone
                  ? `Required inputs are ready (${progressCompleted}/${progressTotal}).`
                  : `Preparing required inputs (${progressCompleted}/${progressTotal}).`;
              updateStep('retrieval', detail, progressDone, true);
              publishSteps();
              upsertRunEvent(`retrieval:${chunk.stage}`, {
                phase: 'retrieval',
                title: 'Prepare evidence',
                detail,
                status: chunk.total > 0 && chunk.completed >= chunk.total ? 'done' : 'running',
                tone: 'analysis',
              });
              break;
            }

            case 'direct_confirmation_required': {
              const reasons = Array.from(new Set((chunk.reasons || []).filter(Boolean))).slice(0, 5);
              const issueByReason = new Map((chunk.issues || []).map(issue => [
                issue.reasonCode,
                issue,
              ]));
              if (reasons.length > 0 && chunk.sourceVersionId) {
                setDirectConfirmation({
                  issues: reasons.map(reasonCode => (
                    issueByReason.get(reasonCode) || { reasonCode }
                  )),
                  sourceVersionId: chunk.sourceVersionId,
                  originalPrompt: options.requestContent || displayContent,
                  selections: {},
                });
              }
              const clarificationContent = normalizeAgentDisplayContent(chunk.content || '');
              if (clarificationContent) {
                accumulatedContent += (accumulatedContent ? '\n\n' : '') + clarificationContent;
                setMessages(prev => prev.map(m => m.id === agentMsgId
                  ? { ...m, content: accumulatedContent, steps: [...accumulatedSteps] }
                  : m));
              }
              upsertRunEvent('direct-confirmation', {
                phase: 'drawing',
                title: '等待图片确认',
                detail: clarificationContent,
                status: 'warning',
                tone: 'validation',
              });
              break;
            }

            case 'source_wait_started':
            case 'source_not_ready':
            case 'source_clarification':
            case 'claim_clarification':
            case 'degraded':
            case 'stale_canvas_selection':
            case 'grounding_rejected': {
              if (chunk.type === 'stale_canvas_selection') {
                // A stale tuple cannot be highlighted or reused; wait for a fresh bridge selection.
                selectedCellsRef.current = null;
                setTargetClarification(null);
              }
              const displayContent = normalizeAgentDisplayContent(chunk.content || '');
              if (displayContent) {
                agentTextContent += displayContent;
                accumulatedContent += (accumulatedContent ? '\n\n' : '') + displayContent;
                setMessages(prev => prev.map(m => m.id === agentMsgId
                  ? { ...m, content: accumulatedContent, steps: [...accumulatedSteps] }
                  : m));
              }
              upsertRunEvent(`retrieval:${chunk.type}`, {
                phase: 'retrieval',
                title: chunk.type,
                detail: displayContent,
                status: chunk.type === 'source_wait_started' ? 'running' : 'warning',
                tone: 'analysis',
              });
              break;
            }

            case 'target_clarification': {
              const candidates = chunk.candidates || [];
              setTargetClarification({
                candidates,
                canvasVersion: chunk.canvasVersion,
                contentHash: chunk.contentHash,
              });
              const highlightIds = candidates.map(candidate => candidate.cellId).filter(Boolean);
              if (highlightIds.length > 0) {
                drawioRef.current?.highlightCells(highlightIds, chunk.canvasVersion, chunk.contentHash);
              }
              const displayContent = normalizeAgentDisplayContent(chunk.content || '');
              accumulatedContent += (accumulatedContent ? '\n\n' : '') + displayContent;
              setMessages(prev => prev.map(m => m.id === agentMsgId
                ? { ...m, content: accumulatedContent, steps: [...accumulatedSteps] }
                : m));
              break;
            }

            case 'evidence_answer': {
              accumulatedContent = normalizeAgentDisplayContent(chunk.content || '');
              agentTextContent = accumulatedContent;
              setMessages(prev => prev.map(m => m.id === agentMsgId
                ? {
                    ...m,
                    id: chunk.messageId || m.id,
                    content: accumulatedContent,
                    evidenceSources: chunk.sources || [],
                    evidenceClaims: chunk.claims || [],
                    citationReceipt: buildCitationReceipt(
                      new Set((chunk.claims || []).flatMap(claim => claim.citationKeys || [])).size,
                      useChinese,
                    ),
                    steps: markStepsDone(m.steps),
                  }
                : m));
              break;
            }

            case 'review_result': {
              const review: VisualReviewPresentation = {
                stage: chunk.stage || 'CURRENT_CANVAS',
                decision: chunk.decision,
                summary: chunk.content,
                issues: chunk.issues,
              };
              recordVisualReview(review);
              const reviewDetail = buildVisualReviewStepDetail({ ...review, useChinese });
              upsertRunEvent('review:result', {
                phase: 'reviewing',
                title: 'review_result',
                detail: reviewDetail,
                status: chunk.approved ? 'done' : 'warning',
                tone: 'review',
                nodes: nodeCount,
                edges: edgeCount,
              });
              updateStep('reviewing', reviewDetail, true, true);
              publishSteps();
              break;
            }

            case 'validation_result': {
              const validationStatus = getValidationStatus(chunk);
              const validationDetail = validationStatus === 'done'
                ? (useChinese ? '图表结构检查通过。' : 'Diagram structure check passed.')
                : (useChinese ? '图表结构检查发现需要注意的问题。' : 'The diagram structure check found issues that need attention.');
              updateStep('reviewing', validationDetail, true, true);
              publishSteps();
              upsertRunEvent('validation:result', {
                phase: 'reviewing',
                title: 'validate_diagram',
                detail: validationDetail,
                status: validationStatus,
                tone: 'validation',
                tool: 'validate_diagram',
                nodes: nodeCount,
                edges: edgeCount,
              });
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
              setMessages(prev => prev.map(m => (
                m.id === agentMsgId ? { ...m, content: accumulatedContent, steps: [...accumulatedSteps] } : m
              )));
              break;
            }

            case 'mutation_rejected': {
              const rollbackXml = chunk.content?.trim();
              if (rollbackXml && currentSessionId === currentSessionRef.current) {
                applyFinalDiagramXml(rollbackXml, 'full');
                saveCurrentCanvasXml(rollbackXml, { diagramId: chunk.diagramId || diagramId });
              }
              const rejectionMessage = useChinese
                ? `画布修改未通过安全检查（${chunk.reason || chunk.status}），已恢复原图。`
                : `The canvas change failed the safety check (${chunk.reason || chunk.status}) and was rolled back.`;
              upsertRunEvent('canvas:mutation_rejected', {
                phase: 'error',
                title: 'Canvas change rejected',
                detail: rejectionMessage,
                status: 'error',
                tone: 'review',
              });
              accumulatedContent += (accumulatedContent ? '\n\n' : '') + `❌ ${rejectionMessage}`;
              setMessages(prev => prev.map(m => (
                m.id === agentMsgId ? { ...m, content: accumulatedContent, steps: [...accumulatedSteps] } : m
              )));
              break;
            }

            case 'token': {
              // Keep plain text only as a final-response fallback; it never appears in the Thinking UI.
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
	                  }
              }
              break;
            }

            case 'error': {
              receivedStreamError = true;
              const isQuotaError = isDemoQuotaErrorCode(chunk.code);
              const errorContent = isQuotaError
                ? quotaExhaustedMessageForCode(chunk.code)
                : streamErrorMessage(chunk.code || chunk.content, useChinese);
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
              if (postDrawReviewPromise) {
                break;
              }
              accumulatedSteps.forEach(s => { s.status = 'done'; });
              markRunEventsDone();
              if (receivedVersionConflict) {
                setMessages(prev => prev.map(m => (
                  m.id === agentMsgId ? { ...m, steps: [...accumulatedSteps] } : m
                )));
              } else if (!appendCompletionMessage() && !appendEmptyResponseMessage()) {
                setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, steps: [...accumulatedSteps] } : m));
              }
              break;
            }
          }
        },
        // onError
        (error: Error) => {
          receivedStreamError = true;
          if (activeAiMutationDiagramId) {
            finishAiCanvasMutationForDiagram(activeAiMutationDiagramId);
          }
          console.error('Stream error:', error);
          markRunEventsDone();
          
          // Only show error message if we didn't receive any content and it's not an AbortError
          if (error.name !== 'AbortError' && !hasIncrementalContent && !agentTextContent && nodeCount === 0) {
              accumulatedContent += (accumulatedContent ? '\n\n' : '') + `❌ ${streamErrorMessage(error, useChinese)}`;
              setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, content: accumulatedContent, steps: m.steps?.map(s => ({...s, status: 'done'})) } : m));
          } else {
              // If we already had content, just mark steps as done gracefully
              setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, steps: m.steps?.map(s => ({...s, status: 'done'})) } : m));
          }
          
          setIsSending(false);
          void refreshCurrentAccount();
        },
        // onComplete
        () => {
          const finishFullRun = () => {
            if (activeAiMutationDiagramId) {
              finishAiCanvasMutationForDiagram(activeAiMutationDiagramId);
            }
            setIsSending(false);
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
          };
          if (postDrawReviewPromise) {
            void postDrawReviewPromise.finally(finishFullRun);
          } else {
            finishFullRun();
          }
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
        content: streamErrorMessage(error, useChinese),
        timestamp: Date.now()
      }]);
      setIsSending(false);
    }
  };

  const sendContent = async (content: string, options: SendContentOptions = {}) => {
    if (!content.trim() || isSending) return;
    const activeSession = sessionsRef.current.find(session => session.id === currentSessionRef.current);
    if (!isDiagramRouteReady(restoreDiagramId, activeSession?.diagramId)) {
      // Never fall back to another local session while a URL-targeted diagram is unavailable.
      return;
    }
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

    if (!drawioRef.current || !isDrawIoReady || !activeSession) {
      await performSendMessage(content, {}, options);
      return;
    }

    const diagramId = activeSession.diagramId || makeLocalDiagramId(activeSession.id);
    let canvasContext = buildStructuredCanvasContext(activeSession.drawIoXml || EMPTY_DRAWIO_XML);
    try {
      const xmlExport = await exportCoordinatorRef.current?.enqueue({
        purpose: 'chat-xml',
        diagramId,
        sessionId: activeSession.id,
        format: 'xmlsvg',
        options: { format: 'xmlsvg' },
      });
      if (!xmlExport || xmlExport.sessionId !== currentSessionRef.current) {
        setIsSending(false);
        return;
      }

      const xml = chooseUsableCanvasXml(xmlExport, activeSession.drawIoXml);
      canvasContext = buildStructuredCanvasContext(xml);
      saveCurrentCanvasXml(xml);

      if (hasDrawableCells(xml)) {
        try {
          const pngExport = await exportCoordinatorRef.current?.enqueue({
            purpose: 'visual-review-png',
            diagramId,
            sessionId: activeSession.id,
            format: 'png',
            options: buildVisualReviewExportRequest(),
          });
          if (pngExport && pngExport.sessionId === currentSessionRef.current
              && isVisualReviewPngDataUrl(pngExport.data)) {
            canvasContext = {
              ...canvasContext,
              canvasImageDataUrl: pngExport.data,
              canvasImageRendererVersion: VISUAL_REVIEW_RENDERER_VERSION,
            };
          }
        } catch (error) {
          if (error instanceof CanvasExportError && error.code === 'EXPORT_SCOPE_CHANGED') {
            setIsSending(false);
            return;
          }
          // Mutations remain fail-open; review-only receives an explicit unavailable result from the backend.
          console.warn('Visual review screenshot export failed:', error);
        }
      }
    } catch (error) {
      if (error instanceof CanvasExportError && error.code === 'EXPORT_SCOPE_CHANGED') {
        setIsSending(false);
        return;
      }
      // XML export failure should not prevent the user from sending a normal mutation request.
      console.warn('Canvas context export failed; using the latest stored canvas:', error);
    }

    await performSendMessage(content, canvasContext, options);
  };

  const handleDirectConfirmation = () => {
    if (!directConfirmation || isSending) return;
    const clarifications = buildDirectClarifications(
      directConfirmation.issues,
      directConfirmation.selections,
    );
    if (!clarifications) return;
    const originalPrompt = directConfirmation.originalPrompt;
    setDirectConfirmation(null);
    void sendContent('已确认图片中的不确定项，请继续转换。', {
      requestContent: originalPrompt,
      directClarifications: clarifications,
      directConfirmationSourceVersionId: directConfirmation.sourceVersionId,
    });
  };

  const initializeAttachmentSession = async () => {
    if (sessionId) return sessionId;
    if (!selectedAgentId || !currentUser) return null;
    try {
      const created = await agentApi.createSession(selectedAgentId, currentUser);
      setSessionId(created.data.sessionId);
      return created.data.sessionId;
    } catch (error) {
      setMessages(prev => [...prev, {
        id: `${Date.now()}-attachment-session`,
        role: 'agent',
        content: error instanceof Error ? `无法创建附件会话：${error.message}` : '无法创建附件会话，请重试。',
        timestamp: Date.now(),
      }]);
      return null;
    }
  };

  const attachLibrarySelection = async (selection: ConversationLibrarySelection) => {
    const librarySessionId = await initializeAttachmentSession();
    if (!librarySessionId) throw new Error('无法创建附件会话，请重试。');
    const currentSelections = librarySelectionSessionLoaded === librarySessionId
      ? librarySelections
      : readConversationLibrarySelections(window.localStorage, librarySessionId);
    if (currentSelections.some(item => item.versionId === selection.versionId)) return;
    if (currentSelections.length >= MAX_CONVERSATION_LIBRARY_SELECTIONS) {
      throw new Error(`每个对话最多可选择 ${MAX_CONVERSATION_LIBRARY_SELECTIONS} 个资料库文件。`);
    }
    const nextSelections = [...currentSelections, selection];
    // Persist immediately so a newly created session cannot race with its session-load effect.
    writeConversationLibrarySelections(window.localStorage, librarySessionId, nextSelections);
    setLibrarySelections(nextSelections);
    setLibrarySelectionSessionLoaded(librarySessionId);
  };

  const removeLibrarySelection = (versionId: string) => {
    setLibrarySelections(current => current.filter(selection => selection.versionId !== versionId));
  };

  const handleSendMessage = async () => {
    if (hasProcessingAttachments) return;
    const content = inputValue;
    // Capture user-picked skills for this message, then clear the chips.
    pendingSkillsRef.current = [...selectedSkills];
    setInputValue('');
    setSelectedSkills([]);
    setSlashOpen(false);
    setDirectConfirmation(null);
    // Reset textarea height
    const textarea = promptInputRef.current;
    if (textarea) textarea.style.height = `${COMPOSER_TEXTAREA_MIN_HEIGHT_PX}px`;
    sendContent(content);
  };

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
  const filesPanelGroups = buildFilesPanelGroups({
    hasChartbook: filesChartbook !== null,
    conversationFiles,
    chartbookFiles,
  });
  const runFileAction = async (file: MaterialCatalogCard, action: () => Promise<unknown>) => {
    setBusyFileId(file.materialId);
    setFilesError('');
    try {
      await action();
      await refreshFilesPanel();
    } catch (error) {
      console.warn('Files action failed:', error);
      setFilesError('File action failed. Please try again.');
    } finally {
      setBusyFileId('');
    }
  };
  const openFileUrl = (url: string) => {
    // The API response controls Content-Disposition; storage identities never reach the browser.
    window.open(url, '_blank', 'noopener,noreferrer');
  };
  const historyEntries = buildDiagramHistoryEntries(historyDiagrams);
  const activeCanvasSession = sessions.find(session => session.id === currentSessionId);
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
          onClick={handleBackToSourcePage}
          aria-label="Back to previous page"
          className="relative grid h-10 w-10 place-items-center overflow-hidden rounded-lg bg-zinc-700 shadow-sm sm:h-9 sm:w-9"
          title="Back to previous page"
        >
          {/* Match the shared app logo used on the home and auth pages. */}
          <Image src="/brand/freedraw-app-icon-brush.png" alt="" fill sizes="36px" className="object-cover" priority />
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
          onClick={() => {
            setIsSidebarOpen(prev => !prev);
            setIsFilesPanelOpen(false);
          }}
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
          onClick={() => {
            setIsFilesPanelOpen(prev => !prev);
            setIsSidebarOpen(false);
          }}
          className={`grid h-10 w-10 place-items-center rounded-lg border transition sm:h-9 sm:w-9 ${
            isFilesPanelOpen
              ? 'border-stone-300 bg-white text-zinc-800 shadow-sm'
              : 'border-transparent text-zinc-500 hover:bg-white hover:text-zinc-800 hover:shadow-sm'
          }`}
          title="Files"
        >
          <svg aria-hidden="true" viewBox="0 0 24 24" className="h-5 w-5" fill="none"
            stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
            <path d="M4 4h6l2 2h8v14H4z" />
          </svg>
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

      {isFilesPanelOpen && (
        <FilesPanel
          groups={filesPanelGroups}
          uploads={conversationAttachments}
          chartbookName={filesChartbook?.name}
          loading={isFilesLoading}
          error={filesError}
          disabled={isSending || !selectedAgentId || !isRequestedDiagramReady}
          busyMaterialId={busyFileId}
          onClose={() => setIsFilesPanelOpen(false)}
          onUpload={() => attachmentUploaderRef.current?.openPicker()}
          onRetryUpload={upload => {
            attachmentUploaderRef.current?.retryUpload(upload.uploadId);
          }}
          onPreview={file => {
            if (!file.latestVersionId) return;
            openFileUrl(materialClient.previewUrl(file.materialId, file.latestVersionId));
          }}
          onDownload={file => {
            if (!file.latestVersionId) return;
            openFileUrl(materialClient.downloadUrl(file.materialId, file.latestVersionId));
          }}
          onAddToChartbook={file => {
            if (!filesChartbook) return;
            void runFileAction(file, () => chartbookClient.addFile(
              filesChartbook.chartbookId, file.materialId, crypto.randomUUID(),
            ));
          }}
          onRemoveFromConversation={file => {
            void runFileAction(file, async () => {
              if (file.retentionClass === 'TEMPORARY') {
                await materialClient.remove(file.materialId, crypto.randomUUID());
                setConversationAttachments(previous => previous.filter(
                  attachment => attachment.materialId !== file.materialId,
                ));
                return;
              }
              const details = await materialClient.details(file.materialId);
              const conversationScope = details.scopes.find(scope => (
                scope.scopeType === 'CONVERSATION' && scope.scopeKey === sessionId
              ));
              if (!conversationScope) return;
              if (details.scopes.length > 1) {
                await materialClient.removeScope(file.materialId, conversationScope.linkId);
                setConversationAttachments(previous => previous.filter(
                  attachment => attachment.materialId !== file.materialId,
                ));
                return;
              }
              // A sole conversation scope has no other consumer, so normal recycle semantics apply.
              await materialClient.remove(file.materialId, crypto.randomUUID());
              setConversationAttachments(previous => previous.filter(
                attachment => attachment.materialId !== file.materialId,
              ));
            });
          }}
          onRemoveFromChartbook={file => {
            if (!filesChartbook) return;
            void runFileAction(file, () => chartbookClient.removeFile(
              filesChartbook.chartbookId, file.materialId, crypto.randomUUID(),
            ));
          }}
          onRetry={file => {
            void runFileAction(file, () => materialClient.reprocess(file.materialId, crypto.randomUUID()));
          }}
        />
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
              baseUrl={DRAWIO_BASE_URL}
              selectionPluginId={DRAWIO_SELECTION_PLUGIN_ID}
              canvasVersion={activeCanvasSession?.canvasVersion}
              contentHash={activeCanvasSession?.canvasContentHash}
              onSelectionChange={(selection) => {
                // The tuple is sent to the server later; stale selections are never reduced to IDs alone.
                selectedCellsRef.current = selection;
                setTargetClarification(null);
                const cellId = selection.cellIds[0];
                const diagramId = activeCanvasSession?.diagramId;
                const requestNumber = ++citationRequestRef.current;
                setCitationCellId(cellId || null);
                setCellCitations([]);
                if (!cellId || !diagramId || !currentUser) {
                  setCitationsLoading(false);
                  return;
                }
                setCitationsLoading(true);
                agentApi.getCellCitations(currentUser, diagramId, cellId, selection.canvasVersion,
                  provenanceRefForCell(editorXml, cellId))
                  .then(response => {
                    if (requestNumber === citationRequestRef.current) setCellCitations(response.data || []);
                  })
                  .catch(() => {
                    if (requestNumber === citationRequestRef.current) setCellCitations([]);
                  })
                  .finally(() => {
                    if (requestNumber === citationRequestRef.current) setCitationsLoading(false);
                  });
              }}
              xml={editorXml}
              autosave={true}
              onAutoSave={(data) => {
                if (ignoreAutosaveForBlankEditorRef.current) return;
                const activeSessionId = currentSessionRef.current || currentSessionId;
                const hasInlineXml = Boolean(data && typeof data === 'object' && 'xml' in data);
                if (shouldHandleManualAutosave({
                  currentSessionId: activeSessionId,
                  editorReady: isDrawIoReadyRef.current,
                  exportingForChat: Boolean(
                    exportCoordinatorRef.current?.isBusy('chat-xml')
                    || exportCoordinatorRef.current?.isBusy('visual-review-png')
                  ),
                  exportingThumbnail: Boolean(exportCoordinatorRef.current?.isBusy('thumbnail-png')),
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
                       if (activeSession) requestAutosaveXmlExport(activeSession);
                  }
                }
              }}
              onLoad={handleDrawioLoad}
              onExport={(data) => {
                const handled = exportCoordinatorRef.current?.handleExport({
                  data: data.data,
                  xml: data.xml,
                  format: data.format,
                  requestId: (data.message as { requestId?: string }).requestId,
                });
                if (!handled && data.data) setImgData(data.data);
              }}
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
          {targetClarification && (
            <aside className="absolute left-5 top-5 z-30 w-80 max-w-[calc(100%-2.5rem)] rounded-xl border border-amber-200 bg-white/95 p-4 shadow-lg backdrop-blur">
              <div className="mb-3 flex items-center justify-between">
                <div>
                  <p className="text-xs font-semibold uppercase tracking-wide text-amber-700">Choose canvas target</p>
                  <p className="text-xs text-zinc-500">Select one candidate, then resend the question.</p>
                </div>
                <button
                  className="rounded p-1 text-zinc-400 hover:bg-stone-100 hover:text-zinc-700"
                  onClick={() => {
                    drawioRef.current?.highlightCells([], targetClarification.canvasVersion, targetClarification.contentHash);
                    selectedCellsRef.current = null;
                    setTargetClarification(null);
                  }}
                  title="Clear target candidates"
                >
                  <Icons.Close className="h-4 w-4" />
                </button>
              </div>
              <div className="max-h-64 space-y-2 overflow-y-auto">
                {targetClarification.candidates.filter(candidate => candidate.cellId).map(candidate => (
                  <button
                    key={candidate.cellId}
                    className="w-full rounded-lg border border-stone-200 bg-stone-50 p-3 text-left hover:border-amber-300 hover:bg-amber-50"
                    onClick={() => {
                      selectedCellsRef.current = {
                        cellIds: [candidate.cellId],
                        canvasVersion: targetClarification.canvasVersion,
                        contentHash: targetClarification.contentHash,
                      };
                      drawioRef.current?.highlightCells([candidate.cellId], targetClarification.canvasVersion,
                        targetClarification.contentHash);
                      setTargetClarification(null);
                    }}
                  >
                    <p className="truncate text-sm font-medium text-zinc-800">{candidate.shortLabel || candidate.cellId}</p>
                    <p className="mt-1 text-xs text-zinc-500">{candidate.kind.toLowerCase()} · {candidate.reasonCode.toLowerCase()}</p>
                  </button>
                ))}
              </div>
            </aside>
          )}
          {directConfirmation && (
            <DirectConfirmationPanel
              issues={directConfirmation.issues}
              selections={directConfirmation.selections}
              onSelectionChange={(reasonCode, value) => setDirectConfirmation(current => (
                current ? {
                  ...current,
                  selections: { ...current.selections, [reasonCode]: value },
                } : null
              ))}
              onConfirm={handleDirectConfirmation}
              onCancel={() => setDirectConfirmation(null)}
              disabled={isSending}
            />
          )}
          {citationCellId && (
            <aside className="absolute bottom-5 right-5 z-30 w-80 max-w-[calc(100%-2.5rem)] rounded-xl border border-stone-200 bg-white/95 p-4 shadow-lg backdrop-blur">
              <div className="mb-3 flex items-center justify-between">
                <div>
                  <p className="text-xs font-semibold uppercase tracking-wide text-zinc-500">Sources</p>
                  <p className="max-w-56 truncate text-sm text-zinc-800">Cell {citationCellId}</p>
                </div>
                <button
                  className="rounded p-1 text-zinc-400 hover:bg-stone-100 hover:text-zinc-700"
                  onClick={() => { setCitationCellId(null); setCellCitations([]); }}
                  title="Close sources"
                >
                  <Icons.Close className="h-4 w-4" />
                </button>
              </div>
              {citationsLoading && <p className="text-xs text-zinc-500">Loading sources…</p>}
              {!citationsLoading && cellCitations.length === 0 && (
                <p className="text-xs leading-5 text-zinc-500">No source is attached to this cell.</p>
              )}
              <div className="max-h-64 space-y-3 overflow-y-auto">
                {cellCitations.map(citation => (
                  <div key={citation.citationId} className="rounded-lg bg-stone-50 p-3">
                    <p className="text-xs font-medium text-zinc-700">{citation.supportType.replace('_', ' ')}</p>
                    {citation.sources.map(source => (
                      <div key={`${citation.citationId}:${source.citationKey}`} className="mt-2 border-t border-stone-200 pt-2 text-xs text-zinc-600">
                        <p className="font-medium text-zinc-800">{source.displayName || 'Source unavailable'}</p>
                        <p>
                          {source.versionNo ? `v${source.versionNo}` : 'Version'}
                          {source.pageNumber ? ` · page ${source.pageNumber}` : ''}
                          {source.modality ? ` · ${source.modality.toLowerCase()}` : ''}
                          {source.origin ? ` · ${citationOriginLabel(source.origin)}` : ''}
                        </p>
                        {source.sourceState === 'SOURCE_UNAVAILABLE' && (
                          <p className="mt-1 text-amber-700">Source unavailable{source.deletedAt ? ` · deleted ${source.deletedAt}` : ''}</p>
                        )}
                        {source.boundedExcerpt && (
                          <p className="mt-1 line-clamp-4 text-zinc-600">{source.boundedExcerpt}</p>
                        )}
                        {source.previewUrl && (
                          <a className="mt-1 inline-block text-indigo-600 hover:underline" href={source.previewUrl} target="_blank" rel="noreferrer">
                            Open bounded page preview
                          </a>
                        )}
                      </div>
                    ))}
                  </div>
                ))}
              </div>
            </aside>
          )}
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

          {/* Keep history independently scrollable so it ends above the fixed-size composer. */}
          {/* Messages Area */}
          <div className="min-h-0 min-w-0 flex-1 space-y-6 overflow-x-hidden overflow-y-auto bg-[var(--app-bg)] p-5 pr-14 scrollbar-thin scrollbar-track-transparent scrollbar-thumb-stone-300">
            {messages.map((msg, index) => {
              const isLatestRunningAgent = index === messages.length - 1 && isSending;
              const visibleExecutionSteps = getVisibleExecutionSteps(msg.steps, isLatestRunningAgent);
              const thinkingSteps = msg.steps || [];
              const currentThinkingStep = visibleExecutionSteps[visibleExecutionSteps.length - 1];
              const hasThinking = msg.role === 'agent' && thinkingSteps.length > 0;
              const useChineseMessage = msg.language === 'zh' || usesChinesePresentation(msg.content);
              const routeLabel = thinkingRouteLabel(msg.routeType, useChineseMessage);

              return (
                <div 
                  key={`${msg.id}-${index}`} 
                  className={`flex min-w-0 gap-3 ${msg.role === 'user' ? 'flex-row-reverse' : 'flex-row'}`}
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

                  <div className="flex min-w-0 w-full max-w-[85%] flex-col">
                      <div className={`flex flex-col gap-2 ${msg.role === 'user' ? 'items-end' : 'items-start'}`}>
                        {/* Show observable execution facts, not private model reasoning. */}
                        {hasThinking && (
                          <div className="w-full max-w-full">
                            <details className="group/details w-full" open={isLatestRunningAgent}>
                              <summary className="flex cursor-pointer list-none select-none items-center gap-2 rounded-lg px-1 py-1 text-xs font-medium text-zinc-500 transition-colors hover:bg-stone-100 hover:text-zinc-800 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-zinc-300">
                                 {isLatestRunningAgent ? <Icons.Loader className="h-3.5 w-3.5 animate-spin text-zinc-600" /> : <Icons.Sparkles className="h-3.5 w-3.5 text-zinc-500" />}
                                 <span className="text-zinc-700">
                                   {isLatestRunningAgent
                                     ? useChineseMessage ? '正在处理' : 'Working'
                                     : useChineseMessage ? '执行过程' : 'Execution trace'}
                                 </span>
                                 <span className="text-zinc-400">
                                   {isLatestRunningAgent
                                     ? currentThinkingStep?.label || (useChineseMessage ? '正在处理请求' : 'Working through the request')
                                     : useChineseMessage
                                       ? `${routeLabel ? `${routeLabel} · ` : ''}${thinkingSteps.length || 1} 步`
                                       : `${routeLabel ? `${routeLabel} · ` : ''}${thinkingSteps.length || 1} step${thinkingSteps.length === 1 ? '' : 's'}`}
                                 </span>
                                 <span className="ml-auto text-zinc-400 group-open/details:hidden">{useChineseMessage ? '展开' : 'Show'}</span>
                                 <span className="ml-auto hidden text-zinc-400 group-open/details:inline">{useChineseMessage ? '收起' : 'Hide'}</span>
                              </summary>
                              <div className="mt-2 border-l border-stone-200 pb-1 pl-3 text-sm text-zinc-600">
                                {thinkingSteps.length > 0 ? thinkingSteps.map((step, idx) => (
                                  <div key={`${step.phase}-${idx}`} className="relative pb-3 last:pb-0">
                                    <span className={`absolute -left-[1.05rem] top-1 h-2 w-2 rounded-full border-2 border-[var(--app-bg)] ${step.status === 'running' ? 'animate-pulse bg-zinc-800' : 'bg-stone-300'}`} aria-hidden="true" />
                                    <div className="flex items-center gap-2 text-xs">
                                      <span className="font-medium text-zinc-700">{step.label}</span>
                                      {step.status === 'running' && <span className="text-zinc-400">{useChineseMessage ? '进行中' : 'In progress'}</span>}
                                    </div>
                                    {step.content && (
                                      <p className="mt-1 text-xs leading-relaxed text-zinc-500">{step.content.trim()}</p>
                                    )}
                                  </div>
                                )) : (
                                  <p className="text-xs leading-relaxed text-zinc-500">{useChineseMessage ? '正在处理请求。' : 'Working through the request.'}</p>
                                )}
                              </div>
                            </details>
                          </div>
                        )}

                        {/* Content Block */}
                        {msg.content && (
                          <div 
                            className={`
                                min-w-0 max-w-full w-fit p-3.5 text-sm leading-relaxed shadow-sm [overflow-wrap:anywhere]
                                ${msg.role === 'user'
                                ? 'rounded-lg bg-zinc-700 text-white shadow-sm whitespace-pre-wrap'
                                : 'rounded-lg border border-stone-200 bg-white text-zinc-700 shadow-sm prose prose-sm prose-zinc overflow-hidden prose-p:my-1.5 prose-ol:my-2 prose-ul:my-2 prose-li:my-1 prose-pre:my-2 prose-pre:max-w-full prose-pre:overflow-x-auto prose-pre:bg-stone-100 prose-pre:text-zinc-700'
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

                        {msg.role === 'user' && msg.attachments && msg.attachments.length > 0 && (
                          <div className="grid max-w-full grid-cols-1 justify-items-end gap-2">
                            {msg.attachments.map(attachment => (
                              <MessageAttachmentPreview
                                key={attachment.uploadId}
                                attachment={attachment}
                                previewUrl={attachment.materialId && attachment.versionId
                                  ? materialClient.previewUrl(attachment.materialId, attachment.versionId)
                                  : undefined}
                              />
                            ))}
                          </div>
                        )}

                        {msg.role === 'agent' && msg.evidenceClaims && msg.evidenceClaims.length > 0 && (
                          <div className="max-w-full space-y-1.5">
                            {msg.evidenceClaims.map(claim => (
                              <div key={claim.claimKey} className="flex flex-wrap items-center gap-1.5 text-[11px] text-zinc-500">
                                <span className="font-medium text-zinc-700">{claim.claimKey}</span>
                                {claim.citationKeys.length === 0 && <span>AI knowledge</span>}
                                {claim.citationKeys.map(citationKey => {
                                  const source = msg.evidenceSources?.find(item => item.citationKey === citationKey);
                                  if (!source) return null;
                                  return (
                                    <span
                                      key={`${claim.claimKey}:${citationKey}`}
                                      className="rounded-full border border-stone-200 bg-stone-50 px-2.5 py-1 text-zinc-600"
                                      title={`${source.origin.toLowerCase()} · ${source.modality?.toLowerCase() || 'text'}`}
                                    >
                                      {source.sourceLabel}{source.pageNumber ? ` p.${source.pageNumber}` : ''}
                                      {source.origin === 'EXISTING_REFERENCE' ? ' · existing' : source.origin === 'SUPPLEMENTAL' ? ' · supplemental' : ''}
                                    </span>
                                  );
                                })}
                              </div>
                            ))}
                          </div>
                        )}

                        {msg.role === 'agent' && (msg.contextReceipts?.length || msg.citationReceipt) && (
                          <ContextReceiptBar
                            receipts={[...(msg.contextReceipts || []), ...(msg.citationReceipt ? [msg.citationReceipt] : [])]}
                            useChinese={useChineseMessage}
                          />
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

          {/* The composer shares the chat surface without a visual divider. */}
          {/* Input Area */}
          <div className="relative z-20 shrink-0 bg-[var(--app-bg)] p-4">
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

            {/* Codex-style composer: attachments, writing surface, and actions each get their own row. */}
            <div
              className="relative flex min-h-[176px] flex-col rounded-[26px] border border-stone-200 bg-white px-3 pb-3 pt-3 shadow-[0_8px_28px_rgba(24,24,27,0.07)] transition-[border-color,box-shadow] focus-within:border-stone-300 focus-within:shadow-[0_10px_32px_rgba(24,24,27,0.1)]"
            >
              {slashOpen && filteredSkills.length > 0 && (
                <div className="absolute bottom-full left-0 z-50 mb-2 max-h-72 w-80 overflow-auto rounded-2xl border border-stone-200 bg-white py-1 shadow-xl">
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
              <ConversationAttachmentTray
                ref={attachmentUploaderRef}
                client={materialClient}
                sessionId={sessionId}
                diagramId={currentDiagramId}
                acceptedMimeTypes={acceptedMaterialMimeTypes}
                attachments={conversationAttachments}
                onChange={setConversationAttachments}
                suppressedUploadIds={sentAttachmentUploadIds}
                onSentAttachmentStatus={attachment => {
                  // A late processing result enriches the sent message without recreating a draft.
                  setMessages(previous => previous.map(message => (
                    message.attachments?.some(item => item.uploadId === attachment.uploadId)
                      ? {
                          ...message,
                          attachments: message.attachments.map(item => (
                            item.uploadId === attachment.uploadId ? { ...item, ...attachment } : item
                          )),
                        }
                      : message
                  )));
                }}
                onPrepareUpload={async () => {
                  const attachmentSessionId = await initializeAttachmentSession();
                  if (!attachmentSessionId) throw new Error('无法创建附件会话，请重试。');
                  if (!currentDiagramId) throw new Error('请先创建图表，再上传资料。');
                  // Selecting an attachment establishes conversation intent even before the first message is sent.
                  const prepared = await ensureConversationDiagramShell({
                    diagramId: currentDiagramId,
                    title: activeCanvasSession?.title,
                    canvasXml: activeCanvasSession?.drawIoXml || undefined,
                    canvasVersion: activeCanvasSession?.canvasVersion,
                    hasConversationMessages: true,
                  });
                  if (!prepared) throw new Error('无法建立资料所属图表，请重试。');
                  return {
                    scopeType: 'CONVERSATION',
                    scopeId: attachmentSessionId,
                    retentionClass: 'TEMPORARY',
                    diagramId: currentDiagramId,
                  };
                }}
                disabled={isSending || !selectedAgentId || !isRequestedDiagramReady}
              />
              <ComposerLibrarySelectionTray
                selections={librarySelections}
                onRemove={removeLibrarySelection}
              />

              {/* The composer shell owns focus treatment; the textarea itself stays visually borderless. */}
              <textarea
                ref={promptInputRef}
                value={inputValue}
                onChange={(e) => {
                  handleInputChange(e.target.value);
                  e.target.style.height = 'auto';
                  e.target.style.height = Math.min(e.target.scrollHeight, 300) + 'px';
                }}
                onKeyDown={handleKeyDown}
                placeholder={!isRequestedDiagramReady ? "Diagram unavailable" : isSending ? "AI is generating..." : demoQuotaState.exhausted ? demoQuotaState.exhaustedMessage : "Describe a diagram, or ask to edit this one…"}
                disabled={!isRequestedDiagramReady || isSending || demoQuotaState.exhausted}
                className="composer-textarea max-h-[300px] w-full resize-none border-none bg-transparent px-2 py-2 text-[15px] leading-6 text-zinc-800 placeholder:text-zinc-400 focus:ring-0 disabled:cursor-not-allowed disabled:opacity-50 scrollbar-thin scrollbar-track-transparent scrollbar-thumb-stone-300"
                rows={1}
                style={{ height: 'auto', minHeight: COMPOSER_TEXTAREA_MIN_HEIGHT_PX }}
              />

              <div className="mt-auto flex items-center justify-between gap-3 pt-1">
                {/* Files owns management; the composer keeps a lightweight upload shortcut. */}
                <div className="flex items-center gap-1.5">
                  <ComposerAddMenu
                    client={materialClient}
                    disabled={isSending || !selectedAgentId || !isRequestedDiagramReady}
                    selections={librarySelections}
                    onSelect={attachLibrarySelection}
                    onRemove={removeLibrarySelection}
                    onUploadFromComputer={() => attachmentUploaderRef.current?.openPicker()}
                  />
                  {demoQuotaState.visible && !demoQuotaState.exhausted && (
                    <span className="flex items-center gap-1.5 whitespace-nowrap font-mono text-[11px] font-medium text-zinc-500" title={demoQuotaState.label}>
                      <span className={`h-1.5 w-1.5 rounded-full ${demoQuotaState.remaining <= 1 ? 'bg-amber-500' : 'bg-emerald-500'}`} aria-hidden="true" />
                      {demoQuotaState.remaining} left
                    </span>
                  )}
                </div>

                {/* Keep model and repair controls in the same footer as the primary actions. */}
                <div className="flex min-w-0 shrink items-center justify-end gap-1">
                  <div className="relative flex h-9 shrink-0 items-center rounded-full transition-colors hover:bg-stone-100">
                    <span className="hidden pl-2.5 text-[11px] font-medium text-zinc-500 min-[360px]:inline">Repair</span>
                    <select
                      value={maxDeterministicRepairRounds}
                      onChange={(e) => {
                        const nextValue = Number(e.target.value);
                        setMaxDeterministicRepairRounds(nextValue);
                        localStorage.setItem(DETERMINISTIC_REPAIR_ROUNDS_STORAGE_KEY, String(nextValue));
                      }}
                      className="cursor-pointer appearance-none border-none bg-transparent py-1 pl-1 pr-5 text-[11px] font-medium text-zinc-700 outline-none focus:ring-0"
                      title="Deterministic repair rounds"
                      aria-label="Deterministic repair rounds"
                    >
                      {DETERMINISTIC_REPAIR_ROUND_OPTIONS.map(count => (
                        <option key={count} value={count}>{count}x</option>
                      ))}
                    </select>
                    <div className="pointer-events-none absolute inset-y-0 right-0 flex items-center px-1.5 text-zinc-400">
                      <svg className="h-3 w-3 fill-current" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path d="M9.293 12.95l.707.707L15.657 8l-1.414-1.414L10 10.828 5.757 6.586 4.343 8z"/></svg>
                    </div>
                  </div>

                  <div className="relative flex h-9 min-w-0 items-center rounded-full transition-colors hover:bg-stone-100">
                    <Icons.Sparkles className={`ml-2 h-3.5 w-3.5 shrink-0 ${selectedCustomModelId !== 'default' ? 'text-zinc-700' : 'text-zinc-400'}`} />
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
                      className="min-w-0 max-w-[112px] cursor-pointer appearance-none truncate border-none bg-transparent py-1 pl-1 pr-5 text-xs font-medium text-zinc-600 outline-none focus:ring-0"
                      aria-label="Model"
                    >
                      <option value="default">Default Model</option>
                      {customModels.filter(m => m.enabled).map(m => (
                        <option key={m.id} value={m.id}>{m.name || m.model}</option>
                      ))}
                      <option disabled>──────────</option>
                      <option value="add_new">+ Manage Models</option>
                    </select>
                    <div className="pointer-events-none absolute inset-y-0 right-0 flex items-center px-1.5 text-zinc-400">
                      <svg className="h-3 w-3 fill-current" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path d="M9.293 12.95l.707.707L15.657 8l-1.414-1.414L10 10.828 5.757 6.586 4.343 8z"/></svg>
                    </div>
                  </div>

                  {isSending ? (
                    <button
                      onClick={handleStopStream}
                      className="grid h-10 w-10 place-items-center rounded-full bg-zinc-900 text-white shadow-sm transition-colors hover:bg-zinc-700"
                      title="Stop generation"
                      aria-label="Stop generation"
                    >
                      <Icons.Square className="h-4 w-4" />
                    </button>
                  ) : (
                    <button
                      onClick={handleSendMessage}
                      disabled={!isRequestedDiagramReady || !inputValue.trim() || demoQuotaState.exhausted || hasProcessingAttachments}
                      className={`
                        grid h-10 w-10 place-items-center rounded-full transition-[background-color,color,transform] duration-150
                        ${isRequestedDiagramReady && inputValue.trim() && !demoQuotaState.exhausted && !hasProcessingAttachments
                          ? 'bg-zinc-900 text-white shadow-sm hover:bg-zinc-700 active:scale-95'
                          : 'cursor-not-allowed bg-stone-100 text-zinc-400'
                        }
                      `}
                      title={!isRequestedDiagramReady ? "Diagram unavailable" : hasProcessingAttachments ? "Wait for attachments to finish" : "Send message"}
                      aria-label="Send message"
                    >
                      <Icons.ArrowUp className="h-[18px] w-[18px]" />
                    </button>
                  )}
                </div>
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
