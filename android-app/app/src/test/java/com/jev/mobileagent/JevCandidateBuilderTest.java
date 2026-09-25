package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public final class JevCandidateBuilderTest {
    @Test
    public void buildsTicket11TapCandidateWithStableIdFromCurrentObservation() throws Exception {
        JSONObject observation = observation(1, 100, 400,
                new JSONObject().put("node_id", "save-button").put("role", "button")
                        .put("label", "保存").put("enabled", true).put("actions", new JSONArray().put("tap")));

        JevCandidateBuilder.CandidateSet result = JevCandidateBuilder.build(
                observation, new JSONObject(), 300L);

        assertEquals(null, result.fallbackReason);
        assertEquals(1, result.candidates.size());
        assertEquals("candidate-tap-save-button-6a6f6c8e1e03", result.candidates.get(0).id);
        assertTrue(result.candidates.get(0).description.contains("保存"));
    }

    @Test
    public void buildsChineseInputOnlyFromKnownTextAndPreservesTicket11CandidateId() throws Exception {
        JSONObject observation = observation(1, 100, 400,
                new JSONObject().put("node_id", "search-box").put("role", "text_field")
                        .put("label", "搜索").put("enabled", true)
                        .put("actions", new JSONArray().put(new JSONObject()
                                .put("kind", "input_text").put("parameter", "query"))));

        JevCandidateBuilder.CandidateSet missing = JevCandidateBuilder.build(
                observation, new JSONObject(), 300L);
        JevCandidateBuilder.CandidateSet result = JevCandidateBuilder.build(
                observation, new JSONObject().put("query", "蓝色杯子"), 300L);

        assertEquals("missing_required_parameter", missing.fallbackReason);
        assertEquals(1, missing.missingParameters.size());
        assertEquals("query", missing.missingParameters.get(0));
        assertEquals(1, result.candidates.size());
        assertEquals("candidate-input_text-search-box-fa0f12d67a62", result.candidates.get(0).id);
        assertTrue(result.candidates.get(0).description.contains("蓝色杯子"));
        assertEquals("蓝色杯子", result.candidates.get(0).parameters.getString("text"));
    }

    @Test
    public void rejectsExpiredUnavailableAndEmptyObservationsBeforeAnyNetworkCall() throws Exception {
        JSONObject expired = observation(2, 100, 200,
                new JSONObject().put("node_id", "submit").put("role", "button")
                        .put("label", "提交").put("enabled", true).put("actions", new JSONArray().put("tap")));
        JSONObject empty = observation(3, 100, 400).put("capabilities", new JSONArray().put("system_back"));
        JSONObject unavailable = new JSONObject(expired.toString()).put("page_state", "unavailable");

        assertEquals("observation_expired",
                JevCandidateBuilder.build(expired, new JSONObject(), 300L).fallbackReason);
        assertEquals("empty_candidates",
                JevCandidateBuilder.build(empty, new JSONObject(), 300L).fallbackReason);
        assertEquals("observation_unavailable",
                JevCandidateBuilder.build(unavailable, new JSONObject(), 150L).fallbackReason);
    }

    @Test
    public void onlyAddsBackWhenAnObservationDeclaresAnExplicitBackAction() throws Exception {
        JSONObject capableButEmpty = observation(1, 100, 400)
                .put("capabilities", new JSONArray().put("system_back"));
        JSONObject explicitBack = observation(2, 100, 400,
                new JSONObject().put("node_id", "system-back").put("role", "system_navigation")
                        .put("enabled", true).put("actions", new JSONArray().put("back")))
                .put("capabilities", new JSONArray().put("system_back"));

        assertEquals("empty_candidates",
                JevCandidateBuilder.build(capableButEmpty, new JSONObject(), 300L).fallbackReason);
        JevCandidateBuilder.CandidateSet result = JevCandidateBuilder.build(
                explicitBack, new JSONObject(), 300L);
        assertEquals(1, result.candidates.size());
        assertEquals("back", result.candidates.get(0).kind);
    }

    @Test
    public void limitsCandidateCountAndIgnoresDisabledOrInvisibleNodes() throws Exception {
        JSONArray nodes = new JSONArray();
        for (int i = 0; i < JevCandidateBuilder.MAX_CANDIDATES + 4; i++) {
            nodes.put(new JSONObject().put("node_id", "node-" + i).put("role", "button")
                    .put("label", "按钮" + i).put("enabled", i != 0)
                    .put("visible_to_user", i != 1).put("actions", new JSONArray().put("tap")));
        }
        JSONObject observation = new JSONObject().put("observation_id", "obs-many")
                .put("observation_version", 1).put("captured_at", 100).put("expires_at", 400)
                .put("page_state", "observed").put("nodes", nodes);

        JevCandidateBuilder.CandidateSet result = JevCandidateBuilder.build(
                observation, new JSONObject(), 300L);

        assertEquals(JevCandidateBuilder.MAX_CANDIDATES, result.candidates.size());
        assertFalse("node-0".equals(result.candidates.get(0).nodeId));
        assertFalse("node-1".equals(result.candidates.get(0).nodeId));
    }

    @Test
    public void recognizesLegalEquivalentActionWithoutReadingAnyReferenceTruth() throws Exception {
        JSONObject target = new JSONObject().put("node_id", "continue").put("role", "button")
                .put("label", "继续").put("enabled", true).put("actions", new JSONArray().put(
                        new JSONObject().put("kind", "tap").put("equivalence_group", "continue")))
                .put("bounds", new JSONObject().put("left", 0).put("top", 0)
                        .put("right", 20).put("bottom", 20));
        JSONObject alias = new JSONObject().put("node_id", "next").put("role", "button")
                .put("label", "下一步").put("enabled", true).put("actions", new JSONArray().put(
                        new JSONObject().put("kind", "tap").put("equivalence_group", "continue")))
                .put("bounds", new JSONObject().put("left", 21).put("top", 0)
                        .put("right", 40).put("bottom", 20));
        JevCandidateBuilder.CandidateSet result = JevCandidateBuilder.build(
                observation(1, 100, 400, target, alias), new JSONObject(), 300L);
        JSONObject action = new JSONObject().put("kind", "coordinate_tap")
                .put("parameters", new JSONObject().put("x", 10).put("y", 10));

        String relation = result.compareRecommendation(result.candidates.get(1).id, action);

        assertEquals("legal_equivalent_action", relation);
        assertNotNull(result.candidateMetadata().getJSONArray("candidates"));
    }

    @Test
    public void explicitEquivalenceRequiresSameActionKindAndCompleteParameters() throws Exception {
        JSONObject tap = new JSONObject().put("node_id", "search-button").put("role", "button")
                .put("enabled", true).put("actions", new JSONArray().put(new JSONObject()
                        .put("kind", "tap").put("equivalence_group", "shared")))
                .put("bounds", new JSONObject().put("left", 0).put("top", 0)
                        .put("right", 20).put("bottom", 20));
        JSONObject input = new JSONObject().put("node_id", "search-box").put("role", "text_field")
                .put("enabled", true).put("actions", new JSONArray().put(new JSONObject()
                        .put("kind", "input_text").put("equivalence_group", "shared")))
                .put("bounds", new JSONObject().put("left", 21).put("top", 0)
                        .put("right", 40).put("bottom", 20));
        JevCandidateBuilder.CandidateSet differentKinds = JevCandidateBuilder.build(
                observation(1, 100, 400, tap, input), new JSONObject().put("text", "蓝色杯子"), 300L);
        JSONObject setText = new JSONObject().put("kind", "set_text").put("target_node_id", "search-box")
                .put("parameters", new JSONObject().put("text", "蓝色杯子"));

        assertEquals("different_action", differentKinds.compareRecommendation(
                differentKinds.candidates.get(0).id, setText));

        JSONObject first = new JSONObject().put("node_id", "first-box").put("role", "text_field")
                .put("enabled", true).put("actions", new JSONArray().put(new JSONObject()
                        .put("kind", "input_text").put("parameter", "first")
                        .put("equivalence_group", "shared")));
        JSONObject second = new JSONObject().put("node_id", "second-box").put("role", "text_field")
                .put("enabled", true).put("actions", new JSONArray().put(new JSONObject()
                        .put("kind", "input_text").put("parameter", "second")
                        .put("equivalence_group", "shared")));
        JevCandidateBuilder.CandidateSet differentParameters = JevCandidateBuilder.build(
                observation(2, 100, 400, first, second),
                new JSONObject().put("first", "蓝色杯子").put("second", "红色杯子"), 300L);
        JSONObject secondText = new JSONObject().put("kind", "set_text").put("target_node_id", "second-box")
                .put("parameters", new JSONObject().put("text", "红色杯子"));

        assertEquals("different_action", differentParameters.compareRecommendation(
                differentParameters.candidates.get(0).id, secondText));
    }

    @Test
    public void ambiguousOverlappingCoordinatesStayAmbiguousEvenWhenJevChoosesOneTarget() throws Exception {
        JSONObject parent = new JSONObject().put("node_id", "parent").put("role", "button")
                .put("enabled", true).put("actions", new JSONArray().put(new JSONObject()
                        .put("kind", "tap").put("equivalence_group", "same")))
                .put("bounds", new JSONObject().put("left", 0).put("top", 0)
                        .put("right", 100).put("bottom", 100));
        JSONObject child = new JSONObject().put("node_id", "child").put("role", "button")
                .put("enabled", true).put("actions", new JSONArray().put(new JSONObject()
                        .put("kind", "tap").put("equivalence_group", "same")))
                .put("bounds", new JSONObject().put("left", 20).put("top", 20)
                        .put("right", 80).put("bottom", 80));
        JevCandidateBuilder.CandidateSet result = JevCandidateBuilder.build(
                observation(1, 100, 400, parent, child), new JSONObject(), 300L);
        JSONObject action = new JSONObject().put("kind", "coordinate_tap")
                .put("parameters", new JSONObject().put("x", 50).put("y", 50));

        assertEquals("vlm_candidate_ambiguous",
                result.compareRecommendation(result.candidates.get(1).id, action));
        assertEquals(null, result.compareVlmAction(action));
        assertEquals("vlm_candidate_ambiguous", result.vlmCoverageStatus(action));
    }

    @Test
    public void missingVlmCandidateAndInvalidChoiceHaveDistinctClassifications() throws Exception {
        JSONObject button = new JSONObject().put("node_id", "save").put("role", "button")
                .put("enabled", true).put("actions", new JSONArray().put("tap"))
                .put("bounds", new JSONObject().put("left", 0).put("top", 0)
                        .put("right", 20).put("bottom", 20));
        JevCandidateBuilder.CandidateSet result = JevCandidateBuilder.build(
                observation(1, 100, 400, button), new JSONObject(), 300L);
        JSONObject outside = new JSONObject().put("kind", "coordinate_tap")
                .put("parameters", new JSONObject().put("x", 50).put("y", 50));
        JSONObject inside = new JSONObject().put("kind", "coordinate_tap")
                .put("parameters", new JSONObject().put("x", 10).put("y", 10));

        assertEquals("vlm_candidate_missing", result.compareRecommendation(result.candidates.get(0).id, outside));
        assertEquals("invalid_choice", result.compareRecommendation("not-a-candidate", inside));
        assertEquals(null, result.compareVlmAction(outside));
        assertEquals("vlm_candidate_missing", result.vlmCoverageStatus(outside));
        assertEquals("matched", result.vlmCoverageStatus(inside));
    }

    @Test
    public void exactKnownTextCoverageRequiresTheSameObservedInputAndCompleteParameters() throws Exception {
        JSONObject input = new JSONObject().put("node_id", "query").put("role", "text_field")
                .put("enabled", true).put("actions", new JSONArray().put("input_text"));
        JevCandidateBuilder.CandidateSet result = JevCandidateBuilder.build(
                observation(1, 100, 400, input), new JSONObject().put("text", "独立手机测试成功"), 300L);
        JSONObject matching = new JSONObject().put("kind", "set_text").put("target_node_id", "query")
                .put("parameters", new JSONObject().put("text", "独立手机测试成功"));
        JSONObject otherTarget = new JSONObject(matching.toString()).put("target_node_id", "another-input");
        JSONObject otherText = new JSONObject(matching.toString()).put("parameters",
                new JSONObject().put("text", "不同文本"));

        assertEquals("matched", result.vlmCoverageStatus(matching));
        assertEquals("vlm_candidate_missing", result.vlmCoverageStatus(otherTarget));
        assertEquals("vlm_candidate_missing", result.vlmCoverageStatus(otherText));
    }

    @Test
    public void takesKnownScrollDirectionFromClosedActionTemplate() throws Exception {
        JSONObject node = new JSONObject().put("node_id", "list")
                .put("role", "list").put("label", "结果列表").put("enabled", true)
                .put("actions", new JSONArray().put(new JSONObject()
                        .put("kind", "scroll").put("direction", "down")));

        JevCandidateBuilder.CandidateSet result = JevCandidateBuilder.build(
                observation(1, 100, 400, node), new JSONObject(), 300L);

        assertEquals(1, result.candidates.size());
        assertEquals("down", result.candidates.get(0).parameters.getString("direction"));
    }

    private static JSONObject observation(int version, long capturedAt, long expiresAt,
            JSONObject... nodes) throws Exception {
        JSONArray array = new JSONArray();
        for (JSONObject node : nodes) array.put(node);
        return new JSONObject().put("observation_id", "obs-" + version)
                .put("observation_version", version).put("captured_at", capturedAt)
                .put("expires_at", expiresAt).put("page_state", "observed").put("nodes", array);
    }
}
