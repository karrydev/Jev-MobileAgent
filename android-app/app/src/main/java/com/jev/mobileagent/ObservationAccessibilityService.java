package com.jev.mobileagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * The device-side observation bridge. All node access happens on the service
 * thread; only the JSON document crosses to the network worker.
 */
public class ObservationAccessibilityService extends AccessibilityService {
    private static final String TAG = "JevObservation";
    private static final int MAX_NODES = 512;
    private static final long AUTO_CAPTURE_DELAY_MS = 250L;
    private static final long POST_ACTION_CAPTURE_DELAY_MS = 350L;
    private static volatile ObservationAccessibilityService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private volatile long lastCapturedVersion;
    /**
     * The node handles from the accepted before observation.  Keeping the
     * handles, rather than looking up a path again at execution time, makes a
     * page replacement fail closed even when it happens to reuse the same
     * traversal path and label.
     */
    private final Map<String, NodeBinding> lastNodeBindings = new HashMap<>();
    /**
     * A task owns the observation stream from its fresh before frame through
     * its receipt.  Accessibility events still arrive while the task runs,
     * but they must not replace the observation to which the action is bound.
     */
    private volatile boolean taskCaptureHeld;

    private static final class NodeBinding {
        final AccessibilityNodeInfo node;
        final int windowId;
        final String packageName;
        final String className;
        final String viewIdResourceName;
        final String uniqueId;
        final Rect bounds;

        NodeBinding(AccessibilityNodeInfo source) {
            node = AccessibilityNodeInfo.obtain(source);
            windowId = source.getWindowId();
            packageName = text(source.getPackageName());
            className = text(source.getClassName());
            viewIdResourceName = viewId(source);
            uniqueId = uniqueId(source);
            Rect sourceBounds = new Rect();
            source.getBoundsInScreen(sourceBounds);
            bounds = new Rect(sourceBounds);
        }
    }
    private final Runnable autoCapture = new Runnable() {
        @Override
        public void run() {
            if (BridgeConfig.captureEnabled(ObservationAccessibilityService.this) && !taskCaptureHeld) {
                captureNow(BridgeConfig.load(ObservationAccessibilityService.this), null);
            }
        }
    };

    public interface CaptureCallback {
        void onSuccess(JSONObject acknowledgement);

        void onError(String code, String message);
    }

    /** Callback for an action executed through the live Accessibility service. */
    public interface ActionCallback {
        void onSuccess();

        void onError(String code, String message);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Log.i(TAG, "Accessibility service connected");
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            setServiceInfo(info);
        }
        if (BridgeConfig.captureEnabled(this)) {
            mainHandler.postDelayed(autoCapture, AUTO_CAPTURE_DELAY_MS);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!BridgeConfig.captureEnabled(this) || taskCaptureHeld) {
            return;
        }
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                || type == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                || type == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            mainHandler.removeCallbacks(autoCapture);
            mainHandler.postDelayed(autoCapture, AUTO_CAPTURE_DELAY_MS);
        }
    }

    @Override
    public void onInterrupt() {
        // The visible activity reports an interrupted/disconnected capture.
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Accessibility service destroyed");
        mainHandler.removeCallbacks(autoCapture);
        taskCaptureHeld = false;
        clearNodeBindings();
        BridgeConfig config = BridgeConfig.load(this);
        boolean captureWasEnabled = BridgeConfig.captureEnabled(this);
        config.save(this, false);
        if (captureWasEnabled) {
            enqueueUnavailable(config, "Accessibility service was destroyed");
        }
        // Let the explicit unavailable observation queued above finish. Any
        // earlier capture upload checks capture_enabled and is skipped after
        // the flag is cleared.
        networkExecutor.shutdown();
        if (instance == this) {
            instance = null;
        }
        super.onDestroy();
    }

    public static boolean isRunning() {
        return instance != null;
    }

    public static boolean isEnabled(android.content.Context context) {
        String enabledSetting = Settings.Secure.getString(
                context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        android.view.accessibility.AccessibilityManager manager =
                (android.view.accessibility.AccessibilityManager) context.getSystemService(ACCESSIBILITY_SERVICE);
        String expected = context.getPackageName() + "/" + ObservationAccessibilityService.class.getName();
        if (enabledSetting != null && enabledSetting.contains(expected)) {
            return true;
        }
        if (manager == null) {
            return false;
        }
        List<AccessibilityServiceInfo> services = manager.getEnabledAccessibilityServiceList(
                AccessibilityEvent.TYPES_ALL_MASK);
        for (AccessibilityServiceInfo service : services) {
            String id = service.getId();
            if (expected.equals(id) || (id != null && id.endsWith("/" + ObservationAccessibilityService.class.getName()))) {
                return true;
            }
        }
        return false;
    }

    public static void requestCapture(final BridgeConfig config, final CaptureCallback callback) {
        final ObservationAccessibilityService service = instance;
        Log.i(TAG, "requestCapture service=" + (service == null ? "null" : "ready"));
        if (service == null) {
            retryCapture(config, callback, 0);
            return;
        }
        service.captureNow(config, callback);
    }

    /**
     * Let the target app deliver its click/text accessibility event before
     * taking the observation that will be associated with the receipt. The
     * task capture hold remains active during this delay, so an automatic
     * frame cannot win the action's causal slot.
     */
    public static void requestCaptureAfterAction(final BridgeConfig config, final CaptureCallback callback) {
        final ObservationAccessibilityService service = instance;
        if (service == null) {
            if (callback != null) {
                callback.onError("permission_unavailable", "Accessibility service is not running");
            }
            return;
        }
        service.mainHandler.postDelayed(() -> service.captureNow(config, callback), POST_ACTION_CAPTURE_DELAY_MS);
    }

    /**
     * Reserve the capture stream for a task and publish one explicit fresh
     * before observation.  The callback is invoked only after the bridge has
     * accepted that observation, so the following submit is bound to this
     * exact version rather than to a delayed automatic frame.
     */
    public static void beginTaskCapture(final BridgeConfig config, final CaptureCallback callback) {
        final ObservationAccessibilityService service = instance;
        if (service == null) {
            if (callback != null) {
                callback.onError("permission_unavailable", "Accessibility service is not running");
            }
            return;
        }
        service.mainHandler.post(() -> {
            service.taskCaptureHeld = true;
            service.mainHandler.removeCallbacks(service.autoCapture);
            service.captureNow(config, callback);
        });
    }

    /** Release a task's capture reservation after its receipt/control outcome. */
    public static void endTaskCapture() {
        final ObservationAccessibilityService service = instance;
        if (service == null) {
            return;
        }
        service.mainHandler.post(() -> {
            service.taskCaptureHeld = false;
            service.mainHandler.removeCallbacks(service.autoCapture);
            if (BridgeConfig.captureEnabled(service)) {
                service.mainHandler.postDelayed(service.autoCapture, AUTO_CAPTURE_DELAY_MS);
            }
        });
    }

    /**
     * Execute a server supplied, observation-bound action through Accessibility.
     * This is deliberately separate from observation upload: the server never
     * receives an adb tap or a host-side substitute for the device action.
     */
    public static void executeAction(final BridgeConfig config, final JSONObject action, final ActionCallback callback) {
        final ObservationAccessibilityService service = instance;
        if (service == null) {
            if (callback != null) {
                callback.onError("permission_unavailable", "Accessibility service is not running");
            }
            return;
        }
        service.executeActionNow(config, action, callback);
    }

    private static void retryCapture(final BridgeConfig config, final CaptureCallback callback, final int attempt) {
        if (attempt >= 5) {
            if (callback != null) {
                callback.onError("permission_unavailable", "Accessibility service is not running");
            }
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                ObservationAccessibilityService service = instance;
                if (service == null) {
                    retryCapture(config, callback, attempt + 1);
                } else {
                    service.captureNow(config, callback);
                }
            }
        }, 400L);
    }

    private void captureNow(final BridgeConfig config, final CaptureCallback callback) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                final JSONObject observation;
                try {
                    observation = captureOnServiceThread(config);
                } catch (JSONException exception) {
                    reportError(callback, "capture_failed", exception.getMessage());
                    return;
                }
                try {
                    networkExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        if (!BridgeConfig.captureEnabled(ObservationAccessibilityService.this)) {
                            Log.i(TAG, "Skipping capture upload because capture is unavailable");
                            return;
                        }
                        try {
                            JSONObject acknowledgement = BridgeClient.postObservation(config, observation);
                            if (callback != null) {
                                callback.onSuccess(acknowledgement);
                            }
                        } catch (BridgeClient.BridgeException exception) {
                            reportError(callback, exception.code, exception.getMessage());
                        }
                    }
                    });
                } catch (RejectedExecutionException exception) {
                    reportError(callback, "capture_unavailable", "Accessibility service network worker is stopping");
                }
            }
        });
    }

    private void executeActionNow(final BridgeConfig config, final JSONObject action, final ActionCallback callback) {
        mainHandler.post(() -> {
            String kind = action.optString("kind", "");
            String targetId = action.optString("target_node_id", "");
            long expectedVersion = action.optLong("observation_version", -1L);
            if (targetId.isEmpty() || expectedVersion < 1L) {
                reportActionError(callback, "invalid_action", "action target and observation version are required");
                return;
            }
            if (expectedVersion != lastCapturedVersion) {
                reportActionError(callback, "stale_observation", "the action is bound to an obsolete Accessibility observation");
                return;
            }
            if (!(kind.equals("tap") || kind.equals("set_text"))) {
                reportActionError(callback, "unsupported_action", "the Accessibility service does not support this action kind");
                return;
            }
            try {
                if (performBoundAction(action)) {
                    if (callback != null) {
                        callback.onSuccess();
                    }
                } else {
                    reportActionError(callback, "target_node_not_found", "the observation-bound Accessibility node is no longer available");
                }
            } catch (ActionBindingException exception) {
                reportActionError(callback, "stale_observation", exception.getMessage());
            } catch (SecurityException exception) {
                reportActionError(callback, "permission_unavailable", "Accessibility permission is unavailable");
            } catch (RuntimeException exception) {
                reportActionError(callback, "action_failed", "Accessibility action failed: " + exception.getMessage());
            }
        });
    }

    private static final class ActionBindingException extends Exception {
        ActionBindingException(String message) {
            super(message);
        }
    }

    /**
     * Execute on the node retained from the accepted before frame.  A refresh
     * is required immediately before the action so a detached/replaced node
     * cannot be treated as the same target merely because its old path and
     * label are still present in the current window.
     */
    private boolean performBoundAction(JSONObject action) throws ActionBindingException {
        String targetId = action.optString("target_node_id", "");
        NodeBinding binding = lastNodeBindings.get(targetId);
        if (binding == null || binding.node == null) {
            throw new ActionBindingException("the observation-bound Accessibility node is no longer available");
        }
        AccessibilityNodeInfo node = binding.node;
        if (!node.refresh()) {
            throw new ActionBindingException("the observation-bound Accessibility node was replaced");
        }
        if (binding.windowId != node.getWindowId()
                || !binding.packageName.equals(text(node.getPackageName()))
                || !binding.className.equals(text(node.getClassName()))
                || !binding.viewIdResourceName.equals(viewId(node))) {
            throw new ActionBindingException("the observation-bound Accessibility node identity changed");
        }
        String currentUniqueId = uniqueId(node);
        if (!binding.uniqueId.isEmpty() && !binding.uniqueId.equals(currentUniqueId)) {
            throw new ActionBindingException("the observation-bound Accessibility node identity changed");
        }
        Rect currentBounds = new Rect();
        node.getBoundsInScreen(currentBounds);
        if (!binding.bounds.equals(currentBounds)) {
            throw new ActionBindingException("the observation-bound Accessibility node moved");
        }
        ensureBoundWindowVisible(binding);
        if (!node.isVisibleToUser() || !node.isEnabled()) {
            return false;
        }
        if (!matchesActionLabel(node, action.optString("target_node_label", ""))) {
            throw new ActionBindingException("the observation-bound Accessibility node label changed");
        }
        String kind = action.optString("kind", "");
        if ("tap".equals(kind)) {
            return node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        }
        if ("set_text".equals(kind)) {
            if (!node.isEditable()) {
                return false;
            }
            JSONObject parameters = action.optJSONObject("parameters");
            String value = parameters == null ? "" : parameters.optString("text", "");
            Bundle arguments = new Bundle();
            arguments.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    value);
            return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments);
        }
        return false;
    }

    /**
     * Check the current window stack immediately before dispatching the action.
     * A retained node can still refresh while a dialog/accessibility overlay
     * covers its old bounds, so node properties alone are not enough to prove
     * that the bound target is currently actionable.
     */
    private void ensureBoundWindowVisible(NodeBinding binding) throws ActionBindingException {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (SecurityException exception) {
            throw new ActionBindingException("current Accessibility windows are unavailable");
        }
        if (windows == null || windows.isEmpty()) {
            throw new ActionBindingException("the observation-bound Accessibility window is no longer available");
        }
        AccessibilityWindowInfo boundWindow = null;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window != null && window.getId() == binding.windowId) {
                    boundWindow = window;
                    break;
                }
            }
            if (boundWindow == null) {
                throw new ActionBindingException("the observation-bound Accessibility window changed");
            }
            if (!boundWindow.isActive() || !boundWindow.isFocused()) {
                throw new ActionBindingException("the observation-bound Accessibility window is no longer active");
            }
            for (AccessibilityWindowInfo window : windows) {
                if (window == null || window == boundWindow
                        || window.getLayer() <= boundWindow.getLayer()) {
                    continue;
                }
                Rect overlayBounds = new Rect();
                window.getBoundsInScreen(overlayBounds);
                if (Rect.intersects(binding.bounds, overlayBounds)) {
                    throw new ActionBindingException("the observation-bound Accessibility node is covered by a higher window");
                }
            }
        } finally {
            for (AccessibilityWindowInfo window : windows) {
                if (window != null) {
                    window.recycle();
                }
            }
        }
    }

    private static boolean matchesActionLabel(AccessibilityNodeInfo node, String expectedLabel) {
        if (expectedLabel.isEmpty()) {
            return true;
        }
        String contentDescription = text(node.getContentDescription());
        String nodeText = text(node.getText());
        return expectedLabel.equals(contentDescription)
                || expectedLabel.equals(nodeText)
                || expectedLabel.equalsIgnoreCase(nodeText)
                || (expectedLabel + " button").equalsIgnoreCase(contentDescription);
    }

    private static void reportActionError(ActionCallback callback, String code, String message) {
        if (callback != null) {
            callback.onError(code, message);
        }
    }

    private void enqueueUnavailable(final BridgeConfig config, final String reason) {
        final JSONObject observation;
        try {
            observation = ObservationPayload.unavailable(
                    this, config, "PERMISSION_UNAVAILABLE", reason, false, false);
        } catch (JSONException exception) {
            Log.e(TAG, "Could not build unavailable observation", exception);
            return;
        }
        try {
            networkExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        BridgeClient.postObservation(config, observation);
                    } catch (BridgeClient.BridgeException exception) {
                        Log.e(TAG, "Could not report unavailable observation code=" + exception.code, exception);
                    }
                }
            });
        } catch (RejectedExecutionException exception) {
            Log.e(TAG, "Could not queue unavailable observation", exception);
        }
    }

    private static void reportError(CaptureCallback callback, String code, String message) {
        if (callback != null) {
            callback.onError(code, message);
        } else {
            Log.e(TAG, "Accessibility capture failed code=" + code + " message=" + message);
        }
    }

    private JSONObject captureOnServiceThread(BridgeConfig config) throws JSONException {
        clearNodeBindings();
        long version = BridgeConfig.nextObservationVersion(this);
        lastCapturedVersion = version;
        JSONObject observation = ObservationPayload.base(this, config, version);
        JSONArray windowsJson = new JSONArray();
        JSONArray nodesJson = new JSONArray();
        JSONArray rootIds = new JSONArray();

        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
        } catch (SecurityException ignored) {
            // The service can remain enabled while the system denies window access.
        }

        if (windows != null) {
            int windowIndex = 0;
            for (AccessibilityWindowInfo window : windows) {
                if (window == null) {
                    continue;
                }
                AccessibilityNodeInfo root = null;
                try {
                    root = window.getRoot();
                    int windowId = Math.max(0, window.getId());
                    String nodePrefix = "window-" + windowId + "-" + windowIndex;
                    String rootId = null;
                    if (root != null && nodesJson.length() < MAX_NODES) {
                        rootId = appendNode(root, nodePrefix + "-node-0", null, nodesJson, windowId);
                        if (rootId != null) {
                            rootIds.put(rootId);
                        }
                    }
                    windowsJson.put(windowJson(window, root, rootId));
                    windowIndex++;
                } catch (SecurityException ignored) {
                    // Keep metadata for a window even when its root is protected.
                } finally {
                    if (root != null) {
                        root.recycle();
                    }
                }
            }
        }

        // Some emulator/system states expose an active root without a window list.
        if (windowsJson.length() == 0) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            try {
                if (root != null && nodesJson.length() < MAX_NODES) {
                    String rootId = appendNode(root, "window-0-node-0", null, nodesJson, 0);
                    if (rootId != null) {
                        rootIds.put(rootId);
                    }
                    JSONObject window = new JSONObject()
                            .put("window_id", 0)
                            .put("window_type", AccessibilityWindowInfo.TYPE_APPLICATION)
                            .put("title", "")
                            .put("package_name", text(root.getPackageName()))
                            .put("active", true)
                            .put("focused", true)
                            .put("layer", 0)
                            .put("bounds", ObservationPayload.bounds(screenBounds()))
                            .put("root_node_id", rootId == null ? JSONObject.NULL : rootId);
                    windowsJson.put(window);
                }
            } finally {
                if (root != null) {
                    root.recycle();
                }
            }
        }

        boolean hasTree = nodesJson.length() > 0;
        observation.put("page_state", hasTree ? "observed" : "empty");
        observation.put("availability", hasTree ? "AVAILABLE" : "EMPTY_TREE");
        observation.put("unavailable_reason", hasTree ? JSONObject.NULL : "no_active_accessibility_root");
        observation.put("permission", new JSONObject()
                .put("service_enabled", true)
                .put("can_observe", true)
                .put("reason", JSONObject.NULL));
        observation.put("windows", windowsJson);
        observation.put("nodes", nodesJson);
        observation.put("root_node_ids", rootIds);
        return observation;
    }

    private JSONObject windowJson(AccessibilityWindowInfo window, AccessibilityNodeInfo root, String rootId)
            throws JSONException {
        Rect bounds = new Rect();
        window.getBoundsInScreen(bounds);
        return new JSONObject()
                .put("window_id", Math.max(0, window.getId()))
                .put("window_type", window.getType())
                .put("title", text(window.getTitle()))
                .put("package_name", root == null ? "" : text(root.getPackageName()))
                .put("active", window.isActive())
                .put("focused", window.isFocused())
                .put("layer", Math.max(0, window.getLayer()))
                .put("bounds", ObservationPayload.bounds(bounds))
                .put("root_node_id", rootId == null ? JSONObject.NULL : rootId);
    }

    private String appendNode(
            AccessibilityNodeInfo node,
            String nodeId,
            String parentId,
            JSONArray nodes,
            int windowId) throws JSONException {
        if (node == null || nodes.length() >= MAX_NODES) {
            return null;
        }
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        JSONObject json = new JSONObject()
                .put("node_id", nodeId)
                .put("parent_node_id", parentId == null ? JSONObject.NULL : parentId)
                .put("class_name", text(node.getClassName()))
                .put("package_name", text(node.getPackageName()))
                .put("text", text(node.getText()))
                .put("content_description", text(node.getContentDescription()))
                .put("state_description", stateDescription(node))
                .put("view_id_resource_name", text(viewId(node)))
                .put("enabled", node.isEnabled())
                .put("visible_to_user", node.isVisibleToUser())
                .put("clickable", node.isClickable())
                .put("focusable", node.isFocusable())
                .put("focused", node.isFocused())
                .put("selected", node.isSelected())
                .put("scrollable", node.isScrollable())
                .put("editable", node.isEditable())
                .put("bounds", ObservationPayload.bounds(bounds));
        JSONArray childIds = new JSONArray();
        // Add the parent before children so child references are stable even when a
        // page contains a large or cyclic-looking provider tree.
        nodes.put(json);
        NodeBinding previous = lastNodeBindings.put(nodeId, new NodeBinding(node));
        if (previous != null && previous.node != null) {
            previous.node.recycle();
        }
        for (int index = 0; index < node.getChildCount() && nodes.length() < MAX_NODES; index++) {
            AccessibilityNodeInfo child = node.getChild(index);
            if (child == null) {
                continue;
            }
            try {
                String childId = appendNode(child, nodeId + "-" + index, nodeId, nodes, windowId);
                if (childId != null) {
                    childIds.put(childId);
                }
            } finally {
                child.recycle();
            }
        }
        json.put("child_node_ids", childIds);
        return nodeId;
    }

    private void clearNodeBindings() {
        for (NodeBinding binding : lastNodeBindings.values()) {
            if (binding != null && binding.node != null) {
                binding.node.recycle();
            }
        }
        lastNodeBindings.clear();
    }

    private Rect screenBounds() {
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        android.view.WindowManager windowManager =
                (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        return new Rect(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private static String stateDescription(AccessibilityNodeInfo node) {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            return text(node.getStateDescription());
        }
        return "";
    }

    private static String viewId(AccessibilityNodeInfo node) {
        try {
            return text(node.getViewIdResourceName());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String uniqueId(AccessibilityNodeInfo node) {
        if (android.os.Build.VERSION.SDK_INT < 33) {
            return "";
        }
        try {
            return text(node.getUniqueId());
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static String text(CharSequence value) {
        return value == null ? "" : value.toString();
    }
}
