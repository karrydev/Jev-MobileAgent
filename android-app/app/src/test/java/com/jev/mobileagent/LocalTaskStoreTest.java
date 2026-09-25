package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class LocalTaskStoreTest {
    @Test
    public void sameStepJevRequestsAreDeduplicatedPerPurpose() throws Exception {
        JSONArray attempts = new JSONArray().put(new JSONObject()
                .put("step", 2).put("request_purpose", "selection").put("request_sent", true));

        assertTrue(LocalTaskStore.hasSentJevRequestForPurpose(attempts, 2, "selection"));
        assertFalse(LocalTaskStore.hasSentJevRequestForPurpose(attempts, 2, "verification"));

        attempts.put(new JSONObject().put("step", 2).put("request_purpose", "verification")
                .put("request_sent", true));
        assertTrue(LocalTaskStore.hasSentJevRequestForPurpose(attempts, 2, "selection"));
        assertTrue(LocalTaskStore.hasSentJevRequestForPurpose(attempts, 2, "verification"));
        assertFalse(LocalTaskStore.hasSentJevRequestForPurpose(attempts, 1, "verification"));
    }

    @Test
    public void sentLegacyJevAttemptsKeepKnownPurposeAndUnknownRecordsBlockConservatively() throws Exception {
        JSONArray knownLegacy = new JSONArray().put(new JSONObject()
                .put("step", 1).put("source", "task_controlled").put("request_sent", true));

        assertTrue(LocalTaskStore.hasSentJevRequestForPurpose(knownLegacy, 1, "selection"));
        assertFalse(LocalTaskStore.hasSentJevRequestForPurpose(knownLegacy, 1, "verification"));

        JSONArray unknownLegacy = new JSONArray().put(new JSONObject()
                .put("step", 1).put("request_sent", true));
        assertTrue(LocalTaskStore.hasSentJevRequestForPurpose(unknownLegacy, 1, "selection"));
        assertTrue(LocalTaskStore.hasSentJevRequestForPurpose(unknownLegacy, 1, "verification"));

        JSONArray unsent = new JSONArray().put(new JSONObject().put("step", 1)
                .put("request_purpose", "selection").put("request_sent", false));
        assertFalse(LocalTaskStore.hasSentJevRequestForPurpose(unsent, 1, "selection"));
    }

    @Test
    public void actionReflectionIsSavedBesideReceiptAndOnlyAIsVerified() throws Exception {
        for (String outcome : new String[] {"A", "B", "C"}) {
            JSONObject receipt = new JSONObject().put("success", true).put("receipt_id", "device-1");
            JSONObject action = new JSONObject().put("phase", "executed").put("result", receipt);

            assertTrue(LocalTaskStore.applyActionReflection(action, outcome,
                    "A".equals(outcome) ? "None" : "no expected effect"));

            assertEquals("A".equals(outcome) ? "verified" : "reflected_failure",
                    action.optString("phase"));
            assertEquals("device-1", action.getJSONObject("result").optString("receipt_id"));
            assertEquals(outcome, action.getJSONObject("reflection").optString("outcome"));
            assertEquals("A".equals(outcome) ? "None" : "no expected effect",
                    action.getJSONObject("reflection").optString("error_description"));
        }
    }

    @Test
    public void reflectionCannotResolveAnActionWithoutItsExecutionReceipt() throws Exception {
        JSONObject action = new JSONObject().put("phase", "executed");

        assertFalse(LocalTaskStore.applyActionReflection(action, "A", "None"));
        assertEquals("executed", action.optString("phase"));
        assertFalse(action.has("reflection"));
    }

    @Test
    public void unsuccessfulExecutionReceiptCannotBePromotedToVerified() throws Exception {
        JSONObject action = new JSONObject().put("phase", "executed")
                .put("result", new JSONObject().put("success", false));

        assertFalse(LocalTaskStore.applyActionReflection(action, "A", "None"));
        assertEquals("executed", action.optString("phase"));
    }

    @Test
    public void fourStateVerificationPersistsUnknownAndCanResolveItWithoutRewritingReceipt() throws Exception {
        JSONObject receipt = new JSONObject().put("success", true).put("receipt_id", "device-1");
        JSONObject action = new JSONObject().put("phase", "executed").put("result", receipt);

        assertTrue(LocalTaskStore.applyActionVerification(action,
                new JSONObject().put("status", "UNKNOWN").put("source", "tree_rule")));
        assertEquals("verification_unknown", action.optString("phase"));
        assertEquals("UNKNOWN", action.getJSONObject("verification").optString("status"));

        assertTrue(LocalTaskStore.applyActionVerification(action,
                new JSONObject().put("status", "SUCCESS").put("source", "vlm_reflector")));
        assertEquals("verified", action.optString("phase"));
        assertEquals("SUCCESS", action.getJSONObject("verification").optString("status"));
        assertEquals("device-1", action.getJSONObject("result").optString("receipt_id"));
    }

    @Test
    public void fourStateVerificationCannotResolveAFailedReceiptOrUnknownLabel() throws Exception {
        JSONObject failed = new JSONObject().put("phase", "executed")
                .put("result", new JSONObject().put("success", false));
        assertFalse(LocalTaskStore.applyActionVerification(failed,
                new JSONObject().put("status", "SUCCESS")));
        assertEquals("executed", failed.optString("phase"));

        JSONObject action = new JSONObject().put("phase", "executed")
                .put("result", new JSONObject().put("success", true));
        assertFalse(LocalTaskStore.applyActionVerification(action,
                new JSONObject().put("status", "MAYBE")));
        assertEquals("executed", action.optString("phase"));
    }
}
