package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Verifies the two controlled-page acceptance goals from a fresh accessibility tree. */
final class StandaloneGoalVerifier {
    enum Outcome {
        VERIFIED,
        NOT_VERIFIED,
        UNKNOWN
    }

    private static final Pattern INPUT_GOAL =
            Pattern.compile("^在中文输入框中输入[“\\\"]([^”\\\"]+)[”\\\"]$");
    private static final String VISUAL_CLICK_GOAL = "点击受控页面中蓝色的视觉手势目标";

    private StandaloneGoalVerifier() {
    }

    static Outcome verify(String goal, JSONObject observation) {
        if (goal == null || observation == null
                || !"AVAILABLE".equals(observation.optString("availability", ""))) {
            return Outcome.UNKNOWN;
        }
        String normalizedGoal = goal.trim();
        Matcher input = INPUT_GOAL.matcher(normalizedGoal);
        if (input.matches()) {
            return verifyInputGoal(input.group(1), observation);
        }
        if (VISUAL_CLICK_GOAL.equals(normalizedGoal)) {
            return verifyVisualGestureGoal(observation);
        }
        return Outcome.UNKNOWN;
    }

    static boolean mayMarkSucceeded(Outcome outcome) {
        return outcome == Outcome.VERIFIED;
    }

    private static Outcome verifyInputGoal(String targetValue, JSONObject observation) {
        String target = targetValue.trim();
        JSONArray nodes = observation.optJSONArray("nodes");
        if (target.isEmpty() || nodes == null) {
            return Outcome.UNKNOWN;
        }
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null && node.optBoolean("editable", false)
                    && "中文输入框".equals(node.optString("content_description", ""))
                    && target.equals(node.optString("text", "").trim())) {
                return Outcome.VERIFIED;
            }
        }
        return Outcome.NOT_VERIFIED;
    }

    private static Outcome verifyVisualGestureGoal(JSONObject observation) {
        JSONArray nodes = observation.optJSONArray("nodes");
        if (nodes == null) {
            return Outcome.UNKNOWN;
        }
        boolean stateObserved = false;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) {
                continue;
            }
            String visibleState = node.optString("text", "") + " "
                    + node.optString("content_description", "");
            if (!visibleState.contains("Visual gesture state")) {
                continue;
            }
            stateObserved = true;
            if (visibleState.contains("coordinate tap completed")) {
                return Outcome.VERIFIED;
            }
        }
        return stateObserved ? Outcome.NOT_VERIFIED : Outcome.UNKNOWN;
    }

}
