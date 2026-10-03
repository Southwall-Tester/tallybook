package dev.tallybook.app;

import android.content.Context;
import android.content.SharedPreferences;

/** Consent is time bounded. The provider checks this on every incoming event. */
public final class CaptureSettings {
    private static final String PREFS = "capture_settings";
    private static final long WINDOW_MILLIS = 30L * 60L * 1000L;

    private CaptureSettings() {}

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context context) {
        long now = System.currentTimeMillis();
        long until = enabledUntil(context);
        return until > now && until - now <= WINDOW_MILLIS;
    }

    public static synchronized void enableFor30Minutes(Context context) {
        prefs(context).edit().putLong("enabled_until", System.currentTimeMillis() + WINDOW_MILLIS).apply();
    }

    public static synchronized void disable(Context context) {
        prefs(context).edit().putLong("enabled_until", 0L).apply();
    }

    public static long enabledUntil(Context context) {
        return prefs(context).getLong("enabled_until", 0L);
    }

    public static synchronized void recordCapture(Context context, boolean inserted) {
        SharedPreferences settings = prefs(context);
        SharedPreferences.Editor edit = settings.edit().putLong("last_capture_at", System.currentTimeMillis());
        if (inserted) edit.putLong("capture_count", settings.getLong("capture_count", 0L) + 1L);
        edit.apply();
    }

    public static long lastCaptureAt(Context context) {
        return prefs(context).getLong("last_capture_at", 0L);
    }

    /** Number of distinct live captures observed, across ledger clears. */
    public static long captureCount(Context context) {
        return prefs(context).getLong("capture_count", 0L);
    }

    public static synchronized void recordHookSeen(Context context) {
        prefs(context).edit().putLong("last_hook_at", System.currentTimeMillis()).apply();
    }

    public static long lastHookAt(Context context) {
        return prefs(context).getLong("last_hook_at", 0L);
    }
}
