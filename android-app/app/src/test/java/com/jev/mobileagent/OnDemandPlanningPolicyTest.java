package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class OnDemandPlanningPolicyTest {
    @Test
    public void plansAtTaskStartAndReusesUntilThePlanExpires() {
        assertEquals("task_start", OnDemandPlanningPolicy.replanReason(false, "", 0, -1, false));
        assertEquals("", OnDemandPlanningPolicy.replanReason(true, "", 1, 0, false));
        assertEquals("plan_expired", OnDemandPlanningPolicy.replanReason(true, "", 2, 0, false));
    }

    @Test
    public void explicitProgressAndFailureReasonsTakePriorityOverAge() {
        assertEquals("subgoal_completed",
                OnDemandPlanningPolicy.replanReason(true, "subgoal_completed", 2, 0, false));
        assertEquals("action_exception",
                OnDemandPlanningPolicy.replanReason(true, "action_exception", 1, 0, true));
    }

    @Test
    public void repeatedActionsAndAlternatingCyclesAreDetected() {
        assertTrue(OnDemandPlanningPolicy.hasActionLoop(Arrays.asList("A", "A")));
        assertTrue(OnDemandPlanningPolicy.hasActionLoop(Arrays.asList("A", "B", "A", "B")));
        assertFalse(OnDemandPlanningPolicy.hasActionLoop(Arrays.asList("A", "B", "C")));
        assertFalse(OnDemandPlanningPolicy.hasActionLoop(Collections.singletonList("A")));
    }

    @Test
    public void threeVerifiedNextPageActionsWithObservedProgressDoNotStopAsALoop() throws Exception {
        List<String> actions = new ArrayList<>();
        List<String> outcomes = new ArrayList<>();
        JSONArray events = new JSONArray();
        boolean loopReplanAttempted = false;

        for (int step = 1; step <= 3; step++) {
            actions.add("{\"action\":\"tap\",\"node_id\":\"next-page\"}");
            outcomes.add("A");
            events.put(new JSONObject()
                    .put("event", "verified_step_outcome")
                    .put("step", step)
                    .put("verification_status", "SUCCESS")
                    .put("observation_changed", true));

            boolean actionLoop = OnDemandPlanningPolicy.hasActionLoop(actions, outcomes, events);
            assertFalse("verified page progress must clear the repeated-action signal at step " + step,
                    actionLoop);
            assertFalse("successful progress must not hit the post-replan loop stop at step " + step,
                    actionLoop && loopReplanAttempted);
            loopReplanAttempted = actionLoop;
        }
    }

    @Test
    public void changedSceneAfterResumeBreaksAStaleLoopBeforeTheStopLimit() throws Exception {
        JSONArray events = new JSONArray().put(new JSONObject()
                .put("event", "verified_step_outcome")
                .put("step", 2)
                .put("verification_status", "FAILURE")
                .put("after_observation_sha256", "before-user-intervention"));
        List<String> actions = Arrays.asList("A", "A");
        List<String> outcomes = Arrays.asList("B", "B");

        boolean sceneChanged = OnDemandPlanningPolicy.sceneChangedSinceLastVerifiedStep(
                events, "after-user-intervention");
        boolean actionLoop = !sceneChanged
                && OnDemandPlanningPolicy.hasActionLoop(actions, outcomes, events);

        assertTrue(sceneChanged);
        assertFalse("a newly observed scene must allow a fresh plan after explicit resume", actionLoop);
    }

    @Test
    public void resumeAllowsOneFreshPlanBeforeThePersistedLoopLimitApplies() {
        assertFalse(OnDemandPlanningPolicy.shouldStopPersistentLoop(true, true, true));
        assertTrue(OnDemandPlanningPolicy.shouldStopPersistentLoop(true, true, false));
        assertFalse(OnDemandPlanningPolicy.shouldStopPersistentLoop(false, true, false));
    }

    @Test
    public void onlyEquivalentJevChoiceCanCarryExecutorCompletionHint() {
        assertTrue(OnDemandPlanningPolicy.executorCompletionHintApplies(true, true, "same_action"));
        assertTrue(OnDemandPlanningPolicy.executorCompletionHintApplies(
                true, true, "legal_equivalent_action"));
        assertFalse(OnDemandPlanningPolicy.executorCompletionHintApplies(
                true, true, "different_action"));
        assertFalse(OnDemandPlanningPolicy.executorCompletionHintApplies(
                true, true, "not_compared"));
        assertFalse(OnDemandPlanningPolicy.executorCompletionHintApplies(
                false, false, "vlm_action_retained"));
        assertEquals("", OnDemandPlanningPolicy.nextPlanReason("SUCCESS",
                OnDemandPlanningPolicy.executorCompletionHintApplies(true, true, "different_action")));
    }

    @Test
    public void confirmedResumeKeepsFailureAndLoopLimitsButRequestsFreshPlan() throws Exception {
        JSONArray events = new JSONArray().put(new JSONObject()
                .put("event", "verified_step_outcome")
                .put("step", 1)
                .put("verification_status", "FAILURE")
                .put("next_plan_reason", "action_exception")
                .put("scheduler_state", new JSONObject()
                        .put("pending_reason", "action_exception")
                        .put("consecutive_failures", 1)
                        .put("loop_replan_attempted", true)));

        OnDemandPlanningPolicy.SchedulerState restored =
                OnDemandPlanningPolicy.restoreSchedulerState(
                        events, Collections.singletonList("{\"action\":\"tap\"}"),
                        Collections.singletonList("B"));
        OnDemandPlanningPolicy.SchedulerState resumed = OnDemandPlanningPolicy.forResume(restored);

        assertEquals("resume_replan", resumed.pendingReason);
        assertEquals(1, resumed.consecutiveFailures);
        assertTrue(resumed.loopReplanAttempted);
        assertFalse(OnDemandPlanningPolicy.shouldPauseAfterFailure(resumed.consecutiveFailures));
        assertTrue(OnDemandPlanningPolicy.shouldPauseAfterFailure(resumed.consecutiveFailures + 1));
    }

    @Test
    public void terminalAnswerOutcomeDoesNotResetTheRestoredFailureLimit() throws Exception {
        JSONArray events = new JSONArray().put(new JSONObject()
                .put("event", "verified_step_outcome")
                .put("step", 1)
                .put("verification_status", "FAILURE")
                .put("scheduler_state", new JSONObject()
                        .put("pending_reason", "action_exception")
                        .put("consecutive_failures", 1)
                        .put("loop_replan_attempted", false)));

        OnDemandPlanningPolicy.SchedulerState restored = OnDemandPlanningPolicy.restoreSchedulerState(
                events,
                Arrays.asList("{\"action\":\"tap\"}", "{\"action\":\"answer\"}"),
                Arrays.asList("B", "A"));

        assertEquals(1, restored.consecutiveFailures);
        assertEquals("action_exception", restored.pendingReason);
    }

    @Test
    public void repeatedFailureLimitIsBounded() {
        assertFalse(OnDemandPlanningPolicy.shouldPauseAfterFailure(1));
        assertTrue(OnDemandPlanningPolicy.shouldPauseAfterFailure(2));
        assertTrue(OnDemandPlanningPolicy.shouldPauseAfterFailure(3));
    }

    @Test
    public void firstSubgoalIsReadWithoutChangingTheFullPlan() {
        assertEquals("Open Settings", OnDemandPlanningPolicy.firstSubgoal(
                "1. Open Settings\n2. Enable Wi-Fi"));
        assertEquals("Open Settings", OnDemandPlanningPolicy.firstSubgoal(
                "- Open Settings\n- Enable Wi-Fi"));
        assertEquals("", OnDemandPlanningPolicy.firstSubgoal("\n"));
    }

    @Test
    public void subgoalCompletionRequiresSuccessfulStepVerification() {
        assertEquals("subgoal_completed",
                OnDemandPlanningPolicy.nextPlanReason("SUCCESS", true));
        assertEquals("", OnDemandPlanningPolicy.nextPlanReason("SUCCESS", false));
        assertEquals("", OnDemandPlanningPolicy.nextPlanReason("UNKNOWN", true));
        assertEquals("action_exception", OnDemandPlanningPolicy.nextPlanReason("FAILURE", false));
        assertTrue(OnDemandPlanningPolicy.subgoalChanged("Open Settings", "Enable Wi-Fi"));
        assertFalse(OnDemandPlanningPolicy.subgoalChanged("Open Settings", "open settings"));
    }
}
