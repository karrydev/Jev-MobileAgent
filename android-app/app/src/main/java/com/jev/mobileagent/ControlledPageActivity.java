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
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
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
    private static final float VISUAL_TARGET_MIN = 0.22f;
    private static final float VISUAL_TARGET_MAX = 0.78f;

    private final Handler statusHandler = new Handler(Looper.getMainLooper());
    private EditText goalInput;
    private TextView taskStatus;
    private TextView inputStatus;
    private TextView visualStatus;
    private String taskId = "";
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
        taskStatus.setMaxLines(3);
        taskStatus.setTextIsSelectable(true);
        taskStatus.setContentDescription("Local VLM task status");
        root.addView(taskStatus, params());

        setContentView(root);
        refreshTaskStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        try {
            LocalVlmTaskService.startTask(this, taskId);
            taskStatus.setText("本地任务：已启动；可用此页或通知栏随时暂停/取消");
        } catch (RuntimeException exception) {
            LocalTaskStore.updateState(this, taskId, "PAUSED", "foreground_service_start_failed");
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
        String progress = active.optString("runtime_status", "");
        if (!progress.isEmpty()) {
            message += " · " + progress;
        }
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
