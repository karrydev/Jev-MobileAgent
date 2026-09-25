package com.jev.mobileagent;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.KeyguardManager;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Insets;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.Log;
import android.view.Display;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The device-side observation bridge. All node access happens on the service
 * thread; only the JSON document crosses to the network worker.
 */
public class ObservationAccessibilityService extends AccessibilityService {
    private static final String TAG = "JevObservation";
    private static final int MAX_NODES = 512;
    private static final long AUTO_CAPTURE_DELAY_MS = 250L;
    private static final long POST_ACTION_CAPTURE_DELAY_MS = 350L;
    private static final long RECOVERY_WINDOW_TIMEOUT_MS = 2500L;
    private static final long RECOVERY_WINDOW_POLL_MS = 120L;
    private static final long RECOVERY_STABILITY_SAMPLE_MS = 150L;
    private static volatile ObservationAccessibilityService instance;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService networkExecutor = Executors.newSingleThreadExecutor();
    private volatile long lastCapturedVersion;
    private volatile long coordinateFingerprintVersion;
    private volatile String coordinateFingerprint = "";
    private volatile long screenshotCaptureCount;
    private volatile long screenshotUploadCount;
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

    private static final class RecoveryObservationAttempt {
        final String taskId;
        final String targetPackage;
        final AtomicBoolean cancelled;
        final LocalObservationCallback callback;
        final long startedAtMs;
        String phase = "starting";
        String readinessError = "";
        JSONObject stableCandidate;
        JSONObject lastObservation;
        boolean shadeDismissed;

        RecoveryObservationAttempt(String taskId, String targetPackage, AtomicBoolean cancelled,
                LocalObservationCallback callback) {
            this.taskId = taskId;
            this.targetPackage = targetPackage;
            this.cancelled = cancelled;
            this.callback = callback;
            this.startedAtMs = SystemClock.elapsedRealtime();
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

    /** Callback for a screenshot tied to one accepted observation. */
    public interface ScreenshotCallback {
        void onSuccess(JSONObject acknowledgement);

        void onError(String code, String message);
    }

    /** Callback for an action executed through the live Accessibility service. */
    public interface ActionCallback {
        void onSuccess();

        void onError(String code, String message);
    }

    /** Local observation callback used by the standalone task loop; payloads never cross a bridge. */
    public interface LocalObservationCallback {
        void onSuccess(JSONObject observation);

        void onError(String code, String message);
    }

    /** Local screenshot callback used by the VLM loop; PNG data remains app-private. */
    public interface LocalScreenshotCallback {
        void onSuccess(JSONObject screenshot);

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
        // The product runtime captures locally on demand. Older bridge capture
        // preferences are disabled during upgrade so an old setting cannot
        // silently resume network uploads.
        getSharedPreferences(BridgeConfig.PREFS, MODE_PRIVATE).edit()
                .putBoolean("capture_enabled", false).commit();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // A physical touch while a model decision is in flight is a human
        // intervention. Pause the local task and require a fresh observation;
        // Accessibility-dispatched actions are bracketed by the task service.
        if (event != null && event.getEventType() == AccessibilityEvent.TYPE_TOUCH_INTERACTION_START
                && LocalVlmTaskService.isTaskLoopActive()
                && !LocalVlmTaskService.isDeviceActionDispatchActive()) {
            JSONObject active = LocalTaskStore.activeTask(this);
            if (active != null) {
                LocalVlmTaskService.pauseForExternalCondition(this,
                        active.optString("task_id", ""), "manual_intervention");
            }
        }
        // Observation remains requested by the app-local task service. Events
        // never trigger bridge traffic or unsolicited screenshot capture.
    }

    @Override
    public void onInterrupt() {
        JSONObject active = LocalTaskStore.activeTask(this);
        if (active != null && LocalVlmTaskService.isTaskLoopActive()) {
            LocalVlmTaskService.pauseForExternalCondition(this,
                    active.optString("task_id", ""), "accessibility_interrupted");
        }
    }

    @Override
    public void onDestroy() {
        Log.i(TAG, "Accessibility service destroyed");
        mainHandler.removeCallbacks(autoCapture);
        taskCaptureHeld = false;
        clearNodeBindings();
        getSharedPreferences(BridgeConfig.PREFS, MODE_PRIVATE).edit()
                .putBoolean("capture_enabled", false).commit();
        networkExecutor.shutdownNow();
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

    /** Reserve local Accessibility observations for one app-local task and return a fresh tree. */
    public static void beginLocalTaskCapture(final String taskId, final LocalObservationCallback callback) {
        final ObservationAccessibilityService service = instance;
        if (service == null) {
            reportLocalObservationError(callback, "permission_unavailable", "Accessibility service is not running");
            return;
        }
        service.mainHandler.post(() -> {
            service.taskCaptureHeld = true;
            service.mainHandler.removeCallbacks(service.autoCapture);
            service.captureLocalObservation(taskId, callback);
        });
    }

    /** Capture another frame inside the same local task reservation. */
    public static void requestLocalObservation(final String taskId, final LocalObservationCallback callback) {
        final ObservationAccessibilityService service = instance;
        if (service == null) {
            reportLocalObservationError(callback, "permission_unavailable", "Accessibility service is not running");
            return;
        }
        service.mainHandler.post(() -> service.captureLocalObservation(taskId, callback));
    }

    /** Return to the task's original app without launching or recreating an Activity, then wait for a stable tree. */
    public static void requestRecoveryObservation(final Context diagnosticContext, final String taskId,
            final String targetPackage, final AtomicBoolean cancelled,
            final LocalObservationCallback callback) {
        final ObservationAccessibilityService service = instance;
        if (service == null) {
            recordUnavailableRecoveryDiagnostic(diagnosticContext, taskId, targetPackage,
                    "accessibility_service_unavailable", "permission_unavailable");
            reportLocalObservationError(callback, "permission_unavailable", "Accessibility service is not running");
            return;
        }
        service.mainHandler.post(() -> {
            service.taskCaptureHeld = true;
            service.mainHandler.removeCallbacks(service.autoCapture);
            service.captureRecoveryObservation(new RecoveryObservationAttempt(
                    taskId, targetPackage, cancelled, callback));
        });
    }

    /** Capture a screenshot associated with the exact local observation, without bridge upload. */
    public static void requestLocalScreenshot(
            final JSONObject observation,
            final String captureType,
            final LocalScreenshotCallback callback) {
        final ObservationAccessibilityService service = instance;
        if (service == null) {
            reportLocalScreenshotError(callback, "permission_unavailable", "Accessibility service is not running");
            return;
        }
        service.mainHandler.post(() -> service.captureLocalScreenshot(observation, captureType, callback));
    }

    /** Execute the existing observation-bound Accessibility action path for a local task. */
    public static void executeLocalAction(
            final JSONObject action,
            final ActionExecutionGate.Token actionToken,
            final ActionCallback callback) {
        final ObservationAccessibilityService service = instance;
        if (service == null) {
            if (callback != null) {
                callback.onError("permission_unavailable", "Accessibility service is not running");
            }
            return;
        }
        service.executeActionNow(null, action, actionToken, callback);
    }

    /** Release a local task's observation reservation after it stops. */
    public static void endLocalTaskCapture() {
        final ObservationAccessibilityService service = instance;
        if (service != null) {
            service.mainHandler.post(() -> {
                service.taskCaptureHeld = false;
                service.mainHandler.removeCallbacks(service.autoCapture);
            });
        }
    }

    private void captureLocalObservation(String taskId, LocalObservationCallback callback) {
        if (!isEnabled(this)) {
            reportLocalObservationError(callback, "permission_unavailable", "Accessibility permission is unavailable");
            return;
        }
        try {
            BridgeConfig localIdentity = new BridgeConfig("", "", "standalone-device", taskId,
                    "", "", "", "", "");
            JSONObject observation = captureOnServiceThread(localIdentity);
            if (callback != null) {
                callback.onSuccess(observation);
            }
        } catch (SecurityException exception) {
            reportLocalObservationError(callback, "permission_unavailable", "Accessibility permission is unavailable");
        } catch (JSONException | RuntimeException exception) {
            reportLocalObservationError(callback, "capture_failed", "Local Accessibility observation failed");
        }
    }

    private void captureRecoveryObservation(RecoveryObservationAttempt attempt) {
        if (attempt.cancelled != null && attempt.cancelled.get()) {
            recordRecoveryDiagnostic(attempt, "cancelled", "cancelled", null, "");
            return;
        }
        if (!isDeviceUnlocked()) {
            finishRecoveryObservation(attempt, "screen_locked", "screen_locked", null, "");
            return;
        }
        if (!isEnabled(this)) {
            finishRecoveryObservation(attempt, "permission_unavailable", "permission_check", null, "");
            return;
        }
        JSONObject observation;
        try {
            BridgeConfig identity = new BridgeConfig("", "", "standalone-device", attempt.taskId,
                    "", "", "", "", "");
            observation = captureRecoveryFrame(identity);
        } catch (SecurityException exception) {
            finishRecoveryObservation(attempt, "permission_unavailable", "window_capture", null, "");
            return;
        } catch (JSONException | RuntimeException exception) {
            finishRecoveryObservation(attempt, "capture_failed", "window_capture", null, "");
            return;
        }
        attempt.lastObservation = observation;
        if (attempt.cancelled != null && attempt.cancelled.get()) {
            recordRecoveryDiagnostic(attempt, "cancelled", "cancelled", observation, "");
            return;
        }
        if (!isDeviceUnlocked()) {
            finishRecoveryObservation(attempt, "screen_locked", "screen_check_after_capture",
                    observation, "");
            return;
        }
        if (!isEnabled(this)) {
            finishRecoveryObservation(attempt, "permission_unavailable", "permission_check_after_capture",
                    observation, "");
            return;
        }
        boolean fullScreenSystemWindow = LocalTaskControlPolicy
                .shouldAttemptNotificationShadeDismiss(observation, true, true);
        if (fullScreenSystemWindow && !attempt.shadeDismissed) {
            if (attempt.cancelled != null && attempt.cancelled.get()) {
                recordRecoveryDiagnostic(attempt, "cancelled", "cancelled", observation, "");
                return;
            }
            if (!isDeviceUnlocked()) {
                finishRecoveryObservation(attempt, "screen_locked", "screen_check_before_dismiss",
                        observation, "");
                return;
            }
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                    || !performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)) {
                finishRecoveryObservation(attempt, "notification_shade_unavailable",
                        "notification_shade_dismiss", observation, "");
                return;
            }
            attempt.shadeDismissed = true;
            attempt.stableCandidate = null;
            attempt.phase = "waiting_after_notification_dismiss";
            scheduleRecoveryObservation(attempt, RECOVERY_WINDOW_POLL_MS);
            return;
        }

        String readinessError = LocalTaskControlPolicy.recoveryTargetReadinessError(
                observation, attempt.targetPackage);
        if (readinessError.isEmpty()) {
            if (attempt.stableCandidate != null
                    && LocalTaskControlPolicy.isStableRecoveryObservationPair(
                            attempt.stableCandidate, observation, attempt.targetPackage)) {
                finishRecoveryObservation(attempt, "", "stable_target", observation, "");
                return;
            }
            attempt.stableCandidate = observation;
            attempt.readinessError = "";
            attempt.phase = "checking_target_stability";
        } else {
            attempt.stableCandidate = null;
            attempt.readinessError = readinessError;
            attempt.phase = "waiting_for_target_window";
        }

        long elapsedMs = SystemClock.elapsedRealtime() - attempt.startedAtMs;
        if (!LocalTaskControlPolicy.shouldContinueRecoveryWindowWait(
                attempt.cancelled != null && attempt.cancelled.get(),
                elapsedMs, RECOVERY_WINDOW_TIMEOUT_MS)) {
            if (attempt.cancelled != null && attempt.cancelled.get()) {
                recordRecoveryDiagnostic(attempt, "cancelled", "cancelled", observation, readinessError);
                return;
            }
            finishRecoveryObservation(attempt, "recovery_target_wait_timeout",
                    "target_transition_timeout", observation, readinessError);
            return;
        }
        long interval = attempt.stableCandidate == null
                ? RECOVERY_WINDOW_POLL_MS : RECOVERY_STABILITY_SAMPLE_MS;
        long remainingMs = RECOVERY_WINDOW_TIMEOUT_MS - elapsedMs;
        scheduleRecoveryObservation(attempt, Math.max(1L, Math.min(interval, remainingMs)));
    }

    private void scheduleRecoveryObservation(RecoveryObservationAttempt attempt, long delayMs) {
        mainHandler.postDelayed(() -> captureRecoveryObservation(attempt), Math.max(1L, delayMs));
    }

    private boolean isDeviceUnlocked() {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
        return power != null && keyguard != null && power.isInteractive() && !keyguard.isKeyguardLocked();
    }

    private void finishRecoveryObservation(RecoveryObservationAttempt attempt, String errorCode,
            String phase, JSONObject observation, String readinessError) {
        recordRecoveryDiagnostic(attempt, phase, errorCode, observation, readinessError);
        if (errorCode == null || errorCode.isEmpty()) {
            if (attempt.callback != null) attempt.callback.onSuccess(observation);
        } else if (attempt.callback != null) {
            String message = "recovery_target_wait_timeout".equals(errorCode)
                    ? "等待原目标应用稳定超时；任务继续暂停"
                    : "无法安全取得原目标应用的本地观察；任务继续暂停";
            attempt.callback.onError(errorCode, message);
        }
    }

    private void recordRecoveryDiagnostic(RecoveryObservationAttempt attempt, String phase,
            String errorCode, JSONObject observation, String readinessError) {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            KeyguardManager keyguard = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            JSONObject diagnostic = new JSONObject()
                    .put("expectedTargetPackage", attempt.targetPackage == null ? "" : attempt.targetPackage)
                    .put("phase", phase == null ? attempt.phase : phase)
                    .put("error", errorCode == null ? "" : errorCode)
                    .put("readinessError", readinessError == null ? "" : readinessError)
                    .put("elapsedMs", Math.max(0L, SystemClock.elapsedRealtime() - attempt.startedAtMs))
                    .put("screenInteractive", power != null && power.isInteractive())
                    .put("keyguardLocked", keyguard == null || keyguard.isKeyguardLocked())
                    .put("observation", observation == null ? attempt.lastObservation : observation);
            LocalTaskStore.recordDebugRecoveryWindowDiagnostic(this, attempt.taskId, diagnostic);
        } catch (JSONException | RuntimeException ignored) {
            // This is Debug-only, best-effort local diagnostic data.
        }
    }

    private static void recordUnavailableRecoveryDiagnostic(Context context, String taskId,
            String targetPackage, String phase, String errorCode) {
        if (!BuildConfig.DEBUG || context == null) return;
        try {
            LocalTaskStore.recordDebugRecoveryWindowDiagnostic(context, taskId,
                    new JSONObject().put("expectedTargetPackage", targetPackage == null ? "" : targetPackage)
                            .put("phase", phase).put("error", errorCode));
        } catch (JSONException ignored) {
            // Keep service-unavailable handling independent from diagnostics.
        }
    }

    private void recordRecoveryScreenshotDiagnostic(JSONObject reviewedObservation, JSONObject currentObservation,
            String errorCode, String readinessError) {
        if (!BuildConfig.DEBUG) return;
        String taskId = reviewedObservation == null ? "" : reviewedObservation.optString("task_id", "");
        String targetPackage = reviewedObservation == null ? ""
                : LocalTaskControlPolicy.activeApplicationPackage(reviewedObservation);
        RecoveryObservationAttempt attempt = new RecoveryObservationAttempt(taskId, targetPackage, null, null);
        attempt.lastObservation = currentObservation;
        recordRecoveryDiagnostic(attempt, "recovery_screenshot_guard", errorCode,
                currentObservation, readinessError);
    }

    private void captureLocalScreenshot(
            JSONObject observation,
            String captureType,
            LocalScreenshotCallback callback) {
        String observationId = observation == null ? "" : observation.optString("observation_id", "");
        long observationVersion = observation == null ? -1L : observation.optLong("observation_version", -1L);
        if (observationId.isEmpty() || observationVersion < 1L
                || (!"BEFORE".equals(captureType) && !"AFTER".equals(captureType)
                        && !"RECOVERY".equals(captureType))) {
            reportLocalScreenshotError(callback, "observation_mismatch", "Screenshot must match a valid local observation");
            return;
        }
        if ("RECOVERY".equals(captureType)) {
            String targetPackage = LocalTaskControlPolicy.activeApplicationPackage(observation);
            JSONObject current = null;
            try {
                current = captureRecoveryFrame(new BridgeConfig("", "", "standalone-device",
                        observation.optString("task_id", ""), "", "", "", "", ""));
                String readinessError = LocalTaskControlPolicy.recoveryTargetReadinessError(
                        current, targetPackage);
                boolean sameScene = readinessError.isEmpty()
                        && LocalTaskControlPolicy.sceneFingerprint(current, "")
                                .equals(LocalTaskControlPolicy.sceneFingerprint(observation, ""));
                if (!readinessError.isEmpty() || !sameScene) {
                    recordRecoveryScreenshotDiagnostic(observation, current,
                            "target_window_changed", readinessError.isEmpty() ? "scene_changed" : readinessError);
                    reportLocalScreenshotError(callback, "target_window_changed",
                            "截图前原目标页面已变化；请重新观察核对");
                    return;
                }
            } catch (RuntimeException | JSONException exception) {
                recordRecoveryScreenshotDiagnostic(observation, current,
                        "target_window_unavailable", "screenshot_window_check_failed");
                reportLocalScreenshotError(callback, "target_window_unavailable",
                        "截图前无法确认原目标页面；请重新观察核对");
                return;
            }
        }
        long captureCount = ++screenshotCaptureCount;
        if (DebugTreeVerificationFixtures.consumeAfterScreenshotFailure(
                this, observation.optString("task_id", ""), captureType)) {
            reportLocalScreenshotError(callback, "screenshot_unavailable",
                    "Debug-only one-shot AFTER screenshot failure was injected");
            return;
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            reportLocalScreenshotError(callback, "screenshot_api_unavailable", "Accessibility screenshots require Android 11 or newer");
            return;
        }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, command -> mainHandler.post(command), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = null;
                    Bitmap hardwareBitmap = null;
                    Bitmap bitmap = null;
                    try {
                        if (result == null || result.getHardwareBuffer() == null) {
                            throw new IllegalStateException("screenshot buffer missing");
                        }
                        buffer = result.getHardwareBuffer();
                        hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hardwareBitmap == null) {
                            throw new IllegalStateException("screenshot buffer unreadable");
                        }
                        bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
                        if (bitmap == null) {
                            throw new IllegalStateException("screenshot copy failed");
                        }
                        ByteArrayOutputStream output = new ByteArrayOutputStream();
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                            throw new IllegalStateException("screenshot encoding failed");
                        }
                        String base64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
                        JSONObject localScreenshot = new JSONObject()
                                .put("screenshot_id", "local-shot-" + UUID.randomUUID().toString().replace("-", ""))
                                .put("observation_id", observationId)
                                .put("observation_version", observationVersion)
                                .put("capture_type", captureType)
                                .put("captured_at", java.time.Instant.now().toString())
                                .put("width_px", bitmap.getWidth())
                                .put("height_px", bitmap.getHeight())
                                .put("capture_count", captureCount)
                                .put("png_base64", base64);
                        if (callback != null) {
                            callback.onSuccess(localScreenshot);
                        }
                    } catch (RuntimeException | JSONException exception) {
                        reportLocalScreenshotError(callback, "screenshot_unavailable", "Local screenshot capture failed");
                    } finally {
                        if (bitmap != null) {
                            bitmap.recycle();
                        }
                        if (hardwareBitmap != null && hardwareBitmap != bitmap) {
                            hardwareBitmap.recycle();
                        }
                        if (buffer != null) {
                            buffer.close();
                        }
                    }
                }

                @Override
                public void onFailure(int errorCode) {
                    reportLocalScreenshotError(callback, "screenshot_unavailable",
                            "Accessibility screenshot failed with code " + errorCode);
                }
            });
        } catch (SecurityException exception) {
            reportLocalScreenshotError(callback, "permission_unavailable", "Accessibility screenshot permission is unavailable");
        } catch (RuntimeException exception) {
            reportLocalScreenshotError(callback, "screenshot_unavailable", "Local screenshot capture failed");
        }
    }

    private static void reportLocalObservationError(LocalObservationCallback callback, String code, String message) {
        if (callback != null) {
            callback.onError(code, message);
        }
    }

    private static void reportLocalScreenshotError(LocalScreenshotCallback callback, String code, String message) {
        if (callback != null) {
            callback.onError(code, message);
        }
    }

    /** Capture and upload a real frame for an observation already accepted by the bridge. */
    public static void requestScreenshot(
            final BridgeConfig config,
            final JSONObject observationAcknowledgement,
            final String captureType,
            final ScreenshotCallback callback) {
        final ObservationAccessibilityService service = instance;
        if (service == null) {
            if (callback != null) {
                callback.onError("permission_unavailable", "Accessibility service is not running");
            }
            return;
        }
        service.mainHandler.post(() -> service.captureScreenshot(
                config, observationAcknowledgement, captureType, callback));
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
    public static void executeAction(
            final BridgeConfig config,
            final JSONObject action,
            final ActionExecutionGate.Token actionToken,
            final ActionCallback callback) {
        final ObservationAccessibilityService service = instance;
        if (service == null) {
            if (callback != null) {
                callback.onError("permission_unavailable", "Accessibility service is not running");
            }
            return;
        }
        service.executeActionNow(config, action, actionToken, callback);
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

    private void captureScreenshot(
            final BridgeConfig config,
            final JSONObject observationAcknowledgement,
            final String captureType,
            final ScreenshotCallback callback) {
        if (!"BEFORE".equals(captureType) && !"AFTER".equals(captureType)) {
            reportScreenshotError(callback, "invalid_capture_type", "capture type must be BEFORE or AFTER");
            return;
        }
        final String observationId = observationAcknowledgement == null
                ? "" : observationAcknowledgement.optString("observation_id", "");
        final long observationVersion = observationAcknowledgement == null
                ? -1L : observationAcknowledgement.optLong("observation_version", -1L);
        if (observationId.isEmpty() || observationVersion < 1L) {
            reportScreenshotError(callback, "observation_mismatch", "screenshot requires an accepted observation acknowledgement");
            return;
        }
        final long captureCount = ++screenshotCaptureCount;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            enqueueScreenshotUnavailable(
                    config, observationId, observationVersion, captureType, captureCount,
                    "screenshot_api_unavailable", "Accessibility screenshots require Android 11 or newer", callback);
            return;
        }
        try {
            takeScreenshot(
                    Display.DEFAULT_DISPLAY,
                    command -> mainHandler.post(command),
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult result) {
                            uploadScreenshot(
                                    config,
                                    observationId,
                                    observationVersion,
                                    captureType,
                                    captureCount,
                                    result,
                                    callback);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            enqueueScreenshotUnavailable(
                                    config, observationId, observationVersion, captureType, captureCount,
                                    "screenshot_unavailable",
                                    "Accessibility screenshot failed with code " + errorCode,
                                    callback);
                        }
                    });
        } catch (SecurityException exception) {
            enqueueScreenshotUnavailable(
                    config, observationId, observationVersion, captureType, captureCount,
                    "screenshot_unavailable", "Accessibility screenshot permission is unavailable", callback);
        } catch (RuntimeException exception) {
            enqueueScreenshotUnavailable(
                    config, observationId, observationVersion, captureType, captureCount,
                    "screenshot_unavailable", "Accessibility screenshot failed: " + exception.getMessage(), callback);
        }
    }

    private void uploadScreenshot(
            final BridgeConfig config,
            final String observationId,
            final long observationVersion,
            final String captureType,
            final long captureCount,
            final ScreenshotResult result,
            final ScreenshotCallback callback) {
        HardwareBuffer buffer = null;
        Bitmap hardwareBitmap = null;
        Bitmap bitmap = null;
        try {
            if (result == null || result.getHardwareBuffer() == null) {
                enqueueScreenshotUnavailable(
                        config, observationId, observationVersion, captureType, captureCount,
                        "screenshot_unavailable", "Accessibility returned no screenshot buffer", callback);
                return;
            }
            buffer = result.getHardwareBuffer();
            hardwareBitmap = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (hardwareBitmap == null) {
                enqueueScreenshotUnavailable(
                        config, observationId, observationVersion, captureType, captureCount,
                        "screenshot_unavailable", "Accessibility returned an unreadable screenshot buffer", callback);
                return;
            }
            bitmap = hardwareBitmap.copy(Bitmap.Config.ARGB_8888, false);
            if (bitmap == null) {
                enqueueScreenshotUnavailable(
                        config, observationId, observationVersion, captureType, captureCount,
                        "screenshot_unavailable", "screenshot could not be copied to a CPU bitmap", callback);
                return;
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                enqueueScreenshotUnavailable(
                        config, observationId, observationVersion, captureType, captureCount,
                        "screenshot_unavailable", "screenshot PNG encoding failed", callback);
                return;
            }
            final int width = bitmap.getWidth();
            final int height = bitmap.getHeight();
            final String pngBase64 = Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP);
            try {
                networkExecutor.execute(() -> {
                    final long uploadCount = screenshotUploadCount + 1L;
                    try {
                        JSONObject payload = new JSONObject()
                                .put("schema_version", "1.0")
                                .put("android_schema_version", "1.0")
                                .put("task_id", config.taskId)
                                .put("device_id", config.deviceId)
                                .put("screenshot_id", config.deviceId + "-shot-" + UUID.randomUUID().toString().replace("-", ""))
                                .put("observation_id", observationId)
                                .put("observation_version", observationVersion)
                                .put("capture_type", captureType)
                                .put("captured_at", java.time.Instant.now().toString())
                                .put("width_px", width)
                                .put("height_px", height)
                                .put("png_base64", pngBase64)
                                .put("capture_count", captureCount)
                                .put("upload_count", uploadCount)
                                .put("missing_reason", JSONObject.NULL);
                        JSONObject acknowledgement = BridgeClient.postScreenshot(config, payload);
                        screenshotUploadCount = uploadCount;
                        if (callback != null) {
                            callback.onSuccess(acknowledgement);
                        }
                    } catch (BridgeClient.BridgeException exception) {
                        postScreenshotUnavailable(
                                config, observationId, observationVersion, captureType, captureCount,
                                "screenshot_upload_unavailable", exception.getMessage());
                        reportScreenshotError(callback, exception.code, exception.getMessage());
                    } catch (JSONException exception) {
                        postScreenshotUnavailable(
                                config, observationId, observationVersion, captureType, captureCount,
                                "screenshot_upload_unavailable", exception.getMessage());
                        reportScreenshotError(callback, "screenshot_failed", exception.getMessage());
                    }
                });
            } catch (RejectedExecutionException exception) {
                enqueueScreenshotUnavailable(
                        config, observationId, observationVersion, captureType, captureCount,
                        "screenshot_upload_unavailable", "Accessibility network worker is stopping", callback);
            }
        } catch (RuntimeException exception) {
            enqueueScreenshotUnavailable(
                    config, observationId, observationVersion, captureType, captureCount,
                    "screenshot_failed", "screenshot encoding failed: " + exception.getMessage(), callback);
        } finally {
            if (bitmap != null) {
                bitmap.recycle();
            }
            if (hardwareBitmap != null && hardwareBitmap != bitmap) {
                hardwareBitmap.recycle();
            }
            if (buffer != null) {
                buffer.close();
            }
        }
    }

    private static void reportScreenshotError(ScreenshotCallback callback, String code, String message) {
        if (callback != null) {
            callback.onError(code, message == null ? code : message);
        } else {
            Log.e(TAG, "Accessibility screenshot failed code=" + code + " message=" + message);
        }
    }

    private void enqueueScreenshotUnavailable(
            final BridgeConfig config,
            final String observationId,
            final long observationVersion,
            final String captureType,
            final long captureCount,
            final String code,
            final String message,
            final ScreenshotCallback callback) {
        try {
            networkExecutor.execute(() -> {
                postScreenshotUnavailable(
                        config, observationId, observationVersion, captureType, captureCount, code, message);
                reportScreenshotError(callback, code, message);
            });
        } catch (RejectedExecutionException exception) {
            reportScreenshotError(callback, code, message);
        }
    }

    /** Send a failed capture as evidence without putting it in the usable-image index. */
    private void postScreenshotUnavailable(
            BridgeConfig config,
            String observationId,
            long observationVersion,
            String captureType,
            long captureCount,
            String code,
            String message) {
        int width = 1;
        int height = 1;
        try {
            Display display = ((android.view.WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay();
            android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
            display.getRealMetrics(metrics);
            width = Math.max(1, metrics.widthPixels);
            height = Math.max(1, metrics.heightPixels);
        } catch (RuntimeException ignored) {
            // Keep the contract valid while the service is being torn down.
        }
        try {
            JSONObject payload = new JSONObject()
                    .put("schema_version", "1.0")
                    .put("android_schema_version", "1.0")
                    .put("task_id", config.taskId)
                    .put("device_id", config.deviceId)
                    .put("screenshot_id", config.deviceId + "-shot-missing-" + UUID.randomUUID().toString().replace("-", ""))
                    .put("observation_id", observationId)
                    .put("observation_version", observationVersion)
                    .put("capture_type", captureType)
                    .put("captured_at", java.time.Instant.now().toString())
                    .put("width_px", width)
                    .put("height_px", height)
                    .put("png_base64", "")
                    .put("capture_count", Math.max(1L, captureCount))
                    .put("upload_count", Math.max(0L, screenshotUploadCount))
                    .put("missing_reason", code + (message == null || message.isEmpty() ? "" : ": " + message));
            BridgeClient.postScreenshot(config, payload);
        } catch (JSONException exception) {
            Log.e(TAG, "Could not build missing screenshot evidence", exception);
        } catch (BridgeClient.BridgeException exception) {
            Log.e(TAG, "Could not report missing screenshot code=" + exception.code, exception);
        }
    }

    private void executeActionNow(
            final BridgeConfig config,
            final JSONObject action,
            final ActionExecutionGate.Token actionToken,
            final ActionCallback callback) {
        mainHandler.post(() -> {
            if (actionToken == null) {
                reportActionError(callback, "action_controlled", "the task was paused or cancelled before action dispatch");
                return;
            }
            String kind = action.optString("kind", "");
            String targetId = action.optString("target_node_id", "");
            long expectedVersion = action.optLong("observation_version", -1L);
            boolean nodeAction = "tap".equals(kind) || "set_text".equals(kind)
                    || ("long_press".equals(kind) && !targetId.isEmpty());
            boolean coordinateAction = "coordinate_tap".equals(kind) || "swipe".equals(kind)
                    || ("long_press".equals(kind) && targetId.isEmpty());
            boolean systemAction = "system_back".equals(kind);
            if (expectedVersion < 1L || (nodeAction && targetId.isEmpty()) || (!nodeAction && !coordinateAction && !systemAction)) {
                reportActionError(callback, "invalid_action", "action kind, target and observation version are required");
                return;
            }
            if (expectedVersion != lastCapturedVersion) {
                reportActionError(callback, "stale_observation", "the action is bound to an obsolete Accessibility observation");
                return;
            }
            if (coordinateAction && !refreshAndMatchCoordinateContext(action)) {
                reportActionError(callback, "stale_observation",
                        "the visible page or coordinate target changed after its observation");
                return;
            }
            try {
                if ("system_back".equals(kind)) {
                    if (!frameMatchesCurrentDisplay(action.optJSONObject("coordinate_frame"))) {
                        reportActionError(callback, "stale_observation", "the action rotation or display frame is stale");
                        return;
                    }
                    final boolean[] performed = new boolean[1];
                    if (!actionToken.runIfCurrent(() -> performed[0] = performGlobalAction(GLOBAL_ACTION_BACK))) {
                        reportActionError(callback, "action_controlled", "the task was paused or cancelled before action dispatch");
                    } else if (!performed[0]) {
                        reportActionError(callback, "action_failed", "system Back was not accepted by Accessibility");
                    } else if (callback != null) {
                        callback.onSuccess();
                    }
                } else if ("coordinate_tap".equals(kind) || "swipe".equals(kind)) {
                    dispatchCoordinateAction(action, actionToken, callback);
                } else if ("long_press".equals(kind)) {
                    NodeBinding binding = validateBoundBinding(action);
                    dispatchGestureAt(action, actionToken, centerX(binding.bounds), centerY(binding.bounds), callback);
                } else {
                    NodeBinding binding = validateBoundBinding(action);
                    AccessibilityNodeInfo node = binding.node;
                    final boolean[] performed = new boolean[1];
                    if (!actionToken.runIfCurrent(() -> performed[0] = performBoundAction(node, action))) {
                        reportActionError(callback, "action_controlled", "the task was paused or cancelled before action dispatch");
                    } else if (performed[0]) {
                        if (callback != null) {
                            callback.onSuccess();
                        }
                    } else {
                        reportActionError(callback, "target_node_not_found", "the observation-bound Accessibility node is no longer available");
                    }
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

    private boolean refreshAndMatchCoordinateContext(JSONObject action) {
        long expectedVersion = action.optLong("observation_version", -1L);
        String expectedFingerprint = coordinateFingerprint;
        if (expectedVersion < 1L || expectedVersion != coordinateFingerprintVersion
                || expectedFingerprint.isEmpty()) {
            return false;
        }
        try {
            BridgeConfig identity = new BridgeConfig("", "",
                    action.optString("device_id", "standalone-device"),
                    action.optString("task_id", ""));
            JSONObject current = captureOnServiceThread(identity);
            return CoordinateObservationFingerprint.matches(expectedFingerprint, current,
                    action.optJSONObject("coordinate_frame"));
        } catch (JSONException | RuntimeException exception) {
            return false;
        }
    }

    /**
     * Execute on the node retained from the accepted before frame.  A refresh
     * is required immediately before the action so a detached/replaced node
     * cannot be treated as the same target merely because its old path and
     * label are still present in the current window.
     */
    private NodeBinding validateBoundBinding(JSONObject action) throws ActionBindingException {
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
                || !safeEquals(binding.packageName, text(node.getPackageName()))
                || !safeEquals(binding.className, text(node.getClassName()))
                || !safeEquals(binding.viewIdResourceName, viewId(node))) {
            throw new ActionBindingException("the observation-bound Accessibility node identity changed");
        }
        String currentUniqueId = uniqueId(node);
        if (!binding.uniqueId.isEmpty() && !safeEquals(binding.uniqueId, currentUniqueId)) {
            throw new ActionBindingException("the observation-bound Accessibility node identity changed");
        }
        Rect currentBounds = new Rect();
        node.getBoundsInScreen(currentBounds);
        if (!binding.bounds.equals(currentBounds)) {
            throw new ActionBindingException("the observation-bound Accessibility node moved");
        }
        ensureBoundWindowVisible(binding);
        if (!node.isVisibleToUser() || !node.isEnabled()) {
            throw new ActionBindingException("the observation-bound Accessibility node is disabled or hidden");
        }
        if (!matchesActionLabel(node, action.optString("target_node_label", ""))) {
            throw new ActionBindingException("the observation-bound Accessibility node label changed");
        }
        return binding;
    }

    private boolean performBoundAction(AccessibilityNodeInfo node, JSONObject action) {
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

    private boolean dispatchGestureAt(
            JSONObject action,
            ActionExecutionGate.Token actionToken,
            float x,
            float y,
            ActionCallback callback) throws ActionBindingException {
        JSONObject parameters = action.optJSONObject("parameters");
        long duration = parameters == null ? 700L : parameters.optLong("duration_ms", 700L);
        duration = Math.max(500L, Math.min(duration, GestureDescription.getMaxGestureDuration()));
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0L, duration));
        return dispatchGestureWithCallback(builder.build(), actionToken, callback);
    }

    private boolean dispatchCoordinateAction(
            JSONObject action,
            ActionExecutionGate.Token actionToken,
            ActionCallback callback) throws ActionBindingException {
        JSONObject parameters = action.optJSONObject("parameters");
        if (parameters == null) {
            throw new ActionBindingException("coordinate action parameters are missing");
        }
        JSONObject frame = action.optJSONObject("coordinate_frame");
        if (frame == null) {
            throw new ActionBindingException("coordinate frame is missing");
        }
        float[] start = mapPoint(frame, parameters.optDouble("x1", parameters.optDouble("x", Double.NaN)),
                parameters.optDouble("y1", parameters.optDouble("y", Double.NaN)));
        float[] end = mapPoint(frame, parameters.optDouble("x2", parameters.optDouble("x", Double.NaN)),
                parameters.optDouble("y2", parameters.optDouble("y", Double.NaN)));
        if (start == null || end == null) {
            throw new ActionBindingException("coordinate is outside the current display frame");
        }
        ensureCoordinateWindowVisible(frame, start[0], start[1], end[0], end[1]);
        Path path = new Path();
        path.moveTo(start[0], start[1]);
        long duration = parameters.optLong("duration_ms", "swipe".equals(action.optString("kind")) ? 600L : 60L);
        if ("swipe".equals(action.optString("kind"))) {
            duration = Math.max(100L, Math.min(duration, GestureDescription.getMaxGestureDuration()));
            path.lineTo(end[0], end[1]);
        } else if ("long_press".equals(action.optString("kind"))) {
            duration = Math.max(500L, Math.min(duration, GestureDescription.getMaxGestureDuration()));
        }
        GestureDescription.Builder builder = new GestureDescription.Builder();
        builder.addStroke(new GestureDescription.StrokeDescription(path, 0L, duration));
        return dispatchGestureWithCallback(builder.build(), actionToken, callback);
    }

    private boolean dispatchGestureWithCallback(
            GestureDescription gesture,
            ActionExecutionGate.Token actionToken,
            ActionCallback callback)
            throws ActionBindingException {
        final boolean[] dispatched = new boolean[1];
        if (!actionToken.runIfCurrent(() -> dispatched[0] = dispatchGesture(gesture, new GestureResultCallback() {
            @Override
            public void onCompleted(GestureDescription description) {
                if (callback != null) {
                    callback.onSuccess();
                }
            }

            @Override
            public void onCancelled(GestureDescription description) {
                reportActionError(callback, "action_cancelled", "Accessibility gesture was cancelled");
            }
        }, mainHandler))) {
            reportActionError(callback, "action_controlled", "the task was paused or cancelled before action dispatch");
            return false;
        }
        if (!dispatched[0]) {
            throw new ActionBindingException("Accessibility gesture could not be dispatched");
        }
        return true;
    }

    private float[] mapPoint(JSONObject frame, double rawX, double rawY) throws ActionBindingException {
        if (Double.isNaN(rawX) || Double.isNaN(rawY)) {
            throw new ActionBindingException("coordinate parameters are missing");
        }
        if (!frameMatchesCurrentDisplay(frame)) {
            throw new ActionBindingException("the action rotation or display frame is stale");
        }
        double modelWidth = frame.optDouble("model_width_px", frame.optDouble("screen_width_px", 0));
        double modelHeight = frame.optDouble("model_height_px", frame.optDouble("screen_height_px", 0));
        double screenWidth = frame.optDouble("screen_width_px", 0);
        double screenHeight = frame.optDouble("screen_height_px", 0);
        if (modelWidth <= 0 || modelHeight <= 0 || screenWidth <= 0 || screenHeight <= 0) {
            throw new ActionBindingException("coordinate frame dimensions are invalid");
        }
        double x = rawX * screenWidth / modelWidth;
        double y = rawY * screenHeight / modelHeight;
        // The bridge frame is already expressed in the current physical
        // display orientation. Rotation is a freshness guard only; applying
        // another transform here would rotate a valid point a second time.
        double mappedX = x;
        double mappedY = y;
        String space = frame.optString("coordinate_space", "screen");
        if ("window".equals(space) || "content".equals(space)) {
            JSONObject offset = frame.optJSONObject("window_offset");
            if (offset != null) {
                mappedX += offset.optInt("x", 0);
                mappedY += offset.optInt("y", 0);
            }
        }
        if ("content".equals(space)) {
            JSONObject insets = frame.optJSONObject("system_bar_insets");
            if (insets != null) {
                mappedX += insets.optInt("left", 0);
                mappedY += insets.optInt("top", 0);
            }
        }
        if (mappedX < 0 || mappedY < 0 || mappedX >= screenWidth || mappedY >= screenHeight) {
            throw new ActionBindingException("coordinate is outside the current display frame");
        }
        return new float[]{(float) mappedX, (float) mappedY};
    }

    private boolean frameMatchesCurrentDisplay(JSONObject frame) throws ActionBindingException {
        if (frame == null) {
            throw new ActionBindingException("coordinate frame is missing");
        }
        android.util.DisplayMetrics metrics = new android.util.DisplayMetrics();
        android.view.WindowManager windowManager = (android.view.WindowManager) getSystemService(WINDOW_SERVICE);
        if (windowManager == null) {
            return false;
        }
        windowManager.getDefaultDisplay().getRealMetrics(metrics);
        int rotation = windowManager.getDefaultDisplay().getRotation();
        if (frame.optInt("rotation", -1) != rotation
                || frame.optInt("screen_width_px", -1) != metrics.widthPixels
                || frame.optInt("screen_height_px", -1) != metrics.heightPixels) {
            return false;
        }
        int expectedWindowId = frame.optInt("active_window_id", -1);
        JSONObject expectedBoundsJson = frame.optJSONObject("active_window_bounds");
        if (expectedWindowId < 0 || expectedBoundsJson == null) {
            return false;
        }
        Rect expectedBounds = rectFromJson(expectedBoundsJson);
        if (expectedBounds == null) {
            return false;
        }
        JSONObject expectedOffset = frame.optJSONObject("window_offset");
        JSONObject expectedInsets = frame.optJSONObject("system_bar_insets");
        int expectedContentWidth = frame.optInt("content_width_px", expectedBounds.width());
        int expectedContentHeight = frame.optInt("content_height_px", expectedBounds.height());
        if (expectedOffset == null || expectedInsets == null
                || expectedOffset.optInt("x", Integer.MIN_VALUE) != expectedBounds.left
                || expectedOffset.optInt("y", Integer.MIN_VALUE) != expectedBounds.top
                || expectedContentWidth != expectedBounds.width()
                || expectedContentHeight != expectedBounds.height()
                || expectedInsets.optInt("left", Integer.MIN_VALUE) != expectedBounds.left
                || expectedInsets.optInt("top", Integer.MIN_VALUE) != expectedBounds.top
                || expectedInsets.optInt("right", Integer.MIN_VALUE) != metrics.widthPixels - expectedBounds.right
                || expectedInsets.optInt("bottom", Integer.MIN_VALUE) != metrics.heightPixels - expectedBounds.bottom) {
            return false;
        }
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (SecurityException exception) {
            return false;
        }
        if (windows == null || windows.isEmpty()) {
            return false;
        }
        try {
            AccessibilityWindowInfo bound = null;
            for (AccessibilityWindowInfo window : windows) {
                if (window != null && window.getId() == expectedWindowId) {
                    bound = window;
                    break;
                }
            }
            if (bound == null || !bound.isActive() || !bound.isFocused()) {
                return false;
            }
            Rect currentBounds = new Rect();
            bound.getBoundsInScreen(currentBounds);
            if (!expectedBounds.equals(currentBounds)) {
                return false;
            }
            String expectedPackage = frame.optString("active_window_package", "");
            if (!expectedPackage.isEmpty()) {
                AccessibilityNodeInfo root = null;
                try {
                    root = bound.getRoot();
                    if (root == null || !expectedPackage.equals(text(root.getPackageName()))) {
                        return false;
                    }
                } finally {
                    if (root != null) {
                        root.recycle();
                    }
                }
            }
            return true;
        } finally {
            for (AccessibilityWindowInfo window : windows) {
                if (window != null) {
                    window.recycle();
                }
            }
        }
    }

    private void ensureCoordinateWindowVisible(
            JSONObject frame,
            float startX,
            float startY,
            float endX,
            float endY) throws ActionBindingException {
        List<AccessibilityWindowInfo> windows;
        try {
            windows = getWindows();
        } catch (SecurityException exception) {
            throw new ActionBindingException("current Accessibility windows are unavailable");
        }
        if (windows == null || windows.isEmpty()) {
            throw new ActionBindingException("current Accessibility windows are unavailable");
        }
        int expectedWindowId = frame.optInt("active_window_id", -1);
        JSONObject expectedBoundsJson = frame.optJSONObject("active_window_bounds");
        Rect expectedBounds = expectedBoundsJson == null ? null : rectFromJson(expectedBoundsJson);
        if (expectedWindowId < 0 || expectedBounds == null) {
            throw new ActionBindingException("the observation-bound Accessibility window is unavailable");
        }
        AccessibilityWindowInfo boundWindow = null;
        try {
            for (AccessibilityWindowInfo window : windows) {
                if (window != null && window.getId() == expectedWindowId) {
                    boundWindow = window;
                    break;
                }
            }
            if (boundWindow == null || !boundWindow.isActive() || !boundWindow.isFocused()) {
                throw new ActionBindingException("the observation-bound Accessibility window is no longer active");
            }
            Rect bounds = new Rect();
            boundWindow.getBoundsInScreen(bounds);
            if (!expectedBounds.equals(bounds)) {
                throw new ActionBindingException("the observation-bound Accessibility window changed");
            }
            if (!bounds.contains((int) startX, (int) startY)
                    || !bounds.contains((int) endX, (int) endY)) {
                throw new ActionBindingException("coordinate path is outside the observation-bound Accessibility window");
            }
            String expectedPackage = frame.optString("active_window_package", "");
            if (!expectedPackage.isEmpty()) {
                AccessibilityNodeInfo root = null;
                try {
                    root = boundWindow.getRoot();
                    if (root == null || !expectedPackage.equals(text(root.getPackageName()))) {
                        throw new ActionBindingException("the observation-bound Accessibility window package changed");
                    }
                } finally {
                    if (root != null) {
                        root.recycle();
                    }
                }
            }
            for (AccessibilityWindowInfo window : windows) {
                if (window == null || window == boundWindow
                        || window.getLayer() <= boundWindow.getLayer()) {
                    continue;
                }
                Rect overlayBounds = new Rect();
                window.getBoundsInScreen(overlayBounds);
                // Compare the actual gesture path, not the full application
                // window. System bars may sit above the app while leaving a
                // center-screen gesture actionable; dialogs and IME windows
                // still reject any point/path they really cover.
                RectF gestureBounds = new RectF(
                        Math.min(startX, endX) - 1.0f,
                        Math.min(startY, endY) - 1.0f,
                        Math.max(startX, endX) + 1.0f,
                        Math.max(startY, endY) + 1.0f);
                if (RectF.intersects(gestureBounds, new RectF(overlayBounds))) {
                    throw new ActionBindingException("the observation-bound gesture path is covered by a higher window");
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

    private static Rect rectFromJson(JSONObject value) {
        if (value == null) {
            return null;
        }
        int left = value.optInt("left", Integer.MIN_VALUE);
        int top = value.optInt("top", Integer.MIN_VALUE);
        int right = value.optInt("right", Integer.MIN_VALUE);
        int bottom = value.optInt("bottom", Integer.MIN_VALUE);
        if (left == Integer.MIN_VALUE || top == Integer.MIN_VALUE
                || right == Integer.MIN_VALUE || bottom == Integer.MIN_VALUE
                || right < left || bottom < top) {
            return null;
        }
        return new Rect(left, top, right, bottom);
    }

    private static boolean safeEquals(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private static float centerX(Rect bounds) {
        return (bounds.left + bounds.right) / 2.0f;
    }

    private static float centerY(Rect bounds) {
        return (bounds.top + bounds.bottom) / 2.0f;
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
        Rect activeWindowBounds = null;
        int activeWindowId = -1;

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
                    if (window.isActive() && window.isFocused()) {
                        Rect candidateBounds = new Rect();
                        window.getBoundsInScreen(candidateBounds);
                        if (!candidateBounds.isEmpty()) {
                            activeWindowBounds = candidateBounds;
                            activeWindowId = windowId;
                        }
                    }
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
        if (activeWindowBounds != null) {
            JSONObject screen = ObservationPayload.screen(this, activeWindowBounds);
            screen.put("active_window_id", activeWindowId);
            screen.put("active_window_bounds", ObservationPayload.bounds(activeWindowBounds));
            observation.put("screen", screen);
        }
        coordinateFingerprint = CoordinateObservationFingerprint.create(observation);
        coordinateFingerprintVersion = version;
        return observation;
    }

    /** Add platform bar geometry only to recovery frames, leaving the ordinary observation contract intact. */
    private JSONObject captureRecoveryFrame(BridgeConfig config) throws JSONException {
        JSONObject observation = captureOnServiceThread(config);
        JSONObject screen = observation.optJSONObject("screen");
        if (screen == null) return observation;

        JSONObject metadata = new JSONObject()
                .put("available", false)
                .put("source", "window_metrics_system_bars")
                .put("reason", "api_unavailable");
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowManager windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
                WindowMetrics metrics = windowManager == null ? null : windowManager.getCurrentWindowMetrics();
                if (metrics != null && metrics.getWindowInsets() != null) {
                    Rect metricsBounds = metrics.getBounds();
                    WindowInsets windowInsets = metrics.getWindowInsets();
                    int systemBars = WindowInsets.Type.systemBars();
                    Insets bars = windowInsets.getInsets(systemBars);
                    int screenWidth = screen.optInt("width_px", 0);
                    int screenHeight = screen.optInt("height_px", 0);
                    metadata.put("reason", "metrics_bounds_mismatch")
                            .put("metrics_bounds_px", ObservationPayload.bounds(metricsBounds))
                            .put("status_bars_visible", windowInsets.isVisible(WindowInsets.Type.statusBars()))
                            .put("navigation_bars_visible", windowInsets.isVisible(WindowInsets.Type.navigationBars()))
                            .put("left", Math.max(0, bars.left))
                            .put("top", Math.max(0, bars.top))
                            .put("right", Math.max(0, bars.right))
                            .put("bottom", Math.max(0, bars.bottom));
                    if (metricsBounds.left == 0 && metricsBounds.top == 0
                            && metricsBounds.width() == screenWidth
                            && metricsBounds.height() == screenHeight) {
                        metadata.put("available", true).put("reason", "");
                    }
                } else {
                    metadata.put("reason", "window_metrics_unavailable");
                }
            }
        } catch (RuntimeException exception) {
            metadata.put("reason", "window_metrics_read_failed");
        }
        screen.put("recovery_system_bar_insets", metadata);
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
                .put("class_name", root == null ? "" : text(root.getClassName()))
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
        // Password fields can appear in the same Accessibility tree as the
        // task controls.  Keep their shape and editability for targeting, but
        // never serialize their value or descriptive text to the bridge.
        boolean password = node.isPassword();
        String nodeText = password ? "" : text(node.getText());
        String nodeDescription = password ? "" : text(node.getContentDescription());
        String nodeStateDescription = password ? "" : stateDescription(node);
        JSONObject json = new JSONObject()
                .put("node_id", nodeId)
                .put("parent_node_id", parentId == null ? JSONObject.NULL : parentId)
                .put("class_name", text(node.getClassName()))
                .put("package_name", text(node.getPackageName()))
                .put("text", nodeText)
                .put("content_description", nodeDescription)
                .put("state_description", nodeStateDescription)
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
