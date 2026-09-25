package com.jev.mobileagent;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public final class VisualChangeGuardTest {
    private static final String FIXTURE_ROOT = "/com/jev/mobileagent/visual-change-guard/";

    @Test
    public void realMissFixtureVetoesVisualSuccessWhenOnlyOwnStatusPixelsChanged() throws Exception {
        FixturePair pair = loadPair("miss");
        VisualChangeGuard.FrameContext context = pair.context();
        assertTrue(context.reason, context.comparable);
        assertEquals("unique_systemui_status_bar_node", context.appBoundsSource);
        assertArrayEquals(new int[] {0, 103, 1080, 2400}, context.appBounds);
        assertArrayEquals(new int[] {0, 2198, 1080, 2343}, context.ignoredStatusBounds);

        VisualChangeGuard.Assessment pixels = compare(pair, context);
        assertEquals(VisualChangeGuard.Change.UNCHANGED, pixels.change);
        TreeActionVerifier.Result guarded = VisualChangeGuard.applyAssessment(
                success("reflector claimed the visible control changed"),
                eventAction(pair.before, "tap"), pixels);

        assertEquals(TreeActionVerifier.Status.UNKNOWN, guarded.status);
        assertEquals("event_action_success_without_visible_app_change", guarded.reason);
        assertEquals("exact_pixel_match", guarded.evidence.optString("comparison"));
    }

    @Test
    public void realHitFixturePreservesModelDecisionForChangeOutsideSelectedActionNode() throws Exception {
        FixturePair pair = loadPair("hit");
        VisualChangeGuard.FrameContext context = pair.context();
        assertTrue(context.reason, context.comparable);
        VisualChangeGuard.Assessment pixels = compare(pair, context);
        assertEquals(VisualChangeGuard.Change.CHANGED, pixels.change);

        JSONObject selectedButton = nodeByText(pair.before, "视觉手势目标");
        assertNotNull(selectedButton);
        int[] selectedBounds = nodeBounds(selectedButton);
        assertTrue("the witnessed result lies outside the selected action node",
                hasChangedPixelOutside(pair.beforePixels, pair.afterPixels,
                        context.appBounds, context.ignoredStatusBounds, selectedBounds));

        TreeActionVerifier.Result kept = VisualChangeGuard.applyAssessment(
                success("the model can still judge semantic outcome"),
                new JSONObject().put("kind", "tap").put("target_node_id",
                        selectedButton.optString("node_id", "")), pixels);
        assertEquals(TreeActionVerifier.Status.SUCCESS, kept.status);
    }

    @Test
    public void exactSinglePixelChangeIsVisibleAndDoesNotPromoteAnUnknownModelDecision() throws Exception {
        int[][] beforePixels = rows(6, 4, 0xff010101);
        int[][] afterPixels = rows(6, 4, 0xff010101);
        afterPixels[2][5] = 0xff010102;
        VisualChangeGuard.Assessment changed = VisualChangeGuard.comparePixelRows(
                rows(beforePixels), rows(afterPixels), 6, 4,
                new int[] {0, 1, 6, 4}, null);

        assertEquals(VisualChangeGuard.Change.CHANGED, changed.change);
        TreeActionVerifier.Result unknown = TreeActionVerifier.decision(
                TreeActionVerifier.Status.UNKNOWN, "model_was_unsure", new JSONObject());
        assertEquals(TreeActionVerifier.Status.UNKNOWN,
                VisualChangeGuard.applyAssessment(unknown, eventAction(null, "tap"), changed).status);
    }

    @Test
    public void unchangedPixelsVetoOnlyEventActionSuccessAndLeaveSetTextRuleAlone() throws Exception {
        int[][] pixels = rows(6, 4, 0xff010101);
        VisualChangeGuard.Assessment unchanged = VisualChangeGuard.comparePixelRows(
                rows(pixels), rows(copy(pixels)), 6, 4,
                new int[] {0, 1, 6, 4}, null);

        TreeActionVerifier.Result event = VisualChangeGuard.applyAssessment(
                success("visual success"), eventAction(null, "coordinate_tap"), unchanged);
        TreeActionVerifier.Result setText = VisualChangeGuard.applyAssessment(
                success("exact tree postcondition"),
                new JSONObject().put("kind", "set_text"), unchanged);
        TreeActionVerifier.Result failedEvent = VisualChangeGuard.applyAssessment(
                TreeActionVerifier.decision(TreeActionVerifier.Status.FAILURE,
                        "model_failure", new JSONObject()), eventAction(null, "tap"), unchanged);

        assertEquals(TreeActionVerifier.Status.UNKNOWN, event.status);
        assertEquals(TreeActionVerifier.Status.SUCCESS, setText.status);
        assertEquals(TreeActionVerifier.Status.FAILURE, failedEvent.status);
    }

    @Test
    public void mismatchedAppWindowOrScreenshotIdentityIsNotComparable() throws Exception {
        FixturePair pair = loadPair("miss");
        JSONObject crossApp = new JSONObject(pair.after.toString());
        activeWindow(crossApp).put("package_name", "example.other");
        assertFalse(VisualChangeGuard.frameContext(pair.before, crossApp,
                pair.beforeShot, pair.afterShot).comparable);

        JSONObject differentWindow = new JSONObject(pair.after.toString());
        differentWindow.getJSONObject("screen").put("active_window_id", 999);
        assertFalse(VisualChangeGuard.frameContext(pair.before, differentWindow,
                pair.beforeShot, pair.afterShot).comparable);

        JSONObject detachedShot = new JSONObject(pair.afterShot.toString());
        detachedShot.put("observation_id", "unrelated-observation");
        VisualChangeGuard.FrameContext detached = VisualChangeGuard.frameContext(pair.before,
                pair.after, pair.beforeShot, detachedShot);
        assertFalse(detached.comparable);
        assertEquals("screenshot_observation_or_frame_mismatch", detached.reason);
    }

    @Test
    public void thirdPartyCannotImpersonateTheFirstPartyStatusMask() throws Exception {
        FixturePair pair = loadPair("miss");
        JSONObject before = new JSONObject(pair.before.toString());
        JSONObject after = new JSONObject(pair.after.toString());
        JSONObject beforeWindow = activeWindow(before);
        JSONObject afterWindow = activeWindow(after);
        beforeWindow.put("package_name", "example.other");
        afterWindow.put("package_name", "example.other");
        for (JSONObject observation : new JSONObject[] {before, after}) {
            JSONObject fake = ownStatusNode(observation);
            fake.put("package_name", "example.other");
        }

        VisualChangeGuard.FrameContext context = VisualChangeGuard.frameContext(
                before, after, pair.beforeShot, pair.afterShot);
        assertTrue(context.reason, context.comparable);
        assertNull(context.ignoredStatusBounds);
        assertFalse(VisualChangeGuard.isOwnStatusNode("example.other", ownStatusNode(before)));
        assertFalse(VisualChangeGuard.isOwnStatusNode("com.jev.mobileagent",
                new JSONObject().put("package_name", "example.other")
                        .put("content_description", "Local VLM task status")));
    }

    @Test
    public void missingObservationIdentityAndUntrustedStatusBarDoNotCompare() throws Exception {
        FixturePair pair = loadPair("miss");
        JSONObject missingId = new JSONObject(pair.after.toString());
        missingId.remove("observation_id");
        assertFalse(VisualChangeGuard.frameContext(pair.before, missingId,
                pair.beforeShot, pair.afterShot).comparable);

        JSONObject missingBar = new JSONObject(pair.after.toString());
        JSONArray nodes = missingBar.getJSONArray("nodes");
        for (int i = nodes.length() - 1; i >= 0; i--) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null && "com.android.systemui:id/status_bar".equals(
                    node.optString("view_id_resource_name", ""))) nodes.remove(i);
        }
        VisualChangeGuard.FrameContext untrusted = VisualChangeGuard.frameContext(pair.before,
                missingBar, pair.beforeShot, pair.afterShot);
        assertFalse(untrusted.comparable);
        assertEquals("target_app_content_bounds_untrusted", untrusted.reason);
    }

    private static VisualChangeGuard.Assessment compare(FixturePair pair,
            VisualChangeGuard.FrameContext context) {
        return VisualChangeGuard.comparePixelRows(
                imageRows(pair.beforePixels), imageRows(pair.afterPixels),
                context.imageWidth, context.imageHeight,
                context.appBounds, context.ignoredStatusBounds);
    }

    private static FixturePair loadPair(String prefix) throws Exception {
        JSONObject before = readJson(prefix + "-before.json");
        JSONObject after = readJson(prefix + "-after.json");
        PngPixels beforePixels = readImage(prefix + "-before.png");
        PngPixels afterPixels = readImage(prefix + "-after.png");
        JSONObject beforeShot = shotFor(before, "BEFORE", prefix + "-shot-before");
        JSONObject afterShot = shotFor(after, "AFTER", prefix + "-shot-after");
        return new FixturePair(before, after, beforeShot, afterShot, beforePixels, afterPixels);
    }

    private static JSONObject readJson(String name) throws Exception {
        try (InputStream input = VisualChangeGuardTest.class.getResourceAsStream(FIXTURE_ROOT + name)) {
            assertNotNull("missing test fixture " + name, input);
            byte[] bytes = input.readAllBytes();
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        }
    }

    private static PngPixels readImage(String name) throws Exception {
        try (InputStream input = VisualChangeGuardTest.class.getResourceAsStream(FIXTURE_ROOT + name)) {
            assertNotNull("missing test fixture " + name, input);
            // Resolve java.desktop only at test runtime so Android's mockable compile jar need not
            // expose java.awt types to source compilation.
            Class<?> imageIo = Class.forName("javax.imageio.ImageIO");
            Object image = imageIo.getMethod("read", InputStream.class).invoke(null, input);
            assertNotNull("invalid PNG fixture " + name, image);
            Class<?> imageClass = image.getClass();
            int width = (Integer) imageClass.getMethod("getWidth").invoke(image);
            int height = (Integer) imageClass.getMethod("getHeight").invoke(image);
            Method readPixels = imageClass.getMethod("getRGB", int.class, int.class,
                    int.class, int.class, int[].class, int.class, int.class);
            int[] pixels = (int[]) readPixels.invoke(image, 0, 0, width, height,
                    null, 0, width);
            return new PngPixels(width, height, pixels);
        }
    }

    private static JSONObject shotFor(JSONObject observation, String type, String screenshotId)
            throws Exception {
        JSONObject screen = observation.getJSONObject("screen");
        // Exported observation JSON and screenshot PNGs are separate records; these fields mirror
        // the production local screenshot callback and bind the real pixels to each observation.
        return new JSONObject().put("screenshot_id", screenshotId)
                .put("observation_id", observation.optString("observation_id", ""))
                .put("capture_type", type)
                .put("width_px", screen.optInt("width_px", 0))
                .put("height_px", screen.optInt("height_px", 0))
                .put("png_base64", "test-fixture-pixels-loaded-from-png-resource");
    }

    private static JSONObject nodeByText(JSONObject observation, String text) {
        JSONArray nodes = observation.optJSONArray("nodes");
        for (int i = 0; nodes != null && i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node != null && text.equals(node.optString("text", ""))) return node;
        }
        return null;
    }

    private static JSONObject ownStatusNode(JSONObject observation) {
        JSONArray nodes = observation.optJSONArray("nodes");
        for (int i = 0; nodes != null && i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (VisualChangeGuard.isOwnStatusNode("com.jev.mobileagent", node)) return node;
        }
        return null;
    }

    private static JSONObject activeWindow(JSONObject observation) {
        JSONArray windows = observation.optJSONArray("windows");
        int activeId = observation.optJSONObject("screen").optInt("active_window_id", -1);
        for (int i = 0; windows != null && i < windows.length(); i++) {
            JSONObject window = windows.optJSONObject(i);
            if (window != null && window.optInt("window_id", -2) == activeId) return window;
        }
        throw new AssertionError("active window missing");
    }

    private static int[] nodeBounds(JSONObject node) {
        JSONObject bounds = node.optJSONObject("bounds");
        return new int[] {bounds.optInt("left"), bounds.optInt("top"),
                bounds.optInt("right"), bounds.optInt("bottom")};
    }

    private static boolean hasChangedPixelOutside(PngPixels before, PngPixels after,
            int[] appBounds, int[] ignoredBounds, int[] selectedBounds) {
        for (int y = appBounds[1]; y < appBounds[3]; y++) {
            for (int x = appBounds[0]; x < appBounds[2]; x++) {
                if (inside(x, y, ignoredBounds) || inside(x, y, selectedBounds)) continue;
                if (before.pixel(x, y) != after.pixel(x, y)) return true;
            }
        }
        return false;
    }

    private static boolean inside(int x, int y, int[] bounds) {
        return bounds != null && x >= bounds[0] && x < bounds[2]
                && y >= bounds[1] && y < bounds[3];
    }

    private static TreeActionVerifier.Result success(String reason) {
        return TreeActionVerifier.decision(TreeActionVerifier.Status.SUCCESS, reason, new JSONObject());
    }

    private static JSONObject eventAction(JSONObject observation, String kind) {
        JSONObject action = new JSONObject();
        try {
            action.put("kind", kind);
            if (observation != null) {
                JSONObject node = nodeByText(observation, "视觉手势目标");
                if (node != null) action.put("target_node_id", node.optString("node_id", ""));
            }
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        return action;
    }

    private static VisualChangeGuard.PixelRows imageRows(PngPixels image) {
        return (x, y, destination) -> System.arraycopy(
                image.pixels, y * image.width + x, destination, 0, destination.length);
    }

    private static VisualChangeGuard.PixelRows rows(int[][] pixels) {
        return (x, y, destination) -> System.arraycopy(pixels[y], x, destination, 0, destination.length);
    }

    private static int[][] rows(int width, int height, int value) {
        int[][] pixels = new int[height][width];
        for (int[] row : pixels) Arrays.fill(row, value);
        return pixels;
    }

    private static int[][] copy(int[][] pixels) {
        int[][] result = new int[pixels.length][];
        for (int i = 0; i < pixels.length; i++) result[i] = Arrays.copyOf(pixels[i], pixels[i].length);
        return result;
    }

    private static final class FixturePair {
        final JSONObject before;
        final JSONObject after;
        final JSONObject beforeShot;
        final JSONObject afterShot;
        final PngPixels beforePixels;
        final PngPixels afterPixels;

        FixturePair(JSONObject before, JSONObject after, JSONObject beforeShot, JSONObject afterShot,
                PngPixels beforePixels, PngPixels afterPixels) {
            this.before = before;
            this.after = after;
            this.beforeShot = beforeShot;
            this.afterShot = afterShot;
            this.beforePixels = beforePixels;
            this.afterPixels = afterPixels;
        }

        VisualChangeGuard.FrameContext context() throws Exception {
            assertEquals(before.getJSONObject("screen").optInt("width_px"), beforePixels.width);
            assertEquals(before.getJSONObject("screen").optInt("height_px"), beforePixels.height);
            assertEquals(after.getJSONObject("screen").optInt("width_px"), afterPixels.width);
            assertEquals(after.getJSONObject("screen").optInt("height_px"), afterPixels.height);
            return VisualChangeGuard.frameContext(before, after, beforeShot, afterShot);
        }
    }

    private static final class PngPixels {
        final int width;
        final int height;
        final int[] pixels;

        PngPixels(int width, int height, int[] pixels) {
            this.width = width;
            this.height = height;
            this.pixels = pixels;
        }

        int pixel(int x, int y) {
            return pixels[y * width + x];
        }
    }
}
