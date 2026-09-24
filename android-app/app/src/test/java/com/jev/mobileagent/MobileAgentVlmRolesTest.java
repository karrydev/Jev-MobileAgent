package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
