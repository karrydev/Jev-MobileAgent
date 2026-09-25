package com.jev.mobileagent;

/** Selects the cheapest supported way to bypass AccessibilityNodeInfo's client cache. */
final class AccessibilityFreshnessPolicy {
    private static final int SERVICE_CACHE_CLEAR_API = 33;

    private AccessibilityFreshnessPolicy() {
    }

    static boolean shouldClearServiceCache(int apiLevel) {
        return apiLevel >= SERVICE_CACHE_CLEAR_API;
    }

    static boolean requiresPerNodeRefresh(int apiLevel, boolean serviceCacheCleared) {
        return apiLevel < SERVICE_CACHE_CLEAR_API || !serviceCacheCleared;
    }

    static boolean maySerializeNode(boolean refreshRequired, boolean refreshSucceeded) {
        return !refreshRequired || refreshSucceeded;
    }
}
