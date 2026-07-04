export const DIAGRAM_ACTION_MENU_ATTRIBUTE = 'data-diagram-action-menu';
export const ACCOUNT_MENU_ATTRIBUTE = 'data-account-menu';
export const SORT_MENU_ATTRIBUTE = 'data-sort-menu';

type ClosestCapableTarget = EventTarget & {
  closest?: (selector: string) => unknown;
};

export const isDiagramActionMenuTarget = (target: EventTarget | null) => {
  const candidate = target as ClosestCapableTarget | null;
  return Boolean(
    candidate?.closest?.(`[${DIAGRAM_ACTION_MENU_ATTRIBUTE}]`),
  );
};

export const isAccountMenuTarget = (target: EventTarget | null) => {
  const candidate = target as ClosestCapableTarget | null;
  return Boolean(
    candidate?.closest?.(`[${ACCOUNT_MENU_ATTRIBUTE}]`),
  );
};

export const isSortMenuTarget = (target: EventTarget | null) => {
  const candidate = target as ClosestCapableTarget | null;
  return Boolean(
    candidate?.closest?.(`[${SORT_MENU_ATTRIBUTE}]`),
  );
};
