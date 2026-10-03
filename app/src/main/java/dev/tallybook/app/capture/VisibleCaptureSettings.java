package dev.tallybook.app.capture;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Local, time-limited consent for window OCR; independent of the experimental hook. */
public final class VisibleCaptureSettings {
    private static final String PREFS = "visible_capture_settings";
    static final String GENERATION = "generation";
    private static final long WINDOW_MILLIS = 30L * 60L * 1000L;
    private static final Set<String> CODES = new HashSet<>(Arrays.asList(
            "OFF", "WAITING_WECHAT", "SERVICE_READY", "SERVICE_STOPPED", "EXPIRED",
            "UNSUPPORTED_ANDROID", "WINDOW_UNAVAILABLE", "SCREENSHOT_FAILED", "SECURE_WINDOW",
            "OCR_FAILED", "NOT_BILL_SCREEN", "ROWS_SKIPPED", "BILLS_SAVED", "NO_NEW_BILLS",
            "INPUT_LIMIT", "SAVE_FAILED", "NEEDS_MONTH"));

    private VisibleCaptureSettings() {}

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static synchronized void enableFor30Minutes(Context context) {
        if (Build.VERSION.SDK_INT < 34) throw new IllegalStateException("屏幕读取需要 Android 14 或以上");
        SharedPreferences settings = prefs(context);
        settings.edit().putLong("enabled_until", System.currentTimeMillis() + WINDOW_MILLIS)
                .putLong("elapsed_until", SystemClock.elapsedRealtime() + WINDOW_MILLIS)
                .putInt("boot_count", bootCount(context))
                .putLong(GENERATION, settings.getLong(GENERATION, 0L) + 1L)
                .putString("status", "WAITING_WECHAT").apply();
        changed(context);
    }

    public static synchronized void disable(Context context) {
        SharedPreferences settings = prefs(context);
        settings.edit().putLong("enabled_until", 0L).putLong("elapsed_until", 0L)
                .putLong(GENERATION, settings.getLong(GENERATION, 0L) + 1L)
                .putString("status", "OFF").apply();
        changed(context);
    }

    public static synchronized boolean isEnabled(Context context) {
        SharedPreferences settings = prefs(context);
        long wallRemaining = settings.getLong("enabled_until", 0L) - System.currentTimeMillis();
        long elapsedRemaining = settings.getLong("elapsed_until", 0L) - SystemClock.elapsedRealtime();
        return Build.VERSION.SDK_INT >= 34 && wallRemaining > 0L && wallRemaining <= WINDOW_MILLIS
                && elapsedRemaining > 0L && elapsedRemaining <= WINDOW_MILLIS
                && settings.getInt("boot_count", -1) == bootCount(context);
    }

    public static long enabledUntil(Context context) { return prefs(context).getLong("enabled_until", 0L); }
    public static long captureCount(Context context) { return prefs(context).getLong("capture_count", 0L); }
    public static long lastCaptureAt(Context context) { return prefs(context).getLong("last_capture_at", 0L); }
    public static long lastScanAt(Context context) { return prefs(context).getLong("last_scan_at", 0L); }
    public static int skippedRows(Context context) { return prefs(context).getInt("skipped_rows", 0); }
    public static long lastServiceSeen(Context context) { return prefs(context).getLong("last_service_seen", 0L); }
    static long generation(Context context) { return prefs(context).getLong(GENERATION, 0L); }

    public static String latestStatus(Context context) {
        if (enabledUntil(context) > 0L && !isEnabled(context)) return "EXPIRED";
        return prefs(context).getString("status", "OFF");
    }

    public static String statusMessage(Context context) {
        switch (latestStatus(context)) {
            case "WAITING_WECHAT": return "等待你打开微信账单列表";
            case "SERVICE_READY": return "系统服务已连接，等待开始读取";
            case "SERVICE_STOPPED": return "系统服务已断开，请检查无障碍设置";
            case "EXPIRED": return "本次读取已到期，可重新开始";
            case "UNSUPPORTED_ANDROID": return "屏幕读取需要 Android 14 或以上";
            case "WINDOW_UNAVAILABLE": return "暂时无法确认微信窗口，请停留在账单列表";
            case "SCREENSHOT_FAILED": return "本次无法读取窗口，稍后会重试";
            case "SECURE_WINDOW": return "微信限制此窗口截图，本页无法读取";
            case "OCR_FAILED": return "本次文字识别失败，稍后会重试";
            case "NOT_BILL_SCREEN": return "尚未识别到账单列表，请打开账单并稍作停留";
            case "ROWS_SKIPPED": return "部分账单信息不完整，请慢慢滚动并停留";
            case "NEEDS_MONTH": return "请先显示账单年月，缓慢翻动并保留重叠记录";
            case "BILLS_SAVED": return "已录入新账单，回到小账本核对";
            case "NO_NEW_BILLS": return "当前页已读取，没有新增账单";
            case "INPUT_LIMIT": return "当前画面过于复杂，请回到账单列表";
            case "SAVE_FAILED": return "本次保存失败，请回到小账本检查";
            default: return "读取已关闭";
        }
    }

    public static boolean isServiceEnabled(Context context) {
        AccessibilityManager manager = context.getSystemService(AccessibilityManager.class);
        if (manager == null || !manager.isEnabled()) return false;
        ComponentName expected = new ComponentName(context, WechatAccessibilityService.class);
        for (AccessibilityServiceInfo info : manager.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK)) {
            ComponentName actual = ComponentName.unflattenFromString(info.getId());
            if (expected.equals(actual)) return true;
        }
        return false;
    }

    static synchronized void recordServiceSeen(Context context) {
        prefs(context).edit().putLong("last_service_seen", System.currentTimeMillis()).apply();
    }

    static synchronized void recordStatus(Context context, String code) {
        if (!CODES.contains(code) || code.equals(prefs(context).getString("status", "OFF"))) return;
        prefs(context).edit().putString("status", code).apply();
        changed(context);
    }

    static synchronized void recordScan(Context context, int candidates, int inserted, int skipped) {
        SharedPreferences settings = prefs(context);
        SharedPreferences.Editor edit = settings.edit().putLong("last_scan_at", System.currentTimeMillis())
                .putInt("skipped_rows", Math.max(0, skipped))
                .putLong("capture_count", settings.getLong("capture_count", 0L) + Math.max(0, inserted));
        if (candidates > 0) edit.putLong("last_capture_at", System.currentTimeMillis());
        edit.apply();
    }

    private static int bootCount(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
    }

    static void changed(Context context) {
        context.getContentResolver().notifyChange(CaptureProvider.EVENTS, null);
    }
}
