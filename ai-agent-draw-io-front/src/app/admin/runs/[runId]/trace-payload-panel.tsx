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
          <SmartPayload content={item.content || ''} contentType={item.contentType} />
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

function SmartPayload({ content, contentType }: { content: string; contentType?: string }) {
  const parsed = parseJson(content);
  const xml = Boolean(contentType?.includes('xml') || content.trim().startsWith('<'));
  const [raw, setRaw] = useState(false);
  const supportsRenderedView = parsed != null || xml;

  return (
    <div>
      {supportsRenderedView && (
        <div className="flex justify-end border-b border-stone-100 px-2 py-1">
          <button
            type="button"
            onClick={() => setRaw((value) => !value)}
            className="rounded px-2 py-1 text-[10px] font-medium text-zinc-500 hover:bg-white hover:text-zinc-800"
          >
            {raw ? 'Rendered' : 'Raw'}
          </button>
        </div>
      )}
      {!raw && parsed != null ? (
        <JsonPayload value={parsed} />
      ) : !raw && xml ? (
        <pre className="max-h-72 overflow-auto whitespace-pre-wrap break-words p-3 font-mono text-[11px] leading-relaxed text-emerald-800">
          {formatXml(content)}
        </pre>
      ) : (
        <pre className="max-h-72 overflow-auto whitespace-pre-wrap break-words p-3 font-mono text-[11px] leading-relaxed text-zinc-700">
          {raw ? content : content || '(empty)'}
        </pre>
      )}
    </div>
  );
}

function JsonPayload({ value }: { value: unknown }) {
  const messages = extractMessages(value);
  if (messages.length > 0) {
    return (
      <div className="max-h-72 space-y-2 overflow-auto p-2">
        {messages.map((message, index) => (
          <div key={`${message.role}-${index}`} className="rounded border border-stone-200 bg-white px-2.5 py-2">
            <div className="mb-1 font-mono text-[10px] font-semibold uppercase text-zinc-400">{message.role}</div>
            <pre className="whitespace-pre-wrap break-words font-mono text-[11px] leading-relaxed text-zinc-700">{message.body}</pre>
          </div>
        ))}
      </div>
    );
  }
  return (
    <pre className="max-h-72 overflow-auto whitespace-pre-wrap break-words p-3 font-mono text-[11px] leading-relaxed text-zinc-700">
      {JSON.stringify(value, null, 2)}
    </pre>
  );
}

function groupPayloads(items: AdminDebugTraceCaptureDTO[]): Record<PayloadBucket, AdminDebugTraceCaptureDTO[]> {
  const grouped: Record<PayloadBucket, AdminDebugTraceCaptureDTO[]> = { input: [], output: [], other: [] };
  items.forEach((item) => {
    const kind = `${item.payloadKind || ''} ${item.eventType || ''}`.toUpperCase();
    if (kind.includes('INPUT') || kind.includes('REQUEST') || kind.includes('ROUTED_MESSAGE')) grouped.input.push(item);
    else if (kind.includes('OUTPUT') || kind.includes('RESPONSE')) grouped.output.push(item);
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
