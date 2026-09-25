package com.jev.mobileagent;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

/** Deterministic local acceptance page with one semantic input and one visual-only target. */
public final class ControlledPageActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 2603;
    private static final String INPUT_GOAL = "在中文输入框中输入“独立手机测试成功”";
    private static final String VISUAL_GOAL = "点击受控页面中蓝色的视觉手势目标";
    static final String EXTRA_DEBUG_TREE_VERIFICATION_FIXTURE = "debug_tree_verification_fixture";
    static final String EXTRA_DEBUG_RECOVERY_FIXTURE = "debug_recovery_fixture";
    private static final String DEBUG_RECOVERY_PREFS = "debug_recovery_fixture";
    private static final String DEBUG_RECOVERY_INPUT_KEY = "target_input_value";
    private static final String DEBUG_LOADING_GOAL = "点击“延迟加载按钮”一次，等待页面显示“加载完成”，不要重复点击。";
    private static final String DEBUG_NO_EFFECT_GOAL = "点击“无效果按钮”一次，不要重复点击。";
    private static final String DEBUG_VISUAL_GOAL = "点击自绘区域蓝框中心";
    private static final float VISUAL_TARGET_MIN = 0.22f;
    private static final float VISUAL_TARGET_MAX = 0.78f;

    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private EditText goalInput;
    private EditText fixtureTextInput;
    private TextView taskStatus;
    private TextView inputStatus;
    private TextView visualStatus;
    private String taskId = "";
    private boolean debugFixturePage;
    private boolean debugRecoveryFixturePage;
    private boolean debugRecoveryInputPersistenceFailed;
    private boolean failAfterScreenshotArmed;
    private String debugRecoveryFaultPoint = "";
    private TextView fixtureStatus;
    private final Runnable refreshStatus = new Runnable() {
        @Override
        public void run() {
            refreshTaskStatus();
            statusHandler.postDelayed(this, 750L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (BuildConfig.DEBUG && getIntent().getBooleanExtra(EXTRA_DEBUG_RECOVERY_FIXTURE, false)) {
            debugFixturePage = true;
            debugRecoveryFixturePage = true;
            getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
            buildDebugRecoveryFixture();
            return;
        }
        if (BuildConfig.DEBUG && getIntent().getBooleanExtra(EXTRA_DEBUG_TREE_VERIFICATION_FIXTURE, false)) {
            debugFixturePage = true;
            buildDebugVerificationFixture();
            return;
        }
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        TextView title = label("受控测试页面", 22, Color.rgb(35, 50, 65));
        title.setContentDescription("Controlled page heading");
        content.addView(title, params());
        content.addView(label("当前页面由 Accessibility tree 和截图本地观察。", 14, Color.DKGRAY), params());

        TextView target = label("可访问文本目标", 17, Color.DKGRAY);
        target.setContentDescription("The tree must contain this visible text");
        content.addView(target, params());

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("请输入中文");
        input.setContentDescription("中文输入框");
        content.addView(input, params());

        inputStatus = label("Controlled input state: empty", 15, Color.DKGRAY);
        inputStatus.setContentDescription("Controlled input state empty");
        content.addView(inputStatus, params());
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String value = s == null ? "" : s.toString();
                String suffix = value.isEmpty() ? "empty" : value;
                inputStatus.setText("Controlled input state: " + suffix);
                inputStatus.setContentDescription("Controlled input state " + suffix);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        TextView visualHeading = label("Visual gesture surface (semantic tree intentionally incomplete)",
                landscape ? 12 : 15, Color.DKGRAY);
        visualHeading.setContentDescription("Visual gesture surface heading");
        VisualGestureSurface surface = new VisualGestureSurface();
        surface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        visualStatus = label("Visual gesture state: ready", landscape ? 12 : 15, Color.DKGRAY);
        visualStatus.setContentDescription("Visual gesture state ready");
        LinearLayout visualPanel = new LinearLayout(this);
        visualPanel.setOrientation(LinearLayout.VERTICAL);
        visualPanel.setGravity(Gravity.CENTER_HORIZONTAL);
        visualPanel.addView(visualHeading, params());
        visualPanel.addView(surface, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(landscape ? 110 : 150)));
        visualPanel.addView(visualStatus, params());
        content.addView(visualPanel, params());

        goalInput = new EditText(this);
        goalInput.setHint("测试目标");
        goalInput.setMinLines(2);
        goalInput.setGravity(Gravity.TOP | Gravity.START);
        goalInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        String savedGoal = getSharedPreferences(BridgeConfig.PREFS, MODE_PRIVATE).getString("task_goal", "");
        goalInput.setText(savedGoal.isEmpty() ? INPUT_GOAL : savedGoal);
        goalInput.setContentDescription("Local VLM task goal");
        content.addView(goalInput, params());

        LinearLayout samples = new LinearLayout(this);
        samples.setOrientation(LinearLayout.HORIZONTAL);
        Button inputSample = button("中文输入目标");
        inputSample.setOnClickListener(view -> goalInput.setText(INPUT_GOAL));
        Button visualSample = button("视觉手势目标");
        visualSample.setOnClickListener(view -> goalInput.setText(VISUAL_GOAL));
        samples.addView(inputSample, rowParams());
        samples.addView(visualSample, rowParams());
        content.addView(samples, params());

        Button openSettings = button("VLM / Jev 凭据设置");
        openSettings.setOnClickListener(view -> startActivity(new Intent(this, MainActivity.class)));
        content.addView(openSettings, params());

        LinearLayout taskControls = new LinearLayout(this);
        taskControls.setOrientation(LinearLayout.HORIZONTAL);
        Button start = button("开始本地 VLM 任务");
        start.setContentDescription("Start local VLM task");
        start.setOnClickListener(view -> startLocalTask());
        Button pause = button("暂停");
        pause.setOnClickListener(view -> controlTask(false));
        Button cancel = button("取消");
        cancel.setOnClickListener(view -> controlTask(true));
        taskControls.addView(start, rowParams());
        taskControls.addView(pause, rowParams());
        taskControls.addView(cancel, rowParams());
        root.addView(taskControls, params());

        taskStatus = label("本地任务：无", 13, Color.DKGRAY);
        taskStatus.setLines(3);
        taskStatus.setTextIsSelectable(true);
        taskStatus.setContentDescription("Local VLM task status");
        root.addView(taskStatus, params());

        setContentView(root);
        refreshTaskStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        JSONObject active = LocalTaskStore.activeTask(this);
        if (active != null && "RUNNING".equals(active.optString("state", ""))
                && !LocalVlmTaskService.isTaskLoopActive()) {
            LocalTaskStore.markInterrupted(this);
            active = LocalTaskStore.activeTask(this);
        }
        if (active != null && ("PAUSED".equals(active.optString("state", ""))
                || "NEEDS_REVIEW".equals(active.optString("state", "")))
                && !LocalVlmTaskService.isForegroundServiceActive()) {
            LocalVlmTaskService.showRecoveryControls(this, active.optString("task_id", ""));
        }
        statusHandler.removeCallbacks(refreshStatus);
        statusHandler.post(refreshStatus);
    }

    @Override
    protected void onPause() {
        statusHandler.removeCallbacks(refreshStatus);
        super.onPause();
    }

    private void startLocalTask() {
        String goal = goalInput.getText().toString().trim();
        if (goal.isEmpty()) {
            taskStatus.setText("本地任务：请先填写目标");
            return;
        }
        if (debugRecoveryFixturePage && debugRecoveryInputPersistenceFailed) {
            taskStatus.setText("本地任务：目标输入未能同步保存；先确认夹具状态后再开始");
            return;
        }
        ModelProfileStore.Profile profile = ModelProfileStore.load(this, ModelProfileStore.Type.VLM);
        if (!profile.isConfigured()) {
            taskStatus.setText("本地任务：请先保存完整 VLM profile");
            return;
        }
        if (!LocalVlmBudgetPolicy.isReviewedProfile(profile.provider, profile.model)) {
            taskStatus.setText("本地任务：此 provider/model 没有已审查费率，无法执行 ¥1 预算门控");
            return;
        }
        if (!ObservationAccessibilityService.isEnabled(this)) {
            taskStatus.setText("本地任务：请先启用 Jev local task accessibility");
            return;
        }
        if (!ensureNotificationPermission()) {
            taskStatus.setText("本地任务：请允许通知权限后再次启动，运行中通知提供暂停和取消按钮");
            return;
        }
        getSharedPreferences(BridgeConfig.PREFS, MODE_PRIVATE).edit().putString("task_goal", goal).apply();
        JSONObject task = LocalTaskStore.create(this, goal, profile.provider, profile.model, true);
        if (task == null) {
            taskStatus.setText("本地任务：已有活动或暂停任务；请先在通知中取消旧任务");
            return;
        }
        taskId = task.optString("task_id", "");
        String debugRecoveryFaultActionKind = debugRecoveryFixturePage
                && (LocalTaskStore.DEBUG_FAULT_AFTER_SIDE_EFFECT.equals(debugRecoveryFaultPoint)
                || LocalTaskStore.DEBUG_FAULT_AFTER_RECEIPT.equals(debugRecoveryFaultPoint))
                ? "set_text" : "";
        if (BuildConfig.DEBUG && debugFixturePage && !debugRecoveryFaultPoint.isEmpty()
                && !LocalTaskStore.armDebugRecoveryFault(this, taskId, debugRecoveryFaultPoint,
                        debugRecoveryFaultActionKind)) {
            LocalTaskStore.updateState(this, taskId, "PAUSED", "debug_recovery_fault_could_not_be_armed");
            taskStatus.setText("本地任务：调试中断点未能可靠预置，未启动");
            return;
        }
        if (debugFixturePage && fixtureTextInput != null) fixtureTextInput.requestFocus();
        if (debugFixturePage && failAfterScreenshotArmed) {
            DebugTreeVerificationFixtures.armAfterScreenshotFailure(this, taskId);
            failAfterScreenshotArmed = false;
        }
        try {
            LocalVlmTaskService.startTask(this, taskId);
            taskStatus.setText("本地任务：已启动；可用此页或通知栏随时暂停/取消");
        } catch (RuntimeException exception) {
            LocalTaskStore.updateState(this, taskId, "PAUSED", "foreground_service_start_failed");
            DebugTreeVerificationFixtures.clearForTask(this, taskId);
            taskStatus.setText("本地任务：前台服务无法启动，任务已安全暂停");
        }
    }

    private void controlTask(boolean cancel) {
        JSONObject active = LocalTaskStore.activeTask(this);
        String activeId = active == null ? taskId : active.optString("task_id", "");
        if (activeId.isEmpty()) {
            taskStatus.setText("本地任务：没有活动任务");
            return;
        }
        try {
            if (cancel) {
                LocalVlmTaskService.cancelTask(this, activeId);
            } else {
                LocalVlmTaskService.pauseTask(this, activeId);
            }
        } catch (RuntimeException exception) {
            taskStatus.setText("本地任务控制未能送达；通知栏仍提供暂停/取消入口");
        }
    }

    private boolean ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return true;
        }
        requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, NOTIFICATION_PERMISSION_REQUEST);
        return false;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == NOTIFICATION_PERMISSION_REQUEST) {
            taskStatus.setText(grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED
                    ? "通知权限已开启，请再次启动任务。"
                    : "运行中通知控制需要通知权限；请在系统设置中开启。");
        }
    }

    private void refreshTaskStatus() {
        JSONObject active = LocalTaskStore.activeTask(this);
        if (active == null) {
            if (taskStatus != null) {
                JSONObject latest = LocalTaskStore.latestTask(this);
                String state = latest == null ? "无任务记录" : latest.optString("state", "UNKNOWN");
                taskStatus.setText("本地任务：" + state + " · " + globalBudgetText());
            }
            return;
        }
        taskId = active.optString("task_id", "");
        String message = "本地任务：" + active.optString("state", "UNKNOWN");
        LocalTaskStore.BudgetSnapshot budget = LocalTaskStore.budgetSnapshot(this, taskId);
        message += String.format(java.util.Locale.ROOT, " · 本任务 ¥%.4f / ¥1，全局 ¥%.4f / ¥%.0f",
                budget.taskAccountedCny, budget.globalAccountedCny, budget.globalBudgetCny);
        if (taskStatus != null) {
            taskStatus.setText(message);
        }
    }

    private String globalBudgetText() {
        LocalTaskStore.BudgetSnapshot budget = LocalTaskStore.budgetSnapshot(this, "");
        return String.format(java.util.Locale.ROOT, "累计 ¥%.4f / ¥%.0f",
                budget.globalAccountedCny, budget.globalBudgetCny);
    }

    private void buildDebugVerificationFixture() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        TextView title = label("树核验受控夹具（仅 Debug）", 21, Color.rgb(35, 50, 65));
        title.setContentDescription("Tree verification controlled fixture debug only");
        content.addView(title, params());
        content.addView(label("每个测试动作由本地 VLM 发出；下方按钮只设置目标或一次性截图故障。", 13,
                Color.DKGRAY), params());

        fixtureStatus = label("夹具状态：就绪", 15, Color.DKGRAY);
        fixtureStatus.setContentDescription("Fixture state ready");
        content.addView(fixtureStatus, params());
        Button delayed = button("延迟加载按钮");
        delayed.setContentDescription("延迟加载按钮");
        delayed.setOnClickListener(view -> {
            delayed.setEnabled(false);
            setFixtureStatus("加载中");
            statusHandler.postDelayed(() -> {
                setFixtureStatus("加载完成");
                delayed.setEnabled(true);
            }, 1_200L);
        });
        content.addView(delayed, params());
        Button noEffect = button("无效果按钮");
        noEffect.setContentDescription("无效果按钮");
        noEffect.setOnClickListener(view -> {
            // Deliberately accepts the click without changing any visible page state.
        });
        content.addView(noEffect, params());

        EditText input = new EditText(this);
        fixtureTextInput = input;
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("请输入中文");
        input.setContentDescription("中文输入框");
        input.setShowSoftInputOnFocus(false);
        input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(7)});
        content.addView(input, params());
        TextView inputStatus = label("夹具输入状态：empty", 14, Color.DKGRAY);
        inputStatus.setContentDescription("Fixture input state empty");
        content.addView(inputStatus, params());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String value = s == null ? "" : s.toString();
                String status = value.isEmpty() ? "empty" : value;
                inputStatus.setText("夹具输入状态：" + status);
                inputStatus.setContentDescription("Fixture input state " + status);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        TextView visualHeading = label("视觉目标（语义树不包含蓝框）", 14, Color.DKGRAY);
        visualHeading.setContentDescription("Visual target heading; blue box is not in the tree");
        VisualGestureSurface surface = new VisualGestureSurface();
        surface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        visualStatus = label("Visual gesture state: ready", 14, Color.DKGRAY);
        visualStatus.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        visualStatus.setContentDescription(null);
        content.addView(visualHeading, params());
        content.addView(surface, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(140)));
        content.addView(visualStatus, params());

        goalInput = new EditText(this);
        goalInput.setHint("测试目标");
        goalInput.setMinLines(2);
        goalInput.setGravity(Gravity.TOP | Gravity.START);
        goalInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        goalInput.setContentDescription("Local VLM task goal");
        goalInput.setText(DEBUG_LOADING_GOAL);
        content.addView(goalInput, params());
        addFixtureGoalButton(content, "加载等待目标", DEBUG_LOADING_GOAL);
        addFixtureGoalButton(content, "无效果点击目标", DEBUG_NO_EFFECT_GOAL);
        addFixtureGoalButton(content, "相似文本目标", INPUT_GOAL);
        addFixtureGoalButton(content, "视觉目标", DEBUG_VISUAL_GOAL);

        Button failAfter = button("注入下一次 AFTER 截图失败（仅 Debug）");
        failAfter.setOnClickListener(view -> {
            failAfterScreenshotArmed = true;
            failAfter.setEnabled(false);
            failAfter.setText("已预置：下一次任务 AFTER 截图不可用");
            setFixtureStatus("截图故障已预置；BEFORE 截图仍正常采集");
        });
        content.addView(failAfter, params());
        addDebugRecoveryFaultControls(content);

        Button openSettings = button("VLM / Jev 凭据设置");
        openSettings.setOnClickListener(view -> startActivity(new Intent(this, MainActivity.class)));
        content.addView(openSettings, params());
        addTaskControls(root);
        setContentView(root);
        refreshTaskStatus();
    }

    private void buildDebugRecoveryFixture() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.WHITE);
        root.setFocusableInTouchMode(true);

        ScrollView scroll = new ScrollView(this);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(8), dp(16), dp(12));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        TextView title = label("任务27精确恢复中断夹具（仅 Debug）", 21, Color.rgb(35, 50, 65));
        title.setContentDescription("Standalone recovery fault fixture debug only");
        content.addView(title, params());
        content.addView(label("独立于树核验夹具。恢复只使用本地任务记录和通知入口；目标框保存其真实文字，不代表历史动作或核验已知。",
                13, Color.DKGRAY), params());

        fixtureStatus = label("夹具状态：就绪", 15, Color.DKGRAY);
        fixtureStatus.setContentDescription("Fixture state ready");
        content.addView(fixtureStatus, params());

        EditText input = new EditText(this);
        fixtureTextInput = input;
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("请输入中文");
        input.setContentDescription("中文输入框");
        input.setShowSoftInputOnFocus(false);
        String savedInput = getSharedPreferences(DEBUG_RECOVERY_PREFS, MODE_PRIVATE)
                .getString(DEBUG_RECOVERY_INPUT_KEY, "");
        input.setText(savedInput);
        content.addView(input, params());

        TextView inputStatus = label("夹具输入状态：" + (savedInput.isEmpty() ? "empty" : savedInput),
                14, Color.DKGRAY);
        inputStatus.setContentDescription("Fixture input state " + (savedInput.isEmpty() ? "empty" : savedInput));
        content.addView(inputStatus, params());
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                String value = s == null ? "" : s.toString();
                String status = value.isEmpty() ? "empty" : value;
                inputStatus.setText("夹具输入状态：" + status);
                inputStatus.setContentDescription("Fixture input state " + status);
                persistDebugRecoveryInput(value);
            }
            @Override public void afterTextChanged(Editable s) { }
        });

        Button resetInput = button("重置目标输入为空（新用例）");
        resetInput.setOnClickListener(view -> {
            if (LocalTaskStore.activeTask(this) != null) {
                setFixtureStatus("请先结束当前本地任务，再重置目标输入；现有恢复记录未改动");
                return;
            }
            input.setText("");
            persistDebugRecoveryInput("");
            input.clearFocus();
            root.requestFocus();
            if (!debugRecoveryInputPersistenceFailed) setFixtureStatus("目标输入已清空并同步保存；可开始新用例");
        });
        content.addView(resetInput, params());

        goalInput = new EditText(this);
        goalInput.setHint("测试目标");
        goalInput.setMinLines(2);
        goalInput.setGravity(Gravity.TOP | Gravity.START);
        goalInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        goalInput.setContentDescription("Local VLM task goal");
        goalInput.setShowSoftInputOnFocus(false);
        goalInput.setText(INPUT_GOAL);
        content.addView(goalInput, params());

        addDebugRecoveryFaultControls(content);
        Button openSettings = button("VLM / Jev 凭据设置");
        openSettings.setOnClickListener(view -> startActivity(new Intent(this, MainActivity.class)));
        content.addView(openSettings, params());
        addTaskControls(root);
        setContentView(root);
        root.requestFocus();
        refreshTaskStatus();
    }

    private void persistDebugRecoveryInput(String value) {
        boolean persisted = getSharedPreferences(DEBUG_RECOVERY_PREFS, MODE_PRIVATE).edit()
                .putString(DEBUG_RECOVERY_INPUT_KEY, value)
                .commit();
        debugRecoveryInputPersistenceFailed = !persisted;
        if (!persisted) {
            setFixtureStatus("目标输入同步保存失败；此轮不能作为可恢复目标状态的证据");
        } else if (debugRecoveryFixturePage) {
            setFixtureStatus("目标实际输入已同步保存；任务和动作历史仍需单独核对");
        }
    }

    private void addFixtureGoalButton(LinearLayout content, String title, String goal) {
        Button sample = button(title);
        sample.setOnClickListener(view -> {
            goalInput.setText(goal);
            if (fixtureTextInput != null) fixtureTextInput.requestFocus();
        });
        content.addView(sample, params());
    }

    private void addDebugRecoveryFaultControls(LinearLayout content) {
        if (!BuildConfig.DEBUG) return;
        content.addView(label("恢复中断点（仅 Debug，一次性，开始任务前选择）", 14, Color.DKGRAY), params());
        if (debugRecoveryFixturePage) {
            content.addView(label("派发前中断作用于首个动作；两个执行后中断只在真实 set_text 输入动作后触发，前序聚焦动作会继续。",
                    13, Color.DKGRAY), params());
        }
        String[] points = {
                LocalTaskStore.DEBUG_FAULT_BEFORE_DISPATCH,
                LocalTaskStore.DEBUG_FAULT_AFTER_SIDE_EFFECT,
                LocalTaskStore.DEBUG_FAULT_AFTER_RECEIPT
        };
        String[] titles = debugRecoveryFixturePage
                ? new String[] {
                    "预置：派发前中断",
                    "预置：真实文字输入后、回执前中断",
                    "预置：文字输入持久回执后、核验前中断"
                }
                : new String[] {
                    "预置：派发前中断",
                    "预置：设备动作后、回执前中断",
                    "预置：持久回执后、核验前中断"
                };
        Button[] controls = new Button[points.length];
        for (int i = 0; i < points.length; i++) {
            final int selected = i;
            controls[i] = button(titles[i]);
            controls[i].setContentDescription("Debug recovery fault " + points[i]);
            controls[i].setOnClickListener(view -> {
                debugRecoveryFaultPoint = points[selected];
                for (int j = 0; j < controls.length; j++) {
                    controls[j].setText((j == selected ? "✓ " : "") + titles[j]);
                }
            });
            content.addView(controls[i], params());
        }
    }

    private void addTaskControls(LinearLayout root) {
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button start = button("开始本地 VLM 任务");
        start.setContentDescription("Start local VLM task");
        start.setOnClickListener(view -> startLocalTask());
        Button pause = button("暂停");
        pause.setOnClickListener(view -> controlTask(false));
        Button cancel = button("取消");
        cancel.setOnClickListener(view -> controlTask(true));
        controls.addView(start, rowParams());
        controls.addView(pause, rowParams());
        controls.addView(cancel, rowParams());
        root.addView(controls, params());
        taskStatus = label("本地任务：无", 13, Color.DKGRAY);
        taskStatus.setLines(3);
        taskStatus.setTextIsSelectable(true);
        taskStatus.setContentDescription("Local VLM task status");
        root.addView(taskStatus, params());
    }

    private void setFixtureStatus(String value) {
        if (fixtureStatus == null) return;
        fixtureStatus.setText("夹具状态：" + value);
        fixtureStatus.setContentDescription("Fixture state " + value);
    }

    @Override
    public void onBackPressed() {
        // Keep the target visible so the VLM can observe action postconditions.
        setVisualStatus("system Back received");
    }

    private void setVisualStatus(String value) {
        if (visualStatus != null) {
            visualStatus.setText("Visual gesture state: " + value);
            visualStatus.setContentDescription("Visual gesture state " + value);
        }
    }

    private final class VisualGestureSurface extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float downX;
        private float downY;
        private long downAt;
        private boolean gestureStayedInsideTarget;

        VisualGestureSurface() {
            super(ControlledPageActivity.this);
            setBackgroundColor(Color.rgb(242, 246, 250));
            setContentDescription(null);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            paint.setColor(Color.rgb(45, 100, 165));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            float left = getWidth() * VISUAL_TARGET_MIN;
            float top = getHeight() * VISUAL_TARGET_MIN;
            float right = getWidth() * VISUAL_TARGET_MAX;
            float bottom = getHeight() * VISUAL_TARGET_MAX;
            canvas.drawRect(left, top, right, bottom, paint);
            paint.setStyle(Paint.Style.FILL);
            paint.setTextSize(dp(14));
            canvas.drawText("自绘目标 · 坐标/长按/滑动", left, top - dp(10), paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    downAt = System.currentTimeMillis();
                    gestureStayedInsideTarget = isInsideVisualTarget(
                            getWidth(), getHeight(), downX, downY);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!isInsideVisualTarget(getWidth(), getHeight(), event.getX(), event.getY())) {
                        gestureStayedInsideTarget = false;
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    long duration = System.currentTimeMillis() - downAt;
                    String completion = completedVisualGesture(getWidth(), getHeight(),
                            downX, downY, event.getX(), event.getY(), duration,
                            gestureStayedInsideTarget, dp(40));
                    gestureStayedInsideTarget = false;
                    if (completion == null) {
                        setVisualStatus("outside target");
                    } else {
                        setVisualStatus(completion);
                        performClick();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    gestureStayedInsideTarget = false;
                    return true;
                default:
                    return true;
            }
        }

        @Override
        public boolean performClick() {
            super.performClick();
            return true;
        }
    }

    static boolean isInsideVisualTarget(int width, int height, float x, float y) {
        return width > 0 && height > 0
                && x >= width * VISUAL_TARGET_MIN && x <= width * VISUAL_TARGET_MAX
                && y >= height * VISUAL_TARGET_MIN && y <= height * VISUAL_TARGET_MAX;
    }

    static String completedVisualGesture(int width, int height, float downX, float downY,
            float upX, float upY, long durationMs, boolean stayedInsideTarget, float swipeDistancePx) {
        if (!stayedInsideTarget
                || !isInsideVisualTarget(width, height, downX, downY)
                || !isInsideVisualTarget(width, height, upX, upY)) {
            return null;
        }
        if (Math.hypot(upX - downX, upY - downY) >= swipeDistancePx) {
            return "swipe completed";
        }
        if (durationMs >= 500L) {
            return "long press completed";
        }
        return "coordinate tap completed";
    }

    private Button button(String title) {
        Button button = new Button(this);
        button.setText(title);
        return button;
    }

    private TextView label(String text, int size, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private LinearLayout.LayoutParams params() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = dp(3);
        params.bottomMargin = dp(3);
        return params;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        params.leftMargin = dp(2);
        params.rightMargin = dp(2);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
