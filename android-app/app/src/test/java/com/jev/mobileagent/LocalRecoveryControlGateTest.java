package com.jev.mobileagent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
