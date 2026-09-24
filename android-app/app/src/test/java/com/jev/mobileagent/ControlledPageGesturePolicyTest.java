package com.jev.mobileagent;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public final class ControlledPageGesturePolicyTest {
    @Test
    public void onlyTapsInsideThePaintedBlueRectangleComplete() {
        assertTrue(ControlledPageActivity.isInsideVisualTarget(1000, 500, 500, 250));
        assertFalse(ControlledPageActivity.isInsideVisualTarget(1000, 500, 219, 250));
        assertFalse(ControlledPageActivity.isInsideVisualTarget(1000, 500, 781, 250));
        assertFalse(ControlledPageActivity.isInsideVisualTarget(1000, 500, 500, 390.1f));

        assertEquals("coordinate tap completed", ControlledPageActivity.completedVisualGesture(
                1000, 500, 500, 250, 500, 250, 90, true, 40));
        assertNull(ControlledPageActivity.completedVisualGesture(
                1000, 500, 100, 250, 100, 250, 90, false, 40));
        assertNull(ControlledPageActivity.completedVisualGesture(
                1000, 500, 500, 250, 800, 250, 90, false, 40));
    }

    @Test
    public void gestureTypeIsReportedOnlyAfterStayingInsideTarget() {
        assertEquals("long press completed", ControlledPageActivity.completedVisualGesture(
                1000, 500, 500, 250, 500, 250, 600, true, 40));
        assertEquals("swipe completed", ControlledPageActivity.completedVisualGesture(
                1000, 500, 400, 200, 600, 300, 150, true, 40));
        assertNull(ControlledPageActivity.completedVisualGesture(
                1000, 500, 400, 200, 600, 300, 150, false, 40));
    }
}
