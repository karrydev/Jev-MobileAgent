package com.jev.mobileagent;

import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Small, deterministic scheduling rules for the opt-in on-demand planner. */
final class OnDemandPlanningPolicy {
    /** Reuse a plan for at most two completed actions before checking that it is still current. */
    static final int MAX_PLAN_AGE_STEPS = 2;
    static final int MAX_CONSECUTIVE_FAILURES = 2;

    private OnDemandPlanningPolicy() {
    }

    /** Empty means reuse the current plan; otherwise the value is the durable replan reason. */
    static String replanReason(boolean hasPlan, String pendingReason, int currentStep,
            int lastPlanStep, boolean actionLoop) {
        if (!hasPlan) return "task_start";
        if (pendingReason != null && !pendingReason.trim().isEmpty()) return pendingReason.trim();
        if (actionLoop) return "action_loop";
        if (lastPlanStep < 0 || currentStep - lastPlanStep >= MAX_PLAN_AGE_STEPS) {
            return "plan_expired";
        }
        return "";
    }

    static boolean shouldPauseAfterFailure(int consecutiveFailures) {
        return consecutiveFailures >= MAX_CONSECUTIVE_FAILURES;
    }

    static boolean shouldStopPersistentLoop(boolean actionLoop, boolean loopReplanAttempted,
            boolean resumePlanPending) {
        return actionLoop && loopReplanAttempted && !resumePlanPending;
    }

    static final class SchedulerState {
        final String pendingReason;
        final int consecutiveFailures;
        final boolean loopReplanAttempted;

        SchedulerState(String pendingReason, int consecutiveFailures, boolean loopReplanAttempted) {
            this.pendingReason = pendingReason == null ? "" : pendingReason;
            this.consecutiveFailures = Math.max(0, consecutiveFailures);
            this.loopReplanAttempted = loopReplanAttempted;
        }

        JSONObject toJson() throws JSONException {
            return new JSONObject()
                    .put("pending_reason", pendingReason)
                    .put("consecutive_failures", consecutiveFailures)
                    .put("loop_replan_attempted", loopReplanAttempted);
        }
    }

    /** Restore the latest durable scheduler checkpoint, deriving legacy event-only records. */
    static SchedulerState restoreSchedulerState(JSONArray planningEvents, List<String> actionHistory,
            List<String> actionOutcomes) {
        String pendingReason = "";
        int consecutiveFailures = 0;
        boolean loopReplanAttempted = false;
        int latestVerifiedStep = 0;

        if (planningEvents != null) {
            for (int i = 0; i < planningEvents.length(); i++) {
                JSONObject event = planningEvents.optJSONObject(i);
                if (event == null) continue;
                JSONObject checkpoint = event.optJSONObject("scheduler_state");
                if (checkpoint != null) {
                    pendingReason = checkpoint.optString("pending_reason", "");
                    consecutiveFailures = Math.max(0, checkpoint.optInt("consecutive_failures", 0));
                    loopReplanAttempted = checkpoint.optBoolean("loop_replan_attempted", false);
                } else {
                    String eventName = event.optString("event", "");
                    if ("planner_decision".equals(eventName)) {
                        loopReplanAttempted = event.optBoolean("action_loop", false)
                                || "action_loop".equals(event.optString("reason", ""));
                    } else if ("plan_updated".equals(eventName)) {
                        pendingReason = "";
                    } else if ("verified_step_outcome".equals(eventName)) {
                        int step = event.optInt("step", 0);
                        latestVerifiedStep = Math.max(latestVerifiedStep, step);
                        String status = event.optString("verification_status", "");
                        pendingReason = event.optString("next_plan_reason",
                                nextPlanReason(status, event.optBoolean("executor_subgoal_complete_hint", false)));
                        if ("SUCCESS".equals(status)) {
                            consecutiveFailures = 0;
                            loopReplanAttempted = false;
                        } else if ("FAILURE".equals(status)) {
                            consecutiveFailures++;
                        }
                    }
                }
                if ("verified_step_outcome".equals(event.optString("event", ""))) {
                    latestVerifiedStep = Math.max(latestVerifiedStep, event.optInt("step", 0));
                }
            }
        }

        // A crash may land after role history was saved but before its outcome event was appended.
        if (actionOutcomes != null) {
            for (int i = latestVerifiedStep; i < actionOutcomes.size(); i++) {
                if (isTerminalAnswerAction(actionHistory, i)) continue;
                String outcome = actionOutcomes.get(i);
                if ("A".equals(outcome)) {
                    consecutiveFailures = 0;
                    loopReplanAttempted = false;
                    pendingReason = "";
                } else {
                    consecutiveFailures++;
                    pendingReason = "action_exception";
                }
            }
        }
        return new SchedulerState(pendingReason, consecutiveFailures, loopReplanAttempted);
    }

    /** A confirmed resume gets a fresh plan while preserving both stop counters. */
    static SchedulerState forResume(SchedulerState state) {
        if (state == null) return new SchedulerState("resume_replan", 0, false);
        return new SchedulerState("resume_replan", state.consecutiveFailures, state.loopReplanAttempted);
    }

    /**
     * Detects a stuck action or a two-action cycle from the role history. This only requests a
     * planner refresh; the service applies a separate bounded stop if the same loop persists.
     */
    static boolean hasActionLoop(List<String> actionHistory) {
        return hasActionLoop(actionHistory, null, null);
    }

    /** Repeated actions count as a loop only when their latest occurrence made no verified progress. */
    static boolean hasActionLoop(List<String> actionHistory, List<String> actionOutcomes,
            JSONArray planningEvents) {
        if (actionHistory == null || actionHistory.size() < 2) return false;
        int size = actionHistory.size();
        String previous = normalizeAction(actionHistory.get(size - 2));
        String latest = normalizeAction(actionHistory.get(size - 1));
        if (!previous.isEmpty() && previous.equals(latest)) {
            return !hasVerifiedProgress(size - 1, actionOutcomes, planningEvents);
        }
        if (size < 4) return false;
        String first = normalizeAction(actionHistory.get(size - 4));
        String second = normalizeAction(actionHistory.get(size - 3));
        return !first.isEmpty() && !second.isEmpty()
                && !first.equals(second)
                && first.equals(previous)
                && second.equals(latest)
                && !hasVerifiedProgress(size - 2, actionOutcomes, planningEvents)
                && !hasVerifiedProgress(size - 1, actionOutcomes, planningEvents);
    }

    /** A fresh scene that differs from the last verified post-action scene breaks a stale loop. */
    static boolean sceneChangedSinceLastVerifiedStep(JSONArray planningEvents, String currentFingerprint) {
        if (planningEvents == null || currentFingerprint == null || currentFingerprint.isEmpty()) return false;
        for (int i = planningEvents.length() - 1; i >= 0; i--) {
            JSONObject event = planningEvents.optJSONObject(i);
            if (event == null || !"verified_step_outcome".equals(event.optString("event", ""))) continue;
            String previousFingerprint = event.optString("after_observation_sha256", "");
            return !previousFingerprint.isEmpty() && !previousFingerprint.equals(currentFingerprint);
        }
        return false;
    }

    static boolean executorCompletionHintApplies(boolean executorMarkedComplete, boolean jevSelectedAction,
            String selectionRelation) {
        if (!executorMarkedComplete) return false;
        if (!jevSelectedAction) return true;
        return "same_action".equals(selectionRelation) || "legal_equivalent_action".equals(selectionRelation);
    }

    static String firstSubgoal(String plan) {
        if (plan == null) return "";
        for (String line : plan.split("\\R")) {
            String value = line.trim();
            if (value.isEmpty()) continue;
            value = value.replaceFirst("^(?:[-*•]|\\d+[.)])\\s*", "").trim();
            return value;
        }
        return "";
    }

    static boolean subgoalChanged(String previous, String next) {
        String before = previous == null ? "" : previous.trim();
        String after = next == null ? "" : next.trim();
        return !before.isEmpty() && !after.isEmpty() && !before.equalsIgnoreCase(after);
    }

    static String nextPlanReason(String verificationStatus, boolean executorMarkedComplete) {
        if ("SUCCESS".equals(verificationStatus)) {
            return executorMarkedComplete ? "subgoal_completed" : "";
        }
        if ("FAILURE".equals(verificationStatus)) return "action_exception";
        return "";
    }

    /** Combines the verified outcome with the action Jev actually selected for execution. */
    static String nextPlanReason(String verificationStatus, boolean executorMarkedComplete,
            boolean jevSelectedAction, String selectionRelation) {
        if ("SUCCESS".equals(verificationStatus) && jevSelectedAction
                && "different_action".equals(selectionRelation)) {
            return "jev_action_changed";
        }
        return nextPlanReason(verificationStatus,
                executorCompletionHintApplies(executorMarkedComplete, jevSelectedAction,
                        selectionRelation));
    }

    private static String normalizeAction(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean isTerminalAnswerAction(List<String> actionHistory, int index) {
        if (actionHistory == null || index < 0 || index >= actionHistory.size()) return false;
        try {
            return "answer".equals(new JSONObject(actionHistory.get(index)).optString("action", ""));
        } catch (JSONException ignored) {
            return false;
        }
    }

    private static boolean hasVerifiedProgress(int zeroBasedStep, List<String> actionOutcomes,
            JSONArray planningEvents) {
        if (actionOutcomes != null && zeroBasedStep >= 0 && zeroBasedStep < actionOutcomes.size()
                && "A".equals(actionOutcomes.get(zeroBasedStep))) {
            return true;
        }
        int oneBasedStep = zeroBasedStep + 1;
        if (planningEvents == null) return false;
        for (int i = planningEvents.length() - 1; i >= 0; i--) {
            JSONObject event = planningEvents.optJSONObject(i);
            if (event == null || !"verified_step_outcome".equals(event.optString("event", ""))
                    || event.optInt("step", -1) != oneBasedStep) {
                continue;
            }
            return "SUCCESS".equals(event.optString("verification_status", ""))
                    || event.optBoolean("observation_changed", false);
        }
        return false;
    }
}
