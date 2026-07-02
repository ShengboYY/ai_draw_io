export const DEMO_QUOTA_EXHAUSTED_CODE = 'DEMO_QUOTA_EXHAUSTED';
export const PLATFORM_QUOTA_EXHAUSTED_CODE = 'PLATFORM_QUOTA_EXHAUSTED';
export const demoQuotaExhaustedMessage = 'Demo quota exhausted. Sign up or add your own API key to continue.';
export const platformDailyQuotaExhaustedMessage = 'Daily free AI quota exhausted. Use your own API key or try again tomorrow.';

export type DemoQuotaAccount = {
  ownerId?: string;
  ownerType?: 'ANONYMOUS' | 'USER' | string;
  authenticated?: boolean;
  emailVerified?: boolean;
  accountStatus?: string;
  demoQuotaLimit?: number;
  demoQuotaUsed?: number;
  demoQuotaRemaining?: number;
  demoQuotaExhausted?: boolean;
  platformDailyQuotaLimit?: number;
  platformDailyQuotaUsed?: number;
  platformDailyQuotaRemaining?: number;
  platformDailyQuotaExhausted?: boolean;
  platformDailyQuotaDate?: string;
};

export type DemoQuotaModel = {
  id: string;
  enabled?: boolean;
  apiKey?: string;
};

export type DemoQuotaState = {
  visible: boolean;
  kind: 'hidden' | 'anonymous' | 'user';
  remaining: number;
  limit: number;
  exhausted: boolean;
  label: string;
  exhaustedMessage: string;
};

const hiddenQuotaState: DemoQuotaState = {
  visible: false,
  kind: 'hidden',
  remaining: 0,
  limit: 0,
  exhausted: false,
  label: '',
  exhaustedMessage: '',
};

export const isDemoQuotaErrorCode = (code?: string | null) => (
  code === DEMO_QUOTA_EXHAUSTED_CODE || code === PLATFORM_QUOTA_EXHAUSTED_CODE
);

export const quotaExhaustedMessageForCode = (code?: string | null) => (
  code === PLATFORM_QUOTA_EXHAUSTED_CODE ? platformDailyQuotaExhaustedMessage : demoQuotaExhaustedMessage
);

export const usesPlatformModel = (
  selectedCustomModelId: string,
  customModels: DemoQuotaModel[] = [],
) => {
  const activeCustomModel = customModels.find(model => model.id === selectedCustomModelId && model.enabled);
  return !activeCustomModel?.apiKey?.trim();
};

export const buildDemoQuotaState = ({
  account,
  selectedCustomModelId,
  customModels,
}: {
  account?: DemoQuotaAccount | null;
  selectedCustomModelId: string;
  customModels: DemoQuotaModel[];
}): DemoQuotaState => {
  if (!account || !usesPlatformModel(selectedCustomModelId, customModels)) {
    return hiddenQuotaState;
  }

  if (account.ownerType === 'ANONYMOUS') {
    const limit = Number.isFinite(account.demoQuotaLimit) ? Math.max(0, account.demoQuotaLimit || 0) : 5;
    const remaining = Number.isFinite(account.demoQuotaRemaining)
      ? Math.max(0, account.demoQuotaRemaining || 0)
      : limit;
    const exhausted = Boolean(account.demoQuotaExhausted) || remaining <= 0;

    return {
      visible: true,
      kind: 'anonymous',
      remaining,
      limit,
      exhausted,
      label: exhausted
        ? 'Demo quota exhausted'
        : `${remaining} demo AI request${remaining === 1 ? '' : 's'} left`,
      exhaustedMessage: demoQuotaExhaustedMessage,
    };
  }

  if (account.ownerType !== 'USER' || !account.authenticated || !account.emailVerified) {
    return hiddenQuotaState;
  }

  const limit = Number.isFinite(account.platformDailyQuotaLimit)
    ? Math.max(0, account.platformDailyQuotaLimit || 0)
    : 20;
  const remaining = Number.isFinite(account.platformDailyQuotaRemaining)
    ? Math.max(0, account.platformDailyQuotaRemaining || 0)
    : limit;
  const exhausted = Boolean(account.platformDailyQuotaExhausted) || remaining <= 0;

  return {
    visible: true,
    kind: 'user',
    remaining,
    limit,
    exhausted,
    label: exhausted
      ? 'Daily free quota exhausted'
      : `${remaining} free AI request${remaining === 1 ? '' : 's'} left today`,
    exhaustedMessage: platformDailyQuotaExhaustedMessage,
  };
};

export const applyDemoQuotaConsumption = <T extends DemoQuotaAccount | null>(account: T): T => {
  if (!account) {
    return account;
  }

  if (account.ownerType === 'USER') {
    const limit = Number.isFinite(account.platformDailyQuotaLimit)
      ? Math.max(0, account.platformDailyQuotaLimit || 0)
      : 20;
    const used = Math.min(limit, Math.max(0, (account.platformDailyQuotaUsed || 0) + 1));
    const remaining = Math.max(0, limit - used);
    return {
      ...account,
      platformDailyQuotaLimit: limit,
      platformDailyQuotaUsed: used,
      platformDailyQuotaRemaining: remaining,
      platformDailyQuotaExhausted: remaining <= 0,
    };
  }

  if (account.ownerType !== 'ANONYMOUS') {
    return account;
  }

  const limit = Number.isFinite(account.demoQuotaLimit) ? Math.max(0, account.demoQuotaLimit || 0) : 5;
  const used = Math.min(limit, Math.max(0, (account.demoQuotaUsed || 0) + 1));
  const remaining = Math.max(0, limit - used);
  return {
    ...account,
    demoQuotaLimit: limit,
    demoQuotaUsed: used,
    demoQuotaRemaining: remaining,
    demoQuotaExhausted: remaining <= 0,
  };
};

export const markDemoQuotaExhausted = <T extends DemoQuotaAccount | null>(account: T): T => {
  if (!account) {
    return account;
  }

  if (account.ownerType === 'USER') {
    const limit = Number.isFinite(account.platformDailyQuotaLimit)
      ? Math.max(0, account.platformDailyQuotaLimit || 0)
      : 20;
    return {
      ...account,
      platformDailyQuotaLimit: limit,
      platformDailyQuotaUsed: Math.max(limit, account.platformDailyQuotaUsed || 0),
      platformDailyQuotaRemaining: 0,
      platformDailyQuotaExhausted: true,
    };
  }

  if (account.ownerType !== 'ANONYMOUS') {
    return account;
  }

  const limit = Number.isFinite(account.demoQuotaLimit) ? Math.max(0, account.demoQuotaLimit || 0) : 5;
  return {
    ...account,
    demoQuotaLimit: limit,
    demoQuotaUsed: Math.max(limit, account.demoQuotaUsed || 0),
    demoQuotaRemaining: 0,
    demoQuotaExhausted: true,
  };
};
