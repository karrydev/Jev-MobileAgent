package com.jev.mobileagent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
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

    @Test
    public void durableSuccessReceiptIsExecutedEvenWhenPostconditionIsUnknown() throws Exception {
        JSONObject action = new JSONObject().put("phase", "verification_unknown")
                .put("result", new JSONObject().put("success", true)
                        .put("error_code", JSONObject.NULL).put("message", JSONObject.NULL))
                .put("verification", new JSONObject().put("status", "UNKNOWN"));
        JSONObject task = new JSONObject().put("actions", new JSONArray().put(action));
        assertEquals(LocalTaskControlPolicy.ExecutionFact.EXECUTED,
                LocalTaskControlPolicy.executionFact(task, action));
        assertTrue(LocalTaskControlPolicy.hasUnresolvedDeviceAction(task));
        assertFalse(LocalTaskControlPolicy.allowsRecoveryDecision("resume", task,
                new JSONObject().put("goal_outcome", "UNKNOWN"), "UNKNOWN"));
        assertTrue(LocalTaskControlPolicy.allowsRecoveryDecision("end", task,
                new JSONObject().put("goal_outcome", "UNKNOWN"), "UNKNOWN"));
    }

    @Test
    public void missingReceiptRemainsUnknownAndCannotBeReleasedByUserConfirmation() throws Exception {
        JSONObject action = new JSONObject().put("phase", "pending").put("result", JSONObject.NULL);
        JSONObject task = new JSONObject().put("actions", new JSONArray().put(action));
        JSONObject review = new JSONObject().put("goal_outcome", "NOT_VERIFIED");
        assertEquals(LocalTaskControlPolicy.ExecutionFact.UNKNOWN,
                LocalTaskControlPolicy.executionFact(task, action));
        assertFalse(LocalTaskControlPolicy.allowsRecoveryDecision("resume", task, review, "NOT_VERIFIED"));
        assertFalse(LocalTaskControlPolicy.allowsRecoveryDecision("end", task, review, "NOT_VERIFIED"));
    }

    @Test
    public void preDispatchFaultProvesNotExecutedAndDoesNotHideReceipt() throws Exception {
        JSONObject action = new JSONObject().put("action_id", "a1").put("phase", "pending");
        JSONObject task = new JSONObject().put("actions", new JSONArray().put(action))
                .put("debug_recovery_fault", new JSONObject().put("status", "fired")
                        .put("point", LocalTaskStore.DEBUG_FAULT_BEFORE_DISPATCH).put("action_id", "a1"));
        assertEquals(LocalTaskControlPolicy.ExecutionFact.NOT_EXECUTED,
                LocalTaskControlPolicy.executionFact(task, action));
        assertTrue(LocalTaskControlPolicy.allowsRecoveryDecision("resume", task,
                new JSONObject().put("goal_outcome", "NOT_VERIFIED"), "NOT_VERIFIED"));
        action.put("result", new JSONObject().put("success", true));
        assertEquals(LocalTaskControlPolicy.ExecutionFact.EXECUTED,
                LocalTaskControlPolicy.executionFact(task, action));
    }

    @Test
    public void completedGoalUsesSeparateUserObservedTerminalPolicyEvenWithUnknownAction() throws Exception {
        JSONObject action = new JSONObject().put("phase", "pending");
        JSONObject task = new JSONObject().put("actions", new JSONArray().put(action));
        JSONObject review = new JSONObject().put("goal_outcome", "VERIFIED");
        assertTrue(LocalTaskControlPolicy.allowsRecoveryDecision("complete_goal", task, review, "VERIFIED"));
        assertFalse(LocalTaskControlPolicy.allowsRecoveryDecision("resume", task, review, "VERIFIED"));
        assertFalse(LocalTaskControlPolicy.allowsRecoveryDecision("complete_goal", task, review, "UNKNOWN"));
        assertFalse(LocalTaskControlPolicy.allowsRecoveryDecision("complete_goal", task,
                new JSONObject().put("goal_outcome", "UNKNOWN"), "VERIFIED"));
    }

    @Test
    public void stableSceneFingerprintIgnoresCaptureMetadataButIncludesTargetAndScreenshot() throws Exception {
        JSONObject observation = sceneObservation();
        String original = LocalTaskControlPolicy.sceneFingerprint(observation, "png-a");
        observation.put("observation_id", "next").put("observation_version", 99)
                .put("captured_at", "later");
        observation.getJSONArray("windows").getJSONObject(0).put("window_id", 999);
        observation.getJSONArray("nodes").getJSONObject(0).put("node_id", "next-node");
        assertEquals(original, LocalTaskControlPolicy.sceneFingerprint(observation, "png-a"));
        observation.getJSONArray("nodes").getJSONObject(0).put("text", "different visible target");
        assertFalse(original.equals(LocalTaskControlPolicy.sceneFingerprint(observation, "png-a")));
        assertFalse(original.equals(LocalTaskControlPolicy.sceneFingerprint(observation, "png-b")));
    }

    @Test
    public void changedGoalInvalidatesReviewedSceneEvenWhenObservationMatches() throws Exception {
        JSONObject task = new JSONObject().put("goal", "first goal").put("recovery_review",
                new JSONObject().put("valid", true).put("goal", "first goal")
                        .put("scene_fingerprint", LocalTaskControlPolicy.sceneFingerprint(sceneObservation(), "png-a")));
        assertTrue(LocalTaskControlPolicy.sameReviewedScene(task, sceneObservation(), "png-a"));
        assertFalse(LocalTaskControlPolicy.sameReviewedScene(task, sceneObservation(), ""));
        task.put("goal", "changed goal");
        assertFalse(LocalTaskControlPolicy.sameReviewedScene(task, sceneObservation(), "png-a"));
    }

    @Test
    public void notificationShadeIsRecognizedButOtherSystemUiAndChangedTargetAreRejected() throws Exception {
        JSONObject target = sceneObservation();
        assertTrue(LocalTaskControlPolicy.isTargetApplicationForeground(target, "com.jev.mobileagent"));

        JSONObject shade = sceneObservation();
        shade.getJSONArray("windows").put(new JSONObject().put("window_id", 2)
                .put("window_type", 3).put("title", "Notifications")
                .put("class_name", "com.android.systemui.statusbar.phone.NotificationShadeWindowView")
                .put("package_name", "com.android.systemui").put("active", true).put("focused", true)
                .put("layer", 10).put("bounds", new JSONObject().put("left", 0).put("top", 0)
                        .put("right", 1080).put("bottom", 2200)));
        shade.getJSONObject("screen").put("active_window_id", 2);
        assertTrue(LocalTaskControlPolicy.isNotificationShade(shade, "com.jev.mobileagent"));
        assertFalse(LocalTaskControlPolicy.isTargetApplicationForeground(shade, "com.jev.mobileagent"));

        JSONObject permissionDialog = new JSONObject(shade.toString());
        permissionDialog.getJSONArray("windows").getJSONObject(1)
                .put("class_name", "com.android.systemui.PermissionDialog");
        assertFalse(LocalTaskControlPolicy.isNotificationShade(permissionDialog, "com.jev.mobileagent"));

        JSONObject changedTarget = new JSONObject(target.toString());
        changedTarget.getJSONArray("nodes").getJSONObject(0).put("text", "Different page");
        JSONObject reviewed = new JSONObject().put("goal", "goal").put("recovery_review",
                new JSONObject().put("valid", true).put("goal", "goal")
                        .put("scene_fingerprint", LocalTaskControlPolicy.sceneFingerprint(target, "same-image")));
        assertFalse(LocalTaskControlPolicy.sameReviewedScene(reviewed, changedTarget, "same-image"));
    }

    @Test
    public void applicationSceneIgnoresSystemStatusNoiseButKeepsTargetPixelsAndSemantics() throws Exception {
        JSONObject observation = sceneObservation();
        String before = LocalTaskControlPolicy.sceneFingerprint(observation, "target-image");
        observation.getJSONArray("windows").put(new JSONObject().put("window_id", 8)
                .put("window_type", 3).put("title", "Status bar").put("package_name", "com.android.systemui")
                .put("active", false).put("focused", false).put("bounds", new JSONObject()));
        observation.getJSONArray("nodes").put(new JSONObject().put("package_name", "com.android.systemui")
                .put("text", "09:59"));
        assertEquals(before, LocalTaskControlPolicy.sceneFingerprint(observation, "target-image"));
        observation.getJSONArray("nodes").getJSONObject(0).put("text", "Changed target");
        assertFalse(before.equals(LocalTaskControlPolicy.sceneFingerprint(observation, "target-image")));
    }

    private static JSONObject sceneObservation() throws Exception {
        return new JSONObject()
                .put("availability", "AVAILABLE")
                .put("observation_id", "first")
                .put("observation_version", 1)
                .put("captured_at", "first-time")
                .put("screen", new JSONObject().put("width_px", 1080).put("height_px", 2400)
                        .put("active_window_id", 1).put("active_window_bounds", new JSONObject()
                                .put("left", 0).put("top", 20).put("right", 1080).put("bottom", 2300))
                        .put("rotation", 0).put("system_bar_insets", new JSONObject()
                                .put("top", 0).put("bottom", 0)))
                .put("windows", new JSONArray().put(new JSONObject().put("window_id", 1)
                        .put("window_type", 1).put("title", "Controlled page")
                        .put("package_name", "com.jev.mobileagent").put("active", true)
                        .put("focused", true).put("layer", 1).put("bounds", new JSONObject()
                                .put("left", 0).put("top", 20).put("right", 1080).put("bottom", 2300))))
                .put("nodes", new JSONArray().put(new JSONObject().put("node_id", "first-node")
                        .put("package_name", "com.jev.mobileagent").put("class_name", "android.widget.Button")
                        .put("text", "Confirm target").put("content_description", "target")
                        .put("state_description", "ready").put("view_id_resource_name", "target")
                        .put("enabled", true).put("visible_to_user", true).put("clickable", true)
                        .put("focusable", true).put("focused", false).put("selected", false)
                        .put("scrollable", false).put("editable", false).put("bounds", "10,20,300,80")));
    }

    private static JSONObject taskWithAction(String phase) throws Exception {
        return new JSONObject().put("actions", new JSONArray().put(new JSONObject().put("phase", phase)));
    }
}
