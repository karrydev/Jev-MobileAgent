package com.jev.mobileagent;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;

public class ActionExecutionGateTest {
    @Test
    public void controlInvalidationPreventsAQueuedLateAction() {
        ActionExecutionGate gate = new ActionExecutionGate();
        ActionExecutionGate.Token token = gate.begin();
        gate.invalidate();

        AtomicBoolean deviceSideEffect = new AtomicBoolean(false);
        assertFalse(token.runIfCurrent(() -> deviceSideEffect.set(true)));
        assertFalse(deviceSideEffect.get());
    }

    @Test
    public void onlyCurrentRunTokenCanDispatch() {
        ActionExecutionGate gate = new ActionExecutionGate();
        ActionExecutionGate.Token oldToken = gate.begin();
        ActionExecutionGate.Token currentToken = gate.begin();

        AtomicBoolean deviceSideEffect = new AtomicBoolean(false);
        assertFalse(oldToken.runIfCurrent(() -> deviceSideEffect.set(true)));
        assertTrue(currentToken.runIfCurrent(() -> deviceSideEffect.set(true)));
        assertTrue(deviceSideEffect.get());
    }
}
