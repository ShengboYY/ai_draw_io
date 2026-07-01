'use client';

import { DrawIoEmbed, DrawIoEmbedRef } from 'react-drawio';
import { useRef, useState, useEffect } from 'react';
import { useRouter } from 'next/navigation';
import { getUserInfo } from '@/utils/cookie';
import { agentApi, StreamEvent } from '@/api/agent';
import ReactMarkdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import { buildStepSummary } from './execution-step-summary';
import {
  buildStreamingPreviewXml,
  isValidDrawioCellXml,
  normalizeDrawioLegendSwatches,
} from './streaming-preview';
import { buildDrawioChatRequestPayload } from './chat-request-payload';
import {
  CanvasStateMetadata,
  makeLocalDiagramId,
  mergeCanvasStateMetadata,
} from './canvas-state-metadata';
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
  title: string;
  messages: Message[];
  drawIoXml: string | null;
  lastModified: number;
}

export interface CustomModelConfig {
  id: string;
  name: string;
  baseUrl: string;
  apiKey: string;
  model: string;
  completionsPath: string;
  enabled: boolean;
}

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
  storedXml?: string | null
) => {
  const exportedXml = extractDrawioXml(exportedPayload);
  if (hasDrawableCells(exportedXml)) return normalizeDrawioLegendSwatches(exportedXml);
  if (hasDrawableCells(storedXml)) return normalizeDrawioLegendSwatches(storedXml || EMPTY_DRAWIO_XML);
  return normalizeDrawioLegendSwatches(exportedXml || storedXml || EMPTY_DRAWIO_XML);
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
    <div className="w-full rounded-2xl rounded-tl-sm border border-slate-200 bg-white px-3.5 py-3 text-sm leading-relaxed text-slate-700 shadow-sm">
      <p className="m-0">{summary}</p>

      {view.finalContent && (
        <div className="mt-2 prose prose-sm prose-slate max-w-none prose-p:my-1.5 prose-ol:my-2 prose-ul:my-2 prose-li:my-1">
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{view.finalContent}</ReactMarkdown>
        </div>
      )}

      {view.visibleEvents.length > 0 && (
        <details className="group/details mt-3 border-t border-slate-100 pt-2">
          <summary className="inline-flex cursor-pointer items-center gap-1.5 text-xs font-medium text-slate-500 transition-colors hover:text-slate-700">
            <Icons.Sparkles className="h-3.5 w-3.5 text-indigo-400" />
            <span className="group-open/details:hidden">View tool and validation details</span>
            <span className="hidden group-open/details:inline">Hide tool and validation details</span>
          </summary>
          <div className="mt-2 space-y-2 rounded-xl bg-slate-50/70 p-3 text-xs text-slate-600">
            <div>
              <span className="font-medium text-slate-700">Actions:</span> {view.toolLabel}
            </div>
            {view.visibleEvents.map(event => (
              <div key={event.id} className="flex gap-2">
                <span className={`mt-0.5 flex h-4 w-4 shrink-0 items-center justify-center rounded-full border text-[10px] font-semibold ${event.status === 'done' ? 'border-emerald-200 bg-emerald-50 text-emerald-600' : event.status === 'warning' ? 'border-amber-200 bg-amber-50 text-amber-600' : event.status === 'error' ? 'border-rose-200 bg-rose-50 text-rose-600' : 'border-blue-200 bg-blue-50 text-blue-600'}`}>
                  {eventStatusMarks[event.status]}
                </span>
                <div className="min-w-0">
                  <div className="flex flex-wrap items-center gap-1.5">
                    <span className="font-medium text-slate-700">{event.title}</span>
                    <span className={eventTextClasses[event.status]}>{event.statusLabel}</span>
                  </div>
                  {event.detail && (
                    <div className="mt-0.5 break-words text-slate-500">{summarizeEventDetail(event.detail)}</div>
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
  const router = useRouter();
  const [imgData, setImgData] = useState<string | null>(null);
  const drawioRef = useRef<DrawIoEmbedRef>(null);
  
  // User State
  const [currentUser, setCurrentUser] = useState('');

  // Chat State
  const [isChatOpen, setIsChatOpen] = useState(true);
  const [chatWidth, setChatWidth] = useState(CHAT_DEFAULT_WIDTH);
  const [isResizingChat, setIsResizingChat] = useState(false);
  const resizeStartXRef = useRef(0);
  const resizeStartWidthRef = useRef(CHAT_DEFAULT_WIDTH);
  const [messages, setMessages] = useState<Message[]>([
    {
      id: '1',
      role: 'agent',
      content: 'Hi! Tell me what Draw.io diagram you want to create, such as a flowchart, architecture diagram, UML class diagram, sequence diagram, ER diagram, use case diagram, or state diagram.',
      timestamp: Date.now()
    }
  ]);
  const [inputValue, setInputValue] = useState('');
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

  // Pick a skill from the menu: drop the "/query" token, add it as a chip.
  const chooseSkill = (name: string) => {
    setInputValue(v => v.replace(/(^|\s)\/[\w-]*$/, '$1'));
    setSelectedSkills(prev => prev.includes(name) ? prev : [...prev, name]);
    setSlashOpen(false);
    setSlashQuery('');
  };
  const removeSkill = (name: string) => setSelectedSkills(prev => prev.filter(s => s !== name));

  // Sidebar State
  const [isSidebarOpen, setIsSidebarOpen] = useState(true);

  // Stream State
  const [streamPhase, setStreamPhase] = useState<string>('');
  const [streamProgress, setStreamProgress] = useState<string>('');
  const streamAbortRef = useRef<AbortController | null>(null);

  // Context State
  const [lastExportedData, setLastExportedData] = useState<{data: string, xml?: string, timestamp: number} | null>(null);
  const isExportingForChatRef = useRef(false);
  const isAutosaveRef = useRef(false);
  const pendingMessageRef = useRef('');
  const [isDrawIoReady, setIsDrawIoReady] = useState(false);
  const isDrawIoReadyRef = useRef(false);
  const streamingPreviewQueueRef = useRef<string[]>([]);
  const streamingPreviewTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const scheduleStreamingPreviewDrainRef = useRef<() => void>(() => {});
  const pendingFinalDrawioXmlRef = useRef('');
  const [editorXml, setEditorXml] = useState(EMPTY_DRAWIO_XML);
  const [editorInstanceKey, setEditorInstanceKey] = useState(0);

  // Agent State
  const [selectedAgentId, setSelectedAgentId] = useState('');
  const [sessionId, setSessionId] = useState('');

  // Rename State
  const [isRenameModalOpen, setIsRenameModalOpen] = useState(false);
  const [renamingSessionId, setRenamingSessionId] = useState<string | null>(null);
  const [newSessionTitle, setNewSessionTitle] = useState('');

  // Custom API Config State
  const [showApiConfig, setShowApiConfig] = useState(false);
  const [customModels, setCustomModels] = useState<CustomModelConfig[]>([]);
  const [selectedCustomModelId, setSelectedCustomModelId] = useState<string>('default');
  const [maxReviewIterations, setMaxReviewIterations] = useState(1);
  
  // Temporary state for editing in modal
  const [editingModel, setEditingModel] = useState<CustomModelConfig | null>(null);

  // Session Management State
  const [sessions, setSessions] = useState<Session[]>([]);
  const [currentSessionId, setCurrentSessionId] = useState<string | null>(null);
  const currentSessionRef = useRef(currentSessionId);

  const persistSessions = (nextSessions: Session[]) => {
    try {
      localStorage.setItem(DRAWIO_SESSIONS_STORAGE_KEY, JSON.stringify(nextSessions));
    } catch (e) {
      console.error('Failed to save sessions to localStorage:', e);
    }
  };

  const saveCurrentCanvasXml = (xml?: string | null, canvasState?: CanvasStateMetadata) => {
    const activeSessionId = currentSessionRef.current;
    const normalizedXml = normalizeDrawioLegendSwatches(xml || EMPTY_DRAWIO_XML);
    if (!activeSessionId || !hasDrawableCells(normalizedXml)) return;

    setSessions(prev => {
      const nextSessions = prev.map(session => {
        if (session.id === activeSessionId) {
          const mergedCanvasState = mergeCanvasStateMetadata(
            { diagramId: session.diagramId, version: session.canvasVersion },
            canvasState,
          );
          return {
            ...session,
            diagramId: mergedCanvasState.diagramId,
            canvasVersion: mergedCanvasState.version,
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

  const clearStreamingPreviewQueue = () => {
    streamingPreviewQueueRef.current = [];
    pendingFinalDrawioXmlRef.current = '';
    if (streamingPreviewTimerRef.current) {
      clearTimeout(streamingPreviewTimerRef.current);
      streamingPreviewTimerRef.current = null;
    }
  };

  const replaceEditorXml = (xml?: string | null) => {
    clearStreamingPreviewQueue();
    isDrawIoReadyRef.current = false;
    setIsDrawIoReady(false);
    setEditorXml(normalizeDrawioLegendSwatches(xml && xml.trim() ? xml : EMPTY_DRAWIO_XML));
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

    pendingFinalDrawioXmlRef.current = normalizedXml;
    queueStreamingPreviewXml(normalizedXml);
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
  scheduleStreamingPreviewDrainRef.current = scheduleStreamingPreviewDrain;

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
    isDrawIoReadyRef.current = isDrawIoReady;
    if (isDrawIoReady) {
      scheduleStreamingPreviewDrainRef.current();
    }
  }, [isDrawIoReady]);

  useEffect(() => () => {
    clearStreamingPreviewQueue();
  }, []);

  useEffect(() => {
    const savedWidth = localStorage.getItem(CHAT_WIDTH_STORAGE_KEY);
    const parsedWidth = savedWidth ? Number(savedWidth) : NaN;
    if (Number.isFinite(parsedWidth)) {
      setChatWidth(clampChatWidth(parsedWidth));
    }

    const savedSidebarOpen = localStorage.getItem(SIDEBAR_OPEN_STORAGE_KEY);
    if (savedSidebarOpen === 'false') {
      setIsSidebarOpen(false);
    }
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

  useEffect(() => {
    localStorage.setItem(SIDEBAR_OPEN_STORAGE_KEY, String(isSidebarOpen));
  }, [isSidebarOpen]);

  // Load sessions from localStorage
  useEffect(() => {
    const savedSessions = localStorage.getItem(DRAWIO_SESSIONS_STORAGE_KEY);
    if (savedSessions) {
      try {
        const parsed = JSON.parse(savedSessions);
        setSessions(parsed);
        if (parsed.length > 0) {
          // Load the most recent session (first one if sorted by lastModified desc)
          const mostRecent = parsed.sort((a: Session, b: Session) => b.lastModified - a.lastModified)[0];
          setCurrentSessionId(mostRecent.id);
          setMessages(mostRecent.messages);
          replaceEditorXml(mostRecent.drawIoXml || EMPTY_DRAWIO_XML);
        } else {
            createNewSession(true);
        }
      } catch (e) {
        console.error('Failed to parse sessions:', e);
        createNewSession(true);
      }
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
            // Update title if it's the default "New Chat" and we have a user message
            title: session.title === 'New Chat' && messages.find(m => m.role === 'user') 
              ? (messages.find(m => m.role === 'user')?.content.slice(0, 20) || 'New Chat')
              : session.title
          };
        }
        return session;
      }));
    }
  }, [messages, currentSessionId, sessionId]);

  const createNewSession = (_isInitial = false, backendId = '') => {
    const localSessionId = Date.now().toString();
    const newSession: Session = {
      id: localSessionId,
      backendSessionId: backendId,
      diagramId: makeLocalDiagramId(localSessionId),
      title: 'New Chat',
      messages: [{
        id: Date.now().toString(),
        role: 'agent',
        content: 'Hi! Tell me what Draw.io diagram you want to create, such as a flowchart, architecture diagram, UML class diagram, sequence diagram, ER diagram, use case diagram, or state diagram.',
        timestamp: Date.now()
      }],
      drawIoXml: null,
      lastModified: Date.now()
    };

    setSessions(prev => [newSession, ...prev]);
    setCurrentSessionId(newSession.id);
    setMessages(newSession.messages);
    setSessionId(backendId);
    replaceEditorXml(EMPTY_DRAWIO_XML);
  };

  const handleSwitchSession = (targetSessionId: string) => {
    if (targetSessionId === currentSessionId) return;
    loadSession(targetSessionId);
  };

  const loadSession = (targetSessionId: string) => {
    const session = sessions.find(s => s.id === targetSessionId);
    if (session) {
	        setCurrentSessionId(targetSessionId);
	        setMessages(session.messages);
	        // Do not reuse old backend sessions when switching local conversations.
	        setSessionId('');
	        replaceEditorXml(session.drawIoXml || EMPTY_DRAWIO_XML);
    }
  };

  const handleDeleteSession = (e: React.MouseEvent, sessionIdToDelete: string) => {
    e.stopPropagation();
    const newSessions = sessions.filter(s => s.id !== sessionIdToDelete);
    setSessions(newSessions);
    persistSessions(newSessions);

    if (currentSessionId === sessionIdToDelete) {
        if (newSessions.length > 0) {
            loadSession(newSessions[0].id);
        } else {
            createNewSession();
        }
    }
  };

  const openRenameSession = (session: Session) => {
    setRenamingSessionId(session.id);
    setNewSessionTitle(session.title);
    setIsRenameModalOpen(true);
  };

  const handleDoubleClickSession = (session: Session) => {
    openRenameSession(session);
  };

  const handleRenameCancel = () => {
    setIsRenameModalOpen(false);
    setRenamingSessionId(null);
    setNewSessionTitle('');
  };

  const handleRenameSave = () => {
    const title = newSessionTitle.trim();
    if (renamingSessionId && title) {
      setSessions(prev => prev.map(s => 
        s.id === renamingSessionId ? { ...s, title } : s
      ));
      handleRenameCancel();
    }
  };

  const saveCustomModels = (models: CustomModelConfig[]) => {
    setCustomModels(models);
    localStorage.setItem('ai_agent_custom_models', JSON.stringify(models));
  };

  const handleAddNewModel = () => {
    setEditingModel({
      id: Date.now().toString(),
      name: 'New Model',
      baseUrl: 'https://api.openai.com',
      apiKey: '',
      model: 'gpt-4o',
      completionsPath: 'v1/chat/completions',
      enabled: true
    });
  };

  const handleSaveEditingModel = () => {
    if (!editingModel) return;
    const exists = customModels.some(m => m.id === editingModel.id);
    let newModels;
    if (exists) {
      newModels = customModels.map(m => m.id === editingModel.id ? editingModel : m);
    } else {
      newModels = [...customModels, editingModel];
    }
    saveCustomModels(newModels);
    setSelectedCustomModelId(editingModel.id);
    localStorage.setItem('ai_agent_selected_model', editingModel.id);
    setEditingModel(null);
  };

  const handleDeleteModel = (id: string) => {
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

  // Check Login & Load Agents
  useEffect(() => {
    const userInfo = getUserInfo();
    if (!userInfo || !userInfo.user) {
      router.push('/login');
      return;
    }
    setCurrentUser(userInfo.user);

    // Load Custom Models
    const savedModels = localStorage.getItem('ai_agent_custom_models');
    if (savedModels) {
      try {
        setCustomModels(JSON.parse(savedModels));
      } catch (e) {}
    }
    const savedSelected = localStorage.getItem('ai_agent_selected_model');
    if (savedSelected) {
      setSelectedCustomModelId(savedSelected);
    }
    const savedMaxReviewIterationsRaw = localStorage.getItem(MAX_REVIEW_ITERATIONS_STORAGE_KEY);
    if (savedMaxReviewIterationsRaw !== null && savedMaxReviewIterationsRaw !== '') {
      const savedMaxReviewIterations = Number(savedMaxReviewIterationsRaw);
      if (Number.isFinite(savedMaxReviewIterations)) {
        setMaxReviewIterations(Math.min(Math.max(savedMaxReviewIterations, 0), 3));
      }
    }

    // Load Agents
    const loadAgents = async () => {
      try {
        const res = await agentApi.queryAiAgentConfigList();
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
        console.error('Failed to load agents:', error);
        setMessages(prev => [...prev, {
          id: Date.now().toString(),
          role: 'agent',
          content: 'Failed to load the agent list. Please check whether the backend service is running.',
          timestamp: Date.now()
        }]);
      }
    };
    loadAgents();
  }, [router]);

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
          content: 'Hi! Tell me what Draw.io diagram you want to create, such as a flowchart, architecture diagram, UML class diagram, sequence diagram, ER diagram, use case diagram, or state diagram.',
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
      let completionMessageAdded = false;
      let emptyResponseMessageAdded = false;
      let previewSkeletonXml = '';
      let accumulatedNodes: string[] = []; // To hold incrementally added nodes
      let accumulatedEdges: string[] = []; // To hold incrementally added edges
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

      const canShowCompletion = () => !requestedMoreInfo && (nodeCount > 0 || edgeCount > 0 || receivedDrawioDone);

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

      const requestPayload = buildDrawioChatRequestPayload({
          agentId: selectedAgentId,
          userId: currentUser,
          sessionId: activeBackendSessionId,
          userMessage: displayContent,
          diagramId,
          expectedVersion: activeSession?.canvasVersion,
          canvasXml: canvasContext.canvasXml,
          canvasSummary: canvasContext.canvasSummary,
          customBaseUrl: activeModelConfig?.baseUrl || undefined,
          customApiKey: activeModelConfig?.apiKey || undefined,
          customCompletionsPath: activeModelConfig?.completionsPath || undefined,
          customModel: activeModelConfig?.model || undefined,
          maxReviewIterations,
          skills: pendingSkillsRef.current.length ? pendingSkillsRef.current : undefined
      });

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
              loadStreamingPreview(previewSkeletonXml);
              break;
            }

            case 'drawio_node': {
              if (requestedMoreInfo) break;

              if (receivedDrawioDone) {
                // Each drawer pass streams a full canvas draft, so a new pass replaces the previous draft.
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
                  loadStreamingPreview(buildStreamingPreviewXml(accumulatedNodes, accumulatedEdges, previewSkeletonXml));
              } else {
                  break;
              }

              break;
            }

            case 'drawio_edge': {
              if (requestedMoreInfo) break;

              if (receivedDrawioDone) {
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
                  loadStreamingPreview(buildStreamingPreviewXml(accumulatedNodes, accumulatedEdges, previewSkeletonXml));
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
	                });
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
              upsertRunEvent('stream:error', {
                phase: 'error',
                title: 'Stream error',
                detail: chunk.content,
                status: 'error',
                tone: 'review',
              });
              accumulatedContent += (accumulatedContent ? '\n\n' : '') + `❌ ${chunk.content}`;
              setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, content: accumulatedContent, steps: [...accumulatedSteps] } : m));
              break;
            }

            case 'done': {
              // Stream completed explicitly by backend
              accumulatedSteps.forEach(s => { s.status = 'done'; });
              markRunEventsDone();
              if (!appendCompletionMessage() && !appendEmptyResponseMessage()) {
                setMessages(prev => prev.map(m => m.id === agentMsgId ? { ...m, steps: [...accumulatedSteps] } : m));
              }
              setStreamPhase('done');
              break;
            }
          }
        },
        // onError
        (error: Error) => {
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
        },
        // onComplete
        () => {
          setIsSending(false);
          setStreamPhase('');
          setStreamProgress('');
          markRunEventsDone();
          
          if (!appendCompletionMessage() && !appendEmptyResponseMessage()) {
              setMessages(prev => prev.map(m => {
                  if (m.id === agentMsgId) {
                      return { ...m, steps: markStepsDone(m.steps) };
                  }
                  return m;
              }));
          }
        }
      );

      streamAbortRef.current = controller;

    } catch (error) {
      console.error('Chat error:', error);
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
    const textarea = document.querySelector('textarea');
    if (textarea) textarea.style.height = '80px';
    sendContent(content);
  };

  useEffect(() => {
    if (!lastExportedData) return;
    
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
        const storedXml = sessions.find(session => session.id === currentSessionId)?.drawIoXml;
        const xml = chooseUsableCanvasXml(lastExportedData, storedXml);
        saveCurrentCanvasXml(xml);
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
    { label: 'UML Class', text: 'Please draw a UML class diagram for an e-commerce system, including User, Order, Product, and related classes.' },
    { label: 'Sequence', text: 'Please draw a sequence diagram for user login and registration, including Client, Gateway, Auth Service, and Database.' },
    { label: 'Architecture', text: 'Please draw a microservice architecture diagram with access layer, business service layer, and data layer.' },
    { label: 'Flowchart', text: 'Please draw an e-commerce shopping flowchart covering product browsing, cart, order creation, payment, and shipment.' }
  ];

  return (
    <div className="flex h-screen w-full overflow-hidden bg-slate-50 text-slate-900 font-sans">
      {/* Sessions Sidebar */}
      <div
        className={`
          bg-white text-slate-600 flex flex-col border-r border-slate-100/60 shrink-0 z-30
          transition-[width] duration-300 ease-[cubic-bezier(0.25,0.1,0.25,1)]
          ${isSidebarOpen ? 'w-64' : 'w-14'}
        `}
      >
          <div className={`h-14 bg-white border-b border-slate-100/60 flex items-center shrink-0 ${isSidebarOpen ? 'px-4 justify-between' : 'px-2 justify-center'}`}>
            <button
              onClick={() => setIsSidebarOpen(true)}
              className={`flex items-center gap-3 rounded-lg transition-colors ${isSidebarOpen ? 'cursor-default' : 'hover:bg-indigo-50 p-1'}`}
              title={isSidebarOpen ? 'ai + draw.io' : 'Expand sidebar'}
            >
              <div className="bg-indigo-600 p-1.5 rounded-lg shadow-sm shadow-indigo-200">
                <svg xmlns="http://www.w3.org/2000/svg" width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="white" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                   <rect x="3" y="3" width="18" height="18" rx="2" ry="2"></rect>
                   <circle cx="8.5" cy="8.5" r="1.5"></circle>
                   <polyline points="21 15 16 10 5 21"></polyline>
                </svg>
              </div>
              {isSidebarOpen && <h1 className="text-lg font-bold text-slate-800 tracking-tight">ai + draw.io</h1>}
            </button>
            {isSidebarOpen && (
              <button
                onClick={() => setIsSidebarOpen(false)}
                className="p-1.5 text-slate-400 hover:text-slate-600 hover:bg-slate-100 rounded-md transition-all"
                title="Collapse sidebar"
              >
                <Icons.ChevronLeft className="w-4 h-4" />
              </button>
            )}
          </div>
          {isSidebarOpen ? (
            <>
              <div className="h-14 px-4 flex items-center justify-between border-b border-slate-100 shrink-0">
                 <span className="font-semibold text-slate-800 flex items-center gap-2">
                    <Icons.MessageSquare className="w-4 h-4 text-indigo-600" />
                    Diagram History
                 </span>
                 <button
                    onClick={handleNewChat}
                    className="p-1.5 text-slate-400 hover:text-indigo-600 hover:bg-indigo-50 rounded-md transition-all"
                    title="New Chat"
                 >
                    <Icons.Plus className="w-5 h-5" />
                 </button>
              </div>
              <div className="flex-1 overflow-y-auto p-2 space-y-1 scrollbar-thin scrollbar-thumb-slate-200 scrollbar-track-transparent">
                 {[...sessions].sort((a, b) => b.lastModified - a.lastModified).map(session => (
                    <div
                      key={session.id}
                      onClick={() => handleSwitchSession(session.id)}
                      onDoubleClick={(e) => { e.stopPropagation(); handleDoubleClickSession(session); }}
                      className={`
                        group flex items-center gap-3 px-3 py-3 rounded-lg cursor-pointer transition-all border border-transparent
                        ${currentSessionId === session.id
                          ? 'bg-indigo-50 text-indigo-700 border-indigo-100 shadow-sm'
                          : 'hover:bg-slate-50 text-slate-600 hover:text-slate-900'
                        }
                      `}
                    >
                      <div className="flex-1 min-w-0">
                         <div className={`text-sm font-medium truncate ${currentSessionId === session.id ? 'text-indigo-700' : 'text-slate-700 group-hover:text-slate-900'}`}>
                            {session.title}
                         </div>
                         <div className={`text-[10px] mt-0.5 ${currentSessionId === session.id ? 'text-indigo-400' : 'text-slate-400'}`}>
                            {new Date(session.lastModified).toLocaleDateString()} {new Date(session.lastModified).toLocaleTimeString([], {hour: '2-digit', minute:'2-digit'})}
                         </div>
                      </div>
                      <button
                        onClick={(e) => {
                          e.stopPropagation();
                          openRenameSession(session);
                        }}
                        className={`
                          p-1.5 rounded-md transition-all opacity-0 group-hover:opacity-100
                          ${currentSessionId === session.id
                            ? 'hover:bg-indigo-100 text-indigo-400 hover:text-indigo-700'
                            : 'hover:bg-slate-100 text-slate-400 hover:text-slate-600'
                          }
                        `}
                        title="Rename"
                      >
                        <Icons.Edit className="w-4 h-4" />
                      </button>
                      <button
                        onClick={(e) => handleDeleteSession(e, session.id)}
                        className={`
                          p-1.5 rounded-md transition-all opacity-0 group-hover:opacity-100
                          ${currentSessionId === session.id
                            ? 'hover:bg-indigo-100 text-indigo-400 hover:text-indigo-700'
                            : 'hover:bg-red-50 text-slate-400 hover:text-red-500'
                          }
                        `}
                        title="Delete"
                      >
                        <Icons.Trash className="w-4 h-4" />
                      </button>
                    </div>
                 ))}
                 {sessions.length === 0 && (
                    <div className="text-center py-10 text-xs text-slate-400">
                        No history yet
                    </div>
                 )}
              </div>
            </>
          ) : (
            <div className="flex-1 flex flex-col items-center gap-2 py-3">
              {/* Keep essential sidebar actions reachable while the full history list is hidden. */}
              <button
                onClick={() => setIsSidebarOpen(true)}
                className="p-2 text-slate-400 hover:text-indigo-600 hover:bg-indigo-50 rounded-lg transition-all"
                title="Expand sidebar"
              >
                <Icons.ChevronRight className="w-5 h-5" />
              </button>
              <button
                onClick={handleNewChat}
                className="p-2 text-slate-400 hover:text-indigo-600 hover:bg-indigo-50 rounded-lg transition-all"
                title="New Chat"
              >
                <Icons.Plus className="w-5 h-5" />
              </button>
              <button
                onClick={() => setIsSidebarOpen(true)}
                className="p-2 text-slate-400 hover:text-indigo-600 hover:bg-indigo-50 rounded-lg transition-all"
                title="Diagram History"
              >
                <Icons.MessageSquare className="w-5 h-5" />
              </button>
            </div>
          )}
        </div>

      {/* Main Layout */}
      <div className="flex flex-1 min-w-0 h-full overflow-hidden relative">
        {isResizingChat && (
          <div className="fixed inset-0 z-50 cursor-col-resize bg-transparent" />
        )}

        {/* Keep the assistant toggle available after the chat panel is collapsed. */}
        {!isChatOpen && (
          <button
            onClick={() => setIsChatOpen(true)}
            className="absolute top-3 right-3 z-40 p-2 text-indigo-600 bg-white hover:bg-indigo-50 rounded-lg transition-colors border border-indigo-100 shadow-sm"
            title="Open Assistant"
          >
            <Icons.Chat />
          </button>
        )}

        {/* Draw.io Canvas Area */}
        <div className="flex-1 min-w-0 relative bg-slate-50 h-full flex flex-col">
          <div className="flex-1 m-2 rounded-2xl overflow-hidden border border-slate-200 shadow-sm bg-white ring-1 ring-slate-100">
            <DrawIoEmbed 
              key={editorInstanceKey}
              ref={drawioRef}
              xml={editorXml}
              autosave={true}
              onAutoSave={(data) => {
                if (currentSessionId && isDrawIoReady && !isExportingForChatRef.current) {
                   // Prefer using the XML directly from the autosave event if available
                   if (data && typeof data === 'object' && 'xml' in data) {
                       const xmlContent = typeof data.xml === 'string' ? data.xml : '';
                       saveCurrentCanvasXml(xmlContent);
                   } else {
                       // Fallback to export if no XML provided in event
                        isAutosaveRef.current = true;
                        drawioRef.current?.exportDiagram({ format: 'xmlsvg' });
                    }
                }
              }}
              onLoad={() => setIsDrawIoReady(true)}
              onExport={(data) => setLastExportedData({ data: data.data, xml: data.xml, timestamp: Date.now() })}
              urlParameters={{
                ui: 'atlas', // More modern UI theme for draw.io
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
            className="group relative z-30 w-2 shrink-0 cursor-col-resize bg-slate-50 hover:bg-indigo-50 transition-colors"
            title="Drag to resize the assistant panel"
          >
            <div className="absolute inset-y-0 left-1/2 w-px -translate-x-1/2 bg-slate-200 group-hover:bg-indigo-300 transition-colors" />
            <div className="absolute top-1/2 left-1/2 h-10 w-1 -translate-x-1/2 -translate-y-1/2 rounded-full bg-slate-300 opacity-0 group-hover:opacity-100 transition-opacity" />
          </div>
        )}

        {/* Chat Sidebar - Modern & Elegant */}
        <div 
          className={`
            relative border-l border-slate-100/60 bg-white flex flex-col ease-[cubic-bezier(0.25,0.1,0.25,1)]
            ${isResizingChat ? 'transition-none' : 'transition-all duration-300'}
            ${isChatOpen ? 'translate-x-0' : 'translate-x-full opacity-0 overflow-hidden'}
            shadow-xl z-20
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
            className="absolute top-3 right-3 z-10 p-1.5 text-slate-400 hover:text-slate-600 hover:bg-slate-100 rounded-md transition-all shrink-0"
            title="Collapse assistant"
          >
            <Icons.Close className="w-5 h-5" />
          </button>

          {/* Messages Area */}
          <div className="flex-1 overflow-y-auto p-5 pr-14 space-y-6 bg-slate-50/50 scrollbar-thin scrollbar-thumb-slate-200 scrollbar-track-transparent">
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
                    shrink-0 w-8 h-8 rounded-full flex items-center justify-center shadow-sm mt-1 ring-2 ring-white
                    ${msg.role === 'user' 
                      ? 'bg-indigo-100 text-indigo-600' 
                      : 'bg-white text-indigo-500 border border-slate-100'
                    }
                  `}>
                    {msg.role === 'user' ? <Icons.User className="w-5 h-5" /> : <Icons.Bot className="w-5 h-5" />}
                  </div>
                  
                  <div className="flex flex-col max-w-[85%] w-full">
                      <span className={`text-[10px] mb-1.5 font-medium ${msg.role === 'user' ? 'text-right text-slate-400' : 'text-left text-slate-400'}`}>
                          {msg.role === 'user' ? 'You' : 'Agent'}
                      </span>
                      
                      <div className={`flex flex-col gap-2 ${msg.role === 'user' ? 'items-end' : 'items-start'}`}>
                        {showAgentProgressCard && (
                          <AgentProgressMessage message={msg} isRunning={isLatestRunningAgent} />
                        )}

                        {/* Steps / Reasoning Block */}
                        {msg.role === 'agent' && (visibleExecutionSteps.length > 0 || (isLatestRunningAgent && msg.reasoning)) && (
                          <div className="w-full max-w-full">
                            <details className="w-full group/details open:pb-2" open={index === messages.length - 1 && isSending}>
                              <summary className="inline-flex items-center gap-2 cursor-pointer text-xs text-slate-500 hover:text-slate-700 font-medium select-none bg-white border border-slate-200 px-3 py-1.5 rounded-lg shadow-sm transition-all hover:border-slate-300">
                                 <Icons.Sparkles className="w-3.5 h-3.5 text-indigo-400" />
                                 <span className="group-open/details:hidden">Show execution steps</span>
                                 <span className="hidden group-open/details:inline">Hide execution steps</span>
                              </summary>
                              <div className="mt-2 flex flex-col gap-2 p-3 bg-slate-50/50 border border-slate-200 rounded-xl shadow-sm text-sm text-slate-600 max-w-none overflow-x-auto">
                                 {visibleExecutionSteps.length > 0 ? (
                                   visibleExecutionSteps.map((step, idx) => (
                                     <div key={idx} className="flex flex-col gap-1.5 p-2 bg-white rounded-lg border border-slate-100 shadow-sm">
                                         <div className="flex items-center gap-2 font-medium text-slate-700">
                                             <Icons.Loader className="w-3.5 h-3.5 text-indigo-500" />
                                             <span>{step.label}</span>
                                         </div>
                                         {step.content && (
                                             <div className="text-xs text-slate-500 pl-6 border-l-2 border-slate-100 ml-1.5 prose prose-sm prose-slate max-w-none prose-p:my-1 prose-pre:my-2 prose-pre:bg-slate-100 prose-pre:text-slate-700">
                                               <ReactMarkdown remarkPlugins={[remarkGfm]}>{formatStepContent(step.content, step.phase)}</ReactMarkdown>
                                           </div>
                                         )}
                                     </div>
                                   ))
                                 ) : (
                                   <div className="p-2 bg-white rounded-lg border border-slate-100 shadow-sm prose prose-sm prose-slate max-w-none prose-p:my-1 prose-pre:my-2 prose-pre:bg-slate-100 prose-pre:text-slate-700">
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
                                ? 'bg-indigo-600 text-white rounded-2xl rounded-tr-sm shadow-indigo-200 whitespace-pre-wrap' 
                                : 'bg-white border border-slate-200 text-slate-700 rounded-2xl rounded-tl-sm shadow-sm prose prose-sm prose-slate max-w-none overflow-x-auto prose-p:my-1.5 prose-ol:my-2 prose-ul:my-2 prose-li:my-1 prose-pre:my-2 prose-pre:bg-slate-100 prose-pre:text-slate-700'
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
                           <div className="flex gap-1 items-center px-4 py-3 text-sm shadow-sm bg-white border border-indigo-100 text-indigo-600 rounded-2xl rounded-tl-sm">
                             <span className="w-1.5 h-1.5 rounded-full bg-indigo-500 animate-bounce" style={{ animationDelay: '0ms' }}></span>
                             <span className="w-1.5 h-1.5 rounded-full bg-indigo-500 animate-bounce" style={{ animationDelay: '150ms' }}></span>
                             <span className="w-1.5 h-1.5 rounded-full bg-indigo-500 animate-bounce" style={{ animationDelay: '300ms' }}></span>
                           </div>
                        )}
                      </div>
                  </div>
                </div>
              );
            })}

            <div ref={messagesEndRef} />
          </div>

          {/* Input Area */}
          <div className="p-4 bg-white border-t border-slate-100 shrink-0 relative z-20 shadow-[0_-4px_6px_-1px_rgba(0,0,0,0.02)]">
            {/* Quick Actions - Only show when chat is empty (just greeting) */}
            {messages.length <= 1 && (
              <div className="flex flex-wrap gap-2 mb-3 px-1 animate-in fade-in slide-in-from-bottom-2 duration-300">
                {quickActions.map((action, idx) => (
                  <button
                    key={idx}
                    onClick={() => {
                      sendContent(action.text);
                    }}
                    className="text-xs px-3 py-1.5 bg-indigo-50 text-indigo-600 rounded-full hover:bg-indigo-100 transition-colors border border-indigo-100 font-medium shadow-sm"
                  >
                    {action.label}
                  </button>
                ))}
              </div>
            )}

            {/* Model and loop controls */}
            <div className="flex flex-wrap items-center gap-2 mb-2 px-1">
                <div className="relative flex items-center bg-white border border-slate-200 rounded-full shadow-sm hover:border-slate-300 transition-colors">
                    <Icons.Sparkles className={`w-3 h-3 ml-2 ${selectedCustomModelId !== 'default' ? 'text-indigo-500' : 'text-slate-400'}`} />
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
                        className="appearance-none bg-transparent border-none text-[11px] font-medium text-slate-600 focus:ring-0 py-1 pl-1 pr-5 cursor-pointer outline-none"
                    >
                        <option value="default">Default Model</option>
                        {customModels.filter(m => m.enabled).map(m => (
                            <option key={m.id} value={m.id}>{m.name || m.model}</option>
                        ))}
                        <option disabled>──────────</option>
                        <option value="add_new">+ Manage Models</option>
                    </select>
                    <div className="pointer-events-none absolute inset-y-0 right-0 flex items-center px-1.5 text-slate-400">
                        <svg className="fill-current h-3 w-3" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path d="M9.293 12.95l.707.707L15.657 8l-1.414-1.414L10 10.828 5.757 6.586 4.343 8z"/></svg>
                    </div>
                </div>
                <div className="relative flex items-center bg-white border border-slate-200 rounded-full shadow-sm hover:border-slate-300 transition-colors">
                    <span className="text-[11px] font-medium text-slate-500 pl-3">Max Loops</span>
                    <select
                        value={maxReviewIterations}
                        onChange={(e) => {
                            const nextValue = Number(e.target.value);
                            setMaxReviewIterations(nextValue);
                            localStorage.setItem(MAX_REVIEW_ITERATIONS_STORAGE_KEY, String(nextValue));
                        }}
                        className="appearance-none bg-transparent border-none text-[11px] font-medium text-slate-600 focus:ring-0 py-1 pl-1 pr-5 cursor-pointer outline-none"
                    >
                        {REVIEW_ITERATION_OPTIONS.map(count => (
                            <option key={count} value={count}>{count}x</option>
                        ))}
                    </select>
                    <div className="pointer-events-none absolute inset-y-0 right-0 flex items-center px-1.5 text-slate-400">
                        <svg className="fill-current h-3 w-3" xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><path d="M9.293 12.95l.707.707L15.657 8l-1.414-1.414L10 10.828 5.757 6.586 4.343 8z"/></svg>
                    </div>
                </div>
            </div>

            {selectedSkills.length > 0 && (
              <div className="flex flex-wrap gap-1.5 mb-2">
                {selectedSkills.map(name => (
                  <span key={name} className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full bg-indigo-50 text-indigo-700 text-xs border border-indigo-200">
                    /{name}
                    <button type="button" onClick={() => removeSkill(name)} className="text-indigo-400 hover:text-indigo-700 leading-none">×</button>
                  </span>
                ))}
              </div>
            )}

            <div className="relative flex items-end gap-2 bg-slate-50 p-2 rounded-2xl border border-slate-200 focus-within:border-indigo-400 focus-within:ring-4 focus-within:ring-indigo-100 focus-within:bg-white transition-all shadow-sm">
              {slashOpen && filteredSkills.length > 0 && (
                <div className="absolute bottom-full left-0 mb-2 w-80 max-h-72 overflow-auto rounded-xl border border-slate-200 bg-white shadow-lg z-50 py-1">
                  {filteredSkills.map((s, i) => (
                    <button
                      key={s.name}
                      type="button"
                      onMouseDown={(e) => { e.preventDefault(); chooseSkill(s.name); }}
                      onMouseEnter={() => setSlashIndex(i)}
                      className={`block w-full text-left px-3 py-2 ${i === slashIndex ? 'bg-slate-100' : ''} hover:bg-slate-100`}
                    >
                      <div className="text-sm font-medium text-slate-800">/{s.name}</div>
                      {s.description && <div className="text-xs text-slate-500 truncate">{s.description}</div>}
                    </button>
                  ))}
                </div>
              )}
              <textarea
                value={inputValue}
                onChange={(e) => {
                  handleInputChange(e.target.value);
                  e.target.style.height = 'auto';
                  e.target.style.height = Math.min(e.target.scrollHeight, 300) + 'px';
                }}
                onKeyDown={handleKeyDown}
                placeholder={isSending ? "AI is generating..." : "Ask a question or describe your diagram request..."}
                disabled={isSending}
                className="flex-1 px-4 py-3 bg-transparent border-none focus:ring-0 text-[15px] text-slate-800 placeholder:text-slate-400 resize-none max-h-[300px] min-h-[80px] scrollbar-thin scrollbar-thumb-slate-200 scrollbar-track-transparent disabled:opacity-50 disabled:cursor-not-allowed leading-relaxed"
                rows={1}
                style={{ height: 'auto', minHeight: '80px' }}
              />
              <div className="flex gap-1 mb-0.5 shrink-0">
                  {isSending ? (
                    <button
                      onClick={handleStopStream}
                      className="p-2.5 rounded-lg transition-all duration-200 flex items-center justify-center bg-red-100 text-red-600 hover:bg-red-200 shadow-sm"
                      title="Stop generation"
                    >
                      <Icons.Square className="w-4 h-4" />
                    </button>
                  ) : (
                    <button
                      onClick={handleSendMessage}
                      disabled={!inputValue.trim()}
                      className={`
                        p-2.5 rounded-lg transition-all duration-200 flex items-center justify-center
                        ${inputValue.trim()
                          ? 'bg-indigo-600 text-white shadow-md shadow-indigo-200 hover:bg-indigo-700 hover:scale-105 active:scale-95' 
                          : 'bg-slate-200 text-slate-400 cursor-not-allowed'
                        }
                      `}
                      title="Send message"
                    >
                      <Icons.Send className="w-4 h-4" />
                    </button>
                  )}
                  <button
                    onClick={handleRestartSession}
                    disabled={isSending}
                    className="p-2.5 rounded-lg bg-white text-slate-400 hover:bg-slate-50 hover:text-indigo-600 transition-all duration-200 border border-slate-200 hover:border-indigo-100 shadow-sm disabled:opacity-50 disabled:cursor-not-allowed"
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
        <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm flex items-center justify-center z-50 p-6 animate-in fade-in duration-200">
            <div className="bg-white p-0 rounded-2xl shadow-2xl max-h-[90vh] flex flex-col w-full max-w-4xl overflow-hidden animate-in zoom-in-95 duration-200 border border-white/20">
                <div className="flex justify-between items-center px-6 py-4 border-b border-slate-100 bg-slate-50/50">
                    <div className="flex items-center gap-3">
                        <div className="p-2 bg-green-100 text-green-600 rounded-lg">
                            <Icons.Download className="w-5 h-5" />
                        </div>
                        <div>
                            <h2 className="text-lg font-bold text-slate-800">Export Ready</h2>
                            <p className="text-xs text-slate-500">Your diagram has been successfully converted</p>
                        </div>
                    </div>
                    <button 
                        onClick={() => setImgData(null)}
                        className="p-2 text-slate-400 hover:text-slate-600 hover:bg-slate-100 rounded-full transition-colors"
                    >
                        <Icons.Close className="w-5 h-5" />
                    </button>
                </div>
                
                <div className="flex-1 overflow-auto bg-slate-50/50 p-8 flex items-center justify-center min-h-[400px]">
                    <div className="bg-white p-2 rounded shadow-sm border border-slate-200">
                        <img src={imgData} alt="Exported diagram" className="max-w-full h-auto object-contain" />
                    </div>
                </div>
                
                <div className="px-6 py-4 border-t border-slate-100 bg-white flex justify-end gap-3">
                   <button 
                        onClick={() => setImgData(null)}
                        className="px-5 py-2.5 text-slate-600 font-medium hover:bg-slate-100 rounded-lg transition-colors text-sm"
                    >
                        Close Preview
                    </button>
                    <a 
                        href={imgData} 
                        download="diagram.svg"
                        className="px-5 py-2.5 bg-indigo-600 text-white font-medium rounded-lg hover:bg-indigo-700 shadow-lg shadow-indigo-200 hover:shadow-indigo-300 transition-all text-sm flex items-center gap-2"
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
        <div className="absolute inset-0 bg-slate-900/60 backdrop-blur-sm flex items-center justify-center z-50 p-6 animate-in fade-in duration-200">
            <div className="bg-white rounded-2xl shadow-2xl w-full max-w-md overflow-hidden animate-in zoom-in-95 duration-200 border border-white/20">
                <div className="px-6 py-4 border-b border-slate-100 bg-slate-50/50 flex justify-between items-center">
                    <h2 className="text-lg font-bold text-slate-800">Rename Session</h2>
                    <button 
                        onClick={handleRenameCancel}
                        className="p-1 text-slate-400 hover:text-slate-600 hover:bg-slate-100 rounded-full transition-colors"
                        title="Close"
                    >
                        <Icons.Close className="w-5 h-5" />
                    </button>
                </div>
                
                <div className="p-6">
                    <label className="block text-sm font-medium text-slate-700 mb-2">
                        Session Name
                    </label>
                    <input 
                        type="text" 
                        value={newSessionTitle}
                        onChange={(e) => setNewSessionTitle(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === 'Enter') handleRenameSave();
                          if (e.key === 'Escape') handleRenameCancel();
                        }}
                        className="w-full px-4 py-2 border border-slate-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all"
                        placeholder="Enter a new session name"
                        autoFocus
                    />
                </div>
                
                <div className="px-6 py-4 border-t border-slate-100 bg-slate-50/50 flex justify-end gap-3">
                   <button 
                        onClick={handleRenameCancel}
                        className="px-4 py-2 text-slate-600 font-medium hover:bg-slate-100 rounded-lg transition-colors text-sm"
                    >
                        Cancel
                    </button>
                    <button 
                        onClick={handleRenameSave}
                        disabled={!newSessionTitle.trim()}
                        className="px-4 py-2 bg-indigo-600 text-white font-medium rounded-lg hover:bg-indigo-700 shadow-lg shadow-indigo-200 hover:shadow-indigo-300 transition-all text-sm disabled:opacity-50 disabled:cursor-not-allowed disabled:hover:bg-indigo-600"
                    >
                        Save
                    </button>
                </div>
            </div>
        </div>
      )}
      {/* Custom Models Settings Modal */}
      {showApiConfig && (
        <div className="fixed inset-0 bg-slate-900/60 backdrop-blur-sm flex items-center justify-center z-50 p-6 animate-in fade-in duration-200">
            <div className="bg-white rounded-2xl shadow-2xl w-full max-w-2xl overflow-hidden animate-in zoom-in-95 duration-200 border border-white/20 flex flex-col max-h-[90vh]">
                <div className="px-6 py-4 border-b border-slate-100 bg-slate-50/50 flex justify-between items-center shrink-0">
                    <div className="flex items-center gap-2.5">
                      <div className="p-1.5 bg-indigo-50 rounded-lg">
                        <Icons.Sparkles className="w-4 h-4 text-indigo-500" />
                      </div>
                      <h2 className="text-base font-bold text-slate-800">Custom Model Settings</h2>
                    </div>
                    <button
                        onClick={() => {
                          setShowApiConfig(false);
                          setEditingModel(null);
                        }}
                        className="p-1.5 text-slate-400 hover:text-slate-600 hover:bg-slate-100 rounded-lg transition-colors"
                    >
                        <Icons.Close className="w-4 h-4" />
                    </button>
                </div>
                
                <div className="flex flex-1 overflow-hidden">
                    {/* List of Models */}
                    <div className="w-1/3 border-r border-slate-100 bg-slate-50 flex flex-col">
                        <div className="p-3">
                            <button 
                                onClick={handleAddNewModel}
                                className="w-full flex items-center justify-center gap-2 py-2 bg-white border border-indigo-200 text-indigo-600 rounded-lg hover:bg-indigo-50 transition-colors shadow-sm text-sm font-medium"
                            >
                                <Icons.Plus className="w-4 h-4" /> Add Model
                            </button>
                        </div>
                        <div className="flex-1 overflow-y-auto p-3 space-y-2">
                            {customModels.map(model => (
                                <div 
                                    key={model.id}
                                    onClick={() => setEditingModel(model)}
                                    className={`p-3 rounded-xl border cursor-pointer transition-all ${editingModel?.id === model.id ? 'bg-indigo-50 border-indigo-200 shadow-sm ring-1 ring-indigo-100' : 'bg-white border-slate-200 hover:border-indigo-100 hover:shadow-sm'}`}
                                >
                                    <div className="flex items-center justify-between mb-1">
                                        <div className="font-semibold text-sm text-slate-800 truncate pr-2">{model.name}</div>
                                        <div className="flex items-center gap-1 shrink-0">
                                            {/* Toggle Switch */}
                                            <button 
                                                onClick={(e) => {
                                                    e.stopPropagation();
                                                    const newModels = customModels.map(m => m.id === model.id ? {...m, enabled: !m.enabled} : m);
                                                    saveCustomModels(newModels);
                                                    if (!(!model.enabled) && selectedCustomModelId === model.id) {
                                                        setSelectedCustomModelId('default');
                                                    }
                                                }}
                                                className={`relative inline-flex h-4 w-7 items-center rounded-full transition-colors focus:outline-none ${model.enabled ? 'bg-indigo-500' : 'bg-slate-300'}`}
                                            >
                                                <span className={`inline-block h-3 w-3 transform rounded-full bg-white transition-transform ${model.enabled ? 'translate-x-3.5' : 'translate-x-0.5'}`} />
                                            </button>
                                            <button onClick={(e) => { e.stopPropagation(); handleDeleteModel(model.id); }} className="text-slate-400 hover:text-red-500 ml-1">
                                                <Icons.Trash className="w-3.5 h-3.5" />
                                            </button>
                                        </div>
                                    </div>
                                    <div className="text-[10px] text-slate-500 truncate">{model.model}</div>
                                </div>
                            ))}
                            {customModels.length === 0 && (
                                <div className="text-center text-xs text-slate-400 py-6">
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
                                    <label className="block text-xs font-medium text-slate-700 mb-1">Display Name</label>
                                    <input type="text" value={editingModel.name} onChange={e => setEditingModel({...editingModel, name: e.target.value})} className="w-full px-3 py-2 text-sm border border-slate-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all" placeholder="Example: My GPT-4o" />
                                </div>
                                <div>
                                    <label className="block text-xs font-medium text-slate-700 mb-1">Model Name</label>
                                    <input type="text" value={editingModel.model} onChange={e => setEditingModel({...editingModel, model: e.target.value})} className="w-full px-3 py-2 text-sm border border-slate-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all" placeholder="Example: gpt-4o" />
                                </div>
                                <div>
                                    <label className="block text-xs font-medium text-slate-700 mb-1">Base URL</label>
                                    <input type="text" value={editingModel.baseUrl} onChange={e => setEditingModel({...editingModel, baseUrl: e.target.value})} className="w-full px-3 py-2 text-sm border border-slate-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all" placeholder="Example: https://api.openai.com" />
                                </div>
                                <div>
                                    <label className="block text-xs font-medium text-slate-700 mb-1">API Key</label>
                                    <input type="password" value={editingModel.apiKey} onChange={e => setEditingModel({...editingModel, apiKey: e.target.value})} className="w-full px-3 py-2 text-sm border border-slate-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all" placeholder="sk-..." />
                                </div>
                                <div>
                                    <label className="block text-xs font-medium text-slate-700 mb-1">Completions Path (optional)</label>
                                    <input type="text" value={editingModel.completionsPath} onChange={e => setEditingModel({...editingModel, completionsPath: e.target.value})} className="w-full px-3 py-2 text-sm border border-slate-300 rounded-lg focus:ring-2 focus:ring-indigo-500 focus:border-indigo-500 outline-none transition-all" placeholder="Default: v1/chat/completions" />
                                </div>
                                <div className="pt-2 flex justify-end">
                                    <button onClick={handleSaveEditingModel} className="px-6 py-2 bg-indigo-600 text-white font-medium rounded-lg hover:bg-indigo-700 shadow-sm transition-all text-sm">
                                        Save Settings
                                    </button>
                                </div>
                            </div>
                        ) : (
                            <div className="h-full flex flex-col items-center justify-center text-slate-400">
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
