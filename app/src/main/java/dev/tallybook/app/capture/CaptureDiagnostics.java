package dev.tallybook.app.capture;

import android.content.Context;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Only diagnostic codes, never payloads, URLs, cookies, or exception messages. */
public final class CaptureDiagnostics {
    private static final Set<String> CODES = new HashSet<>(Arrays.asList(
        "HOOK_READY", "BILL_SAVED", "BILL_UPDATED", "UNSUPPORTED_PAYLOAD",
        "PARSE_REJECTED", "CAPTURE_OFF", "IPC_FAILED"));
    private CaptureDiagnostics() {}
    public static void record(Context context, String code) {
        if (!CODES.contains(code)) return;
        context.getSharedPreferences("capture_diagnostics", Context.MODE_PRIVATE)
            .edit().putString("code", code).putLong("at", System.currentTimeMillis()).apply();
    }
    public static String latest(Context context) {
        return context.getSharedPreferences("capture_diagnostics", Context.MODE_PRIVATE)
            .getString("code", "NO_SIGNAL");
    }
}
