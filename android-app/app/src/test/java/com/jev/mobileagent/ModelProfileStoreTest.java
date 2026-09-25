package com.jev.mobileagent;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class ModelProfileStoreTest {
    private static final String API_KEY_A = "vlm-secret-a";
    private static final String API_KEY_B = "vlm-secret-b";
    private MemoryContext context;

    @Before
    public void setUp() {
        context = new MemoryContext();
        ModelProfileStore.setSecretCodecForTests(new TestCodec());
    }

    @After
    public void tearDown() {
        ModelProfileStore.setSecretCodecForTests(null);
    }

    @Test
    public void savesCompleteProfileAndSecretInOneAtomicPreferenceValue() {
        assertTrue(ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                "GUI-Plus", "https://one.example/v1", "model-a", API_KEY_A));

        MemoryPreferences profiles = context.preferences("jev_model_profiles_v1");
        String document = profiles.getString("vlm.profile", null);
        assertEquals(1, profiles.commitCount);
        assertTrue(document.contains("https://one.example/v1"));
        assertFalse(document.contains(API_KEY_A));
        assertFalse(context.preferences("jev_model_secrets_v1").contains("vlm.secret"));

        profiles.failNextCommit = true;
        assertFalse(ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                "GUI-Plus", "https://two.example/v1", "model-b", API_KEY_B));

        ModelProfileStore.Profile loaded = ModelProfileStore.load(context, ModelProfileStore.Type.VLM);
        assertEquals("https://one.example/v1", loaded.endpoint);
        assertEquals("model-a", loaded.model);
        assertEquals(API_KEY_A, loaded.apiKey);
        assertFalse(loaded.needsKeyReentry);
        assertEquals("Failed commit must leave the prior complete profile in memory",
                2, profiles.commitCount);
    }

    @Test
    public void concurrentSavesKeepEachEndpointBoundToItsOwnKey() throws Exception {
        ExecutorService writers = Executors.newFixedThreadPool(2);
        try {
            for (int iteration = 0; iteration < 20; iteration++) {
                CountDownLatch start = new CountDownLatch(1);
                Future<Boolean> first = writers.submit(() -> {
                    start.await();
                    return ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                            "GUI-Plus", "https://one.example/v1", "model-one", API_KEY_A);
                });
                Future<Boolean> second = writers.submit(() -> {
                    start.await();
                    return ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                            "GUI-Plus", "https://two.example/v1", "model-two", API_KEY_B);
                });
                start.countDown();
                assertTrue(first.get());
                assertTrue(second.get());

                ModelProfileStore.Profile loaded = ModelProfileStore.load(context, ModelProfileStore.Type.VLM);
                boolean firstPair = "https://one.example/v1".equals(loaded.endpoint)
                        && "model-one".equals(loaded.model) && API_KEY_A.equals(loaded.apiKey);
                boolean secondPair = "https://two.example/v1".equals(loaded.endpoint)
                        && "model-two".equals(loaded.model) && API_KEY_B.equals(loaded.apiKey);
                assertTrue("concurrent saves mixed profile metadata and key", firstPair || secondPair);
            }
        } finally {
            writers.shutdownNow();
        }
    }

    @Test
    public void blankKeyCannotFollowProviderOrEndpointChangeButCanKeepSameEndpointKey() {
        assertTrue(ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                "GUI-Plus", "https://one.example/v1", "model-a", API_KEY_A));

        assertFalse(ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                "GUI-Plus", "https://two.example/v1", "model-a", ""));
        assertFalse(ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                "OtherProvider", "https://one.example/v1", "model-a", ""));

        ModelProfileStore.Profile unchanged = ModelProfileStore.load(context, ModelProfileStore.Type.VLM);
        assertEquals("https://one.example/v1", unchanged.endpoint);
        assertEquals(API_KEY_A, unchanged.apiKey);

        assertTrue(ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                "GUI-Plus", "https://one.example/v1", "model-b", "  "));
        ModelProfileStore.Profile modelChanged = ModelProfileStore.load(context, ModelProfileStore.Type.VLM);
        assertEquals("model-b", modelChanged.model);
        assertEquals(API_KEY_A, modelChanged.apiKey);
    }

    @Test
    public void clearLegacyRequiresReadableReplacementKey() {
        MemoryPreferences legacy = context.preferences(BridgeConfig.PREFS);
        legacy.edit()
                .putString("model_provider", "GUI-Plus")
                .putString("model_endpoint", "https://legacy.example/v1")
                .putString("model_name", "legacy-model")
                .putString("model_api_key", "legacy-plaintext")
                .commit();

        assertTrue(ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                "GUI-Plus", "https://replacement.example/v1", "replacement-model", ""));
        assertFalse(ModelProfileStore.clearLegacyVlm(context));
        assertEquals("legacy-plaintext", legacy.getString("model_api_key", null));

        assertTrue(ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                "GUI-Plus", "https://replacement.example/v1", "replacement-model", API_KEY_B));
        assertTrue(ModelProfileStore.clearLegacyVlm(context));
        assertFalse(legacy.contains("model_api_key"));
        assertEquals(API_KEY_B, ModelProfileStore.load(context, ModelProfileStore.Type.VLM).apiKey);
    }

    @Test
    public void failedLegacyEncryptionKeepsPlaintextSourceForRecovery() {
        MemoryPreferences legacy = context.preferences(BridgeConfig.PREFS);
        legacy.edit()
                .putString("model_provider", "GUI-Plus")
                .putString("model_endpoint", "https://legacy.example/v1")
                .putString("model_name", "legacy-model")
                .putString("model_api_key", "legacy-plaintext")
                .commit();
        ModelProfileStore.setSecretCodecForTests(new TestCodec() {
            @Override
            public String encrypt(String plaintext) {
                throw new IllegalStateException("simulated encryption failure");
            }
        });

        ModelProfileStore.MigrationResult result = ModelProfileStore.migrateLegacyVlm(context);

        assertFalse(result.changed);
        assertFalse(result.legacyKeyCleared);
        assertTrue(result.needsKeyReentry);
        assertEquals("legacy-plaintext", legacy.getString("model_api_key", null));
        assertFalse(context.preferences("jev_model_profiles_v1").contains("vlm.profile"));
    }

    @Test
    public void unreadableKeyRequiresReentryAndDoesNotGetReused() {
        assertTrue(ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                "GUI-Plus", "https://one.example/v1", "model-a", API_KEY_A));
        String priorDocument = context.preferences("jev_model_profiles_v1").getString("vlm.profile", null);
        ModelProfileStore.setSecretCodecForTests(new TestCodec() {
            @Override
            public String decrypt(String ciphertext) {
                throw new IllegalStateException("simulated unreadable key");
            }
        });

        ModelProfileStore.Profile loaded = ModelProfileStore.load(context, ModelProfileStore.Type.VLM);
        assertTrue(loaded.hasSavedKey);
        assertTrue(loaded.needsKeyReentry);
        assertFalse(ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                "GUI-Plus", "https://one.example/v1", "model-b", ""));
        assertEquals(priorDocument,
                context.preferences("jev_model_profiles_v1").getString("vlm.profile", null));

        assertTrue(ModelProfileStore.save(context, ModelProfileStore.Type.VLM,
                "GUI-Plus", "https://one.example/v1", "model-b", API_KEY_B));
        ModelProfileStore.setSecretCodecForTests(new TestCodec());
        assertEquals(API_KEY_B, ModelProfileStore.load(context, ModelProfileStore.Type.VLM).apiKey);
    }

    @Test
    public void migratesSplitProfileIntoOneAtomicValueAndKeepsJeVlmIndependent() {
        MemoryPreferences oldProfiles = context.preferences("jev_model_profiles_v1");
        oldProfiles.edit()
                .putString("vlm.provider", "GUI-Plus")
                .putString("vlm.endpoint", "https://legacy.example/v1")
                .putString("vlm.model", "legacy-model")
                .commit();
        context.preferences("jev_model_secrets_v1").edit()
                .putString("vlm.secret", new TestCodec().encryptUnchecked(API_KEY_A))
                .commit();

        ModelProfileStore.Profile migrated = ModelProfileStore.load(context, ModelProfileStore.Type.VLM);

        assertEquals("https://legacy.example/v1", migrated.endpoint);
        assertEquals(API_KEY_A, migrated.apiKey);
        assertTrue(oldProfiles.contains("vlm.profile"));
        assertFalse(oldProfiles.contains("vlm.endpoint"));
        assertTrue(ModelProfileStore.save(context, ModelProfileStore.Type.JEV,
                "GUI-Plus", "https://jev.example/v1", "jev-model", "jev-secret"));
        assertEquals(API_KEY_A, ModelProfileStore.load(context, ModelProfileStore.Type.VLM).apiKey);
        assertEquals("jev-secret", ModelProfileStore.load(context, ModelProfileStore.Type.JEV).apiKey);
    }

    private static class TestCodec implements ModelProfileStore.SecretCodec {
        @Override
        public String encrypt(String plaintext) {
            return encryptUnchecked(plaintext);
        }

        @Override
        public String decrypt(String ciphertext) {
            if (!ciphertext.startsWith("test:")) {
                throw new IllegalArgumentException("invalid test ciphertext");
            }
            byte[] bytes = java.util.Base64.getDecoder().decode(ciphertext.substring("test:".length()));
            return new String(bytes, StandardCharsets.UTF_8);
        }

        String encryptUnchecked(String plaintext) {
            return "test:" + java.util.Base64.getEncoder().encodeToString(
                    plaintext.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static final class MemoryContext extends ContextWrapper {
        private final Map<String, MemoryPreferences> preferences = new HashMap<>();

        MemoryContext() {
            super(null);
        }

        @Override
        public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
            MemoryPreferences result = preferences.get(name);
            if (result == null) {
                result = new MemoryPreferences();
                preferences.put(name, result);
            }
            return result;
        }

        synchronized MemoryPreferences preferences(String name) {
            getSharedPreferences(name, Context.MODE_PRIVATE);
            return preferences.get(name);
        }
    }

    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        private boolean failNextCommit;
        private int commitCount;

        @Override
        public synchronized Map<String, ?> getAll() {
            return new HashMap<>(values);
        }

        @Override
        public synchronized String getString(String key, String defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (String) value;
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized Set<String> getStringSet(String key, Set<String> defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : new HashSet<>((Set<String>) value);
        }

        @Override
        public synchronized int getInt(String key, int defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Integer) value;
        }

        @Override
        public synchronized long getLong(String key, long defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Long) value;
        }

        @Override
        public synchronized float getFloat(String key, float defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Float) value;
        }

        @Override
        public synchronized boolean getBoolean(String key, boolean defaultValue) {
            Object value = values.get(key);
            return value == null ? defaultValue : (Boolean) value;
        }

        @Override
        public synchronized boolean contains(String key) {
            return values.containsKey(key);
        }

        @Override
        public Editor edit() {
            return new MemoryEditor();
        }

        @Override
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        @Override
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        }

        private final class MemoryEditor implements Editor {
            private final Map<String, Object> updates = new HashMap<>();
            private final Set<String> removals = new HashSet<>();
            private boolean clear;

            @Override
            public Editor putString(String key, String value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putStringSet(String key, Set<String> value) {
                updates.put(key, value == null ? null : new HashSet<>(value));
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putInt(String key, int value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putLong(String key, long value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putFloat(String key, float value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor putBoolean(String key, boolean value) {
                updates.put(key, value);
                removals.remove(key);
                return this;
            }

            @Override
            public Editor remove(String key) {
                updates.remove(key);
                removals.add(key);
                return this;
            }

            @Override
            public Editor clear() {
                clear = true;
                updates.clear();
                removals.clear();
                return this;
            }

            @Override
            public boolean commit() {
                synchronized (MemoryPreferences.this) {
                    commitCount++;
                    if (failNextCommit) {
                        failNextCommit = false;
                        return false;
                    }
                    if (clear) {
                        values.clear();
                    }
                    for (String key : removals) {
                        values.remove(key);
                    }
                    for (Map.Entry<String, Object> entry : updates.entrySet()) {
                        if (entry.getValue() == null) {
                            values.remove(entry.getKey());
                        } else {
                            values.put(entry.getKey(), entry.getValue());
                        }
                    }
                    return true;
                }
            }

            @Override
            public void apply() {
                commit();
            }
        }
    }
}
