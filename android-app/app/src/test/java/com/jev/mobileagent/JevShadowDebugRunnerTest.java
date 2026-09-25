package com.jev.mobileagent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.time.Instant;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

public final class JevShadowDebugRunnerTest {
    @Test
    public void acceptsOneBoundedObservationAndKnownTextOnlyCase() throws Exception {
        JSONObject input = validCase()
                .put("known_parameters", new JSONObject().put("text", "继续"));

        JevShadowDebugRunner.validateCase(input);

        assertEquals("holdout", input.optString("split"));
    }

    @Test
    public void rejectsExpectedAndNestedFutureOrExecutionTruthFields() throws Exception {
        assertInvalid(validCase().put("expected_choice", "candidate-1"));
        JSONObject nested = validCase();
        nested.getJSONObject("observation").put("future_observation", new JSONObject());
        assertInvalid(nested);
        JSONObject execution = validCase();
        execution.getJSONObject("observation").getJSONArray("nodes").getJSONObject(0)
                .put("effect_rules", new JSONArray());
        assertInvalid(execution);
    }

    @Test
    public void rejectsUnsupportedKnownParametersAndTooManyNodes() throws Exception {
        JSONObject unsupported = validCase().put("known_parameters",
                new JSONObject().put("password", "never send"));
        assertInvalid(unsupported);

        JSONArray nodes = new JSONArray();
        for (int i = 0; i < 151; i++) nodes.put(new JSONObject().put("node_id", "n-" + i));
        JSONObject tooMany = validCase();
        tooMany.getJSONObject("observation").put("nodes", nodes);
        assertInvalid(tooMany);
    }

    @Test
    public void extractsOnlyOneUnambiguousUserQuotedTextValue() {
        assertEquals("蓝色杯子", JevShadow.exactQuotedText("请搜索“蓝色杯子”"));
        assertEquals("继续", JevShadow.exactQuotedText("点击「继续」"));
        assertNull(JevShadow.exactQuotedText("请搜索蓝色杯子"));
        assertNull(JevShadow.exactQuotedText("先输入“甲”，再输入“乙”"));
    }

    private static JSONObject validCase() throws Exception {
        JSONObject observation = new JSONObject().put("observation_id", "obs-debug-1")
                .put("observation_version", 1).put("captured_at", Instant.now().toString())
                .put("page_state", "observed")
                .put("nodes", new JSONArray().put(new JSONObject().put("node_id", "continue")
                        .put("role", "button").put("label", "继续").put("enabled", true)
                        .put("actions", new JSONArray().put("tap"))));
        return new JSONObject().put("case_id", "synthetic-positive-1")
                .put("split", "holdout").put("instruction", "点击“继续”按钮")
                .put("observation", observation);
    }

    private static void assertInvalid(JSONObject input) throws Exception {
        try {
            JevShadowDebugRunner.validateCase(input);
            fail("invalid debug case was accepted");
        } catch (IOException expected) {
            // Expected: debug inputs never carry evaluator truth or oversized state.
        }
    }
}
