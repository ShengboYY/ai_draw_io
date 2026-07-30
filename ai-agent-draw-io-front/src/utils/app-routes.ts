// Query parameters keep identifier-based client pages compatible with static export.
const withId = (path: string, key: string, value: string) =>
  `${path}?${key}=${encodeURIComponent(value)}`;

export const chartbookDetailsHref = (chartbookId: string) =>
  withId('/chartbooks/details/', 'chartbookId', chartbookId);

export const chartbookMemoryHref = (chartbookId: string) =>
  withId('/chartbooks/memory/', 'chartbookId', chartbookId);

export const materialDetailsHref = (materialId: string) =>
  withId('/library/details/', 'materialId', materialId);

export const evalCaseDetailsHref = (workingCopyId: string) =>
  withId('/admin/eval-cases/details/', 'workingCopyId', workingCopyId);

export const evalRunDetailsHref = (evalRunId: string) =>
  withId('/admin/eval-runs/details/', 'evalRunId', evalRunId);
