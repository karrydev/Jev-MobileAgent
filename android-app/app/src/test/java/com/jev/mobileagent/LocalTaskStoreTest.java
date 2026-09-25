package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public final class LocalTaskStoreTest {
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
