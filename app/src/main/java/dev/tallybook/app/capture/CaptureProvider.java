package dev.tallybook.app.capture;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Process;
import dev.tallybook.app.CaptureSettings;
import dev.tallybook.app.LedgerStore;
import dev.tallybook.core.Transaction;

/** Narrow, write-only, local bridge from the explicitly scoped WeChat process. */
public final class CaptureProvider extends ContentProvider {
    public static final Uri EVENTS = Uri.parse("content://dev.tallybook.app.capture/events");
    private LedgerStore store;

    @Override public boolean onCreate() {
        store = new LedgerStore(getContext());
        return true;
    }

    private void requireWeChatCaller() {
        int uid = Binder.getCallingUid();
        if (uid == Process.myUid()) return;
        String[] packages = getContext().getPackageManager().getPackagesForUid(uid);
        if (packages != null) for (String name : packages) {
            if ("com.tencent.mm".equals(name)) return;
        }
        throw new SecurityException("Caller not permitted");
    }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        requireWeChatCaller();
        Bundle result = new Bundle();
        boolean enabled = CaptureSettings.isEnabled(getContext());
        result.putBoolean("enabled", enabled);
        result.putLong("enabledUntil", CaptureSettings.enabledUntil(getContext()));
        switch (method) {
            case "hello":
                CaptureSettings.recordHookSeen(getContext());
                CaptureDiagnostics.record(getContext(), "HOOK_READY");
                changed();
                break;
            case "state": break;
            case "diagnostic":
                if (enabled) {
                    CaptureDiagnostics.record(getContext(), arg);
                    changed();
                }
                break;
            case "record":
                if (!enabled) { result.putString("code", "CAPTURE_OFF"); break; }
                try {
                    String payload = extras == null ? null : extras.getString("transaction");
                    if (payload == null || payload.length() > 16384) {
                        result.putString("code", "PARSE_REJECTED");
                        break;
                    }
                    Transaction transaction = Transaction.fromJson(payload);
                    if (!Transaction.PROVIDER.equals(transaction.provider)) {
                        throw new IllegalArgumentException("Capture accepts WeChat records only");
                    }
                    boolean inserted = store.insert(transaction, "wechat");
                    CaptureSettings.recordCapture(getContext(), inserted);
                    CaptureDiagnostics.record(getContext(), inserted ? "BILL_SAVED" : "BILL_UPDATED");
                    result.putBoolean("accepted", true);
                    result.putBoolean("inserted", inserted);
                    changed();
                } catch (Exception invalid) {
                    // No raw data or exception content enters logs or diagnostics.
                    CaptureDiagnostics.record(getContext(), "PARSE_REJECTED");
                    result.putString("code", "PARSE_REJECTED");
                }
                break;
            default: throw new IllegalArgumentException("Unknown operation");
        }
        return result;
    }

    private void changed() { getContext().getContentResolver().notifyChange(EVENTS, null); }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        throw new SecurityException("Ledger is private");
    }
    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}
