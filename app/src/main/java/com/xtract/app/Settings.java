package com.xtract.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** App preferences. The Claude API key is encrypted with a key held in the Android Keystore. */
final class Settings {

    private static final String PREFS = "xtract";
    private static final String KEY_ALIAS = "xtract-api-key";
    private static final String KEYSTORE = "AndroidKeyStore";

    private final SharedPreferences prefs;

    Settings(Context ctx) {
        prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    String model() {
        return prefs.getString("model", ClaudeClient.DEFAULT_MODEL);
    }

    void setModel(String model) {
        prefs.edit().putString("model", model).apply();
    }

    String lastUrl() {
        return prefs.getString("last_url", "https://x.com/home");
    }

    void setLastUrl(String url) {
        prefs.edit().putString("last_url", url).apply();
    }

    boolean hasApiKey() {
        return !apiKey().isEmpty();
    }

    String apiKey() {
        String stored = prefs.getString("api_key", null);
        if (stored == null) return "";
        try {
            byte[] all = Base64.decode(stored, Base64.NO_WRAP);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, secretKey(), new GCMParameterSpec(128, all, 0, 12));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return ""; // key invalidated (e.g. app data restored to another device)
        }
    }

    void setApiKey(String key) {
        key = key.trim();
        if (key.isEmpty()) {
            prefs.edit().remove("api_key").apply();
            return;
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, secretKey());
            byte[] iv = c.getIV();
            byte[] ct = c.doFinal(key.getBytes(StandardCharsets.UTF_8));
            byte[] all = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, all, 0, iv.length);
            System.arraycopy(ct, 0, all, iv.length, ct.length);
            prefs.edit().putString("api_key", Base64.encodeToString(all, Base64.NO_WRAP)).apply();
        } catch (Exception e) {
            throw new IllegalStateException("Could not store API key securely", e);
        }
    }

    private static SecretKey secretKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (ks.containsAlias(KEY_ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey();
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build());
        return gen.generateKey();
    }
}
