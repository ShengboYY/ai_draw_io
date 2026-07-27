'use client';

import { useCallback, useEffect, useMemo, useState } from 'react';
import { API_CONFIG } from '@/config/api-config';
import { ChartbookApiError, createChartbookClient } from '@/api/chartbook';
import { createMaterialCapabilitiesClient } from '@/api/material-capabilities';
import { materialCapabilityMessage } from '@/features/materials/library-view';
import type { Chartbook, MaterialCapabilities } from '@/features/materials/material-types';

const idempotencyKey = () => globalThis.crypto.randomUUID();

export type ChartbookShelf = {
  capabilities: MaterialCapabilities | null;
  chartbooks: Chartbook[];
  isLoading: boolean;
  isAvailable: boolean;
  requiresSignIn: boolean;
  capabilityMessage: string | null;
  message: string | null;
  create: (name: string) => Promise<Chartbook | null>;
  groupDiagrams: (name: string, diagramIds: string[]) => Promise<Chartbook | null>;
  addDiagram: (chartbookId: string, diagramId: string) => Promise<boolean>;
  archive: (chartbook: Chartbook) => Promise<void>;
  reload: () => Promise<void>;
};

/**
 * One chartbook data source shared by the diagrams workspace tab and the standalone
 * /chartbooks page, so both surfaces keep the same create/archive contract.
 */
export const useChartbookShelf = (): ChartbookShelf => {
  const chartbookClient = useMemo(() => createChartbookClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const capabilitiesClient = useMemo(() => createMaterialCapabilitiesClient({ baseUrl: API_CONFIG.BASE_URL }), []);
  const [capabilities, setCapabilities] = useState<MaterialCapabilities | null>(null);
  const [chartbooks, setChartbooks] = useState<Chartbook[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [requiresSignIn, setRequiresSignIn] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const currentCapabilities = await capabilitiesClient.get();
      setCapabilities(currentCapabilities);
      if (currentCapabilities.catalog !== 'AVAILABLE') return;
      setChartbooks(await chartbookClient.list());
      setRequiresSignIn(false);
      setMessage(null);
    } catch (error) {
      // Anonymous workspaces are rejected by the catalog: a sign-in prompt, not a failure.
      const needsSignIn = error instanceof ChartbookApiError && error.code === 'REGISTERED_USER_REQUIRED';
      setRequiresSignIn(needsSignIn);
      if (needsSignIn) setChartbooks([]);
      setMessage(needsSignIn ? null : 'Unable to load chartbooks. Please try again later.');
    } finally {
      setIsLoading(false);
    }
  }, [capabilitiesClient, chartbookClient]);

  useEffect(() => {
    const timeoutId = window.setTimeout(() => { void load(); }, 0);
    return () => window.clearTimeout(timeoutId);
  }, [load]);

  const create = useCallback(async (name: string) => {
    const trimmed = name.trim();
    if (!trimmed) return null;
    try {
      const created = await chartbookClient.create(trimmed, idempotencyKey());
      setChartbooks(previous => [created, ...previous]);
      return created;
    } catch {
      setMessage('Unable to create the chartbook.');
      return null;
    }
  }, [chartbookClient]);

  /** Dropping one diagram onto another creates the folder, then moves both diagrams in. */
  const groupDiagrams = useCallback(async (name: string, diagramIds: string[]) => {
    const trimmed = name.trim();
    if (!trimmed) return null;
    try {
      let folder = await chartbookClient.create(trimmed, idempotencyKey());
      for (const diagramId of diagramIds) {
        folder = await chartbookClient.assignDiagram(diagramId, folder.chartbookId);
      }
      const created = folder;
      setChartbooks(previous => [created, ...previous.filter(item => item.chartbookId !== created.chartbookId)]);
      return created;
    } catch {
      setMessage('Unable to create the chartbook.');
      // A partially built folder may exist, so re-read the shelf instead of guessing.
      await load();
      return null;
    }
  }, [chartbookClient, load]);

  const addDiagram = useCallback(async (chartbookId: string, diagramId: string) => {
    try {
      const updated = await chartbookClient.assignDiagram(diagramId, chartbookId);
      setChartbooks(previous => previous.map(item => (item.chartbookId === chartbookId ? updated : item)));
      return true;
    } catch {
      setMessage('Unable to move that diagram into the chartbook.');
      return false;
    }
  }, [chartbookClient]);

  const archive = useCallback(async (chartbook: Chartbook) => {
    if (!window.confirm(`Archive "${chartbook.name}"?`)) return;
    try {
      await chartbookClient.archive(chartbook.chartbookId);
      setChartbooks(previous => previous.filter(item => item.chartbookId !== chartbook.chartbookId));
    } catch {
      setMessage('This chartbook does not exist or you do not have access.');
    }
  }, [chartbookClient]);

  return {
    capabilities,
    chartbooks,
    isLoading,
    isAvailable: capabilities?.catalog === 'AVAILABLE' && !requiresSignIn,
    requiresSignIn,
    capabilityMessage: capabilities ? materialCapabilityMessage(capabilities) : null,
    message,
    create,
    groupDiagrams,
    addDiagram,
    archive,
    reload: load,
  };
};
