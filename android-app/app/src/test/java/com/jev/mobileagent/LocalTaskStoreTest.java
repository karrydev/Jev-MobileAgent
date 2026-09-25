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
}
