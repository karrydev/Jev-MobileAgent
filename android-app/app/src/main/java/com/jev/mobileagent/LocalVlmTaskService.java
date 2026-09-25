package com.jev.mobileagent;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** User-visible, app-local task runner. No request is sent to the old bridge. */
public final class LocalVlmTaskService extends Service {
    public static final String ACTION_ARM = "com.jev.mobileagent.localvlm.ARM";
    public static final String ACTION_START = "com.jev.mobileagent.localvlm.START";
    public static final String ACTION_PAUSE = "com.jev.mobileagent.localvlm.PAUSE";
    public static final String ACTION_CANCEL = "com.jev.mobileagent.localvlm.CANCEL";
    public static final String EXTRA_TASK_ID = "task_id";

    private static final String CHANNEL_ID = "standalone_vlm_task";
    private static final int NOTIFICATION_ID = 2601;
    private static final long CALLBACK_TIMEOUT_SECONDS = 35L;
    private static volatile boolean taskLoopActive;

    private final ExecutorService taskExecutor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "jev-standalone-vlm-task");
        thread.setDaemon(true);
        return thread;
    });
    private final ActionExecutionGate actionGate = new ActionExecutionGate();
    private volatile String taskId = "";
    private volatile String controlCommand = "";
    private volatile AtomicBoolean activeRequestCancellation;
    private volatile boolean loopRunning;
    private volatile boolean deviceActionUnresolved;
    private volatile boolean deviceActionNeedsVerification;
    private boolean foregroundStarted;

    public static boolean isTaskLoopActive() {
        return taskLoopActive;
    }

    public static void arm(Context context, String taskId) {
        start(context, ACTION_ARM, taskId);
    }

    public static void startTask(Context context, String taskId) {
        start(context, ACTION_START, taskId);
    }

    public static void pauseTask(Context context, String taskId) {
        start(context, ACTION_PAUSE, taskId);
    }

    public static void cancelTask(Context context, String taskId) {
        start(context, ACTION_CANCEL, taskId);
    }

    private static void start(Context context, String action, String taskId) {
        Intent intent = new Intent(context, LocalVlmTaskService.class)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, taskId == null ? "" : taskId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            LocalTaskStore.markInterrupted(this);
            stopForeground(true);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        String requestedTaskId = intent.getStringExtra(EXTRA_TASK_ID);
        if (requestedTaskId != null && !requestedTaskId.isEmpty()) {
            taskId = requestedTaskId;
        }
        if (!foregroundStarted) {
            promoteForeground();
        }
        String action = intent.getAction();
        if (ACTION_ARM.equals(action)) {
            armTask();
        } else if (ACTION_START.equals(action)) {
            beginTaskLoop();
        } else if (ACTION_PAUSE.equals(action)) {
            requestControl("pause");
        } else if (ACTION_CANCEL.equals(action)) {
            requestControl("cancel");
        }
        return START_NOT_STICKY;
    }

    private void armTask() {
        JSONObject task = LocalTaskStore.task(this, taskId);
        if (task == null || !"ARMED".equals(task.optString("state", ""))) {
            setStatus("没有可启动的待命任务");
            return;
        }
        setStatus("已就绪。切换到目标应用后，从通知中点“开始任务”");
        refreshNotification();
    }

    private void beginTaskLoop() {
        if (loopRunning) {
            return;
        }
        JSONObject task = LocalTaskStore.task(this, taskId);
        if (task == null) {
            setStatus("本地任务记录不存在");
            refreshNotification();
            return;
        }
        String state = task.optString("state", "");
        if (!"ARMED".equals(state)) {
            if ("RUNNING".equals(state) && taskLoopActive) {
                return;
            }
            setStatus("任务当前状态为 " + state + "，需要先检查或取消后再启动");
            refreshNotification();
            return;
        }
        if (!LocalTaskStore.updateState(this, taskId, "RUNNING", "")) {
            setStatus("无法保存本地任务状态，未发送模型请求");
            return;
        }
        controlCommand = "";
        loopRunning = true;
        taskLoopActive = true;
        actionGate.begin();
        setStatus("正在读取当前应用的本地无障碍观察");
        taskExecutor.execute(() -> runTaskLoop(taskId));
    }

    private void requestControl(String command) {
        if (taskId.isEmpty()) {
            refreshNotification();
            return;
        }
        JSONObject task = LocalTaskStore.task(this, taskId);
        if (task == null) {
            refreshNotification();
            return;
        }
        String state = task.optString("state", "");
        if ("SUCCEEDED".equals(state) || "FAILED".equals(state) || "CANCELLED".equals(state)) {
            stopAfterTerminal();
            return;
        }
        if (!loopRunning) {
            if ("cancel".equals(command)) {
                if (LocalTaskStore.hasUnresolvedDeviceAction(this, taskId)) {
                    LocalTaskStore.updateState(this, taskId, "NEEDS_REVIEW",
                            "cancel_requested_with_unresolved_device_action");
                    setStatus("设备动作结果仍未核实，任务保留待核对状态；请检查当前手机页面");
                    refreshNotification();
                    return;
                }
                LocalTaskStore.updateState(this, taskId, "CANCELLED", "cancelled_by_user");
                setStatus("任务已取消");
                stopAfterTerminal();
            } else if ("pause".equals(command) && "ARMED".equals(state)) {
                LocalTaskStore.updateState(this, taskId, "PAUSED", "paused_before_start");
                setStatus("任务已暂停；没有发送请求");
                refreshNotification();
            } else if ("pause".equals(command) && "RUNNING".equals(state)) {
                boolean unresolvedAction = LocalTaskStore.hasUnresolvedDeviceAction(this, taskId);
                LocalTaskStore.updateState(this, taskId, unresolvedAction ? "NEEDS_REVIEW" : "PAUSED",
                        unresolvedAction ? "runtime_not_active_with_unresolved_device_action"
                                : "runtime_not_active; manual review required");
                setStatus(unresolvedAction
                        ? "设备动作结果仍未核实，任务保留待核对状态；请检查当前手机页面"
                        : "运行时已不在前台；任务已安全暂停，请检查页面后结束任务");
                refreshNotification();
            } else {
                refreshNotification();
            }
            return;
        }
        controlCommand = command;
        actionGate.invalidate();
        AtomicBoolean cancellation = activeRequestCancellation;
        if (cancellation != null) {
            cancellation.set(true);
        }
        setStatus("cancel".equals(command) ? "正在停止任务" : "正在暂停任务并关闭在途请求");
        refreshNotification();
    }

    private void runTaskLoop(String runTaskId) {
        String finalState = "PAUSED";
        String finalReason = "runtime_stopped_safely";
        try {
            JSONObject task = LocalTaskStore.task(this, runTaskId);
            if (task == null) {
                throw new TaskFailure("task_record_missing", "本地任务记录丢失");
            }
            ModelProfileStore.Profile profile = ModelProfileStore.load(this, ModelProfileStore.Type.VLM);
            if (!profile.isConfigured()) {
                throw new TaskFailure("vlm_profile_missing", "请先保存 VLM endpoint、model 和 API key");
            }
            if (!LocalVlmBudgetPolicy.isReviewedProfile(profile.provider, profile.model)) {
                throw new TaskFailure("unpriced_profile", "本地没有该模型的已审查费率，无法执行 ¥1 预算门控");
            }
            if (!ObservationAccessibilityService.isEnabled(this)) {
                throw new TaskFailure("accessibility_unavailable", "无障碍服务未启用；任务已安全暂停");
            }

            MobileAgentVlmRoles roles = new MobileAgentVlmRoles();
            roles.restore(task.optJSONObject("history"));
            roles.instruction = task.optString("goal", "");
            ActionExecutionGate.Token actionToken = actionGate.begin();
            boolean firstObservation = true;
            int step = task.optInt("step_count", 0);
            while (step < LocalTaskStore.MAX_STEPS && !hasControlRequest()) {
                setStatus("第 " + (step + 1) + "/" + LocalTaskStore.MAX_STEPS + " 步：读取当前页面");
                JSONObject before = observe(runTaskId, firstObservation);
                firstObservation = false;
                persistObservation(runTaskId, before);
                JSONObject beforeShot = captureScreenshot(runTaskId, before, "BEFORE");
                JSONArray beforeImages = new JSONArray().put(beforeShot);

                roles.instruction = task.optString("goal", "");
                roles.errorFlagPlan = errorEscalationRequired(roles);
                boolean skipManager = shouldSkipManager(roles);
                if (!skipManager) {
                    String response = requestRole(profile, runTaskId, "manager", step,
                            roles.managerPrompt(), beforeImages);
                    String[] planning = roles.parseManager(response);
                    roles.completedPlan = planning[1];
                    roles.plan = planning[2];
                    if (roles.plan.trim().equalsIgnoreCase("Finished")
                            || roles.plan.trim().startsWith("Finished\n")) {
                        roles.finishThought = planning[0];
                        StandaloneGoalVerifier.Outcome goalOutcome = verifyOverallGoal(runTaskId, task);
                        if (StandaloneGoalVerifier.mayMarkSucceeded(goalOutcome)) {
                            recordTerminal(roles, "finished_by_manager_and_page_goal_verified");
                            finalState = "SUCCEEDED";
                            finalReason = "overall_goal_verified_after_manager_finished";
                        } else {
                            finalState = "NEEDS_REVIEW";
                            finalReason = goalOutcome == StandaloneGoalVerifier.Outcome.NOT_VERIFIED
                                    ? "overall_goal_not_verified_after_manager_finished"
                                    : "overall_goal_unknown_after_manager_finished";
                            setStatus("模型报告完成，但刚采集的页面状态未能证明整体目标；任务待核对");
                        }
                        LocalTaskStore.saveHistory(this, runTaskId, roles.toJson());
                        break;
                    }
                }

                setStatus("第 " + (step + 1) + "/" + LocalTaskStore.MAX_STEPS + " 步：执行角色选择动作");
                String executorResponse = requestRole(profile, runTaskId, "executor", step,
                        roles.executorPrompt(), beforeImages);
                String[] selected = roles.parseExecutor(executorResponse);
                roles.lastActionThought = selected[0];
                roles.lastSummary = selected[2];
                String actionSummary = selected[2];
                MobileAgentVlmRoles.ActionCommand command;
                try {
                    command = roles.action(selected[1], before, runTaskId, step + 1,
                            beforeShot.optString("screenshot_id", ""));
                } catch (JSONException exception) {
                    if (task.optBoolean("jev_selection_enabled", false)) {
                        runJevControlledForDecision(task, runTaskId, step, before, null);
                    } else {
                        runJevShadowForDecision(task, runTaskId, step, before, null);
                    }
                    roles.recordInvalid(actionSummary, "invalid action format; no device action was sent");
                    finishStep(runTaskId, roles, ++step);
                    continue;
                }
                if (command.terminal) {
                    if (command.answer) {
                        roles.recordAnswer(command, selected[2]);
                    }
                    finishStep(runTaskId, roles, ++step);
                    StandaloneGoalVerifier.Outcome goalOutcome = verifyOverallGoal(runTaskId, task);
                    if (StandaloneGoalVerifier.mayMarkSucceeded(goalOutcome)) {
                        recordTerminal(roles, "finished_by_executor_and_page_goal_verified");
                        finalState = "SUCCEEDED";
                        finalReason = "overall_goal_verified_after_executor_finished";
                    } else {
                        finalState = "NEEDS_REVIEW";
                        finalReason = goalOutcome == StandaloneGoalVerifier.Outcome.NOT_VERIFIED
                                ? "overall_goal_not_verified_after_executor_finished"
                                : "overall_goal_unknown_after_executor_finished";
                        setStatus("模型报告完成，但刚采集的页面状态未能证明整体目标；任务待核对");
                    }
                    LocalTaskStore.saveHistory(this, runTaskId, roles.toJson());
                    break;
                }

                boolean jevActionSelected = false;
                if (task.optBoolean("jev_selection_enabled", false)) {
                    JevShadow.ControlledAttempt controlled = runJevControlledForDecision(
                            task, runTaskId, step, before, command.contractAction);
                    if (controlled.selectedCandidate != null) {
                        try {
                            command = roles.actionForCandidate(controlled.selectedCandidate, before,
                                    runTaskId, step + 1, beforeShot.optString("screenshot_id", ""));
                            actionSummary = controlled.selectedCandidate.description;
                            roles.lastActionThought = "Jev selected candidate "
                                    + controlled.selectedCandidate.id;
                            roles.lastSummary = actionSummary;
                            jevActionSelected = true;
                        } catch (JSONException exception) {
                            if (!JevShadow.markControlledFallback(this, runTaskId, step,
                                    "candidate_action_unavailable")) {
                                throw new TaskFailure("jev_control_report_failed",
                                        "Jev 候选动作不可用且回退记录无法保存；任务已暂停");
                            }
                        }
                    }
                } else {
                    runJevShadowForDecision(task, runTaskId, step, before, command.contractAction);
                }

                JSONObject action = command.contractAction;
                if (!LocalTaskStore.recordActionIntent(this, runTaskId, action)) {
                    throw new TaskFailure("action_journal_failed", "无法保存动作记录，未执行设备操作");
                }
                if (jevActionSelected && !annotateJevDispatch(runTaskId, step, action,
                        false, "action_journaled")) {
                    throw new TaskFailure("jev_control_report_failed",
                            "Jev 动作已记录，但选择报告无法更新；任务已暂停");
                }
                setStatus("正在执行与第 " + (step + 1) + " 次观察绑定的设备动作");
                deviceActionUnresolved = true;
                ActionResult actionResult;
                try {
                    actionResult = performAction(action, actionToken);
                    deviceActionUnresolved = false;
                } catch (TaskStopped stopped) {
                    LocalTaskStore.recordActionResult(this, runTaskId,
                            action.optString("action_id", ""), "outcome_unknown",
                            ActionResult.failure("action_outcome_unknown", "设备动作结果无法确认").toJson());
                    if (jevActionSelected) {
                        annotateJevDispatch(runTaskId, step, action, null, "outcome_unknown");
                    }
                    throw stopped;
                }
                if (hasControlRequest()) {
                    boolean notDispatched = !actionResult.success && isKnownNotDispatched(actionResult.code);
                    boolean saved = LocalTaskStore.recordActionResult(this, runTaskId,
                            action.optString("action_id", ""), notDispatched ? "not_dispatched" : "controlled",
                            actionResult.toJson());
                    if (jevActionSelected) {
                        annotateJevDispatch(runTaskId, step, action,
                                notDispatched ? false : null,
                                notDispatched ? "not_dispatched" : "outcome_unknown");
                    }
                    deviceActionUnresolved = !saved || !notDispatched;
                    throw new TaskStopped();
                }
                if (!actionResult.success) {
                    boolean knownNotDispatched = isKnownNotDispatched(actionResult.code);
                    boolean saved = LocalTaskStore.recordActionResult(this, runTaskId,
                            action.optString("action_id", ""),
                            knownNotDispatched ? "not_dispatched" : "outcome_unknown", actionResult.toJson());
                    if (!saved) {
                        deviceActionUnresolved = true;
                        throw new TaskFailure("action_outcome_not_saved", "设备动作结果无法写入本地记录；任务待核对");
                    }
                    if (jevActionSelected && !annotateJevDispatch(runTaskId, step, action,
                            knownNotDispatched ? false : null,
                            knownNotDispatched ? "not_dispatched" : "outcome_unknown")) {
                        deviceActionUnresolved = true;
                        throw new TaskFailure("jev_control_report_failed",
                                "Jev 动作结果已保存，但选择报告无法更新；任务待核对");
                    }
                    if (!knownNotDispatched) {
                        deviceActionUnresolved = true;
                        throw new TaskFailure("action_outcome_needs_review",
                                "设备动作返回结果不明确；任务待核对，请检查当前手机页面");
                    }
                    roles.recordInvalid(actionSummary, actionResult.safeMessage);
                    finishStep(runTaskId, roles, ++step);
                    continue;
                }
                deviceActionNeedsVerification = true;
                if (!LocalTaskStore.recordActionResult(this, runTaskId,
                        action.optString("action_id", ""), "executed", actionResult.toJson())) {
                    throw new TaskFailure("action_outcome_not_saved", "设备动作已执行但本地结果无法保存；任务需人工检查");
                }
                if (jevActionSelected && !annotateJevDispatch(runTaskId, step, action,
                        true, "executed")) {
                    throw new TaskFailure("jev_control_report_failed",
                            "Jev 动作已执行，但选择报告无法更新；任务需人工检查");
                }

                JSONObject after;
                JSONObject afterShot;
                boolean treeVerificationEnabled = task.optBoolean("tree_verification_enabled", false);
                try {
                    after = observe(runTaskId, false);
                    persistObservation(runTaskId, after);
                    afterShot = treeVerificationEnabled
                            ? captureScreenshotForTreeVerification(runTaskId, after, "AFTER")
                            : captureScreenshot(runTaskId, after, "AFTER");
                } catch (TaskFailure failure) {
                    throw new TaskFailure("action_outcome_needs_review",
                            "设备动作已执行但后续页面无法验证；任务已暂停，请检查当前手机页面");
                }
                roles.lastSummary = actionSummary;
                roles.setActionForReflection(command.original);
                if (treeVerificationEnabled) {
                    TreeActionVerifier.Result verification;
                    try {
                        verification = verifyTreeAction(profile, task, runTaskId, step,
                                action, before, after, beforeShot, afterShot, roles);
                    } catch (JSONException exception) {
                        throw new TaskFailure("action_verification_evidence_invalid",
                                "动作核验证据无法编码；设备动作待核对");
                    }
                    String legacyOutcome = legacyReflectionOutcome(verification.status);
                    String explanation = verification.status.name() + ": " + verification.reason;
                    roles.recordAction(command.original, actionSummary, legacyOutcome, explanation);
                    roles.clearActionForReflection();
                    finishStep(runTaskId, roles, ++step);
                    if (verification.status == TreeActionVerifier.Status.SUCCESS
                            || verification.status == TreeActionVerifier.Status.FAILURE) {
                        deviceActionNeedsVerification = false;
                    } else {
                        String reason = verification.status == TreeActionVerifier.Status.PENDING
                                ? "action_verification_pending" : "action_verification_unknown";
                        setStatus("动作后置条件仍无法确认；任务已安全暂停，需人工检查");
                        throw new TaskFailure(reason, "动作后置条件无法确认；任务已安全暂停，请检查当前手机页面");
                    }
                    if (hasControlRequest()) throw new TaskStopped();
                    continue;
                }
                String reflection = requestRole(profile, runTaskId, "action_reflector", step,
                        roles.reflectorPrompt(), new JSONArray().put(beforeShot).put(afterShot));
                String[] reflected = roles.parseReflection(reflection);
                String normalizedOutcome = MobileAgentVlmRoles.reflectionOutcomeLabel(reflected[0]);
                if (normalizedOutcome.isEmpty()) {
                    throw new TaskFailure("invalid_reflection", "动作复核结果格式无法识别；任务已暂停");
                }
                if (!LocalTaskStore.recordActionReflection(this, runTaskId,
                        action.optString("action_id", ""), normalizedOutcome, reflected[1])) {
                    throw new TaskFailure("action_verification_not_saved",
                            "设备动作复核结果无法保存；任务待核对");
                }
                roles.recordAction(command.original, actionSummary, normalizedOutcome, reflected[1]);
                roles.clearActionForReflection();
                finishStep(runTaskId, roles, ++step);
                deviceActionNeedsVerification = false;
                if (hasControlRequest()) {
                    throw new TaskStopped();
                }
            }
            if (hasControlRequest()) {
                throw new TaskStopped();
            }
            if (finalState.equals("PAUSED") && step >= LocalTaskStore.MAX_STEPS) {
                finalReason = "max_steps_reached";
            }
        } catch (TaskStopped stopped) {
            String requested = controlCommand;
            if (deviceActionUnresolved || deviceActionNeedsVerification
                    || LocalTaskStore.hasUnresolvedDeviceAction(this, runTaskId)) {
                finalState = "NEEDS_REVIEW";
                finalReason = "control_during_device_action_or_verification";
            } else {
                finalState = "cancel".equals(requested) ? "CANCELLED" : "PAUSED";
                finalReason = "cancel".equals(requested) ? "cancelled_by_user" : "paused_by_user";
            }
        } catch (TaskFailure failure) {
            finalState = deviceActionUnresolved || deviceActionNeedsVerification
                    || LocalTaskStore.hasUnresolvedDeviceAction(this, runTaskId) ? "NEEDS_REVIEW" : "PAUSED";
            finalReason = failure.code;
            setStatus(failure.safeMessage);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            boolean unresolvedAction = LocalTaskStore.hasUnresolvedDeviceAction(this, runTaskId);
            finalState = unresolvedAction ? "NEEDS_REVIEW" : "PAUSED";
            finalReason = unresolvedAction ? "runtime_interrupted_with_unresolved_device_action"
                    : "runtime_thread_interrupted";
            setStatus("运行线程中断，任务已安全暂停");
        } catch (RuntimeException exception) {
            boolean unresolvedAction = LocalTaskStore.hasUnresolvedDeviceAction(this, runTaskId);
            finalState = unresolvedAction ? "NEEDS_REVIEW" : "PAUSED";
            finalReason = unresolvedAction ? "runtime_error_with_unresolved_device_action" : "runtime_error";
            setStatus("本地运行时异常；任务已暂停，请检查状态");
        } finally {
            ObservationAccessibilityService.endLocalTaskCapture();
            DebugTreeVerificationFixtures.clearForTask(this, runTaskId);
            loopRunning = false;
            taskLoopActive = false;
            activeRequestCancellation = null;
            JSONObject current = LocalTaskStore.task(this, runTaskId);
            String storedState = current == null ? "" : current.optString("state", "");
            if ("RUNNING".equals(storedState) || "PAUSING".equals(storedState)) {
                LocalTaskStore.updateState(this, runTaskId, finalState, finalReason);
            } else if ("SUCCEEDED".equals(finalState) || "CANCELLED".equals(finalState)
                    || "NEEDS_REVIEW".equals(finalState)) {
                LocalTaskStore.updateState(this, runTaskId, finalState, finalReason);
            }
            if ("SUCCEEDED".equals(finalState) || "CANCELLED".equals(finalState)) {
                setStatus("SUCCEEDED".equals(finalState) ? "任务已完成" : "任务已取消");
                stopAfterTerminal();
            } else {
                if (current == null || !finalState.equals(storedState)) {
                    setStatus("任务已暂停：" + finalReason);
                }
                refreshNotification();
            }
        }
    }

    private StandaloneGoalVerifier.Outcome verifyOverallGoal(String runTaskId, JSONObject task)
            throws TaskStopped, InterruptedException {
        try {
            JSONObject freshObservation = observe(runTaskId, false);
            persistObservation(runTaskId, freshObservation);
            return StandaloneGoalVerifier.verify(task.optString("goal", ""), freshObservation);
        } catch (TaskFailure failure) {
            setStatus("无法取得最终页面核验；任务待核对");
            return StandaloneGoalVerifier.Outcome.UNKNOWN;
        }
    }

    private static boolean isKnownNotDispatched(String code) {
        return "action_controlled".equals(code)
                || "invalid_action".equals(code)
                || "stale_observation".equals(code)
                || "target_node_not_found".equals(code)
                || "permission_unavailable".equals(code);
    }

    private JSONObject observe(String runTaskId, boolean first) throws TaskFailure, TaskStopped, InterruptedException {
        if (hasControlRequest()) {
            throw new TaskStopped();
        }
        try {
            JSONObject observation = await(callback -> {
                ObservationAccessibilityService.LocalObservationCallback serviceCallback =
                        new ObservationAccessibilityService.LocalObservationCallback() {
                            @Override
                            public void onSuccess(JSONObject value) {
                                callback.success(value);
                            }

                            @Override
                            public void onError(String code, String message) {
                                callback.failure(code, message);
                            }
                        };
                if (first) {
                    ObservationAccessibilityService.beginLocalTaskCapture(runTaskId, serviceCallback);
                } else {
                    ObservationAccessibilityService.requestLocalObservation(runTaskId, serviceCallback);
                }
            }, "本地无障碍观察失败");
            if (!"AVAILABLE".equals(observation.optString("availability", ""))) {
                throw new TaskFailure("observation_unavailable", "当前页面没有可用的无障碍观察；任务已暂停");
            }
            return observation;
        } catch (TaskStopped stopped) {
            throw stopped;
        } catch (TaskFailure failure) {
            throw failure;
        }
    }

    private JSONObject captureScreenshot(String runTaskId, JSONObject observation, String type)
            throws TaskFailure, TaskStopped, InterruptedException {
        if (hasControlRequest()) {
            throw new TaskStopped();
        }
        JSONObject screenshot;
        try {
            screenshot = await(callback -> ObservationAccessibilityService.requestLocalScreenshot(
                    observation, type, new ObservationAccessibilityService.LocalScreenshotCallback() {
                        @Override
                        public void onSuccess(JSONObject value) {
                            callback.success(value);
                        }

                        @Override
                        public void onError(String code, String message) {
                            callback.failure(code, message);
                        }
                    }), "本地截图失败");
        } catch (TaskFailure failure) {
            boolean logged = LocalTaskStore.recordScreenshotCapture(this, runTaskId,
                    screenshotCaptureRecord(observation, type, null, "unavailable", failure.code));
            if (!logged) {
                throw new TaskFailure("screenshot_capture_log_not_saved", "截图失败记录无法保存；任务已安全暂停");
            }
            throw failure;
        }
        try {
            String path = LocalTaskStore.saveScreenshot(this, runTaskId,
                    observation.optLong("observation_version", 0L), type,
                    screenshot.optString("png_base64", ""));
            screenshot.put("private_file", path);
            if (!LocalTaskStore.recordScreenshotCapture(this, runTaskId,
                    screenshotCaptureRecord(observation, type, screenshot, "captured", ""))) {
                throw new TaskFailure("screenshot_capture_log_not_saved", "截图采集记录无法保存；任务已安全暂停");
            }
            return screenshot;
        } catch (TaskFailure failure) {
            throw failure;
        } catch (IOException | JSONException exception) {
            LocalTaskStore.recordScreenshotCapture(this, runTaskId,
                    screenshotCaptureRecord(observation, type, screenshot, "unavailable", "screenshot_evidence_not_saved"));
            throw new TaskFailure("screenshot_evidence_not_saved", "本地截图证据无法保存；任务已暂停");
        }
    }

    private static JSONObject screenshotCaptureRecord(JSONObject observation, String type,
            JSONObject screenshot, String status, String reason) {
        JSONObject result = new JSONObject();
        try {
            result.put("status", status)
                    .put("capture_type", type)
                    .put("observation_id", observation == null ? "" : observation.optString("observation_id", ""))
                    .put("observation_version", observation == null ? 0L : observation.optLong("observation_version", 0L))
                    .put("screenshot_id", screenshot == null ? JSONObject.NULL
                            : screenshot.optString("screenshot_id", ""))
                    .put("error_code", reason == null || reason.isEmpty() ? JSONObject.NULL : reason)
                    .put("private_file", screenshot == null ? JSONObject.NULL
                            : screenshot.optString("private_file", ""))
                    .put("captured_at", java.time.Instant.now().toString());
        } catch (JSONException ignored) {
            // The fixed safe metadata keys cannot fail to encode.
        }
        return result;
    }

    private JSONObject captureScreenshotForTreeVerification(String runTaskId, JSONObject observation,
            String type) throws TaskFailure, TaskStopped, InterruptedException {
        try {
            return captureScreenshot(runTaskId, observation, type);
        } catch (TaskFailure failure) {
            if (!isRecoverableScreenshotFailure(failure.code)) throw failure;
            JSONObject missing = new JSONObject();
            try {
                missing.put("capture_type", type)
                        .put("observation_id", observation == null ? ""
                                : observation.optString("observation_id", ""))
                        .put("missing_reason", failure.code)
                        .put("png_base64", "");
            } catch (JSONException ignored) {
                // The evidence envelope uses fixed string fields.
            }
            return missing;
        }
    }

    private static boolean isRecoverableScreenshotFailure(String code) {
        return code != null && (code.startsWith("screenshot_")
                || "permission_unavailable".equals(code)
                || "observation_mismatch".equals(code));
    }

    private TreeActionVerifier.Result verifyTreeAction(ModelProfileStore.Profile profile,
            JSONObject task, String runTaskId, int step, JSONObject action,
            JSONObject before, JSONObject initialAfter, JSONObject beforeShot,
            JSONObject initialAfterShot, MobileAgentVlmRoles roles)
            throws TaskFailure, TaskStopped, InterruptedException, JSONException {
        JSONObject after = initialAfter;
        JSONObject afterShot = initialAfterShot;
        int waits = 0;
        JSONArray ruleAttempts = new JSONArray();
        TreeActionVerifier.Result rule = TreeActionVerifier.verify(before, after, action,
                beforeShot, afterShot);
        appendRuleAttempt(ruleAttempts, rule, after, afterShot, "initial");

        while (rule.status == TreeActionVerifier.Status.PENDING
                && waits < TreeActionVerifier.MAX_WAIT_ATTEMPTS) {
            if (hasControlRequest()) throw new TaskStopped();
            Thread.sleep(TreeActionVerifier.WAIT_INTERVAL_MILLIS);
            if (hasControlRequest()) throw new TaskStopped();
            after = observe(runTaskId, false);
            persistObservation(runTaskId, after);
            afterShot = captureScreenshotForTreeVerification(runTaskId, after, "AFTER");
            waits++;
            rule = TreeActionVerifier.verify(before, after, action, beforeShot, afterShot);
            appendRuleAttempt(ruleAttempts, rule, after, afterShot, "wait_" + waits);
        }

        TreeActionVerifier.Result decision = rule;
        String source = "tree_rule";
        JSONObject jevSummary = null;
        if (rule.status != TreeActionVerifier.Status.SUCCESS
                && rule.status != TreeActionVerifier.Status.FAILURE) {
            decision = TreeActionVerifier.decision(TreeActionVerifier.Status.UNKNOWN,
                    rule.status == TreeActionVerifier.Status.PENDING
                            ? "bounded_tree_wait_still_pending" : rule.reason,
                    rule.evidence);
            source = "tree_rule";
            if (!persistTreeVerification(runTaskId, action, decision, source, waits,
                    ruleAttempts, before, after, beforeShot, afterShot, null)) {
                throw new TaskFailure("action_verification_not_saved",
                        "树核验结果无法保存在本地；设备动作待核对");
            }

            // Without both actual images, visual fallback cannot decide and must not invent evidence.
            if (!TreeActionVerifier.hasImage(beforeShot) || !TreeActionVerifier.hasImage(afterShot)) {
                decision = TreeActionVerifier.decision(TreeActionVerifier.Status.UNKNOWN,
                        "visual_fallback_requires_real_before_and_after_screenshots",
                        new JSONObject().put("before_screenshot_available", TreeActionVerifier.hasImage(beforeShot))
                                .put("after_screenshot_available", TreeActionVerifier.hasImage(afterShot)));
                source = "screenshot_unavailable";
            } else {
                if (task.optBoolean("jev_selection_enabled", false)
                        || task.optBoolean("jev_shadow_enabled", false)) {
                    jevSummary = runTreeVerificationWithJev(task, runTaskId, step,
                            action, before, after, rule);
                    TreeActionVerifier.Status jevStatus = jevSummary == null
                            ? TreeActionVerifier.Status.UNKNOWN
                            : TreeActionVerifier.parseModelStatus(jevSummary.optString("verification_status", ""));
                    boolean jevAccepted = jevSummary != null
                            && "valid_recommendation".equals(jevSummary.optString("status", ""))
                            && jevSummary.optDouble("confidence", -1.0) >= JevShadow.LOW_CONFIDENCE_THRESHOLD
                            && jevStatus != TreeActionVerifier.Status.UNKNOWN;
                    if (jevAccepted && (jevStatus == TreeActionVerifier.Status.SUCCESS
                            || jevStatus == TreeActionVerifier.Status.FAILURE)) {
                        decision = modelVerificationDecision(jevStatus, "jev", jevSummary,
                                jevSummary.optString("verification_status", ""));
                        source = "jev";
                    } else if (jevAccepted && jevStatus == TreeActionVerifier.Status.PENDING) {
                        while (waits < TreeActionVerifier.MAX_WAIT_ATTEMPTS) {
                            if (hasControlRequest()) throw new TaskStopped();
                            Thread.sleep(TreeActionVerifier.WAIT_INTERVAL_MILLIS);
                            if (hasControlRequest()) throw new TaskStopped();
                            after = observe(runTaskId, false);
                            persistObservation(runTaskId, after);
                            afterShot = captureScreenshotForTreeVerification(runTaskId, after, "AFTER");
                            waits++;
                            rule = TreeActionVerifier.verify(before, after, action, beforeShot, afterShot);
                            appendRuleAttempt(ruleAttempts, rule, after, afterShot, "jev_pending_wait_" + waits);
                            if (rule.status == TreeActionVerifier.Status.SUCCESS
                                    || rule.status == TreeActionVerifier.Status.FAILURE) break;
                        }
                        if (rule.status == TreeActionVerifier.Status.SUCCESS
                                || rule.status == TreeActionVerifier.Status.FAILURE) {
                            decision = rule;
                            source = "tree_rule_after_jev_pending";
                        }
                    }
                    JSONObject savedJevUnknown = new JSONObject()
                            .put("source", "jev")
                            .put("status", jevSummary == null ? "unavailable" : jevSummary.optString("status", ""))
                            .put("verification_status", jevSummary == null ? "" : jevSummary.optString("verification_status", ""))
                            .put("confidence", jevSummary == null ? JSONObject.NULL
                                    : jevSummary.opt("confidence"));
                    if (decision.status != TreeActionVerifier.Status.SUCCESS
                            && decision.status != TreeActionVerifier.Status.FAILURE) {
                        decision = TreeActionVerifier.decision(
                                jevAccepted && jevStatus == TreeActionVerifier.Status.PENDING
                                        && rule.status == TreeActionVerifier.Status.PENDING
                                        ? TreeActionVerifier.Status.PENDING : TreeActionVerifier.Status.UNKNOWN,
                                jevAccepted && jevStatus == TreeActionVerifier.Status.PENDING
                                        ? "jev_pending_after_bounded_wait" : "jev_did_not_decide",
                                savedJevUnknown);
                        source = "jev";
                    }
                }

                if (decision.status != TreeActionVerifier.Status.SUCCESS
                        && decision.status != TreeActionVerifier.Status.FAILURE) {
                    if (!TreeActionVerifier.hasImage(beforeShot) || !TreeActionVerifier.hasImage(afterShot)) {
                        decision = TreeActionVerifier.decision(TreeActionVerifier.Status.UNKNOWN,
                                "visual_fallback_requires_real_before_and_after_screenshots",
                                new JSONObject().put("before_screenshot_available", TreeActionVerifier.hasImage(beforeShot))
                                        .put("after_screenshot_available", TreeActionVerifier.hasImage(afterShot)));
                        source = "screenshot_unavailable";
                    } else {
                        if (!persistTreeVerification(runTaskId, action, decision, source, waits,
                                ruleAttempts, before, after, beforeShot, afterShot, jevSummary)) {
                            throw new TaskFailure("action_verification_not_saved",
                                    "树与 Jev 核验结果无法保存在本地；设备动作待核对");
                        }
                        String response = requestRole(profile, runTaskId, "action_reflector", step,
                                roles.treeReflectorPrompt(),
                                new JSONArray().put(beforeShot).put(afterShot));
                        String[] reflected = roles.parseTreeReflection(response);
                        TreeActionVerifier.Status visualStatus = TreeActionVerifier.parseModelStatus(reflected[0]);
                        JSONObject visualEvidence = new JSONObject().put("reason", reflected[1]);
                        decision = TreeActionVerifier.decision(visualStatus,
                                reflected[1].isEmpty() ? "visual_reflector_no_reason" : "visual_reflector_decision",
                                visualEvidence);
                        source = "vlm_reflector";
                        if (visualStatus == TreeActionVerifier.Status.PENDING) {
                            while (waits < TreeActionVerifier.MAX_WAIT_ATTEMPTS) {
                                if (hasControlRequest()) throw new TaskStopped();
                                Thread.sleep(TreeActionVerifier.WAIT_INTERVAL_MILLIS);
                                if (hasControlRequest()) throw new TaskStopped();
                                after = observe(runTaskId, false);
                                persistObservation(runTaskId, after);
                                afterShot = captureScreenshotForTreeVerification(runTaskId, after, "AFTER");
                                waits++;
                                rule = TreeActionVerifier.verify(before, after, action, beforeShot, afterShot);
                                appendRuleAttempt(ruleAttempts, rule, after, afterShot, "visual_pending_wait_" + waits);
                                if (rule.status == TreeActionVerifier.Status.SUCCESS
                                        || rule.status == TreeActionVerifier.Status.FAILURE) {
                                    decision = rule;
                                    source = "tree_rule_after_visual_pending";
                                    break;
                                }
                            }
                            if (decision.status == TreeActionVerifier.Status.PENDING
                                    && rule.status != TreeActionVerifier.Status.PENDING) {
                                decision = TreeActionVerifier.decision(TreeActionVerifier.Status.UNKNOWN,
                                        "visual_pending_not_resolved_after_bounded_wait",
                                        new JSONObject().put("visual_reason", reflected[1])
                                                .put("final_tree_status", rule.status.name()));
                            }
                        }
                    }
                }
            }
        }

        if (!persistTreeVerification(runTaskId, action, decision, source, waits,
                ruleAttempts, before, after, beforeShot, afterShot, jevSummary)) {
            throw new TaskFailure("action_verification_not_saved",
                    "四态动作核验结果无法保存在本地；设备动作待核对");
        }
        return decision;
    }

    private JSONObject runTreeVerificationWithJev(JSONObject task,
            String runTaskId, int step, JSONObject action, JSONObject before, JSONObject after,
            TreeActionVerifier.Result rule)
            throws TaskFailure, TaskStopped {
        if (hasControlRequest()) throw new TaskStopped();
        AtomicBoolean cancelled = new AtomicBoolean(false);
        activeRequestCancellation = cancelled;
        JSONObject report;
        try {
            report = JevShadow.runTreeVerificationAttempt(this, runTaskId, step,
                    task.optString("goal", ""), action, before, after, rule, cancelled::get);
        } catch (JSONException exception) {
            throw new TaskFailure("jev_verification_report_failed",
                    "Jev 树核验报告无法保存在本机；任务已暂停");
        } finally {
            activeRequestCancellation = null;
        }
        if (hasControlRequest()) throw new TaskStopped();
        return report;
    }

    private static TreeActionVerifier.Result modelVerificationDecision(TreeActionVerifier.Status status,
            String source, JSONObject report, String label) {
        JSONObject evidence = new JSONObject();
        try {
            evidence.put("classifier", source)
                    .put("label", label)
                    .put("confidence", report.opt("confidence"))
                    .put("attempt_status", report.optString("status", ""));
        } catch (JSONException ignored) {
            // Fixed metadata fields cannot fail under normal operation.
        }
        return TreeActionVerifier.decision(status, source + "_classification", evidence);
    }

    private boolean persistTreeVerification(String runTaskId, JSONObject action,
            TreeActionVerifier.Result result, String source, int waits, JSONArray ruleAttempts,
            JSONObject before, JSONObject after, JSONObject beforeShot, JSONObject afterShot,
            JSONObject jevSummary) throws TaskFailure {
        try {
            JSONObject record = result.asJson(source)
                    .put("policy_id", TreeActionVerifier.POLICY_ID)
                    .put("policy_sha256", TreeActionVerifier.POLICY_SHA256)
                    .put("wait_attempts", waits)
                    .put("rule_attempts", new JSONArray(ruleAttempts.toString()))
                    .put("before_observation_id", before.optString("observation_id", ""))
                    .put("after_observation_id", after.optString("observation_id", ""))
                    .put("before_screenshot", screenshotEvidence(beforeShot))
                    .put("after_screenshot", screenshotEvidence(afterShot));
            if (jevSummary != null) {
                record.put("jev", new JSONObject()
                        .put("status", jevSummary.optString("status", ""))
                        .put("verification_status", jevSummary.optString("verification_status", ""))
                        .put("confidence", jevSummary.opt("confidence"))
                        .put("request_sent", jevSummary.optBoolean("request_sent", false)));
            }
            if (!LocalTaskStore.recordActionVerification(this, runTaskId,
                    action.optString("action_id", ""), record)) {
                return false;
            }
            return true;
        } catch (JSONException exception) {
            return false;
        }
    }

    private static JSONObject screenshotEvidence(JSONObject screenshot) throws JSONException {
        if (screenshot == null) {
            return new JSONObject().put("status", "unavailable").put("reason", "screenshot_missing");
        }
        boolean available = TreeActionVerifier.hasImage(screenshot);
        return new JSONObject()
                .put("status", available ? "captured" : "unavailable")
                .put("screenshot_id", screenshot.optString("screenshot_id", ""))
                .put("observation_id", screenshot.optString("observation_id", ""))
                .put("reason", screenshot.optString("missing_reason", ""));
    }

    private static void appendRuleAttempt(JSONArray attempts, TreeActionVerifier.Result result,
            JSONObject observation, JSONObject screenshot, String point) {
        try {
            attempts.put(new JSONObject()
                    .put("point", point)
                    .put("status", result.status.name())
                    .put("reason", result.reason)
                    .put("observation_id", observation.optString("observation_id", ""))
                    .put("screenshot_available", TreeActionVerifier.hasImage(screenshot)));
        } catch (JSONException ignored) {
            // The fixed rule trace uses safe scalar values.
        }
    }

    private static String legacyReflectionOutcome(TreeActionVerifier.Status status) {
        if (status == TreeActionVerifier.Status.SUCCESS) return "A";
        if (status == TreeActionVerifier.Status.FAILURE) return "B";
        return "C";
    }

    private void persistObservation(String runTaskId, JSONObject observation) throws TaskFailure {
        try {
            LocalTaskStore.saveObservation(this, runTaskId, observation);
        } catch (IOException exception) {
            throw new TaskFailure("observation_evidence_not_saved", "本地观察证据无法保存；任务已暂停");
        }
    }

    private String requestRole(ModelProfileStore.Profile profile, String runTaskId, String role,
            int step, String prompt, JSONArray screenshots)
            throws TaskFailure, TaskStopped, InterruptedException {
        if (hasControlRequest()) {
            throw new TaskStopped();
        }
        LocalTaskStore.Reservation reservation = LocalTaskStore.reserveRequest(this, runTaskId, role, step);
        if (!reservation.allowed) {
            throw new TaskFailure("budget_gate_" + reservation.reason,
                    "请求预算已到上限（每任务 ¥1、最多 25 次）；任务已暂停");
        }
        JSONArray screenshotIds = new JSONArray();
        for (int i = 0; screenshots != null && i < screenshots.length(); i++) {
            JSONObject screenshot = screenshots.optJSONObject(i);
            screenshotIds.put(screenshot == null ? "" : screenshot.optString("screenshot_id", ""));
        }
        if (!LocalTaskStore.recordRequestImages(this, runTaskId, reservation.attemptIndex, screenshotIds)) {
            LocalTaskStore.releaseUnsentRequest(this, runTaskId, reservation.attemptIndex,
                    "request_evidence_not_saved");
            throw new TaskFailure("request_evidence_not_saved", "请求证据无法保存，未发送模型请求");
        }
        if (hasControlRequest()) {
            LocalTaskStore.releaseUnsentRequest(this, runTaskId, reservation.attemptIndex,
                    "controlled_before_request");
            throw new TaskStopped();
        }

        final JSONArray messages;
        try {
            JSONObject message = new JSONObject()
                    .put("role", "user")
                    .put("content", new MobileAgentVlmRoles().userContent(prompt, screenshots));
            messages = new JSONArray().put(message);
        } catch (JSONException exception) {
            LocalTaskStore.releaseUnsentRequest(this, runTaskId, reservation.attemptIndex,
                    "invalid_request_content");
            throw new TaskFailure("invalid_request_content", "本地模型请求内容无法构建，未发送请求");
        }

        setStatus("正在向 VLM 发送 " + role + " 请求（费用已预留）");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        activeRequestCancellation = cancelled;
        VlmApiClient.Response response;
        try {
            response = VlmApiClient.complete(profile.endpoint, profile.model, profile.apiKey,
                    messages, LocalTaskStore.MAX_OUTPUT_TOKENS, cancelled::get);
        } catch (VlmApiClient.VlmApiException exception) {
            if (exception.getCategory() == VlmApiClient.ErrorCategory.INVALID_ARGUMENT) {
                LocalTaskStore.releaseUnsentRequest(this, runTaskId, reservation.attemptIndex,
                        exception.getCategory().name().toLowerCase(java.util.Locale.ROOT));
                throw new TaskFailure("vlm_endpoint_invalid", "模型 endpoint 或请求参数无效；未执行设备动作");
            }
            LocalTaskStore.Settlement settlement = LocalTaskStore.finishRequest(this, runTaskId,
                    reservation.attemptIndex, null, null, null,
                    exception.getHttpStatus(), exception.getCategory().name().toLowerCase(java.util.Locale.ROOT));
            if (!settlement.saved) {
                throw new TaskFailure("usage_ledger_not_saved", "模型请求结束但本地用量记录失败；任务已暂停");
            }
            if (hasControlRequest()) {
                throw new TaskStopped();
            }
            throw new TaskFailure("usage_unknown_" + exception.getCategory().name().toLowerCase(java.util.Locale.ROOT),
                    "模型请求失败或 usage 缺失；已保留本次预留费用，停止重试并暂停任务（HTTP "
                            + exception.getHttpStatus() + "）");
        } finally {
            activeRequestCancellation = null;
        }

        LocalTaskStore.Settlement settlement = LocalTaskStore.finishRequest(this, runTaskId,
                reservation.attemptIndex, response.promptTokens, response.completionTokens,
                response.totalTokens, 200, null);
        if (!settlement.saved) {
            throw new TaskFailure("usage_ledger_not_saved", "模型响应已返回但本地用量记录失败；任务已暂停");
        }
        if (settlement.budgetExceeded) {
            throw new TaskFailure("actual_usage_budget_exceeded", "实际 usage 已超过预算，已停止后续模型请求");
        }
        if (!settlement.usageKnown) {
            throw new TaskFailure("usage_missing", "模型响应缺少完整 usage；本次预留费用保留，停止重试并暂停任务");
        }
        if (hasControlRequest()) {
            throw new TaskStopped();
        }
        return response.content;
    }

    /** Jev can only append a report; this path has no reference to the action dispatcher. */
    private void runJevShadowForDecision(JSONObject task, String runTaskId, int step,
            JSONObject observation, JSONObject vlmAction) throws TaskFailure, TaskStopped {
        if (task == null || !task.optBoolean("jev_shadow_enabled", false)) {
            return;
        }
        if (hasControlRequest()) {
            throw new TaskStopped();
        }
        setStatus("正在记录 Jev 影子建议；设备动作仍由 VLM 控制");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        activeRequestCancellation = cancelled;
        JSONObject attempt;
        try {
            attempt = JevShadow.runTaskAttempt(this, runTaskId, step,
                    task.optString("goal", ""), observation, vlmAction, cancelled::get);
        } catch (JSONException exception) {
            throw new TaskFailure("jev_shadow_report_failed", "Jev 影子报告无法保存在本机；任务已暂停");
        } finally {
            activeRequestCancellation = null;
        }
        if (hasControlRequest()) {
            throw new TaskStopped();
        }
        if ("budget_denied".equals(attempt.optString("status", ""))) {
            JSONObject error = attempt.optJSONObject("error");
            String reason = error == null ? "budget_gate" : error.optString("code", "budget_gate");
            throw new TaskFailure("jev_shadow_" + reason,
                    "Jev 影子预算预留失败；停止本步后续模型与设备请求");
        }
    }

    private JevShadow.ControlledAttempt runJevControlledForDecision(JSONObject task,
            String runTaskId, int step, JSONObject observation, JSONObject vlmAction)
            throws TaskFailure, TaskStopped {
        if (task == null || !task.optBoolean("jev_selection_enabled", false)) return null;
        if (hasControlRequest()) throw new TaskStopped();
        setStatus("正在核对 VLM 动作的候选覆盖并运行 Jev 选择");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        activeRequestCancellation = cancelled;
        JevShadow.ControlledAttempt attempt;
        try {
            attempt = JevShadow.runControlledTaskAttempt(this, runTaskId, step,
                    task.optString("goal", ""), observation, vlmAction, cancelled::get);
        } catch (JSONException exception) {
            throw new TaskFailure("jev_control_report_failed", "Jev 选择报告无法保存在本机；任务已暂停");
        } finally {
            activeRequestCancellation = null;
        }
        if (hasControlRequest()) {
            try {
                LocalTaskStore.annotateJevSelectionAttempt(this, runTaskId, step,
                        new JSONObject().put("dispatch_status", "cancelled_before_action")
                                .put("action_dispatched", false));
            } catch (JSONException ignored) {
                // The task is already stopping; the action journal remains authoritative.
            }
            throw new TaskStopped();
        }
        if ("budget_denied".equals(attempt.report.optString("status", ""))) {
            JSONObject error = attempt.report.optJSONObject("error");
            String reason = error == null ? "budget_gate" : error.optString("code", "budget_gate");
            throw new TaskFailure("jev_control_" + reason,
                    "Jev 预算预留不足；停止本步后续模型与设备请求");
        }
        return attempt;
    }

    private boolean annotateJevDispatch(String taskId, int step, JSONObject action,
            Boolean dispatched, String status) {
        try {
            JSONObject annotations = new JSONObject()
                    .put("dispatch_status", status)
                    .put("action_id", action.optString("action_id", ""))
                    .put("action_source", action.optString("source", ""));
            annotations.put("action_dispatched", dispatched == null ? JSONObject.NULL : dispatched);
            return LocalTaskStore.annotateJevSelectionAttempt(this, taskId, step, annotations);
        } catch (JSONException exception) {
            return false;
        }
    }

    private ActionResult performAction(JSONObject action, ActionExecutionGate.Token token)
            throws TaskFailure, TaskStopped, InterruptedException {
        return await(callback -> ObservationAccessibilityService.executeLocalAction(action, token,
                new ObservationAccessibilityService.ActionCallback() {
                    @Override
                    public void onSuccess() {
                        callback.success(ActionResult.success());
                    }

                    @Override
                    public void onError(String code, String message) {
                        callback.success(ActionResult.failure(code, message));
                    }
                }), "设备动作未返回结果");
    }

    private <T> T await(AsyncStart<T> start, String timeoutMessage)
            throws TaskFailure, TaskStopped, InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<String> errorCode = new AtomicReference<>();
        AtomicReference<String> errorMessage = new AtomicReference<>();
        start.start(new AsyncCallback<T>() {
            @Override
            public void success(T value) {
                result.set(value);
                latch.countDown();
            }

            @Override
            public void failure(String code, String message) {
                errorCode.set(code == null ? "local_operation_failed" : code);
                errorMessage.set(message);
                latch.countDown();
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CALLBACK_TIMEOUT_SECONDS);
        while (!latch.await(200L, TimeUnit.MILLISECONDS)) {
            if (hasControlRequest()) {
                throw new TaskStopped();
            }
            if (System.nanoTime() >= deadline) {
                throw new TaskFailure("local_callback_timeout", timeoutMessage + "；任务已暂停");
            }
        }
        if (errorCode.get() != null) {
            throw new TaskFailure(errorCode.get(), timeoutMessage + "：" + safeLocalError(errorCode.get()));
        }
        T value = result.get();
        if (value == null) {
            throw new TaskFailure("local_callback_empty", timeoutMessage + "；任务已暂停");
        }
        return value;
    }

    private void finishStep(String runTaskId, MobileAgentVlmRoles roles, int nextStep) throws TaskFailure {
        if (!LocalTaskStore.saveHistory(this, runTaskId, roles.toJson())
                || !LocalTaskStore.setStep(this, runTaskId, nextStep)) {
            throw new TaskFailure("task_history_not_saved", "本地任务历史无法保存；已停止后续操作");
        }
    }

    private static boolean errorEscalationRequired(MobileAgentVlmRoles roles) {
        int size = roles.actionOutcomes.size();
        if (size < 2) {
            return false;
        }
        return !"A".equals(roles.actionOutcomes.get(size - 1))
                && !"A".equals(roles.actionOutcomes.get(size - 2));
    }

    private static boolean shouldSkipManager(MobileAgentVlmRoles roles) {
        if (roles.errorFlagPlan || roles.actionHistory.isEmpty()) {
            return false;
        }
        return roles.actionHistory.get(roles.actionHistory.size() - 1).contains("\"action\":\"invalid\"");
    }

    private static void recordTerminal(MobileAgentVlmRoles roles, String reason) {
        try {
            JSONObject terminal = new JSONObject().put("action", "done").put("reason", reason);
            roles.actionHistory.add(terminal.toString());
            roles.summaryHistory.add(reason);
            roles.actionOutcomes.add("A");
            roles.errorDescriptions.add("None");
        } catch (JSONException ignored) {
            // These fixed JSON fields cannot fail.
        }
    }

    private boolean hasControlRequest() {
        return !controlCommand.isEmpty();
    }

    private void setStatus(String message) {
        if (!taskId.isEmpty()) {
            LocalTaskStore.updateRuntimeStatus(this, taskId, message);
        }
        refreshNotification();
    }

    private void promoteForeground() {
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification,
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
        foregroundStarted = true;
    }

    private void refreshNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification());
        }
    }

    private Notification buildNotification() {
        JSONObject task = LocalTaskStore.task(this, taskId);
        String state = task == null ? "" : task.optString("state", "");
        String message = task == null ? "Standalone VLM" : task.optString("runtime_status", "本地任务运行中");
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentTitle("Jev 本地任务")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(mainPendingIntent())
                .setOngoing("RUNNING".equals(state) || "ARMED".equals(state) || "PAUSING".equals(state))
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE);
        if ("ARMED".equals(state)) {
            builder.addAction(0, "开始任务", servicePendingIntent(ACTION_START, taskId, 1));
            builder.addAction(0, "取消", servicePendingIntent(ACTION_CANCEL, taskId, 2));
        } else if ("RUNNING".equals(state) || "PAUSING".equals(state)) {
            builder.addAction(0, "暂停", servicePendingIntent(ACTION_PAUSE, taskId, 3));
            builder.addAction(0, "取消", servicePendingIntent(ACTION_CANCEL, taskId, 4));
        } else if ("PAUSED".equals(state) || "NEEDS_REVIEW".equals(state)) {
            builder.addAction(0, "结束此任务", servicePendingIntent(ACTION_CANCEL, taskId, 5));
        }
        return builder.build();
    }

    private PendingIntent mainPendingIntent() {
        Intent intent = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 10, intent, pendingIntentFlags());
    }

    private PendingIntent servicePendingIntent(String action, String runTaskId, int requestCode) {
        Intent intent = new Intent(this, LocalVlmTaskService.class)
                .setAction(action)
                .putExtra(EXTRA_TASK_ID, runTaskId);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return PendingIntent.getForegroundService(this, requestCode, intent, pendingIntentFlags());
        }
        return PendingIntent.getService(this, requestCode, intent, pendingIntentFlags());
    }

    private static int pendingIntentFlags() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.createNotificationChannel(new NotificationChannel(CHANNEL_ID,
                        "Standalone mobile-agent task", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private void stopAfterTerminal() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.cancel(NOTIFICATION_ID);
        }
        if (Build.VERSION.SDK_INT >= 24) {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } else {
            stopForeground(true);
        }
        stopSelf();
    }

    private static String safeLocalError(String code) {
        if ("permission_unavailable".equals(code)) {
            return "无障碍权限不可用";
        }
        if ("screenshot_api_unavailable".equals(code)) {
            return "Android 版本不支持本地截图";
        }
        return "本地操作失败";
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // The foreground service and its notification remain the user-visible
        // control surface while the target app is in front.
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        AtomicBoolean cancellation = activeRequestCancellation;
        if (cancellation != null) {
            cancellation.set(true);
        }
        actionGate.invalidate();
        ObservationAccessibilityService.endLocalTaskCapture();
        JSONObject task = LocalTaskStore.task(this, taskId);
        if (task != null && "RUNNING".equals(task.optString("state", ""))) {
            boolean unresolvedAction = LocalTaskControlPolicy.hasUnresolvedDeviceAction(task);
            LocalTaskStore.updateState(this, taskId,
                    unresolvedAction ? "NEEDS_REVIEW" : "PAUSED",
                    unresolvedAction ? "runtime_interrupted_with_unresolved_device_action"
                            : "runtime_interrupted; manual review required");
        }
        taskLoopActive = false;
        loopRunning = false;
        taskExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private interface AsyncStart<T> {
        void start(AsyncCallback<T> callback);
    }

    private interface AsyncCallback<T> {
        void success(T value);

        void failure(String code, String message);
    }

    private static final class ActionResult {
        final boolean success;
        final String code;
        final String safeMessage;

        private ActionResult(boolean success, String code, String safeMessage) {
            this.success = success;
            this.code = code;
            this.safeMessage = safeMessage;
        }

        static ActionResult success() {
            return new ActionResult(true, "", "");
        }

        static ActionResult failure(String code, String message) {
            return new ActionResult(false, code == null ? "action_failed" : code,
                    safeLocalError(code == null ? "action_failed" : code));
        }

        JSONObject toJson() {
            try {
                return new JSONObject().put("success", success).put("error_code", success ? JSONObject.NULL : code)
                        .put("message", success ? JSONObject.NULL : safeMessage);
            } catch (JSONException exception) {
                return new JSONObject();
            }
        }
    }

    private static final class TaskFailure extends Exception {
        final String code;
        final String safeMessage;

        TaskFailure(String code, String safeMessage) {
            this.code = code;
            this.safeMessage = safeMessage;
        }
    }

    private static final class TaskStopped extends Exception {
    }
}
