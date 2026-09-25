package com.jev.mobileagent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Test;

public final class LocalRecoveryControlGateTest {
    @Test
    public void pauseBetweenSuccessfulCaptureAndDecisionCommitKeepsTaskPaused() throws Exception {
        LocalRecoveryControlGate gate = new LocalRecoveryControlGate();
        long generation = gate.beginOperation();
        boolean[] decisionApplied = {false};

        // The screenshot callback has succeeded; a pause arrives before durable decision commit.
        gate.requestControl("pause", "screen_locked");
        assertFalse(gate.runIfCurrent(generation, () -> {
            decisionApplied[0] = true;
            return true;
        }));
        assertFalse(decisionApplied[0]);
        assertTrue(gate.hasRequest());
    }

    @Test
    public void currentReviewCanCommitButPauseInvalidatesItsGeneration() throws Exception {
        LocalRecoveryControlGate gate = new LocalRecoveryControlGate();
        long generation = gate.beginOperation();
        boolean[] reviewSaved = {false};
        assertTrue(gate.runIfCurrent(generation, () -> {
            reviewSaved[0] = true;
            return true;
        }));

        long nextGeneration = gate.beginOperation();
        gate.requestControl("pause", "user_pause");
        assertFalse(gate.isCurrent(nextGeneration));
    }

    @Test
    public void cancellationBetweenFreshConfirmationSamplesPreventsAnotherCapture() throws Exception {
        LocalRecoveryControlGate gate = new LocalRecoveryControlGate();
        long generation = gate.beginOperation();
        int[] captures = {1};

        gate.requestControl("cancel", "user_cancel");
        boolean capturedAgain = gate.isCurrent(generation);
        if (capturedAgain) captures[0]++;

        assertFalse(capturedAgain);
        assertEquals(1, captures[0]);
    }

    @Test
    public void pauseCannotInterleaveWithCurrentGenerationObservationCommit() throws Exception {
        LocalRecoveryControlGate gate = new LocalRecoveryControlGate();
        long generation = gate.beginOperation();
        CountDownLatch saveEntered = new CountDownLatch(1);
        CountDownLatch finishSave = new CountDownLatch(1);
        CountDownLatch pauseStarted = new CountDownLatch(1);
        CountDownLatch pauseReturned = new CountDownLatch(1);
        boolean[] observationCommitted = {false};

        Thread save = new Thread(() -> {
            try {
                gate.runIfCurrent(generation, () -> {
                    saveEntered.countDown();
                    try {
                        if (!finishSave.await(2, TimeUnit.SECONDS)) {
                            throw new IOException("timed out waiting for test save release");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IOException("test save interrupted", exception);
                    }
                    observationCommitted[0] = true;
                    return true;
                });
            } catch (IOException exception) {
                throw new AssertionError(exception);
            }
        });
        save.start();
        assertTrue(saveEntered.await(1, TimeUnit.SECONDS));

        Thread pause = new Thread(() -> {
            pauseStarted.countDown();
            gate.requestControl("pause", "manual_intervention");
            pauseReturned.countDown();
        });
        pause.start();
        assertTrue(pauseStarted.await(1, TimeUnit.SECONDS));
        assertFalse("control request must wait for the atomic save", pauseReturned.await(50, TimeUnit.MILLISECONDS));

        finishSave.countDown();
        save.join(1000);
        pause.join(1000);
        assertTrue(observationCommitted[0]);
        assertTrue(pauseReturned.await(1, TimeUnit.SECONDS));
        assertTrue(gate.hasRequest());
        assertFalse(gate.isCurrent(generation));
    }
}
