package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
    public void treeReflectorUsesTheActualSelectedActionAndRequiresFourStates() throws Exception {
        MobileAgentVlmRoles roles = new MobileAgentVlmRoles();
        JSONObject selected = new JSONObject().put("action", "jev_candidate")
                .put("candidate_id", "tap-node-7");
        roles.instruction = "点击蓝框";
        roles.lastSummary = "tap the visual target";
        roles.setActionForReflection(selected);

        String prompt = roles.treeReflectorPrompt();
        String[] parsed = roles.parseTreeReflection(
                "### Status ###\nPENDING\n\n### Reason ###\nLoading remains visible.");

        assertTrue(prompt.contains("Action actually dispatched: " + selected));
        assertTrue(prompt.contains("not proof of the action result"));
        assertTrue(prompt.contains("SUCCESS, FAILURE, PENDING, or UNKNOWN"));
        assertEquals("PENDING", parsed[0]);
        assertTrue(parsed[1].contains("Loading remains visible"));
    }

    @Test
    public void jevCandidateReplacesVlmDraftInBoundActionReflectorAndRoleHistory() throws Exception {
        JSONObject node = new JSONObject().put("node_id", "search-box").put("role", "text_field")
                .put("content_description", "搜索框").put("enabled", true)
                .put("actions", new JSONArray().put("input_text"))
                .put("bounds", new JSONObject().put("left", 10).put("top", 20)
                        .put("right", 210).put("bottom", 70));
        JSONObject observation = new JSONObject().put("observation_id", "obs-jev")
                .put("observation_version", 7).put("captured_at", 100).put("expires_at", 400)
                .put("page_state", "observed")
                .put("screen", new JSONObject().put("width_px", 400).put("height_px", 800))
                .put("nodes", new JSONArray().put(node));
        JevCandidateBuilder.CandidateSet candidates = JevCandidateBuilder.build(
                observation, new JSONObject().put("text", "独立手机测试成功"), 300L);
        JSONObject vlmDraft = new JSONObject().put("kind", "system_back");
        MobileAgentVlmRoles roles = new MobileAgentVlmRoles();
        roles.setActionForReflection(vlmDraft);

        MobileAgentVlmRoles.ActionCommand command = roles.actionForCandidate(
                candidates.candidates.get(0), observation, "task-1", 3, "before-1");

        assertEquals("set_text", command.contractAction.optString("kind"));
        assertEquals("search-box", command.contractAction.optString("target_node_id"));
        assertEquals("独立手机测试成功",
                command.contractAction.getJSONObject("parameters").optString("text"));
        assertEquals("obs-jev", command.contractAction.optString("observation_id"));
        assertEquals(7, command.contractAction.optInt("observation_version"));
        assertEquals("local-vlm-action-task-1-3", command.contractAction.optString("action_id"));
        assertEquals("jev-controlled-candidate-v1", command.contractAction.optString("source"));
        assertEquals(candidates.candidates.get(0).id,
                command.contractAction.optString("jev_candidate_id"));

        roles.setActionForReflection(command.original);
        String reflector = roles.reflectorPrompt();
        assertTrue(reflector.contains(candidates.candidates.get(0).id));
        assertFalse(reflector.contains(vlmDraft.toString()));
        roles.recordAction(command.original, candidates.candidates.get(0).description, "A", "None");
        assertTrue(roles.toJson().optJSONArray("action_history").optString(0)
                .contains(candidates.candidates.get(0).id));
        assertNotNull(command.original);
    }

    @Test
    public void reflectionOutcomeRequiresAnExplicitRoleLabel() {
        assertEquals("A", MobileAgentVlmRoles.reflectionOutcomeLabel("A"));
        assertEquals("B", MobileAgentVlmRoles.reflectionOutcomeLabel("b: failed"));
        assertEquals("C", MobileAgentVlmRoles.reflectionOutcomeLabel("C: no change"));
        assertEquals("", MobileAgentVlmRoles.reflectionOutcomeLabel("INVALID"));
    }

    @Test
    public void planningProgressHintIsOptInAndCannotStandInForVerification() {
        MobileAgentVlmRoles roles = new MobileAgentVlmRoles();
        assertFalse(roles.executorPrompt().contains("### Subgoal Status ###"));
        assertTrue(roles.executorPrompt(true).contains("### Subgoal Status ###"));
        assertTrue(MobileAgentVlmRoles.executorMarkedSubgoalComplete(
                "### Subgoal Status ###\nCOMPLETE\n"));
        assertFalse(MobileAgentVlmRoles.executorMarkedSubgoalComplete(
                "### Subgoal Status ###\nIN_PROGRESS\n"));
        assertFalse(MobileAgentVlmRoles.executorMarkedSubgoalComplete("no planning status"));
        String[] parsed = roles.parseExecutor("### Thought ###\nReason\n### Action ###\n{\"action\":\"click\"}"
                + "\n### Description ###\nTap the next control\n### Subgoal Status ###\nCOMPLETE");
        assertEquals("Tap the next control", parsed[2]);
    }

    @Test
    public void taskSummaryReportsPlanningDecisionsFromTheLocalTrace() throws Exception {
        JSONObject task = new JSONObject()
                .put("state", "PAUSED")
                .put("on_demand_planning_enabled", true)
                .put("planning_events", new JSONArray()
                        .put(new JSONObject().put("event", "planner_decision")
                                .put("decision", "replan").put("reason", "task_start"))
                        .put(new JSONObject().put("event", "planner_decision")
                                .put("decision", "reuse_plan").put("reason", "plan_current")));

        String summary = MainActivity.taskSummary(task);
        assertTrue(summary.contains("1 次重新规划，1 步复用计划"));
        assertTrue(summary.contains("最近决策：plan_current"));
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
