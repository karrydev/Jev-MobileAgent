package com.jev.mobileagent;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Stores independent model profiles, encrypting each API key with Android Keystore. */
public final class ModelProfileStore {
    private static final String PROFILE_PREFS = "jev_model_profiles_v1";
    private static final String LEGACY_SECRET_PREFS = "jev_model_secrets_v1";
    private static final String KEY_ALIAS = "jev_model_secrets_aes_gcm_v1";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final int PROFILE_FORMAT_VERSION = 1;
    private static final Object STORE_LOCK = new Object();

    private static final SecretCodec KEYSTORE_CODEC = new SecretCodec() {
        @Override
        public String encrypt(String plaintext) throws Exception {
            return encryptWithKeystore(plaintext);
        }

        @Override
        public String decrypt(String ciphertext) throws Exception {
            return decryptWithKeystore(ciphertext);
        }
    };
    private static volatile SecretCodec secretCodec = KEYSTORE_CODEC;

    private ModelProfileStore() {
    }

    public enum Type {
        VLM("vlm"),
        JEV("jev");

        final String prefix;

        Type(String prefix) {
            this.prefix = prefix;
        }
    }

    public static final class Profile {
        public final String provider;
        public final String endpoint;
        public final String model;
        public final String apiKey;
        public final boolean hasSavedKey;
        public final boolean needsKeyReentry;

        private Profile(String provider, String endpoint, String model, String apiKey,
                boolean hasSavedKey, boolean needsKeyReentry) {
            this.provider = provider;
            this.endpoint = endpoint;
            this.model = model;
            this.apiKey = apiKey;
            this.hasSavedKey = hasSavedKey;
            this.needsKeyReentry = needsKeyReentry;
        }

        public boolean isConfigured() {
            return !provider.isEmpty() && !endpoint.isEmpty() && !model.isEmpty() && !apiKey.isEmpty();
        }
    }

    public static final class MigrationResult {
        public final boolean changed;
        public final boolean legacyKeyCleared;
        public final boolean needsKeyReentry;
        public final String message;

        private MigrationResult(boolean changed, boolean legacyKeyCleared,
                boolean needsKeyReentry, String message) {
            this.changed = changed;
            this.legacyKeyCleared = legacyKeyCleared;
            this.needsKeyReentry = needsKeyReentry;
            this.message = message;
        }
    }

    public static Profile load(Context context, Type type) {
        synchronized (STORE_LOCK) {
            return loadLocked(context, type);
        }
    }

    /** Blank key input keeps the current credential only when provider and endpoint are unchanged. */
    public static boolean save(Context context, Type type, String provider,
            String endpoint, String model, String apiKey) {
        synchronized (STORE_LOCK) {
            return saveLocked(context, type, provider, endpoint, model, apiKey);
        }
    }

    public static boolean clear(Context context, Type type) {
        synchronized (STORE_LOCK) {
            SharedPreferences profilePreferences = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE);
            boolean profileCleared = profilePreferences.edit()
                    .remove(profileKey(type))
                    .remove(key(type, "provider"))
                    .remove(key(type, "endpoint"))
                    .remove(key(type, "model"))
                    .commit();
            if (!profileCleared) {
                return false;
            }
            // Removing a leftover split-format ciphertext is cleanup only. loadLocked ignores it
            // without the old profile metadata, even if this second commit fails.
            return context.getSharedPreferences(LEGACY_SECRET_PREFS, Context.MODE_PRIVATE)
                    .edit().remove(key(type, "secret")).commit();
        }
    }

    /** Move the previous bridge form's VLM profile to encrypted storage, then erase its plaintext fields. */
    public static MigrationResult migrateLegacyVlm(Context context) {
        synchronized (STORE_LOCK) {
            SharedPreferences legacy = context.getSharedPreferences(BridgeConfig.PREFS, Context.MODE_PRIVATE);
            boolean hasLegacyProfile = legacy.contains("model_provider") || legacy.contains("model_endpoint")
                    || legacy.contains("model_name") || legacy.contains("model_api_key");
            if (!hasLegacyProfile) {
                return new MigrationResult(false, true, false, "No older model settings found.");
            }

            Profile current = loadLocked(context, Type.VLM);
            String legacyKey = legacy.getString("model_api_key", "");
            if (current.needsKeyReentry && legacyKey.isEmpty()) {
                return new MigrationResult(false, false, true,
                        "An older encrypted key needs re-entry. The existing legacy settings were kept.");
            }
            if (!current.hasSavedKey || current.needsKeyReentry) {
                String provider = legacy.getString("model_provider", "GUI-Plus");
                String endpoint = legacy.getString("model_endpoint", "");
                String model = legacy.getString("model_name", "");
                if (!saveLocked(context, Type.VLM, provider, endpoint, model, legacyKey)) {
                    return new MigrationResult(false, false, !legacyKey.isEmpty(),
                            "The older VLM key could not be encrypted. Re-enter it below before clearing older settings.");
                }
            }

            boolean cleared = legacy.edit()
                    .remove("model_provider")
                    .remove("model_endpoint")
                    .remove("model_name")
                    .remove("model_api_key")
                    .commit();
            Profile migrated = loadLocked(context, Type.VLM);
            return new MigrationResult(true, cleared, migrated.needsKeyReentry,
                    cleared ? "Older VLM settings moved to private model settings."
                            : "Older VLM settings were copied but could not be fully cleared.");
        }
    }

    /** Clear legacy plaintext fields only when a replacement key can be decrypted now. */
    public static boolean clearLegacyVlm(Context context) {
        synchronized (STORE_LOCK) {
            Profile saved = loadLocked(context, Type.VLM);
            if (!saved.hasSavedKey || saved.needsKeyReentry || saved.apiKey.isEmpty()) {
                return false;
            }
            SharedPreferences legacy = context.getSharedPreferences(BridgeConfig.PREFS, Context.MODE_PRIVATE);
            return legacy.edit()
                    .remove("model_provider")
                    .remove("model_endpoint")
                    .remove("model_name")
                    .remove("model_api_key")
                    .commit();
        }
    }

    private static Profile loadLocked(Context context, Type type) {
        SharedPreferences profiles = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE);
        String document = profiles.getString(profileKey(type), null);
        if (document != null) {
            return decodeProfile(document);
        }

        // Upgrade profiles written before profile and encrypted key shared one atomic value.
        String providerKey = key(type, "provider");
        String endpointKey = key(type, "endpoint");
        String modelKey = key(type, "model");
        boolean hasSplitProfile = profiles.contains(providerKey)
                || profiles.contains(endpointKey)
                || profiles.contains(modelKey);
        if (!hasSplitProfile) {
            return emptyProfile();
        }

        String provider = profiles.getString(providerKey, "");
        String endpoint = profiles.getString(endpointKey, "");
        String model = profiles.getString(modelKey, "");
        String encrypted = context.getSharedPreferences(LEGACY_SECRET_PREFS, Context.MODE_PRIVATE)
                .getString(key(type, "secret"), null);
        Profile migrated = decryptProfile(provider, endpoint, model, encrypted);

        String migratedDocument = encodeProfile(provider, endpoint, model, encrypted);
        if (migratedDocument != null) {
            profiles.edit()
                    .putString(profileKey(type), migratedDocument)
                    .remove(providerKey)
                    .remove(endpointKey)
                    .remove(modelKey)
                    .commit();
        }
        return migrated;
    }

    private static boolean saveLocked(Context context, Type type, String provider,
            String endpoint, String model, String apiKey) {
        String cleanProvider = trim(provider);
        String cleanEndpoint = trim(endpoint);
        String cleanModel = trim(model);
        String enteredKey = apiKey == null ? "" : apiKey.trim();
        Profile current = loadLocked(context, type);

        final String encrypted;
        if (!enteredKey.isEmpty()) {
            try {
                encrypted = secretCodec.encrypt(enteredKey);
            } catch (Exception exception) {
                return false;
            }
        } else {
            if (current.needsKeyReentry) {
                return false;
            }
            if (current.hasSavedKey) {
                if (!cleanProvider.equals(current.provider) || !cleanEndpoint.equals(current.endpoint)) {
                    return false;
                }
                try {
                    encrypted = secretCodec.encrypt(current.apiKey);
                } catch (Exception exception) {
                    return false;
                }
            } else {
                encrypted = "";
            }
        }

        String document = encodeProfile(cleanProvider, cleanEndpoint, cleanModel, encrypted);
        if (document == null) {
            return false;
        }
        SharedPreferences profiles = context.getSharedPreferences(PROFILE_PREFS, Context.MODE_PRIVATE);
        // Metadata and ciphertext live in one value and one commit. Readers also hold STORE_LOCK,
        // so they observe either the old complete profile or the new complete profile.
        return profiles.edit()
                .putString(profileKey(type), document)
                .remove(key(type, "provider"))
                .remove(key(type, "endpoint"))
                .remove(key(type, "model"))
                .commit();
    }

    private static String encodeProfile(String provider, String endpoint, String model, String encrypted) {
        try {
            return new JSONObject()
                    .put("version", PROFILE_FORMAT_VERSION)
                    .put("provider", provider)
                    .put("endpoint", endpoint)
                    .put("model", model)
                    .put("encrypted_key", encrypted == null ? "" : encrypted)
                    .toString();
        } catch (JSONException | RuntimeException exception) {
            return null;
        }
    }

    private static Profile decodeProfile(String document) {
        try {
            JSONObject profile = new JSONObject(document);
            if (profile.optInt("version", -1) != PROFILE_FORMAT_VERSION) {
                return unreadableProfile();
            }
            String provider = requiredString(profile, "provider");
            String endpoint = requiredString(profile, "endpoint");
            String model = requiredString(profile, "model");
            String encrypted = requiredString(profile, "encrypted_key");
            return decryptProfile(provider, endpoint, model, encrypted);
        } catch (JSONException | RuntimeException exception) {
            return unreadableProfile();
        }
    }

    private static Profile decryptProfile(String provider, String endpoint, String model, String encrypted) {
        if (encrypted == null || encrypted.isEmpty()) {
            return new Profile(provider, endpoint, model, "", false, false);
        }
        try {
            return new Profile(provider, endpoint, model, secretCodec.decrypt(encrypted), true, false);
        } catch (Exception exception) {
            // Never replace unreadable ciphertext with an empty key; the user must re-enter it.
            return new Profile(provider, endpoint, model, "", true, true);
        }
    }

    private static String requiredString(JSONObject profile, String field) throws JSONException {
        Object value = profile.get(field);
        if (!(value instanceof String)) {
            throw new JSONException("invalid profile field");
        }
        return (String) value;
    }

    private static Profile emptyProfile() {
        return new Profile("", "", "", "", false, false);
    }

    private static Profile unreadableProfile() {
        return new Profile("", "", "", "", true, true);
    }

    private static String encryptWithKeystore(String plaintext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        // Randomized IVs are generated by Android Keystore. Supplying a caller IV
        // conflicts with randomizedEncryptionRequired and is rejected by AndroidKeyStore.
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] iv = cipher.getIV();
        byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[iv.length + ciphertext.length];
        System.arraycopy(iv, 0, packed, 0, iv.length);
        System.arraycopy(ciphertext, 0, packed, iv.length, ciphertext.length);
        try {
            return Base64.encodeToString(packed, Base64.NO_WRAP);
        } finally {
            Arrays.fill(ciphertext, (byte) 0);
            Arrays.fill(packed, (byte) 0);
        }
    }

    private static String decryptWithKeystore(String encrypted) throws Exception {
        byte[] packed = Base64.decode(encrypted, Base64.NO_WRAP);
        if (packed.length <= GCM_IV_BYTES + 16) {
            Arrays.fill(packed, (byte) 0);
            throw new IllegalArgumentException("invalid encrypted secret");
        }
        byte[] iv = Arrays.copyOfRange(packed, 0, GCM_IV_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(packed, GCM_IV_BYTES, packed.length);
        Arrays.fill(packed, (byte) 0);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(iv, (byte) 0);
            Arrays.fill(ciphertext, (byte) 0);
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        java.security.Key existing = keyStore.getKey(KEY_ALIAS, null);
        if (existing instanceof SecretKey) {
            return (SecretKey) existing;
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build());
        return generator.generateKey();
    }

    private static String profileKey(Type type) {
        return type.prefix + ".profile";
    }

    private static String key(Type type, String field) {
        return type.prefix + "." + field;
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    static void setSecretCodecForTests(SecretCodec codec) {
        secretCodec = codec == null ? KEYSTORE_CODEC : codec;
    }

    interface SecretCodec {
        String encrypt(String plaintext) throws Exception;

        String decrypt(String ciphertext) throws Exception;
    }
}
