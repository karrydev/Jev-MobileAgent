package com.jev.mobileagent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class LocalTaskControlPolicyTest {
    @Test
    public void pendingExecutedAndUnknownActionsKeepTaskForReview() throws Exception {
        for (String phase : new String[] {"pending", "executed", "outcome_unknown", "controlled"}) {
            JSONObject task = taskWithAction(phase);
            assertTrue("phase=" + phase, LocalTaskControlPolicy.hasUnresolvedDeviceAction(task));
        }
    }

    @Test
    public void verifiedKnownFailuresAndProvenNotDispatchedActionsCanBeReleased() throws Exception {
        for (String phase : new String[] {"verified", "reflected_failure", "not_dispatched"}) {
            JSONObject task = taskWithAction(phase);
            assertFalse("phase=" + phase, LocalTaskControlPolicy.hasUnresolvedDeviceAction(task));
        }
    }

    private static JSONObject taskWithAction(String phase) throws Exception {
        return new JSONObject().put("actions", new JSONArray().put(new JSONObject().put("phase", phase)));
    }
}
