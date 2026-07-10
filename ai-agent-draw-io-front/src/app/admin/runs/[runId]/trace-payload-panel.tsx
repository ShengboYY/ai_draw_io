'use client';

import { useMemo, useState } from 'react';
import type { AdminDebugTraceCaptureDTO } from '@/types/api';

type PayloadBucket = 'input' | 'output' | 'other';

export function TracePayloadPanel({
  payloads,
  loading,
  error,
}: {
  payloads?: AdminDebugTraceCaptureDTO[];
  loading: boolean;
  error?: string;
}) {
  const buckets = useMemo(() => groupPayloads(payloads || []), [payloads]);

  if (loading) {
    return <div className="mt-4 rounded-lg bg-stone-50 px-3 py-4 text-xs text-zinc-500">Loading span I/O…</div>;
  }
  if (error) {
    return <div className="mt-4 rounded-lg bg-rose-50 px-3 py-3 text-xs text-rose-700">{error}</div>;
  }
  if (!payloads) {
    return null;
  }
  if (payloads.length === 0) {
    return (
      <div className="mt-4 rounded-lg border border-dashed border-stone-200 px-3 py-4 text-xs text-zinc-500">
        <span className="mr-2 rounded bg-stone-100 px-1.5 py-0.5 font-medium text-zinc-600">Not captured</span>
        Enable payload capture and run the request again to record this span&apos;s input and output.
      </div>
    );
  }

  return (
    <div className="mt-4 space-y-3">
      <PayloadSection title="Input" items={buckets.input} empty="No input payload was recorded." />
      <PayloadSection title="Output" items={buckets.output} empty="No output payload was recorded." />
      {buckets.other.length > 0 && <PayloadSection title="Related evidence" items={buckets.other} />}
    </div>
  );
}

function PayloadSection({
  title,
  items,
  empty,
}: {
  title: string;
  items: AdminDebugTraceCaptureDTO[];
  empty?: string;
}) {
  return (
    <details open className="group rounded-lg border border-stone-200 bg-white">
      <summary className="flex cursor-pointer list-none items-center justify-between px-3 py-2 text-xs font-semibold text-zinc-700">
        <span>{title}</span>
        <span className="font-mono text-[10px] font-normal text-zinc-400">{items.length}</span>
      </summary>
      <div className="space-y-2 border-t border-stone-100 p-2">
        {items.length === 0 && <div className="px-1 py-2 text-xs text-zinc-400">{empty || 'No payload.'}</div>}
        {items.map((item) => <PayloadCard key={item.id} item={item} />)}
      </div>
    </details>
  );
}

function PayloadCard({ item }: { item: AdminDebugTraceCaptureDTO }) {
  const missingContent = item.content == null;
  const expired = missingContent && isExpired(item.contentExpiresAt);
  const redacted = missingContent && !expired;
  const status = expired ? 'Expired' : redacted ? 'Redacted' : item.truncated ? 'Truncated' : 'Captured';
  const statusClass = expired || redacted
    ? 'bg-stone-100 text-zinc-500'
    : item.truncated
      ? 'bg-amber-50 text-amber-700'
      : 'bg-emerald-50 text-emerald-700';
  const facts = item.content ? extractPayloadFacts(parseJson(item.content)) : [];

  return (
    <div className="overflow-hidden rounded-md bg-stone-50">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b border-stone-100 px-2.5 py-2">
        <div className="flex items-center gap-2">
          <span className="font-mono text-[10px] font-medium text-zinc-600">
            {item.payloadKind || item.eventType || 'PAYLOAD'}
          </span>
          <span className={`rounded px-1.5 py-0.5 text-[10px] font-medium ${statusClass}`}>{status}</span>
        </div>
        <div className="flex items-center gap-2 font-mono text-[10px] text-zinc-400">
          <span>{item.contentType || 'text/plain'}</span>
          {item.originalLength != null && <span>{item.originalLength.toLocaleString()} chars</span>}
        </div>
      </div>
      {expired ? (
        <div className="px-3 py-3 text-xs text-zinc-400">Content expired under the debug-trace retention policy.</div>
      ) : redacted ? (
        <div className="px-3 py-3 text-xs text-zinc-400">Content was removed while audit metadata was retained.</div>
      ) : (
        <>
          {facts.length > 0 && (
            <div className="flex flex-wrap gap-1.5 border-b border-stone-100 px-2.5 py-2">
              {facts.map((fact) => (
                <span key={fact.label} className="rounded bg-white px-1.5 py-1 font-mono text-[10px] text-zinc-500">
                  {fact.label}: {fact.value}
                </span>
              ))}
            </div>
          )}
          <SmartPayload
            content={item.content || ''}
            contentType={item.contentType}
            payloadLabel={item.payloadKind || item.eventType || 'Payload'}
          />
        </>
      )}
    </div>
  );
}

function isExpired(expiresAt?: string): boolean {
  if (!expiresAt) return false;
  const expiry = Date.parse(expiresAt);
  return Number.isFinite(expiry) && expiry <= Date.now();
}

function SmartPayload({
  content,
  contentType,
  payloadLabel,
}: {
  content: string;
  contentType?: string;
  payloadLabel: string;
}) {
  const parsed = parseJson(content);
  const xml = Boolean(contentType?.includes('xml') || content.trim().startsWith('<'));
  const [view, setView] = useState<'rendered' | 'raw'>('rendered');
  const [query, setQuery] = useState('');
  const [expanded, setExpanded] = useState(true);
  const [fullscreen, setFullscreen] = useState(false);
  const [copyStatus, setCopyStatus] = useState<'idle' | 'copied' | 'failed'>('idle');
  const supportsRenderedView = parsed != null || xml;
  const renderedText = parsed != null ? JSON.stringify(parsed, null, 2) : xml ? formatXml(content) : content;
  const matchCount = countMatches(view === 'raw' ? content : renderedText, query);

  const copyContent = async () => {
    try {
      // Copy is deliberately user-triggered; no payload content leaves the local browser.
      await navigator.clipboard.writeText(content);
      setCopyStatus('copied');
      window.setTimeout(() => setCopyStatus('idle'), 1200);
    } catch {
      setCopyStatus('failed');
    }
  };

  return (
    <div>
      <div className="flex flex-wrap items-center gap-1.5 border-b border-stone-100 px-2 py-1.5">
        {supportsRenderedView && (
          <div className="flex rounded bg-stone-100 p-0.5">
            {(['rendered', 'raw'] as const).map((mode) => (
              <button
                key={mode}
                type="button"
                onClick={() => setView(mode)}
                className={`rounded px-2 py-1 text-[10px] font-medium capitalize ${
                  view === mode ? 'bg-white text-zinc-800 shadow-sm' : 'text-zinc-500'
                }`}
              >
                {mode}
              </button>
            ))}
          </div>
        )}
        <label className="ml-auto flex min-w-36 flex-1 items-center rounded border border-stone-200 bg-white px-2 py-1 sm:max-w-52">
          <span className="sr-only">Search {payloadLabel}</span>
          <input
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search payload"
            className="min-w-0 flex-1 bg-transparent text-[10px] text-zinc-700 outline-none placeholder:text-zinc-400"
          />
          {query && <span className="font-mono text-[9px] text-zinc-400">{matchCount}</span>}
        </label>
        <button type="button" onClick={copyContent} className="rounded px-2 py-1 text-[10px] font-medium text-zinc-500 hover:bg-white hover:text-zinc-800">
          {copyStatus === 'copied' ? 'Copied' : copyStatus === 'failed' ? 'Copy failed' : 'Copy'}
        </button>
        <button type="button" onClick={() => setFullscreen(true)} className="rounded px-2 py-1 text-[10px] font-medium text-zinc-500 hover:bg-white hover:text-zinc-800">
          Fullscreen
        </button>
        <button type="button" onClick={() => setExpanded((value) => !value)} className="rounded px-2 py-1 text-[10px] font-medium text-zinc-500 hover:bg-white hover:text-zinc-800">
          {expanded ? 'Collapse' : 'Expand'}
        </button>
      </div>

      {expanded && <PayloadBody content={content} parsed={parsed} xml={xml} view={view} query={query} />}

      {fullscreen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/70 p-4" role="dialog" aria-modal="true" aria-label={`${payloadLabel} fullscreen payload`}>
          <div className="flex max-h-[92vh] w-full max-w-6xl flex-col overflow-hidden rounded-xl bg-white shadow-2xl">
            <div className="flex items-center justify-between gap-3 border-b border-stone-200 px-4 py-3">
              <div>
                <div className="text-sm font-semibold text-zinc-900">{payloadLabel}</div>
                <div className="mt-0.5 font-mono text-[10px] text-zinc-400">{view} · {matchCount} search matches</div>
              </div>
              <button type="button" onClick={() => setFullscreen(false)} className="rounded-md border border-stone-200 px-3 py-1.5 text-xs font-medium text-zinc-600 hover:bg-stone-50">
                Close
              </button>
            </div>
            <div className="min-h-0 flex-1 overflow-auto">
              <PayloadBody content={content} parsed={parsed} xml={xml} view={view} query={query} fullscreen />
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function PayloadBody({
  content,
  parsed,
  xml,
  view,
  query,
  fullscreen = false,
}: {
  content: string;
  parsed: unknown | null;
  xml: boolean;
  view: 'rendered' | 'raw';
  query: string;
  fullscreen?: boolean;
}) {
  const maxHeight = fullscreen ? 'max-h-none' : 'max-h-72';
  if (view === 'rendered' && parsed != null) {
    return <JsonPayload value={parsed} query={query} maxHeight={maxHeight} />;
  }
  const displayed = view === 'rendered' && xml ? formatXml(content) : content || '(empty)';
  return (
    <HighlightedPre
      text={displayed}
      query={query}
      className={`${maxHeight} overflow-auto whitespace-pre-wrap break-words p-3 font-mono text-[11px] leading-relaxed ${
        view === 'rendered' && xml ? 'text-emerald-800' : 'text-zinc-700'
      }`}
    />
  );
}

function JsonPayload({ value, query, maxHeight }: { value: unknown; query: string; maxHeight: string }) {
  const messages = extractMessages(value);
  if (messages.length > 0) {
    return (
      <div className={`${maxHeight} space-y-2 overflow-auto p-2`}>
        {messages.map((message, index) => (
          <div key={`${message.role}-${index}`} className="rounded border border-stone-200 bg-white px-2.5 py-2">
            <div className="mb-1 font-mono text-[10px] font-semibold uppercase text-zinc-400">{message.role}</div>
            <HighlightedPre text={message.body} query={query} className="whitespace-pre-wrap break-words font-mono text-[11px] leading-relaxed text-zinc-700" />
          </div>
        ))}
      </div>
    );
  }
  return (
    <div className={`${maxHeight} overflow-auto p-3 font-mono text-[11px] leading-relaxed text-zinc-700`}>
      <JsonTree value={value} query={query} />
    </div>
  );
}

function JsonTree({ value, query, label }: { value: unknown; query: string; label?: string }) {
  if (value == null || typeof value !== 'object') {
    return (
      <div className="pl-3">
        {label != null && <span className="text-sky-700">{label}: </span>}
        <HighlightedText text={JSON.stringify(value) ?? String(value)} query={query} />
      </div>
    );
  }
  const entries = Array.isArray(value)
    ? value.map((item, index) => [String(index), item] as const)
    : Object.entries(value as Record<string, unknown>);
  return (
    <details open className="pl-2">
      <summary className="cursor-pointer text-zinc-500">
        {label != null && <span className="text-sky-700">{label} </span>}
        <span>{Array.isArray(value) ? `[${entries.length}]` : `{${entries.length}}`}</span>
      </summary>
      <div className="border-l border-stone-200 pl-2">
        {entries.map(([key, item]) => <JsonTree key={key} value={item} label={key} query={query} />)}
      </div>
    </details>
  );
}

function HighlightedPre({ text, query, className }: { text: string; query: string; className: string }) {
  return <pre className={className}><HighlightedText text={text} query={query} /></pre>;
}

function HighlightedText({ text, query }: { text: string; query: string }) {
  if (!query.trim()) return text;
  const needle = query.toLowerCase();
  const lowerText = text.toLowerCase();
  const parts: Array<{ text: string; match: boolean }> = [];
  let cursor = 0;
  while (cursor < text.length) {
    const index = lowerText.indexOf(needle, cursor);
    if (index < 0) {
      parts.push({ text: text.slice(cursor), match: false });
      break;
    }
    if (index > cursor) parts.push({ text: text.slice(cursor, index), match: false });
    parts.push({ text: text.slice(index, index + needle.length), match: true });
    cursor = index + needle.length;
  }
  return parts.map((part, index) => part.match
    ? <mark key={index} className="rounded bg-amber-200 px-0.5 text-inherit">{part.text}</mark>
    : <span key={index}>{part.text}</span>);
}

function countMatches(text: string, query: string): number {
  const needle = query.trim().toLowerCase();
  if (!needle) return 0;
  let count = 0;
  let cursor = 0;
  const haystack = text.toLowerCase();
  while ((cursor = haystack.indexOf(needle, cursor)) >= 0) {
    count++;
    cursor += needle.length;
  }
  return count;
}

function groupPayloads(items: AdminDebugTraceCaptureDTO[]): Record<PayloadBucket, AdminDebugTraceCaptureDTO[]> {
  const grouped: Record<PayloadBucket, AdminDebugTraceCaptureDTO[]> = { input: [], output: [], other: [] };
  items.forEach((item) => {
    const kind = `${item.payloadKind || ''} ${item.eventType || ''}`.toUpperCase();
    if (kind.includes('INPUT') || kind.includes('TOOL_ARGS') || kind.includes('REQUEST') || kind.includes('ROUTED_MESSAGE')) grouped.input.push(item);
    else if (kind.includes('OUTPUT') || kind.includes('TOOL_RESULT') || kind.includes('RESPONSE')) grouped.output.push(item);
    else grouped.other.push(item);
  });
  return grouped;
}

function parseJson(content: string): unknown | null {
  try {
    return JSON.parse(content);
  } catch {
    return null;
  }
}

function extractPayloadFacts(value: unknown): Array<{ label: string; value: string }> {
  if (!value || typeof value !== 'object') return [];
  const root = value as Record<string, unknown>;
  const usage = objectValue(root.usageMetadata) || objectValue(root.usage);
  const facts: Array<{ label: string; value: string }> = [];
  const finishReason = scalarValue(root.finishReason)
    || scalarValue(objectValue(root.content)?.finishReason)
    || scalarValue(Array.isArray(root.candidates) ? objectValue(root.candidates[0])?.finishReason : undefined);
  if (finishReason) facts.push({ label: 'finish', value: finishReason });
  const cachedTokens = numberValue(usage?.cachedContentTokenCount) ?? numberValue(usage?.cacheReadInputTokens);
  if (cachedTokens != null) facts.push({ label: 'cache tokens', value: cachedTokens.toLocaleString() });
  const reasoningTokens = numberValue(usage?.thoughtsTokenCount) ?? numberValue(usage?.reasoningTokens);
  if (reasoningTokens != null) facts.push({ label: 'reasoning tokens', value: reasoningTokens.toLocaleString() });
  return facts;
}

function objectValue(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : undefined;
}

function scalarValue(value: unknown): string | undefined {
  return typeof value === 'string' || typeof value === 'number' ? String(value) : undefined;
}

function numberValue(value: unknown): number | undefined {
  return typeof value === 'number' && Number.isFinite(value) ? value : undefined;
}

function extractMessages(value: unknown): Array<{ role: string; body: string }> {
  if (!value || typeof value !== 'object') return [];
  const record = value as Record<string, unknown>;
  const candidates = Array.isArray(record.contents)
    ? record.contents
    : record.content && typeof record.content === 'object'
      ? [record.content]
      : [];
  return candidates.flatMap((candidate) => {
    if (!candidate || typeof candidate !== 'object') return [];
    const message = candidate as Record<string, unknown>;
    return [{
      role: typeof message.role === 'string' ? message.role : 'message',
      body: renderMessageBody(message.parts),
    }];
  });
}

function renderMessageBody(parts: unknown): string {
  if (!Array.isArray(parts)) return JSON.stringify(parts ?? '', null, 2);
  return parts.map((part) => {
    if (!part || typeof part !== 'object') return String(part ?? '');
    const value = part as Record<string, unknown>;
    if (typeof value.text === 'string') return value.text;
    return JSON.stringify(value, null, 2);
  }).join('\n');
}

function formatXml(content: string): string {
  // A lightweight formatter is sufficient for trace inspection and preserves the exact raw tab.
  return content.replace(/>\s*</g, '>\n<');
}
