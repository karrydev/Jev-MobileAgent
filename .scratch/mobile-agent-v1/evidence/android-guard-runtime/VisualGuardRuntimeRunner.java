package com.jev.mobileagent;

import android.os.Build;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Offline fixture runner for production guard classes in the installed APK. */
public final class VisualGuardRuntimeRunner {
    private static final String SOURCE_SHA = "8d3bcc52aa10c86188f8d4b2b2ab09159e55de8e";
    private static final String EXPECTED_APK_SHA256 =
            "9c66e641c1c61e29889703ff423a8a053074767dafe4f4a58dcd2bad1fc84a1a";
    private static final String MODEL_REASON = "fixture_model_success";

    private VisualGuardRuntimeRunner() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("usage: <fixture-directory> <installed-base.apk>");
        }
        File fixtureDir = new File(args[0]);
        File installedApk = new File(args[1]);
        String apkSha256 = sha256(readAll(installedApk));
        if (!EXPECTED_APK_SHA256.equals(apkSha256)) {
            throw new AssertionError("installed APK SHA-256 mismatch: " + apkSha256);
        }

        JSONArray cases = new JSONArray();
        cases.put(runPair(fixtureDir, "miss", "UNCHANGED", "UNKNOWN"));
        cases.put(runPair(fixtureDir, "hit", "CHANGED", "SUCCESS"));

        ClassLoader runnerLoader = VisualGuardRuntimeRunner.class.getClassLoader();
        ClassLoader guardLoader = VisualChangeGuard.class.getClassLoader();
        if (runnerLoader != guardLoader) {
            throw new AssertionError("runner and production guard use different class loaders");
        }
        JSONObject runtime = new JSONObject()
                .put("sdk", Build.VERSION.SDK_INT)
                .put("release", Build.VERSION.RELEASE)
                .put("sourceSha", SOURCE_SHA)
                .put("installedApkSha256", apkSha256)
                .put("installedApkPath", installedApk.getAbsolutePath())
                .put("runnerClassLoader", String.valueOf(runnerLoader))
                .put("productionClassLoader", String.valueOf(guardLoader))
                .put("sameClassLoader", runnerLoader == guardLoader)
                .put("javaClassPath", System.getProperty("java.class.path", ""))
                .put("productionClassLocation", codeSourceLocation(VisualChangeGuard.class));
        JSONObject output = new JSONObject()
                .put("runner", "android-app-process-visual-change-guard-v1")
                .put("policy", VisualChangeGuard.POLICY_ID)
                .put("runtime", runtime)
                .put("cases", cases);
        System.out.println(output.toString());
    }

    private static JSONObject runPair(File dir, String name, String expectedChange,
            String expectedGuardStatus) throws Exception {
        File beforeJson = new File(dir, name + "-before.json");
        File afterJson = new File(dir, name + "-after.json");
        File beforePng = new File(dir, name + "-before.png");
        File afterPng = new File(dir, name + "-after.png");
        JSONObject before = readJson(beforeJson);
        JSONObject after = readJson(afterJson);
        JSONObject beforeShot = screenshot(before, "BEFORE", name + "-before", beforePng);
        JSONObject afterShot = screenshot(after, "AFTER", name + "-after", afterPng);

        VisualChangeGuard.Assessment assessment =
                VisualChangeGuard.inspect(before, after, beforeShot, afterShot);
        TreeActionVerifier.Result modelDecision = TreeActionVerifier.decision(
                TreeActionVerifier.Status.SUCCESS, MODEL_REASON, new JSONObject());
        JSONObject action = new JSONObject().put("kind", "coordinate_tap");
        TreeActionVerifier.Result guarded = VisualChangeGuard.guardVisualDecision(
                modelDecision, action, before, after, beforeShot, afterShot);

        if (assessment.change != VisualChangeGuard.Change.valueOf(expectedChange)) {
            throw new AssertionError(name + " inspect expected " + expectedChange
                    + " but was " + assessment.change);
        }
        if (guarded.status != TreeActionVerifier.Status.valueOf(expectedGuardStatus)) {
            throw new AssertionError(name + " guard expected " + expectedGuardStatus
                    + " but was " + guarded.status);
        }
        if ("miss".equals(name)
                && !"event_action_success_without_visible_app_change".equals(guarded.reason)) {
            throw new AssertionError("miss guard reason mismatch: " + guarded.reason);
        }
        if ("hit".equals(name) && !MODEL_REASON.equals(guarded.reason)) {
            throw new AssertionError("hit did not preserve model decision: " + guarded.reason);
        }

        JSONObject inputHashes = new JSONObject()
                .put("beforeJson", sha256(readAll(beforeJson)))
                .put("afterJson", sha256(readAll(afterJson)))
                .put("beforePng", sha256(readAll(beforePng)))
                .put("afterPng", sha256(readAll(afterPng)));
        JSONObject inspect = new JSONObject()
                .put("change", assessment.change.name())
                .put("reason", assessment.reason)
                .put("comparison", assessment.evidence.optString("comparison", ""))
                .put("comparedPixelCount", assessment.evidence.optLong("compared_pixel_count", 0));
        JSONObject guard = new JSONObject()
                .put("modelStatus", modelDecision.status.name())
                .put("status", guarded.status.name())
                .put("reason", guarded.reason);
        return new JSONObject()
                .put("fixture", name)
                .put("inputSha256", inputHashes)
                .put("inspect", inspect)
                .put("guardVisualDecision", guard);
    }

    private static JSONObject screenshot(JSONObject observation, String captureType,
            String screenshotId, File png) throws Exception {
        JSONObject screen = observation.getJSONObject("screen");
        byte[] pngBytes = readAll(png);
        return new JSONObject()
                .put("screenshot_id", screenshotId)
                .put("observation_id", observation.getString("observation_id"))
                .put("capture_type", captureType)
                .put("width_px", screen.getInt("width_px"))
                .put("height_px", screen.getInt("height_px"))
                .put("png_base64", Base64.encodeToString(pngBytes, Base64.NO_WRAP));
    }

    private static JSONObject readJson(File file) throws Exception {
        return new JSONObject(new String(readAll(file), StandardCharsets.UTF_8));
    }

    private static byte[] readAll(File file) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        FileInputStream input = new FileInputStream(file);
        try {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        } finally {
            input.close();
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] input) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(input);
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte value : digest) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static String codeSourceLocation(Class<?> type) {
        try {
            if (type.getProtectionDomain() == null
                    || type.getProtectionDomain().getCodeSource() == null) return "unavailable";
            return String.valueOf(type.getProtectionDomain().getCodeSource().getLocation());
        } catch (Throwable unavailable) {
            return "unavailable";
        }
    }
}
