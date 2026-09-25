package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class JevShadowTest {
    @Test
    public void treeVerificationUsesOnlyTheFrozenFourStateChoices() throws Exception {
        JSONObject criteria = JevShadow.verificationCriteria();

        assertEquals(4, criteria.length());
        assertTrue(criteria.has("SUCCESS"));
        assertTrue(criteria.has("FAILURE"));
        assertTrue(criteria.has("PENDING"));
        assertTrue(criteria.has("UNKNOWN"));
    }

    @Test
    public void validHighConfidenceChoiceCannotBypassMissingVlmCandidateCoverage() throws Exception {
        JSONObject button = new JSONObject().put("node_id", "preset-button").put("role", "button")
                .put("enabled", true).put("actions", new JSONArray().put("tap"))
                .put("bounds", new JSONObject().put("left", 0).put("top", 0)
                        .put("right", 20).put("bottom", 20));
        JSONObject observation = new JSONObject().put("observation_id", "obs-visual")
                .put("observation_version", 1).put("captured_at", 100).put("expires_at", 400)
                .put("page_state", "observed").put("nodes", new JSONArray().put(button));
        JevCandidateBuilder.CandidateSet candidates = JevCandidateBuilder.build(
                observation, new JSONObject(), 300L);
        String choiceId = candidates.candidates.get(0).id;
        JSONObject report = new JSONObject().put("status", "valid_recommendation")
                .put("choice_id", choiceId).put("confidence", 0.99)
                .put("candidate_coverage_status", "vlm_candidate_missing");

        assertFalse(JevShadow.mayDispatchControlledChoice(candidates, report));
        report.put("candidate_coverage_status", "matched");
        assertTrue(JevShadow.mayDispatchControlledChoice(candidates, report));
        report.put("status", "valid_low_confidence");
        assertFalse(JevShadow.mayDispatchControlledChoice(candidates, report));
    }

    @Test
    public void usesParsedVlmTextOnlyWhenTheInstructionHasNoUniqueQuotedValue() throws Exception {
        JSONObject observation = new JSONObject().put("observation_id", "obs-text")
                .put("observation_version", 1).put("captured_at", 100).put("expires_at", 400)
                .put("page_state", "observed")
                .put("nodes", new JSONArray().put(new JSONObject().put("node_id", "search-box")
                        .put("role", "text_field").put("enabled", true)
                        .put("actions", new JSONArray().put(new JSONObject()
                                .put("kind", "input_text").put("parameter", "query")))));
        JSONObject vlmAction = new JSONObject().put("kind", "set_text")
                .put("target_node_id", "search-box")
                .put("parameters", new JSONObject().put("text", "蓝色杯子"));

        JevShadow.TaskKnownParameters known = JevShadow.knownParametersForTask(
                "在搜索框输入想找的商品", observation, vlmAction);

        assertEquals("vlm_set_text", known.source);
        assertEquals("蓝色杯子", known.parameters.getString("text"));
        assertEquals("蓝色杯子", known.parameters.getString("query"));
        JevCandidateBuilder.CandidateSet candidates = JevCandidateBuilder.build(observation,
                known.parameters, 300L);
        assertEquals(1, candidates.candidates.size());
        assertEquals("蓝色杯子", candidates.candidates.get(0).parameters.getString("text"));
    }

    @Test
    public void quotedTextWinsAndInvalidVlmTextDoesNotBecomeAParameter() throws Exception {
        JSONObject action = new JSONObject().put("kind", "set_text")
                .put("parameters", new JSONObject().put("text", "模型文本"));
        JevShadow.TaskKnownParameters quoted = JevShadow.knownParametersForTask(
                "输入“用户指定”", new JSONObject(), action);
        assertEquals("instruction_quote", quoted.source);
        assertEquals("用户指定", quoted.parameters.getString("text"));

        JevShadow.TaskKnownParameters ambiguous = JevShadow.knownParametersForTask(
                "输入“甲”再输入“乙”", new JSONObject(), action);
        assertEquals("vlm_set_text", ambiguous.source);
        assertEquals("模型文本", ambiguous.parameters.getString("text"));

        JevShadow.TaskKnownParameters tooLong = JevShadow.knownParametersForTask(
                "请填写", new JSONObject(), new JSONObject().put("kind", "set_text")
                        .put("parameters", new JSONObject().put("text", repeat('x', 501))));
        assertEquals("none", tooLong.source);
        assertFalse(tooLong.parameters.has("text"));
    }

    private static String repeat(char value, int count) {
        StringBuilder result = new StringBuilder(count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
