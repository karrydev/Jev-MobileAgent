package com.jev.mobileagent;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicBoolean;

/** App-local credential settings and task entry point. */
public class MainActivity extends Activity {
    private static final int NOTIFICATION_PERMISSION_REQUEST = 2602;
    private static final String DEFAULT_INPUT_GOAL = "在中文输入框中输入“独立手机测试成功”";

    private EditText vlmProvider;
    private EditText vlmEndpoint;
    private EditText vlmModel;
    private EditText vlmKey;
    private EditText jevProvider;
    private EditText jevEndpoint;
    private EditText jevModel;
    private EditText jevKey;
    private EditText goalInput;
    private TextView settingsStatus;
    private TextView taskStatus;
    private TextView accessibilityStatus;
    private TextView jevDebugStatus;
    private Button debugValidProbeButton;
    private Button debugInvalidProbeButton;
    private Button debugCaseButton;
    private Button debugCancelButton;
    private volatile AtomicBoolean debugCancellation;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        performUpgradeMigration();
        setContentView(buildContent());
        loadProfiles();
        updateAccessibilityStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateAccessibilityStatus();
        JSONObject active = LocalTaskStore.activeTask(this);
        if (active == null) {
            JSONObject latest = LocalTaskStore.latestTask(this);
            renderTask((latest == null ? "当前没有任务记录" : taskSummary(latest)) + "\n" + globalBudgetText());
            return;
        }
        String id = active.optString("task_id", "");
        String state = active.optString("state", "");
        if ("ARMED".equals(state)) {
            LocalVlmTaskService.arm(this, id);
            renderTask("任务已待命。切换到目标应用后，在通知中点“开始任务”\n" + globalBudgetText());
        } else if ("RUNNING".equals(state) && !LocalVlmTaskService.isTaskLoopActive()) {
            LocalTaskStore.markInterrupted(this);
            renderTask("应用运行中断；本地任务已暂停，请检查手机页面后取消该任务\n" + globalBudgetText());
        } else {
            renderTask(taskSummary(active) + "\n" + globalBudgetText());
        }
    }

    private View buildContent() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        scroll.addView(root);

        TextView title = label("Jev Mobile Agent", 25, Color.rgb(35, 50, 65));
        title.setContentDescription("Jev standalone Android agent");
        root.addView(title, widthMatchWrap());
        root.addView(label("任务在手机内运行并直接连接 HTTPS 模型。无需自建服务器、电脑或 ADB。", 15,
                Color.DKGRAY), marginTop(widthMatchWrap(), 4));

        root.addView(label("VLM 配置", 19, Color.rgb(35, 50, 65)), marginTop(widthMatchWrap(), 20));
        vlmProvider = input("Provider，例如 GUI-Plus", false);
        vlmEndpoint = input("HTTPS endpoint，例如 https://host/v1", false);
        vlmModel = input("Model，例如 gui-plus-2026-02-26", false);
        vlmKey = input("VLM API key（留空保留已保存的 key）", true);
        root.addView(vlmProvider, widthMatchWrap());
        root.addView(vlmEndpoint, widthMatchWrap());
        root.addView(vlmModel, widthMatchWrap());
        root.addView(vlmKey, widthMatchWrap());
        LinearLayout vlmButtons = new LinearLayout(this);
        vlmButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button saveVlm = button("保存 VLM");
        saveVlm.setOnClickListener(view -> saveProfile(ModelProfileStore.Type.VLM));
        Button clearVlm = button("清除 VLM");
        clearVlm.setOnClickListener(view -> clearProfile(ModelProfileStore.Type.VLM));
        vlmButtons.addView(saveVlm, rowParams());
        vlmButtons.addView(clearVlm, rowParams());
        root.addView(vlmButtons, widthMatchWrap());

        root.addView(label("Jev 配置（独立保存）", 19, Color.rgb(35, 50, 65)), marginTop(widthMatchWrap(), 18));
        jevProvider = input("Jev provider", false);
        jevEndpoint = input("Jev HTTPS endpoint", false);
        jevModel = input("Jev model", false);
        jevKey = input("Jev API key（留空保留已保存的 key）", true);
        root.addView(jevProvider, widthMatchWrap());
        root.addView(jevEndpoint, widthMatchWrap());
        root.addView(jevModel, widthMatchWrap());
        root.addView(jevKey, widthMatchWrap());
        LinearLayout jevButtons = new LinearLayout(this);
        jevButtons.setOrientation(LinearLayout.HORIZONTAL);
        Button saveJev = button("保存 Jev");
        saveJev.setOnClickListener(view -> saveProfile(ModelProfileStore.Type.JEV));
        Button clearJev = button("清除 Jev");
        clearJev.setOnClickListener(view -> clearProfile(ModelProfileStore.Type.JEV));
        jevButtons.addView(saveJev, rowParams());
        jevButtons.addView(clearJev, rowParams());
        root.addView(jevButtons, widthMatchWrap());

        settingsStatus = label("Key 未显示在此页；保存值使用 Android Keystore 加密。", 13, Color.DKGRAY);
        settingsStatus.setTextIsSelectable(true);
        root.addView(settingsStatus, marginTop(widthMatchWrap(), 6));
        root.addView(label("预算限制", 18, Color.rgb(35, 50, 65)), marginTop(widthMatchWrap(), 14));
        root.addView(label("当前可执行模型：GUI-Plus gui-plus-2026-02-26。每任务上限 ¥1、全局累计上限 ¥10；usage 缺失或费用超限时会暂停。其他模型可保存，完成费率审查前不能启动付费任务。", 13,
                Color.DKGRAY), widthMatchWrap());

        CheckBox jevShadow = new CheckBox(this);
        jevShadow.setText("启用 Jev 影子建议（只记录；VLM 仍控制手机动作）");
        jevShadow.setChecked(LocalTaskStore.isJevShadowEnabled(this));
        jevShadow.setOnCheckedChangeListener((button, checked) -> {
            if (!LocalTaskStore.setJevShadowEnabled(this, checked)) {
                button.setChecked(!checked);
                settingsStatus.setText("Jev 影子设置未能保存；任务保持原配置。");
            }
        });
        root.addView(jevShadow, marginTop(widthMatchWrap(), 10));
        root.addView(label("启用后，新任务每步最多发送一次 Jev HTTPS 请求。每次请求前从任务和全局预算各预留 ¥0.01；美元 usage 单独记录，实际账单与汇率未知。费用预留不足会在后续 VLM 或设备请求前暂停。", 12,
                Color.DKGRAY), widthMatchWrap());

        CheckBox jevSelection = new CheckBox(this);
        jevSelection.setText("启用 Jev 受控候选选择（默认关闭）");
        jevSelection.setChecked(LocalTaskStore.isJevSelectionEnabled(this));
        jevSelection.setOnCheckedChangeListener((button, checked) -> {
            if (!LocalTaskStore.setJevSelectionEnabled(this, checked)) {
                button.setChecked(!checked);
                settingsStatus.setText("Jev 选择设置未能保存；任务保持原配置。");
            }
        });
        root.addView(jevSelection, marginTop(widthMatchWrap(), 10));
        root.addView(label("开启后仍由原 VLM Executor 按原频率提出当前动作；只有该动作能唯一匹配当前观察中的完整候选时才请求 Jev。覆盖不足、建议无效或置信度不足时沿用原 VLM 动作；预算门控拒绝时安全暂停。每步至多一次 Jev 请求，任务创建时固定此开关。", 12,
                Color.DKGRAY), widthMatchWrap());

        CheckBox treeVerification = new CheckBox(this);
        treeVerification.setText("启用树优先动作核验（默认关闭）");
        treeVerification.setChecked(LocalTaskStore.isTreeVerificationEnabled(this));
        treeVerification.setOnCheckedChangeListener((button, checked) -> {
            if (!LocalTaskStore.setTreeVerificationEnabled(this, checked)) {
                button.setChecked(!checked);
                settingsStatus.setText("树核验设置未能保存；任务保持原配置。");
            }
        });
        root.addView(treeVerification, marginTop(widthMatchWrap(), 10));
        root.addView(label("新任务快照独立开关。规则先核对当前树；必要时 Jev 给出四态，信息不足再用真实前后图调用 VLM Reflector。PENDING 最多等待 2 次、每次 600ms；前后截图缺失时保持 UNKNOWN 并暂停。", 12,
                Color.DKGRAY), widthMatchWrap());

        CheckBox onDemandPlanning = new CheckBox(this);
        onDemandPlanning.setText("启用按需规划（默认关闭）");
        onDemandPlanning.setChecked(LocalTaskStore.isOnDemandPlanningEnabled(this));
        onDemandPlanning.setOnCheckedChangeListener((button, checked) -> {
            if (!LocalTaskStore.setOnDemandPlanningEnabled(this, checked)) {
                button.setChecked(!checked);
                settingsStatus.setText("按需规划设置未能保存；任务保持原配置。");
            }
        });
        root.addView(onDemandPlanning, marginTop(widthMatchWrap(), 10));
        root.addView(label("新任务创建时固定此开关。任务开始、已核验的子目标完成、动作异常、计划过期或循环时重新规划；其他步复用计划，最多复用 2 个已执行步骤后复查。反复失败或模型预算耗尽时安全暂停，逐步核验仍按上方设置执行。", 12,
                Color.DKGRAY), widthMatchWrap());

        if (BuildConfig.DEBUG) {
            root.addView(label("Jev 协议验收（仅 Debug）", 18, Color.rgb(35, 50, 65)),
                    marginTop(widthMatchWrap(), 16));
            root.addView(label("探针每次各发送一条请求并预留 ¥0.01，不执行手机动作。单 case 输入：files/jev-shadow-debug/input.json；报告：files/jev-shadow-debug/report.json。", 12,
                    Color.DKGRAY), widthMatchWrap());
            debugValidProbeButton = button("运行一次中文有效协议探针");
            debugValidProbeButton.setOnClickListener(view -> runJevDebug(false, false));
            root.addView(debugValidProbeButton, widthMatchWrap());
            debugInvalidProbeButton = button("运行一次显式无效协议探针");
            debugInvalidProbeButton.setOnClickListener(view -> runJevDebug(false, true));
            root.addView(debugInvalidProbeButton, widthMatchWrap());
            debugCaseButton = button("运行私有 input.json 单 case");
            debugCaseButton.setOnClickListener(view -> runJevDebug(true, false));
            root.addView(debugCaseButton, widthMatchWrap());
            debugCancelButton = button("取消当前 Jev 调试请求");
            debugCancelButton.setEnabled(false);
            debugCancelButton.setOnClickListener(view -> {
                AtomicBoolean cancellation = debugCancellation;
                if (cancellation != null) cancellation.set(true);
            });
            root.addView(debugCancelButton, widthMatchWrap());
            jevDebugStatus = label("调试探针不会修改任务目标或执行动作。", 12, Color.DKGRAY);
            jevDebugStatus.setTextIsSelectable(true);
            root.addView(jevDebugStatus, marginTop(widthMatchWrap(), 4));
        }

        accessibilityStatus = label("无障碍权限：检查中", 14, Color.DKGRAY);
        root.addView(accessibilityStatus, marginTop(widthMatchWrap(), 16));
        Button accessibility = button("打开无障碍设置");
        accessibility.setOnClickListener(view -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(accessibility, widthMatchWrap());

        root.addView(label("任务", 19, Color.rgb(35, 50, 65)), marginTop(widthMatchWrap(), 20));
        goalInput = input("目标，例如：在中文输入框中输入“独立手机测试成功”", false);
        goalInput.setMinLines(2);
        goalInput.setGravity(Gravity.TOP | Gravity.START);
        String savedGoal = getSharedPreferences(BridgeConfig.PREFS, MODE_PRIVATE).getString("task_goal", "");
        goalInput.setText(savedGoal.isEmpty() ? DEFAULT_INPUT_GOAL : savedGoal);
        root.addView(goalInput, widthMatchWrap());

        Button fillInputGoal = button("填入中文输入测试目标");
        fillInputGoal.setOnClickListener(view -> goalInput.setText(DEFAULT_INPUT_GOAL));
        root.addView(fillInputGoal, widthMatchWrap());

        Button armTask = button("准备在其他应用中运行");
        armTask.setOnClickListener(view -> armTaskForOtherApp());
        root.addView(armTask, marginTop(widthMatchWrap(), 8));

        Button controlledPage = button("打开受控测试页面");
        controlledPage.setOnClickListener(view -> {
            persistGoal();
            startActivity(new Intent(this, ControlledPageActivity.class));
        });
        root.addView(controlledPage, widthMatchWrap());
        if (BuildConfig.DEBUG) {
            Button verificationFixture = button("打开树核验受控夹具（仅 Debug）");
            verificationFixture.setOnClickListener(view -> {
                persistGoal();
                startActivity(new Intent(this, ControlledPageActivity.class)
                        .putExtra(ControlledPageActivity.EXTRA_DEBUG_TREE_VERIFICATION_FIXTURE, true));
            });
            root.addView(verificationFixture, widthMatchWrap());
        }
        taskStatus = label("当前没有活动任务", 14, Color.DKGRAY);
        taskStatus.setTextIsSelectable(true);
        root.addView(taskStatus, marginTop(widthMatchWrap(), 8));
        return scroll;
    }

    private void performUpgradeMigration() {
        ModelProfileStore.MigrationResult migration = ModelProfileStore.migrateLegacyVlm(this);
        SharedPreferences legacy = getSharedPreferences(BridgeConfig.PREFS, MODE_PRIVATE);
        legacy.edit()
                .remove("endpoint")
                .remove("token")
                .remove("device_id")
                .remove("task_id")
                .remove("device_session_id")
                .putBoolean("capture_enabled", false)
                .commit();
        migrationMessage = migration.message;
        if (!migration.legacyKeyCleared && migration.needsKeyReentry) {
            migrationMessage = "旧 VLM key 无法安全迁移；原设置已保留，请重新输入并保存 key。";
        }
    }

    private String migrationMessage = "";

    private void loadProfiles() {
        loadProfile(ModelProfileStore.Type.VLM, true);
        loadProfile(ModelProfileStore.Type.JEV, true);
        String vlmKeyState = keyState(ModelProfileStore.load(this, ModelProfileStore.Type.VLM));
        String jevKeyState = keyState(ModelProfileStore.load(this, ModelProfileStore.Type.JEV));
        settingsStatus.setText((migrationMessage.isEmpty() ? "" : migrationMessage + "\n")
                + "VLM key：" + vlmKeyState + "；Jev key：" + jevKeyState
                + "。Key 使用 Android Keystore AES-GCM 加密；空白输入会保留已保存 key。");
    }

    private void loadProfile(ModelProfileStore.Type type, boolean includeProviderDefault) {
        ModelProfileStore.Profile profile = ModelProfileStore.load(this, type);
        EditText provider = providerInput(type);
        provider.setText(profile.provider.isEmpty() && includeProviderDefault && type == ModelProfileStore.Type.VLM
                ? LocalVlmBudgetPolicy.REVIEWED_PROVIDER : profile.provider);
        endpointInput(type).setText(profile.endpoint.isEmpty() && type == ModelProfileStore.Type.VLM
                ? "https://dashscope.aliyuncs.com/compatible-mode/v1" : profile.endpoint);
        modelInput(type).setText(profile.model.isEmpty() && type == ModelProfileStore.Type.VLM
                ? LocalVlmBudgetPolicy.REVIEWED_MODEL : profile.model);
        keyInput(type).setText("");
        keyInput(type).setHint(profile.needsKeyReentry
                ? "Key 需要重新输入" : profile.hasSavedKey ? "已保存；留空保留" : "输入 API key");
    }

    private void saveProfile(ModelProfileStore.Type type) {
        EditText providerField = providerInput(type);
        EditText endpointField = endpointInput(type);
        EditText modelField = modelInput(type);
        EditText keyField = keyInput(type);
        ModelProfileStore.Profile previous = ModelProfileStore.load(this, type);
        String provider = providerField.getText().toString().trim();
        String endpoint = endpointField.getText().toString().trim();
        String model = modelField.getText().toString().trim();
        String enteredKey = keyField.getText().toString();
        if (!isHttpsEndpoint(endpoint)) {
            settingsStatus.setText("Endpoint 必须是有效的 HTTPS 地址；配置未保存。");
            return;
        }
        boolean keyNeeded = previous.needsKeyReentry || !previous.hasSavedKey
                || !previous.provider.equals(provider) || !previous.endpoint.equals(endpoint);
        if (keyNeeded && enteredKey.trim().isEmpty()) {
            settingsStatus.setText("Provider 或 endpoint 改变时必须重新输入 API key；未保存配置。");
            return;
        }
        if (!ModelProfileStore.save(this, type, provider, endpoint, model, enteredKey)) {
            settingsStatus.setText("配置未保存。若 Key 解密失败，请重新输入并保存 Key。");
            return;
        }
        if (type == ModelProfileStore.Type.VLM && !ModelProfileStore.clearLegacyVlm(this)) {
            settingsStatus.setText("VLM 已保存，但旧明文设置无法清除；请重新输入 VLM key 后再保存一次。");
        } else {
            settingsStatus.setText((type == ModelProfileStore.Type.VLM ? "VLM" : "Jev")
                    + " profile 已保存；API key 未显示且已加密。");
        }
        keyField.setText("");
        keyField.setHint("已保存；留空保留");
    }

    private void clearProfile(ModelProfileStore.Type type) {
        if (!ModelProfileStore.clear(this, type)) {
            settingsStatus.setText("配置未能完全清除，请重试。");
            return;
        }
        if (type == ModelProfileStore.Type.VLM) {
            ModelProfileStore.clearLegacyVlm(this);
        }
        loadProfile(type, false);
        settingsStatus.setText((type == ModelProfileStore.Type.VLM ? "VLM" : "Jev")
                + " provider、endpoint、model 和已保存 key 已清除。");
    }

    private void armTaskForOtherApp() {
        String goal = persistGoal();
        if (!requireTaskReady()) {
            return;
        }
        if (!ensureNotificationPermission()) {
            taskStatus.setText("请允许通知权限，然后再次准备任务；运行通知提供随时可用的暂停和取消按钮。");
            return;
        }
        JSONObject task = LocalTaskStore.create(this, goal,
                vlmProvider.getText().toString().trim(), vlmModel.getText().toString().trim(), true);
        if (task == null) {
            taskStatus.setText("已有本地任务；请先在通知中取消已暂停任务。");
            return;
        }
        String id = task.optString("task_id", "");
        try {
            LocalVlmTaskService.arm(this, id);
            taskStatus.setText("任务已待命。切换到目标应用后，下拉通知并点“开始任务”。");
        } catch (RuntimeException exception) {
            LocalTaskStore.updateState(this, id, "PAUSED", "foreground_service_start_failed");
            taskStatus.setText("无法启动本地任务通知；任务已安全暂停。");
        }
    }

    private boolean requireTaskReady() {
        ModelProfileStore.Profile profile = ModelProfileStore.load(this, ModelProfileStore.Type.VLM);
        if (!profile.isConfigured()) {
            taskStatus.setText("请先保存完整的 VLM provider、HTTPS endpoint、model 和 API key。");
            return false;
        }
        if (!LocalVlmBudgetPolicy.isReviewedProfile(profile.provider, profile.model)) {
            taskStatus.setText("本地没有此 provider/model 的已审查费率，无法执行 ¥1 预算门控。");
            return false;
        }
        if (!ObservationAccessibilityService.isEnabled(this)) {
            taskStatus.setText("请先在系统无障碍设置中启用 Jev local task accessibility。");
            return false;
        }
        if (goalInput.getText().toString().trim().isEmpty()) {
            taskStatus.setText("请先填写任务目标。");
            return false;
        }
        return true;
    }

    private String persistGoal() {
        String goal = goalInput.getText().toString().trim();
        getSharedPreferences(BridgeConfig.PREFS, MODE_PRIVATE).edit().putString("task_goal", goal).apply();
        return goal;
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
                    ? "通知权限已开启，请再次准备任务。"
                    : "任务运行时必须通过通知提供暂停和取消控制；通知权限仍未开启。");
        }
    }

    private void updateAccessibilityStatus() {
        if (accessibilityStatus != null) {
            boolean enabled = ObservationAccessibilityService.isEnabled(this);
            accessibilityStatus.setText("无障碍权限：" + (enabled ? "已启用" : "未启用"));
            accessibilityStatus.setContentDescription("Accessibility permission " + (enabled ? "enabled" : "not enabled"));
        }
    }

    private void renderTask(String message) {
        if (taskStatus != null) {
            taskStatus.setText(message);
        }
    }

    static String taskSummary(JSONObject task) {
        String status = task.optString("runtime_status", "");
        String summary = task.optString("state", "") + (status.isEmpty() ? "" : "：" + status);
        JSONObject history = task.optJSONObject("history");
        String answer = history == null ? "" : history.optString("last_answer", "").trim();
        String result = answer.isEmpty() ? summary : summary + "\n模型答复：" + answer;
        if (task.optBoolean("on_demand_planning_enabled", false)) {
            org.json.JSONArray planningEvents = task.optJSONArray("planning_events");
            int replans = 0;
            int reused = 0;
            String lastReason = "";
            for (int i = 0; planningEvents != null && i < planningEvents.length(); i++) {
                JSONObject event = planningEvents.optJSONObject(i);
                if (event == null || !"planner_decision".equals(event.optString("event", ""))) continue;
                if ("replan".equals(event.optString("decision", ""))) replans++;
                if ("reuse_plan".equals(event.optString("decision", ""))) reused++;
                lastReason = event.optString("reason", lastReason);
            }
            result += "\n按需规划：" + replans + " 次重新规划，" + reused + " 步复用计划"
                    + (lastReason.isEmpty() ? "" : "；最近决策：" + lastReason);
        }
        org.json.JSONArray jevAttempts = task.optJSONArray("jev_shadow_attempts");
        if (jevAttempts != null && jevAttempts.length() > 0) {
            int valid = 0;
            int controlled = 0;
            int dispatched = 0;
            int fallback = 0;
            for (int i = 0; i < jevAttempts.length(); i++) {
                JSONObject attempt = jevAttempts.optJSONObject(i);
                if (attempt == null) continue;
                if ("valid_recommendation".equals(attempt.optString("status", ""))
                        || "valid_low_confidence".equals(attempt.optString("status", ""))) valid++;
                if ("task_controlled".equals(attempt.optString("source", ""))) {
                    controlled++;
                    if (attempt.optBoolean("action_dispatched", false)) dispatched++;
                    String selectionStatus = attempt.optString("selection_status", "");
                    if ("fallback".equals(selectionStatus) || "blocked".equals(selectionStatus)) fallback++;
                }
            }
            if (controlled > 0 || task.optBoolean("jev_selection_enabled", false)) {
                result += "\nJev 受控选择：" + controlled + " 次尝试，" + dispatched
                        + " 个候选动作已派发，" + fallback + " 次回退或门控停止；" + valid + " 条合法协议选择";
            } else {
                result += "\nJev 影子：" + jevAttempts.length() + " 次尝试，" + valid + " 条合法建议；从未执行 Jev 动作";
            }
        }
        return result;
    }

    private void runJevDebug(boolean privateCase, boolean invalidProbe) {
        if (debugCancellation != null) return;
        AtomicBoolean cancellation = new AtomicBoolean(false);
        debugCancellation = cancellation;
        setDebugButtonsEnabled(false);
        if (jevDebugStatus != null) jevDebugStatus.setText("Jev 请求进行中；手机动作不会执行。可随时取消。");
        Thread worker = new Thread(() -> {
            String resultText;
            try {
                JSONObject report = privateCase
                        ? JevShadowDebugRunner.runPrivateCase(this, cancellation::get)
                        : JevShadowDebugRunner.runProtocolProbe(this, invalidProbe, cancellation::get);
                File reportFile = JevShadowDebugRunner.reportFile(this);
                resultText = "完成：" + report.optJSONObject("attempt").optString("status", "unknown")
                        + "；报告：" + reportFile.getAbsolutePath();
            } catch (Exception exception) {
                resultText = "未完成：" + safeDebugError(exception) + "；没有 Jev 选择进入设备动作。";
            }
            String finalResultText = resultText;
            runOnUiThread(() -> {
                debugCancellation = null;
                setDebugButtonsEnabled(true);
                if (jevDebugStatus != null) jevDebugStatus.setText(finalResultText);
            });
        }, "jev-debug-acceptance");
        worker.setDaemon(true);
        worker.start();
    }

    private void setDebugButtonsEnabled(boolean enabled) {
        if (debugValidProbeButton != null) debugValidProbeButton.setEnabled(enabled);
        if (debugInvalidProbeButton != null) debugInvalidProbeButton.setEnabled(enabled);
        if (debugCaseButton != null) debugCaseButton.setEnabled(enabled);
        if (debugCancelButton != null) debugCancelButton.setEnabled(!enabled);
    }

    private static String safeDebugError(Exception exception) {
        if (exception instanceof JevApiClient.JevApiException) {
            return "Jev " + ((JevApiClient.JevApiException) exception).getCategory().name().toLowerCase(java.util.Locale.ROOT);
        }
        if (exception instanceof java.io.IOException) return "本地输入或配置错误";
        if (exception instanceof org.json.JSONException) return "本地报告格式错误";
        return "本地调试错误";
    }

    private String globalBudgetText() {
        LocalTaskStore.BudgetSnapshot budget = LocalTaskStore.budgetSnapshot(this, "");
        return String.format(java.util.Locale.ROOT, "预算已计费用与预留 ¥%.4f / ¥%.0f",
                budget.globalAccountedCny, budget.globalBudgetCny);
    }

    private static boolean isHttpsEndpoint(String value) {
        try {
            URI endpoint = new URI(value == null ? "" : value.trim());
            return "https".equalsIgnoreCase(endpoint.getScheme())
                    && endpoint.getHost() != null
                    && endpoint.getRawUserInfo() == null
                    && endpoint.getRawFragment() == null
                    && endpoint.getPort() != 0;
        } catch (URISyntaxException exception) {
            return false;
        }
    }

    private static String keyState(ModelProfileStore.Profile profile) {
        if (profile.needsKeyReentry) {
            return "需重新输入";
        }
        return profile.hasSavedKey ? "已加密保存" : "未保存";
    }

    private EditText providerInput(ModelProfileStore.Type type) {
        return type == ModelProfileStore.Type.VLM ? vlmProvider : jevProvider;
    }

    private EditText endpointInput(ModelProfileStore.Type type) {
        return type == ModelProfileStore.Type.VLM ? vlmEndpoint : jevEndpoint;
    }

    private EditText modelInput(ModelProfileStore.Type type) {
        return type == ModelProfileStore.Type.VLM ? vlmModel : jevModel;
    }

    private EditText keyInput(ModelProfileStore.Type type) {
        return type == ModelProfileStore.Type.VLM ? vlmKey : jevKey;
    }

    private EditText input(String hint, boolean secret) {
        EditText field = new EditText(this);
        field.setHint(hint);
        field.setSingleLine(!hint.startsWith("目标"));
        if (secret) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        return field;
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

    private LinearLayout.LayoutParams widthMatchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams marginTop(LinearLayout.LayoutParams source, int marginDp) {
        source.topMargin = dp(marginDp);
        return source;
    }

    private LinearLayout.LayoutParams rowParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f);
        params.rightMargin = dp(4);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
