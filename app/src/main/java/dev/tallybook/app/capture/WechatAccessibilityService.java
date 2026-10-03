package dev.tallybook.app.capture;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.TargetApi;
import android.app.KeyguardManager;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import dev.tallybook.app.LedgerStore;
import dev.tallybook.core.Transaction;
import dev.tallybook.core.WechatScreenParser;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** Only reads the foreground WeChat window during an explicit, bounded app consent session. */
public final class WechatAccessibilityService extends AccessibilityService {
    private static final String WECHAT = "com.tencent.mm";
    private static final long INTERVAL_MS = 2500L;
    private static final long MAX_PIXELS = 20_000_000L;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong frameEpoch = new AtomicLong();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final WechatScreenParser.Session session = new WechatScreenParser.Session();
    private final Object recognizerLock = new Object();
    private TextRecognizer recognizer;
    private LedgerStore store;
    private volatile boolean alive;
    private boolean inFlight;
    private boolean ocrProcessing;
    private boolean recognizerClosed;
    private long lastSeenRecorded;
    private long observedGeneration;

    private final SharedPreferences.OnSharedPreferenceChangeListener settingsListener = (settings, key) -> {
        if (VisibleCaptureSettings.GENERATION.equals(key)) main.post(() -> {
            if (!alive) return;
            observedGeneration = VisibleCaptureSettings.generation(this);
            invalidateSession();
            main.removeCallbacks(this.poll);
            main.post(this.poll);
        });
    };

    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!alive) return;
            if (!VisibleCaptureSettings.isEnabled(WechatAccessibilityService.this)) {
                invalidateSession();
                if (VisibleCaptureSettings.enabledUntil(WechatAccessibilityService.this) > 0L) {
                    VisibleCaptureSettings.recordStatus(WechatAccessibilityService.this, "EXPIRED");
                }
                return;
            }
            long generation = VisibleCaptureSettings.generation(WechatAccessibilityService.this);
            if (generation != observedGeneration) {
                observedGeneration = generation;
                invalidateSession();
            }
            int windowId = foregroundWechatWindow();
            if (windowId < 0) {
                invalidateSession();
                status("WAITING_WECHAT", generation);
            } else if (!inFlight && Build.VERSION.SDK_INT >= 34) {
                inFlight = true;
                takeWindow(windowId, generation, frameEpoch.get());
            }
            main.postDelayed(this, INTERVAL_MS);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        alive = true;
        store = new LedgerStore(this);
        recognizer = TextRecognition.getClient(new ChineseTextRecognizerOptions.Builder().build());
        observedGeneration = VisibleCaptureSettings.generation(this);
        VisibleCaptureSettings.prefs(this).registerOnSharedPreferenceChangeListener(settingsListener);
    }

    @Override protected void onServiceConnected() {
        AccessibilityServiceInfo info = getServiceInfo();
        info.packageNames = new String[]{WECHAT};
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED | AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        VisibleCaptureSettings.recordServiceSeen(this);
        if (Build.VERSION.SDK_INT < 34) VisibleCaptureSettings.recordStatus(this, "UNSUPPORTED_ANDROID");
        else if (!VisibleCaptureSettings.isEnabled(this)) VisibleCaptureSettings.recordStatus(this, "SERVICE_READY");
        main.removeCallbacks(poll);
        main.post(poll);
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        // Never retain an event, node text, content description, or an event payload.
        if (event == null || !WECHAT.contentEquals(event.getPackageName() == null ? "" : event.getPackageName())) return;
        // Re-entering WeChat or changing its window must not reuse the prior page's month context.
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) invalidateSession();
        long now = System.currentTimeMillis();
        if (now - lastSeenRecorded > 30_000L) {
            lastSeenRecorded = now;
            VisibleCaptureSettings.recordServiceSeen(this);
        }
    }

    @Override public void onInterrupt() { invalidateSession(); }

    /** Package and window identity only; no accessibility text or bounds are read. */
    @SuppressWarnings("deprecation")
    private int foregroundWechatWindow() {
        PowerManager power = getSystemService(PowerManager.class);
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (power == null || !power.isInteractive() || keyguard == null || keyguard.isDeviceLocked()) return -1;
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root != null) {
                if (!WECHAT.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) return -1;
                if (root.getWindowId() >= 0) return root.getWindowId();
            }
        } catch (RuntimeException unavailable) {
            return -1;
        } finally {
            if (root != null) root.recycle();
        }
        List<AccessibilityWindowInfo> windows = null;
        try {
            windows = getWindows();
            for (AccessibilityWindowInfo window : windows) {
                if (!window.isActive() || window.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo windowRoot = null;
                try {
                    windowRoot = window.getRoot();
                    if (windowRoot != null && WECHAT.contentEquals(
                            windowRoot.getPackageName() == null ? "" : windowRoot.getPackageName())) return window.getId();
                } finally {
                    if (windowRoot != null) windowRoot.recycle();
                }
            }
        } catch (RuntimeException unavailable) {
            return -1;
        } finally {
            if (windows != null) for (AccessibilityWindowInfo window : windows) window.recycle();
        }
        return -1;
    }

    private boolean allowed(long generation, long epoch, int windowId) {
        return alive && VisibleCaptureSettings.isEnabled(this)
                && VisibleCaptureSettings.generation(this) == generation && frameEpoch.get() == epoch
                && foregroundWechatWindow() == windowId;
    }

    @TargetApi(34)
    private void takeWindow(int windowId, long generation, long epoch) {
        if (!allowed(generation, epoch, windowId)) { finishFrame(); return; }
        try {
            takeScreenshotOfWindow(windowId, getMainExecutor(), new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult result) {
                    HardwareBuffer buffer = result.getHardwareBuffer();
                    if (!allowed(generation, epoch, windowId)) { buffer.close(); finishFrame(); return; }
                    try {
                        worker.execute(() -> processScreenshot(result, buffer, windowId, generation, epoch));
                    } catch (RejectedExecutionException stopped) {
                        buffer.close();
                        finishFrame();
                    }
                }
                @Override public void onFailure(int errorCode) {
                    status(errorCode == ERROR_TAKE_SCREENSHOT_SECURE_WINDOW ? "SECURE_WINDOW" : "SCREENSHOT_FAILED", generation);
                    finishFrame();
                }
            });
        } catch (RuntimeException unavailable) {
            status("SCREENSHOT_FAILED", generation);
            finishFrame();
        }
    }

    @TargetApi(34)
    private void processScreenshot(ScreenshotResult result, HardwareBuffer buffer,
                                   int windowId, long generation, long epoch) {
        Bitmap hardware = null;
        Bitmap bitmap = null;
        try {
            if (!allowed(generation, epoch, windowId)) { finishFrame(); return; }
            if ((long) buffer.getWidth() * buffer.getHeight() > MAX_PIXELS) {
                status("INPUT_LIMIT", generation);
                finishFrame();
                return;
            }
            hardware = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
            if (hardware == null) throw new IllegalStateException();
            bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
            if (bitmap == null) throw new IllegalStateException();
        } catch (RuntimeException unavailable) {
            status("SCREENSHOT_FAILED", generation);
        } finally {
            if (hardware != null) hardware.recycle();
            buffer.close();
        }
        if (bitmap == null) { finishFrame(); return; }
        final Bitmap input = bitmap;
        synchronized (recognizerLock) {
            if (!allowed(generation, epoch, windowId) || recognizerClosed) {
                input.recycle();
                finishFrame();
                return;
            }
            try {
                ocrProcessing = true;
                recognizer.process(InputImage.fromBitmap(input, 0)).addOnCompleteListener(getMainExecutor(), task -> {
                    synchronized (recognizerLock) { ocrProcessing = false; }
                    try {
                        if (!allowed(generation, epoch, windowId)) { finishFrame(); return; }
                        if (!task.isSuccessful()) { status("OCR_FAILED", generation); finishFrame(); return; }
                        List<WechatScreenParser.TextLine> lines = OcrLines.fromText(task.getResult(), input.getWidth());
                        if (lines == null) { status("INPUT_LIMIT", generation); finishFrame(); return; }
                        int width = input.getWidth(), height = input.getHeight();
                        try {
                            worker.execute(() -> parseAndSave(lines, width, height, windowId, generation, epoch));
                        } catch (RejectedExecutionException stopped) { finishFrame(); }
                    } catch (RuntimeException invalid) {
                        status("OCR_FAILED", generation);
                        finishFrame();
                    } finally {
                        input.recycle();
                        closeRecognizerWhenStopped();
                    }
                });
            } catch (RuntimeException unavailable) {
                ocrProcessing = false;
                input.recycle();
                status("OCR_FAILED", generation);
                finishFrame();
            }
        }
    }

    private void parseAndSave(List<WechatScreenParser.TextLine> lines, int width, int height,
                              int windowId, long generation, long epoch) {
        try {
            if (!allowed(generation, epoch, windowId)) { session.reset(); return; }
            WechatScreenParser.Result result = session.parse(lines, width, height);
            if (result.transactions.isEmpty() && "NOT_BILL_LIST".equals(result.code)) session.reset();
            int inserted = 0;
            synchronized (VisibleCaptureSettings.class) {
                if (!allowed(generation, epoch, windowId)) { session.reset(); return; }
                for (Transaction transaction : result.transactions) {
                    // Revocation and session renewal use the same lock. Never replace a reviewed record.
                    if (!allowed(generation, epoch, windowId)) { session.reset(); return; }
                    if (store.insertScreenIfAbsent(transaction)) inserted++;
                }
                VisibleCaptureSettings.recordScan(this, result.transactions.size(), inserted, result.skippedRows);
                String code = inserted > 0 ? "BILLS_SAVED" : !result.transactions.isEmpty() ? "NO_NEW_BILLS"
                        : "NEEDS_MONTH".equals(result.code) ? "NEEDS_MONTH"
                        : result.skippedRows > 0 ? "ROWS_SKIPPED" : "NOT_BILL_SCREEN";
                VisibleCaptureSettings.recordStatus(this, code);
                VisibleCaptureSettings.changed(this);
            }
        } catch (RuntimeException invalid) {
            // Deliberately omit raw OCR, screenshots, transaction text and exception contents.
            session.reset();
            status("SAVE_FAILED", generation);
        } finally {
            lines.clear();
            finishFrame();
        }
    }

    private void status(String code, long generation) {
        synchronized (VisibleCaptureSettings.class) {
            if (alive && VisibleCaptureSettings.isEnabled(this)
                    && VisibleCaptureSettings.generation(this) == generation) {
                VisibleCaptureSettings.recordStatus(this, code);
            }
        }
    }

    private void invalidateSession() {
        frameEpoch.incrementAndGet();
        try { worker.execute(session::reset); } catch (RejectedExecutionException stopped) { /* Already stopped. */ }
    }

    private void finishFrame() { main.post(() -> { inFlight = false; closeRecognizerWhenStopped(); }); }

    private void closeRecognizerWhenStopped() {
        synchronized (recognizerLock) {
            if (!alive && !ocrProcessing && !recognizerClosed && recognizer != null) {
                recognizerClosed = true;
                recognizer.close();
            }
        }
    }

    @Override public void onDestroy() {
        alive = false;
        frameEpoch.incrementAndGet();
        main.removeCallbacksAndMessages(null);
        VisibleCaptureSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(settingsListener);
        VisibleCaptureSettings.disable(this);
        VisibleCaptureSettings.recordStatus(this, "SERVICE_STOPPED");
        worker.execute(() -> { session.reset(); store.close(); });
        worker.shutdown();
        closeRecognizerWhenStopped();
        super.onDestroy();
    }
}
