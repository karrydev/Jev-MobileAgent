package com.jev.mobileagent;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class AccessibilityFreshnessPolicyTest {
    @Test
    public void api32UsesPerNodeRefreshAndRejectsAnUnrefreshableNode() {
        assertFalse(AccessibilityFreshnessPolicy.shouldClearServiceCache(32));
        assertTrue(AccessibilityFreshnessPolicy.requiresPerNodeRefresh(32, false));
        assertFalse(AccessibilityFreshnessPolicy.maySerializeNode(true, false));
        assertTrue(AccessibilityFreshnessPolicy.maySerializeNode(true, true));
    }

    @Test
    public void api33UsesServiceCacheClearWhenItSucceeds() {
        assertTrue(AccessibilityFreshnessPolicy.shouldClearServiceCache(33));
        assertFalse(AccessibilityFreshnessPolicy.requiresPerNodeRefresh(33, true));
        assertTrue(AccessibilityFreshnessPolicy.maySerializeNode(false, false));
    }

    @Test
    public void api33FallsBackToPerNodeRefreshWhenCacheClearFails() {
        assertTrue(AccessibilityFreshnessPolicy.shouldClearServiceCache(33));
        assertTrue(AccessibilityFreshnessPolicy.requiresPerNodeRefresh(33, false));
        assertFalse(AccessibilityFreshnessPolicy.maySerializeNode(true, false));
        assertTrue(AccessibilityFreshnessPolicy.maySerializeNode(true, true));
    }
}
