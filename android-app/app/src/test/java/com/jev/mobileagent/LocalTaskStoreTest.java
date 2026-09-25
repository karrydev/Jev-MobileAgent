package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class LocalTaskStoreTest {
    @Test
    public void targetFreeCancellationPreservesTaskHistoryAndBudget() throws Exception {
        JSONArray requests = new JSONArray().put(new JSONObject()
                .put("purpose", "manager").put("status", "failed").put("usage_unknown", true));
        JSONArray observations = new JSONArray().put(new JSONObject()
                .put("observation_id", "system-ui").put("active_application_package", ""));
        JSONObject review = new JSONObject().put("valid", true).put("observation_id", "old-review");
        JSONObject task = new JSONObject().put("schema_version", "standalone-vlm-task-v1")
                .put("state", "NEEDS_REVIEW").put("actions", new JSONArray())
                .put("requests", requests).put("observations", observations)
                .put("history", new JSONObject().put("manager", new JSONArray().put("kept")))
                .put("recovery_review", review).put("request_count", 3).put("step_count", 2)
                .put("accounted_cost_cny", 0.123).put("cost_status", "usage_unknown")
                .put("budget_cny", 0.4).put("usage_missing", true);
        String requestsBefore = requests.toString();
        String observationsBefore = observations.toString();
        String historyBefore = task.getJSONObject("history").toString();

        assertTrue(LocalTaskStore.applyCancellationWithoutDeviceDispatch(task));

        assertEquals("CANCELLED", task.optString("state"));
        assertEquals("user_cancelled_before_any_device_dispatch", task.optString("state_reason"));
        assertEquals("cancel", task.getJSONObject("terminal_control").optString("decision"));
        assertEquals("no_device_action_dispatched",
                task.getJSONObject("terminal_control").optString("basis"));
        assertFalse(review.optBoolean("valid", true));
        assertEquals("task_cancelled_without_device_dispatch",
                review.optString("invalidated_reason"));
        assertEquals(requestsBefore, task.getJSONArray("requests").toString());
        assertEquals(observationsBefore, task.getJSONArray("observations").toString());
        assertEquals(historyBefore, task.getJSONObject("history").toString());
        assertEquals(3, task.optInt("request_count"));
        assertEquals(2, task.optInt("step_count"));
        assertEquals(0.123, task.optDouble("accounted_cost_cny"), 0.0);
        assertEquals(0.4, task.optDouble("budget_cny"), 0.0);
        assertEquals("usage_unknown", task.optString("cost_status"));
        assertTrue(task.optBoolean("usage_missing"));
    }

    @Test
    public void targetFreeCancellationRejectsActiveOrUncertainTaskWithoutMutation() throws Exception {
        JSONObject active = new JSONObject().put("schema_version", "standalone-vlm-task-v1")
                .put("state", "RUNNING").put("actions", new JSONArray());
        JSONObject uncertain = new JSONObject().put("schema_version", "standalone-vlm-task-v1")
                .put("state", "PAUSED").put("actions", new JSONArray().put(new JSONObject()
                        .put("action_id", "a1").put("phase", "pending")
                        .put("action", new JSONObject().put("type", "tap"))));
        for (JSONObject task : new JSONObject[] {active, uncertain}) {
            String before = task.toString();
            assertFalse(LocalTaskStore.applyCancellationWithoutDeviceDispatch(task));
            assertEquals(before, task.toString());
        }
    }

    @Test
    public void recoveryTargetUsesLastRunningObservationAndIgnoresUntrustedRecoveryHistory() throws Exception {
        JSONObject task = new JSONObject().put("target_application_package", "com.example.first")
                .put("last_running_target_application_package", "com.example.second")
                .put("observations", new JSONArray()
                        .put(new JSONObject().put("active_application_package", "com.example.second")
                                .put("trusted_running_target_application_package", "com.example.second"))
                        .put(new JSONObject().put("active_application_package", "com.example.recovery")));

        assertEquals("com.example.second", LocalTaskStore.recoveryTargetPackage(task));

        JSONObject legacyTask = new JSONObject().put("target_application_package", "com.example.legacy")
                .put("observations", new JSONArray().put(new JSONObject()
                        .put("active_application_package", "com.example.legacy")));
        assertEquals("com.example.legacy", LocalTaskStore.recoveryTargetPackage(legacyTask));
    }

    @Test
    public void legacyRecoveryTargetUsesLatestLinkedBeforeOrAfterObservation() throws Exception {
        JSONObject task = new JSONObject().put("target_application_package", "com.example.first")
                .put("observations", new JSONArray()
                        .put(legacyObservation("before-1", 1, "com.example.first"))
                        .put(legacyObservation("after-2", 2, "com.example.second")))
                .put("screenshot_captures", new JSONArray()
                        .put(legacyCapture("BEFORE", "before-1", 1))
                        .put(legacyCapture("AFTER", "after-2", 2))
                        .put(legacyCapture("RECOVERY", "review-3", 3)));

        assertEquals("com.example.second", LocalTaskStore.recoveryTargetPackage(task));
        assertFalse(LocalTaskStore.recoveryTargetIsAmbiguous(task));
    }

    @Test
    public void ambiguousLegacyCrossAppObservationFailsClosedButSingleAppHistoryRemainsReviewable() throws Exception {
        JSONObject crossApp = new JSONObject().put("target_application_package", "com.example.first")
                .put("observations", new JSONArray()
                        .put(legacyObservation("before-1", 1, "com.example.first"))
                        .put(legacyObservation("unlinked-2", 2, "com.example.second")))
                .put("screenshot_captures", new JSONArray()
                        .put(legacyCapture("BEFORE", "before-1", 1)));
        assertEquals("", LocalTaskStore.recoveryTargetPackage(crossApp));
        assertTrue(LocalTaskStore.recoveryTargetIsAmbiguous(crossApp));

        JSONObject singleApp = new JSONObject().put("target_application_package", "com.example.first")
                .put("observations", new JSONArray()
                        .put(legacyObservation("unlinked-1", 1, "com.example.first"))
                        .put(legacyObservation("unlinked-2", 2, "com.example.first")));
        assertEquals("com.example.first", LocalTaskStore.recoveryTargetPackage(singleApp));
        assertFalse(LocalTaskStore.recoveryTargetIsAmbiguous(singleApp));
    }

    @Test
    public void legacyRecoveryCapturesDoNotReplaceTheLastRunningTarget() throws Exception {
        JSONObject task = new JSONObject().put("target_application_package", "com.example.first")
                .put("observations", new JSONArray()
                        .put(legacyObservation("before-1", 1, "com.example.first"))
                        .put(legacyObservation("review-2", 2, "com.example.recovery")))
                .put("screenshot_captures", new JSONArray()
                        .put(legacyCapture("BEFORE", "before-1", 1))
                        .put(legacyCapture("RECOVERY", "review-2", 2)));

        assertEquals("com.example.first", LocalTaskStore.recoveryTargetPackage(task));
        assertFalse(LocalTaskStore.recoveryTargetIsAmbiguous(task));
    }

    private static JSONObject legacyObservation(String id, long version, String packageName) throws Exception {
        return new JSONObject().put("observation_id", id).put("observation_version", version)
                .put("active_application_package", packageName);
    }

    private static JSONObject legacyCapture(String type, String id, long version) throws Exception {
        return new JSONObject().put("capture_type", type).put("observation_id", id)
                .put("observation_version", version).put("status", "captured");
    }

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
    public void postActionSceneAuditStaysBesideSuccessfulReceiptWithoutResolvingIt() throws Exception {
        JSONObject receipt = new JSONObject().put("success", true).put("receipt_id", "device-1");
        JSONObject action = new JSONObject().put("phase", "executed").put("result", receipt)
                .put("completed_at", "receipt-time");
        JSONObject audit = new JSONObject().put("status", "stabilized")
                .put("initial_observation_id", "after-first")
                .put("accepted_observation_id", "after-stable");

        assertTrue(LocalTaskStore.applyActionSceneAssociation(action, audit));
        assertEquals("executed", action.optString("phase"));
        assertEquals("receipt-time", action.optString("completed_at"));
        assertEquals("device-1", action.getJSONObject("result").optString("receipt_id"));
        assertEquals("after-stable", action.getJSONObject("post_action_scene")
                .optString("accepted_observation_id"));
        assertFalse(LocalTaskStore.applyActionSceneAssociation(action, audit));

        JSONObject unresolved = new JSONObject().put("phase", "executed")
                .put("result", new JSONObject().put("success", false));
        assertFalse(LocalTaskStore.applyActionSceneAssociation(unresolved, audit));
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

    @Test
    public void debugRecoveryAfterFaultWaitsForMatchingDurableSetTextAction() throws Exception {
        JSONObject task = armedSetTextRecoveryFault();
        JSONArray actions = task.getJSONArray("actions");

        assertFalse(LocalTaskStore.shouldFireDebugRecoveryFault(task,
                LocalTaskStore.DEBUG_FAULT_AFTER_SIDE_EFFECT, "focus-action"));
        assertEquals("armed", task.getJSONObject("debug_recovery_fault").optString("status"));

        actions.put(new JSONObject().put("action_id", "focus-action")
                .put("action", new JSONObject().put("kind", "coordinate_tap")));
        assertFalse(LocalTaskStore.shouldFireDebugRecoveryFault(task,
                LocalTaskStore.DEBUG_FAULT_AFTER_SIDE_EFFECT, "focus-action"));
        assertEquals("armed", task.getJSONObject("debug_recovery_fault").optString("status"));
        task.getJSONObject("debug_recovery_fault").put("point", LocalTaskStore.DEBUG_FAULT_AFTER_RECEIPT);
        assertFalse(LocalTaskStore.shouldFireDebugRecoveryFault(task,
                LocalTaskStore.DEBUG_FAULT_AFTER_RECEIPT, "focus-action"));
        assertEquals("armed", task.getJSONObject("debug_recovery_fault").optString("status"));

        actions.put(new JSONObject().put("action_id", "text-action")
                .put("action", new JSONObject().put("kind", "set_text")));
        assertTrue(LocalTaskStore.shouldFireDebugRecoveryFault(task,
                LocalTaskStore.DEBUG_FAULT_AFTER_RECEIPT, "text-action"));
    }

    @Test
    public void debugRecoveryFaultWithoutActionFilterKeepsLegacyFirstActionBehavior() throws Exception {
        JSONObject task = new JSONObject().put("debug_recovery_fault", new JSONObject()
                .put("point", LocalTaskStore.DEBUG_FAULT_BEFORE_DISPATCH).put("status", "armed"));

        assertTrue(LocalTaskStore.shouldFireDebugRecoveryFault(task,
                LocalTaskStore.DEBUG_FAULT_BEFORE_DISPATCH, "first-action"));
    }

    @Test
    public void firedDebugRecoveryFaultCannotTriggerAgainAfterReload() throws Exception {
        JSONObject task = armedSetTextRecoveryFault();
        task.getJSONArray("actions").put(new JSONObject().put("action_id", "text-action")
                .put("action", new JSONObject().put("kind", "set_text")));
        assertTrue(LocalTaskStore.shouldFireDebugRecoveryFault(task,
                LocalTaskStore.DEBUG_FAULT_AFTER_SIDE_EFFECT, "text-action"));

        JSONObject reloaded = new JSONObject(task.toString());
        reloaded.getJSONObject("debug_recovery_fault").put("status", "fired");
        reloaded.getJSONObject("debug_recovery_fault").put("action_id", "text-action");
        assertFalse(LocalTaskStore.shouldFireDebugRecoveryFault(reloaded,
                LocalTaskStore.DEBUG_FAULT_AFTER_SIDE_EFFECT, "text-action"));
    }

    private static JSONObject armedSetTextRecoveryFault() throws Exception {
        return new JSONObject().put("actions", new JSONArray())
                .put("debug_recovery_fault", new JSONObject()
                        .put("point", LocalTaskStore.DEBUG_FAULT_AFTER_SIDE_EFFECT)
                        .put("status", "armed").put("action_kind", "set_text"));
    }
}
