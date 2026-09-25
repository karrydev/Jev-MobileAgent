package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class MobileAgentVlmRolesTest {
    @Test
    public void reflectorPromptUsesCurrentFirstActionWithoutPrematureHistoryEntry() throws Exception {
        MobileAgentVlmRoles roles = new MobileAgentVlmRoles();
        JSONObject currentAction = new JSONObject().put("action", "type").put("text", "甲");

        roles.setActionForReflection(currentAction);
        String prompt = roles.reflectorPrompt();

        assertTrue(prompt.contains("### Latest Action ###\nAction: " + currentAction));
        assertFalse(prompt.contains("No actions have been taken yet"));
        assertTrue(roles.actionHistory.isEmpty());
    }

    @Test
    public void reflectorPromptPrefersCurrentActionOverPreviousVerifiedAction() throws Exception {
        MobileAgentVlmRoles roles = new MobileAgentVlmRoles();
        JSONObject previousAction = new JSONObject().put("action", "click").put("coordinate", "old");
        JSONObject currentAction = new JSONObject().put("action", "type").put("text", "current");
        roles.recordAction(previousAction, "previous", "A", "None");

        roles.setActionForReflection(currentAction);
        String prompt = roles.reflectorPrompt();
        int latest = prompt.indexOf("### Latest Action ###");
        int instructions = prompt.indexOf("Carefully examine", latest);
        String latestSection = prompt.substring(latest, instructions);

        assertTrue(latestSection.contains("Action: " + currentAction));
        assertFalse(latestSection.contains(previousAction.toString()));
        assertEquals(1, roles.actionHistory.size());
    }

    @Test
    public void reflectionOutcomeRequiresAnExplicitRoleLabel() {
        assertEquals("A", MobileAgentVlmRoles.reflectionOutcomeLabel("A"));
        assertEquals("B", MobileAgentVlmRoles.reflectionOutcomeLabel("b: failed"));
        assertEquals("C", MobileAgentVlmRoles.reflectionOutcomeLabel("C: no change"));
        assertEquals("", MobileAgentVlmRoles.reflectionOutcomeLabel("INVALID"));
    }

    @Test
    public void advertisedChineseAnswerIsSavedAndStillRequiresFreshGoalVerification() throws Exception {
        String goal = "在中文输入框中输入“独立手机测试成功”";
        String answer = "独立手机测试成功";
        MobileAgentVlmRoles roles = new MobileAgentVlmRoles();
        roles.instruction = goal;

        assertTrue(roles.managerPrompt().contains("perform the `answer` action"));
        assertTrue(roles.executorPrompt().contains("answer(text)"));

        MobileAgentVlmRoles.ActionCommand command = roles.action(
                new JSONObject().put("action", "answer").put("text", "  " + answer + "  ").toString(),
                new JSONObject(), "task-1", 1, "");
        assertTrue(command.answer);
        assertTrue(command.terminal);
        assertEquals(answer, command.answerText);
        assertNull(command.contractAction);

        roles.recordAnswer(command, "Reply to the user");
        JSONObject savedHistory = roles.toJson();
        assertEquals(answer, savedHistory.optString("last_answer", ""));
        assertTrue(savedHistory.optString("progress_status", "").contains(answer));
        assertTrue(savedHistory.optJSONArray("action_history").optString(0).contains(answer));

        MobileAgentVlmRoles restored = new MobileAgentVlmRoles();
        restored.restore(savedHistory);
        JSONObject task = new JSONObject()
                .put("state", "NEEDS_REVIEW")
                .put("runtime_status", "任务待核对")
                .put("history", restored.toJson());
        assertTrue(MainActivity.taskSummary(task).contains("模型答复：" + answer));

        JSONObject unmetPage = new JSONObject()
                .put("availability", "AVAILABLE")
                .put("nodes", new JSONArray().put(new JSONObject()
                        .put("editable", true)
                        .put("content_description", "中文输入框")
                        .put("text", "")));
        StandaloneGoalVerifier.Outcome unmet = StandaloneGoalVerifier.verify(goal, unmetPage);
        assertEquals(StandaloneGoalVerifier.Outcome.NOT_VERIFIED, unmet);
        assertFalse(StandaloneGoalVerifier.mayMarkSucceeded(unmet));

        StandaloneGoalVerifier.Outcome unsupported = StandaloneGoalVerifier.verify(
                "完成任意页面任务", unmetPage);
        assertEquals(StandaloneGoalVerifier.Outcome.UNKNOWN, unsupported);
        assertFalse(StandaloneGoalVerifier.mayMarkSucceeded(unsupported));
    }

    @Test
    public void answerWithoutNonBlankStringTextIsRejected() throws Exception {
        assertInvalidAnswer(new JSONObject().put("action", "answer"));
        assertInvalidAnswer(new JSONObject().put("action", "answer").put("text", JSONObject.NULL));
        assertInvalidAnswer(new JSONObject().put("action", "answer").put("text", 123));
        assertInvalidAnswer(new JSONObject().put("action", "answer").put("text", ""));
        assertInvalidAnswer(new JSONObject().put("action", "answer").put("text", " \n "));
    }

    private static void assertInvalidAnswer(JSONObject raw) throws Exception {
        try {
            new MobileAgentVlmRoles().action(raw.toString(), new JSONObject(), "task-1", 1, "");
            throw new AssertionError("invalid answer text should be rejected");
        } catch (org.json.JSONException expected) {
            // Invalid answer content is rejected before any terminal or device action is returned.
        }
    }
}
