package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class StandaloneGoalVerifierTest {
    @Test
    public void managerFinishedClaimCannotSucceedWhenFreshInputPageDoesNotContainTarget() throws Exception {
        JSONObject freshPage = observation(new JSONObject()
                .put("editable", true)
                .put("content_description", "中文输入框")
                .put("text", ""));

        StandaloneGoalVerifier.Outcome outcome = StandaloneGoalVerifier.verify(
                "在中文输入框中输入“独立手机测试成功”", freshPage);

        assertEquals(StandaloneGoalVerifier.Outcome.NOT_VERIFIED, outcome);
        assertFalse(StandaloneGoalVerifier.mayMarkSucceeded(outcome));
    }

    @Test
    public void exactTargetInFreshEditableNodeCanVerifyInputGoal() throws Exception {
        JSONObject freshPage = observation(new JSONObject()
                .put("editable", true)
                .put("content_description", "中文输入框")
                .put("text", "独立手机测试成功"));

        StandaloneGoalVerifier.Outcome outcome = StandaloneGoalVerifier.verify(
                "在中文输入框中输入“独立手机测试成功”", freshPage);

        assertEquals(StandaloneGoalVerifier.Outcome.VERIFIED, outcome);
        assertTrue(StandaloneGoalVerifier.mayMarkSucceeded(outcome));
    }

    @Test
    public void targetTextInAnotherEditableFieldDoesNotVerifyInputGoal() throws Exception {
        JSONObject freshPage = observation(new JSONObject()
                .put("editable", true)
                .put("content_description", "Local VLM task goal")
                .put("text", "独立手机测试成功"));

        StandaloneGoalVerifier.Outcome outcome = StandaloneGoalVerifier.verify(
                "在中文输入框中输入“独立手机测试成功”", freshPage);

        assertEquals(StandaloneGoalVerifier.Outcome.NOT_VERIFIED, outcome);
        assertFalse(StandaloneGoalVerifier.mayMarkSucceeded(outcome));
    }

    @Test
    public void visualGoalRequiresCompletedPageGestureState() throws Exception {
        JSONObject readyPage = observation(new JSONObject()
                .put("text", "Visual gesture state: ready"));
        JSONObject completedPage = observation(new JSONObject()
                .put("text", "Visual gesture state: coordinate tap completed"));

        assertEquals(StandaloneGoalVerifier.Outcome.NOT_VERIFIED,
                StandaloneGoalVerifier.verify("点击受控页面中蓝色的视觉手势目标", readyPage));
        assertEquals(StandaloneGoalVerifier.Outcome.VERIFIED,
                StandaloneGoalVerifier.verify("点击受控页面中蓝色的视觉手势目标", completedPage));
    }

    @Test
    public void compoundInputGoalRemainsUnknownEvenWhenQuotedTextIsPresent() throws Exception {
        JSONObject page = observation(new JSONObject()
                .put("editable", true)
                .put("content_description", "中文输入框")
                .put("text", "甲"));

        assertEquals(StandaloneGoalVerifier.Outcome.UNKNOWN,
                StandaloneGoalVerifier.verify("在中文输入框中输入“甲”并点击发送", page));
    }

    @Test
    public void visualVerifierRejectsWrongGestureTypeAndUnknownVisualGoals() throws Exception {
        JSONObject swipePage = observation(new JSONObject()
                .put("text", "Visual gesture state: swipe completed"));
        JSONObject tapPage = observation(new JSONObject()
                .put("text", "Visual gesture state: coordinate tap completed"));

        assertEquals(StandaloneGoalVerifier.Outcome.NOT_VERIFIED,
                StandaloneGoalVerifier.verify("点击受控页面中蓝色的视觉手势目标", swipePage));
        assertEquals(StandaloneGoalVerifier.Outcome.UNKNOWN,
                StandaloneGoalVerifier.verify("滑动视觉目标", tapPage));
        assertEquals(StandaloneGoalVerifier.Outcome.UNKNOWN,
                StandaloneGoalVerifier.verify("点击受控页面中蓝色的视觉手势目标并输入“甲”", tapPage));
    }

    @Test
    public void unrelatedGoalsRemainUnknownAndCannotBeMarkedSucceeded() throws Exception {
        JSONObject freshPage = observation(new JSONObject().put("text", "Done"));

        StandaloneGoalVerifier.Outcome outcome = StandaloneGoalVerifier.verify("完成任意页面任务", freshPage);

        assertEquals(StandaloneGoalVerifier.Outcome.UNKNOWN, outcome);
        assertFalse(StandaloneGoalVerifier.mayMarkSucceeded(outcome));
    }

    private static JSONObject observation(JSONObject node) throws Exception {
        return new JSONObject()
                .put("availability", "AVAILABLE")
                .put("nodes", new JSONArray().put(node));
    }
}
