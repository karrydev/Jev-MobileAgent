package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public final class JevShadowBudgetPolicyTest {
    @Test
    public void taskAndGlobalJevCallGatesStayBoundedForTreeVerification() {
        assertEquals(70, JevShadowBudgetPolicy.MAX_TOTAL_CALLS);
        assertEquals(LocalTaskStore.MAX_STEPS, JevShadowBudgetPolicy.MAX_CALLS_PER_TASK);
        assertEquals(0.01, JevShadowBudgetPolicy.RESERVATION_CNY_PER_CALL, 0.000001);
    }
}
