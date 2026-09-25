package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocalVlmBudgetPolicyTest {
    @Test
    public void onlyReviewedProviderAndModelCanPassThePriceGate() {
        assertTrue(LocalVlmBudgetPolicy.isReviewedProfile("gui-plus", "gui-plus-2026-02-26"));
        assertFalse(LocalVlmBudgetPolicy.isReviewedProfile("GUI-Plus", "another-model"));
        assertFalse(LocalVlmBudgetPolicy.isReviewedProfile("another-provider", "gui-plus-2026-02-26"));
    }

    @Test
    public void requestReservationIsAnEstimateAndActualUsageSettlesSeparately() {
        assertEquals(0.016896, LocalVlmBudgetPolicy.requestReservationCny(), 1e-12);
        assertEquals(0.00375, LocalVlmBudgetPolicy.actualCostCny(1000L, 500L), 1e-12);
        assertTrue(LocalVlmBudgetPolicy.exceeds(1.000001, 1.0));
        assertFalse(LocalVlmBudgetPolicy.exceeds(1.0, 1.0));
    }
}
