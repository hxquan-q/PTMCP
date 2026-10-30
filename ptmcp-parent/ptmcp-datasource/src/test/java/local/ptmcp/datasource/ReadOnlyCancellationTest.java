package local.ptmcp.datasource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * The gateway may signal a cancel while the request is still waiting for capacity, before the
 * executor has installed its statement action. Late install must fire at once, otherwise a queued
 * cancel would let the read run to completion.
 */
class ReadOnlyCancellationTest {

    @Test
    void cancelObservedBeforeInstallFiresTheActionImmediately() throws Exception {
        ReadOnlyCancellation cancellation = new ReadOnlyCancellation();
        assertFalse(cancellation.isCancelled());

        cancellation.cancel();
        assertTrue(cancellation.isCancelled());

        CountDownLatch actionRan = new CountDownLatch(1);
        cancellation.install(actionRan::countDown);
        assertTrue(actionRan.await(2, TimeUnit.SECONDS), "late install ran the cancel action");
    }

    @Test
    void installBeforeCancelFiresTheActionWhenSignalled() throws Exception {
        ReadOnlyCancellation cancellation = new ReadOnlyCancellation();
        CountDownLatch actionRan = new CountDownLatch(1);
        cancellation.install(actionRan::countDown);
        assertFalse(actionRan.await(50, TimeUnit.MILLISECONDS), "not cancelled yet");

        cancellation.cancel();
        assertTrue(actionRan.await(2, TimeUnit.SECONDS));
    }
}
