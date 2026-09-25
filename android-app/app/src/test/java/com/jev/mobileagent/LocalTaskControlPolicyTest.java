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
    public void lastTrustedRunningApplicationAdvancesAcrossAppsButRecoveryCannotRebindIt() throws Exception {
        JSONObject task = new JSONObject().put("state", "RUNNING")
                .put("target_application_package", "com.example.first");
        JSONObject secondApp = sceneObservation();
        secondApp.getJSONArray("windows").getJSONObject(0).put("package_name", "com.example.second");
        secondApp.getJSONArray("nodes").getJSONObject(0).put("package_name", "com.example.second");

        assertEquals("com.example.second",
                LocalTaskControlPolicy.rememberRunningTargetPackage(task, secondApp));
        assertEquals("com.example.second", task.optString("last_running_target_application_package"));
        assertEquals("com.example.second", task.optString("target_application_package"));

        task.put("state", "PAUSED");
        JSONObject recoveryOnAnotherApp = sceneObservation();
        recoveryOnAnotherApp.getJSONArray("windows").getJSONObject(0)
                .put("package_name", "com.example.recovery");
        recoveryOnAnotherApp.getJSONArray("nodes").getJSONObject(0)
                .put("package_name", "com.example.recovery");
        assertEquals("", LocalTaskControlPolicy.rememberRunningTargetPackage(task, recoveryOnAnotherApp));
        assertEquals("com.example.second", task.optString("last_running_target_application_package"));
        assertEquals("com.example.second", task.optString("target_application_package"));

        task.put("state", "RUNNING");
        assertEquals("", LocalTaskControlPolicy.rememberRunningTargetPackage(
                task, fullScreenSystemWindowObservation()));
        assertEquals("com.example.second", task.optString("target_application_package"));
    }

    @Test
    public void visualMismatchMayResampleOnlyForSameSemanticSceneAndExactPixelsStillDecide() throws Exception {
        JSONObject reviewed = sceneObservation();
        JSONObject task = recoveryTask(reviewed, "review-pixels");
        JSONObject cursorBlink = new JSONObject(reviewed.toString());

        assertEquals(LocalTaskControlPolicy.RecoveryConfirmationSample.RESAMPLE,
                LocalTaskControlPolicy.classifyRecoveryConfirmationSample("resume", task, "review-1",
                        cursorBlink, "cursor-blink-pixels", "NOT_VERIFIED", 1, 5, true));
        assertEquals(LocalTaskControlPolicy.RecoveryConfirmationSample.MATCH,
                LocalTaskControlPolicy.classifyRecoveryConfirmationSample("resume", task, "review-1",
                        cursorBlink, "review-pixels", "NOT_VERIFIED", 2, 5, true));
        assertEquals(LocalTaskControlPolicy.RecoveryConfirmationSample.REJECT,
                LocalTaskControlPolicy.classifyRecoveryConfirmationSample("resume", task, "review-1",
                        cursorBlink, "cursor-blink-pixels", "NOT_VERIFIED", 5, 5, true));

        JSONObject changedPage = new JSONObject(reviewed.toString());
        changedPage.getJSONArray("nodes").getJSONObject(0).put("text", "Different target page");
        assertEquals(LocalTaskControlPolicy.RecoveryConfirmationSample.REJECT,
                LocalTaskControlPolicy.classifyRecoveryConfirmationSample("resume", task, "review-1",
                        changedPage, "different-pixels", "NOT_VERIFIED", 1, 5, true));
        assertEquals(LocalTaskControlPolicy.RecoveryConfirmationSample.REJECT,
                LocalTaskControlPolicy.classifyRecoveryConfirmationSample("resume", task, "review-1",
                        cursorBlink, "review-pixels", "NOT_VERIFIED", 1, 5, false));
    }

    @Test
    public void localStatusDisplayUpdatesDoNotChangeDecisionSceneButOtherTextStillDoes() throws Exception {
        JSONObject expected = sceneObservation();
        expected.getJSONArray("nodes").put(localTaskStatusNode("com.jev.mobileagent"));
        String expectedFingerprint = LocalTaskControlPolicy.sceneFingerprint(expected, "");

        JSONObject updatedStatus = new JSONObject(expected.toString());
        updatedStatus.getJSONArray("nodes").getJSONObject(1)
                .put("text", "本地任务运行中，费用 ¥0.18")
                .put("state_description", "第 3 次请求");
        assertTrue(LocalTaskControlPolicy.sameDecisionScene(expected, updatedStatus));
        assertTrue(LocalTaskControlPolicy.matchesDecisionScene(expectedFingerprint, updatedStatus));

        JSONObject changedTarget = new JSONObject(expected.toString());
        changedTarget.getJSONArray("nodes").getJSONObject(0).put("text", "Changed target page");
        assertFalse(LocalTaskControlPolicy.sameDecisionScene(expected, changedTarget));
        assertFalse(LocalTaskControlPolicy.matchesDecisionScene(expectedFingerprint, changedTarget));

        JSONObject otherApp = new JSONObject(expected.toString());
        otherApp.getJSONArray("windows").getJSONObject(0).put("package_name", "com.example.target");
        otherApp.getJSONArray("nodes").getJSONObject(0).put("package_name", "com.example.target");
        otherApp.getJSONArray("nodes").getJSONObject(1).put("package_name", "com.example.target");
        String otherAppFingerprint = LocalTaskControlPolicy.sceneFingerprint(otherApp, "");
        JSONObject otherAppStatusUpdate = new JSONObject(otherApp.toString());
        otherAppStatusUpdate.getJSONArray("nodes").getJSONObject(1)
                .put("text", "本地任务运行中，费用 ¥0.18");
        assertFalse(LocalTaskControlPolicy.sameDecisionScene(otherApp, otherAppStatusUpdate));
        assertFalse(LocalTaskControlPolicy.matchesDecisionScene(otherAppFingerprint, otherAppStatusUpdate));
    }

    @Test
    public void recoveryConfirmationStillRejectsChangedScreenshotAfterLocalStatusUpdate() throws Exception {
        JSONObject reviewed = sceneObservation();
        reviewed.getJSONArray("nodes").put(localTaskStatusNode("com.jev.mobileagent"));
        JSONObject task = recoveryTask(reviewed, "review-pixels");
        JSONObject updatedStatus = new JSONObject(reviewed.toString());
        updatedStatus.getJSONArray("nodes").getJSONObject(1).put("text", "费用 ¥0.18");

        assertFalse(LocalTaskControlPolicy.sameReviewedScene(task, updatedStatus, "status-updated-pixels"));
        assertEquals(LocalTaskControlPolicy.RecoveryConfirmationSample.REJECT,
                LocalTaskControlPolicy.classifyRecoveryConfirmationSample("resume", task, "review-1",
                        updatedStatus, "status-updated-pixels", "NOT_VERIFIED", 5, 5, true));
    }

    private static JSONObject recoveryTask(JSONObject reviewedObservation, String screenshotFingerprint)
            throws Exception {
        String targetPackage = LocalTaskControlPolicy.activeApplicationPackage(reviewedObservation);
        return new JSONObject().put("state", "PAUSED").put("goal", "goal")
                .put("actions", new JSONArray())
                .put("recovery_review", new JSONObject().put("valid", true)
                        .put("observation_id", "review-1").put("goal", "goal")
                        .put("goal_outcome", "NOT_VERIFIED")
                        .put("target_application_package", targetPackage)
                        .put("semantic_fingerprint", LocalTaskControlPolicy.sceneFingerprint(
                                reviewedObservation, ""))
                        .put("scene_fingerprint", LocalTaskControlPolicy.sceneFingerprint(
                                reviewedObservation, screenshotFingerprint)));
    }

    @Test
    public void fullScreenSystemWindowWithoutTargetCanBeDismissedOnlyDuringUnlockedRecovery()
            throws Exception {
        JSONObject target = sceneObservation();
        assertTrue(LocalTaskControlPolicy.isTargetApplicationForeground(target, "com.jev.mobileagent"));

        JSONObject systemOnly = fullScreenSystemWindowObservation();
        assertTrue(LocalTaskControlPolicy.shouldAttemptNotificationShadeDismiss(systemOnly, true, true));
        assertFalse(LocalTaskControlPolicy.isTargetApplicationForeground(systemOnly, "com.jev.mobileagent"));
        assertFalse(LocalTaskControlPolicy.shouldAttemptNotificationShadeDismiss(systemOnly, false, true));
        assertFalse(LocalTaskControlPolicy.shouldAttemptNotificationShadeDismiss(systemOnly, true, false));

        JSONObject unfocused = new JSONObject(systemOnly.toString());
        unfocused.getJSONArray("windows").getJSONObject(0).put("focused", false);
        assertFalse(LocalTaskControlPolicy.shouldAttemptNotificationShadeDismiss(unfocused, true, true));

        // A full-screen permission window may resemble a shade when its root is unreadable.
        // The dedicated action can be tried, but the unchanged system window is never accepted as target.
        JSONObject permissionWindow = new JSONObject(systemOnly.toString());
        permissionWindow.put("availability", "AVAILABLE");
        permissionWindow.getJSONArray("windows").getJSONObject(0)
                .put("title", "Permission dialog").put("package_name", "com.android.permissioncontroller");
        assertTrue(LocalTaskControlPolicy.shouldAttemptNotificationShadeDismiss(permissionWindow, true, true));
        assertFalse(LocalTaskControlPolicy.isTargetApplicationForeground(permissionWindow, "com.jev.mobileagent"));

        JSONObject inactive = new JSONObject(systemOnly.toString());
        inactive.getJSONArray("windows").getJSONObject(0).put("active", false);
        assertFalse(LocalTaskControlPolicy.shouldAttemptNotificationShadeDismiss(inactive, true, true));

        JSONObject changedTarget = new JSONObject(target.toString());
        changedTarget.getJSONArray("nodes").getJSONObject(0).put("text", "Different page");
        JSONObject reviewed = new JSONObject().put("goal", "goal").put("recovery_review",
                new JSONObject().put("valid", true).put("goal", "goal")
                        .put("scene_fingerprint", LocalTaskControlPolicy.sceneFingerprint(target, "same-image")));
        assertFalse(LocalTaskControlPolicy.sameReviewedScene(reviewed, changedTarget, "same-image"));
    }

    @Test
    public void wrongApplicationAndSmallSystemWindowCannotBeResumedOrDismissed() throws Exception {
        JSONObject wrongApp = sceneObservation();
        wrongApp.getJSONArray("windows").getJSONObject(0).put("package_name", "com.example.other");
        wrongApp.getJSONArray("nodes").getJSONObject(0).put("package_name", "com.example.other");
        assertFalse(LocalTaskControlPolicy.isTargetApplicationForeground(wrongApp, "com.jev.mobileagent"));
        assertFalse(LocalTaskControlPolicy.shouldAttemptNotificationShadeDismiss(wrongApp, true, true));

        JSONObject smallSystemWindow = fullScreenSystemWindowObservation();
        smallSystemWindow.getJSONArray("windows").getJSONObject(0).put("bounds", new JSONObject()
                .put("left", 0).put("top", 0).put("right", 1080).put("bottom", 600));
        assertFalse(LocalTaskControlPolicy.shouldAttemptNotificationShadeDismiss(smallSystemWindow, true, true));
        assertFalse(LocalTaskControlPolicy.isTargetApplicationForeground(smallSystemWindow, "com.jev.mobileagent"));
    }

    @Test
    public void recoveryWaitRequiresReadableExposedTargetAndStableScene() throws Exception {
        JSONObject target = sceneObservation();
        assertEquals("", LocalTaskControlPolicy.recoveryTargetReadinessError(target, "com.jev.mobileagent"));
        JSONObject nextStableTarget = new JSONObject(target.toString())
                .put("observation_id", "next")
                .put("captured_at", "later");
        assertTrue(LocalTaskControlPolicy.isStableRecoveryObservationPair(
                target, nextStableTarget, "com.jev.mobileagent"));

        JSONObject transition = new JSONObject(target.toString()).put("availability", "EMPTY_TREE");
        assertEquals("observation_unavailable", LocalTaskControlPolicy.recoveryTargetReadinessError(
                transition, "com.jev.mobileagent"));
        assertFalse(LocalTaskControlPolicy.isStableRecoveryObservationPair(
                target, transition, "com.jev.mobileagent"));

        JSONObject wrongApp = new JSONObject(target.toString());
        wrongApp.getJSONArray("windows").getJSONObject(0).put("package_name", "com.example.other");
        assertEquals("target_package_mismatch", LocalTaskControlPolicy.recoveryTargetReadinessError(
                wrongApp, "com.jev.mobileagent"));

        JSONObject covered = new JSONObject(target.toString());
        covered.getJSONArray("windows").put(new JSONObject().put("window_id", 2)
                .put("window_type", 3).put("package_name", "com.android.permissioncontroller")
                .put("active", false).put("focused", false).put("layer", 2)
                .put("bounds", new JSONObject().put("left", 100).put("top", 300)
                        .put("right", 800).put("bottom", 900)));
        assertEquals("target_window_covered", LocalTaskControlPolicy.recoveryTargetReadinessError(
                covered, "com.jev.mobileagent"));

        JSONObject realAnonymousStatusBar = recoveryObservationWithAnonymousStatusBar();
        assertEquals("", LocalTaskControlPolicy.recoveryTargetReadinessError(
                realAnonymousStatusBar, "com.jev.mobileagent"));

        JSONObject permissionWindow = new JSONObject(realAnonymousStatusBar.toString());
        permissionWindow.getJSONArray("windows").put(new JSONObject().put("window_id", 1402)
                .put("window_type", 3).put("package_name", "com.android.permissioncontroller")
                .put("class_name", "").put("title", "").put("active", false).put("focused", false)
                .put("layer", 3).put("bounds", new JSONObject().put("left", 100).put("top", 300)
                        .put("right", 980).put("bottom", 900)));
        assertEquals("target_window_covered", LocalTaskControlPolicy.recoveryTargetReadinessError(
                permissionWindow, "com.jev.mobileagent"));

        JSONObject unrelatedEdgeOverlay = new JSONObject(realAnonymousStatusBar.toString());
        unrelatedEdgeOverlay.getJSONArray("windows").put(new JSONObject().put("window_id", 1403)
                .put("window_type", 3).put("package_name", "com.oem.edgepanel")
                .put("class_name", "com.oem.EdgePanel").put("title", "")
                .put("active", false).put("focused", false).put("layer", 3)
                .put("bounds", new JSONObject().put("left", 240).put("top", 12)
                        .put("right", 840).put("bottom", 90)));
        assertEquals("target_window_covered", LocalTaskControlPolicy.recoveryTargetReadinessError(
                unrelatedEdgeOverlay, "com.jev.mobileagent"));
    }

    @Test
    public void recoveryWaitHasHardDeadlineAndCancellationPreemptsFurtherPolling() {
        assertTrue(LocalTaskControlPolicy.shouldContinueRecoveryWindowWait(false, 2499L, 2500L));
        assertFalse(LocalTaskControlPolicy.shouldContinueRecoveryWindowWait(false, 2500L, 2500L));
        assertFalse(LocalTaskControlPolicy.shouldContinueRecoveryWindowWait(true, 100L, 2500L));
        assertFalse(LocalTaskControlPolicy.shouldContinueRecoveryWindowWait(false, -1L, 2500L));
    }

    @Test
    public void recoveryCropExcludesAnonymousBarsAndRetainsTargetChangeRegion() throws Exception {
        JSONObject observation = recoveryObservationWithAnonymousStatusBar();
        observation.getJSONArray("windows").put(new JSONObject().put("window_id", 1404)
                .put("window_type", 3).put("title", "").put("class_name", "")
                .put("package_name", "com.android.systemui").put("active", false)
                .put("focused", false).put("layer", 1)
                .put("bounds", new JSONObject().put("left", 0).put("top", 2300)
                        .put("right", 1080).put("bottom", 2400)));
        observation.getJSONObject("screen").getJSONObject("recovery_system_bar_insets")
                .put("bottom", 100);
        assertBounds(new int[] {0, 103, 1080, 2300},
                LocalTaskControlPolicy.targetScreenshotBounds(observation));

        // A target-page change below the status bar remains inside the pixels used for recovery review.
        int[] crop = LocalTaskControlPolicy.targetScreenshotBounds(observation);
        assertTrue(crop[0] <= 540 && 540 < crop[2]);
        assertTrue(crop[1] <= 1200 && 1200 < crop[3]);
        JSONObject changedTarget = new JSONObject(observation.toString());
        changedTarget.getJSONArray("nodes").getJSONObject(0).put("text", "Changed target page");
        assertFalse(LocalTaskControlPolicy.sceneFingerprint(observation, "target-pixels-before")
                .equals(LocalTaskControlPolicy.sceneFingerprint(changedTarget, "target-pixels-before")));

        JSONObject unrelatedSystemWindow = new JSONObject(observation.toString());
        unrelatedSystemWindow.getJSONArray("windows").getJSONObject(1)
                .put("bounds", new JSONObject().put("left", 0).put("top", 140)
                        .put("right", 1080).put("bottom", 320));
        unrelatedSystemWindow.getJSONArray("windows").getJSONObject(2)
                .put("title", "OEM overlay").put("class_name", "com.oem.EdgePanel");
        assertEquals("target_window_covered", LocalTaskControlPolicy.recoveryTargetReadinessError(
                unrelatedSystemWindow, "com.jev.mobileagent"));

        JSONObject namedFallback = new JSONObject(observation.toString());
        namedFallback.getJSONObject("screen").getJSONObject("recovery_system_bar_insets")
                .put("available", false).put("reason", "metrics_bounds_mismatch");
        namedFallback.getJSONArray("windows").getJSONObject(1)
                .put("title", "状态栏").put("class_name", "");
        namedFallback.getJSONArray("windows").getJSONObject(2)
                .put("title", "导航栏").put("class_name", "");
        assertBounds(new int[] {0, 103, 1080, 2300},
                LocalTaskControlPolicy.targetScreenshotBounds(namedFallback));

        JSONObject unavailableAndAnonymous = new JSONObject(observation.toString());
        unavailableAndAnonymous.getJSONObject("screen").getJSONObject("recovery_system_bar_insets")
                .put("available", false).put("reason", "metrics_bounds_mismatch");
        assertTrue(LocalTaskControlPolicy.targetScreenshotBounds(unavailableAndAnonymous) == null);
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

    @Test
    public void decisionSceneIgnoresObservationIdentityButRejectsAppFocusAndTextChanges() throws Exception {
        JSONObject expected = sceneObservation();
        String expectedFingerprint = LocalTaskControlPolicy.sceneFingerprint(expected, "");
        JSONObject sameScene = new JSONObject(expected.toString())
                .put("observation_id", "fresh-id").put("observation_version", 9)
                .put("captured_at", "later-time");
        assertTrue(LocalTaskControlPolicy.sameDecisionScene(expected, sameScene));
        assertTrue(LocalTaskControlPolicy.matchesDecisionScene(expectedFingerprint, sameScene));

        JSONObject changedFocus = new JSONObject(expected.toString());
        changedFocus.getJSONArray("nodes").getJSONObject(0).put("focused", true);
        assertFalse(LocalTaskControlPolicy.sameDecisionScene(expected, changedFocus));
        assertFalse(LocalTaskControlPolicy.matchesDecisionScene(expectedFingerprint, changedFocus));

        JSONObject changedText = new JSONObject(expected.toString());
        changedText.getJSONArray("nodes").getJSONObject(0).put("text", "Edited target");
        assertFalse(LocalTaskControlPolicy.sameDecisionScene(expected, changedText));
        assertFalse(LocalTaskControlPolicy.matchesDecisionScene(expectedFingerprint, changedText));

        JSONObject changedPackage = new JSONObject(expected.toString());
        changedPackage.getJSONArray("windows").getJSONObject(0).put("package_name", "com.example.other");
        changedPackage.getJSONArray("nodes").getJSONObject(0).put("package_name", "com.example.other");
        assertFalse(LocalTaskControlPolicy.sameDecisionScene(expected, changedPackage));
        assertFalse(LocalTaskControlPolicy.matchesDecisionScene("", expected));
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

    private static JSONObject localTaskStatusNode(String packageName) throws Exception {
        return new JSONObject().put("node_id", "task-status").put("package_name", packageName)
                .put("class_name", "android.widget.TextView").put("text", "本地任务：等待")
                .put("content_description", "Local VLM task status").put("state_description", "等待")
                .put("view_id_resource_name", "com.jev.mobileagent:id/task_status")
                .put("enabled", true).put("visible_to_user", true).put("clickable", false)
                .put("focusable", false).put("focused", false).put("selected", false)
                .put("scrollable", false).put("editable", false).put("bounds", "10,900,700,980");
    }

    private static JSONObject fullScreenSystemWindowObservation() throws Exception {
        return new JSONObject().put("availability", "EMPTY_TREE")
                .put("screen", new JSONObject().put("width_px", 1080).put("height_px", 2400)
                        .put("active_window_id", 1367).put("active_window_bounds", new JSONObject()
                                .put("left", 0).put("top", 0).put("right", 1080).put("bottom", 2400)))
                .put("windows", new JSONArray().put(new JSONObject().put("window_id", 1367)
                        .put("window_type", 3).put("title", "").put("class_name", "")
                        .put("package_name", "").put("active", true).put("focused", true)
                        .put("bounds", new JSONObject().put("left", 0).put("top", 0)
                                .put("right", 1080).put("bottom", 2400))))
                .put("nodes", new JSONArray());
    }

    private static JSONObject recoveryObservationWithAnonymousStatusBar() throws Exception {
        JSONObject observation = sceneObservation();
        observation.getJSONObject("screen")
                .put("active_window_bounds", new JSONObject().put("left", 0).put("top", 0)
                        .put("right", 1080).put("bottom", 2400))
                .put("recovery_system_bar_insets", new JSONObject()
                        .put("available", true).put("source", "window_metrics_system_bars")
                        .put("reason", "").put("status_bars_visible", true)
                        .put("navigation_bars_visible", true)
                        .put("metrics_bounds_px", new JSONObject().put("left", 0).put("top", 0)
                                .put("right", 1080).put("bottom", 2400))
                        .put("left", 0).put("top", 103).put("right", 0).put("bottom", 0));
        observation.getJSONArray("windows").getJSONObject(0)
                .put("bounds", new JSONObject().put("left", 0).put("top", 0)
                        .put("right", 1080).put("bottom", 2400)).put("layer", 0);
        observation.getJSONArray("windows").put(new JSONObject().put("window_id", 1401)
                .put("window_type", 3).put("title", "").put("class_name", "")
                .put("package_name", "com.android.systemui").put("active", false)
                .put("focused", false).put("layer", 1)
                .put("bounds", new JSONObject().put("left", 0).put("top", 0)
                        .put("right", 1080).put("bottom", 103)));
        return observation;
    }

    private static void assertBounds(int[] expected, int[] actual) {
        assertTrue("expected crop bounds", actual != null);
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertEquals("coordinate " + i, expected[i], actual[i]);
        }
    }

    private static JSONObject taskWithAction(String phase) throws Exception {
        return new JSONObject().put("actions", new JSONArray().put(new JSONObject().put("phase", phase)));
    }
}
