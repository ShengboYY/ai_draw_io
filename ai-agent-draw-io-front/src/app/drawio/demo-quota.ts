export const DEMO_QUOTA_EXHAUSTED_CODE = 'DEMO_QUOTA_EXHAUSTED';
export const demoQuotaExhaustedMessage = 'Demo quota exhausted. Sign up or add your own API key to continue.';

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
};

export type DemoQuotaModel = {
  id: string;
  enabled?: boolean;
  apiKey?: string;
};

export type DemoQuotaState = {
  visible: boolean;
  remaining: number;
  limit: number;
  exhausted: boolean;
  label: string;
};

const hiddenQuotaState: DemoQuotaState = {
  visible: false,
  remaining: 0,
  limit: 0,
  exhausted: false,
  label: '',
};

export const isDemoQuotaErrorCode = (code?: string | null) => code === DEMO_QUOTA_EXHAUSTED_CODE;

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
  if (!account || account.ownerType !== 'ANONYMOUS' || !usesPlatformModel(selectedCustomModelId, customModels)) {
    return hiddenQuotaState;
  }

  const limit = Number.isFinite(account.demoQuotaLimit) ? Math.max(0, account.demoQuotaLimit || 0) : 5;
  const remaining = Number.isFinite(account.demoQuotaRemaining)
    ? Math.max(0, account.demoQuotaRemaining || 0)
    : limit;
  const exhausted = Boolean(account.demoQuotaExhausted) || remaining <= 0;

  return {
    visible: true,
    remaining,
    limit,
    exhausted,
    label: exhausted
      ? 'Demo quota exhausted'
      : `${remaining} demo AI request${remaining === 1 ? '' : 's'} left`,
  };
};

export const applyDemoQuotaConsumption = <T extends DemoQuotaAccount | null>(account: T): T => {
  if (!account || account.ownerType !== 'ANONYMOUS') {
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
  if (!account || account.ownerType !== 'ANONYMOUS') {
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
