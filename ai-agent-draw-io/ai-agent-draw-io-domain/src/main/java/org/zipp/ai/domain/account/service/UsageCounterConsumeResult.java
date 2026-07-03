package org.zipp.ai.domain.account.service;

public final class UsageCounterConsumeResult {

    private final boolean consumed;
    private final int count;

    private UsageCounterConsumeResult(boolean consumed, int count) {
        this.consumed = consumed;
        this.count = Math.max(0, count);
    }

    public static UsageCounterConsumeResult consumed(int count) {
        return new UsageCounterConsumeResult(true, count);
    }

    public static UsageCounterConsumeResult rejected(int count) {
        return new UsageCounterConsumeResult(false, count);
    }

    public boolean isConsumed() {
        return consumed;
    }

    public int getCount() {
        return count;
    }
}
