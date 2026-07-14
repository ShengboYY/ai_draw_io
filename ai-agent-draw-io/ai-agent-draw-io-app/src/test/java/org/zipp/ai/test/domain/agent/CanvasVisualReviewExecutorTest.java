package org.zipp.ai.test.domain.agent;

import org.junit.Test;
import org.zipp.ai.domain.agent.service.visualreview.CanvasVisualReviewExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class CanvasVisualReviewExecutorTest {

    @Test
    public void rejectsRequestsBeyondItsBoundedCapacity() throws Exception {
        CanvasVisualReviewExecutor executor = new CanvasVisualReviewExecutor(1, 1, 1, 1);
        CountDownLatch running = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            assertTrue(executor.executeRequest(() -> {
                running.countDown();
                try {
                    release.await();
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertTrue(running.await(1, TimeUnit.SECONDS));
            assertTrue(executor.executeRequest(() -> { }));
            assertFalse(executor.executeRequest(() -> { }));
        } finally {
            release.countDown();
            executor.close();
        }
    }
}
