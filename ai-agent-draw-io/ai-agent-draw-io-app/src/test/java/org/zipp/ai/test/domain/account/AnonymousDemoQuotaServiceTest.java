package org.zipp.ai.test.domain.account;

import org.junit.Test;
import org.zipp.ai.domain.account.model.valobj.DemoQuotaSnapshot;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaExceededException;
import org.zipp.ai.domain.account.service.AnonymousDemoQuotaService;
import org.zipp.ai.domain.account.service.InMemoryUsageCounterStore;
import org.zipp.ai.domain.account.service.UsageCounterStore;

import static org.junit.Assert.assertEquals;

public class AnonymousDemoQuotaServiceTest {

    @Test
    public void shouldPersistDemoQuotaAcrossServiceInstances() {
        UsageCounterStore store = new InMemoryUsageCounterStore();
        AnonymousDemoQuotaService firstInstance = new AnonymousDemoQuotaService(2, store);
        AnonymousDemoQuotaService secondInstance = new AnonymousDemoQuotaService(2, store);

        firstInstance.consume("anon_workspace");
        DemoQuotaSnapshot snapshot = secondInstance.consume("anon_workspace");

        assertEquals(2, snapshot.getUsed());
        assertDemoQuotaExceeded(() -> secondInstance.consume("anon_workspace"));
    }

    private void assertDemoQuotaExceeded(Runnable action) {
        try {
            action.run();
        } catch (AnonymousDemoQuotaExceededException expected) {
            assertEquals(2, expected.getQuota().getLimit());
            assertEquals(2, expected.getQuota().getUsed());
            return;
        }
        throw new AssertionError("expected anonymous demo quota denial");
    }
}
